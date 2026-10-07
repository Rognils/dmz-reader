package se.c64.dmzreader.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.c64.dmzreader.data.pdf.DownloadProgress
import se.c64.dmzreader.data.pdf.DownloadResult
import se.c64.dmzreader.data.pdf.PageSize
import se.c64.dmzreader.data.pdf.pdfDiffersFromInventory
import se.c64.dmzreader.testing.issueEntity

class ReaderLogicTest {

    @Test
    fun `page indicator for a single page and for a spread`() {
        // Procenttalet är andelen lästa sidor, inte läget i numret.
        assertEquals("Sida 1 av 28 · 0 % läst", pageIndicatorText(listOf(1), 1, 28, pagesRead = 0))
        assertEquals("Sida 25 av 28 · 25 % läst", pageIndicatorText(listOf(25), 25, 28, pagesRead = 7))
        assertEquals("Sida 6–7 av 28 · 25 % läst", pageIndicatorText(listOf(6, 7), 7, 28, pagesRead = 7))
        assertEquals("Sida 28 av 28 · 100 % läst", pageIndicatorText(listOf(28), 28, 28, pagesRead = 28))
    }

    @Test
    fun `download progress text shows megabytes and percent`() {
        assertEquals("Ansluter …", downloadProgressText(null, 15_105_472))
        assertEquals("7,4 av 15,1 MB · 48 %", downloadProgressText(DownloadProgress(7_400_000, 15_105_472), null))
        assertEquals(
            "101,6 av 563,1 MB · 18 %",
            downloadProgressText(DownloadProgress(101_600_000, 563_123_977), 563_123_977),
        )
        // Servern uppgav ingen storlek: storleken från inventory används.
        assertEquals("7,4 av 15,1 MB · 48 %", downloadProgressText(DownloadProgress(7_400_000, null), 15_105_472))
        assertEquals("7,4 MB", downloadProgressText(DownloadProgress(7_400_000, null), null))
    }

    @Test
    fun `failure messages are in plain swedish`() {
        assertTrue(downloadFailureMessage(DownloadResult.NetworkError("x")).contains("Kontrollera att telefonen har nät"))
        assertEquals(
            "Det finns inte plats för numret. Det behövs 619,4 MB, och 120,0 MB är ledigt.",
            downloadFailureMessage(DownloadResult.InsufficientSpace(619_436_374, 120_000_000)),
        )
        assertEquals("Arkivet svarade med ett fel (kod 500).", downloadFailureMessage(DownloadResult.HttpError(500)))
        assertTrue(downloadFailureMessage(DownloadResult.LengthMismatch(10, 5)).contains("stämde inte"))
    }

    @Test
    fun `a changed archive file is detected on size etag or last modified`() {
        val issue = issueEntity("datormagazin-1989-06").copy(
            ocrPdfSize = 100, ocrPdfEtag = "\"a\"", ocrPdfLastModified = "Sat, 09 Sep 2017 19:49:26 GMT",
        )
        assertFalse(pdfDiffersFromInventory(issue, 100, "\"a\"", "Sat, 09 Sep 2017 19:49:26 GMT"))
        assertTrue(pdfDiffersFromInventory(issue, 101, "\"a\"", "Sat, 09 Sep 2017 19:49:26 GMT"))
        assertTrue(pdfDiffersFromInventory(issue, 100, "\"b\"", "Sat, 09 Sep 2017 19:49:26 GMT"))
        assertTrue(pdfDiffersFromInventory(issue, 100, "\"a\"", "Sun, 10 Sep 2017 00:00:00 GMT"))
        // Det som servern eller inventory inte uppger kan inte jämföras.
        assertFalse(pdfDiffersFromInventory(issue, 100, null, null))
        assertFalse(
            pdfDiffersFromInventory(issue.copy(ocrPdfSize = null, ocrPdfEtag = null, ocrPdfLastModified = null), 5, "x", "y"),
        )
    }

    @Test
    fun `a single page is fitted and centred`() {
        // Stående skärm 1440 × 3120, tabloidsida 810 × 1145 punkter.
        val rect = layoutPages(listOf(PageSize(810, 1145)), 1440f, 3120f).single()
        assertEquals(1440f, rect.width, 0.5f)
        assertEquals(1440f * 1145 / 810, rect.height, 0.5f)
        assertEquals((3120f - rect.height) / 2, rect.top, 0.5f)
        assertEquals(0f, rect.left, 0.5f)
    }

    @Test
    fun `two pages meet at the spine`() {
        // Liggande skärm 3120 × 1440.
        val (left, right) = layoutPages(listOf(PageSize(810, 1145), PageSize(810, 1145)), 3120f, 1440f)
        assertEquals(1440f, left.height, 0.5f)
        assertEquals(left.right, 1560f, 0.5f)
        assertEquals(right.left, 1560f, 0.5f)
        assertEquals(left.width, right.width, 0.5f)
        assertTrue(left.left > 0f)
    }

    @Test
    fun `pages of different sizes are each fitted in their half`() {
        val (left, right) = layoutPages(listOf(PageSize(600, 1200), PageSize(1200, 600)), 2000f, 1000f)
        assertEquals(Rect(500f, 0f, 1000f, 1000f), left)
        assertEquals(Rect(1000f, 250f, 2000f, 750f), right)
        assertTrue(layoutPages(emptyList(), 100f, 100f).isEmpty())
    }

    @Test
    fun `panning is limited to the content`() {
        val content = Rect(0f, 500f, 1000f, 1500f) // 1000 × 1000 mitt på en skärm som är 1000 × 2000
        fun assertOffset(expectedX: Float, expectedY: Float, actual: Offset) {
            assertEquals(expectedX, actual.x, 0.01f)
            assertEquals(expectedY, actual.y, 0.01f)
        }
        // Utan zoom ryms allt: ingen panorering.
        assertOffset(0f, 0f, clampPan(Offset(300f, -200f), 1f, content, 1000f, 2000f))
        // Med zoom 4 är innehållet 4000 × 4000: ±1500 i sidled, ±1000 i höjdled.
        assertOffset(1500f, -1000f, clampPan(Offset(9999f, -9999f), 4f, content, 1000f, 2000f))
        assertOffset(200f, 300f, clampPan(Offset(200f, 300f), 4f, content, 1000f, 2000f))
    }

    @Test
    fun `high resolution rendering is capped below the maximum zoom`() {
        assertTrue(MAX_RENDER_ZOOM < MAX_ZOOM)
        assertTrue(HIGH_RES_ABOVE_ZOOM < MAX_RENDER_ZOOM)
    }
}
