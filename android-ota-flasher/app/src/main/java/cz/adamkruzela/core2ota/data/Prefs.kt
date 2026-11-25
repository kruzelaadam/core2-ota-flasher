package cz.adamkruzela.core2ota.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class FlashMode {
    ORIGINAL,
    MODIFIED,
}

private val Context.store by preferencesDataStore(name = "core2ota")

/** Last chosen watch and Original/Modified mode. Survives process death. */
class Prefs(private val context: Context) {
    private val macKey = stringPreferencesKey("last_device_mac")
    private val modeKey = stringPreferencesKey("flash_mode")

    val lastDeviceMac: Flow<String?> = context.store.data.map { it[macKey] }

    val flashMode: Flow<FlashMode> =
        context.store.data.map { prefs ->
            when (prefs[modeKey]) {
                FlashMode.MODIFIED.name -> FlashMode.MODIFIED
                else -> FlashMode.ORIGINAL
            }
        }

    suspend fun setLastDeviceMac(mac: String) {
        context.store.edit { it[macKey] = mac }
    }

    suspend fun setFlashMode(mode: FlashMode) {
        context.store.edit { it[modeKey] = mode.name }
    }
}
