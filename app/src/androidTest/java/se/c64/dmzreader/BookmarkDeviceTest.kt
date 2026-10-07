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
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.c64.dmzreader.data.db.Bookmark
import se.c64.dmzreader.data.db.IssueUserState

/**
 * Bokmärken på en riktig enhet (FAS 6): skapa ett bokmärke i läsaren, ge det
 * en titel, och öppna det från fliken Favoriter på exakt rätt sida.
 *
 * Testet använder sida 5 i 1986 nr 1 och tar bort bokmärket efter sig. Finns
 * det redan ett bokmärke på den sidan hoppas testet över, så att användarens
 * eget bokmärke inte rörs.
 */
@RunWith(AndroidJUnit4::class)
class BookmarkDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val issueId = "datormagazin-1986-01"
    private val page = 5
    private val bookmarkDao get() = appContainer(context).database.bookmarkDao()
    private var created = false

    private fun waitFor(timeoutMillis: Long = 20_000, condition: () -> Boolean) = rule.waitUntil(timeoutMillis, condition)
    private fun exists(text: String, substring: Boolean = false) =
        rule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun described(text: String) = rule.onAllNodes(hasContentDescription(text)).fetchSemanticsNodes().isNotEmpty()
    private fun readerShown() = rule.onAllNodes(hasTestTag("reader")).fetchSemanticsNodes().isNotEmpty()
    private fun bookmarksOnPage(): List<Bookmark> = runBlocking { bookmarkDao.getForPage(issueId, page) }

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

    @After
    fun removeTestBookmark() {
        // Rensar hela raden, även om bokmärket har tagits bort mjukt.
        if (created) runBlocking { bookmarkDao.getAll().filter { it.issueId == issueId && it.pageNumber == page }.forEach { bookmarkDao.purge(it.id) } }
    }

    @Test
    fun createBookmarkInReaderAndOpenItFromFavorites() {
        assumeTrue("sida $page har redan ett bokmärke på telefonen", bookmarksOnPage().isEmpty())
        resetReadingState(context, issueId)

        // Bibliotek → 1986 → Nr 1 → läsaren. Numret hämtas om det inte finns på telefonen.
        waitFor { exists("Datormagazin") }
        rule.onNodeWithText("Datormagazin").performClick()
        val yearCard = "Datormagazin 1986, 4 nummer"
        waitFor { rule.onAllNodes(hasContentDescription(yearCard, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription(yearCard, substring = true).performClick()
        waitFor { rule.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("Datormagazin 1986 Nr 1"))
        rule.onNodeWithContentDescription("Datormagazin 1986 Nr 1").performClick()
        waitFor { exists("Läs") }
        rule.onNodeWithText("Läs").performClick()
        waitFor(120_000) { readerShown() }

        // Bläddra till sida 5 och tryck på stjärnan.
        showControls()
        repeat(page - 1) { tapZone(0.85f) }
        waitFor { indicator()?.startsWith("Sida $page av 28") == true }
        assertTrue(described("Sätt bokmärke på sida $page"))
        rule.onNodeWithTag("bookmark-toggle").performClick()
        created = true
        waitFor { exists("Bokmärke sparat") }
        waitFor { described("Ta bort bokmärket på sida $page") }
        assertEquals("Sida $page", bookmarksOnPage().single().title)

        // Snackbarens åtgärd öppnar dialogen för titel och anteckning.
        rule.onNodeWithText("Lägg till titel").performClick()
        waitFor { rule.onAllNodes(hasTestTag("bookmark-title")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("bookmark-title").performTextInput("Testbokmärke")
        rule.onNodeWithTag("bookmark-note").performTextInput("Skapat av ett test")
        rule.onNodeWithText("Spara").performClick()
        waitFor { bookmarksOnPage().singleOrNull()?.title == "Testbokmärke" }
        assertEquals("Skapat av ett test", bookmarksOnPage().single().note)

        // Bläddra bort, så att läspositionen inte är bokmärkets sida, och stäng läsaren.
        rule.mainClock.advanceTimeBy(11_000)
        tapZone(0.85f)
        tapZone(0.85f)
        showControls()
        waitFor { indicator()?.startsWith("Sida ${page + 2} av 28") == true }
        rule.onNodeWithContentDescription("Stäng läsaren").performClick()
        waitFor { exists("Bibliotek") && !readerShown() }
        // Nummervyn listar bokmärket.
        waitFor { exists("Sida $page · Testbokmärke") }

        // Fliken Favoriter: bokmärket står under sitt nummer, med anteckningen.
        rule.onNodeWithText("Favoriter").performClick()
        waitFor { rule.onAllNodes(hasTestTag("favorites")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("favorites").performScrollToNode(hasText("Sida $page · Testbokmärke"))
        assertTrue(exists("Skapat av ett test"))
        rule.onNodeWithText("Sida $page · Testbokmärke").performClick()

        // Läsaren öppnas på exakt bokmärkets sida, inte på läspositionen.
        waitFor(30_000) { readerShown() }
        showControls()
        assertEquals("Sida $page av 28", indicator()!!.substringBefore(" ·"))
        assertTrue(described("Ta bort bokmärket på sida $page"))

        // Ett tryck till tar bort bokmärket, och Ångra lägger tillbaka det oförändrat.
        val before = bookmarksOnPage().single()
        rule.onNodeWithTag("bookmark-toggle").performClick()
        waitFor { exists("Bokmärket borttaget") }
        assertTrue(bookmarksOnPage().isEmpty())
        rule.onNodeWithText("Ångra").performClick()
        waitFor { bookmarksOnPage().isNotEmpty() }
        // Samma bokmärke som förut: samma identitet, titel och anteckning.
        val restored = bookmarksOnPage().single()
        assertEquals(before.bookmarkId, restored.bookmarkId)
        assertEquals(before.id, restored.id)
        assertEquals("Testbokmärke", restored.title)
        assertEquals("Skapat av ett test", restored.note)
    }

    /**
     * Verktyg för verifiering av planens D6: lägger in en favorit och två bokmärken
     * för ett issueId som inte finns i arkivindexet. Körs bara med argumentet dmzGhost.
     */
    @Test
    fun insertDataForMissingIssueOnRequest() = runBlocking<Unit> {
        assumeTrue(arguments.getString("dmzGhost") == "true")
        val ghost = "datormagazin-1999-99"
        val database = appContainer(context).database
        if (database.issueUserStateDao().get(ghost) == null) {
            database.issueUserStateDao().insert(IssueUserState(issueId = ghost, isFavorite = true, lastPage = 4, updatedAt = System.currentTimeMillis()))
        }
        if (database.bookmarkDao().getForPage(ghost, 4).isEmpty()) {
            val now = System.currentTimeMillis()
            database.bookmarkDao().insert(Bookmark(issueId = ghost, pageNumber = 4, title = "Påhittat nummer", note = "Finns inte i arkivindexet", createdAt = now, updatedAt = now))
            database.bookmarkDao().insert(Bookmark(issueId = ghost, pageNumber = 9, title = "Sida 9", createdAt = now - 1, updatedAt = now - 1))
        }
        Log.i("DmzVerify", "GHOST $ghost inlagt")
    }

    /**
     * Verktyg för verifiering av planens C7: ändrar den sparade PDF-storleken för
     * bokmärkena i numret i argumentet dmzStale, som om filen hade bytts i arkivet
     * sedan de skapades.
     */
    @Test
    fun makeBookmarksStaleOnRequest() = runBlocking<Unit> {
        val staleIssueId = arguments.getString("dmzStale")
        assumeTrue(staleIssueId != null)
        val dao = appContainer(context).database.bookmarkDao()
        val changed = dao.getAll().filter { it.issueId == staleIssueId }.onEach { dao.update(it.copy(pdfSize = 1)) }
        Log.i("DmzVerify", "STALE $staleIssueId: ${changed.size} bokmärken")
    }
}
