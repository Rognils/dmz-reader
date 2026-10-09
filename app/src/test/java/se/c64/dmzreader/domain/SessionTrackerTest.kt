package se.c64.dmzreader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.c64.dmzreader.data.db.ReadingSession
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Lässessioner med aktiv tid (planens C6, ADR 0021). Tiderna i testerna är millisekunder på
 * en monoton klocka som börjar på 1 000 000, och klocktiden vid start är 50 000 000.
 */
class SessionTrackerTest {
    private val wall = 50_000_000L
    private val t0 = 1_000_000L
    private val second = 1_000L
    private val minute = 60_000L

    private fun tracker() = SessionTracker("datormagazin-1986-01").apply { start(page = 3, wallClock = wall, elapsed = t0) }

    @Test
    fun `a session records pages, active time and the pages read`() {
        val tracker = SessionTracker("datormagazin-1986-01")
        assertFalse(tracker.active)
        assertNull(tracker.snapshot(t0))
        tracker.start(page = 3, wallClock = wall, elapsed = t0)
        assertTrue(tracker.active)
        tracker.pagesRead(listOf(3))
        tracker.moveTo(4)
        tracker.interaction(t0 + 20 * second)
        tracker.pagesRead(listOf(4, 5))
        tracker.pagesRead(listOf(4))
        tracker.moveTo(25)
        tracker.interaction(t0 + 60 * second)

        val session = tracker.end(t0 + 65_900)!!

        assertEquals("datormagazin-1986-01", session.issueId)
        assertEquals(wall, session.startedAt)
        assertEquals(wall + 65_900, session.endedAt)
        assertEquals(3, session.startPage)
        assertEquals(25, session.endPage)
        // Unika sidor som räknats som lästa, inte sidor som bara bläddrats förbi.
        assertEquals(3, session.pagesViewed)
        assertEquals(65, session.durationSeconds)
        assertFalse(tracker.active)
        assertNull(tracker.end(t0 + 80 * second))
    }

    @Test
    fun `time stops counting five minutes after the last interaction`() {
        // Läsaren ligger öppen i sju minuter utan att någon rör den.
        val tracker = tracker()

        val session = tracker.snapshot(t0 + 7 * minute)!!

        assertEquals(5 * 60, session.durationSeconds)
        // Sessionen slutade när den aktiva tiden tog slut, inte när raden skrevs.
        assertEquals(wall + 5 * minute, session.endedAt)
        // Det ändras inte av att man väntar längre.
        assertEquals(5 * 60, tracker.snapshot(t0 + 3 * 60 * minute)!!.durationSeconds)
    }

    @Test
    fun `an interaction starts the clock again, but the idle gap is not counted`() {
        val tracker = tracker()
        // Sju minuter utan interaktion: fem räknas. Sedan ett sidbyte, och två minuter till.
        tracker.interaction(t0 + 7 * minute)

        val session = tracker.snapshot(t0 + 9 * minute)!!

        assertEquals((5 + 2) * 60, session.durationSeconds)
        assertEquals(wall + 9 * minute, session.endedAt)
    }

    @Test
    fun `interactions within the limit give unbroken time`() {
        val tracker = tracker()
        // Ett sidbyte var fjärde minut i tjugo minuter: ingen lucka är längre än gränsen.
        for (minutes in 4..20 step 4) tracker.interaction(t0 + minutes * minute)

        assertEquals(20 * 60, tracker.snapshot(t0 + 20 * minute)!!.durationSeconds)
        // Därefter går klockan i fem minuter till och stannar.
        assertEquals(25 * 60, tracker.snapshot(t0 + 40 * minute)!!.durationSeconds)
    }

    @Test
    fun `a shorter idle limit can be set`() {
        val tracker = SessionTracker("x", idleLimitMillis = 20 * second).apply { start(1, wall, t0) }
        assertEquals(20, tracker.snapshot(t0 + 2 * minute)!!.durationSeconds)
    }

    @Test
    fun `time in the background is not counted`() {
        val tracker = tracker()
        tracker.background(t0 + 30 * second)
        // En interaktion som kommer medan läsaren inte syns räknas inte.
        tracker.interaction(t0 + 40 * second)
        assertEquals(30, tracker.snapshot(t0 + 2 * minute)!!.durationSeconds)
        assertEquals(wall + 30 * second, tracker.snapshot(t0 + 2 * minute)!!.endedAt)

        // Tillbaka efter två minuter: samma session fortsätter.
        assertTrue(tracker.foreground(t0 + 2 * minute))
        val session = tracker.snapshot(t0 + 2 * minute + 15 * second)!!

        assertEquals(45, session.durationSeconds)
        assertEquals(wall, session.startedAt)
        assertEquals(wall + 2 * minute + 15 * second, session.endedAt)
    }

    @Test
    fun `coming back after more than the idle limit means a new session`() {
        val tracker = tracker()
        tracker.background(t0 + 30 * second)

        assertFalse(tracker.foreground(t0 + 30 * second + 5 * minute + 1))

        // Den gamla sessionen avslutas som den var när läsaren lämnade skärmen.
        val old = tracker.end(t0 + 6 * minute)!!
        assertEquals(30, old.durationSeconds)
        assertEquals(wall + 30 * second, old.endedAt)
        tracker.start(page = 9, wallClock = wall + 6 * minute, elapsed = t0 + 6 * minute)
        assertEquals(9, tracker.snapshot(t0 + 6 * minute + 10 * second)!!.startPage)
        assertEquals(10, tracker.snapshot(t0 + 6 * minute + 10 * second)!!.durationSeconds)
    }

    @Test
    fun `rotation does not end or restart the session`() {
        val tracker = tracker()
        tracker.pagesRead(listOf(3, 4))
        // Vid rotation skapas vyn om och start anropas igen, men ingen bakgrund anmäls.
        tracker.start(page = 9, wallClock = wall + 3 * second, elapsed = t0 + 3 * second)

        val session = tracker.end(t0 + 10 * second)!!

        assertEquals(3, session.startPage)
        assertEquals(wall, session.startedAt)
        assertEquals(10, session.durationSeconds)
        assertEquals(2, session.pagesViewed)
    }

    @Test
    fun `snapshots can be taken repeatedly while the session runs`() {
        val tracker = tracker()
        tracker.interaction(t0 + 20 * second)
        assertEquals(30, tracker.snapshot(t0 + 30 * second)!!.durationSeconds)
        tracker.moveTo(7)
        tracker.interaction(t0 + 50 * second)
        val later = tracker.snapshot(t0 + 60 * second)!!
        assertEquals(60, later.durationSeconds)
        assertEquals(7, later.endPage)
        assertTrue(tracker.active)
        // En ögonblicksbild ändrar ingenting: sluttiden blir densamma som utan dem.
        assertEquals(90, tracker.end(t0 + 90 * second)!!.durationSeconds)
    }

    @Test
    fun `the wall clock being changed does not affect the duration`() {
        val tracker = tracker()
        tracker.interaction(t0 + 40 * second)
        // Telefonens klocka ställs om under läsningen. Bara den monotona klockan används för längden.
        val session = tracker.end(t0 + 60 * second)!!
        assertEquals(60, session.durationSeconds)
        assertEquals(wall + 60 * second, session.endedAt)
    }

    @Test
    fun `a session under five seconds is not worth keeping`() {
        assertFalse(tracker().end(t0 + 4_999)!!.isWorthKeeping())
        assertTrue(tracker().end(t0 + 5_000)!!.isWorthKeeping())
        assertEquals(0, tracker().end(t0)!!.durationSeconds)
        // Sju minuter öppen men bara tre sekunder i förgrunden.
        val tracker = tracker()
        tracker.background(t0 + 3 * second)
        assertFalse(tracker.end(t0 + 7 * minute)!!.isWorthKeeping())
    }

    @Test
    fun `nothing is recorded outside a session`() {
        val tracker = SessionTracker("x")
        tracker.pagesRead(listOf(1))
        tracker.moveTo(5)
        tracker.interaction(t0)
        tracker.background(t0)
        assertTrue(tracker.foreground(t0))
        tracker.start(2, wall, t0)
        val session = tracker.end(t0 + 6 * second)!!
        assertEquals(0, session.pagesViewed)
        assertEquals(2, session.endPage)
    }

    @Test
    fun `a new session after an ended one starts clean`() {
        val tracker = tracker()
        tracker.pagesRead(listOf(1, 2))
        tracker.end(t0 + 10 * second)
        tracker.start(2, wall + minute, t0 + minute)
        val session = tracker.end(t0 + minute + 8 * second)!!
        assertEquals(0, session.pagesViewed)
        assertEquals(8, session.durationSeconds)
        assertEquals(wall + minute, session.startedAt)
    }

    // ---- Delning vid lokal midnatt (planens E12)

    private val zone = ZoneId.of("Europe/Stockholm")
    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    /** Läser från [start] i [minutes] minuter, med en interaktion i minuten. Returnerar alla sessioner. */
    private fun read(start: String, minutes: Int, tracker: SessionTracker = SessionTracker("x", zone = zone)): List<ReadingSession> {
        tracker.start(page = 7, wallClock = at(start), elapsed = t0)
        val sessions = ArrayList<ReadingSession>()
        for (m in 1..minutes) {
            tracker.interaction(t0 + m * minute)
            sessions += tracker.takeCompleted()
        }
        val last = tracker.end(t0 + minutes * minute)
        return sessions + tracker.takeCompleted() + listOfNotNull(last)
    }

    @Test
    fun `sunday 23-50 to monday 00-20 gives ten minutes in one week and twenty in the next`() {
        // 2026-10-04 är en söndag (vecka 40) och 2026-10-05 en måndag (vecka 41).
        val sessions = read("2026-10-04T23:50:00", 30)
        assertEquals(2, sessions.size)
        val (sunday, monday) = sessions
        assertEquals(at("2026-10-04T23:50:00"), sunday.startedAt)
        assertEquals(at("2026-10-05T00:00:00") - 1, sunday.endedAt)
        assertEquals(600, sunday.durationSeconds)
        assertEquals(at("2026-10-05T00:00:00"), monday.startedAt)
        assertEquals(at("2026-10-05T00:20:00"), monday.endedAt)
        assertEquals(1200, monday.durationSeconds)

        val activity = weeklyActivity(sessions, at("2026-10-09T12:00:00"), zone)
        assertEquals(600L, activity[activity.size - 2].seconds)
        assertEquals(1200L, activity.last().seconds)
    }

    @Test
    fun `the new session continues on the same issue and page, with its own pages read`() {
        val tracker = SessionTracker("datormagazin-1986-01", zone = zone)
        tracker.start(page = 3, wallClock = at("2026-10-06T23:59:00"), elapsed = t0)
        tracker.pagesRead(listOf(3, 4))
        tracker.moveTo(5)
        tracker.interaction(t0 + 30 * second)
        assertTrue(tracker.takeCompleted().isEmpty())
        tracker.interaction(t0 + 70 * second)
        tracker.pagesRead(listOf(5))
        val current = tracker.snapshot(t0 + 90 * second)!!
        val before = tracker.takeCompleted().single()
        assertTrue(tracker.takeCompleted().isEmpty())
        assertEquals("datormagazin-1986-01", before.issueId)
        assertEquals(3, before.startPage)
        assertEquals(5, before.endPage)
        assertEquals(2, before.pagesViewed)
        assertEquals(60, before.durationSeconds)
        assertEquals("datormagazin-1986-01", current.issueId)
        assertEquals(5, current.startPage)
        assertEquals(5, current.endPage)
        assertEquals(1, current.pagesViewed)
        assertEquals(30, current.durationSeconds)
        assertTrue(tracker.active)
    }

    @Test
    fun `the idle limit carries over midnight`() {
        // Sista interaktionen 23:57. Gränsen på 5 minuter når till 00:02.
        val tracker = SessionTracker("x", zone = zone)
        tracker.start(1, at("2026-10-06T23:50:00"), t0)
        tracker.interaction(t0 + 4 * minute)
        tracker.interaction(t0 + 7 * minute)
        val last = tracker.end(t0 + 40 * minute)!!
        val before = tracker.takeCompleted().single()
        assertEquals(600, before.durationSeconds)
        assertEquals(120, last.durationSeconds)
        assertEquals(at("2026-10-07T00:00:00"), last.startedAt)
        assertEquals(at("2026-10-07T00:02:00"), last.endedAt)
    }

    @Test
    fun `an idle reader over midnight gives no time and no negative length after midnight`() {
        val tracker = SessionTracker("x", zone = zone)
        tracker.start(1, at("2026-10-06T23:00:00"), t0)
        val last = tracker.end(t0 + 90 * minute)!!
        val before = tracker.takeCompleted().single()
        assertEquals(300, before.durationSeconds)
        assertEquals(at("2026-10-06T23:05:00"), before.endedAt)
        assertEquals(0, last.durationSeconds)
        assertEquals(last.startedAt, last.endedAt)
        assertFalse(last.isWorthKeeping())
    }

    @Test
    fun `a short pause in the background over midnight also splits the session`() {
        val tracker = SessionTracker("x", zone = zone)
        tracker.start(1, at("2026-10-06T23:58:00"), t0)
        tracker.background(t0 + 1 * minute)
        assertTrue(tracker.foreground(t0 + 3 * minute))
        val last = tracker.end(t0 + 4 * minute)!!
        val before = tracker.takeCompleted().single()
        assertEquals(60, before.durationSeconds)
        assertEquals(at("2026-10-06T23:59:00"), before.endedAt)
        assertEquals(at("2026-10-07T00:00:00"), last.startedAt)
        assertEquals(60, last.durationSeconds)
    }

    @Test
    fun `several midnights give one session per day`() {
        val tracker = SessionTracker("x", idleLimitMillis = 100 * 60 * minute, zone = zone)
        tracker.start(1, at("2026-10-06T23:00:00"), t0)
        val last = tracker.end(t0 + 50 * 60 * minute)!!
        val done = tracker.takeCompleted()
        assertEquals(listOf(3600, 86_400, 86_400), done.map { it.durationSeconds })
        assertEquals(listOf("2026-10-06T23:00:00", "2026-10-07T00:00:00", "2026-10-08T00:00:00").map(::at), done.map { it.startedAt })
        assertEquals(3600, last.durationSeconds)
        assertEquals(at("2026-10-09T00:00:00"), last.startedAt)
    }

    @Test
    fun `a day with a clock change is split at the local midnight`() {
        // Natten till 2026-10-25 går klockan från sommartid till vintertid: dygnet är 25 timmar.
        val tracker = SessionTracker("x", idleLimitMillis = 100 * 60 * minute, zone = zone)
        tracker.start(1, at("2026-10-24T23:00:00"), t0)
        val last = tracker.end(t0 + 27 * 60 * minute)!!
        assertEquals(listOf(3600, 25 * 3600), tracker.takeCompleted().map { it.durationSeconds })
        assertEquals(at("2026-10-26T00:00:00"), last.startedAt)
        assertEquals(3600, last.durationSeconds)
    }

    @Test
    fun `a session within one day is not split`() {
        val sessions = read("2026-10-06T10:00:00", 30)
        assertEquals(1800, sessions.single().durationSeconds)
    }
}
