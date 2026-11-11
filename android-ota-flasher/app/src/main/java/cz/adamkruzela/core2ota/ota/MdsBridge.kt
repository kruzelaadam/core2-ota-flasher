package cz.adamkruzela.core2ota.ota

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.movesense.mds.Logger
import com.movesense.mds.Mds
import com.movesense.mds.MdsConnectionListener
import com.movesense.mds.MdsException
import com.movesense.mds.MdsNotificationListener
import com.movesense.mds.MdsResponseListener
import com.movesense.mds.MdsSubscription
import cz.adamkruzela.core2ota.ble.BleManagerGattHack
import cz.adamkruzela.core2ota.ble.BleSerialInject
import cz.adamkruzela.core2ota.mds.KompostiConfig
import cz.adamkruzela.core2ota.mds.MdsEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Thin coroutine wrapper around Movesense MDS (libmds.so).
 * Every Whiteboard call is written to [commLog] in full (no truncation).
 * Cancel / timeout always disconnects so the UI is not stuck on BLE.
 */
class MdsBridge(
    context: Context,
    private val commLog: CommSessionLog? = null,
) {
    private val tag = "MdsBridge"
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var activeMac: String? = null

    private var connectedDevicesSub: MdsSubscription? = null
    private var probeJob: Job? = null
    private var bootstrapJob: Job? = null
    private var logcatMirror: MdsLogcatMirror? = null

    val mds: Mds

    /** Fires when BLE/Whiteboard drops after [connect] returned. Cleared on disconnect. */
    @Volatile
    var linkLostListener: ((String) -> Unit)? = null

    val engine: MdsEngine.Loaded

    init {
        engine = MdsEngine.ensureLoaded(appContext)
        commLog?.note(
            "MDS engine=${engine.kind} ${engine.description} sha256=${engine.sha256 ?: "?"}" +
                (engine.note?.let { " NOTE: $it" } ?: ""),
        )
        // KompostiSettings.xml must exist before SDSInternalWrapper.startService().
        KompostiConfig.install(appContext) { commLog?.note(it) }
        runCatching { Logger.setPipeToOSLoggingEnabled(true) }
            .onFailure { Log.w(tag, "setPipeToOSLoggingEnabled failed: $it") }
        // Build MDS first — touching BleManager before startService broke 0.3.7.
        mds =
            Mds.builder()
                .validateJson(false)
                .operationTimeoutMs(120_000)
                .responseHandler(mainHandler)
                .logLevel(Logger.LogLevel.TRACE)
                .build(appContext)
        commLog?.note("MDS built with LogLevel.TRACE + pipeToOSLoggingEnabled")
        BleManagerGattHack.ensureSuuntoCustomMapped { commLog?.note(it) }
        BleManagerGattHack.dumpMappedServices { commLog?.note(it) }
        BleSerialInject.install { commLog?.note(it) }
        subscribeConnectedDevicesMirror()
        if (commLog != null) {
            logcatMirror = MdsLogcatMirror(commLog).also { it.start() }
        }
    }

    data class Connected(
        val macAddress: String,
        /**
         * Host for `suunto://{host}/…` URIs — must be the 12-hex device serial.
         * Numeric wbId (`10000001`) is rejected locally by SDS (404); it is not a
         * REST host.
         */
        val serial: String,
        val via: String,
        val deviceSerial: String? = null,
    )

    /**
     * @param serialHint serial parsed from BLE name (`Suunto Core 2 {serial}`). Used as
     * late fallback only — early probing floods the MDS work queue.
     */
    suspend fun connect(
        macAddress: String,
        serialHint: String? = null,
        timeoutMs: Long = 60_000,
        onPhase: ((String) -> Unit)? = null,
    ): Connected {
        val deferred = CompletableDeferred<Connected>()
        val t0 = System.currentTimeMillis()
        var sawBle = false
        activeMac = macAddress
        probeJob?.cancel()
        bootstrapJob?.cancel()
        Log.i(tag, "MDS connect $macAddress serialHint=$serialHint")
        commLog?.event(
            "CONNECT",
            "mac=$macAddress serialHint=$serialHint timeoutMs=$timeoutMs",
        )
        onPhase?.invoke("MDS connecting BLE $macAddress… (Cancel aborts)")
        // Fill empty [wbId,"",""] triples before native HELLO (0.3.11 proved wbId REST is 404).
        BleSerialInject.setPending(macAddress, serialHint) { commLog?.note(it) }
        BleManagerGattHack.resetAddressMapHandles { commLog?.note(it) }

        fun failConnect(reason: String) {
            if (deferred.isCompleted) return
            probeJob?.cancel()
            bootstrapJob?.cancel()
            commLog?.event("CONNECT_FAIL", reason)
            deferred.completeExceptionally(IllegalStateException(reason))
        }

        fun tryComplete(
            uriHost: String,
            via: String,
            deviceSerial: String? = null,
        ) {
            if (deferred.isCompleted) return
            val c =
                Connected(
                    macAddress = macAddress,
                    serial = uriHost,
                    via = via,
                    deviceSerial = deviceSerial,
                )
            commLog?.event(
                "CONNECT_OK",
                "mac=$macAddress uriHost=$uriHost deviceSerial=${deviceSerial ?: "—"} " +
                    "via=$via elapsedMs=${System.currentTimeMillis() - t0}",
            )
            onPhase?.invoke(
                "MDS ready host=$uriHost" +
                    (deviceSerial?.let { " serial=$it" } ?: "") +
                    " ($via)",
            )
            deferred.complete(c)
            probeJob?.cancel()
            bootstrapJob?.cancel()
        }

        // BleManager often calls disconnectCb without MDS onDisconnect (seen 0.3.28 secret-menu
        // log: HELLO_ACK → disconnectCb → local POST ConnectedDevices → endless 502 probes).
        BleSerialInject.setWbDisconnectListener { wb ->
            val mapped = BleManagerGattHack.wbAddressFor(macAddress)
            if (mapped != null && mapped != wb) return@setWbDisconnectListener
            if (deferred.isCompleted) return@setWbDisconnectListener
            commLog?.event(
                "WB_DISCONNECT_CB",
                "wb=$wb mac=$macAddress mapped=${mapped ?: "—"} sawBle=$sawBle",
            )
            failConnect(
                "Whiteboard BLE dropped after HELLO (disconnectCb wb=$wb). " +
                    "Force-stop Suunto, wake watch, retry.",
            )
        }

        mds.connect(
            macAddress,
            object : MdsConnectionListener {
                override fun onConnect(mac: String) {
                    sawBle = true
                    Log.i(tag, "onConnect $mac")
                    val wb = BleManagerGattHack.wbAddressFor(mac)
                    commLog?.event(
                        "CONNECT_STATE",
                        "onConnect mac=$mac wbAddress=${wb ?: "—"} " +
                            "(serial inject armed; MDS REST host must be 12-hex serial, " +
                            "NOT wbId — 0.3.11 proved suunto://{wbId}/… is local 404)",
                    )
                    onPhase?.invoke(
                        "BLE connected — waiting SDS serial map / ConnectedDevices…",
                    )
                    bootstrapJob?.cancel()
                    bootstrapJob =
                        scope.launch {
                            // Wait for HELLO_ACK + first WB GETs (0.3.9–0.3.12 window).
                            delay(1_200)
                            if (!isActive || deferred.isCompleted) return@launch
                            if (!BleManagerGattHack.isBleLinkUp(macAddress)) {
                                failConnect(
                                    "BLE link gone before ConnectedDevices POST " +
                                        "(watch dropped WB after HELLO).",
                                )
                                return@launch
                            }

                            val hint = serialHint?.trim().orEmpty()
                            // 0.3.12: POST ConnectedDevices is what actually mapped serial
                            // (201 + onConnectionComplete). Do it first — do not wait on
                            // empty HELLO serial / failed probes.
                            if (hint.isNotEmpty()) {
                                onPhase?.invoke("POST MDS/ConnectedDevices Serial=$hint…")
                                runCatching {
                                    tryPostConnectedDevices(hint, mac)
                                }.onFailure {
                                    commLog?.note("ConnectedDevices POST failed: ${it.message}")
                                }
                                // onConnectionComplete may already have completed us.
                                if (deferred.isCompleted) return@launch
                                delay(400)
                                if (deferred.isCompleted) return@launch
                                if (runCatching { probeWhiteboard(hint) }.getOrDefault(false)) {
                                    tryComplete(hint, "serial-after-ConnectedDevices-POST", hint)
                                    return@launch
                                }
                            }

                            var n = 0
                            while (isActive && !deferred.isCompleted) {
                                n++
                                delay(2_000)
                                if (!isActive || deferred.isCompleted) return@launch
                                if (!BleManagerGattHack.isBleLinkUp(macAddress)) {
                                    failConnect(
                                        "BLE link dropped while polling ConnectedDevices.",
                                    )
                                    return@launch
                                }
                                onPhase?.invoke("Polling MDS ConnectedDevices ($n)…")
                                val mapped =
                                    runCatching { pollConnectedDevicesSerial() }.getOrNull()
                                if (!mapped.isNullOrBlank()) {
                                    tryComplete(mapped, "poll-ConnectedDevices", mapped)
                                    return@launch
                                }
                                if (hint.isNotEmpty()) {
                                    runCatching { tryPostConnectedDevices(hint, mac) }
                                    if (deferred.isCompleted) return@launch
                                    if (runCatching { probeWhiteboard(hint) }.getOrDefault(false)) {
                                        tryComplete(hint, "serialHint-retry", hint)
                                        return@launch
                                    }
                                }
                            }
                        }
                }

                override fun onConnectionComplete(mac: String, serial: String) {
                    Log.i(tag, "onConnectionComplete mac=$mac serial=$serial")
                    // Do NOT complete yet: ConnectedDevices 201 often fires before HELLO/WB
                    // resources exist. Completing early → every suunto://{serial}/… is 404
                    // and GATT then dies with CCCD 133 (seen 2026-11-09 00:24 log).
                    // Also: POST ConnectedDevices is local SDS — it can fire AFTER BleManager
                    // already disconnectCb'd (0.3.28 secret-menu log) → endless 502.
                    bootstrapJob?.cancel()
                    bootstrapJob =
                        scope.launch {
                            if (!BleManagerGattHack.isBleLinkUp(macAddress)) {
                                failConnect(
                                    "SDS mapped serial=$serial but BLE already down — " +
                                        "ConnectedDevices was local-only.",
                                )
                                return@launch
                            }
                            onPhase?.invoke(
                                "SDS mapped serial=$serial — waiting until WB answers…",
                            )
                            commLog?.note(
                                "onConnectionComplete serial=$serial — probing WB before CONNECT_OK",
                            )
                            var consecutiveFail = 0
                            repeat(24) { attempt ->
                                if (!isActive || deferred.isCompleted) return@launch
                                if (!BleManagerGattHack.isBleLinkUp(macAddress)) {
                                    failConnect(
                                        "BLE dropped during WB probe after serial=$serial",
                                    )
                                    return@launch
                                }
                                if (runCatching { probeWhiteboard(serial) }.getOrDefault(false)) {
                                    tryComplete(
                                        serial,
                                        "onConnectionComplete+probe#$attempt",
                                        serial,
                                    )
                                    return@launch
                                }
                                consecutiveFail++
                                // Fast-fail after a streak of 502s with no live answers —
                                // lets SecretMenu/OTA retry connect instead of hanging 55s.
                                // A watch that just reset its link answers locally with 502 for
                                // several seconds before the new HELLO finishes. 6 was ~2 s and
                                // aborted that reconnect; the next attempt then succeeded.
                                if (consecutiveFail >= 20) {
                                    failConnect(
                                        "Whiteboard 502 streak after SDS serial=$serial " +
                                            "(link up=${BleManagerGattHack.isBleLinkUp(macAddress)}). " +
                                            "Force-stop Suunto, retry.",
                                    )
                                    return@launch
                                }
                                onPhase?.invoke(
                                    "Whiteboard not ready yet (probe ${attempt + 1}/24)…",
                                )
                                delay(400)
                            }
                            if (!deferred.isCompleted) {
                                failConnect(
                                    "Whiteboard never answered after SDS mapped " +
                                        "serial=$serial. Force-stop Suunto, toggle BT, retry.",
                                )
                            }
                        }
                }

                override fun onError(error: MdsException) {
                    Log.e(tag, "connect error", error)
                    commLog?.error("CONNECT", macAddress, error, System.currentTimeMillis() - t0)
                    if (!deferred.isCompleted) deferred.completeExceptionally(error)
                }

                override fun onDisconnect(mac: String) {
                    Log.w(tag, "onDisconnect $mac")
                    commLog?.event("DISCONNECT", "mac=$mac sawBle=$sawBle")
                    if (deferred.isCompleted) {
                        linkLostListener?.invoke("MDS onDisconnect $mac")
                        return
                    }
                    failConnect(
                        "MDS disconnected before Whiteboard ready (sawBle=$sawBle)",
                    )
                }
            },
        )
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            probeJob?.cancel()
            bootstrapJob?.cancel()
            BleSerialInject.setWbDisconnectListener(null)
            disconnect(macAddress)
            commLog?.error("CONNECT", macAddress, e, System.currentTimeMillis() - t0)
            val hint =
                if (sawBle) {
                    "BLE linked but Whiteboard never ready " +
                        "(no ConnectedDevices POST). " +
                        "Force-stop Suunto, toggle BT, wake watch. Share this log."
                } else {
                    "No BLE link. Watch nearby, bonded, not used by another app."
                }
            throw IllegalStateException(
                "MDS connect timeout for $macAddress after ${timeoutMs}ms. $hint",
                e,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            probeJob?.cancel()
            bootstrapJob?.cancel()
            BleSerialInject.setWbDisconnectListener(null)
            disconnect(macAddress)
            commLog?.event("CONNECT_CANCELLED", "mac=$macAddress sawBle=$sawBle")
            throw e
        } catch (e: Throwable) {
            // failConnect / onError path — drop GATT so callers can retry cleanly.
            probeJob?.cancel()
            bootstrapJob?.cancel()
            BleSerialInject.setWbDisconnectListener(null)
            runCatching { disconnect(macAddress) }
            throw e
        } finally {
            BleSerialInject.setWbDisconnectListener(null)
        }
    }

    /**
     * Attempt to register serial locally. Body mirrors SDS ConnectedDevices event shape.
     * May return "Serial not found" if discovery never listed the device — that is OK.
     */
    private suspend fun tryPostConnectedDevices(
        serial: String,
        macAddress: String,
    ) {
        val dashed = macAddress.replace(':', '-').uppercase()
        val body =
            JSONObject()
                .put("Serial", serial)
                .put(
                    "DeviceInfo",
                    JSONObject().put(
                        "addressInfo",
                        JSONArray().put(
                            JSONObject().put("name", "BLE").put("address", dashed),
                        ),
                    ),
                ).toString()
        commLog?.note("POST ConnectedDevices Serial=$serial address=$dashed")
        val resp = post("suunto://MDS/ConnectedDevices", body, timeoutMs = 8_000)
        commLog?.note("POST ConnectedDevices → ${resp.take(240)}")
    }

    private suspend fun probeWhiteboard(host: String): Boolean {
        // Never probe numeric wbId as REST host — SDS 404s locally (0.3.11).
        if (host.length < 12 && host.toLongOrNull(16) != null) {
            commLog?.note("skip probe of wbId-like host=$host (not an MDS REST serial)")
            return false
        }
        // Core 2 BLE: Nordic serves /Info reliably. Application /Device/* usually
        // 404s with [wb] Failed to get resource and hammering them can drop WB
        // (seen 0.3.30 secret-menu log). Probe /Info first.
        val uris =
            listOf(
                "suunto://$host/Info",
                "suunto://$host/Update/Info",
                "suunto://$host/net/Core2/Info",
                "suunto://$host/Device/Info/Serial",
            )
        for (uri in uris) {
            val ok =
                runCatching {
                    val resp = get(uri, "{}", timeoutMs = 8_000)
                    commLog?.note("probeOK $uri → ${resp.take(180)}")
                    true
                }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    /**
     * After CONNECT_OK: probe which Update Whiteboard paths exist. Core 2 0.3.12 had
     * /Info OK but /Update/Force/Tag → [wb] Failed to get resource.
     */
    suspend fun discoverUpdatePaths(serial: String): List<String> {
        val paths =
            listOf(
                "/Info",
                "/Update",
                "/Update/Info",
                "/Update/Force",
                "/Update/Force/Tag",
                "/Update/Suggest",
                "/Update/Select",
                "/Update/InstallSelectedFirmware",
                "/Update/Transfer/Nordic/Version/Info",
                "/Update/Transfer/Nordic/Request",
                "/Device/Info",
                "/Device/Info/Serial",
            )
        val ok = mutableListOf<String>()
        for (p in paths) {
            val uri = "suunto://$serial$p"
            val result =
                runCatching { get(uri, "{}", timeoutMs = 6_000) }
                    .fold(
                        onSuccess = { "OK ${it.take(120)}" },
                        onFailure = { "FAIL ${it.message?.take(100)}" },
                    )
            commLog?.note("discover $uri → $result")
            if (result.startsWith("OK")) ok += p
        }
        // Also try PUT Force/Tag variants (GET may 404 while PUT works, or vice versa).
        for ((path, body) in listOf(
            "/Update/Force/Tag" to JSONObject().put("Tag", true).toString(),
            "/Update/Force" to JSONObject().put("Tag", true).toString(),
            "/Update/Force/Tag" to JSONObject().put("newValue", true).toString(),
        )) {
            val uri = "suunto://$serial$path"
            val result =
                runCatching { put(uri, body, timeoutMs = 6_000) }
                    .fold(
                        onSuccess = { "PUT_OK ${it.take(80)}" },
                        onFailure = { "PUT_FAIL ${it.message?.take(100)}" },
                    )
            commLog?.note("discoverPUT $uri body=$body → $result")
            if (result.startsWith("PUT_OK")) ok += "PUT$path"
        }
        return ok
    }

    /**
     * After HELLO_ACK, SDS should eventually POST ConnectedDevices. Also poll GET
     * in case the EventListener subscription missed the notification.
     */
    private suspend fun pollConnectedDevicesSerial(): String? {
        for (uri in listOf(
            "suunto://MDS/ConnectedDevices",
            "suunto://MDS/ConnectingDevices",
        )) {
            val raw =
                runCatching { get(uri, "{}", timeoutMs = 5_000) }
                    .onFailure { /* 404/empty while handshaking is normal */ }
                    .getOrNull()
                    ?: continue
            commLog?.note("poll $uri → ${raw.take(400)}")
            extractSerial(raw)?.let { return it }
        }
        return null
    }

    private fun extractSerial(raw: String): String? {
        fun fromObj(o: JSONObject): String? {
            o.optString("Serial").takeIf { it.isNotBlank() }?.let { return it }
            o.optString("serial").takeIf { it.isNotBlank() }?.let { return it }
            o.optJSONObject("Body")?.let { body ->
                body.optString("Serial").takeIf { it.isNotBlank() }?.let { return it }
                body.optJSONArray("ConnectedDevices")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        fromObj(arr.getJSONObject(i))?.let { return it }
                    }
                }
                body.optJSONArray("Devices")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        fromObj(arr.getJSONObject(i))?.let { return it }
                    }
                }
                body.optJSONArray("devices")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        fromObj(arr.getJSONObject(i))?.let { return it }
                    }
                }
            }
            o.optJSONArray("ConnectedDevices")?.let { arr ->
                for (i in 0 until arr.length()) {
                    fromObj(arr.getJSONObject(i))?.let { return it }
                }
            }
            o.optJSONArray("Devices")?.let { arr ->
                for (i in 0 until arr.length()) {
                    fromObj(arr.getJSONObject(i))?.let { return it }
                }
            }
            return null
        }
        return runCatching {
            val trimmed = raw.trim()
            when {
                trimmed.startsWith("{") -> fromObj(JSONObject(trimmed))
                trimmed.startsWith("[") -> {
                    val arr = JSONArray(trimmed)
                    for (i in 0 until arr.length()) {
                        fromObj(arr.getJSONObject(i))?.let { return@runCatching it }
                    }
                    null
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun subscribeConnectedDevicesMirror() {
        runCatching {
            connectedDevicesSub?.unsubscribe()
            connectedDevicesSub =
                mds.subscribe(
                    Mds.URI_EVENTLISTENER,
                    """{"Uri":"suunto://MDS/ConnectedDevices"}""",
                    object : MdsNotificationListener {
                        override fun onNotification(data: String) {
                            Log.i(tag, "ConnectedDevices notify ${data.take(300)}")
                            commLog?.notify("suunto://MDS/ConnectedDevices", data)
                        }

                        override fun onError(error: MdsException) {
                            Log.w(tag, "ConnectedDevices sub error", error)
                            commLog?.error("SUBSCRIBE", "ConnectedDevices", error, 0)
                        }
                    },
                )
            commLog?.note("Subscribed MDS EventListener → ConnectedDevices (mirror)")
        }.onFailure {
            commLog?.note("ConnectedDevices mirror subscribe failed: $it")
        }
    }

    /** Drop ConnectedDevices EventListener so Nordic/Request can own the EL slot. */
    fun pauseConnectedDevicesMirror() {
        runCatching { connectedDevicesSub?.unsubscribe() }
        connectedDevicesSub = null
        commLog?.note("ConnectedDevices EventListener paused for Nordic/Request")
    }

    fun resumeConnectedDevicesMirror() {
        subscribeConnectedDevicesMirror()
    }

    fun isLinkUp(macAddress: String): Boolean = BleManagerGattHack.isBleLinkUp(macAddress)

    fun disconnect(macAddress: String? = activeMac) {
        val mac = macAddress ?: return
        linkLostListener = null
        probeJob?.cancel()
        bootstrapJob?.cancel()
        BleSerialInject.setWbDisconnectListener(null)
        BleSerialInject.clearPending()
        commLog?.event("DISCONNECT_REQ", "mac=$mac")
        runCatching { mds.disconnect(mac) }
        if (activeMac.equals(mac, true)) activeMac = null
    }

    fun abort() {
        val mac = activeMac
        probeJob?.cancel()
        bootstrapJob?.cancel()
        BleSerialInject.setWbDisconnectListener(null)
        BleSerialInject.clearPending()
        logcatMirror?.stop()
        commLog?.event("ABORT", "activeMac=$mac")
        if (mac != null) disconnect(mac)
    }

    private suspend fun call(
        method: String,
        uri: String,
        body: String,
        timeoutMs: Long,
        quiet: Boolean = false,
        invoke: (MdsResponseListener) -> Unit,
    ): String {
        val t0 = System.currentTimeMillis()
        if (!quiet) commLog?.request(method, uri, body)
        return try {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    invoke(
                        object : MdsResponseListener {
                            override fun onSuccess(data: String) {
                                val elapsed = System.currentTimeMillis() - t0
                                if (!quiet) {
                                    Log.i(tag, "$method $uri ok (${data.length} chars)")
                                    commLog?.response(method, uri, data, elapsed)
                                }
                                if (cont.isActive) cont.resume(data)
                            }

                            override fun onError(error: MdsException) {
                                val elapsed = System.currentTimeMillis() - t0
                                Log.e(tag, "$method $uri error", error)
                                commLog?.error(method, uri, error, elapsed)
                                if (cont.isActive) cont.resumeWithException(error)
                            }
                        },
                    )
                    cont.invokeOnCancellation {
                        commLog?.event("CALL_CANCEL", "$method $uri")
                    }
                }
            }
        } catch (t: Throwable) {
            if (t !is MdsException) {
                commLog?.error(method, uri, t, System.currentTimeMillis() - t0)
            }
            throw t
        }
    }

    suspend fun put(uri: String, contract: String, timeoutMs: Long = 60_000): String =
        call("PUT", uri, contract, timeoutMs) { mds.put(uri, contract, it) }

    /** PUT without REQ/RSP transcript lines (bulk data); errors are still logged in full. */
    suspend fun putQuiet(uri: String, contract: String, timeoutMs: Long = 60_000): String =
        call("PUT", uri, contract, timeoutMs, quiet = true) { mds.put(uri, contract, it) }

    suspend fun post(uri: String, contract: String, timeoutMs: Long = 60_000): String =
        call("POST", uri, contract, timeoutMs) { mds.post(uri, contract, it) }

    suspend fun get(uri: String, contract: String, timeoutMs: Long = 60_000): String =
        call("GET", uri, contract, timeoutMs) { mds.get(uri, contract, it) }

    suspend fun delete(uri: String, contract: String, timeoutMs: Long = 60_000): String =
        call("DELETE", uri, contract, timeoutMs) { mds.delete(uri, contract, it) }

    suspend fun subscribeUntil(
        uri: String,
        contract: String,
        timeoutMs: Long,
        until: (String) -> Boolean,
    ): String {
        val deferred = CompletableDeferred<String>()
        var sub: MdsSubscription? = null
        var last = "{}"
        val t0 = System.currentTimeMillis()
        commLog?.request("SUBSCRIBE", uri, contract)
        sub =
            mds.subscribe(
                uri,
                contract,
                object : MdsNotificationListener {
                    override fun onNotification(data: String) {
                        last = data
                        Log.d(tag, "notify ${data.take(240)}")
                        commLog?.notify(uri, data)
                        if (until(data) && !deferred.isCompleted) {
                            deferred.complete(data)
                        }
                    }

                    override fun onError(error: MdsException) {
                        Log.e(tag, "subscribe error", error)
                        commLog?.error("SUBSCRIBE", uri, error, System.currentTimeMillis() - t0)
                        if (!deferred.isCompleted) deferred.completeExceptionally(error)
                    }
                },
            )
        return try {
            withTimeout(timeoutMs) { deferred.await() }
                .also {
                    commLog?.response(
                        "SUBSCRIBE_DONE",
                        uri,
                        it,
                        System.currentTimeMillis() - t0,
                    )
                }
        } catch (e: TimeoutCancellationException) {
            commLog?.error("SUBSCRIBE", uri, e, System.currentTimeMillis() - t0)
            last
        } finally {
            runCatching { sub?.unsubscribe() }
            commLog?.event("UNSUBSCRIBE", uri)
        }
    }

    class LiveSubscription internal constructor(
        val uri: String,
        val events: ReceiveChannel<Result<String>>,
        private val onClose: () -> Unit,
    ) {
        fun close() = onClose()
    }

    /**
     * Stock Suunto routes device subscriptions through `suunto://MDS/EventListener`
     * with `{"Uri":"<serial>/<path>"}` (MdsRx.subscribe). An error ACK means the engine
     * could not resolve the resource and MDS has already dropped the subscription, so
     * this throws instead of reporting it as armed. Later errors arrive on [LiveSubscription.events].
     */
    suspend fun openSubscription(
        uri: String,
        viaEventListener: Boolean,
        ackTimeoutMs: Long = 8_000,
    ): LiveSubscription {
        val channel = Channel<Result<String>>(Channel.UNLIMITED)
        val t0 = System.currentTimeMillis()
        val subUri = if (viaEventListener) Mds.URI_EVENTLISTENER else uri
        val subContract =
            if (viaEventListener) {
                JSONObject().put("Uri", uri.removePrefix("suunto://")).toString()
            } else {
                "{}"
            }
        commLog?.request("SUBSCRIBE", if (viaEventListener) "$subUri → $uri" else uri, subContract)
        val ack = CompletableDeferred<Unit>()
        val sub =
            mds.subscribe(
                subUri,
                subContract,
                object : MdsNotificationListener {
                    override fun onNotification(data: String) {
                        commLog?.notify(uri, data)
                        channel.trySend(Result.success(data))
                    }

                    override fun onError(error: MdsException) {
                        commLog?.error("SUBSCRIBE_NOTIFY", uri, error, System.currentTimeMillis() - t0)
                        if (!ack.isCompleted) ack.completeExceptionally(error)
                        channel.trySend(Result.failure(error))
                    }
                },
                object : MdsResponseListener {
                    override fun onSuccess(data: String) {
                        commLog?.response(
                            "SUBSCRIBE_OK",
                            uri,
                            data.ifBlank { "(empty)" },
                            System.currentTimeMillis() - t0,
                        )
                        if (!ack.isCompleted) ack.complete(Unit)
                    }

                    override fun onError(error: MdsException) {
                        commLog?.error("SUBSCRIBE", uri, error, System.currentTimeMillis() - t0)
                        if (!ack.isCompleted) ack.completeExceptionally(error)
                        channel.trySend(Result.failure(error))
                    }
                },
            )

        fun close() {
            runCatching { sub?.unsubscribe() }
            channel.close()
            commLog?.event("UNSUBSCRIBE", uri)
        }

        try {
            withTimeout(ackTimeoutMs) { ack.await() }
        } catch (e: TimeoutCancellationException) {
            commLog?.note(
                "SUBSCRIBE no ACK within ${ackTimeoutMs}ms for $uri — keeping it; " +
                    "an error notification will still end it",
            )
        } catch (t: Throwable) {
            close()
            throw t
        }
        return LiveSubscription(uri, channel, ::close)
    }
}
