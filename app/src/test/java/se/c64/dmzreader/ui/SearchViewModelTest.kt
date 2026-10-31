package se.c64.dmzreader.ui

import androidx.lifecycle.viewModelScope
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import se.c64.dmzreader.data.db.DmzDatabase
import se.c64.dmzreader.data.repository.BookmarkRepository
import se.c64.dmzreader.data.repository.LibraryRepository
import se.c64.dmzreader.data.search.SearchIndexManager
import se.c64.dmzreader.testing.FakeActiveInventoryStore
import se.c64.dmzreader.testing.TestFiles
import se.c64.dmzreader.testing.inMemoryDatabase
import se.c64.dmzreader.testing.issueEntity
import se.c64.dmzreader.ui.search.IssueSearchViewModel
import se.c64.dmzreader.ui.search.SearchUiState
import se.c64.dmzreader.ui.search.IssueSearchUiState
import se.c64.dmzreader.ui.search.SearchViewModel
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertFalse
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    @get:Rule val folder = TemporaryFolder()
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private lateinit var db: DmzDatabase
    private lateinit var viewModel: SearchViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        db = inMemoryDatabase()
    }

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        db.close()
        Dispatchers.resetMain()
        main.close()
    }

    @Test
    fun `replacing an index reruns an unchanged query and clears old snippets`() = runBlocking {
        val id = "datormagazin-1995-14"
        db.issueDao().replaceAll(listOf(issueEntity(id)))
        // Bytet sker på samma tråd som vymodellen: det korta null-läget kan inte observeras.
        val manager = SearchIndexManager(folder.newFolder("data"), dispatcher = main)
        manager.import({ TestFiles.searchTestDb.inputStream() }, null)
        viewModel = SearchViewModel(
            LibraryRepository(db.issueDao(), db.issueUserStateDao(), db.issueLocalStateDao()),
            BookmarkRepository(db.bookmarkDao(), db.issueDao(), db.issueLocalStateDao()),
            manager, FakeActiveInventoryStore(), log = {},
        )
        val observer = launch { viewModel.state.collect() }
        try {
            viewModel.setText("högtalare")
            val old = withTimeout(10_000) {
                viewModel.state.first { !it.searching && it.fullText?.totalPages == 3 }
            }
            val rowId = old.fullText!!.groups.first().pages.first().rowId
            viewModel.requestSnippet(rowId)
            withTimeout(10_000) { viewModel.state.first { it.snippets.isNotEmpty() } }

            val replacement = folder.newFile("replacement.db")
            TestFiles.searchTestDb.copyTo(replacement, overwrite = true)
            BundledSQLiteDriver().open(replacement.path).use {
                it.execSQL("INSERT INTO pages_fts(pages_fts) VALUES('delete-all')")
            }
            manager.import({ replacement.inputStream() }, null)
            val updated = withTimeout(10_000) {
                viewModel.state.first { !it.searching && it.query?.display == "högtalare" && it.fullText?.totalPages == 0 }
            }
            assertTrue(updated.snippets.isEmpty())
            assertEquals("högtalare", updated.text)
        } finally {
            observer.cancelAndJoin()
            manager.remove()
        }
    }

    // --- Träffarna under skrivning, och indexbyte med samma fråga (regressioner efter F4) ---

    private val library get() = LibraryRepository(db.issueDao(), db.issueUserStateDao(), db.issueLocalStateDao())

    /** Ett index där "högtalare" bara finns på en av de tre sidorna i 1995 nr 14 (rad 170, sida 26). */
    private fun reducedIndex(): java.io.File {
        val file = folder.newFile("reduced.db")
        TestFiles.searchTestDb.copyTo(file, overwrite = true)
        BundledSQLiteDriver().open(file.path).use {
            it.execSQL("INSERT INTO pages_fts(pages_fts) VALUES('delete-all')")
            it.execSQL("INSERT INTO pages_fts(rowid, searchText) SELECT id, searchText FROM pages WHERE id = 170")
        }
        return file
    }

    private suspend fun globalSearch(manager: SearchIndexManager): SearchViewModel {
        db.issueDao().replaceAll(listOf(issueEntity("datormagazin-1995-14"), issueEntity("datormagazin-1986-01")))
        return SearchViewModel(library, BookmarkRepository(db.bookmarkDao(), db.issueDao(), db.issueLocalStateDao()), manager, FakeActiveInventoryStore(), log = {})
            .also { viewModel = it }
    }

    @Test
    fun `earlier hits stay on screen while typing`() = runBlocking {
        val manager = SearchIndexManager(folder.newFolder("data"), dispatcher = main)
        manager.import({ TestFiles.searchTestDb.inputStream() }, null)
        val search = globalSearch(manager)
        val states = CopyOnWriteArrayList<SearchUiState>()
        val observer = launch { search.state.collect { states += it } }
        try {
            search.setText("högtalare")
            withTimeout(10_000) { search.state.first { !it.searching && it.fullText?.totalPages == 3 } }
            states.clear()
            // Nästa fråga skrivs. Under pausen före den nya sökningen ska de tidigare träffarna stå kvar.
            search.setText("commodore")
            // Frågan i tillståndet följer texten direkt. De nya träffarna är en sida i 1986 nr 1.
            val updated = withTimeout(10_000) { search.state.first { !it.searching && it.fullText?.totalPages == 1 } }
            assertEquals("commodore", updated.query?.display)
            val before = states.takeWhile { it.fullText?.totalPages != 1 }
            assertTrue("tillstånd under pausen: ${before.size}", before.isNotEmpty())
            assertTrue(
                "de tidigare träffarna försvann under pausen: ${before.map { it.fullText?.totalPages }}",
                before.all { it.fullText?.totalPages == 3 },
            )
        } finally {
            observer.cancelAndJoin()
            manager.remove()
        }
    }

    @Test
    fun `an index change with an unchanged query shows only the new index's hits and snippets`() = runBlocking {
        val manager = SearchIndexManager(folder.newFolder("data"), dispatcher = main)
        manager.import({ TestFiles.searchTestDb.inputStream() }, null)
        val search = globalSearch(manager)
        val states = CopyOnWriteArrayList<SearchUiState>()
        val observer = launch { search.state.collect { states += it } }
        try {
            search.setText("högtalare")
            val old = withTimeout(10_000) { search.state.first { !it.searching && it.fullText?.totalPages == 3 } }
            search.requestSnippet(old.fullText!!.groups.first().pages.first().rowId)
            withTimeout(10_000) { search.state.first { it.snippets.isNotEmpty() } }

            val reduced = reducedIndex()
            states.clear()
            manager.import({ reduced.inputStream() }, null)
            val updated = withTimeout(10_000) { search.state.first { !it.searching && it.fullText?.totalPages == 1 } }
            assertEquals("högtalare", updated.text)
            assertEquals(listOf(26), updated.fullText!!.groups.single().pages.map { it.pageNumber })
            // Efter att de nya träffarna har visats syns aldrig de gamla igen, och inga gamla utdrag.
            val after = states.dropWhile { it.fullText?.totalPages == 3 }
            assertTrue(after.none { it.fullText?.totalPages == 3 })
            // Utdrag visas bara vid träffar. Inget tillstånd med träffar efter bytet får bära det gamla indexets utdrag.
            assertTrue("gamla utdrag vid nya träffar", after.filter { (it.fullText?.totalPages ?: 0) > 0 }.all { it.snippets.isEmpty() })
            assertTrue(updated.snippets.isEmpty())
        } finally {
            observer.cancelAndJoin()
            manager.remove()
        }
    }

    // --- Sök i numret ---

    private lateinit var issueSearch: IssueSearchViewModel

    private suspend fun searchInIssue(manager: SearchIndexManager): IssueSearchViewModel {
        db.issueDao().replaceAll(listOf(issueEntity("datormagazin-1995-14")))
        return IssueSearchViewModel("datormagazin-1995-14", manager, library).also { issueSearch = it }
    }

    @Test
    fun `earlier hits in the issue stay while typing`() = runBlocking {
        val manager = SearchIndexManager(folder.newFolder("data"), dispatcher = main)
        manager.import({ TestFiles.searchTestDb.inputStream() }, null)
        val search = searchInIssue(manager)
        val states = CopyOnWriteArrayList<IssueSearchUiState>()
        val observer = launch { search.state.collect { states += it } }
        try {
            search.setText("högtalare")
            withTimeout(10_000) { search.state.first { !it.searching && it.hits.size == 3 } }
            states.clear()
            search.setText("högtal*")
            withTimeout(10_000) { search.state.first { it.query?.display == "högtal*" && !it.searching } }
            val before = states.takeWhile { it.query?.display != "högtal*" }
            assertTrue(before.isNotEmpty())
            assertTrue("träffarna i numret försvann under pausen: ${before.map { it.hits.size }}", before.all { it.hits.size == 3 })
        } finally {
            observer.cancelAndJoin()
            issueSearch.viewModelScope.cancel()
            manager.remove()
        }
    }

    @Test
    fun `an index change in the issue search shows only the new hits`() = runBlocking {
        val manager = SearchIndexManager(folder.newFolder("data"), dispatcher = main)
        manager.import({ TestFiles.searchTestDb.inputStream() }, null)
        val search = searchInIssue(manager)
        val states = CopyOnWriteArrayList<IssueSearchUiState>()
        val observer = launch { search.state.collect { states += it } }
        try {
            search.setText("högtalare")
            val old = withTimeout(10_000) { search.state.first { !it.searching && it.hits.size == 3 } }
            search.requestSnippet(old.hits.first().rowId)
            withTimeout(10_000) { search.state.first { it.snippets.isNotEmpty() } }

            val reduced = reducedIndex()
            states.clear()
            manager.import({ reduced.inputStream() }, null)
            val updated = withTimeout(10_000) { search.state.first { !it.searching && it.hits.size == 1 } }
            assertEquals(listOf(26), updated.hits.map { it.pageNumber })
            val after = states.dropWhile { it.hits.size == 3 }
            assertTrue(after.none { it.hits.size == 3 })
            assertTrue(after.takeWhile { it.hits.size != 1 }.all { it.snippets.isEmpty() })
            assertFalse(updated.snippets.containsKey(old.hits.last().rowId))
        } finally {
            observer.cancelAndJoin()
            issueSearch.viewModelScope.cancel()
            manager.remove()
        }
    }
}
