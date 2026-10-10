package se.c64.dmzreader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Fulltextsökningen på en riktig enhet (FAS 9): importera ett sökindex, sök,
 * öppna en träff på rätt sida och gå till nästa träff i numret.
 *
 * Testet använder det lilla testindexet i debug-assets (tools/build_search_testdb.py)
 * och läser i 1986 nr 1. Allt sker i testkörningens isolerade lagring (IsolatedTestRunner):
 * appens riktiga sökindex, databas och läs-cache rörs inte.
 */
@RunWith(AndroidJUnit4::class)
class FullTextDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = appContainer(context)
    private val issueId = "datormagazin-1986-01"
    private fun waitFor(timeoutMillis: Long = 30_000, condition: () -> Boolean) = rule.waitUntil(timeoutMillis, condition)
    private fun exists(text: String, substring: Boolean = false) =
        rule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

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

    @Before
    fun prepare() {
        resetReadingState(context, issueId)
        runBlocking {
            container.searchIndex.remove()
            val info = container.searchIndex.import({ context.assets.open("search_test.db") }, null)
            assertEquals(6, info.issueCount)
        }
    }

    @After
    fun removeIndex() {
        runBlocking { container.searchIndex.remove() }
    }

    @Test
    fun importSearchOpenHitAndStepToNextHit() {
        // Sökfliken.
        rule.onNodeWithText("Sök").performClick()
        waitFor { tagged("search-field") }
        rule.onNodeWithTag("search-field").performTextInput("Commodore")
        waitFor { tagged("hit-summary") }
        // "Commodore" finns på en sida i 1986 nr 1.
        waitFor { textOf("hit-summary") == "1 sida i 1 nummer" }
        waitFor { tagged("group-$issueId") }
        waitFor { exists("introducerades", substring = true) } // textutdraget har hämtats

        // Öppna träffen: läsaren ska stå på sida 4.
        rule.onAllNodesWithTag("hit-row").onFirst().performClick()
        waitFor(120_000) { tagged("reader") }
        waitFor { tagged("hit-bar") }
        assertEquals("Commodore · träff 1 av 1", textOf("hit-bar-text"))
        showControls()
        assertTrue(indicator(), indicator()!!.startsWith("Sida 4 av 28"))

        // Sök i numret från läsaren: "64" finns på sidorna 4 och 25, "Commodore 64" bara på sida 4.
        rule.onNodeWithTag("reader-search").performClick()
        waitFor { tagged("issue-search-field") }
        rule.onNodeWithTag("issue-search-field").performTextInput(" 64")
        // Sökfältet innehåller nu "Commodore 64": båda orden finns bara på sida 4.
        waitFor { textOf("issue-search-status") == "1 sida" }
        rule.onNodeWithText("Stäng").performClick()

        // Ny sökning i numret med två träffsidor, och steg mellan dem.
        rule.onNodeWithTag("reader-search").performClick()
        waitFor { tagged("issue-search-field") }
        rule.onNodeWithTag("issue-search-field").performTextReplacement("64")
        waitFor { textOf("issue-search-status") == "2 sidor" }
        rule.onAllNodesWithTag("hit-row").onFirst().performClick()
        waitFor { textOf("hit-bar-text") == "64 · träff 1 av 2" }
        showControls()
        waitFor { indicator()!!.startsWith("Sida 4 av 28") }

        rule.onNodeWithContentDescription("Nästa träff").performClick()
        waitFor { textOf("hit-bar-text") == "64 · träff 2 av 2" }
        waitFor { indicator()!!.startsWith("Sida 25 av 28") }
        rule.onNodeWithContentDescription("Nästa träff").performClick()
        waitFor { textOf("hit-bar-text") == "64 · träff 1 av 2" }
        waitFor { indicator()!!.startsWith("Sida 4 av 28") }
        rule.onNodeWithContentDescription("Föregående träff").performClick()
        waitFor { indicator()!!.startsWith("Sida 25 av 28") }

        // Raden kan stängas.
        rule.onNodeWithContentDescription("Stäng träffraden").performClick()
        waitFor { !tagged("hit-bar") }
        assertNotNull(indicator())
    }

    /** Planens D3: ett sökindex från en annan arkivversion fungerar, och sökvyn visar en diskret notis. */
    @Test
    fun indexFromAnotherArchiveVersionWorksAndShowsANotice() {
        val other = File(storage(context).cacheDir, "search-other-version.db")
        context.assets.open("search_test.db").use { input -> other.outputStream().use { input.copyTo(it) } }
        BundledSQLiteDriver().open(other.path).use { connection ->
            connection.execSQL("UPDATE meta SET value = '2020-01-01-001' WHERE key = 'inventoryDatasetVersion'")
        }
        runBlocking {
            val info = container.searchIndex.import({ other.inputStream() }, other.length())
            assertEquals("2020-01-01-001", info.inventoryDatasetVersion)
        }
        other.delete()

        rule.onNodeWithText("Sök").performClick()
        waitFor { tagged("search-field") }
        rule.onNodeWithTag("search-field").performTextInput("Commodore")
        waitFor { textOf("hit-summary") == "1 sida i 1 nummer" }
        assertTrue(exists("Sökindexet är från en annan arkivversion."))
        waitFor { exists("introducerades", substring = true) }
    }

    @Test
    fun filtersSortingAndOutdatedIndexAreShown() {
        rule.onNodeWithText("Sök").performClick()
        waitFor { tagged("search-field") }
        rule.onNodeWithTag("search-field").performTextInput("Amiga")
        // Sex sidor i indexet, men en hör till ett nummer som inte finns i arkivindexet och döljs.
        waitFor { textOf("hit-summary") == "5 sidor i 3 nummer" }
        // 1993 nr 3 har en annan filstorlek i testindexet än i arkivindexet.
        rule.scrollToNodeWhenLoaded(hasTestTag("group-datormagazin-1993-03"), container = hasTestTag("search"))
        assertTrue(exists("Sökindexet gäller en äldre version av filen"))

        // Äldst först: 1989 nr 6 kommer före 1995 nr 14.
        rule.scrollToNodeWhenLoaded(hasTestTag("hit-summary"), container = hasTestTag("search"))
        rule.onNodeWithText("Äldst först").performClick()
        rule.waitForIdle()
        val order = rule.onAllNodes(hasTestTag("group-datormagazin-1989-06") or hasTestTag("group-datormagazin-1995-14"))
            .fetchSemanticsNodes().map { it.config.first { entry -> entry.key.name == "TestTag" }.value }
        assertEquals("group-datormagazin-1989-06", order.first())

        // Filtret HighScore lämnar en sida kvar av de fyra med ordet "spel".
        rule.onNodeWithTag("search-field").performTextReplacement("spel")
        waitFor { textOf("hit-summary") == "4 sidor i 3 nummer" }
        rule.onNodeWithText("HighScore").performClick()
        waitFor { textOf("hit-summary") == "1 sida i 1 nummer" }
        assertTrue(tagged("group-highscore-1993-17"))
    }
}
