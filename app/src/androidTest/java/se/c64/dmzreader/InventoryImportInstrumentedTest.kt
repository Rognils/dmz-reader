package se.c64.dmzreader

import androidx.room3.Room
import androidx.room3.useReaderConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import se.c64.dmzreader.data.db.Bookmark
import se.c64.dmzreader.data.db.DmzDatabase
import se.c64.dmzreader.data.db.IssueUserState
import se.c64.dmzreader.data.db.ReadStatusOverride
import se.c64.dmzreader.data.db.ReadingSession
import se.c64.dmzreader.data.db.sqliteVersion
import se.c64.dmzreader.data.inventory.InventoryImporter
import se.c64.dmzreader.data.inventory.InventoryLoadResult
import se.c64.dmzreader.data.inventory.InventoryParser
import se.c64.dmzreader.data.settings.ActiveInventory
import se.c64.dmzreader.data.settings.ActiveInventoryStore

/**
 * Samma regler som JVM-testerna, men på telefonen: riktig Room 3-databas med
 * BundledSQLiteDriver och det inventory som följer med appen.
 */
@RunWith(AndroidJUnit4::class)
class InventoryImportInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: DmzDatabase
    private val store = object : ActiveInventoryStore {
        private val state = MutableStateFlow<ActiveInventory?>(null)
        override fun observe(): Flow<ActiveInventory?> = state
        override suspend fun get(): ActiveInventory? = state.value
        override suspend fun set(value: ActiveInventory) {
            state.value = value
        }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder<DmzDatabase>(context)
            .setDriver(BundledSQLiteDriver())
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun bundledInventory(): String =
        context.assets.open("archive_inventory.json").bufferedReader().use { it.readText() }

    @Test
    fun bundledInventoryLoadsIntoRoom() = runBlocking {
        val result = InventoryImporter(db.issueDao(), store).load(bundledInventory())

        assertTrue(result is InventoryLoadResult.Replaced)
        assertEquals(185, db.issueDao().count())
        assertEquals(1, store.get()!!.schemaVersion)
        assertEquals(listOf("crop_right_half", "rotate_cw"), db.issueDao().get("datormagazin-1986-01")!!.coverFlags)
    }

    @Test
    fun newDatasetVersionLeavesUserDataUntouched() = runBlocking {
        val importer = InventoryImporter(db.issueDao(), store)
        val original = bundledInventory()
        importer.load(original)

        val kept = IssueUserState(
            issueId = "datormagazin-1989-06", isFavorite = true, lastPage = 12, maxPageReached = 30,
            readStatusOverride = ReadStatusOverride.UNREAD, updatedAt = 2_000,
        )
        val orphan = IssueUserState(issueId = "datormagazin-1986-01", lastPage = 3, updatedAt = 1_000)
        db.issueUserStateDao().upsert(kept)
        db.issueUserStateDao().upsert(orphan)
        db.bookmarkDao().insert(
            Bookmark(issueId = "datormagazin-1986-01", pageNumber = 5, title = "Wargame", createdAt = 1, updatedAt = 1),
        )
        db.readingSessionDao().insert(
            ReadingSession(
                issueId = "datormagazin-1986-01", startedAt = 1, endedAt = 61,
                startPage = 1, endPage = 5, pagesViewed = 5, durationSeconds = 60,
            ),
        )

        // Ett nyare inventory där 1986 nr 1 har försvunnit.
        val parsed = InventoryParser.parse(original)
        val removedIssue = original.indexOf("\"issueId\": \"datormagazin-1986-01\"")
        assertTrue(removedIssue > 0)
        val newer = original
            .replace("\"datasetVersion\": \"${parsed.datasetVersion}\"", "\"datasetVersion\": \"2027-01-01-001\"")
            .replace("\"issueId\": \"datormagazin-1986-01\"", "\"issueId\": \"datormagazin-1985-01\"")
            .replace("\"sortKey\": \"1986-01\"", "\"sortKey\": \"1985-01\"")

        val result = importer.load(newer)

        assertEquals(InventoryLoadResult.Replaced("2027-01-01-001", 185), result)
        assertNull(db.issueDao().get("datormagazin-1986-01"))
        assertNotNull(db.issueDao().get("datormagazin-1985-01"))
        assertEquals(listOf(orphan, kept), db.issueUserStateDao().getAll())
        assertEquals(1, db.bookmarkDao().getAll().size)
        assertEquals(1, db.readingSessionDao().getAll().size)
    }

    @Test
    fun unknownSchemaVersionIsRejected() = runBlocking {
        val importer = InventoryImporter(db.issueDao(), store)
        importer.load(bundledInventory())
        val future = bundledInventory().replaceFirst("\"schemaVersion\": 1", "\"schemaVersion\": 2")

        val result = importer.load(future)

        assertTrue(result is InventoryLoadResult.Rejected)
        assertEquals(185, db.issueDao().count())
    }

    /** rowid för en rad i IssueUserState. Ändras om raden raderas och skapas på nytt. */
    private suspend fun userStateRowIds(): Map<String, Long> = db.useReaderConnection { connection ->
        connection.usePrepared("SELECT issueId, rowid FROM IssueUserState ORDER BY issueId") { statement ->
            val rows = LinkedHashMap<String, Long>()
            while (statement.step()) rows[statement.getText(0)] = statement.getLong(1)
            rows
        }
    }

    @Test
    fun upsertUpdatesAnExistingRow() = runBlocking {
        val dao = db.issueUserStateDao()
        val original = IssueUserState(
            issueId = "datormagazin-1989-06", isFavorite = true, lastPage = 12, maxPageReached = 40,
            firstOpenedAt = 1_000, lastOpenedAt = 2_000, updatedAt = 2_000,
        )
        val other = IssueUserState(issueId = "datormagazin-1989-07", lastPage = 3, updatedAt = 1_500)
        dao.upsert(original)
        dao.upsert(other)
        val rowIdsBefore = userStateRowIds()
        assertEquals(2, rowIdsBefore.size)

        // Samma primärnyckel, ändrade värden: ska bli en UPDATE av den befintliga raden.
        val changed = original.copy(
            lastPage = 20, readStatusOverride = ReadStatusOverride.READ, completedAt = 3_000, updatedAt = 3_000,
        )
        dao.upsert(changed)

        // Ingen ny rad, och raden är densamma (samma rowid), inte raderad och återskapad.
        assertEquals(rowIdsBefore, userStateRowIds())
        val stored = dao.get("datormagazin-1989-06")!!
        assertEquals(changed, stored)
        // Det som ändrades har ändrats ...
        assertEquals(20, stored.lastPage)
        assertEquals(ReadStatusOverride.READ, stored.readStatusOverride)
        assertEquals(3_000L, stored.completedAt)
        // ... och det som inte ändrades finns kvar.
        assertTrue(stored.isFavorite)
        assertEquals(40, stored.maxPageReached)
        assertEquals(1_000L, stored.firstOpenedAt)
        // Raden för det andra numret är orörd.
        assertEquals(other, dao.get("datormagazin-1989-07"))
        assertEquals(listOf(changed, other), dao.getAll())

        // En upsert med identiskt innehåll ändrar ingenting.
        dao.upsert(changed)
        assertEquals(rowIdsBefore, userStateRowIds())
        assertEquals(listOf(changed, other), dao.getAll())
    }

    @Test
    fun bundledSqliteVersionIsReported() = runBlocking {
        assertTrue(Regex("""3\.\d+\.\d+""").matches(db.sqliteVersion()))
    }
}
