package cz.adamkruzela.core2ota.catalog

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class FirmwarePackageTest {
    @get:Rule val tmp = TemporaryFolder()

    private val firmwareDir = File("../../data/firmware")
    private val downloader = FirmwareDownloader()

    private fun pkg(name: String): File =
        listOf(File(firmwareDir, name), File(firmwareDir, "Corium_1.0.0/$name"))
            .firstOrNull { it.exists() }
            .also { assumeTrue("$name not available", it != null) }!!

    private fun prepare(zip: File) = downloader.prepare(zip, tmp.newFolder())

    private fun descriptionTypes(zip: File): List<Int> =
        ZipFile(zip).use { z ->
            val root = JSONObject(z.getInputStream(z.getEntry("firmwareDescription.json")).reader().readText())
            val fw = root.getJSONArray("firmwares")
            (0 until fw.length()).map { fw.getJSONObject(it).getInt("type") }
        }

    @Test
    fun officialPackagesPassUnchanged() {
        for (name in listOf(
            "Explorer-fw_1.13.38-A-5a5259f.zip",
            "Explorer-fw_1.13.44-A-58ee0fb.zip",
            "Explorer-fw_1.14.2-A-5174928.zip",
        )) {
            val zip = pkg(name)
            val p = prepare(zip)
            assertEquals(name, descriptionTypes(zip), p.parts.map { it.type })
            assertTrue("$name notes: ${p.notes}", p.notes.isEmpty())
            for (part in p.parts) {
                val entryBytes = ZipFile(zip).use { z -> z.getInputStream(z.getEntry(part.filename)).readBytes() }
                assertTrue("${part.filename} extracted bytes differ", entryBytes.contentEquals(part.file.readBytes()))
                if (part.component != ComponentType.BLUETOOTH) {
                    assertEquals(EpsonImage.CRC32_RESIDUE, part.crc32 and 0xffffffffL)
                }
            }
        }
    }

    @Test
    fun coriumRelabelPassesWithVersionNote() {
        val p = prepare(pkg("Explorer-fw_1.14.0-A-c0410001.zip"))
        val app = p.parts.single { it.component == ComponentType.APPLICATION }
        assertEquals("1.14.0", app.version)
        assertTrue(p.notes.toString(), p.notes.any { it.contains("header +0x34 says 1.13.44") })
    }

    @Test
    fun paddedApplicationIsRejected() {
        expectFailure("header end") { prepare(pkg("Explorer-fw_1.13.45-date.zip")) }
    }

    @Test
    fun modifiedApplicationRecipe() {
        val base = pkg("Explorer-fw_1.14.2-A-5174928.zip")
        val entries = readEntries(base)
        val desc = JSONObject(String(entries.getValue("firmwareDescription.json")))
        val appMeta = firmwares(desc).single { it.getInt("type") == 2 }
        val appName = appMeta.getString("filename")
        val app = entries.getValue(appName).copyOf()
        app[0x1000] = (app[0x1000].toInt() xor 0x01).toByte()

        appMeta.put("crc32", crc32(app))
        val unsealed = rebuild(entries + (appName to app) + ("firmwareDescription.json" to desc.toString().toByteArray()))
        expectFailure("trailer") { prepare(unsealed) }

        // A resealed image always hashes to the residue, so the original description stays valid.
        seal(app)
        assertEquals(EpsonImage.CRC32_RESIDUE, crc32(app))
        val p = prepare(rebuild(entries + (appName to app)))
        assertEquals(descriptionTypes(base), p.parts.map { it.type })
        assertTrue(p.notes.toString(), p.notes.isEmpty())
        assertTrue(p.parts.single { it.filename == appName }.file.readBytes().contentEquals(app))
    }

    @Test
    fun appendedBytesAreRejectedEvenWhenResealed() {
        val base = pkg("Explorer-fw_1.14.2-A-5174928.zip")
        val entries = readEntries(base)
        val desc = JSONObject(String(entries.getValue("firmwareDescription.json")))
        val appMeta = firmwares(desc).single { it.getInt("type") == 2 }
        val appName = appMeta.getString("filename")
        val app = entries.getValue(appName).copyOf(entries.getValue(appName).size + 16)
        seal(app)
        appMeta.put("crc32", crc32(app)).put("size", app.size)
        val zip = rebuild(entries + (appName to app) + ("firmwareDescription.json" to desc.toString().toByteArray()))
        val p = prepare(zip)
        assertTrue(p.notes.toString(), p.notes.any { it.contains("16 B after the CRC trailer") })
        assertEquals(app.size.toLong(), p.parts.single { it.filename == appName }.size)
    }

    @Test
    fun transferSuffixIsServedWhole() {
        val base = pkg("Explorer-fw_1.14.2-A-5174928.zip")
        val entries = readEntries(base)
        val desc = JSONObject(String(entries.getValue("firmwareDescription.json")))
        val appMeta = firmwares(desc).single { it.getInt("type") == 2 }
        val appName = appMeta.getString("filename")
        val sealed = entries.getValue(appName)
        val suffix =
            ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                .put("C2XF".toByteArray()).putInt(sealed.size).array()
        val app = sealed + suffix
        appMeta.put("crc32", crc32(app)).put("size", app.size)
        val zip = rebuild(entries + (appName to app) + ("firmwareDescription.json" to desc.toString().toByteArray()))
        val p = prepare(zip)
        val part = p.parts.single { it.filename == appName }
        assertEquals(app.size.toLong(), part.size)
        assertEquals(crc32(app), part.crc32 and 0xffffffffL)
        assertTrue(part.file.readBytes().contentEquals(app))
        assertTrue(p.notes.toString(), p.notes.any { it.contains("16 B after the CRC trailer") })
        assertTrue(part.crc32 and 0xffffffffL != EpsonImage.CRC32_RESIDUE)
    }

    @Test
    fun descriptionMustListEachTypeOnce() {
        val base = pkg("Explorer-fw_1.14.2-A-5174928.zip")
        val entries = readEntries(base)
        val desc = JSONObject(String(entries.getValue("firmwareDescription.json")))
        firmwares(desc).single { it.getInt("type") == 1 }.put("type", 0)
        val zip = rebuild(entries + ("firmwareDescription.json" to desc.toString().toByteArray()))
        expectFailure("exactly once") { prepare(zip) }
    }

    @Test
    fun unsafeRootNamesAreRejected() {
        expectFailure("not at the root") { ZipNames.requireSafe(listOf("a.bin", "x/../b.bin")) }
        expectFailure("not at the root") { ZipNames.requireSafe(listOf("dir\\b.bin")) }
        expectFailure("path component") { ZipNames.requireSafe(listOf("..")) }
        expectFailure("twice") { ZipNames.requireSafe(listOf("a.bin", "a.bin")) }
        ZipNames.requireSafe(listOf("Application_v1.14.2.bin", "firmwareDescription.json"))
    }

    @Test
    fun bluetoothPackageIsReadAsMcuboot() {
        val p = prepare(pkg("Explorer-fw_1.14.2-A-5174928.zip"))
        val bt = p.parts.single { it.component == ComponentType.BLUETOOTH }
        val info = BluetoothPackage.check(bt.file.readBytes(), bt.filename)
        assertEquals("suunto.signed.bin", info.imageName)
        assertEquals(403_038, info.imageSize)
        assertEquals(0, info.imageIndex)
    }

    private fun firmwares(desc: JSONObject): List<JSONObject> {
        val a = desc.getJSONArray("firmwares")
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    private fun seal(image: ByteArray) {
        val crc = crc32(image, 0, image.size - 4)
        ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN).putInt(image.size - 4, crc.toInt())
    }

    private fun readEntries(zip: File): Map<String, ByteArray> =
        ZipFile(zip).use { z ->
            z.entries().toList().associate { e -> e.name to z.getInputStream(e).readBytes() }
        }

    private fun rebuild(entries: Map<String, ByteArray>): File {
        val out = tmp.newFile("pkg-${System.nanoTime()}.zip")
        ZipOutputStream(FileOutputStream(out)).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out
    }

    private fun expectFailure(fragment: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            assertTrue("expected \"$fragment\" in: ${e.message}", e.message.orEmpty().contains(fragment))
            return
        }
        fail("expected a failure containing \"$fragment\"")
    }
}
