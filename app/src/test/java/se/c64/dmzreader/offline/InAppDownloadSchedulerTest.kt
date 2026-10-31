package se.c64.dmzreader.offline

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InAppDownloadSchedulerTest {
    @Test
    fun `wifi rule gates both queues and resumes when the network becomes allowed`() = runTest {
        val network = MutableStateFlow(false)
        var pdfRuns = 0
        var indexRuns = 0
        val scheduler = InAppDownloadScheduler(backgroundScope, network, { pdfRuns++ }, { indexRuns++ })
        scheduler.setForeground(true)
        scheduler.start()
        runCurrent()
        assertEquals(0, pdfRuns)
        assertEquals(0, indexRuns)

        network.value = true
        runCurrent()
        assertTrue(pdfRuns > 0)
        assertEquals(pdfRuns, indexRuns)
    }

    @Test
    fun `leaving the foreground cancels and returning resumes without parallel workers`() = runTest {
        var active = 0
        var maximum = 0
        var starts = 0
        val scheduler = InAppDownloadScheduler(backgroundScope, MutableStateFlow(true), {
            starts++
            active++
            maximum = maxOf(maximum, active)
            try { awaitCancellation() } finally { active-- }
        })
        scheduler.start()
        runCurrent()
        assertEquals(0, starts)
        scheduler.setForeground(true)
        runCurrent()
        assertEquals(1, active)
        scheduler.start()
        runCurrent()
        assertEquals(1, starts)
        scheduler.setForeground(false)
        runCurrent()
        assertEquals(0, active)
        scheduler.setForeground(true)
        runCurrent()
        assertEquals(2, starts)
        assertEquals(1, maximum)
    }

    @Test
    fun `switching to mobile data pauses an ongoing transfer and wifi resumes it`() = runTest {
        val network = MutableStateFlow(true)
        var starts = 0
        var stops = 0
        val scheduler = InAppDownloadScheduler(backgroundScope, network, {
            starts++
            try { awaitCancellation() } finally { stops++ }
        })
        scheduler.setForeground(true)
        runCurrent()
        assertEquals(1, starts)
        network.value = false
        runCurrent()
        assertEquals(1, stops)
        network.value = true
        runCurrent()
        assertEquals(2, starts)
        scheduler.start(restart = true)
        runCurrent()
        assertEquals(2, stops)
        assertEquals(3, starts)
    }

    @Test
    fun `network policy never treats disconnection as an allowed network`() {
        assertFalse(DownloadNetwork(false, false).allows(false))
        assertFalse(DownloadNetwork(true, true).allows(true))
        assertTrue(DownloadNetwork(true, true).allows(false))
        assertTrue(DownloadNetwork(true, false).allows(true))
    }
}
