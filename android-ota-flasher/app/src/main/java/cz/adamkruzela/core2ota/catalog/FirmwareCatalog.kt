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

/**
 * Official Explorer ZIPs published on the movescount CDN. The device catalog at
 * devices.suunto-operations.com answers 401 without an app key, so Original mode
 * uses this list (and merges anything a configured key still returns).
 */
object ExplorerCatalog {
    private const val CDN = "https://firmware.geo.movescount.com/production/"

    fun bundled(): List<CatalogEntry> =
        listOf(
            row("1.14.2", "Explorer-fw_1.14.2-A-5174928.zip", "2026-11-10"),
            row("1.13.44", "Explorer-fw_1.13.44-A-58ee0fb.zip", "2026-08-31"),
            row("1.13.38", "Explorer-fw_1.13.38-A-5a5259f.zip", "2026-08-03"),
        ).sortedWith(compareByDescending { FwVersion.parse(it.version) ?: FwVersion(0, 0, 0) })

    private fun row(version: String, fileName: String, date: String): CatalogEntry {
        val uri = CDN + fileName
        return CatalogEntry(
            deviceName = "Explorer",
            hwVersion = "A",
            version = version,
            uri = uri,
            uploadDate = date,
            releaseType = "public",
            firmwareId = FirmwareIds.packageIdFromUriOrPath(fileName) ?: 0L,
            forceUpdate = false,
        )
    }
}

class FirmwareCatalog(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    private val base = BuildConfig.CATALOG_BASE.trimEnd('/')
    private val appKey = BuildConfig.SUUNTO_APPKEY

    fun fetchHistory(variant: String = "Explorer", hw: String = "A"): List<CatalogEntry> {
        val live =
            if (appKey.isBlank()) {
                emptyList()
            } else {
                runCatching { fetchLiveHistory(variant, hw) }.getOrDefault(emptyList())
            }
        val merged = LinkedHashMap<String, CatalogEntry>()
        for (entry in live) merged.putIfAbsent(entry.version, entry)
        for (entry in ExplorerCatalog.bundled()) merged.putIfAbsent(entry.version, entry)
        return merged.values.sortedWith(compareByDescending { FwVersion.parse(it.version) ?: FwVersion(0, 0, 0) })
    }

    private fun fetchLiveHistory(variant: String, hw: String): List<CatalogEntry> {
        val url = "$base/firmware/devices/$variant/$hw/history?appkey=$appKey"
        val body = get(url)
        val arr = JSONArray(body)
        return buildList {
            for (i in 0 until arr.length()) {
                add(parseEntry(arr.getJSONObject(i)))
            }
        }
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
