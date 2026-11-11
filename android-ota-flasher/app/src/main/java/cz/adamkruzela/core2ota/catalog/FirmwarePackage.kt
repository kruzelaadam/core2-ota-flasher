package cz.adamkruzela.core2ota.catalog

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * One component exactly as described by firmwareDescription.json. [version], [size] and
 * [crc32] are sent to the watch unchanged; [file] holds the byte-identical ZIP entry.
 */
data class FirmwarePart(
    val type: Int,
    val version: String,
    val filename: String,
    val size: Long,
    val crc32: Long,
    val file: File,
    val sha256: String,
    /** Validation summary for the comm log (header fields, inner BT image, …). */
    val detail: String,
) {
    val component: ComponentType? get() = ComponentType.of(type)
    val label: String get() = "${component?.label ?: "type-$type"} $version"
}

data class PreparedPackage(
    val zipFile: File,
    val extractDir: File,
    val packageVersion: String,
    /** In firmwareDescription.json order — Version/Info is sent in this order. */
    val parts: List<FirmwarePart>,
    val zipSha256: String,
    /** Non-fatal observations worth showing to whoever built the ZIP. */
    val notes: List<String>,
)

class FirmwareDownloader(
    private val client: OkHttpClient = OkHttpClient(),
) {
    fun download(
        uri: String,
        destZip: File,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): File {
        destZip.parentFile?.mkdirs()
        val req = Request.Builder().url(uri).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Download HTTP ${resp.code}")
            val body = resp.body ?: error("Empty body")
            val total = body.contentLength()
            body.byteStream().use { input ->
                FileOutputStream(destZip).use { out ->
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
        return destZip
    }

    /**
     * Validates the package without changing a byte and extracts each component.
     * Throws with a message aimed at whoever built the ZIP when anything would make
     * the watch reject it (or would make it flash something other than described).
     */
    fun prepare(zipFile: File, workDir: File): PreparedPackage {
        val extractDir = File(workDir, zipFile.nameWithoutExtension + "_unzipped")
        if (extractDir.exists()) extractDir.deleteRecursively()
        extractDir.mkdirs()
        val notes = mutableListOf<String>()

        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().toList()
            ZipNames.requireSafe(entries.map { it.name })
            val byName = entries.associateBy { it.name }

            val descEntry =
                byName[DESCRIPTION] ?: error("$DESCRIPTION missing at the ZIP root")
            val root = JSONObject(String(zip.getInputStream(descEntry).use { it.readBytes() }, Charsets.UTF_8))
            val meta = root.optJSONObject("meta")
            val packageVersion = meta?.optString("package_version").orEmpty()
            val firmwares = root.getJSONArray("firmwares")

            val types = (0 until firmwares.length()).map { firmwares.getJSONObject(it).getInt("type") }
            require(types.sorted() == ComponentType.entries.map { it.wire }) {
                "$DESCRIPTION must list each type 0,1,2,3 exactly once (found $types)"
            }
            meta?.optInt("file_count", -1)?.takeIf { it >= 0 && it != firmwares.length() }?.let {
                notes += "meta.file_count=$it but ${firmwares.length()} firmwares are listed"
            }

            val described = HashSet<String>()
            val parts =
                (0 until firmwares.length()).map { i ->
                    val o = firmwares.getJSONObject(i)
                    val type = ComponentType.of(o.getInt("type"))!!
                    val name = o.getString("filename")
                    require(described.add(name)) { "$DESCRIPTION uses $name for two components" }
                    val entry = byName[name] ?: error("${type.label}: $name listed but not in the ZIP")
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }

                    val actualCrc = crc32(bytes)
                    require(entry.crc == -1L || entry.crc == actualCrc) {
                        "$name: ZIP entry CRC 0x%08X ≠ data 0x%08X (corrupt ZIP)".format(entry.crc, actualCrc)
                    }
                    val jsonSize = o.getLong("size")
                    require(jsonSize == bytes.size.toLong()) {
                        "$name: description size $jsonSize ≠ file ${bytes.size} B"
                    }
                    val jsonCrc = o.getLong("crc32")
                    require((jsonCrc and 0xffffffffL) == actualCrc) {
                        "$name: description crc32 $jsonCrc ≠ CRC32(file) $actualCrc"
                    }

                    val version = o.optString("version")
                    val detail =
                        if (type == ComponentType.BLUETOOTH) {
                            val bt = BluetoothPackage.check(bytes, name)
                            "inner=${bt.imageName} ${bt.imageSize} B image_index=${bt.imageIndex} " +
                                "mcuboot_hdr=0x${bt.headerSize.toString(16)}"
                        } else {
                            val h = EpsonImage.check(type, bytes, name)
                            if (h.binaryVersion != version) {
                                notes += "${type.label}: description version $version, " +
                                    "header +0x34 says ${h.binaryVersion}"
                            }
                            if (h.suffixBytes > 0) {
                                notes += "${type.label}: ${h.suffixBytes} B after the CRC trailer so the " +
                                    "offered size and crc differ from the sealed image"
                            }
                            "base=0x%X end=0x%X trailer=0x%08X +0x34=%s%s".format(
                                h.base,
                                h.end,
                                h.trailer,
                                h.binaryVersion,
                                if (h.suffixBytes > 0) " suffix=${h.suffixBytes}B" else "",
                            )
                        }

                    val out = File(extractDir, name)
                    out.writeBytes(bytes)
                    FirmwarePart(
                        type = type.wire,
                        version = version,
                        filename = name,
                        size = jsonSize,
                        crc32 = jsonCrc,
                        file = out,
                        sha256 = sha256Hex(bytes),
                        detail = detail,
                    )
                }

            val extra = byName.keys - described - DESCRIPTION
            if (extra.isNotEmpty()) notes += "ZIP has entries not in $DESCRIPTION: ${extra.sorted()}"

            return PreparedPackage(
                zipFile = zipFile,
                extractDir = extractDir,
                packageVersion = packageVersion,
                parts = parts,
                zipSha256 = sha256Hex(zipFile.readBytes()),
                notes = notes,
            )
        }
    }

    private companion object {
        const val DESCRIPTION = "firmwareDescription.json"
    }
}
