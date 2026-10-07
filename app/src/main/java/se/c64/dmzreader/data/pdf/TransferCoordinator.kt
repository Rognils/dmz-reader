package se.c64.dmzreader.data.pdf

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Ser till att appen bara har en PDF-överföring igång åt gången mot arkivet,
 * oavsett om den kommer från "Läs" eller från nedladdningskön (planens E9).
 *
 * En läsning går före kön: användaren sitter och väntar på den. Pågår en
 * nedladdning ur kön avbryts dess förfrågan, läsningen körs, och kön får sedan
 * fortsätta. Den avbrutna nedladdningens .part-fil ligger kvar, så den
 * fortsätter där den slutade.
 */
class TransferCoordinator {
    private class Background(val job: Deferred<*>) {
        @Volatile
        var paused = false
    }

    private val transfer = Mutex()
    private val readings = MutableStateFlow(0)

    @Volatile
    private var background: Background? = null

    /** Sant medan en läsning väntar eller pågår. Kön ska då inte påbörja något nytt. */
    val readingActive: Boolean get() = readings.value > 0

    /** Väntar tills ingen läsning väntar eller pågår. */
    suspend fun awaitNoReading() {
        readings.first { it == 0 }
    }

    /**
     * En överföring som användaren väntar på. Pågår en bakgrundsöverföring
     * pausas den, och [block] körs när dess förfrågan har avslutats.
     */
    suspend fun <T> reading(block: suspend () -> T): T {
        readings.update { it + 1 }
        try {
            pauseBackground()
            return transfer.withLock { block() }
        } finally {
            readings.update { it - 1 }
        }
    }

    /**
     * En överföring ur kön. Ger null om den pausades för en läsning; anroparen
     * försöker då igen senare. Avbryts anroparen själv kastas avbrottet vidare
     * som vanligt.
     */
    suspend fun <T : Any> background(block: suspend () -> T): T? {
        awaitNoReading()
        return transfer.withLock {
            // En läsning kan ha anmält sig medan låset togs. Den går före.
            if (readingActive) return@withLock null
            coroutineScope {
                val entry = Background(async { block() })
                background = entry
                try {
                    // Och en kan ha anmält sig precis innan överföringen blev synlig för den.
                    if (readingActive) pause(entry)
                    @Suppress("UNCHECKED_CAST")
                    entry.job.await() as T
                } catch (e: CancellationException) {
                    if (!entry.paused) throw e
                    // Pausad, inte avbruten: anroparen ska leva vidare.
                    currentCoroutineContext().ensureActive()
                    null
                } finally {
                    background = null
                }
            }
        }
    }

    private fun pauseBackground() {
        background?.let(::pause)
    }

    private fun pause(entry: Background) {
        entry.paused = true
        entry.job.cancel()
    }
}
