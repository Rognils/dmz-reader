package se.c64.dmzreader

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.room3.useWriterConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.c64.dmzreader.data.hq.ScanFailure
import se.c64.dmzreader.data.hq.ScanFetchException
import se.c64.dmzreader.data.hq.ScanSource
import se.c64.dmzreader.domain.SeenPages
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * En falsk källa för skanningar: bilder som ritas på telefonen, så att testet inte
 * belastar arkivet. Sidnumret står på bilden. Kan fås att vara utan nät.
 */
class FakeScanSource : ScanSource {
    val fetched = CopyOnWriteArrayList<String>()

    @Volatile
    var offline = false

    override suspend fun fetch(url: String, target: File, onProgress: (bytes: Long, total: Long?) -> Unit): Long {
        if (offline) throw ScanFetchException(ScanFailure.NETWORK, "inget nät (test)")
        fetched += url.substringAfterLast('/')
        val bitmap = Bitmap.createBitmap(1800, 2550, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(250, 246, 232))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 110f }
        canvas.drawText("HQ-skanning (test)", 120f, 300f, paint)
        canvas.drawText(url.substringAfterLast('/'), 120f, 480f, paint)
        paint.textSize = 28f
        for (row in 0 until 60) canvas.drawText("Liten text som bara går att läsa i full upplösning, rad ${row + 1}.", 120f, 700f + row * 30f, paint)
        val bytes = java.io.ByteArrayOutputStream().also { output ->
            val format = if (url.endsWith(".png")) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            bitmap.compress(format, 92, output)
        }.toByteArray()
        bitmap.recycle()
        onProgress(0, bytes.size.toLong())
        target.outputStream().use { output ->
            output.write(bytes, 0, bytes.size / 2)
            onProgress(bytes.size / 2L, bytes.size.toLong())
            delay(150)
            output.write(bytes, bytes.size / 2, bytes.size - bytes.size / 2)
        }
        onProgress(bytes.size.toLong(), bytes.size.toLong())
        return bytes.size.toLong()
    }
}

/**
 * HQ-läget i läsaren (planens FAS 11), mot isolerad lagring och en falsk skanningskälla:
 * öppna ett nummer, slå på HQ, se sidan, byt sida, och läs ur cachen utan nät.
 * Numrets PDF (1986 nr 1) läggs på telefonen av tools/device_test.sh, eller hämtas från arkivet.
 */
@RunWith(AndroidJUnit4::class)
class HqDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = appContainer(context)
    private val issueId = "datormagazin-1986-01"
    private val source = FakeScanSource()
    private val hqDirectory get() = File(storage(context).cacheDir, "hq/$issueId")
    private lateinit var realSource: ScanSource

    private fun waitFor(timeoutMillis: Long = 20_000, condition: () -> Boolean) = rule.waitUntil(timeoutMillis, condition)
    private fun exists(text: String) = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    private fun tagged(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun described(text: String) = rule.onAllNodes(hasContentDescription(text)).fetchSemanticsNodes().isNotEmpty()
    private fun textOf(tag: String): String? = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()
        .firstOrNull()?.config?.firstOrNull { it.key.name == "Text" }?.value?.toString()?.trim('[', ']')

    private fun indicator() = textOf("page-indicator")
    private fun status() = textOf("hq-status-text")

    private fun tapZone(fractionX: Float) {
        rule.onNodeWithTag("reader").performTouchInput { click(Offset(width * fractionX, height * 0.6f)) }
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
    }

    private fun showControls() {
        if (indicator() == null) tapZone(0.5f)
        waitFor { indicator() != null }
    }

    private fun waitForPage(prefix: String) = waitFor { indicator()?.startsWith(prefix) == true }

    private fun openReader() {
        waitFor { exists("Datormagazin") }
        rule.onNodeWithText("Datormagazin").performClick()
        val year = "Datormagazin 1986, 4 nummer"
        waitFor { rule.onAllNodes(hasContentDescription(year, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription(year, substring = true).performClick()
        waitFor { rule.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("Datormagazin 1986 Nr 1"))
        rule.onNodeWithContentDescription("Datormagazin 1986 Nr 1").performClick()
        waitFor { exists("Läs") }
        rule.onNodeWithText("Läs").performClick()
        waitFor(180_000) { tagged("reader") }
        showControls()
        waitForPage("Sida 1 av 28")
    }

    /** Sätter en HQ-tabell på numret i testkörningens katalog. null tar bort den. */
    private fun setPageMap(json: String?) = runBlocking {
        container.database.useWriterConnection { connection ->
            connection.usePrepared("UPDATE Issue SET hqPageMap = ? WHERE issueId = ?") { statement ->
                if (json == null) statement.bindNull(1) else statement.bindText(1, json)
                statement.bindText(2, issueId)
                statement.step()
            }
        }
    }

    @Before
    fun prepare() {
        realSource = container.scanSource
        container.scanSource = source
        container.hqCache.clear()
        resetReadingState(context, issueId)
        removeDownloads(context, issueId)
        setPageMap(null)
    }

    @After
    fun restore() {
        rule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        container.scanSource = realSource
        setPageMap(null)
        container.hqCache.clear()
    }

    @Test
    fun hqPageNextPageAndOfflineFromCache() {
        openReader()

        // HQ-knappen är aktiv och slår på HQ-läget. Bara sidan som visas hämtas.
        rule.onNodeWithTag("hq-toggle").assertExists()
        assertTrue(described("Slå på HQ-läget"))
        assertFalse(tagged("hq-status"))
        rule.onNodeWithTag("hq-toggle").performClick()
        waitFor { status() == "HQ" }
        assertTrue(described("Stäng av HQ-läget"))
        assertEquals(listOf("DMZ_1986-01-001.jpg"), source.fetched.toList())
        assertTrue(File(hqDirectory, "DMZ_1986-01-001.jpg").isFile)
        assertFalse(File(hqDirectory, "DMZ_1986-01-001.jpg.part").exists())
        waitFor { tagged("page-full-1") }

        // Nästa sida hämtas först när den visas. Sidan efter den hämtas inte i förväg.
        tapZone(0.85f)
        waitForPage("Sida 2 av 28")
        waitFor { status() == "HQ" }
        Thread.sleep(1_000)
        assertEquals(listOf("DMZ_1986-01-001.jpg", "DMZ_1986-01-002.jpg"), source.fetched.toList())
        waitFor { tagged("page-full-2") }

        // Zoom med dubbeltryck ger ett utsnitt i hög upplösning, som i PDF-läsaren.
        rule.onNodeWithTag("reader").performTouchInput { doubleClick(Offset(width * 0.5f, height * 0.5f)) }
        waitFor(30_000) {
            rule.mainClock.advanceTimeBy(200)
            described("Högupplöst utsnitt")
        }
        rule.onNodeWithTag("reader").performTouchInput { doubleClick(Offset(width * 0.5f, height * 0.5f)) }
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()

        // Utan nät: en sida som redan är hämtad visas ur cachen.
        source.offline = true
        tapZone(0.15f)
        waitForPage("Sida 1 av 28")
        waitFor { status() == "HQ" }
        assertEquals(2, source.fetched.size)

        // En sida som inte är hämtad ger ett tydligt meddelande, och PDF-sidan visas.
        tapZone(0.85f)
        waitForPage("Sida 2 av 28")
        tapZone(0.85f)
        waitForPage("Sida 3 av 28")
        waitFor { status()?.startsWith("HQ gick inte att hämta") == true }
        assertTrue(status(), status()!!.endsWith("PDF-sidan visas."))
        waitFor { tagged("page-full-3") }
        assertFalse(File(hqDirectory, "DMZ_1986-01-003.jpg").exists())

        // Med nät igen fungerar "Försök igen".
        source.offline = false
        rule.onNodeWithTag("hq-retry").performClick()
        waitFor { status() == "HQ" }
        assertEquals(listOf("DMZ_1986-01-001.jpg", "DMZ_1986-01-002.jpg", "DMZ_1986-01-003.jpg"), source.fetched.toList())

        // HQ av: samma sida, och HQ-raden försvinner. HQ på igen: sidan kommer ur cachen.
        showControls()
        rule.onNodeWithTag("hq-toggle").performClick()
        waitFor { !tagged("hq-status") }
        waitForPage("Sida 3 av 28")
        rule.onNodeWithTag("hq-toggle").performClick()
        waitFor { status() == "HQ" }
        assertEquals(3, source.fetched.size)

        // Läspositionen och de lästa sidorna förs som vanligt i HQ-läge.
        Thread.sleep(2_500)
        rule.onNodeWithContentDescription("Stäng läsaren").performClick()
        waitFor { exists("Fortsätt på sida 3") }
        val state = readingState(context, issueId)!!
        assertEquals(3, state.lastPage)
        assertTrue(3 in SeenPages.decode(state.seenPages))
        val sessions = runBlocking { container.database.readingSessionDao().getAll() }.filter { it.issueId == issueId }
        assertTrue(sessions.isNotEmpty())
        assertEquals(3, sessions.last().endPage)
    }

    @Test
    fun pageWithoutScanShowsMessageAndPdfPage() {
        // Sida 2 saknar skanning, och sida 3 har en fil med ett annat namn än mönstret ger.
        val map = (1..28).joinToString(",", "{", "}") { page ->
            when (page) {
                2 -> "\"2\":null"
                3 -> "\"3\":\"DMZ_1986-01-Bilaga-001.png\""
                else -> "\"$page\":\"DMZ_1986-01-%03d.jpg\"".format(page)
            }
        }
        setPageMap(map)
        openReader()
        rule.onNodeWithTag("hq-toggle").performClick()
        waitFor { status() == "HQ" }

        tapZone(0.85f)
        waitForPage("Sida 2 av 28")
        waitFor { status() == "HQ finns inte för den här sidan" }
        waitFor { tagged("page-full-2") }
        assertFalse(tagged("hq-retry"))

        tapZone(0.85f)
        waitForPage("Sida 3 av 28")
        waitFor { status() == "HQ" }
        // Ingen hämtning gjordes för sidan utan skanning, och tabellens fil användes för sida 3.
        assertEquals(listOf("DMZ_1986-01-001.jpg", "DMZ_1986-01-Bilaga-001.png"), source.fetched.toList())
        assertTrue(File(hqDirectory, "DMZ_1986-01-Bilaga-001.png").isFile)
    }

    @Test
    fun hqInLandscapeShowsSinglePage() {
        openReader()
        repeat(2) { tapZone(0.85f) }
        waitForPage("Sida 3 av 28")
        rule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        waitFor { tagged("reader") }
        Thread.sleep(1_500)
        showControls()
        waitForPage("Sida 2–3 av 28")

        // HQ i uppslagsläge byter till enkelsida, på samma sida.
        rule.onNodeWithTag("hq-toggle").performClick()
        waitFor { status() == "HQ" }
        showControls()
        waitForPage("Sida 3 av 28")
        assertEquals(listOf("DMZ_1986-01-003.jpg"), source.fetched.toList())

        // HQ av: uppslaget kommer tillbaka.
        rule.onNodeWithTag("hq-toggle").performClick()
        waitFor { !tagged("hq-status") }
        showControls()
        waitForPage("Sida 2–3 av 28")
    }

    @Test
    fun hqCacheIsShownAndClearedInSettings() {
        openReader()
        rule.onNodeWithTag("hq-toggle").performClick()
        waitFor { status() == "HQ" }
        assertTrue(container.hqCache.sizeBytes() > 0)
        rule.onNodeWithContentDescription("Stäng läsaren").performClick()
        waitFor { exists("Fortsätt på sida 1") || exists("Läs") }
        // Tillbaka till biblioteket, där inställningarna nås.
        repeat(3) {
            if (!described("Inställningar")) {
                rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                rule.waitForIdle()
            }
        }
        waitFor { described("Inställningar") }
        rule.onNodeWithContentDescription("Inställningar").performClick()
        waitFor { exists("HQ-cache") }
        waitFor { rule.onAllNodes(hasText("av högst 500,0 MB", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(exists("0 MB av högst 500,0 MB"))

        rule.onNodeWithTag("clear-hq-cache").performScrollTo().performClick()
        waitFor { exists("0 MB av högst 500,0 MB") }
        assertEquals(0L, container.hqCache.sizeBytes())
        assertFalse(hqDirectory.exists())
        // Läs-cachen rördes inte.
        assertTrue(hasLocalPdf(context, issueId))
    }
}
