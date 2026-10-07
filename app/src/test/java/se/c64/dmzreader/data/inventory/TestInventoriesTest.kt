package se.c64.dmzreader.data.inventory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import se.c64.dmzreader.data.settings.ActiveInventory
import se.c64.dmzreader.data.settings.InventorySource
import se.c64.dmzreader.domain.CoverSource
import se.c64.dmzreader.domain.coverSources
import se.c64.dmzreader.testing.TestFiles
import se.c64.dmzreader.ui.settings.inventoryDiffLines
import se.c64.dmzreader.ui.settings.inventorySourceText
import java.io.File

/**
 * Testindexen i tools/testdata/ (byggda av tools/make_test_inventories.py) mot
 * appens validering: samma filer som importeras på telefonen vid verifieringen.
 */
class TestInventoriesTest {
    private val directory = File(TestFiles.inventory.parentFile!!.parentFile, "tools/testdata")
    private fun text(name: String) = File(directory, name).also { check(it.isFile) { "$it saknas" } }.readText()

    private fun rejected(name: String): String = try {
        InventoryParser.parse(text(name))
        fail("$name skulle ha avvisats")
        ""
    } catch (e: InventoryException) {
        e.message!!
    }

    private val real = InventoryParser.parse(TestFiles.inventory.readText()).issues.map { it.toEntity() }
    private val abc = InventoryParser.parse(text("inventory_test_abc.json"))

    @Test
    fun `the valid test index has one new, one removed and one changed issue`() {
        val diff = diffInventories(real, abc.issues.map { it.toEntity() })
        assertEquals(listOf("datormagazin-1996-01"), diff.added)
        assertEquals(listOf("datormagazin-1986-04"), diff.removed)
        assertEquals(listOf("datormagazin-1995-14"), diff.changed)
        assertEquals("2026-10-08-901", abc.datasetVersion)
        assertEquals(185, abc.issues.size)
    }

    @Test
    fun `the summary before import names the differences`() {
        val diff = diffInventories(real, abc.issues.map { it.toEntity() })
        val lines = inventoryDiffLines("2026-10-06-004", abc.datasetVersion, abc.issues.size, diff)
        assertEquals("Aktivt index: 2026-10-06-004", lines[0])
        assertEquals("Nytt index: 2026-10-08-901, 185 nummer", lines[1])
        assertTrue("1 nya nummer: datormagazin-1996-01" in lines)
        assertTrue("1 borttagna nummer: datormagazin-1986-04" in lines)
        assertTrue("1 ändrade nummer: datormagazin-1995-14" in lines)
        assertTrue(lines.last().startsWith("Favoriter, bokmärken, läsdata och nedladdade nummer ändras inte."))

        // Samma index igen, och ett med många ändringar.
        val same = inventoryDiffLines("x", "x", 185, diffInventories(real, real))
        assertTrue("0 nya nummer" in same && "0 borttagna nummer" in same && "0 ändrade nummer" in same)
        assertEquals("Det nya indexet har samma innehåll som det aktiva.", same.last())
        val many = inventoryDiffLines(null, "y", 0, diffInventories(real, emptyList()))
        assertEquals("Aktivt index: inget", many[0])
        assertTrue(many.any { it.startsWith("185 borttagna nummer: ") && it.endsWith(" med flera") })
    }

    @Test
    fun `the new issue has a thumbnail hint that the app does not have`() {
        val issue = abc.issues.first { it.issueId == "datormagazin-1996-01" }.toEntity()
        val bundled = real.mapNotNull { it.coverAsset }.toSet()
        assertEquals("covers/datormagazin-1996-01.webp", issue.coverAsset)
        // Miniatyren finns inte: omslaget hämtas från webben.
        val source = coverSources(issue, bundled).single() as CoverSource.Remote
        assertEquals("https://dmzarkivet.se/magazines/199515/DMZ_1995-15-001.jpg", source.url)
        assertEquals("https://dmzarkivet.se/pdf/198601.pdf", issue.ocrPdfUrl)
    }

    @Test
    fun `every broken test index is refused with a clear message`() {
        assertEquals(
            "Arkivindexet har schemaVersion 2. Den här appversionen stöder bara schemaVersion 1.",
            rejected("inventory_broken_schema.json"),
        )
        assertEquals(
            "Arkivindexet går inte att läsa: issueId är inte unikt: datormagazin-1986-01",
            rejected("inventory_broken_duplicate.json"),
        )
        assertEquals(
            "Arkivindexet går inte att läsa: datormagazin-1996-01: relatedIssueId pekar på datormagazin-1999-99, som inte finns",
            rejected("inventory_broken_related.json"),
        )
        assertEquals(
            "Arkivindexet går inte att läsa: filen är avbruten eller skadad (JSON-texten tar slut mitt i)",
            rejected("inventory_broken_truncated.json"),
        )
    }

    @Test
    fun `the source of the active index is described`() {
        assertEquals("Inbyggt i appen", inventorySourceText(ActiveInventory("2026-10-06-004", 1)))
        assertTrue(
            inventorySourceText(ActiveInventory("2026-10-08-901", 1, InventorySource.IMPORTED, 1_791_400_000_000))
                .startsWith("Importerat 7 oktober 2026"),
        )
        assertEquals("Importerat", inventorySourceText(ActiveInventory("x", 1, InventorySource.IMPORTED, null)))
        assertEquals("Inget index är inläst", inventorySourceText(null))
    }
}
