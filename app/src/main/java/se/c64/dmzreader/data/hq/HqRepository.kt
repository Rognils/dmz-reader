package se.c64.dmzreader.data.hq

import kotlinx.coroutines.CancellationException
import se.c64.dmzreader.data.pdf.DownloadProgress
import se.c64.dmzreader.data.pdf.TransferCoordinator
import se.c64.dmzreader.domain.HqScan
import java.io.File

sealed interface HqResult {
    /** Skanningen finns lokalt. [fromCache] är sant när ingenting behövde hämtas. */
    data class Success(val file: File, val fromCache: Boolean, val millis: Long) : HqResult

    /** [message] kan visas för användaren. */
    data class Failure(val failure: ScanFailure, val message: String) : HqResult
}

/**
 * HQ-sidor: ur cachen om de finns där, annars från arkivet (planens FAS 11).
 *
 * All trafik går genom appens [TransferCoordinator], som en läsning: högst en
 * överföring mot arkivet åt gången, och en HQ-sida som användaren väntar på går
 * före nedladdningskön.
 */
class HqRepository(
    private val cache: HqCache,
    private val source: ScanSource,
    private val transfers: TransferCoordinator,
    private val clock: () -> Long = System::nanoTime,
    private val log: (String) -> Unit = {},
) {
    /** Sant om skanningen redan finns i cachen. */
    fun isCached(issueId: String, scan: HqScan): Boolean = cache.file(issueId, scan.fileName).isFile

    suspend fun page(issueId: String, scan: HqScan, onProgress: (DownloadProgress) -> Unit = {}): HqResult {
        val started = clock()
        cache.cached(issueId, scan.fileName)?.let { return HqResult.Success(it, fromCache = true, millis = 0) }
        return try {
            val file = transfers.reading {
                // En annan hämtning av samma sida kan ha blivit klar medan den här väntade.
                cache.cached(issueId, scan.fileName) ?: run {
                    val part = cache.partFile(issueId, scan.fileName)
                    part.parentFile?.mkdirs()
                    source.fetch(scan.url, part) { bytes, total -> onProgress(DownloadProgress(bytes, total)) }
                    cache.commit(issueId, scan.fileName)
                }
            }
            val millis = (clock() - started) / 1_000_000
            log("HQ hämtad: $issueId/${scan.fileName}, ${file.length()} byte på $millis ms")
            HqResult.Success(file, fromCache = false, millis = millis)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ScanFetchException) {
            log("HQ misslyckades: $issueId/${scan.fileName}: ${e.message}")
            HqResult.Failure(e.failure, hqFailureMessage(e.failure))
        } catch (e: Exception) {
            log("HQ misslyckades: $issueId/${scan.fileName}: $e")
            HqResult.Failure(ScanFailure.SERVER, hqFailureMessage(ScanFailure.SERVER))
        }
    }

    fun cacheSizeBytes(): Long = cache.sizeBytes()

    fun clearCache(): Long = cache.clear()
}

/** Felmeddelandet för en HQ-sida som inte gick att hämta. PDF-sidan visas i stället. */
fun hqFailureMessage(failure: ScanFailure): String = when (failure) {
    ScanFailure.NETWORK -> "HQ gick inte att hämta: arkivet nås inte. Kontrollera att telefonen har nät. PDF-sidan visas."
    ScanFailure.NOT_FOUND -> "HQ gick inte att hämta: skanningen finns inte i arkivet. PDF-sidan visas."
    ScanFailure.SERVER -> "HQ gick inte att hämta: arkivet svarade med ett fel. PDF-sidan visas."
}
