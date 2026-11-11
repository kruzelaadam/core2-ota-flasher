package cz.adamkruzela.core2ota.mds

import android.content.Context
import android.util.Log
import cz.adamkruzela.core2ota.R
import java.io.File

/**
 * libmds looks for `KompostiSettings.xml` under the MDS config path
 * (`Context.getFilesDir()`). Official Suunto copies the same file from res/raw.
 * Without it, native falls back to defaults (whiteboardSerial ECSD00000000).
 */
object KompostiConfig {
    private const val TAG = "KompostiConfig"
    private const val FILE_NAME = "KompostiSettings.xml"

    fun install(context: Context, log: ((String) -> Unit)? = null) {
        val dest = File(context.applicationContext.filesDir, FILE_NAME)
        runCatching {
            context.resources.openRawResource(R.raw.kompostisettings).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            val msg = "Wrote ${dest.absolutePath} (${dest.length()} bytes)"
            Log.i(TAG, msg)
            log?.invoke(msg)
        }.onFailure {
            Log.w(TAG, "Failed to install $FILE_NAME", it)
            log?.invoke("KompostiSettings install failed: ${it.message}")
        }
    }
}
