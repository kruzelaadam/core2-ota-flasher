package cz.adamkruzela.core2ota.catalog

/**
 * Stock Suunto derives `packageId` from the ZIP filename hex suffix, e.g.
 * `Explorer-fw_1.13.44-A-58ee0fb.zip` → `0x58ee0fb`.
 * See FirmwareFileUtils.firmwarePackageId in Suunto.apk.
 */
object FirmwareIds {
    fun packageIdFromUriOrPath(uriOrPath: String): Long? {
        val name = uriOrPath.substringAfterLast('/').substringAfterLast('\\')
        if (name.isBlank()) return null
        val dash = name.lastIndexOf('-')
        val dot = name.lastIndexOf('.')
        if (dash < 0 || dot <= dash + 1) return null
        val hex = name.substring(dash + 1, dot)
        return hex.toLongOrNull(16)
    }

    fun fileNameFromUri(uri: String): String =
        uri.substringAfterLast('/').ifBlank { "explorer-fw.zip" }
}
