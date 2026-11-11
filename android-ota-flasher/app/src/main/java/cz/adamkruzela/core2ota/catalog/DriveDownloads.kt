package cz.adamkruzela.core2ota.catalog

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/** Public Google Drive file download via export links (folder must be link-shared). */
object DriveDownloads {
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

    fun directUrl(fileId: String): String =
        "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"

    fun readText(fileId: String): String {
        val req = Request.Builder().url(directUrl(fileId)).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Drive download HTTP ${resp.code} for $fileId")
            return resp.body?.string().orEmpty()
        }
    }

    fun download(
        fileId: String,
        dest: File,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): File {
        dest.parentFile?.mkdirs()
        val req = Request.Builder().url(directUrl(fileId)).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Drive download HTTP ${resp.code} for $fileId")
            val body = resp.body ?: error("Empty Drive body")
            val total = body.contentLength()
            body.byteStream().use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var sum = 0L
                    while (input.read(buf).also { read = it } >= 0) {
                        out.write(buf, 0, read)
                        sum += read
                        onProgress(sum, total)
                    }
                }
            }
        }
        return dest
    }
}
