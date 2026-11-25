package cz.adamkruzela.core2ota.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class ExplorerCatalogTest {
    @Test
    fun bundledListsThePublicExplorerBuildsNewestFirst() {
        val entries = ExplorerCatalog.bundled()
        assertEquals(listOf("1.14.2", "1.13.44", "1.13.38"), entries.map { it.version })
        assertEquals(
            "https://firmware.geo.movescount.com/production/Explorer-fw_1.14.2-A-5174928.zip",
            entries[0].uri,
        )
        assertEquals(0x5174928L, entries[0].firmwareId)
        assertEquals("2026-08-31", entries[1].uploadDate)
        assertEquals("A", entries[2].hwVersion)
    }
}
