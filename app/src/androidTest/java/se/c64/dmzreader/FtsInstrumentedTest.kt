package se.c64.dmzreader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import se.c64.dmzreader.data.search.FtsSelfTest
import se.c64.dmzreader.data.search.FtsTestDatabase
import se.c64.dmzreader.data.search.SearchDb

/**
 * ADR 0006 på en riktig enhet: ett FTS5-index byggt på datorn kopieras till
 * filesDir/data/, öppnas skrivskyddat med BundledSQLiteDriver och söks.
 */
@RunWith(AndroidJUnit4::class)
class FtsInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun pregeneratedFts5DatabaseIsSearchedCorrectly() {
        val file = FtsTestDatabase.install(context)
        assertNotNull("fts_test.db saknas i debug-assets", file)
        assertEquals(context.filesDir.resolve("data"), file!!.parentFile)

        val result = FtsSelfTest.run(file.path)

        assertNull(result.error)
        val failed = result.cases.filterNot { it.passed }.map { "${it.name}: ${it.detail} ${it.actual}" }
        assertEquals(emptyList<String>(), failed)
        assertEquals(11, result.cases.size)
        assertTrue(result.readOnlyEnforced)
        assertTrue(result.passed)
    }

    @Test
    fun matchBm25AndSnippetWithSwedishLetters() {
        val file = FtsTestDatabase.install(context)!!
        SearchDb.openReadOnly(file.path).use { db ->
            val fairlight = db.search("Fairlight")
            assertEquals(
                listOf("datormagazin-1988-07:14", "datormagazin-1988-07:15"),
                fairlight.map { "${it.issueId}:${it.pageNumber}" },
            )
            assertTrue(fairlight.first().snippet.contains("[Fairlight]"))
            assertTrue(fairlight.first().score < 0)

            val speakers = db.search("högtalare")
            assertEquals(setOf(26, 27), speakers.map { it.pageNumber }.toSet())
            assertTrue(speakers.first().snippet.contains("[högtalare]"))

            assertEquals(9, db.search("Amiga").first().pageNumber)
            assertTrue(Regex("""3\.\d+\.\d+""").matches(db.sqliteVersion()))
        }
    }
}
