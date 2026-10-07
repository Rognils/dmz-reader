package se.c64.dmzreader.data.db

import androidx.room3.useReaderConnection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.c64.dmzreader.data.inventory.InventoryImporter
import se.c64.dmzreader.testing.FakeActiveInventoryStore
import se.c64.dmzreader.testing.TestFiles
import se.c64.dmzreader.testing.inMemoryDatabase

class DatabaseSchemaTest {
    private lateinit var db: DmzDatabase

    @Before
    fun setUp() {
        db = inMemoryDatabase()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun <T> query(sql: String, row: (androidx.sqlite.SQLiteStatement) -> T): List<T> =
        db.useReaderConnection { connection ->
            connection.usePrepared(sql) { statement ->
                val rows = ArrayList<T>()
                while (statement.step()) rows += row(statement)
                rows
            }
        }

    @Test
    fun `the database has exactly the five planned tables`() = runTest {
        val tables = query(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name NOT LIKE 'sqlite_%' AND name != 'room_master_table' ORDER BY name"
        ) { it.getText(0) }
        assertEquals(listOf("Bookmark", "Issue", "IssueLocalState", "IssueUserState", "ReadingSession"), tables)
    }

    @Test
    fun `no table has a foreign key`() = runTest {
        // Regel: inga främmande nycklar (och därmed ingen kaskadradering) till Issue.
        for (table in listOf("Issue", "IssueLocalState", "IssueUserState", "Bookmark", "ReadingSession")) {
            val foreignKeys = query("PRAGMA foreign_key_list('$table')") { it.getText(2) }
            assertEquals("$table har en främmande nyckel", emptyList<String>(), foreignKeys)
        }
        val definitions = query("SELECT sql FROM sqlite_master WHERE type = 'table'") { it.getText(0) }
        assertTrue(definitions.none { it.contains("REFERENCES", ignoreCase = true) })
    }

    @Test
    fun `issue table has every inventory field except sourceNames`() = runTest {
        val columns = query("PRAGMA table_info('Issue')") { it.getText(1) }.toSet()
        assertEquals(
            setOf(
                "issueId", "publicationId", "publicationTitle", "year", "issueLabel", "sortKey",
                "sourceKey", "relatedIssueId", "coverUrl", "coverFlags", "coverAsset", "ocrPdfUrl", "ocrPdfSize",
                "ocrPdfEtag", "ocrPdfLastModified", "largePdfUrl", "largePdfSize", "hqSource",
                "scanPagePattern", "scanPageCount", "pageCount", "spreadOffset", "flags",
            ),
            columns,
        )
    }

    @Test
    fun `local state has no generated cover path`() = runTest {
        val columns = query("PRAGMA table_info('IssueLocalState')") { it.getText(1) }
        assertEquals(
            listOf(
                "issueId", "observedPageCount", "observedPdfSize", "observedPdfEtag",
                "observedPdfLastModified", "downloadState", "downloadedPath",
                "downloadedBytes", "downloadedAt", "downloadQueuedAt", "detectedSpreadOffset",
            ),
            columns,
        )
    }

    @Test
    fun `the bundled sqlite driver has fts5`() = runTest {
        val version = db.sqliteVersion()
        assertTrue("oväntad SQLite-version: $version", Regex("""3\.\d+\.\d+""").matches(version))
        val options = query("PRAGMA compile_options") { it.getText(0) }
        assertTrue("FTS5 saknas i $options", options.contains("ENABLE_FTS5"))
    }

    @Test
    fun `user state round trips including the manual read status`() = runTest {
        // Att uppdatera en befintlig rad med @Upsert prövas i det instrumenterade
        // testet: Room känner igen konflikten på ett Android-undantag som inte
        // finns på datorns JVM.
        val dao = db.issueUserStateDao()
        dao.upsert(IssueUserState(issueId = "datormagazin-1989-06", updatedAt = 1))
        dao.upsert(
            IssueUserState(
                issueId = "datormagazin-1989-07", maxPageReached = 40,
                readStatusOverride = ReadStatusOverride.READ, updatedAt = 2,
            ),
        )
        dao.upsert(
            IssueUserState(
                issueId = "datormagazin-1989-08", readStatusOverride = ReadStatusOverride.UNREAD, updatedAt = 3,
            ),
        )

        assertNull(dao.get("datormagazin-1989-06")!!.readStatusOverride)
        val read = dao.get("datormagazin-1989-07")!!
        assertEquals(ReadStatusOverride.READ, read.readStatusOverride)
        assertEquals(40, read.maxPageReached)
        assertEquals(ReadStatusOverride.UNREAD, dao.get("datormagazin-1989-08")!!.readStatusOverride)
        assertEquals(3, dao.getAll().size)
    }

    @Test
    fun `issue counts per publication and year match the inventory`() = runTest {
        InventoryImporter(db.issueDao(), FakeActiveInventoryStore()).load(TestFiles.inventory.readText())

        val publications = db.issueDao().observePublicationCounts().first()
        assertEquals(
            listOf("Datormagazin" to 171, "HighScore" to 14),
            publications.map { it.publicationTitle to it.issueCount },
        )

        val years = db.issueDao().observeYearCounts().first()
        assertEquals(12, years.size)
        assertEquals(
            listOf(4, 10, 16, 19, 20, 22, 22, 22, 22, 14),
            years.filter { it.publicationId == "datormagazin" }.map { it.issueCount },
        )
        assertEquals(
            listOf(1993 to 6, 1994 to 8),
            years.filter { it.publicationId == "highscore" }.map { it.year to it.issueCount },
        )
    }
}
