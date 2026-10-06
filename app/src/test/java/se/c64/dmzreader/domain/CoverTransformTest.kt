package se.c64.dmzreader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.c64.dmzreader.domain.CoverTransform.Crop

class CoverTransformTest {

    @Test
    fun `no flags means no transform`() {
        val transform = CoverTransform.fromFlags(emptyList())
        assertEquals(CoverTransform.IDENTITY, transform)
        assertTrue(transform.isIdentity)
        assertEquals(440 to 611, transform.sourceSizeFor(440, 611))
    }

    @Test
    fun `flags map to rotation and crop`() {
        assertEquals(CoverTransform(90, Crop.NONE), CoverTransform.fromFlags(listOf("rotate_cw")))
        assertEquals(CoverTransform(270, Crop.NONE), CoverTransform.fromFlags(listOf("rotate_ccw")))
        assertEquals(CoverTransform(180, Crop.NONE), CoverTransform.fromFlags(listOf("rotate_180")))
        assertEquals(CoverTransform(0, Crop.LEFT_HALF), CoverTransform.fromFlags(listOf("crop_left_half")))
        assertEquals(
            CoverTransform(90, Crop.RIGHT_HALF),
            CoverTransform.fromFlags(listOf("crop_right_half", "rotate_cw")),
        )
    }

    @Test
    fun `order of flags does not matter`() {
        assertEquals(
            CoverTransform.fromFlags(listOf("rotate_cw", "crop_right_half")),
            CoverTransform.fromFlags(listOf("crop_right_half", "rotate_cw")),
        )
    }

    @Test
    fun `unknown flags are ignored`() {
        assertTrue(CoverTransform.fromFlags(listOf("rotated", "sepia")).isIdentity)
        assertEquals(CoverTransform(90, Crop.NONE), CoverTransform.fromFlags(listOf("framtida", "rotate_cw")))
    }

    @Test
    fun `the 1986 nr 1 cover ends up as an upright page`() {
        // Sidbild 1 är 950 × 1339: ett liggande uppslag som står på högkant.
        val transform = CoverTransform.fromFlags(listOf("crop_right_half", "rotate_cw"))
        assertFalse(transform.isIdentity)
        val (width, height) = transform.resultSizeFor(950, 1339)
        assertEquals(669 to 950, width to height)
        assertEquals(0.70, width.toDouble() / height, 0.01)
    }

    @Test
    fun `source size compensates for rotation and crop`() {
        val transform = CoverTransform(90, Crop.RIGHT_HALF)
        // För ett kort på 440 × 611 behövs en källa på 611 × 880 ...
        assertEquals(611 to 880, transform.sourceSizeFor(440, 611))
        // ... som efter vridning och beskärning blir just 440 × 611.
        assertEquals(440 to 611, transform.resultSizeFor(611, 880))
    }

    @Test
    fun `source and result size are inverses for every transform`() {
        for (rotation in listOf(0, 90, 180, 270)) {
            for (crop in Crop.entries) {
                val transform = CoverTransform(rotation, crop)
                val (sourceWidth, sourceHeight) = transform.sourceSizeFor(300, 400)
                assertEquals("$transform", 300 to 400, transform.resultSizeFor(sourceWidth, sourceHeight))
            }
        }
    }

    @Test
    fun `cache key separates transforms`() {
        val keys = listOf(0, 90, 180, 270).flatMap { rotation ->
            Crop.entries.map { CoverTransform(rotation, it).cacheKey }
        }
        assertEquals(keys.size, keys.toSet().size)
    }
}
