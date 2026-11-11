package cz.adamkruzela.core2ota.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * One-shot GATT discovery used before MDS connect: logs every service/char property
 * so we can see whether Core 2 exposes FDF3 / NG / Suunto-custom, then fully closes
 * the client so MDS BleManager owns the link.
 */
object GattProbe {
    private const val TAG = "GattProbe"

    data class Result(
        val services: List<String>,
        val hasMovesense: Boolean,
        val hasNg: Boolean,
        val hasSuuntoCustom: Boolean,
        val hasNus: Boolean,
        val hasSmp: Boolean,
        val summary: String,
    )

    @SuppressLint("MissingPermission")
    suspend fun probeAndClose(
        context: Context,
        device: BluetoothDevice,
        timeoutMs: Long = 12_000,
        log: ((String) -> Unit)? = null,
    ): Result {
        val deferred = CompletableDeferred<Result>()
        var gatt: BluetoothGatt? = null

        val cb =
            object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    Log.i(TAG, "conn status=$status state=$newState")
                    if (newState == BluetoothProfile.STATE_CONNECTED && status == 0) {
                        // Do NOT call BluetoothGatt.refresh() here — it can wedge CCCD
                        // writes on the next MDS connection (BleManager DEFAULT notify).
                        g.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        if (!deferred.isCompleted) {
                            deferred.completeExceptionally(
                                IllegalStateException("GATT probe disconnected status=$status"),
                            )
                        }
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        deferred.completeExceptionally(
                            IllegalStateException("Service discovery failed status=$status"),
                        )
                        return
                    }
                    val lines = mutableListOf<String>()
                    for (svc in g.services) {
                        lines += "SVC ${svc.uuid}"
                        for (ch in svc.characteristics) {
                            lines +=
                                "  CHR ${ch.uuid} props=${props(ch.properties)} " +
                                    "perms=${ch.permissions}"
                        }
                    }
                    val uuids = g.services.map { it.uuid }
                    val result =
                        Result(
                            services = uuids.map { it.toString() },
                            hasMovesense = uuids.contains(GattConstants.MOVESENSE_SERVICE),
                            hasNg = uuids.contains(GattConstants.NG_SERVICE),
                            hasSuuntoCustom = uuids.contains(GattConstants.SUUNTO_CUSTOM_SERVICE),
                            hasNus = uuids.contains(GattConstants.NUS_SERVICE),
                            hasSmp = uuids.contains(GattConstants.SMP_SERVICE),
                            summary = lines.joinToString("\n"),
                        )
                    log?.invoke(
                        "GATT probe: movesense=${result.hasMovesense} ng=${result.hasNg} " +
                            "suuntoCustom=${result.hasSuuntoCustom} nus=${result.hasNus} " +
                            "smp=${result.hasSmp} services=${result.services}",
                    )
                    for (line in lines) log?.invoke(line)
                    if (!deferred.isCompleted) deferred.complete(result)
                }
            }

        return try {
            withTimeout(timeoutMs) {
                gatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
                deferred.await()
            }
        } finally {
            runCatching {
                gatt?.disconnect()
                gatt?.close()
            }
            // Give the stack a beat so MDS does not hit BleManager's
            // "already connected → disconnect and abort" path.
            delay(800)
        }
    }

    private fun props(p: Int): String {
        val bits = mutableListOf<String>()
        if (p and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) bits += "BCAST"
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) bits += "READ"
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) bits += "WR_NR"
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) bits += "WRITE"
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) bits += "NOTIFY"
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) bits += "INDICATE"
        if (p and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) bits += "SIGNED"
        return bits.joinToString("|").ifEmpty { "0x${Integer.toHexString(p)}" }
    }

    fun hasWhiteboardCandidate(r: Result): Boolean =
        r.hasMovesense || r.hasNg || r.hasSuuntoCustom
}
