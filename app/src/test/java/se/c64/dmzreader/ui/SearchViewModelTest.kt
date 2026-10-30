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
import se.c64.dmzreader.ui.search.SearchViewModel
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
}
