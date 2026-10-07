package se.c64.dmzreader.data.backup

import se.c64.dmzreader.data.db.Bookmark
import se.c64.dmzreader.data.db.IssueUserState
import se.c64.dmzreader.data.db.ReadingSession
import se.c64.dmzreader.domain.SeenPages

/**
 * Sammanslagning av en backup med det som redan finns på telefonen (planens
 * E11, ADR 0012). Ren logik: ingenting här rör databasen.
 *
 * Grundregeln är att en import aldrig tar bort något. Läshistorik läggs ihop,
 * och för de fält där bara ett värde kan gälla vinner den senast ändrade posten.
 */

/**
 * Slår ihop numrets rad på telefonen med raden i backupen.
 *
 * - lastPage, readStatusOverride och isFavorite tas från raden med senast
 *   updatedAt. Är de lika gäller telefonens.
 * - seenPages slås ihop med bitvis OR, och maxPageReached räknas om från resultatet.
 * - firstOpenedAt blir det tidigaste, lastOpenedAt och completedAt det senaste.
 */
fun mergeUserState(local: IssueUserState?, incoming: IssueUserState): IssueUserState {
    if (local == null) return incoming.withRecomputedMax(incoming.maxPageReached)
    val newest = if (incoming.updatedAt > local.updatedAt) incoming else local
    val seen = SeenPages.decode(local.seenPages) or SeenPages.decode(incoming.seenPages)
    return local.copy(
        isFavorite = newest.isFavorite,
        lastPage = newest.lastPage,
        readStatusOverride = newest.readStatusOverride,
        seenPages = seen.encode(),
        // Äldre rader kan ha ett värde utan seenPages. Det sänks aldrig.
        maxPageReached = listOfNotNull(seen.maxPage, local.maxPageReached, incoming.maxPageReached).maxOrNull(),
        firstOpenedAt = listOfNotNull(local.firstOpenedAt, incoming.firstOpenedAt).minOrNull(),
        lastOpenedAt = listOfNotNull(local.lastOpenedAt, incoming.lastOpenedAt).maxOrNull(),
        completedAt = listOfNotNull(local.completedAt, incoming.completedAt).maxOrNull(),
        updatedAt = maxOf(local.updatedAt, incoming.updatedAt),
    )
}

private fun IssueUserState.withRecomputedMax(previous: Int?): IssueUserState =
    copy(maxPageReached = listOfNotNull(SeenPages.decode(seenPages).maxPage, previous).maxOrNull())

/**
 * Slår ihop ett bokmärke på telefonen med samma bokmärke (samma bookmarkId) i backupen.
 *
 * - Titel och anteckning tas från raden med senast updatedAt. Är de lika gäller telefonens.
 * - Är bokmärket borttaget på någon av sidorna förblir det borttaget. En äldre
 *   backup kan därför aldrig återuppliva ett bokmärke.
 */
fun mergeBookmark(local: Bookmark, incoming: Bookmark): Bookmark {
    val newest = if (incoming.updatedAt > local.updatedAt) incoming else local
    return local.copy(
        title = newest.title,
        note = newest.note,
        deletedAt = listOfNotNull(local.deletedAt, incoming.deletedAt).maxOrNull(),
        updatedAt = maxOf(local.updatedAt, incoming.updatedAt),
    )
}

/** Det som finns på telefonen när importen planeras. [bookmarks] innehåller även borttagna. */
data class UserData(
    val userStates: List<IssueUserState>,
    val bookmarks: List<Bookmark>,
    val sessions: List<ReadingSession>,
)

/** Det användaren får se innan importen görs. */
data class ImportSummary(
    // Vad filen innehåller.
    val favorites: Int,
    val bookmarks: Int,
    val sessions: Int,
    val issuesWithReadingData: Int,
    // Vad importen skulle göra.
    val newIssues: Int,
    val updatedIssues: Int,
    /** Nya bokmärken som kommer att synas. */
    val newBookmarks: Int,
    /** Befintliga bokmärken som får ny titel eller anteckning, eller tas bort. */
    val updatedBookmarks: Int = 0,
    val newSessions: Int,
    /** Rader som skrivs utan att synas: borttagna bokmärken som följer med från filen. */
    val hiddenChanges: Int = 0,
) {
    /** Sant om importen inte skulle ändra någonting: allt i filen finns redan. */
    val nothingToDo: Boolean
        get() = newIssues + updatedIssues + newBookmarks + updatedBookmarks + newSessions + hiddenChanges == 0
}

/** Exakt vad importen ska skriva. Ingenting tas bort. */
data class ImportPlan(
    val userStatesToInsert: List<IssueUserState>,
    val userStatesToUpdate: List<IssueUserState>,
    val bookmarksToInsert: List<Bookmark>,
    val bookmarksToUpdate: List<Bookmark> = emptyList(),
    val sessionsToInsert: List<ReadingSession>,
    val summary: ImportSummary,
)

private data class BookmarkKey(val issueId: String, val pageNumber: Int, val title: String?)
private data class SessionKey(val issueId: String, val startedAt: Long)

private fun Bookmark.key() = BookmarkKey(issueId, pageNumber, title)
private fun ReadingSession.key() = SessionKey(issueId, startedAt)

/**
 * Jämför backupen med det som finns och räknar ut vad som ska skrivas.
 *
 * - Bokmärken med bookmarkId (format 2) matchas på det och slås ihop med [mergeBookmark].
 * - Bokmärken utan bookmarkId (format 1) får ett nytt UUID. De hoppas över om
 *   telefonen redan har ett med samma issueId, pageNumber och title, även ett borttaget.
 * - Sessioner med samma issueId och startedAt som en befintlig hoppas över.
 * - Dubbletter inom själva filen räknas bara en gång.
 */
fun planImport(current: UserData, backup: BackupFile, newId: () -> String = ::newBookmarkIdForImport): ImportPlan {
    val localStates = current.userStates.associateBy { it.issueId }
    val merged = LinkedHashMap<String, IssueUserState>()
    for (incoming in backup.issueUserStates.map { it.toEntity() }) {
        merged[incoming.issueId] = mergeUserState(merged[incoming.issueId] ?: localStates[incoming.issueId], incoming)
    }
    val toInsert = merged.values.filter { it.issueId !in localStates }
    val toUpdate = merged.values.filter { state -> localStates[state.issueId]?.let { it != state } == true }

    val localBookmarks = current.bookmarks.associateBy { it.bookmarkId }
    val bookmarkKeys = current.bookmarks.mapTo(HashSet()) { it.key() }
    // Läget efter importen, per bookmarkId: både nya och ändrade bokmärken.
    val changedBookmarks = LinkedHashMap<String, Bookmark>()
    for (incoming in backup.bookmarks) {
        if (incoming.bookmarkId == null) {
            // Format 1: ingen identitet att matcha på, bara dubblettregeln.
            val bookmark = incoming.toEntity(newId)
            if (bookmarkKeys.add(bookmark.key())) changedBookmarks[bookmark.bookmarkId] = bookmark
        } else {
            val bookmark = incoming.toEntity()
            val known = changedBookmarks[bookmark.bookmarkId] ?: localBookmarks[bookmark.bookmarkId]
            val result = if (known == null) bookmark else mergeBookmark(known, bookmark)
            if (result != known) changedBookmarks[bookmark.bookmarkId] = result
        }
    }
    val bookmarksToInsert = changedBookmarks.values.filter { it.bookmarkId !in localBookmarks }
    val bookmarksToUpdate = changedBookmarks.values.filter { it.bookmarkId in localBookmarks }

    val sessionKeys = current.sessions.mapTo(HashSet()) { it.key() }
    val newSessions = backup.readingSessions.map { it.toEntity() }.filter { sessionKeys.add(it.key()) }

    return ImportPlan(
        userStatesToInsert = toInsert,
        userStatesToUpdate = toUpdate,
        bookmarksToInsert = bookmarksToInsert,
        bookmarksToUpdate = bookmarksToUpdate,
        sessionsToInsert = newSessions,
        summary = ImportSummary(
            favorites = backup.issueUserStates.count { it.isFavorite },
            bookmarks = backup.bookmarks.count { it.deletedAt == null },
            sessions = backup.readingSessions.size,
            issuesWithReadingData = backup.issueUserStates.count { it.seenPages != null || it.lastPage != null },
            newIssues = toInsert.size,
            updatedIssues = toUpdate.size,
            newBookmarks = bookmarksToInsert.count { !it.isDeleted },
            // Ett bokmärke som redan är borttaget på telefonen syns inte, vad filen än säger.
            updatedBookmarks = bookmarksToUpdate.count { localBookmarks.getValue(it.bookmarkId).deletedAt == null },
            newSessions = newSessions.size,
            hiddenChanges = bookmarksToInsert.count { it.isDeleted } +
                bookmarksToUpdate.count { localBookmarks.getValue(it.bookmarkId).deletedAt != null },
        ),
    )
}

private fun newBookmarkIdForImport(): String = se.c64.dmzreader.data.db.newBookmarkId()

/** Resultatet om planen genomförs. Används för att kontrollera att ingenting försvinner. */
fun UserData.after(plan: ImportPlan): UserData {
    val updated = plan.userStatesToUpdate.associateBy { it.issueId }
    return UserData(
        userStates = userStates.map { updated[it.issueId] ?: it } + plan.userStatesToInsert,
        bookmarks = bookmarks.map { existing -> plan.bookmarksToUpdate.firstOrNull { it.bookmarkId == existing.bookmarkId } ?: existing } +
            plan.bookmarksToInsert,
        sessions = sessions + plan.sessionsToInsert,
    )
}
