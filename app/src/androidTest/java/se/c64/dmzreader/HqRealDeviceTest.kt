package se.c64.dmzreader

import android.os.Debug
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.c64.dmzreader.data.db.IssueUserState
import se.c64.dmzreader.data.hq.FtpScanSource
import java.io.File

/**
 * Verifiering av HQ mot det riktiga arkivet (planens FAS 11, del C). Körs bara med
 * -e dmzHqReal true, i testkörningens isolerade lagring. Skanningarna hämtas via FTP
 * från arkivet, en sida i taget, precis som när appen används.
 *
 * Numrens PDF:er läggs på telefonen av tools/device_test.sh (DMZ_SEED_ISSUES).
 * Vid varje "SKÄRMBILD" i loggen väntar testet (-e dmzPause millisekunder), så att en
 * skärmbild kan tas utifrån. Vid "MINNE" kan dumpsys meminfo köras utifrån.
 */
@RunWith(AndroidJUnit4::class)
class HqRealDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val container get() = appContainer(context)
    private val pause get() = arguments.getString("dmzPause")?.toLongOrNull() ?: 0L

    private fun waitFor(timeoutMillis: Long = 30_000, condition: () -> Boolean) = rule.waitUntil(timeoutMillis, condition)
    private fun exists(text: String) = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    private fun tagged(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun described(text: String) = rule.onAllNodes(hasContentDescription(text)).fetchSemanticsNodes().isNotEmpty()
    private fun textOf(tag: String): String? = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()
        .firstOrNull()?.config?.firstOrNull { it.key.name == "Text" }?.value?.toString()?.trim('[', ']')

    private fun indicator() = textOf("page-indicator")
    private fun status() = textOf("hq-status-text")

    private fun tapCenter() {
        rule.onNodeWithTag("reader").performTouchInput { click(Offset(width * 0.5f, height * 0.5f)) }
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
    }

    private fun showControls() {
        if (indicator() == null) tapCenter()
        waitFor { indicator() != null }
    }

    private fun hideControls() {
        if (indicator() != null) tapCenter()
        waitFor { indicator() == null }
    }

    /** Låter läsaren rendera klart, och väntar på utsnittet i hög upplösning när sidan är zoomad. */
    private fun settle(expectHighRes: Boolean) {
        repeat(2) {
            rule.mainClock.advanceTimeBy(400)
            rule.waitForIdle()
            Thread.sleep(700)
            rule.waitForIdle()
        }
        if (expectHighRes) {
            waitFor(60_000) {
                rule.mainClock.advanceTimeBy(200)
                described("Högupplöst utsnitt")
            }
            rule.waitForIdle()
        }
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        Log.i(TAG, "SKÄRMBILD $name")
        Thread.sleep(pause)
    }

    private fun logMemory(label: String) {
        val info = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val runtime = Runtime.getRuntime()
        Log.i(
            TAG,
            "MINNE $label pssKb=${info.totalPss} nativePssKb=${info.nativePss} graphicsKb=${info.getMemoryStat("summary.graphics")} " +
                "nativeHeapKb=${Debug.getNativeHeapAllocatedSize() / 1024} " +
                "javaHeapKb=${(runtime.totalMemory() - runtime.freeMemory()) / 1024} maxJavaKb=${runtime.maxMemory() / 1024}",
        )
        Thread.sleep(pause)
    }

    /** Öppnar numret på [page] genom "Fortsätt på sida N", som användaren hade gjort. */
    private fun openReader(issueId: String, year: Int, label: String, page: Int, pageCount: Int) {
        assumeTrue("PDF:en för $issueId saknas i testkörningens lagring", hasLocalPdf(context, issueId))
        runBlocking {
            container.database.issueUserStateDao().upsert(
                IssueUserState(issueId = issueId, lastPage = page, maxPageReached = page, updatedAt = System.currentTimeMillis()),
            )
        }
        waitFor { exists("Datormagazin") }
        rule.onNodeWithText("Datormagazin").performClick()
        val yearCard = "Datormagazin $year, "
        waitFor { rule.onAllNodes(hasContentDescription(yearCard, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription(yearCard, substring = true))
        rule.onNodeWithContentDescription(yearCard, substring = true).performClick()
        val cover = "Datormagazin $year $label"
        waitFor { rule.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() && !described("Inställningar") }
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription(cover))
        rule.onNodeWithContentDescription(cover).performClick()
        val button = if (page > 1) "Fortsätt på sida $page" else "Läs"
        waitFor { exists(button) }
        rule.onNodeWithText(button).performClick()
        waitFor(180_000) { tagged("reader") }
        showControls()
        waitFor { indicator()?.startsWith("Sida $page av $pageCount") == true }
    }

    /** Nyper isär kring en punkt på sidan. [factor] är förstoringen. */
    private fun zoom(fractionX: Float, fractionY: Float, factor: Float) {
        rule.onNodeWithTag("reader").performTouchInput {
            val focus = Offset(width * fractionX, height * fractionY)
            val start = 60f
            val end = start * factor
            pinch(
                start0 = focus - Offset(start, 0f), end0 = focus - Offset(end, 0f),
                start1 = focus + Offset(start, 0f), end1 = focus + Offset(end, 0f),
                durationMillis = 400,
            )
        }
    }

    private fun hqOn(expectedStatus: String = "HQ") {
        val started = System.currentTimeMillis()
        showControls()
        rule.onNodeWithTag("hq-toggle").performClick()
        waitFor(240_000) { status() == expectedStatus }
        Log.i(TAG, "HQ ${indicator()} status=\"$expectedStatus\" efter ${System.currentTimeMillis() - started} ms")
    }

    /** PDF mot HQ vid samma zoom: zooma, ta bild, slå på HQ, ta bild. */
    private fun compare(name: String, fractionX: Float, fractionY: Float, factor: Float) {
        hideControls()
        zoom(fractionX, fractionY, factor)
        settle(expectHighRes = true)
        screenshot("$name-pdf")
        hqOn()
        hideControls()
        settle(expectHighRes = true)
        screenshot("$name-hq")
    }

    @Before
    fun prepare() {
        assumeTrue(arguments.getString("dmzHqReal") == "true")
        assertTrue("Testet ska gå mot arkivets FTP-server", container.scanSource is FtpScanSource)
    }

    /** En sida ur en v2-PDF (150 dpi): 1986 nr 1, sida 9, småannonserna nedtill. */
    @Test
    fun v2PagePdfAgainstHq() {
        openReader("datormagazin-1986-01", 1986, "Nr 1", page = 9, pageCount = 28)
        hideControls()
        screenshot("v2-helsida-pdf")
        compare("v2-annons", fractionX = 0.3f, fractionY = 0.72f, factor = 4f)
        // Nästa sida hämtas först när den visas, över samma anslutning.
        rule.onNodeWithTag("reader").performTouchInput { pinch(Offset(100f, 800f), Offset(600f, 800f), Offset(1300f, 800f), Offset(800f, 800f), 300) }
        settle(expectHighRes = false)
        showControls()
        rule.onNodeWithTag("reader").performTouchInput { click(Offset(width * 0.9f, height * 0.6f)) }
        rule.mainClock.advanceTimeBy(400)
        waitFor(240_000) { indicator()?.startsWith("Sida 10 av 28") == true && status() == "HQ" }
        hideControls()
        settle(expectHighRes = false)
        screenshot("v2-nasta-sida-hq")
    }

    /** En sida ur den äldre PDF-generationen, efter avvikelsen i 1993 nr 3: sida 41 är skanning 43. */
    @Test
    fun olderPageAfterTheDeviationIn199303() {
        openReader("datormagazin-1993-03", 1993, "Nr 3", page = 41, pageCount = 68)
        hideControls()
        screenshot("199303-s41-helsida-pdf")
        compare("199303-s41-innehall", fractionX = 0.85f, fractionY = 0.78f, factor = 4f)
        assertTrue(File(storage(context).cacheDir, "hq/datormagazin-1993-03/DMZ_1993-03-43.jpg").isFile)
        // Hela sidan i HQ, för jämförelse med PDF-sidan.
        rule.onNodeWithTag("reader").performTouchInput { pinch(Offset(100f, 800f), Offset(650f, 800f), Offset(1300f, 800f), Offset(750f, 800f), 300) }
        settle(expectHighRes = false)
        screenshot("199303-s41-helsida-hq")
    }

    /** Sidorna 39 och 40 i 1993 nr 3 saknar skanning med säker motsvarighet. */
    @Test
    fun pageWithoutScanIn199303() {
        openReader("datormagazin-1993-03", 1993, "Nr 3", page = 39, pageCount = 68)
        hqOn(expectedStatus = "HQ finns inte för den här sidan")
        waitFor { tagged("page-full-39") }
        hideControls()
        screenshot("199303-s39-ingen-hq")
        assertEquals(false, File(storage(context).cacheDir, "hq/datormagazin-1993-03").listFiles().orEmpty().any { "41" in it.name })
    }

    /** Sida 27 i 1993 nr 3 ligger upp och ned i skanningen och vänds 180 grader. */
    @Test
    fun rotatedPageIn199303() {
        openReader("datormagazin-1993-03", 1993, "Nr 3", page = 27, pageCount = 68)
        hideControls()
        screenshot("199303-s27-pdf")
        hqOn()
        hideControls()
        settle(expectHighRes = false)
        screenshot("199303-s27-hq-vand")
    }

    /** Spelbilagan i 1986 nr 4: PDF-sida 36 är DMZ_1986-04-Spelbilaga-003.png (PNG, 30 MB). */
    @Test
    fun supplementPageIn198604() {
        openReader("datormagazin-1986-04", 1986, "Nr 4", page = 36, pageCount = 49)
        hideControls()
        screenshot("198604-s36-helsida-pdf")
        compare("198604-s36-prislista", fractionX = 0.4f, fractionY = 0.75f, factor = 4f)
        assertTrue(File(storage(context).cacheDir, "hq/datormagazin-1986-04/DMZ_1986-04-Spelbilaga-003.png").isFile)
        logMemory("198604-png-zoom4")
        rule.onNodeWithTag("reader").performTouchInput { pinch(Offset(100f, 800f), Offset(650f, 800f), Offset(1300f, 800f), Offset(750f, 800f), 300) }
        settle(expectHighRes = false)
        screenshot("198604-s36-helsida-hq")
    }

    /** Ett liggande omslag: 1987 nr 4–5, sida 1. Skanningen står och vrids 90 grader medurs. */
    @Test
    fun landscapeCoverIn19870405() {
        openReader("datormagazin-1987-04-05", 1987, "Nr 4–5", page = 1, pageCount = 48)
        hideControls()
        screenshot("19870405-s1-pdf")
        hqOn()
        hideControls()
        settle(expectHighRes = false)
        screenshot("19870405-s1-hq-vriden")
        zoom(0.75f, 0.5f, 5f)
        settle(expectHighRes = true)
        screenshot("19870405-s1-hq-vriden-zoom")
    }

    /** Den största skanningen i arkivet: 1995 nr 15, sida 15 (36,6 MB, 4890 × 7000). Maximal zoom och minne. */
    @Test
    fun largestScanAtMaximumZoom() {
        openReader("datormagazin-1995-15", 1995, "Nr 15", page = 15, pageCount = 92)
        hideControls()
        logMemory("199515-pdf")
        hqOn()
        hideControls()
        settle(expectHighRes = false)
        logMemory("199515-hq-oversikt")
        screenshot("199515-s15-helsida-hq")
        // Två nyp ger mer än högsta zoom (8 gånger), som läsaren begränsar till.
        zoom(0.4f, 0.85f, 5f)
        settle(expectHighRes = true)
        zoom(0.5f, 0.5f, 5f)
        settle(expectHighRes = true)
        logMemory("199515-hq-maxzoom")
        screenshot("199515-s15-maxzoom-hq")
        // Panorera runt i full upplösning: varje stopp avkodar ett nytt utsnitt.
        val moves = listOf(Offset(-500f, -700f), Offset(600f, 900f), Offset(-400f, 500f), Offset(700f, -600f), Offset(-600f, -800f), Offset(300f, 1000f))
        for ((index, move) in moves.withIndex()) {
            rule.onNodeWithTag("reader").performTouchInput {
                val start = Offset(width * 0.5f, height * 0.5f)
                swipe(start, start + move, 250)
            }
            settle(expectHighRes = true)
            if (index == 2) logMemory("199515-hq-maxzoom-panorering")
        }
        logMemory("199515-hq-maxzoom-efter-panorering")
        screenshot("199515-s15-maxzoom-hq-panorerad")
        // Tillbaka till PDF vid samma zoom, för jämförelse.
        showControls()
        rule.onNodeWithTag("hq-toggle").performClick()
        waitFor { !tagged("hq-status") }
        hideControls()
        settle(expectHighRes = true)
        logMemory("199515-pdf-maxzoom")
        screenshot("199515-s15-maxzoom-pdf")
    }

    private companion object {
        const val TAG = "DmzHqReal"
    }
}
