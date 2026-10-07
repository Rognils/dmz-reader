package se.c64.dmzreader.data.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import se.c64.dmzreader.domain.RenderUrgency
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** En genomförd rendering och hur lång tid den tog. */
data class RenderRecord(val kind: String, val pageNumber: Int, val width: Int, val height: Int, val millis: Long)

/** En sidas storlek i PDF-punkter. */
data class PageSize(val width: Int, val height: Int) {
    val aspectRatio: Float get() = width.toFloat() / height
}

/**
 * Var en sida ska ritas i en bild: [scale] är pixlar per PDF-punkt, och
 * ([left], [top]) är sidans övre vänstra hörn i bildens koordinater. Hörnet får
 * ligga utanför bilden; då ritas bara den del av sidan som ryms.
 */
data class PagePlacement(val pageNumber: Int, val scale: Float, val left: Float, val top: Float)

/**
 * En öppen PDF, renderad med Androids PdfRenderer (ADR 0011).
 *
 * PdfRenderer klarar bara en öppen sida åt gången och är inte trådsäker. All
 * åtkomst går därför genom en enda tråd. Sidnummer är PDF-sidindex med start på 1.
 */
class PdfDocument private constructor(
    private val file: File,
    private var descriptor: ParcelFileDescriptor,
    private var renderer: PdfRenderer,
    private val dispatcher: ExecutorCoroutineDispatcher,
) : Closeable {
    val pageCount: Int = renderer.pageCount

    // Sidor som har renderats sedan PDF:en senast öppnades. Bara renderartråden rör den.
    private val pagesRenderedSinceOpen = HashSet<Int>()
    private val pageSizes = arrayOfNulls<PageSize>(pageCount)
    private val whitePaint = Paint().apply { color = Color.WHITE }

    /** En rendering som väntar på sin tur. [urgency] läses om varje gång kön väljer. */
    private class Task(
        val urgency: () -> Int,
        val caller: Job,
        val sequence: Long,
        val run: () -> Unit,
    ) {
        val done = CompletableDeferred<Unit>()
    }

    // Väntande renderingar. Skyddas av sig själv som lås.
    private val waiting = ArrayList<Task>()
    private var nextSequence = 0L

    @Volatile
    private var closed = false

    suspend fun pageSize(pageNumber: Int): PageSize = withContext(dispatcher) {
        pageSizes[pageNumber - 1] ?: usePage(pageNumber) { page ->
            PageSize(page.width, page.height).also { pageSizes[pageNumber - 1] = it }
        }
    }

    /**
     * Läser in alla sidors storlek i förväg. Det går fort, och efteråt behöver
     * layouten aldrig vänta bakom en pågående rendering för att få veta hur
     * stor en sida är.
     */
    suspend fun preloadPageSizes() = withContext(dispatcher) {
        val started = SystemClock.elapsedRealtime()
        for (pageNumber in 1..pageCount) {
            if (pageSizes[pageNumber - 1] == null) {
                usePage(pageNumber) { page -> pageSizes[pageNumber - 1] = PageSize(page.width, page.height) }
            }
        }
        Log.d(LOG_TAG, "pageSizes pages=$pageCount ms=${SystemClock.elapsedRealtime() - started}")
    }

    /**
     * Hela sidan, skalad till [width] × [height] pixlar. Används för grundbilden
     * i skärmupplösning och för miniatyrer, aldrig för en sida i full upplösning.
     */
    suspend fun renderPage(
        pageNumber: Int,
        width: Int,
        height: Int,
        urgency: () -> Int = { RenderUrgency.CURRENT },
    ): Bitmap =
        renderOwned(width, height, urgency) { bitmap ->
            val started = SystemClock.elapsedRealtime()
            // PdfRenderer ritar på genomskinlig botten. Tidningssidor ska vara vita.
            bitmap.eraseColor(Color.WHITE)
            beforeRendering(pageNumber)
            usePage(pageNumber) { page ->
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
            logRender("page", pageNumber, width, height, started)
        }

    /**
     * Ett utsnitt i hög upplösning: bara det som syns på skärmen ritas, i en bild
     * av skärmens storlek. Så hålls minnet nere även för sidor skannade i 400 dpi,
     * som i full upplösning skulle bli över 100 MB.
     */
    suspend fun renderRegion(width: Int, height: Int, placements: List<PagePlacement>): Bitmap =
        renderOwned(width, height, { RenderUrgency.CURRENT }) { bitmap ->
            val started = SystemClock.elapsedRealtime()
            val canvas = Canvas(bitmap)
            for (placement in placements) {
                beforeRendering(placement.pageNumber)
                usePage(placement.pageNumber) { page ->
                    canvas.drawRect(
                        placement.left,
                        placement.top,
                        placement.left + page.width * placement.scale,
                        placement.top + page.height * placement.scale,
                        whitePaint,
                    )
                    val matrix = Matrix().apply {
                        setScale(placement.scale, placement.scale)
                        postTranslate(placement.left, placement.top)
                    }
                    page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
            // En Canvas håller kvar bildens pixlar så länge den lever. Släpp dem,
            // så att recycle() frigör minnet direkt.
            canvas.setBitmap(null)
            logRender("region", placements.firstOrNull()?.pageNumber ?: 0, width, height, started)
        }

    /**
     * Renderar till en bild som anroparen äger, och frigör den om anroparen
     * avbryts eller renderingen misslyckas.
     *
     * Renderingen körs alltid till slut (NonCancellable): PdfRenderer ska inte
     * rita i en bild som redan har frigjorts. Avbrottet märks först när
     * renderingen är klar, och då frigörs bilden här. Utan det hade en avbruten
     * rendering lämnat kvar en bild på upp till 18 MB tills skräpsamlaren kör.
     */
    private suspend fun renderOwned(
        width: Int,
        height: Int,
        urgency: () -> Int,
        draw: (Bitmap) -> Unit,
    ): Bitmap {
        val caller = currentCoroutineContext().job
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // En rendering som ingen längre väntar på hoppas över, så att kön inte
        // fylls av sidor som användaren redan har bläddrat förbi.
        val task = synchronized(waiting) {
            Task(urgency, caller, nextSequence++) { if (caller.isActive) draw(bitmap) }.also { waiting += it }
        }
        try {
            try {
                // Varje anrop här tar en uppgift ur kön, men inte nödvändigtvis den här.
                dispatcher.executor.execute(::runMostUrgent)
            } catch (e: RejectedExecutionException) {
                synchronized(waiting) { waiting -= task }
                throw CancellationException("PDF:en är stängd")
            }
            withContext(NonCancellable) { task.done.await() }
            currentCoroutineContext().ensureActive()
            return bitmap
        } catch (e: Throwable) {
            bitmap.recycle()
            throw e
        }
    }

    /**
     * Kör den mest brådskande väntande renderingen. Körs bara på renderartråden.
     * Hur bråttom en rendering har avgörs här och inte när den begärdes: en sida
     * som var granne när den köades kan ha blivit den aktuella sedan dess.
     * Avbrutna renderingar tas först, eftersom de bara ska frigöra sin bild.
     */
    private fun runMostUrgent() {
        val task = synchronized(waiting) {
            waiting.minWithOrNull(
                compareBy<Task>({ if (it.caller.isActive) it.urgency() else Int.MIN_VALUE }, { it.sequence }),
            )?.also { waiting -= it }
        } ?: return
        try {
            task.run()
            task.done.complete(Unit)
        } catch (e: Throwable) {
            task.done.completeExceptionally(e)
        }
    }

    private inline fun <T> usePage(pageNumber: Int, block: (PdfRenderer.Page) -> T): T {
        check(!closed) { "PDF:en är stängd" }
        require(pageNumber in 1..pageCount) { "Sida $pageNumber finns inte (1–$pageCount)" }
        return renderer.openPage(pageNumber - 1).use(block)
    }

    /**
     * PDF-motorn sparar avkodade bilder för varje sida den har ritat, så länge
     * dokumentet är öppet. För nummer skannade i hög upplösning växer det med
     * tiotals MB per sida. Efter [REOPEN_AFTER_PAGES] olika sidor öppnas därför
     * filen om, vilket släpper det sparade.
     */
    private fun beforeRendering(pageNumber: Int) {
        if (pageNumber in pagesRenderedSinceOpen) return
        if (pagesRenderedSinceOpen.size >= REOPEN_AFTER_PAGES) {
            val started = SystemClock.elapsedRealtime()
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(descriptor)
            pagesRenderedSinceOpen.clear()
            Log.d(LOG_TAG, "reopen ms=${SystemClock.elapsedRealtime() - started}")
        }
        pagesRenderedSinceOpen += pageNumber
    }

    private fun logRender(kind: String, pageNumber: Int, width: Int, height: Int, started: Long) {
        val millis = SystemClock.elapsedRealtime() - started
        Log.d(LOG_TAG, "render kind=$kind page=$pageNumber size=${width}x$height ms=$millis")
        synchronized(renderLog) {
            if (renderLog.size >= RENDER_LOG_LIMIT) renderLog.removeAt(0)
            renderLog += RenderRecord(kind, pageNumber, width, height, millis)
        }
    }

    /** Stänger PDF:en när pågående rendering är klar. */
    override fun close() {
        if (closed) return
        dispatcher.executor.execute {
            closed = true
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
        }
        (dispatcher.executor as java.util.concurrent.ExecutorService).shutdown()
    }

    companion object {
        const val LOG_TAG = "DmzReader"
        private const val RENDER_LOG_LIMIT = 2_000

        /** Antal olika sidor som renderas innan filen öppnas om. */
        const val REOPEN_AFTER_PAGES = 4
        private val renderLog = ArrayList<RenderRecord>()

        /** De senaste renderingarna, för mätning av renderingstider. */
        fun recentRenders(): List<RenderRecord> = synchronized(renderLog) { renderLog.toList() }

        fun clearRenderLog() = synchronized(renderLog) { renderLog.clear() }

        /** Öppnar en PDF. Kastar ett undantag om filen inte är en läsbar PDF. */
        suspend fun open(file: File): PdfDocument {
            val dispatcher = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "pdf-renderer")
            }.asCoroutineDispatcher()
            return try {
                withContext(dispatcher) {
                    val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    try {
                        PdfDocument(file, descriptor, PdfRenderer(descriptor), dispatcher)
                    } catch (e: Exception) {
                        descriptor.close()
                        throw e
                    }
                }
            } catch (e: Exception) {
                dispatcher.close()
                throw e
            }
        }
    }
}
