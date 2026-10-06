package se.c64.dmzreader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.c64.dmzreader.testing.issueEntity

class IssueTextTest {

    @Test
    fun `page count prefers the measured value`() {
        assertEquals("28 sidor", pageCountText(pageCount = 28, scanPageCount = 28))
        assertEquals("84 sidor", pageCountText(pageCount = 84, scanPageCount = 80))
    }

    @Test
    fun `page count falls back to an approximate scan count`() {
        assertEquals("≈ 48 sidor", pageCountText(pageCount = null, scanPageCount = 48))
    }

    @Test
    fun `page count is left out when nothing is known`() {
        assertNull(pageCountText(pageCount = null, scanPageCount = null))
    }

    @Test
    fun `file size is megabytes with a decimal comma`() {
        assertEquals("25,7 MB", fileSizeText(25_672_804))
        assertEquals("15,1 MB", fileSizeText(15_105_472))
        assertEquals("563,1 MB", fileSizeText(563_123_977))
        assertEquals("0,5 MB", fileSizeText(500_000))
        assertNull(fileSizeText(null))
        assertNull(fileSizeText(0))
    }

    @Test
    fun `full title names publication year and issue`() {
        assertEquals("Datormagazin 1989 Nr 6", issueEntity("datormagazin-1989-06").fullTitle())
        assertEquals(
            "Datormagazin 1988 Nr 16–17",
            issueEntity("datormagazin-1988-16-17", issueLabel = "Nr 16–17").fullTitle(),
        )
    }

    @Test
    fun `read action depends on the pdf`() {
        assertEquals(ReadAction.READ, issueEntity("datormagazin-1989-06").readAction())
        assertEquals(
            ReadAction.PDF_MISSING,
            issueEntity("datormagazin-1989-06", ocrPdfUrl = null, flags = listOf("missing_ocr_pdf")).readAction(),
        )
        // Flaggan och adressen ska stämma överens, men var och en räcker.
        assertEquals(ReadAction.PDF_MISSING, issueEntity("datormagazin-1989-06", ocrPdfUrl = null).readAction())
        assertEquals(
            ReadAction.PDF_MISSING,
            issueEntity("datormagazin-1989-06", flags = listOf("missing_ocr_pdf")).readAction(),
        )
    }
}
