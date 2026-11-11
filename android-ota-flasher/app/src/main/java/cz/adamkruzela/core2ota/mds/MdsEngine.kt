package cz.adamkruzela.core2ota.mds

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Chooses the native Whiteboard engine. Must run before any `com.movesense.mds` class
 * is initialised: `SDSInternalWrapper` binds its natives to whichever `libmds` is
 * already loaded (its own `loadLibrary("mds")` fails harmlessly because the APK
 * ships the bundled engine as `libmds_movesense.so`).
 *
 * The bundled Movesense engine cannot subscribe `Update/Transfer/Nordic/Request`
 * on Core 2 (local `404 Failed to get metadata`), so the watch's READ requests never
 * reach the phone. The Suunto app ships a JNI-compatible `libmds.so` that serves the
 * Suunto resources; it is copied out of the installed Suunto APK and loaded instead.
 */
object MdsEngine {
    const val SUUNTO_PACKAGE = "com.stt.android.suunto"

    private const val TAG = "MdsEngine"
    private const val PREFS = "mds_engine"
    private const val KEY_CHOICE = "choice"
    private const val KEY_LAST_KIND = "last_kind"
    private const val KEY_LAST_AT = "last_loaded_at"
    private const val MOVESENSE_LIB = "mds_movesense"
    private const val MDS_SO = "libmds.so"
    private val MISSING_LIB = Regex("\"([^\"/]+\\.so)\" not found")

    private val SYSTEM_LIBS =
        setOf(
            "libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so", "libandroid.so",
            "libEGL.so", "libGLESv1_CM.so", "libGLESv2.so", "libGLESv3.so", "libjnigraphics.so",
            "libOpenSLES.so", "libOpenMAXAL.so", "libmediandk.so", "libvulkan.so",
            "libnativewindow.so", "libbinder_ndk.so", "libstdc++.so", "libsync.so",
            "libneuralnetworks.so", "libcamera2ndk.so", "libaaudio.so", "libamidi.so",
        )

    enum class Choice { AUTO, SUUNTO, MOVESENSE }

    enum class Kind { SUUNTO, MOVESENSE }

    data class Loaded(
        val kind: Kind,
        val description: String,
        val sha256: String?,
        /** Why the preferred engine was not used, if it was not. */
        val note: String?,
    )

    @Volatile
    private var loaded: Loaded? = null

    fun current(): Loaded? = loaded

    fun choice(context: Context): Choice =
        prefs(context).getString(KEY_CHOICE, null)
            ?.let { runCatching { Choice.valueOf(it) }.getOrNull() }
            ?: Choice.AUTO

    /** Takes effect on the next process start. */
    fun setChoice(context: Context, choice: Choice) {
        prefs(context).edit().putString(KEY_CHOICE, choice.name).commit()
    }

    fun suuntoAppVersion(context: Context): String? =
        runCatching {
            val pi = context.packageManager.getPackageInfo(SUUNTO_PACKAGE, 0)
            "${pi.versionName} (${pi.longVersionCode})"
        }.getOrNull()

    @Synchronized
    fun ensureLoaded(context: Context): Loaded {
        loaded?.let { return it }
        val app = context.applicationContext
        val prefs = prefs(app)
        var choice = choice(app)
        val notes = mutableListOf<String>()
        if (choice == Choice.AUTO && lastSuuntoRunCrashedNatively(app, prefs)) {
            notes += "The last run on the Suunto engine ended in a native crash, so Auto " +
                "switched to Movesense. Pick Suunto to try it again."
            choice = Choice.MOVESENSE
            setChoice(app, Choice.MOVESENSE)
        }
        if (choice != Choice.MOVESENSE) {
            try {
                val lib = loadSuunto(app)
                return record(
                    prefs,
                    Loaded(Kind.SUUNTO, lib.description, sha256(lib.file), notes.joinOrNull()),
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Suunto engine unavailable", t)
                notes += "Suunto engine unavailable (${t.message ?: t.javaClass.simpleName}); " +
                    "using bundled Movesense, which cannot receive the watch's OTA requests."
            }
        }
        System.loadLibrary(MOVESENSE_LIB)
        return record(
            prefs,
            Loaded(
                kind = Kind.MOVESENSE,
                description = "Movesense MDS 3.33.7 (bundled)",
                sha256 = bundledSha256(app),
                note = notes.joinOrNull(),
            ),
        )
    }

    private class SuuntoLib(val file: File, val description: String)

    private fun loadSuunto(context: Context): SuuntoLib {
        val pi = context.packageManager.getPackageInfo(SUUNTO_PACKAGE, 0)
        val ai = pi.applicationInfo ?: error("Suunto app has no ApplicationInfo")
        val abi = processAbi()
        val root = File(context.noBackupFilesDir, "mds-engine")
        val dir = File(root, "suunto-${pi.longVersionCode}-$abi")
        root.listFiles()?.filter { it.name.startsWith("suunto-") && it != dir }
            ?.forEach { it.deleteRecursively() }
        dir.mkdirs()
        val apks = listOfNotNull(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())
        val nativeDir = ai.nativeLibraryDir?.let(::File)

        fun extract(name: String): Pair<File, String> {
            val dest = File(dir, name)
            nativeDir?.let { File(it, name) }?.takeIf { it.isFile && it.canRead() }?.let { src ->
                if (dest.length() != src.length()) src.copyTo(dest, overwrite = true)
                return dest to "nativeLibraryDir"
            }
            for (apk in apks) {
                ZipFile(apk).use { zip ->
                    val entry = zip.getEntry("lib/$abi/$name") ?: return@use
                    if (dest.length() != entry.size) {
                        val tmp = File(dir, "$name.tmp")
                        zip.getInputStream(entry).use { input ->
                            tmp.outputStream().use { input.copyTo(it) }
                        }
                        if (!tmp.renameTo(dest)) error("rename $tmp → $dest failed")
                    }
                    return dest to "${File(apk).name}!lib/$abi/$name"
                }
            }
            error("$name for $abi not found in Suunto app (${apks.size} APKs)")
        }

        val loadedNames = mutableSetOf<String>()

        fun loadWithDeps(name: String, depth: Int = 0) {
            if (name in loadedNames) return
            require(depth < 8) { "dependency chain too deep at $name" }
            val (file, _) = extract(name)
            for (dep in elfNeeded(file)) {
                if (dep in SYSTEM_LIBS || dep == name) continue
                loadWithDeps(dep, depth + 1)
            }
            var attempts = 0
            while (true) {
                try {
                    System.load(file.absolutePath)
                    loadedNames += name
                    return
                } catch (e: UnsatisfiedLinkError) {
                    val missing = MISSING_LIB.find(e.message.orEmpty())?.groupValues?.get(1)
                    if (missing == null || missing in loadedNames || ++attempts > 4) throw e
                    loadWithDeps(missing, depth + 1)
                }
            }
        }

        val (_, source) = extract(MDS_SO)
        loadWithDeps(MDS_SO)
        val extra = (loadedNames - MDS_SO).sorted()
        return SuuntoLib(
            file = File(dir, MDS_SO),
            description =
                "Suunto app ${pi.versionName} (${pi.longVersionCode}) $source" +
                    (if (extra.isNotEmpty()) " + ${extra.joinToString()}" else ""),
        )
    }

    private fun lastSuuntoRunCrashedNatively(context: Context, prefs: SharedPreferences): Boolean {
        if (prefs.getString(KEY_LAST_KIND, null) != Kind.SUUNTO.name) return false
        val since = prefs.getLong(KEY_LAST_AT, 0L)
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        val last =
            runCatching { am.getHistoricalProcessExitReasons(null, 0, 1) }
                .getOrNull()?.firstOrNull() ?: return false
        return last.timestamp >= since && last.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
    }

    private fun record(prefs: SharedPreferences, info: Loaded): Loaded {
        prefs.edit()
            .putString(KEY_LAST_KIND, info.kind.name)
            .putLong(KEY_LAST_AT, System.currentTimeMillis())
            .commit()
        loaded = info
        Log.i(TAG, "engine=${info.kind} ${info.description} sha256=${info.sha256} note=${info.note}")
        return info
    }

    private fun bundledSha256(context: Context): String? =
        runCatching {
            ZipFile(context.applicationInfo.sourceDir).use { zip ->
                val entry = zip.getEntry("lib/${processAbi()}/lib$MOVESENSE_LIB.so") ?: return null
                zip.getInputStream(entry).use { input ->
                    val md = MessageDigest.getInstance("SHA-256")
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        md.update(buf, 0, n)
                    }
                    md.digest().toHex()
                }
            }
        }.getOrNull()

    private fun sha256(file: File): String? =
        runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().toHex()
        }.getOrNull()

    private fun processAbi(): String =
        if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS.first() else Build.SUPPORTED_32_BIT_ABIS.first()

    /** DT_NEEDED entries via the section table; empty if the file cannot be parsed. */
    internal fun elfNeeded(file: File): List<String> =
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val ident = ByteArray(16).also { raf.readFully(it) }
                require(ident[0] == 0x7f.toByte() && ident[1] == 'E'.code.toByte()) { "not ELF" }
                val is64 = ident[4].toInt() == 2
                val order = if (ident[5].toInt() == 2) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN

                fun read(offset: Long, length: Int): ByteBuffer {
                    val b = ByteArray(length)
                    raf.seek(offset)
                    raf.readFully(b)
                    return ByteBuffer.wrap(b).order(order)
                }

                fun u32(b: ByteBuffer, at: Int): Long = b.getInt(at).toLong() and 0xffffffffL

                val hdr = read(0, if (is64) 64 else 52)
                val shoff = if (is64) hdr.getLong(0x28) else u32(hdr, 0x20)
                val shentsize = hdr.getShort(if (is64) 0x3A else 0x2E).toInt() and 0xffff
                val shnum = hdr.getShort(if (is64) 0x3C else 0x30).toInt() and 0xffff

                class Section(val type: Int, val offset: Long, val size: Long, val link: Int)

                val sections =
                    (0 until shnum).map { i ->
                        val s = read(shoff + i.toLong() * shentsize, shentsize)
                        if (is64) {
                            Section(s.getInt(4), s.getLong(0x18), s.getLong(0x20), s.getInt(0x28))
                        } else {
                            Section(s.getInt(4), u32(s, 0x10), u32(s, 0x14), s.getInt(0x18))
                        }
                    }
                val dynamic = sections.first { it.type == 6 }
                val strtab = sections[dynamic.link].let { read(it.offset, it.size.toInt()).array() }
                val dyn = read(dynamic.offset, dynamic.size.toInt())
                val entSize = if (is64) 16 else 8
                buildList {
                    var p = 0
                    while (p + entSize <= dynamic.size) {
                        val tag = if (is64) dyn.getLong(p) else u32(dyn, p)
                        val value = if (is64) dyn.getLong(p + 8) else u32(dyn, p + 4)
                        if (tag == 0L) break
                        if (tag == 1L) {
                            val start = value.toInt()
                            var end = start
                            while (end < strtab.size && strtab[end] != 0.toByte()) end++
                            add(String(strtab, start, end - start, Charsets.US_ASCII))
                        }
                        p += entSize
                    }
                }
            }
        }.getOrDefault(emptyList())

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun List<String>.joinOrNull(): String? = if (isEmpty()) null else joinToString(" ")

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
