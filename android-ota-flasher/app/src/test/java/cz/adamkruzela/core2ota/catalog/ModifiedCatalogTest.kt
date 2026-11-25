package cz.adamkruzela.core2ota.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModifiedCatalogTest {
    @Test
    fun bundledMirrorHasChangelogAndZipForEachBuild() {
        val packages = ModifiedCatalog().bundled()
        assertEquals(listOf("1.14.2", "1.13.44", "1.13.38"), packages.map { it.name })
        for (pkg in packages) {
            assertTrue(pkg.versionNotes.orEmpty().startsWith(pkg.name))
            assertTrue(pkg.downloadUri.endsWith(pkg.zipFileName))
            assertTrue(pkg.downloadUri.startsWith("https://devices.suunto-operations.com/firmwares/"))
            assertEquals(pkg.versionNotes, ModifiedCatalog().fetchVersionNotes(pkg))
        }
    }

    @Test
    fun coriumChangelogNameIsAccepted() {
        val files =
            listOf(
                DriveListedFile("zip", "folder", "Explorer-fw_1.14.3-null.zip", "application/x-zip-compressed"),
                DriveListedFile("txt", "folder", "Version 1.0.0 (Corium).txt", "text/plain"),
            )
        assertEquals("Version 1.0.0 (Corium).txt", ModifiedCatalog().pickChangelog(files)?.name)
    }
}
