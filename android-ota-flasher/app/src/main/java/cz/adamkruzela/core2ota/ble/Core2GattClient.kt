package cz.adamkruzela.core2ota.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Discovers Whiteboard / Suunto / SMP GATT services on a bonded Core 2 and
 * exposes a minimal write/notify channel used by [cz.adamkruzela.core2ota.ota.OtaSession].
 */
class Core2GattClient(private val context: Context) {
    private val tag = "Core2Gatt"

    private var gatt: BluetoothGatt? = null
    private val connected = AtomicBoolean(false)
    private val _state = MutableStateFlow("idle")
    val state: StateFlow<String> = _state

    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var smpChar: BluetoothGattCharacteristic? = null

    data class Discovered(
        val whiteboard: Boolean,
        val suuntoCustom: Boolean,
        val nus: Boolean,
        val smp: Boolean,
        val services: List<UUID>,
    )

    private var connectDeferred: CompletableDeferred<Discovered>? = null
    private var writeDeferred: CompletableDeferred<Boolean>? = null
    private val notifyListeners = ConcurrentLinkedQueue<(ByteArray) -> Unit>()

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice, timeoutMs: Long = 30_000): Discovered {
        disconnect()
        val deferred = CompletableDeferred<Discovered>()
        connectDeferred = deferred
        _state.value = "connecting"
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            disconnect()
            throw IllegalStateException("GATT connect timeout", e)
        }
    }

    fun addNotifyListener(listener: (ByteArray) -> Unit) {
        notifyListeners.add(listener)
    }

    @SuppressLint("MissingPermission")
    suspend fun write(payload: ByteArray, timeoutMs: Long = 10_000): Boolean {
        val ch = writeChar ?: error("No Whiteboard write characteristic")
        val g = gatt ?: error("Not connected")
        val deferred = CompletableDeferred<Boolean>()
        writeDeferred = deferred
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            ch.value = payload
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        connected.set(false)
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        writeChar = null
        notifyChar = null
        smpChar = null
        _state.value = "disconnected"
        connectDeferred?.cancel()
        connectDeferred = null
    }

    fun isConnected(): Boolean = connected.get()

    private val callback =
        object : BluetoothGattCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                Log.i(tag, "conn status=$status state=$newState")
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    _state.value = "discovering"
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connected.set(false)
                    _state.value = "disconnected"
                    connectDeferred?.completeExceptionally(IllegalStateException("Disconnected status=$status"))
                }
            }

            @SuppressLint("MissingPermission")
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    connectDeferred?.completeExceptionally(IllegalStateException("Service discovery failed $status"))
                    return
                }
                val services = g.services.map { it.uuid }
                Log.i(tag, "services=$services")

                fun pick(service: UUID, write: UUID, notify: UUID): Boolean {
                    val svc = g.getService(service) ?: return false
                    val w = svc.getCharacteristic(write) ?: return false
                    val n = svc.getCharacteristic(notify) ?: return false
                    writeChar = w
                    notifyChar = n
                    enableNotify(g, n)
                    return true
                }

                val wb =
                    pick(GattConstants.MOVESENSE_SERVICE, GattConstants.MOVESENSE_WRITE, GattConstants.MOVESENSE_NOTIFY) ||
                        pick(GattConstants.NG_SERVICE, GattConstants.NG_WRITE, GattConstants.NG_NOTIFY) ||
                        pick(GattConstants.SUUNTO_CUSTOM_SERVICE, GattConstants.SUUNTO_CUSTOM_A, GattConstants.SUUNTO_CUSTOM_B) ||
                        pick(GattConstants.NUS_SERVICE, GattConstants.NUS_RX, GattConstants.NUS_TX)

                val smpSvc = g.getService(GattConstants.SMP_SERVICE)
                smpChar = smpSvc?.getCharacteristic(GattConstants.SMP_CHAR)

                val discovered =
                    Discovered(
                        whiteboard = wb,
                        suuntoCustom = g.getService(GattConstants.SUUNTO_CUSTOM_SERVICE) != null,
                        nus = g.getService(GattConstants.NUS_SERVICE) != null,
                        smp = smpChar != null,
                        services = services,
                    )
                connected.set(wb || discovered.smp)
                _state.value = if (connected.get()) "ready" else "no-wb-service"
                connectDeferred?.complete(discovered)
                connectDeferred = null
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                writeDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
                writeDeferred = null
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: return
                notifyListeners.forEach { it(value) }
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                notifyListeners.forEach { it(value) }
            }
        }

    @SuppressLint("MissingPermission")
    private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(GattConstants.CCCD) ?: return
        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, enable)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = enable
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
    }
}
