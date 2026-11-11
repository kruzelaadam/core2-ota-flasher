package cz.adamkruzela.core2ota.ota

import android.bluetooth.BluetoothManager
import android.content.Context
import io.runtime.mcumgr.McuMgrErrorCode
import io.runtime.mcumgr.ble.McuMgrBleTransport
import io.runtime.mcumgr.dfu.FirmwareUpgradeCallback
import io.runtime.mcumgr.dfu.FirmwareUpgradeController
import io.runtime.mcumgr.dfu.mcuboot.FirmwareUpgradeManager
import io.runtime.mcumgr.dfu.mcuboot.model.ImageSet
import io.runtime.mcumgr.dfu.mcuboot.model.TargetImage
import io.runtime.mcumgr.exception.McuMgrErrorException
import io.runtime.mcumgr.exception.McuMgrException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * The Suunto app's NordicDfu: when the watch sends operation DFU, the phone uploads the
 * type-3 package to the Nordic radio with mcumgr over a separate GATT connection
 * (SMP service), mode TEST_AND_CONFIRM, estimated swap 10 s, app settings kept.
 * NOT_SUPPORTED while confirming is treated as success, as in stock.
 */
class BluetoothDfu(
    context: Context,
    macAddress: String,
) {
    sealed class Event {
        data class StateChanged(val from: String, val to: String) : Event()

        data class Upload(val sent: Int, val total: Int) : Event()

        data object Completed : Event()

        data class Failed(val state: String, val message: String) : Event()

        data class Canceled(val state: String) : Event()
    }

    private val transport: McuMgrBleTransport
    private var manager: FirmwareUpgradeManager? = null

    init {
        val adapter =
            context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: error("Bluetooth adapter unavailable")
        transport = McuMgrBleTransport(context.applicationContext, adapter.getRemoteDevice(macAddress))
    }

    fun start(images: ImageSet, onEvent: (Event) -> Unit) {
        val callback =
            object : FirmwareUpgradeCallback<FirmwareUpgradeManager.State> {
                override fun onUpgradeStarted(controller: FirmwareUpgradeController) {}

                override fun onStateChanged(
                    prevState: FirmwareUpgradeManager.State,
                    newState: FirmwareUpgradeManager.State,
                ) = onEvent(Event.StateChanged(prevState.name, newState.name))

                override fun onUpgradeCompleted() = onEvent(Event.Completed)

                override fun onUpgradeFailed(state: FirmwareUpgradeManager.State, error: McuMgrException) {
                    if (state == FirmwareUpgradeManager.State.CONFIRM &&
                        error is McuMgrErrorException &&
                        error.code == McuMgrErrorCode.NOT_SUPPORTED
                    ) {
                        onEvent(Event.Completed)
                    } else {
                        onEvent(Event.Failed(state.name, "${error.javaClass.simpleName}: ${error.message}"))
                    }
                }

                override fun onUpgradeCanceled(state: FirmwareUpgradeManager.State) =
                    onEvent(Event.Canceled(state.name))

                override fun onUploadProgressChanged(bytesSent: Int, imageSize: Int, timestamp: Long) =
                    onEvent(Event.Upload(bytesSent, imageSize))
            }
        val m = FirmwareUpgradeManager(transport, callback)
        m.setMode(FirmwareUpgradeManager.Mode.TEST_AND_CONFIRM)
        manager = m
        m.start(
            images,
            FirmwareUpgradeManager.Settings.Builder()
                .setEstimatedSwapTime(10_000)
                .setEraseAppSettings(false)
                .build(),
        )
    }

    fun cancel() {
        runCatching { manager?.cancel() }
    }

    fun release() {
        runCatching { manager?.cancel() }
        runCatching { transport.release() }
    }

    companion object {
        /**
         * Stock getImages(): a raw MCUboot image is used as is; otherwise the file is a
         * ZIP whose manifest.json lists the images (`image_index`, optional `slot`,
         * default secondary slot for application images).
         */
        fun imagesFrom(file: File): Pair<ImageSet, String> {
            val bytes = file.readBytes()
            runCatching { ImageSet().add(bytes) }.getOrNull()?.let {
                return it to "raw MCUboot image ${bytes.size} B"
            }
            val entries = LinkedHashMap<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (!e.isDirectory) entries[e.name] = zin.readBytes()
                }
            }
            val manifest =
                JSONObject(String(entries["manifest.json"] ?: error("manifest.json missing"), Charsets.UTF_8))
            val files = manifest.getJSONArray("files")
            val set = ImageSet()
            val described = mutableListOf<String>()
            var mcubootSlot = 0
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                val name = f.getString("file")
                val data = entries[name] ?: error("$name listed in manifest.json but missing")
                val imageIndex = f.optString("image_index", "0").toIntOrNull() ?: 0
                val slot =
                    when {
                        f.has("slot") && f.optInt("slot", -1) >= 0 -> f.getInt("slot")
                        f.optString("type") == "mcuboot" -> mcubootSlot++
                        else -> TargetImage.SLOT_SECONDARY
                    }
                set.add(TargetImage(imageIndex, slot, data))
                described += "$name image=$imageIndex slot=$slot ${data.size} B"
            }
            require(set.images.isNotEmpty()) { "manifest.json lists no images" }
            return set to described.joinToString("; ")
        }
    }
}
