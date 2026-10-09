package se.c64.dmzreader.data.hq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Räkningen bakom utsnitten ur en skanning: nedskalning och vridning. */
class ScanRegionTest {
    @Test
    fun `the sample size never gives fewer pixels than asked for`() {
        // Den största skanningen i arkivet, 4890 × 7000, på en skärm som är 1440 bred.
        assertEquals(2, sampleSizeFor(4890, 7000, 1440, 2061))
        assertEquals(1, sampleSizeFor(4890, 7000, 2500, 3600))
        assertEquals(8, sampleSizeFor(4890, 7000, 360, 515))
        assertEquals(1, sampleSizeFor(1000, 1000, 1000, 1000))
        assertEquals(1, sampleSizeFor(500, 500, 1000, 1000))
        for (target in listOf(1, 7, 100, 333, 1440, 3000)) {
            val sample = sampleSizeFor(4890, 7000, target, target)
            assertTrue(4890 / sample >= target || sample == 1)
            // Och inte mer än nödvändigt: nästa steg hade blivit för litet.
            assertTrue(4890 / (sample * 2) < target || 7000 / (sample * 2) < target)
        }
    }

    @Test
    fun `the decoded region is less than twice the target in each direction`() {
        // Ett utsnitt i full skärmstorlek tar cirka 18 MB. Det avkodade får aldrig bli mer än fyra gånger det.
        for (width in listOf(1441, 2000, 2879, 2880, 4890)) {
            val sample = sampleSizeFor(width, width * 2, 1440, 2880)
            assertTrue("$width / $sample", width / sample < 2 * 1440)
        }
    }

    @Test
    fun `without rotation the region is the same rectangle`() {
        assertEquals(SourceRegion(10, 20, 110, 220), sourceRegionFor(10f, 20f, 110f, 220f, 1000, 2000, 0))
        // Avrundas utåt och hålls inom bilden.
        assertEquals(SourceRegion(10, 20, 111, 221), sourceRegionFor(10.4f, 20.6f, 110.2f, 220.1f, 1000, 2000, 0))
        assertEquals(SourceRegion(0, 0, 1000, 2000), sourceRegionFor(-50f, -50f, 5000f, 5000f, 1000, 2000, 0))
    }

    @Test
    fun `an empty region is no region`() {
        assertNull(sourceRegionFor(100f, 100f, 100f, 300f, 1000, 2000, 0))
        assertNull(sourceRegionFor(2000f, 0f, 3000f, 100f, 1000, 2000, 0))
    }

    @Test
    fun `a scan turned 90 degrees clockwise is read from the turned rectangle`() {
        // Källan är 1000 × 2000 (stående). Vriden 90 grader medurs visas den 2000 × 1000.
        // Det visade övre vänstra hörnet är källans nedre vänstra.
        assertEquals(SourceRegion(0, 1900, 50, 2000), sourceRegionFor(0f, 0f, 100f, 50f, 1000, 2000, 90))
        // Det visade nedre högra hörnet är källans övre högra.
        assertEquals(SourceRegion(950, 0, 1000, 100), sourceRegionFor(1900f, 950f, 2000f, 1000f, 1000, 2000, 90))
        // Hela den visade sidan är hela källan.
        assertEquals(SourceRegion(0, 0, 1000, 2000), sourceRegionFor(0f, 0f, 2000f, 1000f, 1000, 2000, 90))
    }

    @Test
    fun `a scan turned 270 degrees is read from the opposite corner`() {
        // Det visade övre vänstra hörnet är källans övre högra.
        assertEquals(SourceRegion(950, 0, 1000, 100), sourceRegionFor(0f, 0f, 100f, 50f, 1000, 2000, 270))
        assertEquals(SourceRegion(0, 0, 1000, 2000), sourceRegionFor(0f, 0f, 2000f, 1000f, 1000, 2000, 270))
    }

    @Test
    fun `a scan turned upside down is read from the opposite corner`() {
        assertEquals(SourceRegion(900, 1950, 1000, 2000), sourceRegionFor(0f, 0f, 100f, 50f, 1000, 2000, 180))
        assertEquals(SourceRegion(0, 0, 1000, 2000), sourceRegionFor(0f, 0f, 1000f, 2000f, 1000, 2000, 180))
    }
}
