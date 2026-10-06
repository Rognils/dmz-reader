package se.c64.dmzreader.data.inventory

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.c64.dmzreader.data.db.Bookmark
import se.c64.dmzreader.data.db.DmzDatabase
import se.c64.dmzreader.data.db.DownloadState
import se.c64.dmzreader.data.db.IssueLocalState
import se.c64.dmzreader.data.db.IssueUserState
import se.c64.dmzreader.data.db.ReadStatusOverride
import se.c64.dmzreader.data.db.ReadingSession
import se.c64.dmzreader.data.settings.ActiveInventory
import se.c64.dmzreader.testing.FakeActiveInventoryStore
import se.c64.dmzreader.testing.TestFiles
import se.c64.dmzreader.testing.inMemoryDatabase
import se.c64.dmzreader.testing.inventoryJson
import se.c64.dmzreader.testing.issueJson

/**
 * Kör mot en riktig Room 3-databas med BundledSQLiteDriver, i minnet.
 * Projektets viktigaste regel prövas här: katalogdata får aldrig förstöra
 * användardata.
 */
class InventoryImporterTest {
    private lateinit var db: DmzDatabase
    private lateinit var store: FakeActiveInventoryStore
    private lateinit var importer: InventoryImporter

    private val userState = IssueUserState(
        issueId = "datormagazin-1989-06",
        isFavorite = true,
        lastPage = 12,
        maxPageReached = 30,
        firstOpenedAt = 1_000,
        lastOpenedAt = 2_000,
        readStatusOverride = ReadStatusOverride.UNREAD,
        updatedAt = 2_000,
    )
    private val bookmark = Bookmark(
        issueId = "datormagazin-1989-06", pageNumber = 14, title = "Annons", note = "Fairlight",
        createdAt = 1_500, updatedAt = 1_500,
    )
    private val session = ReadingSession(
        issueId = "datormagazin-1989-06", startedAt = 1_000, endedAt = 1_600,
        startPage = 1, endPage = 30, pagesViewed = 30, durationSeconds = 600,
    )
    private val localState = IssueLocalState(
        issueId = "datormagazin-1989-06", observedPageCount = 48, observedPdfSize = 25672804,
        downloadState = DownloadState.DOWNLOADED, downloadedPath = "/data/issues/198906.pdf",
    )

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        store = FakeActiveInventoryStore()
        importer = InventoryImporter(db.issueDao(), store)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insertUserData() {
        db.issueUserStateDao().upsert(userState)
        db.bookmarkDao().insert(bookmark)
        db.readingSessionDao().insert(session)
        db.issueLocalStateDao().upsert(localState)
    }

    private suspend fun assertUserDataUntouched() {
        assertEquals(listOf(userState), db.issueUserStateDao().getAll())
        assertEquals(listOf(bookmark.copy(id = 1)), db.bookmarkDao().getAll())
        assertEquals(listOf(session.copy(id = 1)), db.readingSessionDao().getAll())
        assertEquals(listOf(localState), db.issueLocalStateDao().getAll())
    }

    @Test
    fun `first load fills the catalogue and records the version`() = runTest {
        val result = importer.load(TestFiles.inventory.readText())

        assertTrue(result is InventoryLoadResult.Replaced)
        assertEquals(185, db.issueDao().count())
        assertEquals(1, store.get()!!.schemaVersion)
        assertEquals((result as InventoryLoadResult.Replaced).datasetVersion, store.get()!!.datasetVersion)

        val issue = db.issueDao().get("datormagazin-1986-01")!!
        assertEquals(listOf("rotated"), issue.coverFlags)
        assertEquals(28, issue.pageCount)
        assertEquals(listOf("double_issue"), db.issueDao().get("datormagazin-1988-16-17")!!.flags)
    }

    @Test
    fun `a new datasetVersion replaces the catalogue but leaves user data untouched`() = runTest {
        importer.load(
            inventoryJson(
                datasetVersion = "2026-10-06-001",
                issues = listOf(issueJson("datormagazin-1989-06"), issueJson("datormagazin-1989-07")),
            ),
        )
        insertUserData()

        val result = importer.load(
            inventoryJson(
                datasetVersion = "2026-11-01-001",
                issues = listOf(issueJson("datormagazin-1989-06"), issueJson("datormagazin-1989-08")),
            ),
        )

        assertEquals(InventoryLoadResult.Replaced("2026-11-01-001", 2), result)
        assertEquals(
            listOf("datormagazin-1989-06", "datormagazin-1989-08"),
            db.issueDao().getAll().map { it.issueId },
        )
        assertEquals(ActiveInventory("2026-11-01-001", 1), store.get())
        assertUserDataUntouched()
    }

    @Test
    fun `user data survives when its issue disappears from the inventory`() = runTest {
        importer.load(inventoryJson(datasetVersion = "2026-10-06-001"))
        insertUserData()

        importer.load(
            inventoryJson(datasetVersion = "2026-11-01-001", issues = listOf(issueJson("datormagazin-1990-01"))),
        )

        assertNull(db.issueDao().get("datormagazin-1989-06"))
        assertNotNull(db.issueUserStateDao().get("datormagazin-1989-06"))
        assertUserDataUntouched()

        // Kommer numret tillbaka hör datan ihop med det igen, via issueId.
        importer.load(inventoryJson(datasetVersion = "2026-12-01-001"))
        assertNotNull(db.issueDao().get("datormagazin-1989-06"))
        assertUserDataUntouched()
    }

    @Test
    fun `the same datasetVersion is not loaded again`() = runTest {
        importer.load(inventoryJson(datasetVersion = "2026-10-06-001"))
        assertEquals(1, store.writes)

        // Samma version men annat innehåll: katalogen ska inte röras.
        val result = importer.load(
            inventoryJson(datasetVersion = "2026-10-06-001", issues = listOf(issueJson("datormagazin-1990-01"))),
        )

        assertEquals(InventoryLoadResult.AlreadyActive("2026-10-06-001", 1), result)
        assertEquals(listOf("datormagazin-1989-06"), db.issueDao().getAll().map { it.issueId })
        assertEquals(1, store.writes)
    }

    @Test
    fun `an empty catalogue is loaded even if the version matches`() = runTest {
        store.set(ActiveInventory("2026-10-06-001", 1))
        val result = importer.load(inventoryJson(datasetVersion = "2026-10-06-001"))
        assertTrue(result is InventoryLoadResult.Replaced)
        assertEquals(1, db.issueDao().count())
    }

    @Test
    fun `an unknown schemaVersion is rejected and nothing changes`() = runTest {
        importer.load(inventoryJson(datasetVersion = "2026-10-06-001"))
        insertUserData()

        val result = importer.load(
            inventoryJson(
                datasetVersion = "2027-01-01-001",
                schemaVersion = 2,
                issues = listOf(issueJson("datormagazin-1990-01")),
            ),
        )

        assertTrue(result is InventoryLoadResult.Rejected)
        assertTrue((result as InventoryLoadResult.Rejected).message.contains("schemaVersion 2"))
        assertEquals(listOf("datormagazin-1989-06"), db.issueDao().getAll().map { it.issueId })
        assertEquals(ActiveInventory("2026-10-06-001", 1), store.get())
        assertUserDataUntouched()
    }

    @Test
    fun `a broken file is rejected and the previous catalogue remains`() = runTest {
        importer.load(inventoryJson(datasetVersion = "2026-10-06-001"))

        assertTrue(importer.load("{ trasig") is InventoryLoadResult.Rejected)
        assertTrue(
            importer.load(inventoryJson(datasetVersion = "x", issues = List(2) { issueJson("datormagazin-1990-01") }))
                is InventoryLoadResult.Rejected,
        )

        assertEquals(listOf("datormagazin-1989-06"), db.issueDao().getAll().map { it.issueId })
        assertEquals("2026-10-06-001", store.get()!!.datasetVersion)
    }

    @Test
    fun `issues without cover or pdf are stored with null urls`() = runTest {
        importer.load(
            inventoryJson(
                issues = listOf(
                    issueJson(
                        "datormagazin-1989-06", coverUrl = null, ocrPdfUrl = null,
                        flags = listOf("missing_cover", "missing_ocr_pdf"),
                    ),
                ),
            ),
        )
        val issue = db.issueDao().get("datormagazin-1989-06")!!
        assertNull(issue.coverUrl)
        assertNull(issue.ocrPdfUrl)
        assertEquals(listOf("missing_cover", "missing_ocr_pdf"), issue.flags)
    }
}
