package se.c64.dmzreader

import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
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
import androidx.room3.useWriterConnection
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
import se.c64.dmzreader.data.db.Bookmark
import se.c64.dmzreader.data.db.IssueUserState
import se.c64.dmzreader.data.db.ReadStatusOverride
import se.c64.dmzreader.data.db.ReadingSession
import se.c64.dmzreader.domain.SeenPages
import se.c64.dmzreader.domain.weekStart
import java.time.LocalDate
import java.time.ZoneId

/**
 * Statistiken och lässessionerna på en riktig enhet (FAS 10). Allt sker i testkörningens
 * isolerade databas (IsolatedTestRunner), som töms på användardata före varje test.
 *
 * Tvångsstoppet körs av tools/device_test.sh i två delar, eftersom ett test inte kan
 * stoppa sin egen process: den första delen läser tills skriptet stoppar appen, och den
 * andra kontrollerar i en ny process att sessionen finns kvar.
 */
@RunWith(AndroidJUnit4::class)
class StatisticsDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val container get() = appContainer(context)
    private val issueId = "datormagazin-1986-01"

    private fun waitFor(timeoutMillis: Long = 30_000, condition: () -> Boolean) = rule.waitUntil(timeoutMillis, condition)
    private fun exists(text: String, substring: Boolean = false) =
        rule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun tagged(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun described(tag: String): String = rule.onNodeWithTag(tag).fetchSemanticsNode()
        .config[SemanticsProperties.ContentDescription].joinToString()

    private fun sessions(): List<ReadingSession> = runBlocking { container.database.readingSessionDao().getAll() }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        Log.i(TAG, "SKÄRMBILD $name")
        Thread.sleep(arguments.getString("dmzPause")?.toLongOrNull() ?: 0L)
    }

    /** Tömmer användardatan i testdatabasen. Körs aldrig mot appens riktiga databas: se appContainer. */
    private fun clearUserData() = runBlocking {
        container.database.useWriterConnection { connection ->
            for (table in listOf("IssueUserState", "Bookmark", "ReadingSession")) {
                connection.usePrepared("DELETE FROM $table") { it.step() }
            }
        }
    }

    private fun openReader() {
        waitFor { exists("Datormagazin") }
        rule.onNodeWithText("Datormagazin").performClick()
        val yearCard = "Datormagazin 1986, 4 nummer"
        waitFor { rule.onAllNodes(hasContentDescription(yearCard, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription(yearCard, substring = true).performClick()
        waitFor { rule.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.scrollToNodeWhenLoaded(hasContentDescription("Datormagazin 1986 Nr 1"))
        rule.onNodeWithContentDescription("Datormagazin 1986 Nr 1").performClick()
        waitFor { exists("Lässtatus") }
        rule.onNode(hasText("Läs") or hasText("Fortsätt på sida", substring = true)).performClick()
        waitFor(180_000) { tagged("reader") }
    }

    private fun tap(fractionX: Float) {
        rule.onNodeWithTag("reader").performTouchInput { click(Offset(width * fractionX, height * 0.45f)) }
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
    }

    @Before
    fun restoreDefaults() {
        container.sessionIdleLimitMillis = se.c64.dmzreader.domain.SESSION_IDLE_LIMIT_MILLIS
        container.sessionSaveIntervalMillis = se.c64.dmzreader.domain.SESSION_SAVE_INTERVAL_MILLIS
    }

    @Test
    fun emptyStateThenFiguresFromKnownData() {
        assumeTrue(arguments.getString("dmzPhase") == null)
        clearUserData()
        rule.onNodeWithText("Statistik").performClick()
        waitFor { tagged("statistics-empty") }
        assertTrue(exists("Inget läst ännu"))
        // Årgångarna visas även utan läsdata, med noll.
        assertTrue(tagged("year-datormagazin-1986"))
        screenshot("tom")

        val now = System.currentTimeMillis()
        val monday = weekStart(LocalDate.now()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        fun pages(range: IntRange) = SeenPages.of(*range.toList().toIntArray()).encode()
        fun session(startedAt: Long, seconds: Int, issue: String) = ReadingSession(
            issueId = issue, startedAt = startedAt, endedAt = startedAt + seconds * 1000L, startPage = 1, endPage = 5, pagesViewed = 5, durationSeconds = seconds,
        )
        runBlocking {
            val states = container.database.issueUserStateDao()
            // 1986: nr 1 helt läst (28 sidor), nr 2 påbörjat, nr 3 markerat som läst.
            states.insert(IssueUserState(issueId = "datormagazin-1986-01", seenPages = pages(1..28), isFavorite = true, updatedAt = now))
            states.insert(IssueUserState(issueId = "datormagazin-1986-02", seenPages = pages(1..10), updatedAt = now))
            states.insert(IssueUserState(issueId = "datormagazin-1986-03", readStatusOverride = ReadStatusOverride.READ, updatedAt = now))
            states.insert(IssueUserState(issueId = "datormagazin-1989-06", seenPages = pages(1..12), updatedAt = now))
            states.insert(IssueUserState(issueId = "highscore-1993-17", seenPages = pages(1..32), updatedAt = now))
            container.database.bookmarkDao().insert(Bookmark(issueId = "datormagazin-1986-01", pageNumber = 4, createdAt = now, updatedAt = now))
            val dao = container.database.readingSessionDao()
            dao.insert(session(monday + 3_600_000, 42 * 60, "datormagazin-1986-01"))          // den här veckan
            dao.insert(session(monday - 6 * 86_400_000L, 2 * 3600 + 5 * 60, "datormagazin-1986-02")) // förra veckan
            dao.insert(session(monday - 20 * 86_400_000L, 35 * 60, "datormagazin-1989-06"))   // tre veckor sedan
            dao.insert(session(monday - 55 * 86_400_000L, 75 * 60, "highscore-1993-17"))      // åtta veckor sedan
        }

        waitFor { tagged("figure-read") }
        assertEquals("Lästa nummer: 3", described("figure-read"))       // 1986 nr 1, nr 3 (markerat) och HighScore 1993 nr 17
        assertEquals("Påbörjade: 2", described("figure-started"))
        assertEquals("Lästa sidor: 82", described("figure-pages"))      // 28 + 10 + 12 + 32
        assertEquals("Lästid: 4 h 37 min", described("figure-time"))    // 42 + 125 + 35 + 75 minuter
        val thisWeek = "week-${weekStart(LocalDate.now())}"
        assertTrue(described(thisWeek), described(thisWeek).endsWith(": 42 min"))
        assertEquals(
            "Datormagazin 1986: 2 av 4 nummer lästa, 27 procent av sidorna lästa", // 38 av 141 sidor
            described("year-datormagazin-1986"),
        )
        assertTrue(exists("27 % sidor"))
        screenshot("testdata")

        rule.scrollToNodeWhenLoaded(hasTestTag("statistics-other"), container = hasTestTag("statistics"))
        assertTrue(exists("Datormagazin 1986 · 38 sidor"))
        screenshot("testdata-ovrigt")

        // Ett tryck på en årgång öppnar årgångsvyn.
        rule.scrollToNodeWhenLoaded(hasTestTag("year-datormagazin-1986"), container = hasTestTag("statistics"))
        rule.onNodeWithTag("year-datormagazin-1986").performClick()
        waitFor { exists("/ 4 lästa", substring = true) }
    }

    /**
     * Inaktivitetsgränsen: läsaren ligger öppen utan att någon rör den. Bara tiden fram till
     * gränsen räknas. Gränsen är här kortad till 8 sekunder; logiken är densamma som med 5 minuter.
     */
    @Test
    fun idleTimeIsNotCounted() {
        assumeTrue(arguments.getString("dmzPhase") == null)
        clearUserData()
        container.sessionIdleLimitMillis = 8_000
        container.sessionSaveIntervalMillis = 2_000
        openReader()
        val opened = SystemClock.elapsedRealtime()

        // 20 sekunder utan interaktion: högst 8 räknas.
        Thread.sleep(20_000)
        rule.waitForIdle()
        val idle = sessions().single { it.issueId == issueId }
        Log.i(TAG, "INAKTIV öppen ${(SystemClock.elapsedRealtime() - opened) / 1000} s, räknad tid ${idle.durationSeconds} s")
        assertTrue("räknad tid efter 20 s utan interaktion: ${idle.durationSeconds} s", idle.durationSeconds in 7..9)

        // Ett tryck sätter igång klockan igen. Luckan räknas inte.
        tap(0.5f)
        Thread.sleep(5_000)
        rule.waitForIdle()
        val after = sessions().single { it.issueId == issueId }
        Log.i(TAG, "INAKTIV efter ett tryck och 5 s till: ${after.durationSeconds} s")
        // Raden skrivs varannan sekund i det här testet, så det senast skrivna värdet kan vara upp till 2 s gammalt.
        assertTrue("räknad tid efter tryck: ${after.durationSeconds} s", after.durationSeconds in 10..14)
        assertEquals(idle.id, after.id)
    }

    /** Tvångsstoppets första del: läs, byt sida, och fortsätt läsa tills skriptet stoppar appen. */
    @Test
    fun forceStopPhase1Read() {
        assumeTrue(arguments.getString("dmzPhase") == "1")
        clearUserData()
        openReader()
        Log.i(TAG, "LÄSER start")
        // Normala inställningar: sessionen skrivs var 30:e sekund och 2 s efter ett sidbyte.
        Thread.sleep(18_000)
        tap(0.85f)
        Log.i(TAG, "LÄSER sidbyte")
        // Skriptet stoppar appen under den här väntan.
        Thread.sleep(120_000)
    }

    /** Tvångsstoppets andra del, i en ny process: sessionen finns kvar med ungefär rätt aktiv tid. */
    @Test
    fun forceStopPhase2SessionSurvived() {
        assumeTrue(arguments.getString("dmzPhase") == "2")
        val read = arguments.getString("dmzReadSeconds")!!.toInt()
        val session = sessions().single { it.issueId == issueId }
        Log.i(TAG, "TVÅNGSSTOPP läste $read s före stoppet, sparad aktiv tid ${session.durationSeconds} s, sida ${session.startPage}–${session.endPage}")
        // Högst ett sparintervall (30 s) går förlorat, och inget mer än den lästa tiden kan vara sparat.
        assertTrue("sparad tid ${session.durationSeconds} s av $read s", session.durationSeconds in (read - 31)..(read + 1))
        assertTrue(session.durationSeconds >= 20) // sidbytet vid 18 s skrevs 2 s senare
        assertEquals(2, session.endPage)

        // Statistiken visar lästiden efter omstarten.
        rule.onNodeWithText("Statistik").performClick()
        waitFor { tagged("figure-time") }
        assertTrue(described("figure-time"), described("figure-time") != "Lästid: 0 min")
    }

    private companion object {
        const val TAG = "DmzStatistics"
    }
}
