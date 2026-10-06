package se.c64.dmzreader.data.pdf

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import se.c64.dmzreader.data.db.IssueEntity
import se.c64.dmzreader.data.db.IssueLocalState
import se.c64.dmzreader.data.db.IssueLocalStateDao
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
    private val localStateDao: IssueLocalStateDao,
) {
    // En PDF-nedladdning åt gången. Nästa väntar tills den förra är klar eller avbruten.
    private val downloadLock = Mutex()

    fun cachedFile(issueId: String): File? = cache.file(issueId).takeIf { it.isFile }

    /** Markerar numret som öppnat och håller cachen under taket. Det öppna numret tas aldrig bort. */
    fun markOpened(issueId: String) {
        cache.touch(issueId)
        cache.trim(keepIssueId = issueId)
    }

    suspend fun download(issue: IssueEntity, onProgress: (DownloadProgress) -> Unit): DownloadResult {
        val url = issue.ocrPdfUrl ?: return DownloadResult.HttpError(404)
        return downloadLock.withLock {
            // Gör plats i cachen innan nedladdningen börjar.
            cache.trim(keepIssueId = issue.issueId, reserveBytes = issue.ocrPdfSize ?: 0)
            val result = downloader.download(issue.issueId, url, issue.ocrPdfSize, onProgress)
            if (result is DownloadResult.Success) {
                Log.i(LOG_TAG, "${issue.issueId}: nedladdad, ${result.size} byte, återupptagen=${result.resumed}")
                recordDownload(issue, result)
                cache.touch(issue.issueId)
            } else {
                Log.i(LOG_TAG, "${issue.issueId}: nedladdningen avslutades med $result")
            }
            result
        }
    }

    private suspend fun recordDownload(issue: IssueEntity, result: DownloadResult.Success) {
        val current = localStateDao.get(issue.issueId) ?: IssueLocalState(issueId = issue.issueId)
        localStateDao.upsert(
            current.copy(
                observedPdfSize = result.size,
                observedPdfEtag = result.etag,
                observedPdfLastModified = result.lastModified,
                // Ett sidantal som mättes på en annan fil gäller inte längre.
                observedPageCount = current.observedPageCount.takeIf { current.observedPdfSize == result.size },
            ),
        )
        if (pdfDiffersFromInventory(issue, result.size, result.etag, result.lastModified)) {
            Log.w(
                LOG_TAG,
                "${issue.issueId}: filen i arkivet skiljer sig från inventory " +
                    "(storlek ${result.size} mot ${issue.ocrPdfSize}, ETag ${result.etag} mot ${issue.ocrPdfEtag})",
            )
        }
    }

    /** Sparar sidantalet som mättes när PDF:en öppnades. */
    suspend fun recordPageCount(issueId: String, pageCount: Int, fileSize: Long) {
        val current = localStateDao.get(issueId) ?: IssueLocalState(issueId = issueId)
        if (current.observedPageCount != pageCount || current.observedPdfSize != fileSize) {
            localStateDao.upsert(current.copy(observedPageCount = pageCount, observedPdfSize = fileSize))
        }
    }

    suspend fun localState(issueId: String): IssueLocalState? = localStateDao.get(issueId)

    /** Sparar användarens förskjutning av uppslagsparningen. */
    suspend fun saveSpreadOffset(issueId: String, offset: Int) {
        val current = localStateDao.get(issueId) ?: IssueLocalState(issueId = issueId)
        localStateDao.upsert(current.copy(detectedSpreadOffset = offset))
    }

    companion object {
        const val LOG_TAG = "DmzPdf"
    }
}
