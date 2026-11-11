package cz.adamkruzela.core2ota.ota

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mirrors this-process logcat from MDS/BleManager/komposti into [CommSessionLog].
 * Critical strings: HELLO_ACK, Device discovered, serial, ROUTE_STATE_*,
 * Message deserialization failed.
 */
class MdsLogcatMirror(
    private val commLog: CommSessionLog,
) {
    private val tag = "MdsLogcatMirror"
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var logcatProc: java.lang.Process? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val pid = android.os.Process.myPid()
        thread =
            Thread({
                try {
                    logcatProc =
                        Runtime.getRuntime().exec(
                            arrayOf(
                                "logcat",
                                "-v",
                                "time",
                                "--pid=$pid",
                                "BleManager:V",
                                "NGBLEWrapper:V",
                                "SDSInternalWrapper:V",
                                "MDS:V",
                                "Komposti:V",
                                "komposti:V",
                                "*:S",
                            ),
                        )
                    BufferedReader(InputStreamReader(logcatProc!!.inputStream)).use { reader ->
                        while (running.get()) {
                            val line = reader.readLine() ?: break
                            if (line.isBlank()) continue
                            if (isInteresting(line)) {
                                commLog.note("LOGCAT $line")
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(tag, "logcat mirror failed", t)
                    commLog.note("LOGCAT mirror failed: ${t.message}")
                } finally {
                    running.set(false)
                    runCatching { logcatProc?.destroy() }
                }
            }, "mds-logcat-mirror")
        thread?.start()
        commLog.note("LOGCAT mirror started pid=$pid (BleManager/Komposti verbose)")
    }

    fun stop() {
        running.set(false)
        runCatching { logcatProc?.destroy() }
        thread = null
        logcatProc = null
    }

    private fun isInteresting(line: String): Boolean {
        val l = line.lowercase()
        // Drop ultra-noisy successful SDS bookkeeping after startup unless handshake-related.
        if (l.contains("[sds request]") || l.contains("[sds response]")) {
            return l.contains("connecteddevices") ||
                l.contains("connectingdevices") ||
                l.contains("serial") ||
                l.contains("deviceinfo") ||
                l.contains("bypass") ||
                l.contains("whiteboard")
        }
        return l.contains("bypass") ||
            l.contains("notification") ||
            l.contains("hello") ||
            l.contains("whiteboard") ||
            l.contains("connecteddevices") ||
            l.contains("connectingdevices") ||
            l.contains("service uuid") ||
            l.contains("got service") ||
            l.contains("connect") ||
            l.contains("received data") ||
            l.contains("sendcb") ||
            l.contains("serial") ||
            l.contains("device discovered") ||
            l.contains("route_state") ||
            l.contains("deserial") ||
            l.contains("mapped serial") ||
            l.contains("matched serial") ||
            l.contains("invalid serial") ||
            l.contains("without serial") ||
            l.contains("wb_comm") ||
            l.contains("wb_data")
    }
}
