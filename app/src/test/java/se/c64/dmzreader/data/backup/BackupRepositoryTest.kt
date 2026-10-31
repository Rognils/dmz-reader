package se.c64.dmzreader.data.backup

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import se.c64.dmzreader.data.db.Bookmark
import se.c64.dmzreader.data.db.DmzDatabase
import se.c64.dmzreader.data.db.IssueLocalState
import se.c64.dmzreader.data.db.IssueUserState
import se.c64.dmzreader.data.db.IssueUserStateDao
import se.c64.dmzreader.data.db.ReadStatusOverride
import se.c64.dmzreader.data.db.ReadingSession
import se.c64.dmzreader.domain.SeenPages
import se.c64.dmzreader.data.repository.ReadingRepository
import se.c64.dmzreader.testing.inMemoryDatabase
import se.c64.dmzreader.testing.issueEntity
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Export och import mot den riktiga databasen (Room 3 med BundledSQLiteDriver, i minnet). */
class BackupRepositoryTest {
    private lateinit var source: DmzDatabase
    private lateinit var target: DmzDatabase
    private val a = "datormagazin-1989-06"
    private val b = "datormagazin-1989-07"

    @Before
    fun setUp() {
        source = inMemoryDatabase()
        target = inMemoryDatabase()
    }

    @After
    fun tearDown() {
        source.close()
        target.close()
    }

    private fun repository(db: DmzDatabase, afterWrite: suspend () -> Unit = {}) = BackupRepository(
        db.issueUserStateDao(), db.bookmarkDao(), db.readingSessionDao(), db.backupDao(),
        appVersion = "0.1.0", now = { 1_791_400_000_000 }, afterWrite = afterWrite,
    )

    private fun state(issueId: String, updatedAt: Long, vararg pages: Int, favorite: Boolean = false, lastPage: Int? = null) =
        IssueUserState(
            issueId = issueId, isFavorite = favorite, lastPage = lastPage, seenPages = SeenPages.of(*pages).encode(),
            maxPageReached = SeenPages.of(*pages).maxPage, updatedAt = updatedAt,
        )

    private fun bookmark(issueId: String, page: Int, title: String = "Sida $page", note: String? = null) =
        Bookmark(issueId = issueId, pageNumber = page, title = title, note = note, createdAt = 10, updatedAt = 20, pdfSize = 25_672_804)

    private fun session(issueId: String, startedAt: Long) = ReadingSession(
        issueId = issueId, startedAt = startedAt, endedAt = startedAt + 60_000, startPage = 1, endPage = 6,
        pagesViewed = 6, durationSeconds = 60,
    )

    private suspend fun fill(db: DmzDatabase) {
        db.issueUserStateDao().insert(
            state(a, 200, 1, 2, 3, 25, favorite = true, lastPage = 25).copy(
                readStatusOverride = ReadStatusOverride.UNREAD, firstOpenedAt = 100, lastOpenedAt = 190, completedAt = 150,
            ),
        )
        db.issueUserStateDao().insert(state(b, 300, favorite = true))
        db.bookmarkDao().insert(bookmark(a, 7, "Ledare", "rad 1\nrad 2"))
        db.bookmarkDao().insert(bookmark(a, 25))
        db.bookmarkDao().insert(bookmark("datormagazin-1999-99", 2, "Saknat nummer"))
        db.readingSessionDao().insert(session(a, 1_000))
        db.readingSessionDao().insert(session(b, 5_000))
    }

    private suspend fun comparable(db: DmzDatabase) = repository(db).currentData().let { data ->
        Triple(
            data.userStates.sortedBy { it.issueId },
            // Radernas id sätts av databasen och ingår inte i backupen.
            data.bookmarks.map { it.copy(id = 0) }.sortedBy { "${it.issueId}/${it.pageNumber}" },
            data.sessions.map { it.copy(id = 0) }.sortedBy { it.startedAt },
        )
    }

    @Test
    fun `export then import into an empty app restores everything`() = runBlocking {
        fill(source)
        // Katalogdata och lokal status ingår inte i backupen.
        source.issueDao().replaceAll(listOf(issueEntity(a)))
        source.issueLocalStateDao().insert(IssueLocalState(issueId = a, observedPageCount = 48))

        val text = repository(source).export()
        assertTrue(text.contains("\"backupFormat\": 2"))
        assertTrue(text.contains("\"exportedAt\": \"2026-10-07T"))
        assertTrue(text.contains("\"appVersion\": \"0.1.0\""))
        assertTrue(!text.contains("observedPageCount") && !text.contains("publicationTitle"))

        val backup = parseBackup(text)
        val preview = repository(target).preview(backup)
        assertEquals(2, preview.favorites)
        assertEquals(3, preview.bookmarks)
        assertEquals(2, preview.sessions)
        assertEquals(1, preview.issuesWithReadingData)
        assertEquals(2, preview.newIssues)
        assertEquals(0, preview.updatedIssues)
        assertEquals(3, preview.newBookmarks)
        assertEquals(2, preview.newSessions)
        // Förhandsvisningen skriver ingenting.
        assertTrue(repository(target).currentData().let { it.userStates.isEmpty() && it.bookmarks.isEmpty() && it.sessions.isEmpty() })

        assertEquals(preview, repository(target).import(backup))
        assertEquals(comparable(source), comparable(target))
        assertEquals(0, target.issueDao().count())
        assertTrue(target.issueLocalStateDao().getAll().isEmpty())
    }

    @Test
    fun `importing the same backup twice gives no duplicates`() = runBlocking {
        fill(source)
        val backup = parseBackup(repository(source).export())
        repository(target).import(backup)
        val afterFirst = comparable(target)

        val second = repository(target).import(backup)
        assertTrue(second.nothingToDo)
        assertEquals(afterFirst, comparable(target))
        assertEquals(3, target.bookmarkDao().getAll().size)
        assertEquals(2, target.readingSessionDao().getAll().size)
        // Även tillbaka i källan: ingenting nytt.
        assertTrue(repository(source).import(backup).nothingToDo)
    }

    @Test
    fun `import merges with what is already on the phone and removes nothing`() = runBlocking {
        fill(source)
        val backup = parseBackup(repository(source).export())
        // Telefonen har redan egen data, delvis för samma nummer.
        target.issueUserStateDao().insert(state(a, 900, 30, 31, favorite = false, lastPage = 31))
        target.issueUserStateDao().insert(state("datormagazin-1990-01", 50, 4, lastPage = 4))
        // Samma bokmärke som i backupen (samma bookmarkId), med en nyare titel på telefonen.
        val shared = source.bookmarkDao().getAll().first { it.pageNumber == 7 }
        target.bookmarkDao().insert(shared.copy(id = 0, title = "Ledare, omdöpt", updatedAt = 5_000))
        target.bookmarkDao().insert(bookmark(a, 31, "Eget"))
        target.readingSessionDao().insert(session(a, 1_000)) // dubblett
        target.readingSessionDao().insert(session(a, 99_000))

        val summary = repository(target).import(backup)
        assertEquals(1, summary.newIssues)
        assertEquals(1, summary.updatedIssues)
        assertEquals(2, summary.newBookmarks)
        assertEquals(1, summary.newSessions)

        val merged = target.issueUserStateDao().get(a)!!
        // Telefonens rad var nyast: dess val gäller. Läshistoriken är hopslagen.
        assertEquals(31, merged.lastPage)
        assertEquals(false, merged.isFavorite)
        assertEquals(null, merged.readStatusOverride)
        assertEquals(SeenPages.of(1, 2, 3, 25, 30, 31), SeenPages.decode(merged.seenPages))
        assertEquals(31, merged.maxPageReached)
        assertEquals(100L, merged.firstOpenedAt)
        assertEquals(150L, merged.completedAt)
        // Det som bara fanns på telefonen är orört.
        assertEquals(state("datormagazin-1990-01", 50, 4, lastPage = 4), target.issueUserStateDao().get("datormagazin-1990-01"))
        // Det delade bokmärket blev inte en dubblett, och den nyare titeln på telefonen gäller.
        assertEquals(listOf("Eget", "Ledare, omdöpt", "Saknat nummer", "Sida 25"), target.bookmarkDao().getAll().map { it.title!! }.sorted())
        assertEquals(0, summary.updatedBookmarks)
        assertEquals(listOf(1_000L, 5_000L, 99_000L), target.readingSessionDao().getAll().map { it.startedAt }.sorted())
    }

    @Test
    fun `an error during import rolls everything back`() = runBlocking {
        fill(source)
        val backup = parseBackup(repository(source).export())
        target.issueUserStateDao().insert(state(a, 900, 30, favorite = false, lastPage = 30))
        target.bookmarkDao().insert(bookmark(a, 31, "Eget"))
        val before = comparable(target)

        // Felet inträffar efter att alla rader har skrivits, inne i transaktionen.
        var writtenInsideTransaction = 0
        val failing = repository(target) {
            writtenInsideTransaction = target.bookmarkDao().getAll().size
            error("disken är full")
        }
        try {
            failing.import(backup)
            fail("importen skulle ha misslyckats")
        } catch (e: IllegalStateException) {
            assertEquals("disken är full", e.message)
        }

        // Raderna var skrivna när felet kom, och är borta efter återställningen.
        assertEquals(4, writtenInsideTransaction)
        assertEquals(before, comparable(target))
        assertEquals(1, target.bookmarkDao().getAll().size)
        assertTrue(target.readingSessionDao().getAll().isEmpty())
        // Samma import går bra när felet är borta.
        repository(target).import(backup)
        assertEquals(4, target.bookmarkDao().getAll().size)
    }

    @Test
    fun `a backup in format 1 is imported and a second import gives no duplicates`() = runBlocking {
        fill(source)
        val format1 = parseBackup(repository(source).export()).let { file ->
            encodeBackup(file.copy(backupFormat = 1, bookmarks = file.bookmarks.map { it.copy(bookmarkId = null, deletedAt = null) }))
        }
        val backup = parseBackup(format1)
        assertEquals(1, backup.backupFormat)

        val first = repository(target).import(backup)
        assertEquals(3, first.newBookmarks)
        val imported = target.bookmarkDao().getAll()
        assertEquals(listOf("Ledare", "Saknat nummer", "Sida 25"), imported.map { it.title!! }.sorted())
        // Varje bokmärke fick ett eget nytt UUID.
        assertEquals(3, imported.map { it.bookmarkId }.toSet().size)
        assertTrue(imported.none { it.bookmarkId in source.bookmarkDao().getAll().map { b -> b.bookmarkId } })
        assertEquals("rad 1\nrad 2", imported.first { it.title == "Ledare" }.note)

        assertTrue(repository(target).import(backup).nothingToDo)
        assertEquals(3, target.bookmarkDao().getAll().size)
        // Nästa export är i format 2 och går runt utan förändring.
        val exported = parseBackup(repository(target).export())
        assertEquals(2, exported.backupFormat)
        assertTrue(repository(target).import(exported).nothingToDo)
    }

    @Test
    fun `a bookmark deleted on the phone stays deleted after importing an older backup`() = runBlocking {
        fill(source)
        val older = parseBackup(repository(source).export())
        repository(target).import(older)
        val ledare = target.bookmarkDao().getAll().first { it.title == "Ledare" }
        assertEquals(1, target.bookmarkDao().markDeleted(ledare.id, 9_000))

        assertTrue(repository(target).import(older).nothingToDo)
        assertEquals(9_000L, target.bookmarkDao().get(ledare.id)!!.deletedAt)
        assertEquals(3, target.bookmarkDao().getAll().size)
        // Borttagningen följer med i nästa export, så att den gäller även på en annan telefon.
        val newer = parseBackup(repository(target).export())
        assertEquals(9_000L, newer.bookmarks.first { it.bookmarkId == ledare.bookmarkId }.deletedAt)
        val summary = repository(source).import(newer)
        assertEquals(1, summary.updatedBookmarks)
        assertEquals(9_000L, source.bookmarkDao().getAll().first { it.bookmarkId == ledare.bookmarkId }.deletedAt)
    }

    @Test
    fun `reading writes wait for backup snapshot and merge instead of overwriting imported fields`() = runBlocking {
        target.issueUserStateDao().insert(state(a, 100, 1, lastPage = 1))
        val snapshotRead = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val actual = target.issueUserStateDao()
        var intercepted = false
        val held = object : IssueUserStateDao by actual {
            override suspend fun getAll(): List<IssueUserState> {
                val snapshot = actual.getAll()
                if (!intercepted) {
                    intercepted = true
                    snapshotRead.complete(Unit)
                    release.await()
                }
                return snapshot
            }
        }
        val backup = BackupFile(
            backupFormat = BACKUP_FORMAT, exportedAt = "2026-10-30T00:00:00Z", appVersion = "1.0.0-rc4",
            issueUserStates = listOf(state(a, 200, 1, 5, favorite = true, lastPage = 5).toBackup()),
            bookmarks = emptyList(), readingSessions = emptyList(),
        )
        val repository = BackupRepository(held, target.bookmarkDao(), target.readingSessionDao(), target.backupDao(), "test")
        val importing = async(Dispatchers.IO) { repository.import(backup) }
        withTimeout(10_000) { snapshotRead.await() }
        val writerStarted = CompletableDeferred<Unit>()
        val reading = async(Dispatchers.IO) {
            writerStarted.complete(Unit)
            ReadingRepository(actual, target.readingSessionDao()) { 300L }.update(a) { current ->
                val seen = SeenPages.decode(current.seenPages) + listOf(9)
                current.copy(lastPage = 9, seenPages = seen.encode(), maxPageReached = seen.maxPage)
            }
        }
        writerStarted.await()
        try {
            assertEquals(null, withTimeoutOrNull(200) { reading.await() })
        } finally {
            release.complete(Unit)
        }
        withTimeout(10_000) { importing.await(); reading.await() }
        val result = actual.get(a)!!
        assertTrue(result.isFavorite)
        assertEquals(9, result.lastPage)
        assertEquals(SeenPages.of(1, 5, 9), SeenPages.decode(result.seenPages))
        assertEquals(300L, result.updatedAt)
    }

    @Test
    fun `backup import waits for a reading read modify write transaction`() = runBlocking {
        target.issueUserStateDao().insert(state(a, 100, 1, lastPage = 1))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reading = async(Dispatchers.IO) {
            ReadingRepository(target.issueUserStateDao(), target.readingSessionDao()) { 150L }.update(a) { current ->
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                val seen = SeenPages.decode(current.seenPages) + listOf(9)
                current.copy(lastPage = 9, seenPages = seen.encode(), maxPageReached = seen.maxPage)
            }
        }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val started = CompletableDeferred<Unit>()
        val importing = async(Dispatchers.IO) {
            started.complete(Unit)
            repository(target).import(BackupFile(
                backupFormat = BACKUP_FORMAT, exportedAt = "2026-10-30T00:00:00Z", appVersion = "test",
                issueUserStates = listOf(state(a, 200, 5, favorite = true, lastPage = 5).toBackup()),
                bookmarks = emptyList(), readingSessions = emptyList(),
            ))
        }
        started.await()
        try {
            assertEquals(null, withTimeoutOrNull(200) { importing.await() })
        } finally {
            release.countDown()
        }
        withTimeout(10_000) { reading.await(); importing.await() }
        val result = target.issueUserStateDao().get(a)!!
        assertEquals(SeenPages.of(1, 5, 9), SeenPages.decode(result.seenPages))
        assertEquals(5, result.lastPage)
        assertTrue(result.isFavorite)
    }

    @Test
    fun `simultaneous backup imports preserve the union of read pages`() = runBlocking {
        val start = CompletableDeferred<Unit>()
        fun backup(page: Int, at: Long) = BackupFile(
            backupFormat = BACKUP_FORMAT, exportedAt = "2026-10-30T00:00:00Z", appVersion = "test",
            issueUserStates = listOf(state(a, at, page, lastPage = page).toBackup()),
            bookmarks = emptyList(), readingSessions = emptyList(),
        )
        val first = async(Dispatchers.IO) { start.await(); repository(target).import(backup(4, 100)) }
        val second = async(Dispatchers.IO) { start.await(); repository(target).import(backup(8, 200)) }
        start.complete(Unit)
        withTimeout(10_000) { first.await(); second.await() }
        val result = target.issueUserStateDao().get(a)!!
        assertEquals(SeenPages.of(4, 8), SeenPages.decode(result.seenPages))
        assertEquals(8, result.lastPage)
    }

    @Test
    fun `the file name has the date`() {
        assertEquals(
            "dmz-reader-backup-2026-10-07.json",
            BackupRepository.fileName(1_791_400_000_000, ZoneId.of("Europe/Stockholm")),
        )
    }
}
