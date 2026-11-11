package cz.adamkruzela.core2ota.ota

import android.content.Context
import android.util.Log
import cz.adamkruzela.core2ota.ble.BondedCore2Devices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Opens / leaves Suunto Core 2 factory Test menu via Whiteboard mode selection.
 *
 * Application 1.13.44 handler ~0x3a670 on `/Device/Ble/mode`:
 * PUT `{"mode": N}` → selection + soft reboot.
 * - 10/11 (0x0A/0x0B) → `appTestMenuView`
 * - 19 (0x13) → `appAgingView`
 * - 5 → normal UI
 *
 * That URI lives only in Application (not Nordic). Over BLE, MDS must first
 * `POST suunto://MDS/BypassRouter` so phone SDS can proxy the remote path.
 * libmds keys are case-sensitive: **`serial`** (not `Serial`) + `pathToBypass`
 * (+ optional `remotePath`). Wrong casing → 403 "Bypass-path or serial is missing".
 */
class SecretMenuSession(
    private val context: Context,
) {
    private val tag = "SecretMenu"
    private val commLog = CommSessionLog(context)
    private val mds = MdsBridge(context, commLog)

    val commLogPath: String get() = commLog.file.absolutePath

    fun abort(reason: String = "user cancel"): File {
        mds.abort()
        return commLog.finish("ABORTED: $reason")
    }

    sealed class Progress {
        data class Status(val message: String) : Progress()
        data class Done(val message: String, val logFile: File?) : Progress()
        data class Error(val message: String, val logFile: File?) : Progress()
    }

    enum class Target(val mode: Int, val label: String) {
        TEST_MENU(10, "Test menu (mode 10)"),
        TEST_MENU_ALT(11, "Test menu alt (mode 11)"),
        AGING(19, "Aging (mode 19)"),
        NORMAL(5, "Normal UI (mode 5)"),
    }

    suspend fun setMode(
        macAddress: String,
        target: Target = Target.TEST_MENU,
        onProgress: (Progress) -> Unit,
    ) = withContext(Dispatchers.IO) {
        try {
            commLog.note("secretMenu target=${target.name} mode=${target.mode} mac=$macAddress")
            val bonded =
                BondedCore2Devices.list(context)
                    .firstOrNull { it.address.equals(macAddress, true) }
            onProgress(Progress.Status("Connecting via MDS for Secret menu…"))
            val connected =
                run {
                    var last: Throwable? = null
                    repeat(3) { attempt ->
                        onProgress(Progress.Status("MDS connect ${attempt + 1}/3…"))
                        try {
                            return@run mds.connect(
                                macAddress = macAddress,
                                serialHint = bonded?.serial,
                                timeoutMs = 45_000,
                            ) { phase -> onProgress(Progress.Status(phase)) }
                        } catch (c: CancellationException) {
                            throw c
                        } catch (t: Throwable) {
                            last = t
                            runCatching { mds.disconnect(macAddress) }
                            if (attempt < 2) {
                                onProgress(
                                    Progress.Status(
                                        "Connect failed (${t.message?.take(60)}) — " +
                                            "retry in 3s. Force-stop Suunto first.",
                                    ),
                                )
                                delay(3_000)
                            }
                        }
                    }
                    throw last ?: error("MDS connect failed")
                }
            val serial =
                connected.serial.ifBlank {
                    bonded?.serial ?: error("Empty serial after MDS connect")
                }
            onProgress(Progress.Status("MDS ok serial=$serial — BypassRouter + PUT mode ${target.mode}…"))

            runCatching { mds.get("suunto://$serial/Info", "{}", timeoutMs = 6_000) }
                .getOrElse { error("Lost /Info right after CONNECT_OK: ${it.message}") }

            // Firmware-canonical URI first; /net/Core2 is wbtool's Application net prefix.
            val remotePaths =
                listOf(
                    "/Device/Ble/mode",
                    "/net/Core2/Device/Ble/mode",
                )
            var bypassOk = false
            for (remote in remotePaths) {
                // Local SDS node path (pathToBypass) — keep short + unique per remote.
                val localPath = remote
                val bypassBodies =
                    listOf(
                        // libmds BypassRouter::post — required keys: pathToBypass + serial
                        JSONObject()
                            .put("pathToBypass", localPath)
                            .put("serial", serial)
                            .put("remotePath", remote)
                            .toString(),
                        JSONObject()
                            .put("pathToBypass", localPath)
                            .put("serial", serial)
                            .toString(),
                    )
                for (bypassBody in bypassBodies) {
                    onProgress(Progress.Status("POST BypassRouter $remote…"))
                    try {
                        val resp =
                            mds.post(
                                "suunto://MDS/BypassRouter",
                                bypassBody,
                                timeoutMs = 8_000,
                            )
                        bypassOk = true
                        commLog.note("BypassRouter OK $remote body=$bypassBody → ${resp.take(240)}")
                        break
                    } catch (c: CancellationException) {
                        throw c
                    } catch (err: Throwable) {
                        val msg = err.message.orEmpty()
                        // 409 = already effective — treat as usable
                        if (msg.contains("409") || msg.contains("already effective", true)) {
                            bypassOk = true
                            commLog.note("BypassRouter already effective for $remote")
                            break
                        }
                        commLog.note(
                            "BypassRouter fail $remote body=$bypassBody → ${msg.take(160)}",
                        )
                    }
                }
                if (bypassOk) break
            }
            if (!bypassOk) {
                commLog.note("BypassRouter never accepted — PUTs likely stay Nordic 404")
            }

            val modeBody = JSONObject().put("mode", target.mode).toString()
            val putTargets =
                buildList {
                    for (path in remotePaths) {
                        // Device URI (what OTA uses for /Update/…)
                        add("suunto://$serial$path")
                        // Local SDS bypass node (pathToBypass hosted under MDS)
                        add("suunto://MDS$path")
                    }
                }

            var lastErr: Throwable? = null
            var putOk: Pair<String, String>? = null
            for (uri in putTargets) {
                val infoAlive =
                    runCatching {
                        mds.get("suunto://$serial/Info", "{}", timeoutMs = 3_000)
                    }.isSuccess
                if (!infoAlive) {
                    commLog.note("abort PUT loop — /Info dead (WB dropped)")
                    break
                }
                onProgress(Progress.Status("PUT $uri"))
                try {
                    val resp = mds.put(uri, modeBody, timeoutMs = 10_000)
                    commLog.note("PUT OK $uri body=$modeBody → ${resp.take(300)}")
                    putOk = uri to resp
                    break
                } catch (c: CancellationException) {
                    throw c
                } catch (err: Throwable) {
                    lastErr = err
                    commLog.note("PUT FAIL $uri body=$modeBody → ${err.message?.take(160)}")
                }
            }

            val ok = putOk
            if (ok != null) {
                val msg =
                    when (target) {
                        Target.TEST_MENU, Target.TEST_MENU_ALT ->
                            "Secret menu requested (mode ${target.mode}). " +
                                "Watch should soft-reboot into Test menu."
                        Target.AGING ->
                            "Aging mode requested (19). Watch should reboot into AGING view."
                        Target.NORMAL ->
                            "Normal mode requested (5). Watch should reboot into regular UI."
                    }
                onProgress(Progress.Status("PUT mode → ${ok.second.take(160)}"))
                val log = commLog.finish("OK: ${target.name}")
                onProgress(Progress.Done(msg, log))
                return@withContext
            }

            val detail = lastErr?.message?.take(120) ?: "no candidate accepted"
            error(
                "Secret menu PUT failed ($detail). " +
                    "BypassRouter=${if (bypassOk) "ok" else "failed"}. " +
                    "/Device/Ble/mode is Application-only; Nordic BLE may still 404. Share this log.",
            )
        } catch (c: CancellationException) {
            commLog.finish("CANCELLED")
            throw c
        } catch (t: Throwable) {
            Log.e(tag, "secret menu failed", t)
            val log = commLog.finish("FAILED: ${t.message}")
            onProgress(Progress.Error(t.message ?: t.toString(), log))
        } finally {
            runCatching { mds.disconnect(macAddress) }
        }
    }
}
