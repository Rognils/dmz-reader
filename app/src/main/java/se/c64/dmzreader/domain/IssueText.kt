package se.c64.dmzreader.domain

import se.c64.dmzreader.data.db.IssueEntity
import java.util.Locale

/** Visningstexter för ett nummer. Ren logik utan Android-beroenden. */

const val FLAG_MISSING_OCR_PDF = "missing_ocr_pdf"

/** "Datormagazin 1989 Nr 6". Används som titel och som beskrivning av omslaget för skärmläsare. */
fun IssueEntity.fullTitle(): String = "$publicationTitle $year $issueLabel"

/**
 * Sidantal för nummervyn. pageCount är uppmätt i läs-PDF:en. Saknas det visas
 * antalet skanningar som ett ungefärligt värde. Saknas båda visas ingenting.
 */
fun pageCountText(pageCount: Int?, scanPageCount: Int?): String? = when {
    pageCount != null -> "$pageCount sidor"
    scanPageCount != null -> "≈ $scanPageCount sidor"
    else -> null
}

/** Filstorlek i MB med en decimal och svenskt decimalkomma, t.ex. "25,7 MB". */
fun fileSizeText(bytes: Long?): String? {
    if (bytes == null || bytes <= 0) return null
    val megabytes = bytes / 1_000_000.0
    return String.format(Locale.ROOT, "%.1f", megabytes).replace('.', ',') + " MB"
}

enum class ReadAction {
    /** Numret går att läsa. */
    READ,

    /** Numret har ingen läs-PDF i arkivet. */
    PDF_MISSING,
}

fun IssueEntity.readAction(): ReadAction =
    if (FLAG_MISSING_OCR_PDF in flags || ocrPdfUrl == null) ReadAction.PDF_MISSING else ReadAction.READ

/** Ett nummer som hör ihop med ett annat, och texten på länken dit. */
data class RelatedIssue(val issueId: String, val linkText: String)

/**
 * Relationen via relatedIssueId, åt båda håll och utan specialkod för någon
 * publikation.
 *
 * - Numret pekar själv på [target] (det är en bilaga): "Bilaga till Datormagazin 1993 Nr 17".
 * - Ett annat nummer pekar hit ([pointingHere]): "Med bilagan HighScore Nr 17".
 *
 * Om numret pekar på ett nummer som inte finns i katalogen visas ingen länk.
 */
fun relatedIssueFor(
    issue: IssueEntity,
    target: IssueEntity?,
    pointingHere: List<IssueEntity>,
): RelatedIssue? {
    if (issue.relatedIssueId != null) {
        return target?.let { RelatedIssue(it.issueId, "Bilaga till ${it.fullTitle()}") }
    }
    val supplement = pointingHere.minWithOrNull(compareBy({ it.publicationId }, { it.sortKey })) ?: return null
    return RelatedIssue(supplement.issueId, "Med bilagan ${supplement.publicationTitle} ${supplement.issueLabel}")
}
