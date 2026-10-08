package se.c64.dmzreader.data.inventory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import se.c64.dmzreader.testing.TestFiles
import se.c64.dmzreader.testing.inventoryJson
import se.c64.dmzreader.testing.issueJson

class InventoryParserTest {

    @Test
    fun `the real inventory from the toolchain parses`() {
        val inventory = InventoryParser.parse(TestFiles.inventory.readText())

        assertEquals(1, inventory.schemaVersion)
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}-\d{3}""").matches(inventory.datasetVersion))
        assertEquals(185, inventory.issues.size)
        assertEquals(
            mapOf("datormagazin" to 171, "highscore" to 14),
            inventory.issues.groupingBy { it.publicationId }.eachCount(),
        )
        assertEquals(listOf("Datormagazin", "HighScore"), inventory.publications.map { it.publicationTitle })

        val first = inventory.issues.first { it.issueId == "datormagazin-1986-01" }
        assertEquals(listOf("crop_right_half", "rotate_cw"), first.coverFlags)
        assertEquals("covers/datormagazin-1986-01.webp", first.coverAsset)
        assertTrue(inventory.issues.all { it.coverAsset == "covers/${it.issueId}.webp" })
        assertEquals(28, first.pageCount)
        assertEquals(28, first.scanPageCount)
        assertTrue(first.sourceNames.contains("OCR PDF v2/198601.pdf"))

        val highScore = inventory.issues.first { it.issueId == "highscore-1993-17" }
        assertEquals("datormagazin-1993-17", highScore.relatedIssueId)
        assertEquals(listOf("supplement"), highScore.flags)
        assertEquals(32, highScore.pageCount)  // uppmätt ur PDF:en sedan FAS 8B
        assertTrue(inventory.issues.all { it.pageCount != null })
    }

    @Test
    fun `unknown schemaVersion is rejected with a clear message`() {
        val error = assertThrows(InventoryException.UnsupportedSchemaVersion::class.java) {
            InventoryParser.parse(inventoryJson(schemaVersion = 2))
        }
        assertEquals(2, error.found)
        assertTrue(error.message!!.contains("schemaVersion 2"))
        assertTrue(error.message!!.contains("stöder bara schemaVersion 1"))
    }

    @Test
    fun `schemaVersion is checked before the rest of the structure`() {
        // En framtida version kan se helt annorlunda ut. Felet ska ändå handla om versionen.
        val future = """{"schemaVersion": 7, "tidningar": []}"""
        assertThrows(InventoryException.UnsupportedSchemaVersion::class.java) {
            InventoryParser.parse(future)
        }
    }

    @Test
    fun `missing schemaVersion and broken json are malformed`() {
        assertThrows(InventoryException.Malformed::class.java) { InventoryParser.parse("""{"issues": []}""") }
        assertThrows(InventoryException.Malformed::class.java) { InventoryParser.parse("inte json") }
        assertThrows(InventoryException.Malformed::class.java) { InventoryParser.parse("") }
    }

    @Test
    fun `cover and pdf url may be null`() {
        val json = inventoryJson(
            issues = listOf(
                issueJson(
                    "datormagazin-1989-06",
                    coverUrl = null,
                    ocrPdfUrl = null,
                    flags = listOf("missing_cover", "missing_ocr_pdf"),
                ),
            ),
        )
        val issue = InventoryParser.parse(json).issues.single()
        assertNull(issue.coverUrl)
        assertNull(issue.ocrPdfUrl)
        assertNull(issue.ocrPdfSize)
        assertEquals(listOf("missing_cover", "missing_ocr_pdf"), issue.flags)
    }

    @Test
    fun `cover and pdf url must be present even when null`() {
        val withoutCover = issueJson("datormagazin-1989-06").replace(Regex(""""coverUrl": [^\n]+\n"""), "")
        assertThrows(InventoryException.Malformed::class.java) {
            InventoryParser.parse(inventoryJson(issues = listOf(withoutCover)))
        }
        val withoutPdf = issueJson("datormagazin-1989-06").replace(Regex(""""ocrPdfUrl": [^\n]+\n"""), "")
        assertThrows(InventoryException.Malformed::class.java) {
            InventoryParser.parse(inventoryJson(issues = listOf(withoutPdf)))
        }
    }

    @Test
    fun `optional fields may be absent`() {
        val minimal = """
            {
              "issueId": "datormagazin-1989-06", "publicationId": "datormagazin",
              "publicationTitle": "Datormagazin", "year": 1989, "issueLabel": "Nr 6",
              "sortKey": "1989-06", "sourceKey": "198906", "sourceNames": [],
              "coverUrl": null, "coverFlags": [], "ocrPdfUrl": null, "flags": []
            }
        """.trimIndent()
        val issue = InventoryParser.parse(inventoryJson(issues = listOf(minimal))).issues.single()
        assertNull(issue.relatedIssueId)
        assertNull(issue.scanPageCount)
        assertNull(issue.spreadOffset)
    }

    @Test
    fun `an unknown extra field does not break reading`() {
        val json = inventoryJson(issues = listOf(issueJson("datormagazin-1989-06", extra = ",\n\"nyttFalt\": 1")))
        assertEquals(1, InventoryParser.parse(json).issues.size)
    }

    @Test
    fun `duplicate issueId is rejected`() {
        val json = inventoryJson(issues = List(2) { issueJson("datormagazin-1989-06") })
        val error = assertThrows(InventoryException.Malformed::class.java) { InventoryParser.parse(json) }
        assertTrue(error.message!!.contains("datormagazin-1989-06"))
    }
}
