package se.c64.dmzreader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
