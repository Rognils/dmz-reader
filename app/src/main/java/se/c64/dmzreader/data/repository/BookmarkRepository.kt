package se.c64.dmzreader.data.repository

import kotlinx.coroutines.flow.Flow
import se.c64.dmzreader.data.db.Bookmark
import se.c64.dmzreader.data.db.BookmarkDao
import se.c64.dmzreader.data.db.IssueDao
import se.c64.dmzreader.data.db.IssueLocalStateDao
import se.c64.dmzreader.domain.defaultBookmarkTitle
import se.c64.dmzreader.domain.normalizedBookmarkNote
import se.c64.dmzreader.domain.normalizedBookmarkTitle
import se.c64.dmzreader.domain.pdfVersionForNewBookmark

/** Vad ett tryck på stjärnan gjorde. */
sealed interface BookmarkToggle {
    data class Added(val bookmark: Bookmark) : BookmarkToggle

    /** Bokmärkena som togs bort, så att borttagningen kan ångras. */
    data class Removed(val bookmarks: List<Bookmark>) : BookmarkToggle
}

/** Bokmärken (planens C5). Sidnummer är PDF-sidindex med start på 1. */
class BookmarkRepository(
    private val bookmarkDao: BookmarkDao,
    private val issueDao: IssueDao,
    private val localStateDao: IssueLocalStateDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun observeAll(): Flow<List<Bookmark>> = bookmarkDao.observeAll()

    suspend fun get(id: Long): Bookmark? = bookmarkDao.get(id)

    /**
     * Stjärnan i läsaren: har sidan inget bokmärke skapas ett med titeln "Sida X".
     * Har den ett (eller flera) tas de bort.
     */
    suspend fun toggle(issueId: String, pageNumber: Int): BookmarkToggle {
        val existing = bookmarkDao.getForPage(issueId, pageNumber)
        if (existing.isNotEmpty()) {
            existing.forEach { bookmarkDao.delete(it.id) }
            return BookmarkToggle.Removed(existing)
        }
        return BookmarkToggle.Added(add(issueId, pageNumber))
    }

    private suspend fun add(issueId: String, pageNumber: Int): Bookmark {
        val timestamp = now()
        // PDF-versionen sparas, så att ett senare byte av filen i arkivet går att upptäcka (C7).
        val version = pdfVersionForNewBookmark(issueDao.get(issueId), localStateDao.get(issueId))
        val bookmark = Bookmark(
            issueId = issueId,
            pageNumber = pageNumber,
            title = defaultBookmarkTitle(pageNumber),
            createdAt = timestamp,
            updatedAt = timestamp,
            pdfSize = version.size,
            pdfEtag = version.etag,
            pdfLastModified = version.lastModified,
        )
        return bookmark.copy(id = bookmarkDao.insert(bookmark))
    }

    /** Ångrar en borttagning: bokmärkena läggs tillbaka precis som de var. */
    suspend fun restore(bookmarks: List<Bookmark>) {
        for (bookmark in bookmarks) {
            if (bookmarkDao.get(bookmark.id) == null) bookmarkDao.insert(bookmark)
        }
    }

    /** Ändrar titel och anteckning. En tom titel blir "Sida X", en tom anteckning tas bort. */
    suspend fun edit(id: Long, title: String, note: String): Bookmark? {
        val current = bookmarkDao.get(id) ?: return null
        val changed = current.copy(
            title = normalizedBookmarkTitle(title, current.pageNumber),
            note = normalizedBookmarkNote(note),
        )
        if (changed == current) return current
        return changed.copy(updatedAt = now()).also { bookmarkDao.update(it) }
    }

    /** Tar bort ett bokmärke och ger tillbaka det, så att borttagningen kan ångras. */
    suspend fun delete(id: Long): Bookmark? {
        val current = bookmarkDao.get(id) ?: return null
        bookmarkDao.delete(id)
        return current
    }
}
