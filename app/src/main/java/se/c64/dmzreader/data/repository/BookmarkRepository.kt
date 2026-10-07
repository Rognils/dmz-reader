package se.c64.dmzreader.data.repository

import kotlinx.coroutines.flow.Flow
import se.c64.dmzreader.data.db.Bookmark
import se.c64.dmzreader.data.db.BookmarkDao
import se.c64.dmzreader.data.db.IssueDao
import se.c64.dmzreader.data.db.IssueLocalStateDao
import se.c64.dmzreader.data.db.newBookmarkId
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
    private val newId: () -> String = ::newBookmarkId,
) {
    fun observeAll(): Flow<List<Bookmark>> = bookmarkDao.observeAll()

    /** Bokmärket, eller null om det inte finns eller är borttaget. */
    suspend fun get(id: Long): Bookmark? = bookmarkDao.get(id)?.takeUnless { it.isDeleted }

    /**
     * Stjärnan i läsaren: har sidan inget bokmärke skapas ett med titeln "Sida X".
     * Har den ett (eller flera) tas de bort.
     */
    suspend fun toggle(issueId: String, pageNumber: Int): BookmarkToggle {
        val existing = bookmarkDao.getForPage(issueId, pageNumber)
        if (existing.isNotEmpty()) {
            val timestamp = now()
            existing.forEach { bookmarkDao.markDeleted(it.id, timestamp) }
            return BookmarkToggle.Removed(existing)
        }
        return BookmarkToggle.Added(add(issueId, pageNumber))
    }

    private suspend fun add(issueId: String, pageNumber: Int): Bookmark {
        val timestamp = now()
        // PDF-versionen sparas, så att ett senare byte av filen i arkivet går att upptäcka (C7).
        val version = pdfVersionForNewBookmark(issueDao.get(issueId), localStateDao.get(issueId))
        val bookmark = Bookmark(
            bookmarkId = newId(),
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

    /**
     * Ångrar en borttagning: raderingen nollställs, och bokmärkena visas igen med
     * samma identitet, titel och anteckning som förut.
     */
    suspend fun restore(bookmarks: List<Bookmark>) {
        val timestamp = now()
        bookmarks.forEach { bookmarkDao.restore(it.id, timestamp) }
    }

    /** Ändrar titel och anteckning. En tom titel blir "Sida X", en tom anteckning tas bort. */
    suspend fun edit(id: Long, title: String, note: String): Bookmark? {
        val current = bookmarkDao.get(id)?.takeUnless { it.isDeleted } ?: return null
        val changed = current.copy(
            title = normalizedBookmarkTitle(title, current.pageNumber),
            note = normalizedBookmarkNote(note),
        )
        if (changed == current) return current
        return changed.copy(updatedAt = now()).also { bookmarkDao.update(it) }
    }

    /**
     * Tar bort ett bokmärke och ger tillbaka det, så att borttagningen kan ångras.
     * Raderingen är mjuk: raden finns kvar med deletedAt satt, så att en äldre
     * säkerhetskopia inte kan återuppliva bokmärket.
     */
    suspend fun delete(id: Long): Bookmark? {
        val current = bookmarkDao.get(id)?.takeUnless { it.isDeleted } ?: return null
        bookmarkDao.markDeleted(id, now())
        return current
    }
}
