package se.c64.dmzreader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingTrackingTest {
    // --- Tidtagning för läsning ---

    @Test
    fun `a page is read after one and a half seconds on screen`() {
        val timer = PageReadTimer()
        timer.setScreen(listOf(7))
        timer.resume(now = 1_000)
        assertEquals(1_500L, timer.remaining(1_000))
        assertEquals(emptyList<Int>(), timer.collect(2_499))
        assertEquals(listOf(7), timer.collect(2_500))
        // Samma skärm räknas bara en gång.
        assertEquals(emptyList<Int>(), timer.collect(9_000))
        assertNull(timer.remaining(9_000))
    }

    @Test
    fun `both pages of a spread are read together`() {
        val timer = PageReadTimer()
        timer.setScreen(listOf(6, 7))
        timer.resume(0)
        assertEquals(listOf(6, 7), timer.collect(1_500))
    }

    @Test
    fun `fast page turning counts nothing`() {
        val timer = PageReadTimer()
        var now = 0L
        val read = ArrayList<Int>()
        // Tjugo sidor med en halv sekund på varje, som via reglaget eller snabba svep.
        for (page in 1..20) {
            timer.setScreen(listOf(page))
            timer.resume(now)
            now += 500
            read += timer.collect(now)
        }
        assertEquals(emptyList<Int>(), read)
        // Stannar läsaren på sista sidan räknas den, och bara den.
        assertEquals(listOf(20), timer.collect(now + 1_000))
    }

    @Test
    fun `time on a page starts over when the page changes and comes back`() {
        val timer = PageReadTimer()
        timer.setScreen(listOf(3))
        timer.resume(0)
        timer.setScreen(listOf(4))
        timer.resume(1_000)
        timer.setScreen(listOf(3))
        timer.resume(1_200)
        // 1,0 s förra gången räknas inte: tiden ska vara i följd.
        assertEquals(emptyList<Int>(), timer.collect(2_000))
        assertEquals(listOf(3), timer.collect(2_700))
    }

    @Test
    fun `the clock does not run before the page is rendered`() {
        val timer = PageReadTimer()
        timer.setScreen(listOf(9))
        // Sidan visas men är inte färdigrenderad: resume har inte anropats.
        assertNull(timer.remaining(5_000))
        assertEquals(emptyList<Int>(), timer.collect(5_000))
        timer.resume(5_000)
        assertEquals(listOf(9), timer.collect(6_500))
    }

    @Test
    fun `the clock pauses in the background and continues afterwards`() {
        val timer = PageReadTimer()
        timer.setScreen(listOf(5))
        timer.resume(0)
        timer.pause(1_000)
        // En timme i bakgrunden räknas inte.
        assertEquals(emptyList<Int>(), timer.collect(3_600_000))
        assertNull(timer.remaining(3_600_000))
        timer.resume(3_600_000)
        assertEquals(500L, timer.remaining(3_600_000))
        assertEquals(emptyList<Int>(), timer.collect(3_600_499))
        assertEquals(listOf(5), timer.collect(3_600_500))
    }

    @Test
    fun `setting the same screen again does not restart the clock`() {
        val timer = PageReadTimer()
        timer.setScreen(listOf(5))
        timer.resume(0)
        timer.setScreen(listOf(5))
        timer.resume(900)
        assertEquals(listOf(5), timer.collect(1_500))
    }

    @Test
    fun `an empty screen is never read`() {
        val timer = PageReadTimer()
        timer.setScreen(emptyList())
        timer.resume(0)
        assertNull(timer.remaining(10_000))
        assertEquals(emptyList<Int>(), timer.collect(10_000))
    }

    // --- Sessioner ---

    @Test
    fun `a session records pages, time and the pages read`() {
        val tracker = SessionTracker("datormagazin-1986-01")
        assertFalse(tracker.active)
        tracker.start(page = 3, now = 10_000)
        assertTrue(tracker.active)
        tracker.pagesRead(listOf(3))
        tracker.moveTo(4)
        tracker.pagesRead(listOf(4, 5))
        tracker.pagesRead(listOf(4))
        tracker.moveTo(25)
        val session = tracker.end(now = 75_900)!!
        assertEquals("datormagazin-1986-01", session.issueId)
        assertEquals(10_000L, session.startedAt)
        assertEquals(75_900L, session.endedAt)
        assertEquals(3, session.startPage)
        assertEquals(25, session.endPage)
        // Unika sidor som räknats som lästa, inte sidor som bara bläddrats förbi.
        assertEquals(3, session.pagesViewed)
        assertEquals(65, session.durationSeconds)
        assertFalse(tracker.active)
        assertNull(tracker.end(80_000))
    }

    @Test
    fun `sessions shorter than five seconds are not saved`() {
        val tracker = SessionTracker("x")
        tracker.start(1, 0)
        assertNull(tracker.end(4_999))
        tracker.start(1, 10_000)
        assertEquals(5, tracker.end(15_000)!!.durationSeconds)
    }

    @Test
    fun `a new session starts clean and a running one is not restarted`() {
        val tracker = SessionTracker("x")
        tracker.start(1, 0)
        tracker.pagesRead(listOf(1, 2))
        // Rotation: start anropas igen, men sessionen fortsätter.
        tracker.start(9, 3_000)
        assertEquals(1, tracker.end(10_000)!!.startPage)
        tracker.start(2, 20_000)
        assertEquals(0, tracker.end(30_000)!!.pagesViewed)
    }

    @Test
    fun `nothing is recorded outside a session`() {
        val tracker = SessionTracker("x")
        tracker.pagesRead(listOf(1))
        tracker.moveTo(5)
        tracker.start(2, 0)
        val session = tracker.end(6_000)!!
        assertEquals(0, session.pagesViewed)
        assertEquals(2, session.endPage)
    }

    // --- Renderingsordning ---

    @Test
    fun `current page first, then next, then previous`() {
        val focus = RenderFocus(current = listOf(7), next = listOf(8), previous = listOf(6))
        val order = listOf(
            "aktuell förhandsbild" to renderUrgency(7, true, focus),
            "aktuell" to renderUrgency(7, false, focus),
            "nästa förhandsbild" to renderUrgency(8, true, focus),
            "föregående förhandsbild" to renderUrgency(6, true, focus),
            "nästa" to renderUrgency(8, false, focus),
            "föregående" to renderUrgency(6, false, focus),
            "annan" to renderUrgency(20, false, focus),
        )
        assertEquals(order.map { it.first }, order.shuffled(java.util.Random(1)).sortedBy { it.second }.map { it.first })
        assertEquals(order.size, order.map { it.second }.toSet().size)
        assertTrue(order.all { it.second < RenderUrgency.THUMBNAIL })
    }

    @Test
    fun `urgency follows the focus in spreads and when the reader moves`() {
        val spread = RenderFocus(current = listOf(6, 7), next = listOf(8, 9), previous = listOf(4, 5))
        assertEquals(RenderUrgency.CURRENT, renderUrgency(6, false, spread))
        assertEquals(RenderUrgency.CURRENT, renderUrgency(7, false, spread))
        assertEquals(RenderUrgency.NEXT, renderUrgency(9, false, spread))
        assertEquals(RenderUrgency.PREVIOUS_PREVIEW, renderUrgency(4, true, spread))
        // Samma sida blir mer brådskande när läsaren har bläddrat fram till den.
        val moved = RenderFocus(current = listOf(8, 9), next = listOf(10, 11), previous = listOf(6, 7))
        assertTrue(renderUrgency(9, false, moved) < renderUrgency(9, false, spread))
        assertEquals(RenderUrgency.OTHER, renderUrgency(9, false, RenderFocus()))
    }
}
