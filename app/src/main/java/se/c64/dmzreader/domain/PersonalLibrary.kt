package se.c64.dmzreader.domain

import se.c64.dmzreader.data.db.Bookmark
import se.c64.dmzreader.data.db.IssueEntity
import se.c64.dmzreader.data.db.IssueUserState

/** Favoriter och bokmärken (planens FAS 6). Ren logik utan Android-beroenden. */

/** Titeln ett bokmärke får när det skapas med ett tryck. */
fun defaultBookmarkTitle(pageNumber: Int) = "Sida $pageNumber"

/** "Sida 7 · Ledare", eller bara "Sida 7" när bokmärket saknar egen titel. */
fun bookmarkLabel(bookmark: Bookmark): String {
    val page = defaultBookmarkTitle(bookmark.pageNumber)
    val title = bookmark.title?.trim().orEmpty()
    return if (title.isEmpty() || title == page) page else "$page · $title"
}

/** Det användaren skrev i titelfältet. Tomt betyder standardtiteln. */
fun normalizedBookmarkTitle(input: String, pageNumber: Int): String =
    input.trim().ifEmpty { defaultBookmarkTitle(pageNumber) }

/** En tom anteckning lagras som null. */
fun normalizedBookmarkNote(input: String): String? = input.trim().ifEmpty { null }

/**
 * Bokmärkena i ett nummer, nyast först. [issue] är null när numret saknas i det
 * aktiva arkivindexet (planens D6): bokmärkena finns kvar och kan tas bort, men
 * numret går inte att öppna.
 */
data class BookmarkGroup(
    val issueId: String,
    val issue: IssueEntity?,
    val bookmarks: List<Bookmark>,
    /** Sant om numrets PDF kan ha bytts i arkivet sedan bokmärkena skapades (planens C7). */
    val fileChanged: Boolean,
) {
    val missingFromInventory: Boolean get() = issue == null
}

data class PersonalLibrary(
    /** Favoritnummer som finns i arkivindexet, i tidningens ordning. */
    val favorites: List<IssueWithState> = emptyList(),
    /** issueId för favoriter som saknas i arkivindexet. */
    val missingFavorites: List<String> = emptyList(),
    val bookmarkGroups: List<BookmarkGroup> = emptyList(),
) {
    val isEmpty: Boolean get() = favorites.isEmpty() && missingFavorites.isEmpty() && bookmarkGroups.isEmpty()
}

/** Bokmärken i visningsordning: nyast först. */
fun newestFirst(bookmarks: List<Bookmark>): List<Bookmark> =
    bookmarks.sortedWith(compareByDescending<Bookmark> { it.createdAt }.thenByDescending { it.id })

/**
 * Sammanställer fliken Favoriter. Användardata för nummer som inte finns i
 * [issues] tas inte bort och göms inte: den visas som saknad.
 */
fun personalLibrary(
    issues: List<IssueWithState>,
    userStates: List<IssueUserState>,
    bookmarks: List<Bookmark>,
): PersonalLibrary {
    val byId = issues.associateBy { it.issue.issueId }
    val groups = bookmarks.groupBy { it.issueId }.map { (issueId, inIssue) ->
        val item = byId[issueId]
        BookmarkGroup(
            issueId = issueId,
            issue = item?.issue,
            bookmarks = newestFirst(inIssue),
            fileChanged = item != null && archiveFileChanged(item.issue, item.local, inIssue),
        )
    }
    return PersonalLibrary(
        favorites = issues.filter { it.state?.isFavorite == true },
        missingFavorites = userStates.filter { it.isFavorite && it.issueId !in byId }.map { it.issueId }.sorted(),
        // Nummer som finns först, det med nyast bokmärke överst. Saknade nummer sist.
        bookmarkGroups = groups.sortedWith(
            compareBy<BookmarkGroup> { it.missingFromInventory }
                .thenByDescending { it.bookmarks.first().createdAt }
                .thenBy { it.issueId },
        ),
    )
}
