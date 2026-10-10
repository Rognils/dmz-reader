package se.c64.dmzreader.data.search

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.c64.dmzreader.testing.TestFiles
import java.io.File

/**
 * FTS5-frågorna mot den förgenererade testdatabasen, med samma drivrutin
 * (BundledSQLiteDriver) och samma kod som appen använder.
 */
class SearchDbTest {
    private lateinit var db: SearchDb

    @Before
    fun setUp() {
        db = SearchDb.openReadOnly(TestFiles.ftsTestDb.path)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun hits(query: String, issueId: String? = null) =
        db.search(query, issueId).map { "${it.issueId}:${it.pageNumber}" }

    @Test
    fun `self test passes with the bundled driver`() {
        val result = FtsSelfTest.run(TestFiles.ftsTestDb.path)

        assertNull(result.error)
        result.cases.filterNot { it.passed }.forEach { println("${it.name}: ${it.detail} ${it.actual}") }
        assertTrue(result.passed)
        assertEquals(11, result.cases.size)
        assertTrue(result.readOnlyEnforced)
        assertEquals("unicode61", result.tokenizer)
        assertEquals("11 av 11 sökningar gav rätt träffar", result.summary)
    }

    @Test
    fun `meta table follows the planned format`() {
        val meta = db.meta()
        assertEquals(
            setOf("schemaVersion", "datasetVersion", "generatedAt", "tokenizer", "issueCount", "pageCount"),
            meta.keys,
        )
        assertEquals("1", meta["schemaVersion"])
        assertEquals("8", meta["pageCount"])
    }

    @Test
    fun `match returns the right issue and page`() {
        assertEquals(listOf("datormagazin-1988-07:14", "datormagazin-1988-07:15"), hits("Fairlight"))
        assertEquals(emptyList<String>(), hits("Atari"))
    }

    @Test
    fun `bm25 ranks the page with most occurrences first`() {
        val result = db.search("Amiga")
        assertEquals("datormagazin-1995-14", result.first().issueId)
        assertEquals(9, result.first().pageNumber)
        assertEquals(result.map { it.score }.sorted(), result.map { it.score })
        assertTrue(result.all { it.score < 0 })
    }

    @Test
    fun `snippet marks the match`() {
        val snippet = db.search("Fairlight").first().snippet
        assertTrue(snippet, snippet.contains("Demogruppen [Fairlight] släppte"))
    }

    @Test
    fun `swedish letters work in queries and snippets`() {
        assertEquals(listOf("datormagazin-1995-14:26", "datormagazin-1995-14:27"), hits("högtalare").sorted())
        assertEquals(listOf("highscore-1993-17:3"), hits("Äventyrsspel"))
        assertEquals(listOf("highscore-1993-17:3"), hits("äventyrsspel"))
        assertEquals(listOf("datormagazin-1988-07:14"), hits("\"största träff\""))
        assertTrue(db.search("förvånansvärt").single().snippet.contains("låter [förvånansvärt] bra"))
    }

    @Test
    fun `search uses normalised text not raw text`() {
        // Sidan 26 har utspärrad rubrik i rawText, och sidan 14 ett ord avstavat med mjukt bindestreck.
        assertTrue(hits("högtalare").contains("datormagazin-1995-14:26"))
        assertTrue(db.search("högtalare").first { it.pageNumber == 26 }.snippet.startsWith("Bra [högtalare] gör"))
        assertEquals(listOf("datormagazin-1988-07:14"), hits("uppmärksamhet"))
    }

    @Test
    fun `search can be limited to one issue`() {
        assertEquals(listOf("highscore-1993-17:3"), hits("Amiga", issueId = "highscore-1993-17"))
        assertEquals(emptyList<String>(), hits("Fairlight", issueId = "highscore-1993-17"))
    }

    @Test
    fun `plain unicode61 folds diacritics`() {
        // Underlag för ADR 0010: med standardinställningen blir "får" och "far" samma ord.
        assertEquals(hits("får").sorted(), hits("far").sorted())
        assertTrue(hits("far").contains("datormagazin-1995-14:43"))
    }

    @Test
    fun `the database is opened read only`() {
        assertThrows(Exception::class.java) { db.execute("CREATE TABLE write_probe (x)") }
        assertThrows(Exception::class.java) { db.execute("DELETE FROM pages") }
        assertEquals("8", db.meta()["pageCount"])
    }

    @Test
    fun `a missing or broken file fails the self test instead of crashing`() {
        val missing = FtsSelfTest.run(File(TestFiles.ftsTestDb.parentFile, "saknas.db").path)
        assertFalse(missing.passed)

        val broken = File.createTempFile("trasig", ".db").apply { writeText("inte en databas") }
        try {
            val result = FtsSelfTest.run(broken.path)
            assertFalse(result.passed)
            assertTrue(result.error != null)
        } finally {
            broken.delete()
        }
    }
}
