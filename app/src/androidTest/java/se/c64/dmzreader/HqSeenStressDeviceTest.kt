package se.c64.dmzreader

import android.os.SystemClock
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
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.c64.dmzreader.data.hq.ScanSource
import se.c64.dmzreader.domain.SeenPages

/**
 * Stresstest för lästa sidor i HQ-läge: räknas en sida som läst när HQ slås på och av
 * medan den visas?
 *
 * Varje test går igenom sidorna 2–28 i 1986 nr 1, en sida per försök. För varje sida görs
 * variantens byten av HQ-läge, och sedan mäts hur lång tid det tar från det sista bytet tills
 * sidan står som läst i databasen. En sida som inte står som läst efter [GIVE_UP_MILLIS] räknas som tappad.
 *
 * Testerna tar ett par minuter var och körs bara på begäran:
 *
 *   tools/device_test.sh KATALOG --only -e dmzStress true -e class se.c64.dmzreader.HqSeenStressDeviceTest
 *
 * Resultatet skrivs i loggen med taggen DmzStress.
 *
 * Bakgrund: HqDeviceTest föll en gång på att en sida inte stod som läst. Orsaken var testmiljön.
 * Under en ComposeTestRule ritas skärmen bara om när testet gör något, och testet sov medan HQ-bilden
 * blev färdig. Med -e dmzPump false återskapas det: då tappas sidor i två av varianterna.
 */
@RunWith(AndroidJUnit4::class)
class HqSeenStressDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val container get() = appContainer(context)
    private val issueId = "datormagazin-1986-01"
    private val source = FakeScanSource()
    private lateinit var realSource: ScanSource
    // Med -e dmzPump false sover testtråden under väntan, och då står skärmen still i testmiljön.
    private val pump = arguments.getString("dmzPump") != "false"

    private fun waitFor(timeoutMillis: Long = 20_000, condition: () -> Boolean) = rule.waitUntil(timeoutMillis, condition)
    private fun exists(text: String) = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    private fun tagged(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun textOf(tag: String): String? = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()
        .firstOrNull()?.config?.firstOrNull { it.key.name == "Text" }?.value?.toString()?.trim('[', ']')

    private fun indicator() = textOf("page-indicator")
    private fun status() = textOf("hq-status-text")
    private fun hqOn() = rule.onAllNodes(hasContentDescription("Stäng av HQ-läget")).fetchSemanticsNodes().isNotEmpty()

    private fun tapZone(fractionX: Float) {
        rule.onNodeWithTag("reader").performTouchInput { click(Offset(width * fractionX, height * 0.6f)) }
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
    }

    private fun toggle() = rule.onNodeWithTag("hq-toggle").performClick()

    private fun setHq(on: Boolean) {
        if (hqOn() != on) toggle()
        waitFor { hqOn() == on }
    }

    private fun seen(): Set<Int> = SeenPages.decode(readingState(context, issueId)?.seenPages).let { pages ->
        (1..28).filter { it in pages }.toSet()
    }

    @Before
    fun prepare() {
        assumeTrue("körs bara med -e dmzStress true", arguments.getString("dmzStress") == "true")
        realSource = container.scanSource
        container.scanSource = source
        container.hqCache.clear()
        resetReadingState(context, issueId)
        removeDownloads(context, issueId)
    }

    @After
    fun restore() {
        if (::realSource.isInitialized) {
            container.scanSource = realSource
            container.hqCache.clear()
        }
    }

    private fun openReader() {
        waitFor { exists("Datormagazin") }
        rule.onNodeWithText("Datormagazin").performClick()
        val year = "Datormagazin 1986, 4 nummer"
        waitFor { rule.onAllNodes(hasContentDescription(year, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription(year, substring = true).performClick()
        waitFor { rule.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.scrollToNodeWhenLoaded(hasContentDescription("Datormagazin 1986 Nr 1"))
        rule.onNodeWithContentDescription("Datormagazin 1986 Nr 1").performClick()
        waitFor { exists("Läs") }
        rule.onNodeWithText("Läs").performClick()
        waitFor(180_000) { tagged("reader") }
        if (indicator() == null) tapZone(0.5f)
        waitFor { indicator()?.startsWith("Sida 1 av 28") == true }
    }

    /**
     * Kör [variant] på sidorna 2–28. [before] ställer in läget före sidbytet, och [afterTurn]
     * gör bytena av HQ-läge på den nya sidan. Tiden mäts från att [afterTurn] är klar.
     */
    private fun stress(variant: String, before: () -> Unit, afterTurn: (page: Int) -> Unit) {
        openReader()
        val times = ArrayList<Long>()
        val lost = ArrayList<Int>()
        for (page in 2..28) {
            before()
            assertTrue("sida $page är redan läst före försöket", page !in seen())
            tapZone(0.85f)
            waitFor { indicator()?.startsWith("Sida $page av 28") == true }
            afterTurn(page)
            val started = SystemClock.elapsedRealtime()
            var counted: Long? = null
            while (SystemClock.elapsedRealtime() - started < GIVE_UP_MILLIS) {
                if (page in seen()) {
                    counted = SystemClock.elapsedRealtime() - started
                    break
                }
                // Testet låter Compose rita under väntan, som en riktig skärm gör. Annars sover
                // testtråden, och då ritas ingenting om förrän testet gör något (se letAppRun).
                if (pump) rule.waitForIdle()
                Thread.sleep(50)
            }
            Log.i(TAG, "$variant pump=$pump page=$page hq=${hqOn()} status=${status()} countedAfterMs=${counted ?: "ALDRIG"}")
            if (counted == null) lost += page else times += counted
        }
        val late = times.count { it > WINDOW_MILLIS }
        Log.i(TAG, "$variant pump=$pump KLAR: ${times.size + lost.size} sidor, tappade=$lost, över $WINDOW_MILLIS ms=$late, " +
            "längsta=${times.maxOrNull()} ms, median=${times.sorted().getOrNull(times.size / 2)} ms")
        assertTrue("$variant: sidorna $lost räknades aldrig som lästa", lost.isEmpty())
        assertTrue("$variant: $late sidor räknades senare än $WINDOW_MILLIS ms efter bytet", late == 0)
    }

    /** HQ slås på direkt efter sidbytet. */
    @Test
    fun toggleRightAfterPageTurn() = stress("direkt-efter-sidbyte", before = { setHq(false) }) {
        toggle()
    }

    /** HQ slås på, av och på i snabb följd direkt efter sidbytet. */
    @Test
    fun rapidTogglesAfterPageTurn() = stress("snabba-byten", before = { setHq(false) }) {
        repeat(5) { toggle() }
    }

    /** HQ slås på mitt i de 2,5 sekunderna, när PDF-sidan redan har visats en stund. */
    @Test
    fun toggleInTheMiddleOfTheWindow() = stress("mitt-i-fonstret", before = { setHq(false) }) {
        Thread.sleep(1_200)
        toggle()
    }

    /** HQ slås av och på igen mitt i de 2,5 sekunderna, när HQ-sidan redan visas. */
    @Test
    fun offAndOnInTheMiddleOfTheWindow() = stress("av-och-pa-mitt-i", before = { setHq(true) }) {
        waitFor { status() == "HQ" }
        Thread.sleep(700)
        toggle()
        toggle()
    }

    /** HQ slås av och på medan hämtningen av sidan pågår. */
    @Test
    fun toggleWhileFetching() {
        source.slowMillis = 1_200
        stress("under-hamtning", before = { setHq(true) }) {
            Thread.sleep(300)
            toggle()
            Thread.sleep(200)
            toggle()
        }
    }

    /** Hämtningen misslyckas, och HQ slås av och på. PDF-sidan visas hela tiden. */
    @Test
    fun toggleWhenFetchFails() {
        source.offline = true
        stress("hamtning-misslyckas", before = { setHq(true) }) {
            waitFor { status()?.startsWith("HQ gick inte att hämta") == true }
            toggle()
            toggle()
        }
    }

    /** Förloppet i HqDeviceTest som föll: fel utan nät, nytt försök med nät, HQ av och på. */
    @Test
    fun failRetryThenOffAndOn() = stress("fel-nytt-forsok-av-pa", before = { setHq(true); source.offline = true }) {
        waitFor { status()?.startsWith("HQ gick inte att hämta") == true }
        source.offline = false
        rule.onNodeWithTag("hq-retry").performClick()
        waitFor { status() == "HQ" }
        toggle()
        waitFor { !tagged("hq-status") }
        toggle()
        waitFor { status() == "HQ" }
    }

    private companion object {
        const val TAG = "DmzStress"
        const val WINDOW_MILLIS = 2_500L
        const val GIVE_UP_MILLIS = 6_000L
    }
}
