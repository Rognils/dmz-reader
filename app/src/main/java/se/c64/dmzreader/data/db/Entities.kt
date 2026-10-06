package se.c64.dmzreader.data.db

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Katalogdata (planens C2). Speglar inventory utom sourceNames och ersätts helt
 * när ett nytt inventory läses in.
 *
 * Ingen annan tabell har en främmande nyckel hit: användardata och lokal data
 * måste överleva att katalogen byts ut.
 */
@Entity(tableName = "Issue", indices = [Index("publicationId", "year")])
data class IssueEntity(
    @PrimaryKey val issueId: String,
    val publicationId: String,
    val publicationTitle: String,
    val year: Int,
    val issueLabel: String,
    val sortKey: String,
    val sourceKey: String,
    val relatedIssueId: String?,
    val coverUrl: String?,
    val coverFlags: List<String>,
    /** Sökväg under assets till en färdig miniatyr, t.ex. "covers/datormagazin-1989-06.webp". */
    val coverAsset: String? = null,
    val ocrPdfUrl: String?,
    val ocrPdfSize: Long?,
    val ocrPdfEtag: String?,
    val ocrPdfLastModified: String?,
    val largePdfUrl: String?,
    val largePdfSize: Long?,
    val hqSource: String?,
    val scanPagePattern: String?,
    val scanPageCount: Int?,
    val pageCount: Int?,
    val spreadOffset: Int?,
    val flags: List<String>,
)

enum class DownloadState { NONE, QUEUED, DOWNLOADING, DOWNLOADED, FAILED }

/** Telefonens egen kunskap om ett nummer (planens C3). Ingår inte i backup. */
@Entity(tableName = "IssueLocalState")
data class IssueLocalState(
    @PrimaryKey val issueId: String,
    val observedPageCount: Int? = null,
    val observedPdfSize: Long? = null,
    val downloadState: DownloadState = DownloadState.NONE,
    val downloadedPath: String? = null,
    val downloadedBytes: Long? = null,
    val downloadedAt: Long? = null,
    val detectedSpreadOffset: Int? = null,
)

enum class ReadStatusOverride { READ, UNREAD }

/** Användarens data om ett nummer (planens C4). Skrivs aldrig av import. */
@Entity(tableName = "IssueUserState")
data class IssueUserState(
    @PrimaryKey val issueId: String,
    val isFavorite: Boolean = false,
    val lastPage: Int? = null,
    val maxPageReached: Int? = null,
    val firstOpenedAt: Long? = null,
    val lastOpenedAt: Long? = null,
    val completedAt: Long? = null,
    /** null betyder automatisk lässtatus (planens C10). */
    val readStatusOverride: ReadStatusOverride? = null,
    val updatedAt: Long,
)

/** Sparad sida (planens C5). pageNumber är PDF-sidindex med start på 1. */
@Entity(tableName = "Bookmark", indices = [Index("issueId")])
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val issueId: String,
    val pageNumber: Int,
    val title: String? = null,
    val note: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** En lässession (planens C6). */
@Entity(tableName = "ReadingSession", indices = [Index("issueId")])
data class ReadingSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val issueId: String,
    val startedAt: Long,
    val endedAt: Long,
    val startPage: Int,
    val endPage: Int,
    val pagesViewed: Int,
    val durationSeconds: Int,
)
