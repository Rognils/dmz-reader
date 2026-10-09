package se.c64.dmzreader

import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import java.io.File
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

/**
 * Verifiering mot det riktiga sökindexet (dist/search.db, 185 nummer). Körs bara med
 * -e dmzRealIndex true, och när datorn har lagt filen på telefonen:
 *
 *   adb push dist/search.db /data/local/tmp/dmz-test-search.db
 *
 * Testet importerar filen i testkörningens isolerade lagring, skriver sökorden i
 * sökfältet, kontrollerar antalet träffar mot det datorn ger, mäter tiden från
 * inmatning till visat resultat och öppnar en träff på rätt sida. Vid varje
 * "SKÄRMBILD" i loggen väntar det några sekunder, så att en skärmbild kan tas utifrån.
 */
@RunWith(AndroidJUnit4::class)
class RealIndexDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val container get() = appContainer(context)
    private val issueId = "datormagazin-1986-01"

    private fun waitFor(timeoutMillis: Long = 30_000, condition: () -> Boolean) = rule.waitUntil(timeoutMillis, condition)
    private fun tagged(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun textOf(tag: String): String? = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()
        .firstOrNull()?.config?.firstOrNull { it.key.name == "Text" }?.value?.toString()?.trim('[', ']')

    private fun indicator() = textOf("page-indicator")

    private fun showControls() {
        if (indicator() == null) {
            rule.onNodeWithTag("reader").performTouchInput { click(Offset(width * 0.5f, height * 0.45f)) }
            rule.mainClock.advanceTimeBy(400)
            rule.waitForIdle()
        }
        waitFor { indicator() != null }
    }

    /** Pausar så att en skärmbild kan tas utifrån. */
    private fun screenshot(name: String) {
        // Tangentbordet skymmer annars halva skärmen.
        if (tagged("search-field")) androidx.test.espresso.Espresso.closeSoftKeyboard()
        rule.waitForIdle()
        Log.i(TAG, "SKÄRMBILD $name")
        Thread.sleep(arguments.getString("dmzPause")?.toLongOrNull() ?: 0L)
    }

    /** Skriver sökordet och väntar på resultatet. Returnerar tiden från inmatning till visad sammanfattning. */
    private fun search(text: String, expected: String): Long {
        val start = SystemClock.elapsedRealtime()
        rule.onNodeWithTag("search-field").performTextReplacement(text)
        waitFor { textOf("hit-summary") == expected }
        val shown = SystemClock.elapsedRealtime() - start
        // Textutdragen för de synliga raderna hämtas i ett andra steg.
        waitFor {
            rule.onAllNodes(hasTestTag("hit-row")).fetchSemanticsNodes().firstOrNull()
                ?.config?.firstOrNull { it.key.name == "Text" }?.value?.toString()?.contains(" · ") == true
        }
        val snippets = SystemClock.elapsedRealtime() - start
        Log.i(TAG, "TID %-18s %s: resultat efter %d ms, första textutdragen efter %d ms (varav 300 ms väntan på paus)"
            .format(text, expected, shown, snippets))
        return shown
    }

    @Before
    fun prepare() {
        assumeTrue(arguments.getString("dmzRealIndex") == "true")
        val file = File("/data/local/tmp/dmz-test-search.db")
        assumeTrue("${file.path} saknas", file.canRead())
        val info = runBlocking { container.searchIndex.import({ file.inputStream() }, file.length()) }
        assumeTrue("Filen är inte det riktiga sökindexet", info.issueCount == 185)
        resetReadingState(context, issueId)
    }

    @Test
    fun searchSortFilterAndOpenHit() {
        rule.onNodeWithText("Sök").performClick()
        waitFor { tagged("search-field") }
        screenshot("sok-tom")

        // Antalen är de som datorn ger för samma frågor mot samma fil.
        search("högtalare", "230 sidor i 110 nummer")
        screenshot("hogtalare")
        search("Amiga", "7289 sidor i 185 nummer")
        screenshot("amiga")
        search("C64", "3022 sidor i 184 nummer")
        screenshot("c64")
        search("amig*", "8204 sidor i 185 nummer")
        search("skärm", "1057 sidor i 177 nummer")
        screenshot("skarm")

        // Sortering: äldst först börjar med 1986 nr 1.
        search("Amiga", "7289 sidor i 185 nummer")
        rule.onNodeWithText("Äldst först").performClick()
        waitFor { tagged("group-datormagazin-1986-01") }
        screenshot("aldst-forst")
        rule.onNodeWithText("Relevans").performClick()

        // Filter: åren 1993–1995.
        rule.onNodeWithTag("year-filter").performScrollTo().performClick()
        waitFor { tagged("year-range") }
        rule.onNodeWithTag("year-1993").performClick()
        rule.onNodeWithTag("year-1995").performClick()
        waitFor { textOf("year-range-text") == "1993–1995" }
        screenshot("ar-dialog")
        rule.onNodeWithText("Använd").performClick()
        waitFor { textOf("hit-summary") == "2618 sidor i 72 nummer" }
        screenshot("filter-1993-1995")
        rule.onNodeWithTag("year-filter").performScrollTo().performClick()
        waitFor { tagged("year-range") }
        rule.onNodeWithText("Alla år").performClick()
        waitFor { textOf("hit-summary") == "7289 sidor i 185 nummer" }

        // Frassökning, och öppna den bästa träffen: 1986 nr 1, sida 4.
        search("\"Commodore 128\"", "168 sidor i 73 nummer")
        waitFor { tagged("group-$issueId") }
        screenshot("commodore-128")
        val first = rule.onAllNodes(hasTestTag("hit-row")).fetchSemanticsNodes().first()
        assertTrue(first.config[SemanticsProperties.Text].toString().contains("Sida 4"))
        rule.onAllNodesWithTag("hit-row").onFirst().performClick()
        waitFor(180_000) { tagged("reader") }
        waitFor { tagged("hit-bar") }
        showControls()
        assertTrue(indicator(), indicator()!!.startsWith("Sida 4 av 28"))
        assertTrue(textOf("hit-bar-text"), textOf("hit-bar-text")!!.startsWith("\"Commodore 128\" · träff 1 av "))
        screenshot("lasaren-traff")

        // Sök i numret från läsaren: "Amiga" finns på sidorna 2, 13 och 18.
        rule.onNodeWithTag("reader-search").performClick()
        waitFor { tagged("issue-search-field") }
        rule.onNodeWithTag("issue-search-field").performTextReplacement("Amiga")
        waitFor { textOf("issue-search-status") == "3 sidor" }
        screenshot("sok-i-numret")
        rule.onAllNodesWithTag("hit-row").onFirst().performClick()
        waitFor { textOf("hit-bar-text") == "Amiga · träff 1 av 3" }
        showControls()
        waitFor { indicator()!!.startsWith("Sida 2 av 28") }
        rule.onNodeWithContentDescription("Nästa träff").performClick()
        waitFor { textOf("hit-bar-text") == "Amiga · träff 2 av 3" }
        waitFor { indicator()!!.startsWith("Sida 13 av 28") }
        screenshot("lasaren-nasta-traff")
        rule.onNodeWithContentDescription("Nästa träff").performClick()
        waitFor { indicator()!!.startsWith("Sida 18 av 28") }
        assertEquals("Amiga · träff 3 av 3", textOf("hit-bar-text"))
    }

    private companion object {
        const val TAG = "DmzRealIndex"
    }
}
