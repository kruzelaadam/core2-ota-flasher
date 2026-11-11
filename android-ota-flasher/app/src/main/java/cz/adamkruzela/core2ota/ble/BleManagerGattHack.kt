package cz.adamkruzela.core2ota.ble

import android.util.Log
import com.movesense.mds.BLEWrapper
import com.movesense.mds.internal.connectivity.BleManager
import java.util.UUID

/**
 * Stock MDS [BleManager] only maps Movesense FDF3 + NG Whiteboard GATT services.
 * Core 2 also exposes Suunto custom `57a70012-…`. Inject it so BleManager can
 * match that service if FDF3/NG are absent or empty.
 *
 * Also exposes a last-resort [forceBypassConnect] when BLE is up but native
 * Whiteboard never posts ConnectedDevices.
 */
object BleManagerGattHack {
    private const val TAG = "BleManagerGattHack"
    private const val WB_GATT_SERVICE = "com.movesense.mds.internal.connectivity.WbBleGattService"

    @Volatile
    private var injected = false

    fun ensureSuuntoCustomMapped(log: ((String) -> Unit)? = null) {
        if (injected) {
            log?.invoke("GATT_SERVICES_MAP already has Suunto custom")
            return
        }
        runCatching {
            val field =
                BleManager::class.java.getDeclaredField("GATT_SERVICES_MAP").apply {
                    isAccessible = true
                }
            @Suppress("UNCHECKED_CAST")
            val map = field.get(null) as MutableMap<UUID, Any>
            val wbClass = Class.forName(WB_GATT_SERVICE)
            val ctor =
                wbClass
                    .getDeclaredConstructor(UUID::class.java, UUID::class.java)
                    .apply { isAccessible = true }
            // Core2GattClient pick order: A=write, B=notify
            val svc =
                ctor.newInstance(
                    GattConstants.SUUNTO_CUSTOM_A,
                    GattConstants.SUUNTO_CUSTOM_B,
                )
            map[GattConstants.SUUNTO_CUSTOM_SERVICE] = svc
            injected = true
            val msg =
                "Injected Suunto custom WB service ${GattConstants.SUUNTO_CUSTOM_SERVICE} " +
                    "write=${GattConstants.SUUNTO_CUSTOM_A} notify=${GattConstants.SUUNTO_CUSTOM_B} " +
                    "(mapSize=${map.size})"
            Log.i(TAG, msg)
            log?.invoke(msg)
        }.onFailure {
            Log.w(TAG, "Failed to inject Suunto custom GATT", it)
            log?.invoke(
                "GATT inject failed: ${it.javaClass.name}: ${it.message} " +
                    "cause=${it.cause?.javaClass?.name}:${it.cause?.message}",
            )
        }
    }

    /**
     * Mirror of BleManager's post-notify path: AddressMap → BLEWrapper.bypassConnect.
     * Safe to call when onConnect already fired but ConnectedDevices never arrives.
     */
    /**
     * Existing MAC→wbId mapping only (do not allocate a second handle).
     * After HELLO, BleManager already mapped e.g. `0C:…` → `10000001`.
     */
    fun wbAddressFor(macAddress: String): String? =
        runCatching {
            val addressMap =
                BleManager::class.java
                    .getDeclaredField("addressMap")
                    .apply { isAccessible = true }
                    .get(BleManager.INSTANCE)
            val getWb =
                addressMap.javaClass
                    .getDeclaredMethod("getWbAddress", String::class.java)
                    .apply { isAccessible = true }
            (getWb.invoke(addressMap, macAddress) as? String)?.takeIf { it.isNotBlank() }
        }.getOrNull()

    fun forceBypassConnect(macAddress: String, log: ((String) -> Unit)? = null): Boolean {
        return runCatching {
            val addressMap =
                BleManager::class.java
                    .getDeclaredField("addressMap")
                    .apply { isAccessible = true }
                    .get(BleManager.INSTANCE)
            val getOrCreate =
                addressMap.javaClass
                    .getDeclaredMethod("getOrCreateWbAddress", String::class.java)
                    .apply { isAccessible = true }
            val wbAddress = getOrCreate.invoke(addressMap, macAddress) as String
            val wrapperField =
                BleManager::class.java.getDeclaredField("bleWrapper").apply {
                    isAccessible = true
                }
            val wrapper = wrapperField.get(BleManager.INSTANCE) as BLEWrapper
            wrapper.bypassConnect(wbAddress)
            val msg = "forceBypassConnect mac=$macAddress wbAddress=$wbAddress"
            Log.i(TAG, msg)
            log?.invoke(msg)
            true
        }.onFailure {
            Log.w(TAG, "forceBypassConnect failed", it)
            log?.invoke("forceBypassConnect failed: ${it.message}")
        }.getOrDefault(false)
    }

    /** True if BleManager still has an RxBleConnection for this MAC. */
    fun isBleLinkUp(macAddress: String): Boolean =
        runCatching {
            val field =
                BleManager::class.java.getDeclaredField("connectionMap").apply {
                    isAccessible = true
                }
            @Suppress("UNCHECKED_CAST")
            val map = field.get(BleManager.INSTANCE) as Map<String, *>
            map.keys.any { it.equals(macAddress, ignoreCase = true) }
        }.getOrDefault(false)

    /** Avoid runaway wbId handles (10000001 → 1000000a) across reconnects in one process. */
    fun resetAddressMapHandles(log: ((String) -> Unit)? = null) {
        runCatching {
            val addressMap =
                BleManager::class.java
                    .getDeclaredField("addressMap")
                    .apply { isAccessible = true }
                    .get(BleManager.INSTANCE)
            val next =
                addressMap.javaClass.getDeclaredField("nextDeviceHandle").apply {
                    isAccessible = true
                }
            // BleManager$AddressMap ctor seeds 0x10000001
            next.setInt(addressMap, 0x10000001)
            val wbToMac =
                addressMap.javaClass.getDeclaredField("wbAddressToBleMac").apply {
                    isAccessible = true
                }.get(addressMap) as MutableMap<*, *>
            val macToWb =
                addressMap.javaClass.getDeclaredField("bleMacToWbAddress").apply {
                    isAccessible = true
                }.get(addressMap) as MutableMap<*, *>
            wbToMac.clear()
            macToWb.clear()
            log?.invoke("AddressMap reset nextDeviceHandle=10000001 maps cleared")
        }.onFailure {
            log?.invoke("AddressMap reset failed: ${it.message}")
        }
    }

    fun dumpMappedServices(log: ((String) -> Unit)? = null) {
        runCatching {
            val field =
                BleManager::class.java.getDeclaredField("GATT_SERVICES_MAP").apply {
                    isAccessible = true
                }
            @Suppress("UNCHECKED_CAST")
            val map = field.get(null) as Map<UUID, Any>
            val wbClass = Class.forName(WB_GATT_SERVICE)
            val getWrite = wbClass.getDeclaredMethod("getWriteCharacteristicUuid").apply {
                isAccessible = true
            }
            val getNotify = wbClass.getDeclaredMethod("getNotifyCharacteristicUuid").apply {
                isAccessible = true
            }
            val lines =
                map.entries.joinToString("; ") { (k, v) ->
                    "$k write=${getWrite.invoke(v)} notify=${getNotify.invoke(v)}"
                }
            log?.invoke("BleManager GATT_SERVICES_MAP (${map.size}): $lines")
        }.onFailure {
            log?.invoke(
                "dumpMappedServices failed: ${it.javaClass.name}: ${it.message}",
            )
        }
    }
}
