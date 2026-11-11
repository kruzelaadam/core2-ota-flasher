package cz.adamkruzela.core2ota.ota

import android.content.Context
import android.util.Log
import cz.adamkruzela.core2ota.ble.BondedCore2Devices
import cz.adamkruzela.core2ota.catalog.ComponentType
import cz.adamkruzela.core2ota.catalog.EpsonImage
import cz.adamkruzela.core2ota.catalog.FirmwareDownloader
import cz.adamkruzela.core2ota.catalog.FirmwareIds
import cz.adamkruzela.core2ota.catalog.FirmwarePart
import cz.adamkruzela.core2ota.catalog.FwVersion
import cz.adamkruzela.core2ota.catalog.PreparedPackage
import cz.adamkruzela.core2ota.mds.MdsEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.BitSet
import java.util.zip.CRC32

sealed class OtaProgress {
    data class Status(val message: String) : OtaProgress()

    data class Download(val percent: Int) : OtaProgress()

    /** Share of [partLabel] the watch has pulled with READ requests. */
    data class Transfer(val partLabel: String, val percent: Int) : OtaProgress()

    /** operation PROGRESS reported by the watch itself. */
    data class WatchProgress(val percent: Int) : OtaProgress()

    /** mcumgr upload of the Bluetooth package to the Nordic radio. */
    data class Dfu(val percent: Int, val stage: String) : OtaProgress()

    /** Version is announced; the watch has not started reading yet (often it reboots first). */
    data class Waiting(val message: String) : OtaProgress()

    data class Done(val message: String, val commLogFile: File? = null) : OtaProgress()

    data class Error(
        val message: String,
        val cause: Throwable? = null,
        val commLogFile: File? = null,
    ) : OtaProgress()
}

/**
 * `operation` of a TransferReq notification on Update/Transfer/Nordic/Request
 * (stock NordicOtaOperationType). Distinct from [ComponentType].
 */
enum class TransferOperation(val wire: Int) {
    INFO(0),
    READ(1),
    DFU(2),
    PROGRESS(3),
    FAIL_REASON(4),
    UNKNOWN(-1),
    ;

    companion object {
        fun of(wire: Int): TransferOperation = entries.firstOrNull { it.wire == wire && it != UNKNOWN } ?: UNKNOWN
    }
}

internal sealed class ServeResult {
    data object Continue : ServeResult()

    data object StartDfu : ServeResult()

    data object Completed : ServeResult()

    data class Failed(val message: String) : ServeResult()
}

/**
 * Watch-driven pull OTA, the same protocol the Suunto app runs for Core 2:
 *
 * 1. Subscribe `<serial>/Update/Transfer/Nordic/Request` through MDS/EventListener.
 * 2. PUT `Update/Transfer/Nordic/Version/Info` with the package description as is.
 * 3. Serve the watch's requests until it reports PROGRESS 100 or FAIL_REASON:
 *    INFO → PUT Version/Info again; READ → PUT `Data/Response` with the exact byte range;
 *    DFU → mcumgr upload of the type-3 package; PROGRESS → UI.
 *
 * Package bytes and description values are sent exactly as they are in the ZIP. The
 * session never rewrites versions, sizes or CRCs and never retries a flash on its own;
 * it only reconnects when the radio drops the link after the watch has started pulling.
 */
class OtaSession(
    private val context: Context,
    private val downloader: FirmwareDownloader = FirmwareDownloader(),
) {
    private val tag = "OtaSession"
    private val commLog = CommSessionLog(context)
    private val mds = MdsBridge(context, commLog)

    @Volatile
    private var dfu: BluetoothDfu? = null

    val commLogPath: String get() = commLog.file.absolutePath

    fun abort(reason: String = "user cancel"): File {
        dfu?.release()
        dfu = null
        mds.abort()
        return commLog.finish("ABORTED: $reason")
    }

    suspend fun flashOriginal(
        macAddress: String,
        downloadUri: String,
        cacheDir: File,
        onProgress: (OtaProgress) -> Unit,
    ) = run(macAddress, onProgress) {
        commLog.note("flashOriginal uri=$downloadUri")
        onProgress(OtaProgress.Status("Downloading firmware ZIP…"))
        val zipFile = File(cacheDir, FirmwareIds.fileNameFromUri(downloadUri))
        downloader.download(downloadUri, zipFile) { downloaded, total ->
            val pct = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
            onProgress(OtaProgress.Download(pct))
        }
        zipFile
    }

    suspend fun flashLocalZip(
        macAddress: String,
        zipFile: File,
        packageLabel: String? = null,
        onProgress: (OtaProgress) -> Unit,
    ) = run(macAddress, onProgress) {
        commLog.note("flashLocalZip file=${zipFile.name} label=$packageLabel")
        zipFile
    }

    private suspend fun run(
        macAddress: String,
        onProgress: (OtaProgress) -> Unit,
        obtainZip: suspend () -> File,
    ) = withContext(Dispatchers.IO) {
        try {
            flash(macAddress, obtainZip(), onProgress)
        } catch (c: CancellationException) {
            commLog.finish("CANCELLED")
            throw c
        } catch (t: Throwable) {
            Log.e(tag, "flash failed", t)
            val logFile = commLog.finish("FAILED: ${t.message}")
            onProgress(OtaProgress.Error(t.message ?: t.toString(), t, logFile))
        } finally {
            dfu?.release()
            dfu = null
            runCatching { mds.disconnect(macAddress) }
        }
    }

    private suspend fun flash(
        macAddress: String,
        zipFile: File,
        onProgress: (OtaProgress) -> Unit,
    ) {
        onProgress(OtaProgress.Status("Checking ${zipFile.name}…"))
        val pkg = downloader.prepare(zipFile, File(zipFile.parentFile, zipFile.nameWithoutExtension + "_unpacked"))
        logPackage(pkg)
        pkg.notes.forEach { onProgress(OtaProgress.Status("Note: $it")) }

        val engine = mds.engine
        if (engine.kind == MdsEngine.Kind.MOVESENSE) {
            onProgress(
                OtaProgress.Status(
                    "Engine: bundled Movesense. It cannot open the watch's OTA channel on Core 2; " +
                        "install the official Suunto app (it can stay force-stopped).",
                ),
            )
        }

        val bonded = BondedCore2Devices.list(context).firstOrNull { it.address.equals(macAddress, true) }
        var serial = connect(macAddress, bonded?.serial, onProgress)
        awaitWhiteboardReady(serial, onProgress)
        val notNewer = reportVersionGate(serial, pkg, onProgress)

        val server = TransferServer(pkg, onProgress)
        val events = Channel<Event>(Channel.UNLIMITED)
        var generation = 0
        var linkDown = false
        var dropsBeforeData = 0
        var watchdog: Job? = null
        var forwarder: Job? = null
        var subscription: MdsBridge.LiveSubscription? = null

        mds.pauseConnectedDevicesMirror()
        coroutineScope {
            suspend fun arm() {
                val gen = ++generation
                val uri = "suunto://$serial/Update/Transfer/Nordic/Request"
                onProgress(OtaProgress.Status("Opening the watch's OTA channel (Nordic/Request)…"))
                if (dropsBeforeData > 0) {
                    // The watch dumps stale packets for a moment after it comes back.
                    delay(1_500)
                }
                var last: Throwable? = null
                var opened: MdsBridge.LiveSubscription? = null
                for (attempt in 0 until 8) {
                    if (!mds.isLinkUp(macAddress)) {
                        error("Link dropped while opening the OTA channel. ${last?.message ?: ""}")
                    }
                    try {
                        opened = mds.openSubscription(uri, viaEventListener = true)
                        break
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        last = t
                        val retryable = t.message?.contains("404") == true || t.message?.contains("502") == true
                        commLog.note("Nordic/Request subscribe attempt ${attempt + 1} failed: ${t.message}")
                        if (!retryable || attempt == 7) {
                            error(
                                "The watch's OTA channel could not be opened: ${t.message}. " +
                                    "Engine: ${engine.kind} (${engine.description}). " +
                                    (if (engine.kind == MdsEngine.Kind.MOVESENSE) {
                                        "The bundled Movesense engine does not know this resource — " +
                                            "install the official Suunto app and select Engine: Suunto/Auto."
                                    } else {
                                        "Force-stop the official Suunto app, keep the watch awake and try again."
                                    }),
                            )
                        }
                        delay(1_000)
                    }
                }
                val subscriptionOpened = opened ?: error("Nordic/Request subscribe produced no subscription")
                subscription = subscriptionOpened
                forwarder =
                    launch {
                        for (r in subscriptionOpened.events) {
                            events.send(r.fold({ Event.Notify(gen, it) }, { Event.SubscriptionError(gen, it) }))
                        }
                    }
                mds.linkLostListener = { events.trySend(Event.LinkLost(gen, it)) }
                watchdog =
                    launch {
                        while (isActive) {
                            delay(2_000)
                            if (!mds.isLinkUp(macAddress)) {
                                events.send(Event.LinkLost(gen, "BLE link down"))
                                break
                            }
                        }
                    }
                putVersionInfo(serial, pkg.parts, onProgress)
                onProgress(
                    OtaProgress.Waiting(
                        if (dropsBeforeData == 0) {
                            "Version sent. Confirm the update on the watch and keep this app open. " +
                                "The watch restarts before it reads the firmware; the bar moves once it does."
                        } else {
                            "Reconnected. Waiting for the watch to read the firmware — keep this app open."
                        },
                    ),
                )
            }

            fun disarm() {
                generation++
                watchdog?.cancel()
                forwarder?.cancel()
                mds.linkLostListener = null
                subscription?.close()
                subscription = null
            }

            suspend fun reconnect(reason: String) {
                onProgress(OtaProgress.Status("Link lost ($reason) — waiting for the watch to come back…"))
                val deadline = System.currentTimeMillis() + RECONNECT_WINDOW_MS
                var attempt = 0
                var last: Throwable? = null
                while (System.currentTimeMillis() < deadline) {
                    attempt++
                    if (mds.isLinkUp(macAddress)) {
                        runCatching { mds.disconnect(macAddress) }
                    }
                    delay(4_000)
                    try {
                        serial = mds.connect(macAddress, bonded?.serial, timeoutMs = 45_000) {
                            onProgress(OtaProgress.Status("Reconnect $attempt: $it"))
                        }.serial.ifBlank { serial }
                        awaitWhiteboardReady(serial, onProgress)
                        commLog.note("reconnected attempt=$attempt after: $reason")
                        return
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        last = t
                        commLog.note("reconnect attempt=$attempt failed: ${t.message}")
                    }
                }
                error(
                    "The watch did not reconnect within ${RECONNECT_WINDOW_MS / 60_000} min after " +
                        "$reason (${last?.message}). ${server.summary()}",
                )
            }

            /** The watch drops BLE when it boots into the update. Serve it again when it returns. */
            suspend fun recoverLink(reason: String) {
                if (linkDown) return
                linkDown = true
                disarm()
                commLog.event("LINK_LOST", "$reason ${server.summary()}")
                if (!server.started && notNewer != null) {
                    error("The watch declined the package without reading any data. $notNewer")
                }
                if (server.dfuRunning) {
                    onProgress(OtaProgress.Status("Radio restarting for DFU — waiting for mcumgr to finish…"))
                    return
                }
                if (!server.started) {
                    dropsBeforeData++
                    if (dropsBeforeData > 8) {
                        error(
                            "The watch dropped the link $dropsBeforeData times before reading any firmware. " +
                                server.summary(),
                        )
                    }
                    onProgress(
                        OtaProgress.Waiting(
                            if (sealedSameAsInstalled(pkg)) {
                                "Watch closed the link without reading. This Application has the same " +
                                    "size and the same CRC as the installed image (every sealed image " +
                                    "CRCs to 558161692), so the watch will not download it. Rebuild with " +
                                    "--after-trailer 16."
                            } else {
                                "Watch restarted to begin the update. Keep this app open — " +
                                    "reconnecting to send the firmware ($dropsBeforeData)."
                            },
                        ),
                    )
                }
                reconnect(reason)
                linkDown = false
                arm()
            }

            try {
                arm()
                while (true) {
                    val idle =
                        when {
                            server.dfuRunning -> DFU_IDLE_MS
                            !server.started -> FIRST_REQUEST_MS
                            else -> TRANSFER_IDLE_MS
                        }
                    val ev =
                        withTimeoutOrNull(idle) { events.receive() }
                            ?: error(
                                (if (server.started) {
                                    "The watch went quiet for ${idle / 60_000} min. "
                                } else {
                                    "The watch never asked for data within ${idle / 60_000} min. " +
                                        (notNewer ?: "Did it offer the update? ")
                                }) + server.summary(),
                            )
                    if (ev.generation != generation && ev !is Event.FromDfu) continue
                    when (ev) {
                        is Event.Notify -> {
                            when (val r = server.handle(ev.raw, serial)) {
                                ServeResult.Continue -> Unit
                                ServeResult.StartDfu -> startDfu(macAddress, pkg, server, events, onProgress)
                                ServeResult.Completed -> {
                                    if (server.dfuRunning) {
                                        onProgress(OtaProgress.Status("Watch reports 100 % — waiting for the radio DFU to finish…"))
                                    } else {
                                        break
                                    }
                                }
                                is ServeResult.Failed -> error(r.message)
                            }
                        }
                        is Event.SubscriptionError -> {
                            if (!mds.isLinkUp(macAddress)) {
                                recoverLink("subscription error, link down")
                            } else {
                                error("OTA channel error: ${ev.error.message}. ${server.summary()}")
                            }
                        }
                        is Event.LinkLost -> recoverLink(ev.reason)
                        is Event.FromDfu -> {
                            when (val outcome = server.onDfu(ev.event)) {
                                null -> Unit
                                else -> {
                                    dfu?.release()
                                    dfu = null
                                    if (outcome is ServeResult.Failed) error(outcome.message)
                                    if (server.completed) break
                                    if (linkDown) {
                                        reconnect("radio DFU")
                                        linkDown = false
                                        arm()
                                    }
                                }
                            }
                        }
                    }
                }
            } finally {
                disarm()
            }
        }

        val summary = "Watch reported 100 %. ${server.summary()}"
        val logFile = commLog.finish("COMPLETED: $summary")
        onProgress(
            OtaProgress.Done(
                message = "Update finished on the watch. Check Settings → About on the watch.\n$summary",
                commLogFile = logFile,
            ),
        )
    }

    private sealed class Event(val generation: Int) {
        class Notify(generation: Int, val raw: String) : Event(generation)

        class SubscriptionError(generation: Int, val error: Throwable) : Event(generation)

        class LinkLost(generation: Int, val reason: String) : Event(generation)

        class FromDfu(val event: BluetoothDfu.Event) : Event(-1)
    }

    private fun startDfu(
        macAddress: String,
        pkg: PreparedPackage,
        server: TransferServer,
        events: Channel<Event>,
        onProgress: (OtaProgress) -> Unit,
    ) {
        if (server.dfuRunning) {
            commLog.note("DFU requested again while running — ignored")
            return
        }
        val part = pkg.parts.first { it.type == ComponentType.BLUETOOTH.wire }
        val (images, description) = BluetoothDfu.imagesFrom(part.file)
        commLog.event("DFU_START", "${part.label} ${part.filename} → $description")
        onProgress(OtaProgress.Dfu(0, "starting"))
        val d = BluetoothDfu(context, macAddress)
        dfu = d
        server.dfuStarted()
        try {
            d.start(images) { events.trySend(Event.FromDfu(it)) }
        } catch (t: Throwable) {
            events.trySend(Event.FromDfu(BluetoothDfu.Event.Failed("NONE", "start: ${t.message}")))
        }
    }

    /**
     * Serves READs and tracks what the watch has done. Logs each request compactly:
     * one line per READ with the block CRC as hex / unsigned / signed.
     */
    private inner class TransferServer(
        private val pkg: PreparedPackage,
        private val onProgress: (OtaProgress) -> Unit,
    ) {
        private val partsByType = pkg.parts.associateBy { it.type }
        private val coverage = pkg.parts.associate { it.type to BitSet(it.size.toInt()) }
        private val readsByType = mutableMapOf<Int, Int>()
        private val bytesByType = mutableMapOf<Int, Long>()
        private val t0 = System.currentTimeMillis()
        private var firstRequestLogged = false
        private var lastWatchProgress: Int? = null
        private var dfuResult: String? = null
        private var lastDfuPct = -1

        var started = false
            private set
        var completed = false
            private set
        var dfuRunning = false
            private set

        fun dfuStarted() {
            dfuRunning = true
            started = true
            dfuResult = "running"
        }

        suspend fun handle(raw: String, serial: String): ServeResult {
            val req = parseRequest(raw) ?: run {
                commLog.note("unparsed Nordic/Request notification: ${raw.take(300)}")
                return ServeResult.Continue
            }
            val opWire = req.optInt("operation", Int.MIN_VALUE)
            val op = TransferOperation.of(opWire)
            if (!firstRequestLogged) {
                firstRequestLogged = true
                commLog.event("FIRST_REQUEST", "operation=$opWire ($op) after ${System.currentTimeMillis() - t0} ms")
            }
            return when (op) {
                TransferOperation.INFO -> {
                    onProgress(OtaProgress.Status("Watch asked for INFO — sending Version/Info again"))
                    putVersionInfo(serial, pkg.parts, onProgress)
                    ServeResult.Continue
                }
                TransferOperation.READ -> read(req, serial)
                TransferOperation.DFU -> {
                    started = true
                    onProgress(OtaProgress.Status("Watch asked for the Bluetooth radio DFU"))
                    ServeResult.StartDfu
                }
                TransferOperation.PROGRESS -> {
                    val pct = if (req.has("progress") && !req.isNull("progress")) req.optInt("progress") else 0
                    lastWatchProgress = pct
                    commLog.event("PROGRESS", "progress=$pct raw=${req.opt("progress")}")
                    onProgress(OtaProgress.WatchProgress(pct.coerceIn(0, 100)))
                    if (pct == 100) {
                        completed = true
                        ServeResult.Completed
                    } else {
                        ServeResult.Continue
                    }
                }
                TransferOperation.FAIL_REASON -> {
                    val reason = req.opt("failReason")
                    commLog.event("FAIL_REASON", "failReason=$reason request=$req")
                    ServeResult.Failed("Watch reported FAIL_REASON failReason=${reason ?: "null"}. ${summary()}")
                }
                TransferOperation.UNKNOWN -> {
                    commLog.note("unknown operation=$opWire request=$req")
                    ServeResult.Continue
                }
            }
        }

        private suspend fun read(req: JSONObject, serial: String): ServeResult {
            started = true
            val fr = req.optJSONObject("fileRequest") ?: return ServeResult.Failed("READ without fileRequest: $req")
            val type = fr.optInt("fileType", -1)
            val offset = fr.optLong("fileOffset", -1)
            val size = fr.optLong("fileSize", -1)
            val part =
                partsByType[type]
                    ?: return ServeResult.Failed("Watch asked for fileType=$type, which this package does not contain.")
            if (offset < 0 || size < 0 || offset + size > part.size) {
                return ServeResult.Failed(
                    "Watch asked for ${part.label} offset=$offset size=$size beyond its ${part.size} B " +
                        "(the Suunto app refuses such reads too).",
                )
            }
            val chunk = readRange(part.file, offset, size.toInt())
            val crc = CRC32().apply { update(chunk) }.value
            val blockData = JSONArray()
            for (b in chunk) blockData.put(b.toInt() and 0xff)
            val body =
                JSONObject()
                    .put(
                        "TransferDataRes",
                        JSONObject()
                            .put("result", 0)
                            .put(
                                "fileRequest",
                                JSONObject()
                                    .put("fileType", type)
                                    .put("fileOffset", offset)
                                    .put("fileSize", size),
                            )
                            .put("blockCrc", crc.toInt())
                            .put("blockData", blockData),
                    )
                    .toString()
            val n = (readsByType[type] ?: 0) + 1
            readsByType[type] = n
            bytesByType[type] = (bytesByType[type] ?: 0L) + size
            commLog.event(
                "READ",
                "type=$type ${part.label} #$n offset=$offset size=$size " +
                    "crc=0x%08X/%d/%d".format(crc, crc, crc.toInt()),
            )
            mds.putQuiet("suunto://$serial/Update/Transfer/Nordic/Data/Response", body, timeoutMs = 30_000)
            val bits = coverage.getValue(type)
            if (size > 0) bits.set(offset.toInt(), (offset + size).toInt())
            val pct = if (part.size > 0) (bits.cardinality() * 100L / part.size).toInt() else 100
            onProgress(OtaProgress.Transfer(part.label, pct))
            return ServeResult.Continue
        }

        /** Returns a terminal result once mcumgr finishes, else null. */
        fun onDfu(e: BluetoothDfu.Event): ServeResult? =
            when (e) {
                is BluetoothDfu.Event.StateChanged -> {
                    commLog.event("DFU_STATE", "${e.from} → ${e.to}")
                    onProgress(OtaProgress.Dfu(lastDfuPct.coerceAtLeast(0), e.to.lowercase()))
                    null
                }
                is BluetoothDfu.Event.Upload -> {
                    val pct = if (e.total > 0) (e.sent * 100L / e.total).toInt() else 0
                    if (pct != lastDfuPct) {
                        lastDfuPct = pct
                        if (pct % 10 == 0) commLog.event("DFU_UPLOAD", "${e.sent}/${e.total}")
                        onProgress(OtaProgress.Dfu(pct, "upload"))
                    }
                    null
                }
                BluetoothDfu.Event.Completed -> {
                    dfuRunning = false
                    dfuResult = "completed"
                    commLog.event("DFU_DONE", "completed")
                    onProgress(OtaProgress.Dfu(100, "done"))
                    ServeResult.Continue
                }
                is BluetoothDfu.Event.Failed -> {
                    dfuRunning = false
                    dfuResult = "failed in ${e.state}: ${e.message}"
                    commLog.event("DFU_FAILED", dfuResult!!)
                    ServeResult.Failed("Bluetooth radio DFU failed in ${e.state}: ${e.message}. ${summary()}")
                }
                is BluetoothDfu.Event.Canceled -> {
                    dfuRunning = false
                    dfuResult = "canceled in ${e.state}"
                    commLog.event("DFU_CANCELED", e.state)
                    ServeResult.Failed("Bluetooth radio DFU was canceled (${e.state}). ${summary()}")
                }
            }

        fun summary(): String =
            buildString {
                append("Served: ")
                append(
                    pkg.parts.joinToString { p ->
                        val reads = readsByType[p.type] ?: 0
                        val covered = coverage.getValue(p.type).cardinality()
                        "${p.label} $reads reads ${bytesByType[p.type] ?: 0} B (${covered}/${p.size} B unique)"
                    },
                )
                append(". Watch progress: ${lastWatchProgress?.let { "$it %" } ?: "none"}.")
                append(" Radio DFU: ${dfuResult ?: "not requested"}.")
                append(" Elapsed ${(System.currentTimeMillis() - t0) / 1000} s.")
            }
    }

    private fun parseRequest(raw: String): JSONObject? {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (o.has("operation")) return o
        for (key in listOf("Body", "body", "Content", "content")) {
            o.optJSONObject(key)?.let { inner ->
                if (inner.has("operation")) return inner
                inner.optJSONObject("TransferReq")?.let { return it }
            }
            o.optString(key).takeIf { it.startsWith("{") }?.let { s ->
                runCatching { JSONObject(s) }.getOrNull()?.takeIf { it.has("operation") }?.let { return it }
            }
        }
        return o.optJSONObject("TransferReq")
    }

    private fun readRange(file: File, offset: Long, size: Int): ByteArray {
        val buf = ByteArray(size)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            raf.readFully(buf)
        }
        return buf
    }

    private fun logPackage(pkg: PreparedPackage) {
        commLog.note(
            "package ${pkg.zipFile.name} version=${pkg.packageVersion} sha256=${pkg.zipSha256} " +
                "engine=${mds.engine.kind}",
        )
        for (p in pkg.parts) {
            commLog.note(
                "part type=${p.type} ${p.label} file=${p.filename} size=${p.size} " +
                    "crc32=0x%08X/%d/%d ".format(p.crc32 and 0xffffffffL, p.crc32 and 0xffffffffL, p.crc32.toInt()) +
                    "sha256=${p.sha256} ${p.detail}",
            )
        }
        pkg.notes.forEach { commLog.note("package note: $it") }
    }

    /**
     * PUT Update/Transfer/Nordic/Version/Info — stock TransferVersion body built from the
     * description entries in their original order and with their original values.
     */
    private suspend fun putVersionInfo(
        serial: String,
        parts: List<FirmwarePart>,
        onProgress: (OtaProgress) -> Unit,
    ) {
        val versionList = JSONArray()
        for (p in parts) {
            versionList.put(
                JSONObject()
                    .put("type", p.type)
                    .put("version", p.version)
                    .put("size", p.size)
                    .put("crc32", p.crc32),
            )
        }
        val body = JSONObject().put("TransferVersionRes", JSONObject().put("versionList", versionList)).toString()
        onProgress(OtaProgress.Status("PUT Version/Info: ${parts.joinToString { it.label }}"))
        try {
            mds.put("suunto://$serial/Update/Transfer/Nordic/Version/Info", body)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            error("Version/Info PUT failed: ${t.message ?: t}")
        }
    }

    private suspend fun connect(
        macAddress: String,
        serialHint: String?,
        onProgress: (OtaProgress) -> Unit,
    ): String {
        onProgress(OtaProgress.Status("Connecting… Force-stop the official Suunto app first and keep the watch awake."))
        var last: Throwable? = null
        repeat(2) { attempt ->
            commLog.note("connectAttempt=${attempt + 1} mac=$macAddress")
            try {
                val c =
                    mds.connect(macAddress = macAddress, serialHint = serialHint, timeoutMs = 55_000) {
                        onProgress(OtaProgress.Status(it))
                    }
                val serial = c.serial.ifBlank { serialHint ?: error("MDS connected but serial empty") }
                commLog.note("connected serial=$serial via=${c.via}")
                return serial
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                last = t
                commLog.note("connectAttempt=${attempt + 1} failed: ${t.message}")
                runCatching { mds.disconnect(macAddress) }
                if (attempt == 0) {
                    onProgress(OtaProgress.Status("Connect failed — waiting 5 s before the second attempt…"))
                    delay(5_000)
                }
            }
        }
        throw last ?: IllegalStateException("MDS connect failed")
    }

    private suspend fun awaitWhiteboardReady(
        serial: String,
        onProgress: (OtaProgress) -> Unit,
    ) {
        val uri = "suunto://$serial/Info"
        repeat(25) { attempt ->
            val raw = runCatching { mds.get(uri, "{}") }.getOrNull()
            if (raw != null) {
                commLog.note("whiteboardReady attempt=$attempt")
                return
            }
            onProgress(OtaProgress.Status("Waiting for the watch to answer /Info (${attempt + 1}/25)…"))
            delay(400)
        }
        error("The watch never answered $uri. Force-stop the Suunto app, toggle Bluetooth, wake the watch.")
    }

    /**
     * A sealed image always CRCs to the residue, so the watch cannot tell it from the
     * installed one when the length matches too. A trailer suffix changes both.
     */
    private fun sealedSameAsInstalled(pkg: PreparedPackage): Boolean {
        val app = pkg.parts.firstOrNull { it.type == ComponentType.APPLICATION.wire } ?: return false
        return (app.crc32 and 0xffffffffL) == EpsonImage.CRC32_RESIDUE
    }

    /**
     * Information only: the watch decides whether to take the package. Returns an
     * explanation when the offered Application is not newer than the installed one.
     */
    private suspend fun reportVersionGate(
        serial: String,
        pkg: PreparedPackage,
        onProgress: (OtaProgress) -> Unit,
    ): String? {
        val installed = probeInstalledVersion(serial)
        val offered = FwVersion.parse(pkg.parts.firstOrNull { it.type == ComponentType.APPLICATION.wire }?.version)
        commLog.note("versionGate installed=${installed ?: "?"} offeredApplication=${offered ?: "?"}")
        val notNewer =
            if (installed != null && offered != null && offered <= installed) {
                "The watch runs $installed and the package offers Application $offered. Core 2 only " +
                    "installs an Application newer than the one it runs, so it reports \"up to date\". " +
                    "An official package flashes only when it is newer; a modified one needs a higher " +
                    "Application version in firmwareDescription.json (see FW_AGENT_PACKAGING_RULES)."
            } else {
                null
            }
        onProgress(
            OtaProgress.Status(
                notNewer?.let { "Watch runs $installed, package Application $offered — not newer; expect \"up to date\"." }
                    ?: "Watch runs ${installed ?: "?"}, package Application ${offered ?: "?"}.",
            ),
        )
        return notNewer
    }

    private suspend fun probeInstalledVersion(serial: String): FwVersion? {
        for (uri in listOf("suunto://$serial/Info", "suunto://$serial/Update/Info", "suunto://$serial/Device/Info")) {
            val raw = runCatching { mds.get(uri, "{}") }.getOrNull() ?: continue
            parseVersionFromJson(raw)?.let { return it }
        }
        return null
    }

    private fun parseVersionFromJson(raw: String): FwVersion? {
        val candidates = ArrayList<String>()

        fun walk(node: Any?, depth: Int = 0) {
            if (depth > 6 || node == null) return
            when (node) {
                is JSONObject ->
                    node.keys().forEach { k ->
                        val v = node.opt(k)
                        if (k.contains("version", true) || k.contains("firmware", true) || k.equals("sw", true)) {
                            if (v is String || v is Number) candidates += v.toString()
                        }
                        walk(v, depth + 1)
                    }
                is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), depth + 1)
                is String -> if (FwVersion.parse(node) != null) candidates += node
            }
        }
        runCatching { walk(JSONObject(raw)) }
        val parsed = candidates.mapNotNull { FwVersion.parse(it) }.sortedDescending()
        parsed.firstOrNull { it.major == 1 && it.minor >= 10 }?.let { return it }
        for (c in candidates) {
            val n = c.toLongOrNull() ?: continue
            if (n in 0x01000000..0x01FFFFFF) return FwVersion.parsePacked(n)
        }
        return parsed.firstOrNull()
    }

    private companion object {
        const val FIRST_REQUEST_MS = 10 * 60_000L
        const val TRANSFER_IDLE_MS = 3 * 60_000L
        const val DFU_IDLE_MS = 6 * 60_000L
        const val RECONNECT_WINDOW_MS = 4 * 60_000L
    }
}
