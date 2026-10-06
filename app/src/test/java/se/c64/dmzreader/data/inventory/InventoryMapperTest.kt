package se.c64.dmzreader.data.inventory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.c64.dmzreader.data.db.IssueEntity
import se.c64.dmzreader.testing.TestFiles
import se.c64.dmzreader.testing.inventoryJson
import se.c64.dmzreader.testing.issueJson

class InventoryMapperTest {

    @Test
    fun `every field except sourceNames is carried over`() {
        val dto = InventoryIssueDto(
            issueId = "highscore-1993-17",
            publicationId = "highscore",
            publicationTitle = "HighScore",
            year = 1993,
            issueLabel = "Nr 17",
            sortKey = "1993-17",
            sourceKey = "HighScore-199317",
            sourceNames = listOf("OCR PDF/HighScore-199317.pdf"),
            relatedIssueId = "datormagazin-1993-17",
            coverUrl = "https://dmzarkivet.se/highscore/199317/HighScore_1993-17_001.jpg",
            coverFlags = listOf("rotate_cw"),
            ocrPdfUrl = "https://dmzarkivet.se/pdf/HighScore-199317.pdf",
            ocrPdfSize = 90700078,
            ocrPdfEtag = "\"1503389842.0-90700078-2458978658\"",
            ocrPdfLastModified = "Tue, 22 Aug 2017 08:17:22 GMT",
            largePdfUrl = "ftp://dmzarkivet.se/Large%20PDF/199317-HighScore-Original.pdf",
            largePdfSize = 581483230,
            hqSource = "scans",
            scanPagePattern = "ftp://dmzarkivet.se/x/HighScore_1993-17_{page:03d}.jpg",
            scanPageCount = 32,
            pageCount = 32,
            spreadOffset = 1,
            flags = listOf("supplement"),
        )

        assertEquals(
            IssueEntity(
                issueId = "highscore-1993-17",
                publicationId = "highscore",
                publicationTitle = "HighScore",
                year = 1993,
                issueLabel = "Nr 17",
                sortKey = "1993-17",
                sourceKey = "HighScore-199317",
                relatedIssueId = "datormagazin-1993-17",
                coverUrl = "https://dmzarkivet.se/highscore/199317/HighScore_1993-17_001.jpg",
                coverFlags = listOf("rotate_cw"),
                ocrPdfUrl = "https://dmzarkivet.se/pdf/HighScore-199317.pdf",
                ocrPdfSize = 90700078,
                ocrPdfEtag = "\"1503389842.0-90700078-2458978658\"",
                ocrPdfLastModified = "Tue, 22 Aug 2017 08:17:22 GMT",
                largePdfUrl = "ftp://dmzarkivet.se/Large%20PDF/199317-HighScore-Original.pdf",
                largePdfSize = 581483230,
                hqSource = "scans",
                scanPagePattern = "ftp://dmzarkivet.se/x/HighScore_1993-17_{page:03d}.jpg",
                scanPageCount = 32,
                pageCount = 32,
                spreadOffset = 1,
                flags = listOf("supplement"),
            ),
            dto.toEntity(),
        )
    }

    @Test
    fun `null cover and pdf url stay null`() {
        val json = inventoryJson(
            issues = listOf(
                issueJson("datormagazin-1989-06", coverUrl = null, ocrPdfUrl = null, flags = listOf("missing_ocr_pdf")),
            ),
        )
        val entity = InventoryParser.parse(json).issues.single().toEntity()
        assertNull(entity.coverUrl)
        assertNull(entity.ocrPdfUrl)
        assertNull(entity.ocrPdfSize)
        assertNull(entity.pageCount)
        assertEquals(listOf("missing_ocr_pdf"), entity.flags)
    }

    @Test
    fun `the whole real inventory maps to entities with unique ids`() {
        val entities = InventoryParser.parse(TestFiles.inventory.readText()).issues.map { it.toEntity() }
        assertEquals(185, entities.size)
        assertEquals(185, entities.map { it.issueId }.toSet().size)
    }
}
