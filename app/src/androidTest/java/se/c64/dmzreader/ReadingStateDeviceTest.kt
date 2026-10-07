package se.c64.dmzreader

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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.c64.dmzreader.data.db.ReadStatusOverride
import se.c64.dmzreader.domain.SeenPages
import se.c64.dmzreader.ui.reader.PageQuality
import se.c64.dmzreader.ui.reader.ReaderViewModel
import java.io.File

/**
 * Lässtatus på en riktig enhet (FAS 4), mot 1986 nr 1 som ska finnas i PDF-cachen
 * (ReaderDeviceTest hämtar den).
 *
 * Omstartstestet har två delar med ett tvångsstopp av appen emellan, och körs
 * därför från datorn med tools/device_restart_test.sh. Mätningen av
 * förrenderingen körs med argumentet dmzVerify, som i ReaderDeviceTest.
 */
@RunWith(AndroidJUnit4::class)
class ReadingStateDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val issueId = "datormagazin-1986-01"

    private fun waitFor(timeoutMillis: Long = 20_000, condition: () -> Boolean) =
        rule.waitUntil(timeoutMillis, condition)

    private fun exists(text: String) = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    private fun readerShown() = rule.onAllNodes(hasTestTag("reader")).fetchSemanticsNodes().isNotEmpty()
    private fun fullPage(page: Int) = rule.onAllNodes(hasTestTag("page-full-$page")).fetchSemanticsNodes().isNotEmpty()

    private fun indicator(): String? = rule.onAllNodes(hasTestTag("page-indicator")).fetchSemanticsNodes()
        .firstOrNull()?.config?.firstOrNull { it.key.name == "Text" }?.value?.toString()?.trim('[', ']')

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

    private fun openIssueView(publication: String, year: Int, issueCount: Int, label: String) {
        waitFor { exists(publication) }
        rule.onNodeWithText(publication).performClick()
        val yearCard = "$publication $year, $issueCount nummer"
        waitFor { rule.onAllNodes(hasContentDescription(yearCard, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription(yearCard, substring = true).performClick()
        val cover = "$publication $year $label"
        waitFor { rule.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription(cover))
        rule.onNodeWithContentDescription(cover).performClick()
        waitFor { exists("Lässtatus") }
    }

    /** Visar sidan tills den räknas som läst: 1,5 s efter att den är färdigrenderad. */
    private fun readPage(page: Int) {
        waitFor(30_000) { fullPage(page) }
        waitFor { page in SeenPages.decode(readingState(context, issueId)?.seenPages) }
    }

    private fun requireCachedPdf() {
        assumeTrue("1986 nr 1 finns inte i PDF-cachen", File(context.cacheDir, "pdf/$issueId.pdf").isFile)
    }

    /**
     * Lästa sidor räknas, snabbläddring gör det inte, och en manuell markering
     * ändrar status utan att röra läshistoriken.
     */
    @Test
    fun progressCountsReadPagesAndManualStatusKeepsHistory() {
        requireCachedPdf()
        resetReadingState(context, issueId)
        openIssueView("Datormagazin", 1986, 4, "Nr 1")
        assertTrue(exists("Oläst"))
        rule.onNodeWithText("Läs").performClick()
        waitFor { readerShown() }

        // Tre sidor läses ordentligt.
        readPage(1)
        tapZone(0.85f)
        readPage(2)
        tapZone(0.85f)
        readPage(3)

        // Sedan snabbläddring förbi sidorna 4–8 fram till sida 9.
        repeat(6) { tapZone(0.85f) }
        showControls()
        waitFor { indicator()?.startsWith("Sida 9 av 28") == true }
        readPage(9)
        assertEquals("Sida 9 av 28 · 14 % läst", indicator())
        assertEquals(SeenPages.of(1, 2, 3, 9), SeenPages.decode(readingState(context, issueId)?.seenPages))

        rule.onNodeWithContentDescription("Stäng läsaren").performClick()
        waitFor { exists("Fortsätt på sida 9") }
        assertTrue(exists("Påbörjad · 14 %"))
        assertTrue(exists("4 av 28"))
        val before = readingState(context, issueId)!!
        assertEquals(9, before.lastPage)
        assertEquals(9, before.maxPageReached)
        assertNotNull(before.firstOpenedAt)
        assertNull(before.completedAt)

        // Markera som oläst: status ändras, men läshistoriken finns kvar.
        rule.onNodeWithText("Markera som oläst").performScrollTo().performClick()
        waitFor { exists("Oläst · 14 % (markerad)") }
        val unread = readingState(context, issueId)!!
        assertEquals(ReadStatusOverride.UNREAD, unread.readStatusOverride)
        assertEquals(before.seenPages, unread.seenPages)
        assertEquals(9, unread.lastPage)
        assertEquals(9, unread.maxPageReached)
        assertTrue(exists("Fortsätt på sida 9"))

        // Markera som läst, och återställ till automatisk status.
        rule.onNodeWithText("Markera som läst").performScrollTo().performClick()
        waitFor { exists("Läst · 14 % (markerad)") }
        assertNotNull(readingState(context, issueId)!!.completedAt)
        rule.onNodeWithText("Återställ").performScrollTo().performClick()
        waitFor { exists("Påbörjad · 14 %") }
        assertFalse(exists("Återställ"))
        assertEquals(before.seenPages, readingState(context, issueId)!!.seenPages)

        // En session är sparad för läsningen.
        val sessions = runBlocking { appContainer(context).database.readingSessionDao().getAll() }
        val session = sessions.last { it.issueId == issueId }
        assertEquals(1, session.startPage)
        assertEquals(9, session.endPage)
        assertEquals(4, session.pagesViewed)
        assertTrue("sessionens längd: ${session.durationSeconds}", session.durationSeconds in 5..300)
    }

    /** Omstartstestets första del: läs fram till sida 4. Positionen ska vara skriven innan appen stoppas. */
    @Test
    fun restartPhase1ReadPages() {
        assumeTrue(arguments.getString("dmzPhase") == "1")
        requireCachedPdf()
        resetReadingState(context, issueId)
        openIssueView("Datormagazin", 1986, 4, "Nr 1")
        rule.onNodeWithText("Läs").performClick()
        waitFor { readerShown() }
        for (page in 1..4) {
            readPage(page)
            if (page < 4) tapZone(0.85f)
        }
        // Läsaren är fortfarande öppen. Den fördröjda skrivningen ska redan ha gått.
        waitFor(5_000) { readingState(context, issueId)?.lastPage == 4 }
        assertEquals(SeenPages.of(1, 2, 3, 4), SeenPages.decode(readingState(context, issueId)?.seenPages))
        Log.i(TAG, "RESTART phase1 klar, lastPage=4")
    }

    /** Omstartstestets andra del, i en ny process: Fortsätt läsa öppnar sida 4 och progressen finns kvar. */
    @Test
    fun restartPhase2ContinueReading() {
        assumeTrue(arguments.getString("dmzPhase") == "2")
        waitFor { exists("Datormagazin") }
        rule.onNodeWithText("Datormagazin").performClick()
        waitFor { rule.onAllNodes(hasTestTag("continue-reading")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(exists("Sida 4 av 28"))
        rule.onNodeWithTag("continue-reading").performClick()
        waitFor { readerShown() }
        showControls()
        assertEquals("Sida 4 av 28 · 14 % läst", indicator())
        assertEquals(SeenPages.of(1, 2, 3, 4), SeenPages.decode(readingState(context, issueId)?.seenPages))
        Log.i(TAG, "RESTART phase2 klar, läsaren öppnade sida 4")
    }

    /**
     * Mätning av förrenderingen: 20 sidbyten framåt i 1995 nr 14 med cirka 3 s
     * mellanrum. För varje byte loggas om nästa sida redan var färdigrenderad.
     */
    @Test
    fun verifyPrerender199514() {
        assumeTrue(arguments.getString("dmzVerify") == "true")
        assumeTrue(File(context.cacheDir, "pdf/datormagazin-1995-14.pdf").isFile)
        resetReadingState(context, "datormagazin-1995-14")
        openIssueView("Datormagazin", 1995, 14, "Nr 14")
        rule.onNodeWithText("Läs").performClick()
        waitFor(60_000) { readerShown() }
        waitFor(30_000) { fullPage(1) }
        Thread.sleep(3_000)
        ReaderViewModel.clearPageTurnLog()
        repeat(20) {
            tapZone(0.85f)
            Thread.sleep(3_000)
            rule.waitForIdle()
        }
        val turns = ReaderViewModel.recentPageTurns()
        val full = turns.count { it.quality == PageQuality.FULL }
        val preview = turns.count { it.quality == PageQuality.PREVIEW }
        Log.i(TAG, "PRERENDER 199514 sidbyten=${turns.size} fardigrenderad=$full forhandsbild=$preview ingenting=${turns.size - full - preview}")
        Log.i(TAG, "PRERENDER 199514 detalj=" + turns.joinToString(" ") { "${it.pages.first()}:${it.quality ?: "NONE"}" })
        assertEquals(20, turns.size)
    }

    /**
     * Verktyg för verifiering på telefonen: nollställer läsdata för numren i
     * argumentet dmzReset (kommaseparerade issueId), så att ett scenario kan
     * göras om från början. Körs aldrig utan argumentet.
     */
    @Test
    fun resetReadingStateOnRequest() {
        val issueIds = arguments.getString("dmzReset")?.split(",")?.filter { it.isNotBlank() }
        assumeTrue(issueIds != null)
        resetReadingState(context, *issueIds!!.toTypedArray())
        Log.i(TAG, "RESET $issueIds")
    }

    private companion object {
        const val TAG = "DmzVerify"
    }
}
