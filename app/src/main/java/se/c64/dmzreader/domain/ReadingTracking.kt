package se.c64.dmzreader.domain

import se.c64.dmzreader.data.db.ReadingSession
import java.time.Instant
import java.time.ZoneId

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

/** Sessioner med kortare aktiv tid än så sparas inte (planens C6). */
const val MIN_SESSION_SECONDS = 5

/** Efter så här lång tid utan interaktion räknas tiden inte längre som lästid. */
const val SESSION_IDLE_LIMIT_MILLIS = 5 * 60_000L

/** Så ofta skrivs en pågående session till databasen. Det är också det mesta som kan gå förlorat vid en krasch. */
const val SESSION_SAVE_INTERVAL_MILLIS = 30_000L

/** Så lång stund efter ett sidbyte skrivs sessionen, så att snabb bläddring ger en enda skrivning. */
const val SESSION_PAGE_SAVE_DELAY_MILLIS = 2_000L

/**
 * En pågående lässession (planens C6 och ADR 0021).
 *
 * Lästiden är AKTIV tid: klockan går bara när läsaren är i förgrunden och det
 * har förekommit en interaktion (sidbyte, tryck, zoom eller panorering) under de
 * senaste [idleLimitMillis]. En tänd skärm som ingen rör ger alltså högst
 * [idleLimitMillis] lästid efter den sista interaktionen.
 *
 * Två klockor används: [start] och [snapshot] får klocktiden (för startedAt och
 * endedAt), och allt som mäter längd får en monoton klocka som inte påverkas av
 * att telefonens tid ställs om.
 *
 * En session delas vid lokal midnatt i [zone]: den som pågår avslutas 23:59:59
 * och en ny börjar 00:00 på samma nummer och sida. Lästiden hamnar då på rätt
 * dag och i rätt vecka (planens E12). De avslutade delarna hämtas med [takeCompleted].
 */
class SessionTracker(
    private val issueId: String,
    private val idleLimitMillis: Long = SESSION_IDLE_LIMIT_MILLIS,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private var startedAt: Long? = null
    private var startedElapsed = 0L
    private var startPage = 1
    private var endPage = 1
    private val pagesRead = HashSet<Int>()

    private var foreground = false
    private var activeMillis = 0L
    private var accruedUntil = 0L
    private var lastInteraction = 0L

    /** Sista stunden som räknades som aktiv, på den monotona klockan. */
    private var lastActive = 0L
    private var backgroundedAt: Long? = null

    /** Nästa lokala midnatt, på den monotona klockan. */
    private var midnightElapsed = Long.MAX_VALUE
    private val completed = ArrayList<ReadingSession>()

    val active: Boolean get() = startedAt != null

    /** Aktiv tid hittills, i millisekunder. */
    fun activeMillis(elapsed: Long): Long {
        accrue(elapsed)
        return activeMillis
    }

    /** Läsaren öppnas på [page]. Att öppna läsaren räknas som en interaktion. */
    fun start(page: Int, wallClock: Long, elapsed: Long) {
        if (active) return
        startedAt = wallClock
        startedElapsed = elapsed
        startPage = page
        endPage = page
        pagesRead.clear()
        foreground = true
        activeMillis = 0
        accruedUntil = elapsed
        lastInteraction = elapsed
        lastActive = elapsed
        backgroundedAt = null
        midnightElapsed = elapsed + (nextMidnight(wallClock) - wallClock)
    }

    private fun nextMidnight(wallClock: Long): Long =
        Instant.ofEpochMilli(wallClock).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * Delar sessionen vid varje lokal midnatt som har passerats före [elapsed].
     * Förgrund, senaste interaktion och sida följer med till den nya sessionen.
     */
    private fun splitAtMidnight(elapsed: Long) {
        while (active && elapsed >= midnightElapsed) {
            val midnight = midnightElapsed
            accrueUntil(midnight)
            val midnightWall = startedAt!! + (midnight - startedElapsed)
            current()?.let { completed += it.copy(endedAt = minOf(it.endedAt, midnightWall - 1)) }
            startedAt = midnightWall
            startedElapsed = midnight
            startPage = endPage
            pagesRead.clear()
            activeMillis = 0
            midnightElapsed = midnight + (nextMidnight(midnightWall) - midnightWall)
        }
    }

    /**
     * Sessioner som avslutades vid midnatt, äldst först. Varje session lämnas ut en gång
     * och ska skrivas som avslutad före den pågående.
     */
    fun takeCompleted(): List<ReadingSession> = completed.toList().also { completed.clear() }

    /** Lägger till den aktiva tiden fram till [elapsed]. */
    private fun accrue(elapsed: Long) {
        splitAtMidnight(elapsed)
        accrueUntil(elapsed)
    }

    private fun accrueUntil(elapsed: Long) {
        if (!active || elapsed <= accruedUntil) return
        if (foreground) {
            val activeUntil = minOf(elapsed, lastInteraction + idleLimitMillis)
            if (activeUntil > accruedUntil) {
                activeMillis += activeUntil - accruedUntil
                lastActive = activeUntil
            }
        }
        accruedUntil = elapsed
    }

    /** Användaren gjorde något: bytte sida, tryckte, zoomade eller panorerade. */
    fun interaction(elapsed: Long) {
        if (!active) return
        accrue(elapsed)
        if (foreground) {
            lastInteraction = elapsed
            lastActive = elapsed
        }
    }

    fun moveTo(page: Int) {
        if (active) endPage = page
    }

    fun pagesRead(pages: Collection<Int>) {
        if (active) pagesRead += pages
    }

    /** Läsaren lämnar skärmen. Klockan stannar. */
    fun background(elapsed: Long) {
        if (!active || !foreground) return
        accrue(elapsed)
        foreground = false
        backgroundedAt = elapsed
    }

    /**
     * Läsaren syns igen. Det räknas som en interaktion.
     * Returnerar falskt om pausen var längre än inaktivitetsgränsen: då ska
     * sessionen avslutas och en ny börjas, så att en session inte sträcker sig över dagar.
     */
    fun foreground(elapsed: Long): Boolean {
        if (!active || foreground) return true
        val away = elapsed - (backgroundedAt ?: elapsed)
        if (away > idleLimitMillis) return false
        splitAtMidnight(elapsed)
        accruedUntil = elapsed
        foreground = true
        backgroundedAt = null
        lastInteraction = elapsed
        lastActive = elapsed
        return true
    }

    /**
     * Sessionen som den ser ut nu, att skriva till databasen. null om ingen pågår.
     * endedAt är den sista stunden som räknades som aktiv, inte stunden då raden skrevs.
     */
    fun snapshot(elapsed: Long): ReadingSession? {
        if (!active) return null
        accrue(elapsed)
        return current()
    }

    private fun current(): ReadingSession? {
        val started = startedAt ?: return null
        return ReadingSession(
            issueId = issueId,
            startedAt = started,
            // Efter en delning vid midnatt kan den sista aktiva stunden ligga före sessionens början.
            endedAt = started + (lastActive - startedElapsed).coerceAtLeast(0),
            startPage = startPage,
            endPage = endPage,
            pagesViewed = pagesRead.size,
            durationSeconds = (activeMillis / 1000).toInt(),
        )
    }

    /** Avslutar sessionen och returnerar den som den slutade. null om ingen pågick. */
    fun end(elapsed: Long): ReadingSession? {
        val session = snapshot(elapsed) ?: return null
        startedAt = null
        foreground = false
        return session
    }
}

/** Sant om en avslutad session ska ligga kvar: den har minst [MIN_SESSION_SECONDS] aktiv tid. */
fun ReadingSession.isWorthKeeping(): Boolean = durationSeconds >= MIN_SESSION_SECONDS

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
