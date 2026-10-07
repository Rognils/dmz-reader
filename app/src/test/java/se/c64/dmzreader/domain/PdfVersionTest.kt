package se.c64.dmzreader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.c64.dmzreader.data.db.Bookmark
import se.c64.dmzreader.data.db.IssueLocalState
import se.c64.dmzreader.testing.issueEntity

/** Markeringen "Arkivfilen har uppdaterats" (planens C7). */
class PdfVersionTest {
    private val id = "datormagazin-1989-06"
    private val issue = issueEntity(id).copy(ocrPdfSize = 25_672_804, ocrPdfEtag = "\"a\"", ocrPdfLastModified = "Sat, 09 Sep 2017")

    private fun local(size: Long? = 25_672_804, etag: String? = "\"a\"", modified: String? = "Sat, 09 Sep 2017") =
        IssueLocalState(issueId = id, observedPdfSize = size, observedPdfEtag = etag, observedPdfLastModified = modified)

    private fun bookmark(size: Long? = 25_672_804, etag: String? = "\"a\"", modified: String? = "Sat, 09 Sep 2017") =
        Bookmark(issueId = id, pageNumber = 7, createdAt = 1, updatedAt = 1, pdfSize = size, pdfEtag = etag, pdfLastModified = modified)

    @Test
    fun `nothing is flagged when everything agrees`() {
        assertFalse(archiveFileChanged(issue, null, emptyList()))
        assertFalse(archiveFileChanged(issue, local(), listOf(bookmark())))
    }

    @Test
    fun `a different size, etag or date flags the issue`() {
        // Katalogen har fått en ny version sedan bokmärket skapades.
        assertTrue(archiveFileChanged(issue, null, listOf(bookmark(size = 1))))
        assertTrue(archiveFileChanged(issue, null, listOf(bookmark(etag = "\"b\""))))
        assertTrue(archiveFileChanged(issue, null, listOf(bookmark(modified = "Mon, 01 Jan 2024"))))
        // Ett bokmärke räcker.
        assertTrue(archiveFileChanged(issue, null, listOf(bookmark(), bookmark(etag = "\"b\""))))
        // Filen telefonen hämtade stämmer inte med katalogen.
        assertTrue(archiveFileChanged(issue, local(size = 1), emptyList()))
        // Filen på telefonen är en annan än den bokmärket skapades i.
        val newCatalogue = issue.copy(ocrPdfSize = null, ocrPdfEtag = null, ocrPdfLastModified = null)
        assertTrue(archiveFileChanged(newCatalogue, local(etag = "\"b\""), listOf(bookmark())))
    }

    @Test
    fun `unknown values are not differences`() {
        assertFalse(archiveFileChanged(issue, null, listOf(bookmark(size = null, etag = null, modified = null))))
        assertFalse(archiveFileChanged(issue, local(etag = null, modified = null), listOf(bookmark(size = null))))
        val bare = issue.copy(ocrPdfSize = null, ocrPdfEtag = null, ocrPdfLastModified = null)
        assertFalse(archiveFileChanged(bare, local(), listOf(bookmark())))
        assertFalse(PdfVersion(null, null, null).differsFrom(PdfVersion(1, "x", "y")))
    }

    @Test
    fun `a new bookmark saves the version of the file that was read`() {
        // Filen på telefonen går före katalogen.
        assertEquals(
            PdfVersion(99, "\"lokal\"", "Sat, 09 Sep 2017"),
            pdfVersionForNewBookmark(issue, local(size = 99, etag = "\"lokal\"")),
        )
        // Saknas en uppgift lokalt fylls den på från katalogen.
        assertEquals(PdfVersion(99, "\"a\"", "Sat, 09 Sep 2017"), pdfVersionForNewBookmark(issue, local(size = 99, etag = null, modified = null)))
        assertEquals(issue.pdfVersion(), pdfVersionForNewBookmark(issue, null))
        // Ett nummer som saknas i katalogen: ingenting är känt.
        assertEquals(PdfVersion(null, null, null), pdfVersionForNewBookmark(null, null))
    }
}
