package se.c64.dmzreader.data.pdf

import android.util.Log
import se.c64.dmzreader.data.db.IssueEntity
import se.c64.dmzreader.data.db.IssueLocalState
import se.c64.dmzreader.data.offline.LocalStateStore
import java.io.File

/**
 * Det läsaren behöver veta om en PDF har bytts i arkivet (planens C7):
 * storlek, ETag eller Last-Modified skiljer sig från inventory.
 */
fun pdfDiffersFromInventory(issue: IssueEntity, size: Long, etag: String?, lastModified: String?): Boolean =
    (issue.ocrPdfSize != null && issue.ocrPdfSize != size) ||
        (issue.ocrPdfEtag != null && etag != null && issue.ocrPdfEtag != etag) ||
        (issue.ocrPdfLastModified != null && lastModified != null && issue.ocrPdfLastModified != lastModified)

/**
 * Läs-PDF:er: en nedladdning åt gången till cachen, och det telefonen har
 * observerat om filen sparas i IssueLocalState.
 */
class PdfRepository(
    private val cache: PdfCache,
    private val downloader: PdfDownloader,
    private val localState: LocalStateStore,
    /** Delas med nedladdningskön: bara en PDF-överföring åt gången i hela appen. */
    private val transfers: TransferCoordinator = TransferCoordinator(),
    private val log: (String) -> Unit = { Log.i(LOG_TAG, it) },
) {
    fun cachedFile(issueId: String): File? = cache.file(issueId).takeIf { it.isFile }

    // Nummer vars cachefil redan har hämtats om i den här körningen. Stämmer filen
    // ändå inte med indexet är det indexet som är fel, och då hämtas den inte om igen.
    private val refetched: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /**
     * Cachefilen för numret, om den går att använda. En fil vars storlek inte
     * stämmer med arkivindexet är från en äldre version av PDF:en (planens C7):
     * den kastas, så att läsaren hämtar den aktuella. Läs-cachen är tillfällig,
     * till skillnad från nedladdningar, som aldrig raderas automatiskt.
     */
    fun usableCachedFile(issue: IssueEntity): File? {
        val file = cachedFile(issue.issueId) ?: return null
        val expected = issue.ocrPdfSize ?: return file
        if (file.length() == expected || !refetched.add(issue.issueId)) return file
        log("${issue.issueId}: cachefilen är från en äldre version av arkivfilen och hämtas om")
        cache.remove(issue.issueId)
        return null
    }

    /** Storleken på läs-cachen i byte. */
    fun cacheBytes(): Long = cache.totalBytes()

    /** Tömmer läs-cachen, även påbörjade nedladdningar. */
    fun clearCache() = cache.clear()

    /** Markerar numret som öppnat och håller cachen under taket. Det öppna numret tas aldrig bort. */
    fun markOpened(issueId: String) {
        cache.touch(issueId)
        cache.trim(keepIssueId = issueId)
    }

    suspend fun download(issue: IssueEntity, onProgress: (DownloadProgress) -> Unit): DownloadResult {
        val url = issue.ocrPdfUrl ?: return DownloadResult.HttpError(404)
        // En läsning går före nedladdningskön, som pausas så länge.
        return transfers.reading {
            // Gör plats i cachen innan nedladdningen börjar.
            cache.trim(keepIssueId = issue.issueId, reserveBytes = issue.ocrPdfSize ?: 0)
            val result = downloader.download(issue.issueId, url, issue.ocrPdfSize, onProgress)
            if (result is DownloadResult.Success) {
                log("${issue.issueId}: nedladdad, ${result.size} byte, återupptagen=${result.resumed}")
                recordDownload(issue, result)
                cache.touch(issue.issueId)
            } else {
                log("${issue.issueId}: nedladdningen avslutades med $result")
            }
            result
        }
    }

    private suspend fun recordDownload(issue: IssueEntity, result: DownloadResult.Success) {
        localState.update(issue.issueId) { current ->
            current.copy(
                observedPdfSize = result.size,
                observedPdfEtag = result.etag,
                observedPdfLastModified = result.lastModified,
                // Ett sidantal som mättes på en annan fil gäller inte längre.
                observedPageCount = current.observedPageCount.takeIf { current.observedPdfSize == result.size },
            )
        }
        if (pdfDiffersFromInventory(issue, result.size, result.etag, result.lastModified)) {
            log(
                "VARNING: " +
                "${issue.issueId}: filen i arkivet skiljer sig från inventory " +
                    "(storlek ${result.size} mot ${issue.ocrPdfSize}, ETag ${result.etag} mot ${issue.ocrPdfEtag})",
            )
        }
    }

    /** Sparar sidantalet som mättes när PDF:en öppnades. */
    suspend fun recordPageCount(issueId: String, pageCount: Int, fileSize: Long) {
        localState.update(issueId) { it.copy(observedPageCount = pageCount, observedPdfSize = fileSize) }
    }

    suspend fun localState(issueId: String): IssueLocalState? = localState.get(issueId)

    /** Sparar användarens förskjutning av uppslagsparningen. */
    suspend fun saveSpreadOffset(issueId: String, offset: Int) {
        localState.update(issueId) { it.copy(detectedSpreadOffset = offset) }
    }

    companion object {
        const val LOG_TAG = "DmzPdf"
    }
}
