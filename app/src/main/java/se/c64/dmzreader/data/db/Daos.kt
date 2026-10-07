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

    /** Nästa nummer i nedladdningskön: det som begärdes först. */
    @Query(
        "SELECT * FROM IssueLocalState WHERE downloadState = 'QUEUED' " +
            "ORDER BY downloadQueuedAt IS NULL, downloadQueuedAt, issueId LIMIT 1"
    )
    suspend fun nextQueued(): IssueLocalState?

    @Query("SELECT COUNT(*) FROM IssueLocalState WHERE downloadState = 'QUEUED'")
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

    @Query("SELECT * FROM Bookmark ORDER BY id")
    suspend fun getAll(): List<Bookmark>
}

@Dao
interface ReadingSessionDao {
    @Insert
    suspend fun insert(session: ReadingSession): Long

    @Query("SELECT * FROM ReadingSession ORDER BY id")
    suspend fun getAll(): List<ReadingSession>
}
