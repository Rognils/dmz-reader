package se.c64.dmzreader.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

data class YearCount(
    val publicationId: String,
    val publicationTitle: String,
    val year: Int,
    val issueCount: Int,
)

data class PublicationCount(
    val publicationId: String,
    val publicationTitle: String,
    val issueCount: Int,
)

@Dao
abstract class IssueDao {
    @Query("SELECT COUNT(*) FROM Issue")
    abstract suspend fun count(): Int

    @Query("SELECT * FROM Issue ORDER BY publicationId, sortKey")
    abstract suspend fun getAll(): List<IssueEntity>

    @Query("SELECT * FROM Issue ORDER BY publicationId, sortKey")
    abstract fun observeAll(): Flow<List<IssueEntity>>

    @Query("SELECT * FROM Issue WHERE issueId = :issueId")
    abstract suspend fun get(issueId: String): IssueEntity?

    @Query(
        "SELECT publicationId, publicationTitle, year, COUNT(*) AS issueCount FROM Issue " +
            "GROUP BY publicationId, publicationTitle, year ORDER BY publicationTitle, year"
    )
    abstract fun observeYearCounts(): Flow<List<YearCount>>

    @Query(
        "SELECT publicationId, publicationTitle, COUNT(*) AS issueCount FROM Issue " +
            "GROUP BY publicationId, publicationTitle ORDER BY publicationTitle"
    )
    abstract fun observePublicationCounts(): Flow<List<PublicationCount>>

    /** Numren i en årgång, i tidningens ordning. sortKey sorterar rätt som text. */
    @Query("SELECT * FROM Issue WHERE publicationId = :publicationId AND year = :year ORDER BY sortKey")
    abstract fun observeIssues(publicationId: String, year: Int): Flow<List<IssueEntity>>

    @Query("SELECT * FROM Issue WHERE issueId = :issueId")
    abstract fun observeIssue(issueId: String): Flow<IssueEntity?>

    /** Nummer som pekar på [issueId] med relatedIssueId, t.ex. en bilaga till numret. */
    @Query("SELECT * FROM Issue WHERE relatedIssueId = :issueId ORDER BY publicationId, sortKey")
    abstract fun observeIssuesRelatedTo(issueId: String): Flow<List<IssueEntity>>

    @Query("DELETE FROM Issue")
    protected abstract suspend fun deleteAll()

    @Insert
    protected abstract suspend fun insertAll(issues: List<IssueEntity>)

    /**
     * Ersätter hela katalogen i en enda transaktion. Antingen gäller den nya
     * katalogen helt, eller så är den gamla kvar. Inga andra tabeller berörs.
     */
    @Transaction
    open suspend fun replaceAll(issues: List<IssueEntity>) {
        deleteAll()
        insertAll(issues)
    }
}

@Dao
interface IssueLocalStateDao {
    @Upsert
    suspend fun upsert(state: IssueLocalState)

    @Insert
    suspend fun insert(state: IssueLocalState)

    /** Ger antalet ändrade rader: 0 om numret inte har någon rad. */
    @Update
    suspend fun update(state: IssueLocalState): Int

    /**
     * Nästa nummer i nedladdningskön: det som begärdes först. Ett nedladdat
     * nummer med downloadQueuedAt satt väntar på att uppdateras (planens C7).
     */
    @Query(
        "SELECT * FROM IssueLocalState WHERE downloadState = 'QUEUED' " +
            "OR (downloadState = 'DOWNLOADED' AND downloadQueuedAt IS NOT NULL) " +
            "ORDER BY downloadQueuedAt IS NULL, downloadQueuedAt, issueId LIMIT 1"
    )
    suspend fun nextQueued(): IssueLocalState?

    @Query(
        "SELECT COUNT(*) FROM IssueLocalState WHERE downloadState = 'QUEUED' " +
            "OR (downloadState = 'DOWNLOADED' AND downloadQueuedAt IS NOT NULL)"
    )
    suspend fun queuedCount(): Int

    @Query("SELECT * FROM IssueLocalState WHERE issueId = :issueId")
    suspend fun get(issueId: String): IssueLocalState?

    @Query("SELECT * FROM IssueLocalState ORDER BY issueId")
    suspend fun getAll(): List<IssueLocalState>

    @Query("SELECT * FROM IssueLocalState ORDER BY issueId")
    fun observeAll(): Flow<List<IssueLocalState>>
}

@Dao
interface IssueUserStateDao {
    @Upsert
    suspend fun upsert(state: IssueUserState)

    @Insert
    suspend fun insert(state: IssueUserState)

    /** Ger antalet ändrade rader: 0 om numret inte har någon rad. */
    @Update
    suspend fun update(state: IssueUserState): Int

    @Query("SELECT * FROM IssueUserState WHERE issueId = :issueId")
    suspend fun get(issueId: String): IssueUserState?

    @Query("SELECT * FROM IssueUserState ORDER BY issueId")
    suspend fun getAll(): List<IssueUserState>

    @Query("SELECT * FROM IssueUserState ORDER BY issueId")
    fun observeAll(): Flow<List<IssueUserState>>
}

@Dao
interface BookmarkDao {
    @Insert
    suspend fun insert(bookmark: Bookmark): Long

    @Update
    suspend fun update(bookmark: Bookmark): Int

    /** Mjuk radering: raden finns kvar men visas aldrig (ADR 0012). */
    @Query("UPDATE Bookmark SET deletedAt = :at, updatedAt = :at WHERE id = :id AND deletedAt IS NULL")
    suspend fun markDeleted(id: Long, at: Long): Int

    /** Ångrar en mjuk radering. */
    @Query("UPDATE Bookmark SET deletedAt = NULL, updatedAt = :at WHERE id = :id AND deletedAt IS NOT NULL")
    suspend fun restore(id: Long, at: Long): Int

    /** Tar bort raden helt. Bara för tester och rensning; appen raderar mjukt. */
    @Query("DELETE FROM Bookmark WHERE id = :id")
    suspend fun purge(id: Long): Int

    @Query("SELECT * FROM Bookmark WHERE id = :id")
    suspend fun get(id: Long): Bookmark?

    /** Alla rader, även borttagna bokmärken. För säkerhetskopior. */
    @Query("SELECT * FROM Bookmark ORDER BY id")
    suspend fun getAll(): List<Bookmark>

    @Query("SELECT * FROM Bookmark WHERE issueId = :issueId AND pageNumber = :pageNumber AND deletedAt IS NULL ORDER BY id")
    suspend fun getForPage(issueId: String, pageNumber: Int): List<Bookmark>

    /** Bokmärkena som visas: borttagna ingår aldrig. */
    @Query("SELECT * FROM Bookmark WHERE deletedAt IS NULL ORDER BY id")
    fun observeAll(): Flow<List<Bookmark>>
}

/**
 * Import av en backup (planens E11). Allt skrivs i en enda transaktion: går
 * något fel rullas hela importen tillbaka och ingenting har ändrats.
 */
@Dao
abstract class BackupDao {
    @Insert
    protected abstract suspend fun insertUserStates(states: List<IssueUserState>)

    @Update
    protected abstract suspend fun updateUserStates(states: List<IssueUserState>): Int

    @Insert
    protected abstract suspend fun insertBookmarks(bookmarks: List<Bookmark>)

    @Update
    protected abstract suspend fun updateBookmarks(bookmarks: List<Bookmark>): Int

    @Insert
    protected abstract suspend fun insertSessions(sessions: List<ReadingSession>)

    /**
     * Skriver det importen har räknat fram. [verify] körs sist, inne i
     * transaktionen: kastar den ett undantag rullas allt tillbaka.
     */
    @Transaction
    open suspend fun applyImport(
        userStatesToInsert: List<IssueUserState>,
        userStatesToUpdate: List<IssueUserState>,
        bookmarksToInsert: List<Bookmark>,
        bookmarksToUpdate: List<Bookmark>,
        sessionsToInsert: List<ReadingSession>,
        verify: suspend () -> Unit,
    ) {
        insertUserStates(userStatesToInsert)
        val updated = updateUserStates(userStatesToUpdate)
        check(updated == userStatesToUpdate.size) {
            "$updated av ${userStatesToUpdate.size} rader gick att uppdatera"
        }
        insertBookmarks(bookmarksToInsert)
        val updatedBookmarks = updateBookmarks(bookmarksToUpdate)
        check(updatedBookmarks == bookmarksToUpdate.size) {
            "$updatedBookmarks av ${bookmarksToUpdate.size} bokmärken gick att uppdatera"
        }
        insertSessions(sessionsToInsert)
        verify()
    }
}

@Dao
interface ReadingSessionDao {
    @Insert
    suspend fun insert(session: ReadingSession): Long

    @Query("SELECT * FROM ReadingSession ORDER BY id")
    suspend fun getAll(): List<ReadingSession>
}
