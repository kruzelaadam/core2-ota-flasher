package cz.adamkruzela.core2ota.catalog

/**
 * Explorer component version. The Epson header word at +0x34 packs it as
 * (major << 24) | (minor << 16) | patch, e.g. 1.14.2 ⇒ 0x010E0002.
 */
data class FwVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<FwVersion> {
    init {
        require(major in 0..255) { "major out of range: $major" }
        require(minor in 0..255) { "minor out of range: $minor" }
        require(patch in 0..0xFFFF) { "patch out of range: $patch" }
    }

    /** Unsigned packed form matching the Application literal / compare value. */
    val packed: Long
        get() = ((major.toLong() and 0xFF) shl 24) or
            ((minor.toLong() and 0xFF) shl 16) or
            (patch.toLong() and 0xFFFF)

    override fun toString(): String = "$major.$minor.$patch"

    override fun compareTo(other: FwVersion): Int = packed.compareTo(other.packed)

    companion object {
        private val RE = Regex("""(\d+)\.(\d+)\.(\d+)""")

        fun parse(text: String?): FwVersion? {
            if (text.isNullOrBlank()) return null
            val m = RE.find(text.trim()) ?: return null
            return runCatching {
                FwVersion(
                    major = m.groupValues[1].toInt(),
                    minor = m.groupValues[2].toInt(),
                    patch = m.groupValues[3].toInt(),
                )
            }.getOrNull()
        }

        fun parsePacked(packed: Long): FwVersion =
            FwVersion(
                major = ((packed ushr 24) and 0xFF).toInt(),
                minor = ((packed ushr 16) and 0xFF).toInt(),
                patch = (packed and 0xFFFF).toInt(),
            )
    }
}
