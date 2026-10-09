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
    /** Sant om skanningen finns hel i cachen. */
    fun isCached(issueId: String, scan: HqScan): Boolean = cache.cached(issueId, scan.fileName) != null

    suspend fun page(issueId: String, scan: HqScan, onProgress: (DownloadProgress) -> Unit = {}): HqResult {
        val started = clock()
        cache.cached(issueId, scan.fileName)?.let { return HqResult.Success(it, fromCache = true, millis = 0) }
        return try {
            val file = transfers.reading {
                // En annan hämtning av samma sida kan ha blivit klar medan den här väntade.
                cache.cached(issueId, scan.fileName) ?: run {
                    val part = cache.partFile(issueId, scan.fileName)
                    part.parentFile?.mkdirs()
                    // Filen skrivs till .part och flyttas in i cachen först när storleken stämmer med serverns.
                    val size = source.fetch(scan.url, part) { bytes, total -> onProgress(DownloadProgress(bytes, total)) }
                    cache.commit(issueId, scan.fileName, size)
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
        } catch (e: java.io.IOException) {
            // Storleken stämde inte, eller filen gick inte att lägga på plats. Ingenting hamnade i cachen.
            log("HQ misslyckades: $issueId/${scan.fileName}: ${e.message}")
            HqResult.Failure(ScanFailure.NETWORK, hqFailureMessage(ScanFailure.NETWORK))
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
