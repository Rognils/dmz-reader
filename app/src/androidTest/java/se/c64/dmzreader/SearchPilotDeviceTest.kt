package se.c64.dmzreader

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import se.c64.dmzreader.data.search.SearchDb

/**
 * FAS 8A: pilotens search.db, byggd på datorn av tools/build_search_index.py, öppnas och söks
 * med appens motor (BundledSQLiteDriver) på telefonen.
 *
 * Filen kopieras tillfälligt till appens cache som search-pilot.db och tas bort efteråt:
 *
 *   adb exec-in run-as se.c64.dmzreader sh -c 'cat > cache/search-pilot.db' < cache/search/search-rd0.db
 *   adb shell am instrument -w -e dmzSearchPilot true \
 *       -e class se.c64.dmzreader.SearchPilotDeviceTest \
 *       se.c64.dmzreader.test/androidx.test.runner.AndroidJUnitRunner
 *   adb shell run-as se.c64.dmzreader rm cache/search-pilot.db
 *
 * De förväntade svaren är facit i tools/testdata/search_golden.json, för varianten
 * unicode61 remove_diacritics 0.
 */
@RunWith(AndroidJUnit4::class)
class SearchPilotDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = context.cacheDir.resolve("search-pilot.db")

    private fun SearchDb.pages(query: String) = search(query).map { "${it.issueId}:${it.pageNumber}" }

    @Test
    fun pilotDatabaseAnswersGoldenQueries() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("dmzSearchPilot") == "true")
        assertTrue("search-pilot.db saknas i appens cache", file.exists())

        SearchDb.openReadOnly(file.path).use { db ->
            val meta = db.meta()
            Log.i(TAG, "sqlite ${db.sqliteVersion()}, fil ${file.length()} byte, meta $meta")
            assertEquals("1", meta["searchSchemaVersion"])
            assertEquals("unicode61 remove_diacritics 0", meta["tokenizer"])
            assertEquals("11", meta["issueCount"])
            assertEquals("585", meta["pageCount"])

            // 1. Utspärrad text i 1995 nr 14: textlagret har "h ö g ta la re".
            val speakers = db.search("högtalare")
            Log.i(TAG, "högtalare: ${speakers.take(5).map { "${it.issueId}:${it.pageNumber}" }}")
            assertEquals("datormagazin-1995-14:27", speakers.first().let { "${it.issueId}:${it.pageNumber}" })
            val spaced = speakers.single { it.issueId == "datormagazin-1995-14" && it.pageNumber == 26 }
            Log.i(TAG, "utdrag sida 26: ${spaced.snippet}")
            assertTrue(spaced.snippet, spaced.snippet.contains("[högtalare]"))

            // 2. Frassökning.
            assertEquals("datormagazin-1986-01:4", db.pages("\"Commodore 128\"").first())

            // 3. Riktigt bindestreck.
            assertEquals("datormagazin-1995-14:25", db.pages("\"CD-ROM\"").first())

            // 4. Avstavat ord med mjukt bindestreck: "teknis-" + "ka".
            assertTrue(db.pages("tekniska").contains("highscore-1993-17:6"))
            assertEquals(listOf("datormagazin-1995-14:20"), db.pages("turboköpet"))

            // 5. å är en egen bokstav: "får" ger samma antal sidor som på datorn (349),
            //    och "far" ger andra sidor.
            val count = { word: String ->
                db.query("SELECT COUNT(*) FROM pages_fts WHERE pages_fts MATCH '\"$word\"'") { it.getLong(0) }.single()
            }
            Log.i(TAG, "får ${count("får")} sidor, far ${count("far")} sidor, här ${count("här")}, har ${count("har")}")
            assertEquals(349L, count("får"))
            assertEquals("datormagazin-1995-05-06:24", db.pages("får").first())
            assertEquals(24L, count("far"))

            val issues = db.query("SELECT issueId, pageCount, ocrPdfSize, ocrPdfEtag FROM issues ORDER BY issueId") {
                "${it.getText(0)} ${it.getLong(1)} ${it.getLong(2)} ${it.getText(3)}"
            }
            Log.i(TAG, "issues: ${issues.first()} … (${issues.size} rader)")
            assertEquals("datormagazin-1986-01 28 15105472 \"1504986566.0-15105472-984024500\"", issues.first())
        }
    }

    private companion object {
        const val TAG = "DmzSearchPilot"
    }
}
