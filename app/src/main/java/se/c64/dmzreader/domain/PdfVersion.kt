package se.c64.dmzreader.domain

import se.c64.dmzreader.data.db.Bookmark
import se.c64.dmzreader.data.db.IssueEntity
import se.c64.dmzreader.data.db.IssueLocalState

/**
 * Det som identifierar en viss version av ett nummers PDF i arkivet: storlek,
 * ETag och Last-Modified (planens C7). Fält som saknas är okända.
 */
data class PdfVersion(val size: Long?, val etag: String?, val lastModified: String?) {
    val isKnown: Boolean get() = size != null || etag != null || lastModified != null

    /**
     * Sant om någon uppgift som båda sidor känner till skiljer sig. Okända
     * uppgifter räknas inte som skillnader: bättre att missa ett byte än att
     * varna för bokmärken som pekar rätt.
     */
    fun differsFrom(other: PdfVersion): Boolean =
        differs(size, other.size) || differs(etag, other.etag) || differs(lastModified, other.lastModified)

    private fun <T> differs(a: T?, b: T?) = a != null && b != null && a != b
}

/** Versionen som katalogen uppger för numret. */
fun IssueEntity.pdfVersion() = PdfVersion(ocrPdfSize, ocrPdfEtag, ocrPdfLastModified)

/** Versionen telefonen mätte när filen hämtades, eller null om den aldrig har hämtats. */
fun IssueLocalState.observedPdfVersion(): PdfVersion? =
    PdfVersion(observedPdfSize, observedPdfEtag, observedPdfLastModified).takeIf { it.isKnown }

/** Versionen som gällde när bokmärket skapades. */
fun Bookmark.pdfVersion() = PdfVersion(pdfSize, pdfEtag, pdfLastModified)

/**
 * Versionen som sparas i ett nytt bokmärke: den fil telefonen faktiskt har
 * läst i, annars den katalogen uppger.
 */
fun pdfVersionForNewBookmark(issue: IssueEntity?, local: IssueLocalState?): PdfVersion {
    val observed = local?.observedPdfVersion()
    val catalogue = issue?.pdfVersion()
    return PdfVersion(
        size = observed?.size ?: catalogue?.size,
        etag = observed?.etag ?: catalogue?.etag,
        lastModified = observed?.lastModified ?: catalogue?.lastModified,
    )
}

/**
 * Om numrets PDF kan ha bytts i arkivet (planens C7): katalogen uppger en annan
 * version än den telefonen mätte, eller än den som gällde när något av
 * bokmärkena skapades. Då visas "Arkivfilen har uppdaterats – bokmärken kan peka fel".
 */
fun archiveFileChanged(issue: IssueEntity, local: IssueLocalState?, bookmarks: List<Bookmark>): Boolean {
    val catalogue = issue.pdfVersion()
    val observed = local?.observedPdfVersion()
    if (observed != null && catalogue.differsFrom(observed)) return true
    return bookmarks.any { bookmark ->
        val saved = bookmark.pdfVersion()
        catalogue.differsFrom(saved) || (observed != null && observed.differsFrom(saved))
    }
}

const val ARCHIVE_FILE_CHANGED_TEXT = "Arkivfilen har uppdaterats – bokmärken kan peka fel"
const val MISSING_FROM_INVENTORY_TEXT = "Saknas i aktuellt arkivindex"
