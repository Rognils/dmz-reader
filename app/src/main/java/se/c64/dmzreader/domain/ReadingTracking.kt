package se.c64.dmzreader.domain

import se.c64.dmzreader.data.db.ReadingSession

/** Så länge ska en sida ha visats i följd för att räknas som läst (planens C10). */
const val PAGE_READ_MILLIS = 1_500L

/**
 * Tidtagning för när sidorna på en skärm räknas som lästa.
 *
 * Klockan går bara medan sidan visas färdigrenderad och appen ligger i
 * förgrunden ([resume] och [pause]). Byter skärmen sidor börjar tiden om från
 * noll, så snabbläddring räknas inte. Tiderna är millisekunder från en klocka
 * som anroparen väljer.
 */
class PageReadTimer(private val thresholdMillis: Long = PAGE_READ_MILLIS) {
    private var pages: List<Int> = emptyList()
    private var accumulated = 0L
    private var runningSince: Long? = null
    private var counted = false

    /** Sidorna som visas nu. Samma sidor som förut ändrar ingenting. */
    fun setScreen(pages: List<Int>) {
        if (pages == this.pages) return
        this.pages = pages
        accumulated = 0
        runningSince = null
        counted = false
    }

    fun resume(now: Long) {
        if (runningSince == null && !counted && pages.isNotEmpty()) runningSince = now
    }

    fun pause(now: Long) {
        runningSince?.let { accumulated += now - it }
        runningSince = null
    }

    /** Tid kvar tills sidorna räknas som lästa, eller null om klockan står still. */
    fun remaining(now: Long): Long? {
        val since = runningSince ?: return null
        return (thresholdMillis - accumulated - (now - since)).coerceAtLeast(0)
    }

    /** Sidorna som nu räknas som lästa. Varje skärm lämnas ut en enda gång. */
    fun collect(now: Long): List<Int> {
        if (counted || remaining(now) != 0L) return emptyList()
        counted = true
        runningSince = null
        return pages
    }
}

/** Sessioner kortare än så sparas inte (planens C6). */
const val MIN_SESSION_SECONDS = 5

/** En pågående lässession (planens C6). Tiderna är klocktid i millisekunder. */
class SessionTracker(private val issueId: String) {
    private var startedAt: Long? = null
    private var startPage = 1
    private var endPage = 1
    private val pagesRead = HashSet<Int>()

    val active: Boolean get() = startedAt != null

    fun start(page: Int, now: Long) {
        if (active) return
        startedAt = now
        startPage = page
        endPage = page
        pagesRead.clear()
    }

    fun moveTo(page: Int) {
        if (active) endPage = page
    }

    fun pagesRead(pages: Collection<Int>) {
        if (active) pagesRead += pages
    }

    /** Avslutar sessionen. Ger null om ingen pågick eller om den var för kort för att sparas. */
    fun end(now: Long): ReadingSession? {
        val started = startedAt ?: return null
        startedAt = null
        val seconds = ((now - started) / 1000).toInt()
        if (seconds < MIN_SESSION_SECONDS) return null
        return ReadingSession(
            issueId = issueId,
            startedAt = started,
            endedAt = now,
            startPage = startPage,
            endPage = endPage,
            pagesViewed = pagesRead.size,
            durationSeconds = seconds,
        )
    }
}

/** Sidorna läsaren arbetar med just nu: skärmen som visas och dess grannar. */
data class RenderFocus(
    val current: List<Int> = emptyList(),
    val next: List<Int> = emptyList(),
    val previous: List<Int> = emptyList(),
)

/** Ordningen i renderingskön. Lägre värde renderas först. */
object RenderUrgency {
    const val CURRENT_PREVIEW = 0
    const val CURRENT = 1
    const val NEXT_PREVIEW = 2
    const val PREVIOUS_PREVIEW = 3
    const val NEXT = 4
    const val PREVIOUS = 5
    const val OTHER = 6
    const val THUMBNAIL = 7
}

/**
 * Hur bråttom en sidrendering har: aktuell sida, sedan nästa, sedan föregående.
 * Grannsidornas förhandsbilder går före deras fulla rendering, så att det
 * alltid finns något att visa även när kön är upptagen.
 */
fun renderUrgency(page: Int, preview: Boolean, focus: RenderFocus): Int = when (page) {
    in focus.current -> if (preview) RenderUrgency.CURRENT_PREVIEW else RenderUrgency.CURRENT
    in focus.next -> if (preview) RenderUrgency.NEXT_PREVIEW else RenderUrgency.NEXT
    in focus.previous -> if (preview) RenderUrgency.PREVIOUS_PREVIEW else RenderUrgency.PREVIOUS
    else -> RenderUrgency.OTHER
}
