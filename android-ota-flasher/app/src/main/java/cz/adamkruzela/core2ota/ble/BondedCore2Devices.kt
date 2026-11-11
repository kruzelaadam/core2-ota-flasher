package cz.adamkruzela.core2ota.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

data class Core2Device(
    val name: String,
    val address: String,
) {
    /** Watch serial from BLE name `Suunto Core 2 {serial}`; falls back to MAC digits. */
    val serial: String
        get() {
            val fromName =
                name.removePrefix(BondedCore2Devices.NAME_PREFIX).trim()
                    .takeIf { it.isNotEmpty() && !it.contains(':') }
            if (fromName != null) return fromName
            return address.replace(":", "").replace("-", "")
        }
}

object BondedCore2Devices {
    const val NAME_PREFIX = "Suunto Core 2 "

    fun hasConnectPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun list(context: Context): List<Core2Device> {
        if (!hasConnectPermission(context)) return emptyList()
        val adapter =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        return adapter.bondedDevices
            .orEmpty()
            .filter { d ->
                val n = d.name.orEmpty()
                n.startsWith(NAME_PREFIX) || n.contains("Core 2", ignoreCase = true)
            }
            .map { Core2Device(name = it.name ?: it.address, address = it.address) }
            .sortedBy { it.name }
    }

    @SuppressLint("MissingPermission")
    fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    @SuppressLint("MissingPermission")
    fun device(context: Context, mac: String): BluetoothDevice? =
        adapter(context)?.getRemoteDevice(mac)
}
