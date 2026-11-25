package cz.adamkruzela.core2ota.catalog

import cz.adamkruzela.core2ota.BuildConfig
import org.json.JSONObject

data class ModifiedPackage(
    val name: String,
    val folderId: String,
    val versionTxtFileId: String,
    val zipFileId: String,
    val zipFileName: String,
    /** Filled after selection by reading Version.txt, or already set for a baked package. */
    val versionNotes: String? = null,
    /** When set, the ZIP is fetched from this URL instead of a Drive file id. */
    val zipUrl: String = "",
) {
    val downloadUri: String
        get() = zipUrl.ifBlank { DriveDownloads.directUrl(zipFileId) }
}

/**
 * Modified packages live in a Google Drive mirror ([BuildConfig.MODIFIED_MIRROR_FOLDER_ID]).
 *
 * Each version is a child folder containing `Version.txt` and a firmware ZIP
 * (`Corium_1.0.0`, `1.14.2`, …). The flasher scans that parent so a new folder shows up
 * without an app update. [BuildConfig.MODIFIED_INDEX_FILE_ID] is the next fallback.
 * If the folder is not link-shared, [bundled] still serves the packages that are in
 * `Suunto Core 2 firmware`, with the ZIP taken from the public CDN.
 */
class ModifiedCatalog {
    fun fetchIndex(): List<ModifiedPackage> {
        val mirror = BuildConfig.MODIFIED_MIRROR_FOLDER_ID
        if (mirror.isNotBlank()) {
            val scanned = runCatching { scanMirror(mirror) }.getOrDefault(emptyList())
            if (scanned.isNotEmpty()) return sort(scanned)
        }

        if (BuildConfig.MODIFIED_INDEX_FILE_ID.isNotBlank()) {
            val fromIndex =
                runCatching { fetchIndexFile(BuildConfig.MODIFIED_INDEX_FILE_ID) }
                    .getOrDefault(emptyList())
            if (fromIndex.isNotEmpty()) return sort(fromIndex)
        }

        return sort(bundled())
    }

    fun fetchVersionNotes(pkg: ModifiedPackage): String {
        if (pkg.versionTxtFileId.isBlank()) return pkg.versionNotes.orEmpty().trim()
        return DriveDownloads.readText(pkg.versionTxtFileId).trim()
    }

    /** Live listing of package folders under the mirror. A folder without both files is skipped. */
    fun scanMirror(mirrorFolderId: String): List<ModifiedPackage> {
        val children = DriveFolderListing.listFolder(mirrorFolderId)
        val packages = ArrayList<ModifiedPackage>()
        for (child in children) {
            if (!child.isFolder) continue
            runCatching { loadPackageFolder(child.name, child.id) }
                .onSuccess { packages += it }
        }
        return packages
    }

    /** Same three packages as the Drive mirror, readable without a link-shared folder. */
    fun bundled(): List<ModifiedPackage> =
        listOf(
            baked(
                "1.14.2",
                "Explorer-fw_1.14.2-A-5174928.zip",
                """
                1.14.2
                Staged 10 Nov 2026. Package built 2026-11-09.

                - Improved altimeter and barometer measurements
                - Manual compass calibration
                - Countdown timer view
                - Weather trend on the time and altimeter views
                - Sea level pressure on the altimeter view

                Application 1.14.2, bootloaders 1.0.44, Bluetooth 1.0.54.
                Explorer-fw_1.14.2-A-5174928.zip
                """.trimIndent(),
            ),
            baked(
                "1.13.44",
                "Explorer-fw_1.13.44-A-58ee0fb.zip",
                """
                1.13.44
                CDN 31 Aug 2026. Package built 2026-08-28.

                Build between 1.13.38 and 1.14.2. Suunto did not publish a separate release note for it.

                Application 1.13.44, bootloaders 1.0.36, Bluetooth 1.0.46.
                Explorer-fw_1.13.44-A-58ee0fb.zip
                """.trimIndent(),
            ),
            baked(
                "1.13.38",
                "Explorer-fw_1.13.38-A-5a5259f.zip",
                """
                1.13.38
                Staged 3 Aug 2026. Package built 2026-07-20.

                Diving
                - Dive screen layout and unit accuracy on longer dives

                Daily use
                - Manual date after startup when the app is not connected
                - Barometer, raise-to-wake, step count, battery estimate
                - Raise-to-wake default 22:00-06:00
                - Alarms, countdown, watch face text in some languages

                Connectivity
                - Android Bluetooth, Suunto app sync, update speed, sleep and wake

                Application 1.13.38, bootloaders 1.0.34, Bluetooth 1.0.46.
                Explorer-fw_1.13.38-A-5a5259f.zip
                """.trimIndent(),
            ),
        )

    private fun baked(name: String, zipFileName: String, notes: String) =
        ModifiedPackage(
            name = name,
            folderId = "",
            versionTxtFileId = "",
            zipFileId = "",
            zipFileName = zipFileName,
            versionNotes = notes,
            zipUrl = "https://firmware.geo.movescount.com/production/$zipFileName",
        )

    private fun sort(list: List<ModifiedPackage>): List<ModifiedPackage> =
        list.sortedWith(
            compareByDescending {
                FwVersion.parse(it.name.removePrefix("Corium_")) ?: FwVersion(0, 0, 0)
            },
        )

    private fun loadPackageFolder(name: String, folderId: String): ModifiedPackage {
        val files = DriveFolderListing.listFolder(folderId)
        val version =
            files.firstOrNull { it.name.equals("Version.txt", ignoreCase = true) }
                ?: error("$name: missing Version.txt")
        val zip =
            files.firstOrNull {
                it.name.endsWith(".zip", ignoreCase = true) &&
                    (it.name.contains("Explorer", ignoreCase = true) ||
                        it.name.contains("fw", ignoreCase = true) ||
                        it.mimeType.contains("zip", ignoreCase = true))
            } ?: files.firstOrNull { it.name.endsWith(".zip", ignoreCase = true) }
                ?: error("$name: missing firmware .zip")
        return ModifiedPackage(
            name = name,
            folderId = folderId,
            versionTxtFileId = version.id,
            zipFileId = zip.id,
            zipFileName = zip.name,
        )
    }

    private fun fetchIndexFile(indexFileId: String): List<ModifiedPackage> {
        val raw = DriveDownloads.readText(indexFileId)
        val root = JSONObject(raw)
        val arr = root.getJSONArray("packages")
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    ModifiedPackage(
                        name = o.getString("name"),
                        folderId = o.optString("folderId"),
                        versionTxtFileId = o.getString("versionTxtFileId"),
                        zipFileId = o.getString("zipFileId"),
                        zipFileName = o.optString("zipFileName", "firmware.zip"),
                    ),
                )
            }
        }
    }
}
