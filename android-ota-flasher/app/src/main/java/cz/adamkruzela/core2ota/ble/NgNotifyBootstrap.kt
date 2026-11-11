package cz.adamkruzela.core2ota.ble

import android.bluetooth.BluetoothGatt
import android.util.Log
import com.movesense.mds.internal.connectivity.BleManager
import com.polidea.rxandroidble2.RxBleConnection
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Soft nudge after BleManager [onConnect].
 *
 * Do **not** call [RxBleConnection.setupNotification] again — BleManager already
 * subscribed DEFAULT mode; a second COMPAT setup tears down the GATT link
 * ("connection failed" regression in 0.3.7).
 *
 * Only request MTU / connection priority, then [BleManagerGattHack.forceBypassConnect]
 * in case CCCD already completed but native bypassConnect was missed.
 */
object NgNotifyBootstrap {
    private const val TAG = "NgNotifyBootstrap"

    suspend fun run(
        macAddress: String,
        timeoutMs: Long = 8_000,
        log: ((String) -> Unit)? = null,
    ): Boolean =
        withTimeout(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                fun done(ok: Boolean, msg: String) {
                    Log.i(TAG, msg)
                    log?.invoke(msg)
                    if (cont.isActive) cont.resume(ok)
                }

                val conn =
                    runCatching { connectionFor(macAddress) }.getOrElse {
                        log?.invoke("No RxBleConnection yet: ${it.message}")
                        BleManagerGattHack.forceBypassConnect(macAddress, log)
                        done(false, "bypassConnect without connectionMap entry")
                        return@suspendCancellableCoroutine
                    }
                log?.invoke("NgNotifyBootstrap got RxBleConnection for $macAddress")

                runCatching {
                    conn.requestConnectionPriority(
                        BluetoothGatt.CONNECTION_PRIORITY_HIGH,
                        1,
                        TimeUnit.SECONDS,
                    ).subscribe({}, {})
                }

                val d =
                    conn.requestMtu(247)
                        .timeout(4, TimeUnit.SECONDS)
                        .subscribe(
                            { mtu ->
                                log?.invoke("requestMtu → $mtu")
                                BleManagerGattHack.forceBypassConnect(macAddress, log)
                                done(true, "MTU+bypassConnect done")
                            },
                            { err ->
                                log?.invoke("requestMtu failed (${err.message}) — bypass anyway")
                                BleManagerGattHack.forceBypassConnect(macAddress, log)
                                done(true, "bypassConnect after MTU fail")
                            },
                        )
                cont.invokeOnCancellation { d.dispose() }
            }
        }

    fun describeConnection(macAddress: String, log: ((String) -> Unit)?) {
        runCatching {
            val keys =
                connectionMapKeys()
            log?.invoke("connectionMap keys=$keys lookingFor=$macAddress")
        }.onFailure {
            log?.invoke("describeConnection: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun connectionFor(macAddress: String): RxBleConnection {
        val field =
            BleManager::class.java.getDeclaredField("connectionMap").apply {
                isAccessible = true
            }
        @Suppress("UNCHECKED_CAST")
        val map = field.get(BleManager.INSTANCE) as Map<String, RxBleConnection>
        return map[macAddress]
            ?: map.entries.firstOrNull { it.key.equals(macAddress, true) }?.value
            ?: error("connectionMap miss keys=${map.keys}")
    }

    private fun connectionMapKeys(): Set<String> {
        val field =
            BleManager::class.java.getDeclaredField("connectionMap").apply {
                isAccessible = true
            }
        @Suppress("UNCHECKED_CAST")
        val map = field.get(BleManager.INSTANCE) as Map<String, *>
        return map.keys
    }
}
