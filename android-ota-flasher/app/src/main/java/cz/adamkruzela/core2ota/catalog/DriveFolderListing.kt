package cz.adamkruzela.core2ota.catalog

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class DriveListedFile(
    val id: String,
    val parentId: String,
    val name: String,
    val mimeType: String,
) {
    val isFolder: Boolean
        get() = mimeType == "application/vnd.google-apps.folder" ||
            mimeType.endsWith("/vnd.google-apps.folder")
}

/**
 * Lists a publicly link-shared Drive folder by scraping the folder page `_DRIVE_ivd` blob.
 * No API key required; folder must be shared as "anyone with the link".
 */
object DriveFolderListing {
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

    private val ivdAssign =
        Pattern.compile("window\\['_DRIVE_ivd'\\]\\s*=\\s*'([^']*)'")

    // [id, [parent], "name", "mimeType", ...]
    private val entry =
        Pattern.compile(
            "\\[\"(1[A-Za-z0-9_-]+)\",\\[\"(1[A-Za-z0-9_-]+)\"\\],\"([^\"]+)\",\"([^\"]+)\"",
        )

    fun listFolder(folderId: String): List<DriveListedFile> {
        val url = "https://drive.google.com/drive/folders/$folderId?usp=sharing"
        val req =
            Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                        "Chrome/120.0.0.0 Mobile Safari/537.36",
                )
                .get()
                .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                error("Drive folder list HTTP ${resp.code} for $folderId")
            }
            val html = resp.body?.string().orEmpty()
            val m = ivdAssign.matcher(html)
            if (!m.find()) {
                error("Drive folder page had no file list for $folderId (is it link-shared?)")
            }
            val decoded = decodeJsHexString(m.group(1))
            val out = ArrayList<DriveListedFile>()
            val em = entry.matcher(decoded)
            while (em.find()) {
                out +=
                    DriveListedFile(
                        id = em.group(1),
                        parentId = em.group(2),
                        name = em.group(3),
                        mimeType = em.group(4).replace("\\/", "/"),
                    )
            }
            return out
        }
    }

    /** Decode `\xNN` sequences used inside Drive's JS string literals. */
    internal fun decodeJsHexString(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            if (raw[i] == '\\' && i + 3 < raw.length && raw[i + 1] == 'x') {
                val hex = raw.substring(i + 2, i + 4)
                sb.append(hex.toInt(16).toChar())
                i += 4
            } else {
                sb.append(raw[i])
                i++
            }
        }
        return sb.toString()
    }
}
