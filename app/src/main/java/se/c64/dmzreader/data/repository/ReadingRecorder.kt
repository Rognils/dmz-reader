package se.c64.dmzreader.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.c64.dmzreader.data.db.ReadingSession
import se.c64.dmzreader.domain.opened
import se.c64.dmzreader.domain.withPagesRead

/**
 * Skriver läsarens läsdata för ett nummer till databasen, utanför renderingens
 * och gesternas kod.
 *
 * Läspositionen skrivs med en kort fördröjning, så att bläddring genom många
 * sidor ger en enda skrivning, och omedelbart med [flush]. Skrivningarna köas
 * och utförs i tur och ordning i [scope], som ska leva längre än läsaren: en
 * skrivning som begärs när läsaren stängs ska hinna bli klar.
 */
class ReadingRecorder(
    private val issueId: String,
    private val repository: ReadingRepository,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val writeDelayMillis: Long = POSITION_WRITE_DELAY_MILLIS,
    private val onError: (Exception) -> Unit = {},
) {
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val lock = Any()
    private var pendingPage: Int? = null
    private var delayedWrite: Job? = null

    init {
        scope.launch {
            for (write in writes) {
                try {
                    write()
                } catch (e: Exception) {
                    onError(e)
                }
            }
        }
    }

    /** Numret öppnades i läsaren. */
    fun opened() {
        val timestamp = now()
        enqueue { repository.update(issueId) { it.opened(timestamp) } }
    }

    /**
     * Ny läsposition. Skrivs [writeDelayMillis] efter den första ändringen som
     * väntar, eller tidigare med [flush]. Fler ändringar under väntan skjuter inte
     * upp skrivningen: den senaste positionen skrivs när tiden är ute.
     */
    fun anchorPage(page: Int) {
        synchronized(lock) {
            pendingPage = page
            if (delayedWrite?.isActive == true) return
            delayedWrite = scope.launch {
                delay(writeDelayMillis)
                writePending()
            }
        }
    }

    /** Skriver en väntande läsposition nu. */
    fun flush() {
        synchronized(lock) {
            delayedWrite?.cancel()
            delayedWrite = null
        }
        writePending()
    }

    private fun writePending() {
        val page = synchronized(lock) { pendingPage.also { pendingPage = null } } ?: return
        enqueue { repository.update(issueId) { it.copy(lastPage = page) } }
    }

    /** [pages] har visats tillräckligt länge för att räknas som lästa. */
    fun pagesRead(pages: List<Int>, pageCount: Int?, clearUnread: Boolean) {
        val timestamp = now()
        enqueue { repository.update(issueId) { it.withPagesRead(pages, pageCount, timestamp, clearUnread) } }
    }

    fun session(session: ReadingSession) {
        enqueue { repository.saveSession(session) }
    }

    /** Skriver det som väntar och tar inte emot mer. Köade skrivningar utförs först. */
    fun close() {
        flush()
        writes.close()
    }

    /** Väntar tills allt som hittills har köats är skrivet. För tester. */
    suspend fun awaitWritten() {
        val marker = CompletableDeferred<Unit>()
        if (writes.trySend { marker.complete(Unit) }.isSuccess) marker.await()
    }

    private fun enqueue(write: suspend () -> Unit) {
        writes.trySend(write)
    }

    companion object {
        const val POSITION_WRITE_DELAY_MILLIS = 1_000L
    }
}
