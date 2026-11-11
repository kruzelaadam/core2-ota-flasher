package cz.adamkruzela.core2ota.catalog

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipInputStream

/**
 * `type` values of firmwareDescription.json. Not to be confused with the Whiteboard
 * transfer operation (INFO/READ/…) or the watch-internal IPC packet type.
 */
enum class ComponentType(val wire: Int, val label: String) {
    BOOTLOADER1(0, "BL1"),
    BOOTLOADER2(1, "BL2"),
    APPLICATION(2, "Application"),
    BLUETOOTH(3, "Bluetooth"),
    ;

    companion object {
        fun of(wire: Int): ComponentType? = entries.firstOrNull { it.wire == wire }
    }
}

/** Header of an Epson (BL1/BL2/Application) image. All checks are read-only. */
object EpsonImage {
    private const val MAGIC = 0xDA11C10CL
    private const val OFF_MAGIC1 = 0x1C
    private const val OFF_BASE = 0x20
    private const val OFF_END = 0x24
    private const val OFF_MAGIC2 = 0x28
    private const val OFF_VERSION = 0x34
    private const val MIN_LEN = 0x40

    /** CRC-32 of any buffer that ends with its own little-endian CRC-32. */
    const val CRC32_RESIDUE = 0x2144DF1CL

    private val BASE_BY_TYPE =
        mapOf(
            ComponentType.BOOTLOADER1 to 0x2000L,
            ComponentType.BOOTLOADER2 to 0xA000L,
            ComponentType.APPLICATION to 0x12000L,
        )

    data class Header(
        val base: Long,
        val end: Long,
        val trailer: Long,
        val versionWord: Long,
        /** Bytes after the CRC trailer. The sealed prefix stays a valid image. */
        val suffixBytes: Int = 0,
    ) {
        val binaryVersion: String
            get() = "${versionWord ushr 24}.${(versionWord ushr 16) and 0xFF}.${versionWord and 0xFFFF}"
    }

    fun check(type: ComponentType, data: ByteArray, name: String): Header {
        val expectedBase = BASE_BY_TYPE[type] ?: error("$name: ${type.label} is not an Epson image")
        require(data.size >= MIN_LEN) { "$name: ${data.size} B is shorter than the Epson header" }
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        fun u32(at: Int) = b.getInt(at).toLong() and 0xffffffffL
        val m1 = u32(OFF_MAGIC1)
        val m2 = u32(OFF_MAGIC2)
        require(m1 == MAGIC && m2 == MAGIC) {
            "$name: header magic ${hex(m1)}/${hex(m2)} ≠ ${hex(MAGIC)}"
        }
        val base = u32(OFF_BASE)
        require(base == expectedBase) {
            "$name: header base ${hex(base)} ≠ ${hex(expectedBase)} expected for ${type.label}"
        }
        val end = u32(OFF_END)
        val expectedEnd = base + data.size - 4
        // A sealed image always CRCs to the same residue, so two images of the same
        // length are indistinguishable to the watch. Bytes after the trailer change the
        // file size and the description crc while the header-delimited image stays valid.
        val sealedLen =
            if (end == expectedEnd) {
                data.size
            } else {
                val n = (end - base + 4).toInt()
                if (n in MIN_LEN until data.size && crc32(data, 0, n) == CRC32_RESIDUE) {
                    n
                } else {
                    throw IllegalArgumentException(
                        "$name: header end ${hex(end)} ≠ base+len−4 = ${hex(expectedEnd)} " +
                            "(image length changed without updating +0x24)",
                    )
                }
            }
        val trailer = u32(sealedLen - 4)
        val body = crc32(data, 0, sealedLen - 4)
        require(trailer == body) {
            "$name: trailer ${hex(trailer)} ≠ CRC32(sealed image without last 4 B) ${hex(body)}"
        }
        return Header(
            base = base,
            end = end,
            trailer = trailer,
            versionWord = u32(OFF_VERSION),
            suffixBytes = data.size - sealedLen,
        )
    }

    private fun hex(v: Long) = "0x%08X".format(v)
}

/** The type-3 component: a nested ZIP with an MCUboot image and manifest.json. */
object BluetoothPackage {
    private const val MCUBOOT_MAGIC = 0x96F3B83DL

    data class Info(
        val imageName: String,
        val imageSize: Int,
        val imageIndex: Int,
        val headerSize: Int,
    )

    fun check(data: ByteArray, name: String): Info {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(data)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (e.isDirectory) continue
                require(e.name !in entries) { "$name: duplicate inner entry ${e.name}" }
                entries[e.name] = zin.readBytes()
            }
        }
        val manifestBytes = entries["manifest.json"] ?: error("$name: inner manifest.json missing")
        val files = JSONObject(String(manifestBytes, Charsets.UTF_8)).getJSONArray("files")
        require(files.length() >= 1) { "$name: manifest lists no files" }
        val f = files.getJSONObject(0)
        val imageName = f.getString("file")
        val image = entries[imageName] ?: error("$name: manifest file $imageName missing")
        require(image.size >= 32) { "$name: $imageName too short for an MCUboot header" }
        val b = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN)
        val magic = b.getInt(0).toLong() and 0xffffffffL
        require(magic == MCUBOOT_MAGIC) {
            "$name: $imageName magic 0x%08X ≠ MCUboot 0x%08X".format(magic, MCUBOOT_MAGIC)
        }
        return Info(
            imageName = imageName,
            imageSize = image.size,
            imageIndex = f.optString("image_index", "0").toIntOrNull() ?: 0,
            headerSize = b.getShort(8).toInt() and 0xffff,
        )
    }
}

/** Root entry names must be plain file names: no folders, traversal or duplicates. */
object ZipNames {
    fun requireSafe(names: List<String>) {
        val seen = HashSet<String>()
        for (n in names) {
            require(n.isNotBlank()) { "ZIP contains an entry with an empty name" }
            require(!n.contains('/') && !n.contains('\\')) {
                "ZIP entry \"$n\" is not at the root (folders are not allowed)"
            }
            require(n != "." && n != "..") { "ZIP entry \"$n\" is a path component" }
            require(seen.add(n)) { "ZIP contains \"$n\" twice" }
        }
    }
}

internal fun crc32(data: ByteArray, offset: Int = 0, length: Int = data.size): Long =
    CRC32().apply { update(data, offset, length) }.value

internal fun sha256Hex(data: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
