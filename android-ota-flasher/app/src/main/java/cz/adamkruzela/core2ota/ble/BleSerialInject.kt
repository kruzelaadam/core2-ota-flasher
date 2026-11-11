package cz.adamkruzela.core2ota.ble

import android.util.Log
import com.movesense.mds.BLEDelegate
import com.movesense.mds.BLEWrapper
import com.movesense.mds.internal.connectivity.BleManager
import java.util.concurrent.atomic.AtomicReference

/**
 * Stock [BleManager.getConnectedBleDevicesCb] always returns triples
 * `[wbAddress, "", ""]`. Native `wbble::enumerateBleDevices` reads that array
 * when building the BLE Whiteboard route. Empty serial slots + empty HELLO serial
 * leave SDS stuck on "without serial" and `suunto://{serial}/…` never routes.
 *
 * We wrap the BleManager BLEDelegate and fill serial (from BLE name / hint) into
 * those slots **before** HELLO is composed.
 *
 * Important: `suunto://{wbId}/…` is **not** a valid MDS REST host — SDS rejects it
 * locally with 404. The firmware-agent path `suunto://10000001/Device/Info/Serial`
 * is Whiteboard-native addressing, not the phone REST URI. After inject, OTA must
 * use the 12-hex serial host.
 */
object BleSerialInject {
    private const val TAG = "BleSerialInject"

    data class Pending(
        val macAddress: String,
        val serial: String,
    )

    private val pending = AtomicReference<Pending?>(null)

    /** Fired when BleManager native path calls disconnectCb (often without MDS onDisconnect). */
    @Volatile
    private var onWbDisconnect: ((wbAddress: String) -> Unit)? = null

    @Volatile
    private var installed = false

    fun setPending(
        macAddress: String,
        serial: String?,
        log: ((String) -> Unit)? = null,
    ) {
        val s = serial?.trim().orEmpty()
        if (s.isEmpty()) {
            pending.set(null)
            log?.invoke("BleSerialInject pending cleared (no serial)")
            return
        }
        pending.set(Pending(macAddress = macAddress, serial = s))
        log?.invoke("BleSerialInject pending mac=$macAddress serial=$s")
    }

    fun clearPending() {
        pending.set(null)
    }

    fun setWbDisconnectListener(listener: ((wbAddress: String) -> Unit)?) {
        onWbDisconnect = listener
    }

    fun install(log: ((String) -> Unit)? = null) {
        if (installed) {
            log?.invoke("BleSerialInject already installed")
            return
        }
        runCatching {
            val bm = BleManager.INSTANCE
            val wrapperField =
                BleManager::class.java.getDeclaredField("bleWrapper").apply {
                    isAccessible = true
                }
            val wrapper = wrapperField.get(bm) as BLEWrapper
            val forwarding =
                object : BLEDelegate {
                    override fun connectCb(wbAddress: String): Boolean = bm.connectCb(wbAddress)

                    override fun cancelConnectCb(wbAddress: String): Boolean =
                        bm.cancelConnectCb(wbAddress)

                    override fun disconnectCb(wbAddress: String): Boolean {
                        Log.w(TAG, "disconnectCb wb=$wbAddress (forward + notify)")
                        runCatching { onWbDisconnect?.invoke(wbAddress) }
                        return bm.disconnectCb(wbAddress)
                    }

                    override fun sendCb(
                        wbAddress: String,
                        data: ByteArray,
                        length: Int,
                        requestId: Long,
                    ): Boolean = bm.sendCb(wbAddress, data, length, requestId)

                    override fun cancelSendCb(requestId: Long): Boolean = bm.cancelSendCb(requestId)

                    override fun deviceToWhiteboardCb(deviceId: Int): BLEWrapper.WbAddress =
                        bm.deviceToWhiteboardCb(deviceId)

                    override fun whiteboardToDevice(wbAddress: String): Int =
                        bm.whiteboardToDevice(wbAddress)

                    override fun getConnectedBleDevicesCb(): BLEWrapper.ConnectedDevices {
                        val raw = bm.getConnectedBleDevicesCb()
                        return patchConnected(raw)
                    }
                }
            wrapper.setDelegate(forwarding)
            installed = true
            val msg =
                "BleSerialInject installed — will fill empty serial slots in " +
                    "getConnectedBleDevicesCb before HELLO"
            Log.i(TAG, msg)
            log?.invoke(msg)
        }.onFailure {
            Log.w(TAG, "BleSerialInject install failed", it)
            log?.invoke("BleSerialInject install failed: ${it.message}")
        }
    }

    private fun patchConnected(raw: BLEWrapper.ConnectedDevices): BLEWrapper.ConnectedDevices {
        val p = pending.get() ?: return raw
        val arr = raw.connectedDevices ?: return raw
        if (arr.isEmpty()) return raw
        val out = arr.copyOf()
        var patched = 0
        var i = 0
        while (i + 2 < out.size) {
            val wb = out[i]
            val slot1 = out[i + 1]
            val slot2 = out[i + 2]
            // Only touch empty slots so we never overwrite a real serial.
            if (slot1.isNullOrBlank()) {
                out[i + 1] = p.serial
                patched++
            }
            if (slot2.isNullOrBlank()) {
                // Both empty slots get serial — format of the triple is unclear;
                // MAC in slot2 did not help HELLO mapping in 0.3.12.
                out[i + 2] = p.serial
                patched++
            }
            // Log under BleManager tag so MdsLogcatMirror captures it.
            Log.i(
                "BleManager",
                "BleSerialInject patched wb=$wb serial='${out[i + 1]}' " +
                    "slot2='${out[i + 2]}' (was '$slot1'/'$slot2')",
            )
            i += 3
        }
        if (patched == 0) return raw
        return BLEWrapper.ConnectedDevices(out)
    }
}
