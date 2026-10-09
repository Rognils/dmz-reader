package se.c64.dmzreader

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import se.c64.dmzreader.data.search.SearchDb
import java.io.File

/**
 * FAS 8B: den fullständiga search.db (185 nummer, 11 293 sidor), byggd på datorn av
 * tools/build_search_index.py, öppnas och söks med appens motor (BundledSQLiteDriver)
 * på telefonen. Testet mäter också kopieringstid, svarstider och minne.
 *
 * Filen läggs tillfälligt utanför appen och tas bort efteråt. Testet rör aldrig appens
 * egen databas eller inställningar.
 *
 *   adb push dist/search.db /data/local/tmp/dmz-search.db
 *   adb shell am instrument -w -e dmzSearchPilot true \
 *       -e class se.c64.dmzreader.SearchPilotDeviceTest \
 *       se.c64.dmzreader.test/se.c64.dmzreader.IsolatedTestRunner
 *   adb shell rm /data/local/tmp/dmz-search.db
 *   adb shell run-as se.c64.dmzreader rm cache/search-pilot.db
 *
 * De förväntade svaren är facit i tools/testdata/search_golden.json och gäller
 * inventory 2026-10-06-005 med normalizerVersion 2.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SearchPilotDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val external = File("/data/local/tmp/dmz-search.db")
    private val file = storage(context).cacheDir.resolve("search-pilot.db")

    private fun SearchDb.pages(query: String) = search(query).map { "${it.issueId}:${it.pageNumber}" }

    private fun enabled() = InstrumentationRegistry.getArguments().getString("dmzSearchPilot") == "true"

    private fun millis(block: () -> Unit): Double {
        val start = SystemClock.elapsedRealtimeNanos()
        block()
        return (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
    }

    private fun List<Double>.median() = sorted()[size / 2]

    /** Kopierar filen från en plats utanför appen till privat lagring, som importen i FAS 9 kommer att göra. */
    @Test
    fun a_copyFromExternalFile() {
        assumeTrue(enabled())
        assertTrue("${external.path} saknas eller går inte att läsa", external.canRead())
        file.delete()
        var bytes = 0L
        val elapsed = millis {
            external.inputStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        bytes += read
                    }
                    output.fd.sync()
                }
            }
        }
        Log.i(TAG, "KOPIERING %d byte på %.0f ms (%.1f MB/s), med fsync".format(bytes, elapsed, bytes / 1e6 / (elapsed / 1000)))
        assertEquals(external.length(), file.length())
    }

    @Test
    fun b_fullDatabaseAnswersGoldenQueries() {
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

            val results = ArrayList<String>()
            fun check(name: String, expected: String, actual: List<String>, mustBeFirst: Boolean) {
                val place = actual.indexOf(expected) + 1
                results += "$name → $expected: plats $place av ${actual.size}"
                assertTrue("$name: $expected saknas bland $actual", place > 0)
                if (mustBeFirst) assertEquals(name, 1, place)
            }
            // 1–2. Utspärrad text i 1995 nr 14: textlagret har "h ö g ta la re".
            val speakers = db.search("högtalare")
            check("högtalare", "datormagazin-1995-14:27", speakers.map { "${it.issueId}:${it.pageNumber}" }, true)
            val spaced = speakers.single { it.issueId == "datormagazin-1995-14" && it.pageNumber == 26 }
            Log.i(TAG, "utdrag 1995 nr 14 sida 26: ${spaced.snippet}")
            assertTrue(spaced.snippet, spaced.snippet.contains("[högtalare]"))
            assertEquals(listOf("datormagazin-1995-14:20"), db.pages("turboköpet"))
            results += "turboköpet → datormagazin-1995-14:20: enda träffen"
            // 3–6. Frassökningar, i och utanför piloten.
            check("\"Commodore 128\"", "datormagazin-1986-01:4", db.pages("\"Commodore 128\""), true)
            check("\"Decision in the Desert\"", "datormagazin-1988-12:19", db.pages("\"Decision in the Desert\""), true)
            check("\"Anders Limpar\"", "datormagazin-1989-15:6", db.pages("\"Anders Limpar\""), true)
            check("\"Mikael Roth\"", "highscore-1993-17:6", db.pages("\"Mikael Roth\""), true)
            // 7–9. Ord med å, ä och ö ur nummer utanför piloten, bland dem 1995 nr 15.
            check("starköl", "datormagazin-1988-12:22", db.pages("starköl"), false)
            check("Visionärer", "datormagazin-1995-15:10", db.pages("Visionärer"), false)
            check("terminatorn", "highscore-1993-20:21", db.pages("terminatorn"), true)
            results.forEach { Log.i(TAG, "FACIT $it") }

            assertTrue(db.pages("\"CD-ROM\"").isNotEmpty())

            // å är en egen bokstav: samma antal sidor som på datorn.
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
            assertEquals(
                "92 563123977 \"1493509840.0-563123977-985793977\" " +
                    "9b24e04d37bd13ae5d9f8cc744536a7e15be0f02d7f8fc209efece9f5025c6f9",
                issue,
            )
        }
    }

    /**
     * Svarstider för appens sökfråga (bm25, utdrag, högst 50 träffar).
     * Kall: första frågan direkt efter att databasen öppnats i en ny anslutning.
     * Varm: samma fråga fem gånger till i samma anslutning.
     */
    @Test
    fun c_responseTimes() {
        assumeTrue(enabled())
        val typical = listOf(
            "Fairlight", "högtalare", "joystick", "piratkopiering", "Amiga", "för", "\"Commodore 128\"",
            "\"CD-ROM\"", "\"Street Fighter\"", "hårddisk", "C64", "\"Last Ninja\"", "skärm", "Wolfenstein",
            "\"Amiga 1200\"", "turboköpet",
        )
        val cold = ArrayList<Double>()
        val warm = ArrayList<Double>()
        for (query in typical) {
            var openTime = 0.0
            var coldTime = 0.0
            lateinit var db: SearchDb
            openTime = millis { db = SearchDb.openReadOnly(file.path) }
            db.use {
                coldTime = millis { assertTrue(query, db.search(query).isNotEmpty()) }
                val times = (1..5).map { millis { db.search(query) } }
                cold += openTime + coldTime
                warm += times
                Log.i(TAG, "TID %-18s öppna %.1f + kall %.1f ms; varm median %.1f, max %.1f ms"
                    .format(query, openTime, coldTime, times.median(), times.max()))
            }
        }
        Log.i(TAG, "SVARSTID KALL (öppna + första frågan), ${cold.size} sökord: median %.1f ms, max %.1f ms"
            .format(cold.median(), cold.max()))
        Log.i(TAG, "SVARSTID VARM, ${warm.size} sökningar: median %.1f ms, max %.1f ms".format(warm.median(), warm.max()))
    }

    /** De dyra fallen, och samma frågor utan textutdrag som underlag för FAS 9. */
    @Test
    fun d_expensiveQueries() {
        assumeTrue(enabled())
        val cases = listOf(
            "Amiga (7 289 sidor)" to "Amiga",
            "prefix amig*" to "amig*",
            "fras \"Commodore 128\"" to "\"Commodore 128\"",
            "OCH: för att" to "för att",
            "OCH: och det" to "och det",
            "prefix a*" to "a*",
        )
        for ((name, query) in cases) {
            lateinit var db: SearchDb
            val open = millis { db = SearchDb.openReadOnly(file.path) }
            db.use {
                var hits = 0
                val coldTime = millis { hits = db.search(query).size }
                val full = (1..5).map { millis { db.search(query) } }
                val literal = query.replace("'", "''")
                val total = db.query("SELECT COUNT(*) FROM pages_fts WHERE pages_fts MATCH '$literal'") { it.getLong(0) }.single()
                // Bara rangordning: inget textutdrag och ingen läsning av tabellen pages.
                val rankOnly = (1..5).map {
                    millis {
                        db.query(
                            "SELECT rowid, rank FROM pages_fts WHERE pages_fts MATCH '$literal' ORDER BY rank LIMIT 50",
                        ) { it.getLong(0) }
                    }
                }
                // Andra steget: utdrag för de tio översta träffarna.
                val ids = db.query(
                    "SELECT rowid FROM pages_fts WHERE pages_fts MATCH '$literal' ORDER BY rank LIMIT 10",
                ) { it.getLong(0) }
                val snippets = (1..5).map {
                    millis {
                        db.query(
                            "SELECT snippet(pages_fts, 0, '[', ']', '…', 12) FROM pages_fts " +
                                "WHERE pages_fts MATCH '$literal' AND rowid IN (${ids.joinToString(",")})",
                        ) { it.getText(0) }
                    }
                }
                val counting = (1..3).map {
                    millis { db.query("SELECT COUNT(*) FROM pages_fts WHERE pages_fts MATCH '$literal'") { it.getLong(0) } }
                }
                Log.i(TAG, ("DYR %-22s %5d sidor, %d visade | kall %.0f ms (öppna %.0f) | varm median %.0f, max %.0f ms | " +
                    "bara rangordning %.0f ms | utdrag för 10 %.0f ms | räkna %.0f ms")
                    .format(name, total, hits, coldTime, open, full.median(), full.max(), rankOnly.median(),
                        snippets.median(), counting.median()))
            }
        }
    }

    /** Kör den tyngsta frågan i tio sekunder, så att minnet kan läsas av med dumpsys meminfo utifrån. */
    @Test
    fun e_memoryDuringHeaviestQuery() {
        assumeTrue(enabled())
        val before = Debug.getNativeHeapAllocatedSize()
        val pssBefore = Debug.getPss()
        SearchDb.openReadOnly(file.path).use { db ->
            Log.i(TAG, "MINNE START")
            var peakNative = 0L
            var peakPss = 0L
            val end = SystemClock.elapsedRealtime() + 10_000
            var rounds = 0
            while (SystemClock.elapsedRealtime() < end) {
                db.search("a*")
                db.search("Amiga")
                rounds++
                peakNative = maxOf(peakNative, Debug.getNativeHeapAllocatedSize())
                if (rounds % 3 == 0) peakPss = maxOf(peakPss, Debug.getPss())
            }
            Log.i(TAG, "MINNE SLUT $rounds varv; native heap före %.1f MB, högst %.1f MB; PSS före %.1f MB, högst %.1f MB"
                .format(before / 1e6, peakNative / 1e6, pssBefore / 1024.0, peakPss / 1024.0))
        }
    }

    private companion object {
        const val TAG = "DmzSearchPilot"
    }
}
