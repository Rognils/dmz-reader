package se.c64.dmzreader.data.pdf

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class DownloadProgress(val bytesDownloaded: Long, val totalBytes: Long?) {
    val fraction: Float? get() = totalBytes?.takeIf { it > 0 }?.let { (bytesDownloaded.toFloat() / it).coerceIn(0f, 1f) }
}

sealed interface DownloadResult {
    /** Filen är komplett och ligger på plats som .pdf. */
    data class Success(
        val file: File,
        val size: Long,
        val etag: String?,
        val lastModified: String?,
        /** Sant om nedladdningen fortsatte på en tidigare .part-fil. */
        val resumed: Boolean,
    ) : DownloadResult

    /** Servern gick inte att nå, eller anslutningen bröts. .part-filen ligger kvar. */
    data class NetworkError(val message: String) : DownloadResult

    data class HttpError(val code: Int) : DownloadResult

    data class InsufficientSpace(val requiredBytes: Long, val availableBytes: Long) : DownloadResult

    /** Det som kom stämde inte med den storlek servern uppgav. */
    data class LengthMismatch(val expected: Long, val actual: Long) : DownloadResult
}

/**
 * Hämtar en PDF till läs-cachen, med återupptagning (ADR 0017).
 *
 * - Data skrivs till {issueId}.pdf.part. Filen döps om till .pdf först när
 *   storleken stämmer med den servern uppgav. En .pdf är därför alltid komplett.
 * - Finns en .part-fil sedan tidigare fortsätter nedladdningen med Range och
 *   If-Range. Svarar servern 200 i stället för 206 har filen ändrats (eller så
 *   stöds inte Range), och då börjar nedladdningen om.
 * - Avbryts nedladdningen ligger .part-filen kvar för nästa försök.
 */
class PdfDownloader(
    private val client: OkHttpClient,
    private val cache: PdfCache,
    private val availableBytes: (File) -> Long = { it.usableSpace },
) {
    suspend fun download(
        issueId: String,
        url: String,
        expectedSize: Long? = null,
        onProgress: (DownloadProgress) -> Unit = {},
    ): DownloadResult = withContext(Dispatchers.IO) {
        cache.directory.mkdirs()
        val target = cache.file(issueId)
        val part = cache.partFile(issueId)
        val validatorFile = cache.validatorFile(issueId)

        // Utan validerare går det inte att veta att resten hör till samma fil.
        val validator = validatorFile.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
        if (part.isFile && validator == null) part.delete()
        var have = if (part.isFile) part.length() else 0L

        // Kontroll före start, med storleken från inventory om den är känd.
        if (expectedSize != null) {
            spaceProblem(expectedSize, have)?.let { return@withContext it }
        }

        val request = Request.Builder().url(url).apply {
            if (have > 0 && validator != null) {
                header("Range", "bytes=$have-")
                header("If-Range", validator)
            }
        }.build()
        val call = client.newCall(request)
        // Ett avbrott ska bryta även en läsning som står och väntar på nätet.
        val cancelHandle = coroutineContext.job.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }

        try {
            call.execute().use { response ->
                val total: Long
                val resumed: Boolean
                when (response.code) {
                    206 -> {
                        val range = parseContentRange(response.header("Content-Range"))
                        if (range == null || range.first != have) {
                            // Servern svarade med ett annat intervall än det vi bad om.
                            return@withContext DownloadResult.LengthMismatch(have, range?.first ?: -1)
                        }
                        total = range.second
                        resumed = true
                    }

                    200 -> {
                        // Hela filen från början: den gamla delen gäller inte längre.
                        have = 0
                        part.delete()
                        total = response.body.contentLength()
                        resumed = false
                    }

                    416 -> {
                        // .part-filen är längre än filen på servern. Börja om nästa gång.
                        part.delete()
                        validatorFile.delete()
                        return@withContext DownloadResult.HttpError(416)
                    }

                    else -> return@withContext DownloadResult.HttpError(response.code)
                }

                if (total >= 0) {
                    spaceProblem(total, have)?.let { return@withContext it }
                }
                val newValidator = response.header("ETag") ?: response.header("Last-Modified")
                if (newValidator != null) validatorFile.writeText(newValidator) else validatorFile.delete()

                val written = copy(response, part, append = resumed, startAt = have, total = total, onProgress)
                if (total >= 0 && written != total) {
                    if (written > total) {
                        part.delete()
                        validatorFile.delete()
                    }
                    return@withContext DownloadResult.LengthMismatch(total, written)
                }

                target.delete()
                if (!part.renameTo(target)) {
                    return@withContext DownloadResult.NetworkError("Filen kunde inte läggas på plats.")
                }
                validatorFile.delete()
                DownloadResult.Success(
                    file = target,
                    size = written,
                    etag = response.header("ETag"),
                    lastModified = response.header("Last-Modified"),
                    resumed = resumed,
                )
            }
        } catch (e: IOException) {
            // Ett avbrott visar sig som ett IO-fel från det avbrutna anropet.
            coroutineContext.ensureActive()
            DownloadResult.NetworkError(e.message ?: e.javaClass.simpleName)
        } finally {
            cancelHandle.dispose()
        }
    }

    private suspend fun copy(
        response: Response,
        part: File,
        append: Boolean,
        startAt: Long,
        total: Long,
        onProgress: (DownloadProgress) -> Unit,
    ): Long {
        var written = startAt
        val knownTotal = total.takeIf { it >= 0 }
        onProgress(DownloadProgress(written, knownTotal))
        FileOutputStream(part, append).use { output ->
            val input = response.body.byteStream()
            val buffer = ByteArray(BUFFER_BYTES)
            var sinceReport = 0L
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                written += read
                sinceReport += read
                if (sinceReport >= REPORT_EVERY_BYTES) {
                    sinceReport = 0
                    onProgress(DownloadProgress(written, knownTotal))
                }
            }
            output.fd.sync()
        }
        onProgress(DownloadProgress(written, knownTotal))
        return written
    }

    /** Kräver plats för det som återstår plus tio procent av filens storlek. */
    private fun spaceProblem(totalSize: Long, have: Long): DownloadResult.InsufficientSpace? {
        val required = (totalSize - have).coerceAtLeast(0) + totalSize / 10
        val available = availableBytes(cache.directory)
        return if (available < required) DownloadResult.InsufficientSpace(required, available) else null
    }

    companion object {
        private const val BUFFER_BYTES = 64 * 1024
        private const val REPORT_EVERY_BYTES = 64 * 1024L

        /**
         * En egen klient för PDF:er, skild från bildladdarens. Ingen total
         * tidsgräns: en fil på flera hundra MB får ta den tid den tar.
         */
        fun createHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()

        /** "bytes 100-999/1000" ger (100, 1000). */
        internal fun parseContentRange(header: String?): Pair<Long, Long>? {
            val match = Regex("""bytes (\d+)-(\d+)/(\d+)""").matchEntire(header?.trim() ?: return null) ?: return null
            return match.groupValues[1].toLong() to match.groupValues[3].toLong()
        }
    }
}
