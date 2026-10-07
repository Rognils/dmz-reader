package se.c64.dmzreader.data.inventory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import se.c64.dmzreader.testing.TestFiles
import se.c64.dmzreader.testing.inventoryJson
import se.c64.dmzreader.testing.issueJson

/**
 * Valideringen av ett arkivindex i appen (planens D5). Fallen är desamma som i
 * toolchainens tester (tools/tests/test_inventory.py, SchemaValidation och
 * SemanticValidation), för de fält appen använder.
 */
class InventoryValidationTest {
    private val id = "datormagazin-1989-06"

    private fun rejected(text: String): String = try {
        InventoryParser.parse(text)
        fail("skulle ha avvisats")
        ""
    } catch (e: InventoryException) {
        e.message!!
    }

    private fun valid(extra: String = "") = inventoryJson(issues = listOf(issueJson(id, extra = extra)))

    @Test
    fun `the real inventory and the small test inventory are valid`() {
        assertEquals(185, InventoryParser.parse(TestFiles.inventory.readText()).issues.size)
        assertEquals(1, InventoryParser.parse(valid()).issues.size)
    }

    @Test
    fun `wrong schema version is refused with the version in the message`() {
        val message = rejected(inventoryJson(schemaVersion = 2))
        assertTrue(message, "schemaVersion 2" in message && "stöder bara schemaVersion 1" in message)
        assertTrue("schemaVersion 0" in rejected(inventoryJson(schemaVersion = 0)))
    }

    @Test
    fun `dataset version must have the form of a date and a sequence number`() {
        for (bad in listOf("2026-10-06", "2026-10-06-4", "v4", "", "2026-10-06-0004", "20261006-004")) {
            val message = rejected(inventoryJson(datasetVersion = bad))
            assertTrue("$bad: $message", "datasetVersion" in message && "ÅÅÅÅ-MM-DD-NNN" in message)
        }
        assertEquals("2026-10-07-901", InventoryParser.parse(inventoryJson(datasetVersion = "2026-10-07-901")).datasetVersion)
    }

    @Test
    fun `missing required fields are named`() {
        val withoutId = valid().replace("\"issueId\": \"$id\",", "")
        assertEquals("Arkivindexet går inte att läsa: obligatoriska fält saknas: issueId", rejected(withoutId))
        val withoutTwo = valid().replace("\"sortKey\": \"1989-06\",", "").replace("\"year\": 1989,", "")
        val message = rejected(withoutTwo)
        assertTrue(message, "year" in message && "sortKey" in message)
        // Fält på översta nivån.
        assertTrue("datasetVersion" in rejected(valid().replace("\"datasetVersion\": \"2026-10-06-002\",", "")))
        assertTrue("issues" in rejected("""{"schemaVersion": 1, "datasetVersion": "2026-10-06-002", "generatedAt": "x", "generator": "y", "publications": []}"""))
    }

    @Test
    fun `wrong types are refused`() {
        // Ett tal inom citattecken är text, inte ett tal.
        assertEquals("Arkivindexet går inte att läsa: $id: year har fel typ (ska vara ett heltal)", rejected(valid().replace("\"year\": 1989", "\"year\": \"1989\"")))
        assertTrue("issueLabel har fel typ (ska vara text)" in rejected(valid().replace("\"issueLabel\": \"Nr 6\"", "\"issueLabel\": 6")))
        assertTrue("schemaVersion ska vara ett tal" in rejected(valid().replace("\"schemaVersion\": 1", "\"schemaVersion\": \"1\"")))
        assertTrue("fel typ" in rejected(valid().replace("\"coverFlags\": []", "\"coverFlags\": \"rotate_cw\"")))
        assertTrue("fel typ" in rejected(valid().replace("\"ocrPdfSize\": 25672804", "\"ocrPdfSize\": \"stor\"")))
    }

    @Test
    fun `nullable fields may be null or absent`() {
        val nulls = inventoryJson(issues = listOf(issueJson(id, coverUrl = null, ocrPdfUrl = null, flags = listOf("missing_cover", "missing_ocr_pdf"))))
        val issue = InventoryParser.parse(nulls).issues.single()
        assertEquals(null, issue.coverUrl)
        assertEquals(null, issue.ocrPdfUrl)
        assertEquals(null, issue.pageCount)
        assertEquals(null, issue.coverAsset)
        // Valfria fält får saknas helt.
        val absent = valid().replace("\"pageCount\": null,", "").replace("\"spreadOffset\": null,", "").replace("\"relatedIssueId\": null,", "")
        assertEquals(1, InventoryParser.parse(absent).issues.size)
    }

    @Test
    fun `issue ids must be unique`() {
        val message = rejected(inventoryJson(issues = listOf(issueJson(id), issueJson("datormagazin-1989-07"), issueJson(id))))
        assertEquals("Arkivindexet går inte att läsa: issueId är inte unikt: $id", message)
    }

    @Test
    fun `related issue must exist and must not be the issue itself`() {
        fun related(target: String) = valid().replace("\"relatedIssueId\": null", "\"relatedIssueId\": \"$target\"")
        assertEquals(
            "Arkivindexet går inte att läsa: $id: relatedIssueId pekar på datormagazin-1989-99, som inte finns",
            rejected(related("datormagazin-1989-99")),
        )
        assertTrue("pekar på numret självt" in rejected(related(id)))
        // En giltig relation.
        val pair = inventoryJson(
            issues = listOf(
                issueJson(id),
                issueJson("datormagazin-1989-07").replace("\"relatedIssueId\": null", "\"relatedIssueId\": \"$id\""),
            ),
        )
        assertEquals(id, InventoryParser.parse(pair).issues.last().relatedIssueId)
    }

    @Test
    fun `formats of ids, sort keys, urls and cover data are checked`() {
        assertTrue("issueId" in rejected(valid().replace("\"issueId\": \"$id\"", "\"issueId\": \"Datormagazin 1989 6\"")))
        assertTrue("sortKey" in rejected(valid().replace("\"sortKey\": \"1989-06\"", "\"sortKey\": \"89-6\"")))
        assertTrue("sortKey stämmer inte med året" in rejected(valid().replace("\"sortKey\": \"1989-06\"", "\"sortKey\": \"1990-06\"")))
        assertTrue("ocrPdfUrl är inte en https-adress" in rejected(valid().replace("https://dmzarkivet.se/pdf/198906.pdf", "ftp://dmzarkivet.se/pdf/198906.pdf")))
        assertTrue("coverUrl är inte en https-adress" in rejected(valid().replace("https://dmzarkivet.se/magazines", "http://dmzarkivet.se/magazines")))
        assertTrue("okänd coverFlag" in rejected(valid().replace("\"coverFlags\": []", "\"coverFlags\": [\"flip\"]")))
        assertTrue("mer än en vridning" in rejected(valid().replace("\"coverFlags\": []", "\"coverFlags\": [\"rotate_cw\", \"rotate_180\"]")))
        assertTrue("coverAsset" in rejected(valid(extra = ",\n\"coverAsset\": \"../hemlig.webp\"")))
        assertTrue("pageCount måste vara minst 1" in rejected(valid().replace("\"pageCount\": null", "\"pageCount\": 0")))
        assertTrue("år 3000" in rejected(valid().replace("\"year\": 1989", "\"year\": 3000")))
    }

    @Test
    fun `publications must be declared and match`() {
        // I numret står fälten på egna rader; i listan publications står de på samma rad.
        assertTrue("saknas i publications" in rejected(valid().replace("\"publicationId\": \"datormagazin\",\n", "\"publicationId\": \"okand\",\n")))
        assertTrue("publicationTitle stämmer inte" in rejected(valid().replace("\"publicationTitle\": \"Datormagazin\",\n", "\"publicationTitle\": \"DMZ\",\n")))
    }

    @Test
    fun `broken files are explained`() {
        val whole = valid()
        assertTrue("avbruten eller skadad" in rejected(whole.take(whole.length / 2)))
        assertTrue("avbruten eller skadad" in rejected(whole.dropLast(1)))
        assertTrue("filen är tom" in rejected(""))
        assertTrue("filen är tom" in rejected("  \n "))
        assertTrue("inte ett arkivindex" in rejected("PK\u0003\u0004 en zip-fil"))
        assertTrue("inte ett arkivindex" in rejected("[1, 2, 3]"))
        // En säkerhetskopia av misstag: giltig JSON, men inget arkivindex.
        assertTrue("schemaVersion saknas" in rejected("""{"backupFormat": 2, "bookmarks": []}"""))
    }

    @Test
    fun `every message is in Swedish and starts the same way`() {
        val messages = listOf(
            rejected(inventoryJson(schemaVersion = 2)),
            rejected(inventoryJson(datasetVersion = "x")),
            rejected(valid().take(40)),
            rejected(inventoryJson(issues = listOf(issueJson(id), issueJson(id)))),
        )
        assertTrue(messages.all { it.startsWith("Arkivindexet") })
        assertTrue(messages.none { "Unexpected" in it || "Exception" in it || "required for type" in it })
    }
}
