package se.c64.dmzreader.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import se.c64.dmzreader.data.pdf.PagePlacement
import se.c64.dmzreader.data.pdf.PageSize
import se.c64.dmzreader.data.pdf.PdfDocument
import kotlin.math.abs
import kotlin.math.roundToInt

/** Största zoom, och gränsen över vilken det synliga området renderas om i hög upplösning. */
const val MAX_ZOOM = 8f
const val HIGH_RES_ABOVE_ZOOM = 1.5f

/**
 * Högsta zoom som renderas i full upplösning: tre gånger skärmbredden, cirka
 * 4 300 pixlar på en sida. Skanningarna är som mest cirka 2 500 pixlar breda
 * i läs-PDF:erna, så ingenting går förlorat.
 */
const val MAX_RENDER_ZOOM = 3f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val HIGH_RES_DELAY_MILLIS = 160L
private const val PREVIEW_DIVISOR = 4

/**
 * Var sidorna på en skärm ligger när zoomen är 1: en sida inpassad i hela
 * ytan, eller två sidor som möts i mitten.
 */
fun layoutPages(sizes: List<PageSize>, viewportWidth: Float, viewportHeight: Float): List<Rect> {
    if (sizes.isEmpty()) return emptyList()
    if (sizes.size == 1) {
        return listOf(fit(sizes[0], Rect(0f, 0f, viewportWidth, viewportHeight), align = 0f))
    }
    val half = viewportWidth / 2
    return listOf(
        // Vänster sida ligger an mot ryggen till höger, höger sida till vänster.
        fit(sizes[0], Rect(0f, 0f, half, viewportHeight), align = 1f),
        fit(sizes[1], Rect(half, 0f, viewportWidth, viewportHeight), align = -1f),
    )
}

/** Passar in en sida i [slot]. [align] är -1 (vänster), 0 (mitten) eller 1 (höger). */
private fun fit(size: PageSize, slot: Rect, align: Float): Rect {
    val scale = minOf(slot.width / size.width, slot.height / size.height)
    val width = size.width * scale
    val height = size.height * scale
    val free = slot.width - width
    val left = slot.left + free * (align + 1) / 2
    val top = slot.top + (slot.height - height) / 2
    return Rect(left, top, left + width, top + height)
}

/** Begränsar panoreringen så att innehållet inte kan dras bort från skärmen. */
fun clampPan(offset: Offset, scale: Float, content: Rect, viewportWidth: Float, viewportHeight: Float): Offset {
    val maxX = ((content.width * scale - viewportWidth) / 2).coerceAtLeast(0f)
    val maxY = ((content.height * scale - viewportHeight) / 2).coerceAtLeast(0f)
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

private data class HighResTile(val bitmap: Bitmap, val scale: Float, val offset: Offset)

/**
 * En skärm i läsaren: en sida, eller ett uppslag med två sidor.
 *
 * Grundbilden renderas i skärmupplösning. Över [HIGH_RES_ABOVE_ZOOM] renderas det
 * synliga området om i hög upplösning och läggs ovanpå, när gesten har stannat.
 */
@Composable
fun ZoomablePages(
    pages: List<Int>,
    document: PdfDocument,
    isCurrent: Boolean,
    acquirePage: suspend (pageNumber: Int, width: Int, height: Int, preview: Boolean) -> Bitmap,
    releasePage: (pageNumber: Int, width: Int, height: Int, bitmap: Bitmap) -> Unit,
    recycleLater: (Bitmap) -> Unit,
    onPageShown: (pageNumber: Int, quality: PageQuality?) -> Unit,
    onTap: (fractionX: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val viewportWidth = constraints.maxWidth.toFloat()
        val viewportHeight = constraints.maxHeight.toFloat()
        val center = Offset(viewportWidth / 2, viewportHeight / 2)

        val sizes by produceState<List<PageSize>?>(null, pages) {
            value = pages.map { document.pageSize(it) }
        }
        val rects = remember(sizes, viewportWidth, viewportHeight) {
            sizes?.let { layoutPages(it, viewportWidth, viewportHeight) }
        }
        val content = remember(rects) {
            rects?.takeIf { it.isNotEmpty() }?.let { list ->
                Rect(list.minOf { it.left }, list.minOf { it.top }, list.maxOf { it.right }, list.maxOf { it.bottom })
            }
        }

        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var tile by remember { mutableStateOf<HighResTile?>(null) }

        // Ett utsnitt är lika stort som skärmen (cirka 18 MB) och frigörs så fort det byts ut.
        fun replaceTile(next: HighResTile?) {
            val previous = tile
            tile = next
            if (previous != null && previous.bitmap !== next?.bitmap) recycleLater(previous.bitmap)
        }
        DisposableEffect(Unit) {
            onDispose { tile?.let { recycleLater(it.bitmap) } }
        }

        // En sida som lämnas ska vara i grundläget när man kommer tillbaka.
        LaunchedEffect(isCurrent) {
            if (!isCurrent) {
                scale = 1f
                offset = Offset.Zero
                replaceTile(null)
            }
        }

        // Det synliga området i hög upplösning, när gesten har stannat en stund.
        LaunchedEffect(scale, offset, rects, sizes) {
            val currentRects = rects
            val currentSizes = sizes
            if (scale <= HIGH_RES_ABOVE_ZOOM || currentRects == null || currentSizes == null) {
                replaceTile(null)
                return@LaunchedEffect
            }
            delay(HIGH_RES_DELAY_MILLIS)
            // Över MAX_RENDER_ZOOM finns inga fler detaljer att hämta ur skanningarna.
            // Då renderas utsnittet i den upplösningen och förstoras på skärmen. Annars
            // skulle PDF-motorn arbeta med en sida på över tiotusen pixlars bredd.
            val shrink = scale / minOf(scale, MAX_RENDER_ZOOM)
            val placements = pages.indices.map { index ->
                val rect = currentRects[index]
                PagePlacement(
                    pageNumber = pages[index],
                    scale = rect.width * scale / currentSizes[index].width / shrink,
                    left = (center.x + (rect.left - center.x) * scale + offset.x) / shrink,
                    top = (center.y + (rect.top - center.y) * scale + offset.y) / shrink,
                )
            }
            val bitmap = document.renderRegion(
                (viewportWidth / shrink).roundToInt().coerceAtLeast(1),
                (viewportHeight / shrink).roundToInt().coerceAtLeast(1),
                placements,
            )
            replaceTile(HighResTile(bitmap, scale, offset))
        }

        // Gesterna läser alltid de senaste värdena. De får inte startas om när
        // sidstorleken blir känd: då hade ett tryck som just pågick gått förlorat.
        val latestContent by rememberUpdatedState(content)
        val latestOnTap by rememberUpdatedState(onTap)

        fun setTransform(newScale: Float, newOffset: Offset) {
            val bounds = latestContent ?: return
            scale = newScale
            offset = clampPan(newOffset, newScale, bounds, viewportWidth, viewportHeight)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(viewportWidth, viewportHeight) {
                    detectTapGestures(
                        onTap = { position -> latestOnTap(position.x / viewportWidth) },
                        onDoubleTap = { position ->
                            if (scale > 1.01f) {
                                setTransform(1f, Offset.Zero)
                            } else {
                                // Zooma in så att punkten under fingret ligger kvar.
                                val focus = position - center
                                setTransform(DOUBLE_TAP_ZOOM, focus * (1 - DOUBLE_TAP_ZOOM))
                            }
                        },
                    )
                }
                .pointerInput(viewportWidth, viewportHeight) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val bounds = latestContent
                            val pressed = event.changes.count { it.pressed }
                            if (bounds != null && (pressed >= 2 || scale > 1.01f)) {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = false)
                                val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                                // Zooma kring fingrarnas mittpunkt.
                                val focus = if (centroid == Offset.Unspecified) Offset.Zero else centroid - center
                                val proposed = offset + (focus - offset) * (1 - newScale / scale) + pan
                                val clamped = clampPan(proposed, newScale, bounds, viewportWidth, viewportHeight)
                                // Vid kanten släpps ett vågrätt drag med ett finger vidare, så att sidan byts.
                                val atEdge = pressed == 1 && zoom == 1f &&
                                    abs(pan.x) > abs(pan.y) && abs(clamped.x - proposed.x) > 0.5f &&
                                    abs(clamped.x - offset.x) < 0.5f
                                scale = newScale
                                offset = clamped
                                if (!atEdge) {
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        if (scale < 1.02f) setTransform(1f, Offset.Zero)
                    }
                },
        ) {
            val currentRects = rects
            if (currentRects != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                ) {
                    pages.forEachIndexed { index, pageNumber ->
                        PageImage(pageNumber, currentRects[index], acquirePage, releasePage, onPageShown)
                    }
                }
            }
            val currentTile = tile
            if (currentTile != null && !currentTile.bitmap.isRecycled &&
                currentTile.scale == scale && currentTile.offset == offset
            ) {
                Image(
                    bitmap = currentTile.bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    filterQuality = FilterQuality.Medium,
                    modifier = Modifier.fillMaxSize().semantics { contentDescription = "Högupplöst utsnitt" },
                )
            }
        }
    }
}

@Composable
private fun PageImage(
    pageNumber: Int,
    rect: Rect,
    acquirePage: suspend (pageNumber: Int, width: Int, height: Int, preview: Boolean) -> Bitmap,
    releasePage: (pageNumber: Int, width: Int, height: Int, bitmap: Bitmap) -> Unit,
    onPageShown: (pageNumber: Int, quality: PageQuality?) -> Unit,
) {
    val width = rect.width.roundToInt().coerceAtLeast(1)
    val height = rect.height.roundToInt().coerceAtLeast(1)
    // En snabb förhandsbild i en fjärdedels upplösning visas medan sidan renderas
    // i full skärmupplösning. Sidor ur den äldre PDF-generationen tar närmare en
    // sekund att rendera; förhandsbilden tar en tiondel av det.
    val previewWidth = (width / PREVIEW_DIVISOR).coerceAtLeast(1)
    val previewHeight = (height / PREVIEW_DIVISOR).coerceAtLeast(1)
    val preview by produceState<Bitmap?>(null, pageNumber, width, height) {
        val acquired = acquirePage(pageNumber, previewWidth, previewHeight, true)
        value = acquired
        awaitDispose {
            value = null
            releasePage(pageNumber, previewWidth, previewHeight, acquired)
        }
    }
    // Bilden lånas ur cachen så länge den visas och lämnas tillbaka efteråt.
    val bitmap by produceState<Bitmap?>(null, pageNumber, width, height) {
        val acquired = acquirePage(pageNumber, width, height, false)
        value = acquired
        awaitDispose {
            value = null
            releasePage(pageNumber, width, height, acquired)
        }
    }
    val density = LocalDensity.current
    val sizeModifier = with(density) {
        Modifier
            .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .size(width.toDp(), height.toDp())
    }
    val loaded = bitmap?.takeIf { !it.isRecycled }
    val loadedPreview = preview?.takeIf { !it.isRecycled }
    // Läsaren får veta vad som faktiskt visas: tidtagningen för läsning startar
    // först när sidan är färdigrenderad.
    val quality = when {
        loaded != null -> PageQuality.FULL
        loadedPreview != null -> PageQuality.PREVIEW
        else -> null
    }
    val latestOnPageShown by rememberUpdatedState(onPageShown)
    LaunchedEffect(pageNumber, quality) { latestOnPageShown(pageNumber, quality) }
    DisposableEffect(pageNumber) {
        onDispose { latestOnPageShown(pageNumber, null) }
    }
    when {
        loaded != null -> Image(
            bitmap = loaded.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = sizeModifier.testTag("page-full-$pageNumber").semantics { contentDescription = "Sida $pageNumber" },
        )

        loadedPreview != null -> Image(
            bitmap = loadedPreview.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            filterQuality = FilterQuality.Medium,
            modifier = sizeModifier.semantics { contentDescription = "Sida $pageNumber, förhandsbild" },
        )

        else -> Box(modifier = sizeModifier)
    }
}
