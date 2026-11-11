package cz.adamkruzela.core2ota.catalog

import cz.adamkruzela.core2ota.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class CatalogEntry(
    val deviceName: String,
    val hwVersion: String,
    val version: String,
    val uri: String,
    val uploadDate: String,
    val releaseType: String,
    val firmwareId: Long,
    val forceUpdate: Boolean,
)

class FirmwareCatalog(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    private val base = BuildConfig.CATALOG_BASE.trimEnd('/')
    private val appKey = BuildConfig.SUUNTO_APPKEY

    fun fetchHistory(variant: String = "Explorer", hw: String = "A"): List<CatalogEntry> {
        val url = "$base/firmware/devices/$variant/$hw/history?appkey=$appKey"
        val body = get(url)
        val arr = JSONArray(body)
        return buildList {
            for (i in 0 until arr.length()) {
                add(parseEntry(arr.getJSONObject(i)))
            }
        }.sortedByDescending { it.version }
    }

    fun fetchLatest(variant: String = "Explorer", hw: String = "A"): CatalogEntry {
        val url = "$base/firmware/devices/$variant/$hw?appkey=$appKey"
        return parseEntry(JSONObject(get(url)))
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Catalog HTTP ${resp.code}: $url")
            }
            return resp.body?.string().orEmpty()
        }
    }

    private fun parseEntry(o: JSONObject): CatalogEntry =
        CatalogEntry(
            deviceName = o.optString("DeviceName"),
            hwVersion = o.optString("Version"),
            version = o.optString("LatestFirmwareVersion"),
            uri = o.optString("LatestFirmwareURI"),
            uploadDate = o.optString("FirmwareUploadDate"),
            releaseType = o.optString("ReleaseType"),
            firmwareId = o.optLong("FirmwareId"),
            forceUpdate = o.optBoolean("ForceUpdate", false),
        )
}
