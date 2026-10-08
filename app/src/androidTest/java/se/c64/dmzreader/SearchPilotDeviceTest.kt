package se.c64.dmzreader

import android.os.SystemClock
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
 * FAS 8B: den fullständiga search.db (185 nummer, 11 293 sidor), byggd på datorn av
 * tools/build_search_index.py, öppnas och söks med appens motor (BundledSQLiteDriver)
 * på telefonen. Testet mäter också svarstiden för typiska sökningar.
 *
 * Filen kopieras tillfälligt till appens cache som search-pilot.db och tas bort efteråt:
 *
 *   adb exec-in run-as se.c64.dmzreader sh -c 'cat > cache/search-pilot.db' < dist/search.db
 *   adb shell am instrument -w -e dmzSearchPilot true \
 *       -e class se.c64.dmzreader.SearchPilotDeviceTest \
 *       se.c64.dmzreader.test/androidx.test.runner.AndroidJUnitRunner
 *   adb shell run-as se.c64.dmzreader rm cache/search-pilot.db
 *
 * De förväntade svaren är facit i tools/testdata/search_golden.json och gäller
 * inventory 2026-10-06-005 med normalizerVersion 2.
 */
@RunWith(AndroidJUnit4::class)
class SearchPilotDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = context.cacheDir.resolve("search-pilot.db")

    private fun SearchDb.pages(query: String) = search(query).map { "${it.issueId}:${it.pageNumber}" }

    private fun enabled() = InstrumentationRegistry.getArguments().getString("dmzSearchPilot") == "true"

    @Test
    fun fullDatabaseAnswersGoldenQueries() {
        assumeTrue(enabled())
        assertTrue("search-pilot.db saknas i appens cache", file.exists())

        SearchDb.openReadOnly(file.path).use { db ->
            val meta = db.meta()
            Log.i(TAG, "sqlite ${db.sqliteVersion()}, fil ${file.length()} byte, meta $meta")
            assertEquals("1", meta["searchSchemaVersion"])
            assertEquals("2", meta["normalizerVersion"])
            assertEquals("2026-10-06-005", meta["inventoryDatasetVersion"])
            assertEquals("unicode61 remove_diacritics 0", meta["tokenizer"])
            assertEquals("185", meta["issueCount"])
            assertEquals("11293", meta["pageCount"])

            // 1. Utspärrad text i 1995 nr 14: textlagret har "h ö g ta la re".
            val speakers = db.search("högtalare")
            assertEquals("datormagazin-1995-14:27", speakers.first().let { "${it.issueId}:${it.pageNumber}" })
            val spaced = speakers.single { it.issueId == "datormagazin-1995-14" && it.pageNumber == 26 }
            Log.i(TAG, "utdrag 1995 nr 14 sida 26: ${spaced.snippet}")
            assertTrue(spaced.snippet, spaced.snippet.contains("[högtalare]"))
            assertEquals(listOf("datormagazin-1995-14:20"), db.pages("turboköpet"))

            // 2. Frassökningar, i och utanför piloten.
            assertEquals("datormagazin-1986-01:4", db.pages("\"Commodore 128\"").first())
            assertEquals("datormagazin-1988-12:19", db.pages("\"Decision in the Desert\"").first())
            assertEquals("datormagazin-1989-15:6", db.pages("\"Anders Limpar\"").first())
            assertEquals("highscore-1993-17:6", db.pages("\"Mikael Roth\"").first())

            // 3. Ord med å, ä och ö ur nummer utanför piloten, bland dem 1995 nr 15.
            assertTrue(db.pages("starköl").contains("datormagazin-1988-12:22"))
            assertTrue(db.pages("Visionärer").contains("datormagazin-1995-15:10"))
            assertEquals("highscore-1993-20:21", db.pages("terminatorn").first())

            // 4. Riktigt bindestreck.
            assertTrue(db.pages("\"CD-ROM\"").isNotEmpty())

            // 5. å är en egen bokstav: samma antal sidor som på datorn.
            val count = { word: String ->
                db.query("SELECT COUNT(*) FROM pages_fts WHERE pages_fts MATCH '\"$word\"'") { it.getLong(0) }.single()
            }
            Log.i(TAG, "får ${count("får")} sidor, far ${count("far")}, här ${count("här")}, har ${count("har")}")
            assertEquals(6202L, count("får"))
            assertEquals(445L, count("far"))
            assertEquals(6210L, count("här"))
            assertEquals(9011L, count("har"))

            val issue = db.query(
                "SELECT pageCount, ocrPdfSize, ocrPdfEtag, ocrPdfSha256 FROM issues WHERE issueId = 'datormagazin-1995-15'",
            ) { "${it.getLong(0)} ${it.getLong(1)} ${it.getText(2)} ${it.getText(3)}" }.single()
            Log.i(TAG, "issues, 1995 nr 15: $issue")
            assertEquals(
                "92 563123977 \"1493509840.0-563123977-985793977\" " +
                    "9b24e04d37bd13ae5d9f8cc744536a7e15be0f02d7f8fc209efece9f5025c6f9",
                issue,
            )
        }
    }

    /** Svarstid för appens sökfråga (bm25, utdrag, högst 50 träffar), fem varv per sökord. */
    @Test
    fun responseTimes() {
        assumeTrue(enabled())
        val queries = listOf(
            "Fairlight", "högtalare", "joystick", "piratkopiering", "Amiga", "för", "\"Commodore 128\"",
            "\"CD-ROM\"", "\"Street Fighter\"", "hårddisk", "C64", "\"Last Ninja\"", "skärm", "Wolfenstein",
            "\"Amiga 1200\"", "turboköpet",
        )
        val opened = SystemClock.elapsedRealtimeNanos()
        SearchDb.openReadOnly(file.path).use { db ->
            val first = SystemClock.elapsedRealtimeNanos()
            db.search("Infiltrator")
            Log.i(TAG, "öppna ${(first - opened) / 1_000_000.0} ms, första sökningen " +
                "${(SystemClock.elapsedRealtimeNanos() - first) / 1_000_000.0} ms")
            val all = ArrayList<Double>()
            for (query in queries) {
                val times = (1..5).map {
                    val start = SystemClock.elapsedRealtimeNanos()
                    val hits = db.search(query)
                    assertTrue(query, hits.isNotEmpty())
                    (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                }
                all += times
                Log.i(TAG, "tid %-18s median %.1f ms, max %.1f ms".format(query, times.sorted()[2], times.max()))
            }
            val inIssue = (1..5).map {
                val start = SystemClock.elapsedRealtimeNanos()
                db.search("Amiga", issueId = "datormagazin-1995-15")
                (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            }
            Log.i(TAG, "tid Amiga i ett nummer: median %.1f ms, max %.1f ms".format(inIssue.sorted()[2], inIssue.max()))
            all.sort()
            Log.i(TAG, "SVARSTID ${all.size} sökningar: median %.1f ms, max %.1f ms".format(all[all.size / 2], all.last()))
        }
    }

    private companion object {
        const val TAG = "DmzSearchPilot"
    }
}
