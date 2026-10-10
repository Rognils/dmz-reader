package se.c64.dmzreader

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Debug
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
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
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.c64.dmzreader.data.pdf.PdfDocument
import java.io.File

/**
 * Läsaren på en riktig enhet, mot arkivets riktiga PDF:er.
 *
 * [readDownloadTurnPageAndClose] ingår i den vanliga testsviten och hämtar
 * 1986 nr 1 (15 MB). De övriga är verifieringskörningar som bara körs när de
 * begärs med argumentet dmzVerify, eftersom de hämtar stora filer:
 *
 *   adb shell am instrument -w -e class se.c64.dmzreader.ReaderDeviceTest \
 *     -e dmzVerify true se.c64.dmzreader.debug.test/se.c64.dmzreader.IsolatedTestRunner
 */
@RunWith(AndroidJUnit4::class)
class ReaderDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val pdfDirectory = File(storage(context).cacheDir, "pdf")
    private val screenshotDirectory = File(storage(context).cacheDir, "screens")

    private fun verifying() = arguments.getString("dmzVerify") == "true"

    /** Testerna börjar från sida 1 med "Läs": numrens läsdata på telefonen nollställs. */
    @Before
    fun resetReadingState() {
        resetReadingState(context, "datormagazin-1986-01", "datormagazin-1995-14", "datormagazin-1995-15")
        // Testerna gäller läs-cachen. En permanent nedladdning av samma nummer hade
        // använts i stället, så den tas bort.
        removeDownloads(context, "datormagazin-1986-01", "datormagazin-1995-14", "datormagazin-1995-15")
    }

    private fun waitFor(timeoutMillis: Long = 15_000, condition: () -> Boolean) =
        rule.waitUntil(timeoutMillis, condition)

    private fun exists(text: String) = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    private fun described(text: String) =
        rule.onAllNodes(hasContentDescription(text)).fetchSemanticsNodes().isNotEmpty()

    private fun readerShown() = rule.onAllNodes(hasTestTag("reader")).fetchSemanticsNodes().isNotEmpty()

    private fun indicator(): String? = rule.onAllNodes(hasTestTag("page-indicator")).fetchSemanticsNodes()
        .firstOrNull()?.config?.firstOrNull { it.key.name == "Text" }?.value?.toString()?.trim('[', ']')

    private fun openIssue(publication: String, year: Int, issueCount: Int, label: String) {
        waitFor { exists(publication) }
        rule.onNodeWithText(publication).performClick()
        // Beskrivningen slutar med antalet lästa nummer när något är läst.
        val yearCard = "$publication $year, $issueCount nummer"
        waitFor { rule.onAllNodes(hasContentDescription(yearCard, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription(yearCard, substring = true).performClick()
        val cover = "$publication $year $label"
        waitFor { rule.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() }
        // Numret kan ligga längre ned i rutnätet än det som ryms på skärmen.
        rule.scrollToNodeWhenLoaded(hasContentDescription(cover))
        rule.onNodeWithContentDescription(cover).performClick()
        // Knappen heter "Fortsätt på sida N" när numret har läsdata.
        waitFor { exists("Läs") || rule.onAllNodes(hasText("Fortsätt på sida", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun tapZone(fractionX: Float) {
        rule.onNodeWithTag("reader").performTouchInput { click(Offset(width * fractionX, height * 0.45f)) }
        // Ett enkeltryck avgörs först när tiden för ett dubbeltryck har gått ut.
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
    }

    private fun showControls() {
        if (indicator() == null) tapZone(0.5f)
        waitFor { indicator() != null }
    }

    private fun waitForPage(prefix: String) = waitFor(20_000) { indicator()?.startsWith(prefix) == true }

    /**
     * Låter läsaren rendera klart efter en gest. Testklockan är virtuell, så
     * väntan före högupplöst rendering måste stegas fram uttryckligen, och
     * själva renderingen sker på en egen tråd i verklig tid.
     */
    private fun settle(expectHighRes: Boolean = false) {
        repeat(2) {
            rule.mainClock.advanceTimeBy(400)
            rule.waitForIdle()
            Thread.sleep(700)
            rule.waitForIdle()
        }
        if (expectHighRes) {
            // Sidor ur den äldre PDF-generationen tar en dryg sekund att rendera.
            waitFor(30_000) {
                rule.mainClock.advanceTimeBy(200)
                described("Högupplöst utsnitt")
            }
        }
    }

    /** Median, 90:e percentil och max för renderingarna sedan loggen senast tömdes. */
    private fun logRenderStats(label: String) {
        val records = PdfDocument.recentRenders()
        fun summary(name: String, selected: List<Long>) {
            if (selected.isEmpty()) return
            val sorted = selected.sorted()
            Log.i(
                TAG,
                "RENDER $label $name antal=${sorted.size} medianMs=${sorted[sorted.size / 2]} " +
                    "p90Ms=${sorted[(sorted.size * 9 / 10).coerceAtMost(sorted.lastIndex)]} maxMs=${sorted.last()}",
            )
        }
        summary("helsida", records.filter { it.kind == "page" && it.width >= 1000 }.map { it.millis })
        summary("forhandsbild", records.filter { it.kind == "page" && it.width in 300..999 }.map { it.millis })
        summary("miniatyr", records.filter { it.kind == "page" && it.width < 300 }.map { it.millis })
        summary("utsnitt", records.filter { it.kind == "region" }.map { it.millis })
        val pages = records.filter { it.kind == "page" && it.width >= 1000 }.map { it.pageNumber }.toSet()
        Log.i(TAG, "RENDER $label sidorIFullUpplosning=${pages.size}")
    }

    private fun screenshot(name: String) {
        settle()
        screenshotDirectory.mkdirs()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(screenshotDirectory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Läs → nedladdning → läsare → byte av sida → stäng. */
    @Test
    fun readDownloadTurnPageAndClose() {
        val issueId = "datormagazin-1986-01"
        File(pdfDirectory, "$issueId.pdf").delete()
        File(pdfDirectory, "$issueId.pdf.part").delete()

        openIssue("Datormagazin", 1986, 4, "Nr 1")
        rule.onNodeWithText("Läs").performClick()

        // Nedladdningsvyn visas tills filen är hämtad, och sedan öppnas läsaren.
        waitFor(120_000) { exists("Avbryt") || readerShown() }
        waitFor(120_000) { readerShown() }
        val pdf = File(pdfDirectory, "$issueId.pdf")
        assertTrue(pdf.isFile)
        assertEquals(15_105_472L, pdf.length())
        assertFalse(File(pdfDirectory, "$issueId.pdf.part").exists())

        showControls()
        assertEquals("Sida 1 av 28 · 0 % läst", indicator())
        rule.onNodeWithText("Datormagazin 1986 Nr 1").assertIsDisplayed()
        // Bottenfältet syns inte i läsaren.
        assertFalse(exists("Bibliotek"))

        // Tryckzonen till höger byter sida, och ett svep gör det också.
        tapZone(0.85f)
        waitForPage("Sida 2 av 28")
        rule.onNodeWithTag("reader").performTouchInput { swipeLeft() }
        waitForPage("Sida 3 av 28")
        tapZone(0.15f)
        waitForPage("Sida 2 av 28")

        rule.onNodeWithContentDescription("Stäng läsaren").performClick()
        // Läspositionen är sparad: knappen leder tillbaka till samma sida.
        waitFor { exists("Fortsätt på sida 2") && exists("Bibliotek") }

        // Nu finns filen i cachen: läsaren öppnas direkt, utan nedladdningsvy.
        rule.onNodeWithText("Fortsätt på sida 2").performClick()
        waitFor(20_000) { readerShown() }
        assertFalse(exists("Avbryt"))
    }

    private fun openReader(publication: String, year: Int, issueCount: Int, label: String) {
        PdfDocument.clearRenderLog()
        openIssue(publication, year, issueCount, label)
        rule.onNodeWithText("Läs").performClick()
        waitFor(900_000) { readerShown() }
    }

    /** Bläddrar igenom hela numret, sida för sida. Renderingstiderna hamnar i logcat. */
    private fun readThrough(pageCount: Int) {
        showControls()
        logMemory("readthrough-start")
        for (page in 2..pageCount) {
            if (page % 20 == 0) logMemory("readthrough-page$page")
            tapZone(0.85f)
            waitForPage("Sida $page av $pageCount")
            // Vänta tills sidan faktiskt visas i full skärmupplösning, som en läsare skulle göra.
            waitFor(30_000) { rule.onAllNodes(hasTestTag("page-full-$page")).fetchSemanticsNodes().isNotEmpty() }
        }
        assertTrue(indicator()!!.startsWith("Sida $pageCount av $pageCount"))
        logMemory("readthrough-end")
    }

    @Test
    fun verifyReadThrough198601() {
        assumeTrue(verifying())
        openReader("Datormagazin", 1986, 4, "Nr 1")
        readThrough(28)
        logRenderStats("198601")
    }

    @Test
    fun verifyReadThrough199514() {
        assumeTrue(verifying())
        openReader("Datormagazin", 1995, 14, "Nr 14")
        readThrough(84)
        logRenderStats("199514")
    }

    /** Väntar tills sidan som visas är renderad i full skärmupplösning, som en läsare skulle göra. */
    private fun waitForCurrentPageInFullResolution() {
        showControls()
        val page = Regex("""Sida (\d+)""").find(indicator()!!)!!.groupValues[1]
        waitFor(30_000) { rule.onAllNodes(hasTestTag("page-full-$page")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun zoomToMaximum() {
        rule.onNodeWithTag("reader").performTouchInput {
            // Dra isär två fingrar tills zoomen slår i taket.
            repeat(3) {
                pinch(
                    start0 = Offset(centerX - 60f, centerY), end0 = Offset(centerX - 600f, centerY),
                    start1 = Offset(centerX + 60f, centerY), end1 = Offset(centerX + 600f, centerY),
                    durationMillis = 300,
                )
            }
        }
        settle(expectHighRes = true)
    }

    private fun logMemory(label: String) {
        val info = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val runtime = Runtime.getRuntime()
        Log.i(
            TAG,
            "MEMORY $label pssKb=${info.totalPss} javaHeapKb=${(runtime.totalMemory() - runtime.freeMemory()) / 1024} " +
                "nativeHeapKb=${Debug.getNativeHeapAllocatedSize() / 1024} maxJavaKb=${runtime.maxMemory() / 1024}",
        )
    }

    private fun zoomAndMeasure(tag: String, screenshotName: String?) {
        showControls()
        tapZone(0.85f)
        tapZone(0.85f)
        waitForCurrentPageInFullResolution()
        tapZone(0.5f) // dölj kontrollerna
        logMemory("$tag-before")
        zoomToMaximum()
        logMemory("$tag-maxzoom")
        if (screenshotName != null) screenshot(screenshotName)
        // Håll kvar läget en stund, så att minnet kan läsas av utifrån med dumpsys meminfo.
        Log.i(TAG, "ZOOM_HOLD_START $tag")
        // Panorera runt i maximal zoom: varje stopp renderar ett nytt utsnitt.
        repeat(4) { step ->
            rule.onNodeWithTag("reader").performTouchInput {
                if (step % 2 == 0) swipeUp(startY = height * 0.7f, endY = height * 0.3f) else swipeLeft(startX = width * 0.7f, endX = width * 0.3f)
            }
            settle(expectHighRes = true)
            logMemory("$tag-pan$step")
        }
        Thread.sleep(6_000)
        logMemory("$tag-held")
        Runtime.getRuntime().gc()
        Thread.sleep(1_000)
        logMemory("$tag-after-gc")
        logRenderStats("$tag-zoom")
        Log.i(TAG, "ZOOM_HOLD_END $tag")
        rule.onNodeWithTag("reader").performTouchInput { doubleClick() }
        rule.waitForIdle()
    }

    @Test
    fun verifyZoom198601() {
        assumeTrue(verifying())
        openReader("Datormagazin", 1986, 4, "Nr 1")
        showControls()
        repeat(2) { tapZone(0.85f) }
        tapZone(0.5f)
        screenshot("02-staende-sida")
        tapZone(0.5f)
        screenshot("04-kontroller-med-miniatyrremsa")
        tapZone(0.5f)
        // Dubbeltryck zoomar in, och det synliga området renderas om i hög upplösning.
        rule.onNodeWithTag("reader").performTouchInput { doubleClick(Offset(width * 0.5f, height * 0.42f)) }
        rule.waitForIdle()
        settle(expectHighRes = true)
        screenshot("03-zoomad-sida-dubbeltryck")
        rule.onNodeWithTag("reader").performTouchInput { doubleClick() }
        rule.waitForIdle()
        zoomAndMeasure("198601", "03-zoomad-sida-maximal")
    }

    @Test
    fun verifyZoom199514() {
        assumeTrue(verifying())
        openReader("Datormagazin", 1995, 14, "Nr 14")
        zoomAndMeasure("199514", "03-zoomad-sida-199514")
    }

    /** Belastningstestet: arkivets största PDF, 563 MB. Filen ska redan vara hämtad. */
    @Test
    fun verifyLoad199515() {
        assumeTrue(verifying())
        assumeTrue(File(pdfDirectory, "datormagazin-1995-15.pdf").isFile)
        openReader("Datormagazin", 1995, 14, "Nr 15")
        showControls()
        assertTrue(indicator()!!.startsWith("Sida 1 av "))
        repeat(8) { tapZone(0.85f); waitForCurrentPageInFullResolution() }
        zoomAndMeasure("199515", "07-belastning-199515-maximal-zoom")
        showControls()
        repeat(6) { tapZone(0.85f); Thread.sleep(400) }
        logMemory("199515-after")
    }

    @Test
    fun verifyLandscapeSpreadAndRotation() {
        assumeTrue(verifying())
        openReader("Datormagazin", 1986, 4, "Nr 1")
        showControls()
        repeat(6) { tapZone(0.85f) }
        waitForPage("Sida 7 av 28")

        // Stående sida 7 → liggande uppslag 6–7.
        rule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        waitFor(20_000) { readerShown() }
        Thread.sleep(1_500)
        showControls()
        waitForPage("Sida 6–7 av 28")
        screenshot("05-liggande-uppslag-med-kontroller")
        tapZone(0.5f)
        screenshot("05-liggande-uppslag")

        // Tillbaka till stående: samma sida som innan.
        rule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        waitFor(20_000) { readerShown() }
        Thread.sleep(1_500)
        showControls()
        waitForPage("Sida 7 av 28")

        // Förskjut parningen i liggande läge: 6–7 blir 7–8, och valet sparas.
        rule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        waitFor(20_000) { readerShown() }
        Thread.sleep(1_500)
        showControls()
        rule.onNodeWithText("Flytta uppslag").performClick()
        waitForPage("Sida 7–8 av 28")
        screenshot("05-liggande-uppslag-forskjutet")
        rule.onNodeWithText("Flytta uppslag").performClick()
        waitForPage("Sida 6–7 av 28")
        rule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    /**
     * Minnet på en enskild tung sida: 1995 nr 15, sida 15, vars skanning är arkivets största.
     * Bläddrar dit sida för sida och mäter före zoom, vid högsta zoom och under panorering.
     */
    @Test
    fun verifyMemoryOnPage15() {
        assumeTrue(verifying())
        assumeTrue(File(pdfDirectory, "datormagazin-1995-15.pdf").isFile)
        openReader("Datormagazin", 1995, 14, "Nr 15")
        showControls()
        repeat(14) { tapZone(0.85f); waitForCurrentPageInFullResolution() }
        waitForPage("Sida 15 av ")
        tapZone(0.5f) // dölj kontrollerna
        Thread.sleep(3_000)
        logMemory("p15-before")
        Thread.sleep(4_000)
        zoomToMaximum()
        logMemory("p15-maxzoom")
        repeat(4) { step ->
            rule.onNodeWithTag("reader").performTouchInput {
                if (step % 2 == 0) swipeUp(startY = height * 0.7f, endY = height * 0.3f) else swipeLeft(startX = width * 0.7f, endX = width * 0.3f)
            }
            settle(expectHighRes = true)
            logMemory("p15-pan$step")
        }
        Thread.sleep(6_000)
        logMemory("p15-held")
    }

    /**
     * Minnet när ett nummer öppnas direkt på en tung sida, som med "Fortsätt på sida 15":
     * 1995 nr 15, sida 15. Mäter direkt efter öppningen och en stund senare.
     */
    @Test
    fun verifyMemoryOpenedOnPage15() {
        assumeTrue(verifying())
        assumeTrue(File(pdfDirectory, "datormagazin-1995-15.pdf").isFile)
        kotlinx.coroutines.runBlocking {
            appContainer(context).database.issueUserStateDao().upsert(
                se.c64.dmzreader.data.db.IssueUserState(issueId = "datormagazin-1995-15", lastPage = 15, maxPageReached = 15, updatedAt = System.currentTimeMillis()),
            )
        }
        PdfDocument.clearRenderLog()
        openIssue("Datormagazin", 1995, 14, "Nr 15")
        rule.onNodeWithText("Fortsätt på sida 15").performClick()
        waitFor(900_000) { readerShown() }
        if (arguments.getString("dmzControls") != "false") {
            showControls()
            waitForPage("Sida 15 av ")
            tapZone(0.5f) // dölj kontrollerna
        }
        Thread.sleep(1_000)
        logMemory("open15-before")
        Thread.sleep(8_000)
        logMemory("open15-maxzoom")
        Thread.sleep(8_000)
        logMemory("open15-pan3")
        Thread.sleep(6_000)
        logMemory("open15-held")
        // PDF-motorns sparade bilder ska vara släppta när läsaren har stått still. Före rättningen
        // i 1.0-rc1 låg native heap kvar på cirka 640 MB här.
        val nativeMb = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        assertTrue("native heap är $nativeMb MB", nativeMb < 300)
    }

    /** Minnet sida för sida genom de första 22 sidorna i 1995 nr 15, där sidorna 12–20 är skannade i hög upplösning. */
    @Test
    fun verifyMemoryPageByPage() {
        assumeTrue(verifying())
        assumeTrue(File(pdfDirectory, "datormagazin-1995-15.pdf").isFile)
        openReader("Datormagazin", 1995, 14, "Nr 15")
        showControls()
        tapZone(0.5f)
        for (page in 2..22) {
            tapZone(0.85f)
            Thread.sleep(3_500)
            logMemory("walk-p$page")
        }
    }

    private companion object {
        const val TAG = "DmzVerify"
    }
}
