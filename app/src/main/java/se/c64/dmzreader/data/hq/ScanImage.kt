package se.c64.dmzreader.data.hq

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import se.c64.dmzreader.data.pdf.PagePlacement
import se.c64.dmzreader.data.pdf.PageSize
import java.io.Closeable
import java.io.File
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.floor

/** En rektangel i skanningens egna pixlar, före vridning. */
data class SourceRegion(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Största nedskalning (1, 2, 4, 8 …) som fortfarande ger minst [targetWidth] × [targetHeight]
 * pixlar ur en källa på [sourceWidth] × [sourceHeight]. Den avkodade bilden blir då
 * mindre än dubbla målet på varje ledd.
 */
fun sampleSizeFor(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Int {
    var sample = 1
    while (sourceWidth / (sample * 2) >= targetWidth && sourceHeight / (sample * 2) >= targetHeight) sample *= 2
    return sample
}

/**
 * Den del av skanningen som motsvarar rektangeln [left]–[right], [top]–[bottom] på den
 * vridna sidan. [rotation] är hur många grader medurs skanningen vrids för att visas.
 */
fun sourceRegionFor(
    left: Float, top: Float, right: Float, bottom: Float,
    sourceWidth: Int, sourceHeight: Int, rotation: Int,
): SourceRegion? {
    val (l, t, r, b) = when (rotation) {
        90 -> listOf(top, sourceHeight - right, bottom, sourceHeight - left)
        180 -> listOf(sourceWidth - right, sourceHeight - bottom, sourceWidth - left, sourceHeight - top)
        270 -> listOf(sourceWidth - bottom, left, sourceWidth - top, right)
        else -> listOf(left, top, right, bottom)
    }
    val region = SourceRegion(
        floor(l).toInt().coerceIn(0, sourceWidth),
        floor(t).toInt().coerceIn(0, sourceHeight),
        ceil(r).toInt().coerceIn(0, sourceWidth),
        ceil(b).toInt().coerceIn(0, sourceHeight),
    )
    return region.takeIf { it.width > 0 && it.height > 0 }
}

/**
 * En hämtad originalskanning (JPEG eller PNG), öppnad för visning.
 *
 * Skanningarna är upp till cirka 4 500 × 6 400 pixlar och skulle ta över 100 MB i
 * full upplösning. Hela bilden avkodas därför bara nedskalad ([overview]), och i full
 * upplösning avkodas bara det utsnitt som syns på skärmen ([renderRegion]), med
 * BitmapRegionDecoder.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScanImage private constructor(
    val file: File,
    private val decoder: BitmapRegionDecoder,
    /** Grader medurs som skanningen vrids för att ligga som PDF-sidan. */
    val rotation: Int,
    dispatcher: CoroutineDispatcher,
) : Closeable {
    // En avkodning åt gången per bild: två samtidiga utsnitt skulle dubbla minnet.
    private val decoding = dispatcher.limitedParallelism(1)
    private val sourceWidth = decoder.width
    private val sourceHeight = decoder.height
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    @Volatile
    private var closed = false

    /** Sidans mått i skanningens pixlar, efter vridning. */
    val size: PageSize =
        if (rotation == 90 || rotation == 270) PageSize(sourceHeight, sourceWidth) else PageSize(sourceWidth, sourceHeight)

    /** Hela sidan nedskalad till [width] × [height] pixlar. */
    suspend fun overview(width: Int, height: Int): Bitmap = withContext(decoding) {
        val started = SystemClock.elapsedRealtime()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        draw(Canvas(bitmap), SourceRegion(0, 0, sourceWidth, sourceHeight), RectF(0f, 0f, width.toFloat(), height.toFloat()))
        Log.d(LOG_TAG, "hq overview ${file.name} ${width}x$height ms=${SystemClock.elapsedRealtime() - started}")
        bitmap
    }

    /**
     * Det som syns på skärmen, i en bild av skärmens storlek. Samma kontrakt som
     * PdfDocument.renderRegion: [placements] säger var sidan ligger och i vilken skala,
     * räknat i sidans egna mått ([size]).
     */
    suspend fun renderRegion(width: Int, height: Int, placements: List<PagePlacement>): Bitmap = withContext(decoding) {
        val started = SystemClock.elapsedRealtime()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bitmap)
        for (placement in placements) {
            val scale = placement.scale
            // Den synliga delen av sidan, i sidans mått.
            val left = (-placement.left / scale).coerceIn(0f, size.width.toFloat())
            val top = (-placement.top / scale).coerceIn(0f, size.height.toFloat())
            val right = ((width - placement.left) / scale).coerceIn(0f, size.width.toFloat())
            val bottom = ((height - placement.top) / scale).coerceIn(0f, size.height.toFloat())
            val region = sourceRegionFor(left, top, right, bottom, sourceWidth, sourceHeight, rotation) ?: continue
            // Utsnittet är avrundat utåt till hela pixlar. Målet räknas från det avrundade utsnittet.
            val shown = shownRect(region)
            val target = RectF(
                placement.left + shown.left * scale,
                placement.top + shown.top * scale,
                placement.left + shown.right * scale,
                placement.top + shown.bottom * scale,
            )
            draw(canvas, region, target)
        }
        Log.d(LOG_TAG, "hq region ${file.name} ${width}x$height ms=${SystemClock.elapsedRealtime() - started}")
        bitmap
    }

    /** Var [region] hamnar på den vridna sidan. Omvändningen av [sourceRegionFor]. */
    private fun shownRect(region: SourceRegion): RectF = when (rotation) {
        90 -> RectF(
            (sourceHeight - region.bottom).toFloat(), region.left.toFloat(),
            (sourceHeight - region.top).toFloat(), region.right.toFloat(),
        )
        180 -> RectF(
            (sourceWidth - region.right).toFloat(), (sourceHeight - region.bottom).toFloat(),
            (sourceWidth - region.left).toFloat(), (sourceHeight - region.top).toFloat(),
        )
        270 -> RectF(
            region.top.toFloat(), (sourceWidth - region.right).toFloat(),
            region.bottom.toFloat(), (sourceWidth - region.left).toFloat(),
        )
        else -> RectF(region.left.toFloat(), region.top.toFloat(), region.right.toFloat(), region.bottom.toFloat())
    }

    /** Avkodar [region] så nedskalad som [target] tillåter och ritar den vriden i [target]. */
    private fun draw(canvas: Canvas, region: SourceRegion, target: RectF) {
        val turned = rotation == 90 || rotation == 270
        val wantedWidth = (if (turned) target.height() else target.width()).toInt().coerceAtLeast(1)
        val wantedHeight = (if (turned) target.width() else target.height()).toInt().coerceAtLeast(1)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(region.width, region.height, wantedWidth, wantedHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = synchronized(decoder) {
            if (closed) throw CancellationException("Skanningen är stängd")
            decoder.decodeRegion(Rect(region.left, region.top, region.right, region.bottom), options)
        } ?: throw IOException("${file.name}: utsnittet gick inte att avkoda")
        try {
            val matrix = Matrix()
            matrix.postRotate(rotation.toFloat())
            val bounds = RectF(0f, 0f, decoded.width.toFloat(), decoded.height.toFloat())
            matrix.mapRect(bounds)
            matrix.postTranslate(-bounds.left, -bounds.top)
            matrix.postScale(target.width() / bounds.width(), target.height() / bounds.height())
            matrix.postTranslate(target.left, target.top)
            canvas.drawBitmap(decoded, matrix, paint)
        } finally {
            decoded.recycle()
        }
    }

    override fun close() {
        synchronized(decoder) {
            if (closed) return
            closed = true
            decoder.recycle()
        }
    }

    companion object {
        private const val LOG_TAG = "DmzReader"

        /** Öppnar en hämtad skanning. Kastar IOException om filen inte är en bild som går att läsa. */
        suspend fun open(file: File, rotation: Int = 0, dispatcher: CoroutineDispatcher = Dispatchers.Default): ScanImage =
            withContext(Dispatchers.IO) {
                val decoder = try {
                    if (Build.VERSION.SDK_INT >= 31) {
                        BitmapRegionDecoder.newInstance(file.path)
                    } else {
                        @Suppress("DEPRECATION")
                        BitmapRegionDecoder.newInstance(file.path, false)
                    }
                } catch (e: IOException) {
                    throw IOException("${file.name} gick inte att öppna som bild", e)
                } ?: throw IOException("${file.name} gick inte att öppna som bild")
                ScanImage(file, decoder, rotation, dispatcher)
            }
    }
}
