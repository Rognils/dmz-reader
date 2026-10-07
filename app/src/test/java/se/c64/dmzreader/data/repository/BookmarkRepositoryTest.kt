package se.c64.dmzreader.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.c64.dmzreader.data.db.DmzDatabase
import se.c64.dmzreader.data.db.IssueLocalState
import se.c64.dmzreader.testing.inMemoryDatabase
import se.c64.dmzreader.testing.issueEntity

/** Bokmärken mot den riktiga databasen (Room 3 med BundledSQLiteDriver, i minnet). */
class BookmarkRepositoryTest {
    private lateinit var db: DmzDatabase
    private lateinit var repository: BookmarkRepository
    private var clock = 1_000L
    private val issueId = "datormagazin-1989-06"

    @Before
    fun setUp() = runBlocking {
        db = inMemoryDatabase()
        db.issueDao().replaceAll(
            listOf(issueEntity(issueId).copy(ocrPdfSize = 25_672_804, ocrPdfEtag = "\"katalog\"", ocrPdfLastModified = "Sat, 09 Sep 2017")),
        )
        repository = BookmarkRepository(db.bookmarkDao(), db.issueDao(), db.issueLocalStateDao()) { clock }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun all() = repository.observeAll().first()

    @Test
    fun `one tap creates a bookmark with the default title`() = runBlocking {
        val result = repository.toggle(issueId, 7)
        val added = (result as BookmarkToggle.Added).bookmark
        assertTrue(added.id > 0)
        assertEquals("Sida 7", added.title)
        assertNull(added.note)
        assertEquals(7, added.pageNumber)
        assertEquals(1_000L, added.createdAt)
        assertEquals(listOf(added), all())
        // PDF-versionen från katalogen sparas för C7.
        assertEquals(25_672_804L, added.pdfSize)
        assertEquals("\"katalog\"", added.pdfEtag)
        assertEquals("Sat, 09 Sep 2017", added.pdfLastModified)
    }

    @Test
    fun `the version of the downloaded file is saved when there is one`() = runBlocking {
        db.issueLocalStateDao().insert(IssueLocalState(issueId = issueId, observedPdfSize = 777, observedPdfEtag = "\"lokal\""))
        val added = (repository.toggle(issueId, 7) as BookmarkToggle.Added).bookmark
        assertEquals(777L, added.pdfSize)
        assertEquals("\"lokal\"", added.pdfEtag)
        // En bokmärkt sida i ett nummer som saknas i katalogen går också att spara.
        val ghost = (repository.toggle("datormagazin-1999-99", 2) as BookmarkToggle.Added).bookmark
        assertNull(ghost.pdfSize)
    }

    @Test
    fun `a second tap removes the bookmark and undo puts it back unchanged`() = runBlocking {
        val added = (repository.toggle(issueId, 7) as BookmarkToggle.Added).bookmark
        clock = 2_000
        val edited = repository.edit(added.id, "Ledare", "läs igen")!!
        repository.toggle(issueId, 9)

        val removed = (repository.toggle(issueId, 7) as BookmarkToggle.Removed).bookmarks
        assertEquals(listOf(edited), removed)
        assertEquals(listOf(9), all().map { it.pageNumber })

        // Ångra: samma id, titel, anteckning och tider som förut.
        repository.restore(removed)
        assertEquals(edited, repository.get(added.id))
        assertEquals(2, all().size)
        // Att ångra två gånger ger ingen dubblett.
        repository.restore(removed)
        assertEquals(2, all().size)
    }

    @Test
    fun `toggle only looks at the page of that issue`() = runBlocking {
        repository.toggle(issueId, 7)
        assertTrue(repository.toggle("datormagazin-1989-07", 7) is BookmarkToggle.Added)
        assertTrue(repository.toggle(issueId, 8) is BookmarkToggle.Added)
        assertEquals(3, all().size)
    }

    @Test
    fun `editing changes title and note and nothing else`() = runBlocking {
        val added = (repository.toggle(issueId, 7) as BookmarkToggle.Added).bookmark
        clock = 5_000
        val edited = repository.edit(added.id, "  Ledare ", " rad 1\nrad 2 ")!!
        assertEquals("Ledare", edited.title)
        assertEquals("rad 1\nrad 2", edited.note)
        assertEquals(5_000L, edited.updatedAt)
        assertEquals(added.createdAt, edited.createdAt)
        assertEquals(added.pdfSize, edited.pdfSize)
        assertEquals(edited, repository.get(added.id))

        // Tom titel blir standardtiteln, tom anteckning tas bort.
        clock = 6_000
        val cleared = repository.edit(added.id, "", "  ")!!
        assertEquals("Sida 7", cleared.title)
        assertNull(cleared.note)
        // Ingen ändring: ingen ny tidsstämpel.
        clock = 7_000
        assertEquals(6_000L, repository.edit(added.id, "Sida 7", "")!!.updatedAt)
        assertNull(repository.edit(9_999, "x", "y"))
    }

    @Test
    fun `deleting returns the bookmark so that it can be restored`() = runBlocking {
        val added = (repository.toggle(issueId, 7) as BookmarkToggle.Added).bookmark
        assertEquals(added, repository.delete(added.id))
        assertTrue(all().isEmpty())
        assertNull(repository.delete(added.id))
        repository.restore(listOf(added))
        assertEquals(listOf(added), all())
    }
}
