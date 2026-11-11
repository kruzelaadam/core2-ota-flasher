package cz.adamkruzela.core2ota.catalog

import cz.adamkruzela.core2ota.BuildConfig
import org.json.JSONObject

data class ModifiedPackage(
    val name: String,
    val folderId: String,
    val versionTxtFileId: String,
    val zipFileId: String,
    val zipFileName: String,
    /** Filled after selection by reading Version.txt */
    val versionNotes: String? = null,
) {
    val downloadUri: String get() = DriveDownloads.directUrl(zipFileId)
}

/**
 * Modified packages live in a Google Drive mirror ([BuildConfig.MODIFIED_MIRROR_FOLDER_ID]).
 *
 * Each version is a child folder named like `Corium_1.0.0` containing `Version.txt` + an
 * Explorer ZIP. The flasher **scans that parent folder** so newly uploaded Corium_* packages
 * appear without a manual index edit. [BuildConfig.MODIFIED_INDEX_FILE_ID] (`index.json`) is
 * only a fallback if the live scan fails.
 */
class ModifiedCatalog {
    fun fetchIndex(): List<ModifiedPackage> {
        val scanned =
            runCatching { scanMirror(BuildConfig.MODIFIED_MIRROR_FOLDER_ID) }
                .getOrDefault(emptyList())
        if (scanned.isNotEmpty()) return scanned.sortedByDescending { it.name }

        val fromIndex =
            runCatching { fetchIndexFile(BuildConfig.MODIFIED_INDEX_FILE_ID) }
                .getOrDefault(emptyList())
        if (fromIndex.isNotEmpty()) return fromIndex.sortedByDescending { it.name }

        error(
            "No Corium_* packages found in Drive mirror " +
                "${BuildConfig.MODIFIED_MIRROR_FOLDER_ID} (scan empty, index empty)",
        )
    }

    fun fetchVersionNotes(pkg: ModifiedPackage): String =
        DriveDownloads.readText(pkg.versionTxtFileId).trim()

    /** Live listing of Corium_* children under the mirror folder. */
    fun scanMirror(mirrorFolderId: String): List<ModifiedPackage> {
        val children = DriveFolderListing.listFolder(mirrorFolderId)
        val packages = ArrayList<ModifiedPackage>()
        for (child in children) {
            if (!child.isFolder) continue
            if (!child.name.startsWith("Corium_")) continue
            runCatching { loadPackageFolder(child.name, child.id) }
                .onSuccess { packages += it }
        }
        return packages
    }

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
