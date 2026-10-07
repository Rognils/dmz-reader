package se.c64.dmzreader.data.inventory

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import se.c64.dmzreader.data.db.Bookmark
import se.c64.dmzreader.data.db.DmzDatabase
import se.c64.dmzreader.data.db.DownloadState
import se.c64.dmzreader.data.db.IssueEntity
import se.c64.dmzreader.data.db.IssueLocalState
import se.c64.dmzreader.data.db.IssueUserState
import se.c64.dmzreader.data.db.ReadingSession
import se.c64.dmzreader.data.settings.InventorySource
import se.c64.dmzreader.testing.FakeActiveInventoryStore
import se.c64.dmzreader.testing.inMemoryDatabase
import se.c64.dmzreader.testing.inventoryJson
import se.c64.dmzreader.testing.issueJson
import java.io.File
import java.nio.file.Files

/**
 * Import av arkivindex mot den riktiga databasen (Room 3 med BundledSQLiteDriver,
 * i minnet) och ett riktigt filsystem: atomiskt byte, återställning vid fel,
 * återgång till det inbyggda, och att användardata aldrig rörs.
 */
class InventoryManagerTest {
    private lateinit var db: DmzDatabase
    private lateinit var directory: File
    private lateinit var store: FakeActiveInventoryStore
    private var failReplace = false
    private var clock = 5_000L

    private val a = "datormagazin-1989-06"
    private val b = "datormagazin-1989-07"
    private val c = "datormagazin-1989-08"
    private val bundled = inventoryJson(datasetVersion = "2026-10-06-004", issues = listOf(issueJson(a), issueJson(b)))

    /** Nytt index: b borttaget, c tillagt, a med ändrad PDF-storlek. */
    private val newer = inventoryJson(
        datasetVersion = "2026-10-07-901",
        issues = listOf(issueJson(a).replace("25672804", "30000000"), issueJson(c)),
    )

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        directory = Files.createTempDirectory("dmz-inventory").toFile().resolve("data")
        store = FakeActiveInventoryStore()
    }

    @After
    fun tearDown() {
        db.close()
        directory.parentFile!!.deleteRecursively()
    }

    private fun manager(bundledText: String = bundled) = InventoryManager(
        directory = directory,
        activeInventory = store,
        currentIssues = { db.issueDao().getAll() },
        replaceIssues = { issues: List<IssueEntity> ->
            if (failReplace) error("databasen är låst")
            db.issueDao().replaceAll(issues)
        },
        readBundled = { bundledText },
        now = { clock },
    )

    private suspend fun ids() = db.issueDao().getAll().map { it.issueId }
    private val active get() = File(directory, "inventory.json")
    private fun leftovers() = directory.listFiles().orEmpty().map { it.name }.filter { it != "inventory.json" }

    private suspend fun insertUserData() {
        db.issueUserStateDao().insert(IssueUserState(issueId = b, isFavorite = true, lastPage = 12, seenPages = "Bw==", updatedAt = 1))
        db.bookmarkDao().insert(Bookmark(issueId = b, pageNumber = 14, title = "Annons", createdAt = 1, updatedAt = 1))
        db.readingSessionDao().insert(ReadingSession(issueId = b, startedAt = 1, endedAt = 9_000, startPage = 1, endPage = 3, pagesViewed = 3, durationSeconds = 9))
        db.issueLocalStateDao().insert(IssueLocalState(issueId = b, observedPageCount = 48, downloadState = DownloadState.DOWNLOADED, downloadedBytes = 25_672_804))
    }

    private suspend fun userData() = listOf(
        db.issueUserStateDao().getAll(), db.bookmarkDao().getAll(), db.readingSessionDao().getAll(), db.issueLocalStateDao().getAll(),
    )

    @Test
    fun `the bundled index is used when nothing is imported`() = runBlocking {
        val result = manager().loadAtStartup()
        assertEquals(InventoryLoadResult.Replaced("2026-10-06-004", 2), result)
        assertEquals(listOf(a, b), ids())
        assertEquals(InventorySource.BUNDLED, store.get()!!.source)
        assertNull(store.get()!!.importedAt)
        assertFalse(manager().hasImportedFile())
        // Nästa start skriver ingenting.
        assertEquals(InventoryLoadResult.AlreadyActive("2026-10-06-004", 2), manager().loadAtStartup())
    }

    @Test
    fun `staging validates and compares without changing anything`() = runBlocking {
        val manager = manager()
        manager.loadAtStartup()
        val staged = manager.stage { newer.byteInputStream() }

        assertEquals("2026-10-07-901", staged.datasetVersion)
        assertEquals("2026-10-06-004", staged.activeDatasetVersion)
        assertEquals(2, staged.issueCount)
        assertEquals(InventoryDiff(added = listOf(c), removed = listOf(b), changed = listOf(a)), staged.diff)
        // Ingenting i appen har ändrats: tabellen, den aktiva filen och uppgiften om version.
        assertEquals(listOf(a, b), ids())
        assertFalse(active.exists())
        assertEquals("2026-10-06-004", store.get()!!.datasetVersion)

        manager.discard(staged)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `the same index again shows no differences`() = runBlocking {
        val manager = manager()
        manager.loadAtStartup()
        assertTrue(manager.stage { bundled.byteInputStream() }.diff.isEmpty)
    }

    @Test
    fun `commit swaps the file and replaces the catalogue but never touches user data`() = runBlocking {
        val manager = manager()
        manager.loadAtStartup()
        insertUserData()
        val before = userData()

        manager.commit(manager.stage { newer.byteInputStream() })

        assertEquals(listOf(a, c), ids())
        assertEquals(30_000_000L, db.issueDao().get(a)!!.ocrPdfSize)
        assertEquals(newer, active.readText())
        assertEquals(emptyList<String>(), leftovers())
        val now = store.get()!!
        assertEquals("2026-10-07-901", now.datasetVersion)
        assertEquals(InventorySource.IMPORTED, now.source)
        assertEquals(5_000L, now.importedAt)
        // Användardata och lokal status för det borttagna numret finns kvar orörda.
        assertEquals(before, userData())
        assertEquals(b, db.issueUserStateDao().getAll().single().issueId)
        assertNull(db.issueDao().get(b))
    }

    @Test
    fun `invalid files are refused and leave everything as it was`() = runBlocking {
        val manager = manager()
        manager.loadAtStartup()
        manager.commit(manager.stage { newer.byteInputStream() })
        val broken = listOf(
            inventoryJson(schemaVersion = 2),
            inventoryJson(issues = listOf(issueJson(a), issueJson(a))),
            inventoryJson(issues = listOf(issueJson(a).replace("\"relatedIssueId\": null", "\"relatedIssueId\": \"datormagazin-1999-99\""))),
            newer.take(newer.length / 2),
            "",
        )
        for (text in broken) {
            try {
                manager.stage { text.byteInputStream() }
                fail("skulle ha avvisats")
            } catch (e: InventoryException) {
                assertTrue(e.message!!.startsWith("Arkivindexet"))
            }
            // Det importerade indexet gäller fortfarande, och inga temporära filer ligger kvar.
            assertEquals(listOf(a, c), ids())
            assertEquals(newer, active.readText())
            assertEquals(emptyList<String>(), leftovers())
            assertEquals("2026-10-07-901", store.get()!!.datasetVersion)
        }
    }

    @Test
    fun `a failure while replacing the catalogue rolls back to the previous file`() = runBlocking {
        val manager = manager()
        manager.loadAtStartup()
        // Ett importerat index är redan aktivt.
        manager.commit(manager.stage { newer.byteInputStream() })
        val third = inventoryJson(datasetVersion = "2026-10-08-902", issues = listOf(issueJson(b)))
        val staged = manager.stage { third.byteInputStream() }

        failReplace = true
        try {
            manager.commit(staged)
            fail("skulle ha misslyckats")
        } catch (e: IllegalStateException) {
            assertEquals("databasen är låst", e.message)
        }

        // Den tidigare filen ligger på plats igen, tabellen och versionen är orörda.
        assertEquals(newer, active.readText())
        assertEquals(emptyList<String>(), leftovers())
        assertEquals(listOf(a, c), ids())
        assertEquals("2026-10-07-901", store.get()!!.datasetVersion)

        // Samma sak när inget importerat index fanns förut: ingen aktiv fil blir kvar.
        failReplace = false
        manager.revertToBundled()
        failReplace = true
        try {
            manager.commit(manager.stage { third.byteInputStream() })
            fail("skulle ha misslyckats")
        } catch (e: IllegalStateException) {
            // väntat
        }
        assertFalse(active.exists())
        assertEquals(emptyList<String>(), leftovers())
        assertEquals(listOf(a, b), ids())
        assertEquals(InventorySource.BUNDLED, store.get()!!.source)
    }

    @Test
    fun `the imported index is used at the next start and survives a new bundled version`() = runBlocking {
        manager().loadAtStartup()
        manager().let { it.commit(it.stage { newer.byteInputStream() }) }

        // Appen startas om. Det importerade gäller, och ingenting skrivs om.
        assertEquals(InventoryLoadResult.AlreadyActive("2026-10-07-901", 2), manager().loadAtStartup())
        // En ny appversion med ett nyare inbyggt index ändrar inte det: det importerade går före (D4).
        val newerBundled = inventoryJson(datasetVersion = "2026-11-01-005", issues = listOf(issueJson(a)))
        assertEquals(InventoryLoadResult.AlreadyActive("2026-10-07-901", 2), manager(newerBundled).loadAtStartup())
        assertEquals(listOf(a, c), ids())
    }

    @Test
    fun `a damaged imported file falls back to the bundled index at start`() = runBlocking {
        manager().loadAtStartup()
        manager().let { it.commit(it.stage { newer.byteInputStream() }) }
        active.writeText(newer.take(100))

        assertEquals(InventoryLoadResult.Replaced("2026-10-06-004", 2), manager().loadAtStartup())
        assertEquals(listOf(a, b), ids())
        assertEquals(InventorySource.BUNDLED, store.get()!!.source)
        assertFalse(active.exists())
    }

    @Test
    fun `an import interrupted between the swap and the database is undone at start`() = runBlocking {
        manager().loadAtStartup()
        manager().let { it.commit(it.stage { newer.byteInputStream() }) }
        // Appen dog efter att filerna bytte plats men innan tabellen ersattes.
        active.renameTo(File(directory, "inventory.json.previous"))
        active.writeText(inventoryJson(datasetVersion = "2026-10-08-902", issues = listOf(issueJson(b))))
        File(directory, "inventory.json.tmp").writeText("skräp")

        assertEquals(InventoryLoadResult.AlreadyActive("2026-10-07-901", 2), manager().loadAtStartup())
        assertEquals(newer, active.readText())
        assertEquals(emptyList<String>(), leftovers())
        assertEquals(listOf(a, c), ids())
    }

    @Test
    fun `going back to the bundled index restores the catalogue and reconnects user data`() = runBlocking {
        val manager = manager()
        manager.loadAtStartup()
        insertUserData()
        val before = userData()
        manager.commit(manager.stage { newer.byteInputStream() })
        assertNull(db.issueDao().get(b))

        assertEquals(InventoryLoadResult.Replaced("2026-10-06-004", 2), manager.revertToBundled())

        assertEquals(listOf(a, b), ids())
        assertFalse(active.exists())
        assertEquals(InventorySource.BUNDLED, store.get()!!.source)
        assertNull(store.get()!!.importedAt)
        // Numret är tillbaka, och dess data hör ihop med det igen via issueId.
        assertEquals(before, userData())
        assertEquals(b, db.issueDao().get(db.issueUserStateDao().getAll().single().issueId)!!.issueId)
        assertEquals(25_672_804L, db.issueDao().get(a)!!.ocrPdfSize)
    }

    @Test
    fun `differences between two catalogues`() {
        fun entity(text: String) = InventoryParser.parse(inventoryJson(issues = listOf(text))).issues.single().toEntity()
        val one = entity(issueJson(a))
        val two = entity(issueJson(b))
        assertTrue(diffInventories(listOf(one, two), listOf(two, one)).isEmpty)
        assertEquals(InventoryDiff(listOf(b), emptyList(), emptyList()), diffInventories(listOf(one), listOf(one, two)))
        assertEquals(InventoryDiff(emptyList(), listOf(a), emptyList()), diffInventories(listOf(one, two), listOf(two)))
        assertEquals(
            InventoryDiff(emptyList(), emptyList(), listOf(a)),
            diffInventories(listOf(one), listOf(one.copy(ocrPdfEtag = "\"ny\""))),
        )
        assertEquals(InventoryDiff(listOf(a, b), emptyList(), emptyList()), diffInventories(emptyList(), listOf(two, one)))
    }
}
