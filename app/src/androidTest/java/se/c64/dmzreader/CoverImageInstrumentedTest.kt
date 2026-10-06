package se.c64.dmzreader

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.size.Dimension
import coil3.size.Size
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import se.c64.dmzreader.data.inventory.InventoryParser
import se.c64.dmzreader.data.inventory.toEntity
import se.c64.dmzreader.domain.CoverTransform
import se.c64.dmzreader.ui.components.CoverFlagsTransformation
import se.c64.dmzreader.ui.components.coverImageRequest

@RunWith(AndroidJUnit4::class)
class CoverImageInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun bundledIssues() = InventoryParser.parse(
        context.assets.open("archive_inventory.json").bufferedReader().use { it.readText() },
    ).issues

    @Test
    fun requestUsesTheCoverUrlFromInventoryUnchanged() {
        val issues = bundledIssues()
        assertEquals(185, issues.size)
        for (dto in issues) {
            val request = coverImageRequest(context, dto.toEntity(), 440, 611)
            assertEquals(dto.issueId, dto.coverUrl, request.data)
        }
    }

    @Test
    fun requestIsScaledToTheCardAndNeverFullResolution() {
        val ordinary = bundledIssues().first { it.issueId == "datormagazin-1989-06" }.toEntity()
        val size = runBlocking { coverImageRequest(context, ordinary, 440, 611).sizeResolver.size() }
        assertEquals(Size(440, 611), size)

        // Det liggande omslaget behöver en större källa för att bli skarpt efter beskärningen.
        val rotated = bundledIssues().first { it.issueId == "datormagazin-1986-01" }.toEntity()
        assertEquals(listOf("crop_right_half", "rotate_cw"), rotated.coverFlags)
        val rotatedSize = runBlocking { coverImageRequest(context, rotated, 440, 611).sizeResolver.size() }
        assertEquals(Size(611, 880), rotatedSize)
        assertTrue((rotatedSize.width as Dimension.Pixels).px < 950)
    }

    /**
     * En testbild formad som sidbild 1 för 1986 nr 1: ett liggande uppslag
     * som står på högkant. Övre halvan är "framsidan" (röd), nedre "baksidan" (blå).
     */
    private fun uprightSpread(): Bitmap {
        val bitmap = Bitmap.createBitmap(200, 280, Bitmap.Config.ARGB_8888)
        for (y in 0 until 280) for (x in 0 until 200) {
            bitmap.setPixel(x, y, if (y < 140) Color.RED else Color.BLUE)
        }
        // Framsidans övre vänstra hörn (i läsriktningen) ligger uppe till höger i den vridna bilden.
        for (y in 0 until 20) for (x in 180 until 200) bitmap.setPixel(x, y, Color.GREEN)
        return bitmap
    }

    @Test
    fun rotateClockwiseAndCropRightHalfGivesTheFrontPage() = runBlocking {
        val transform = CoverTransform.fromFlags(listOf("rotate_cw", "crop_right_half"))
        val result = CoverFlagsTransformation(transform).transform(uprightSpread(), Size.ORIGINAL)

        // 200 × 280 vrids till 280 × 200, och högra halvan är 140 × 200.
        assertEquals(140, result.width)
        assertEquals(200, result.height)
        // Hela resultatet är framsidan (röd), inget av baksidan (blå) ...
        assertEquals(Color.RED, result.getPixel(70, 100))
        assertEquals(Color.RED, result.getPixel(5, 195))
        assertEquals(Color.RED, result.getPixel(135, 5))
        // ... och markeringen har hamnat nere till höger: bilden är vriden medurs.
        assertEquals(Color.GREEN, result.getPixel(135, 195))
    }

    @Test
    fun otherTransformsWorkGenerally() = runBlocking {
        val source = uprightSpread()
        suspend fun apply(vararg flags: String) =
            CoverFlagsTransformation(CoverTransform.fromFlags(flags.toList())).transform(source, Size.ORIGINAL)

        val ccw = apply("rotate_ccw")
        assertEquals(280 to 200, ccw.width to ccw.height)
        assertEquals(Color.GREEN, ccw.getPixel(5, 5))

        val upsideDown = apply("rotate_180")
        assertEquals(200 to 280, upsideDown.width to upsideDown.height)
        assertEquals(Color.BLUE, upsideDown.getPixel(100, 10))
        assertEquals(Color.GREEN, upsideDown.getPixel(5, 275))

        val left = apply("rotate_cw", "crop_left_half")
        assertEquals(140 to 200, left.width to left.height)
        assertEquals(Color.BLUE, left.getPixel(70, 100))

        val leftOnly = apply("crop_left_half")
        assertEquals(100 to 280, leftOnly.width to leftOnly.height)
    }

    @Test
    fun issueWithoutCoverUrlHasNoImageData() {
        val issue = bundledIssues().first().toEntity().copy(coverUrl = null)
        assertNull(coverImageRequest(context, issue, 440, 611).data.takeIf { it is String })
    }
}
