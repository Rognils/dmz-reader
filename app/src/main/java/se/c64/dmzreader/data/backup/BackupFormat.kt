package se.c64.dmzreader.data.backup

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import se.c64.dmzreader.data.db.Bookmark
import se.c64.dmzreader.data.db.IssueUserState
import se.c64.dmzreader.data.db.ReadStatusOverride
import se.c64.dmzreader.data.db.ReadingSession
import se.c64.dmzreader.data.db.newBookmarkId

/**
 * Backupfilens format (planens E11, ADR 0012). Formatet är skilt från
 * databasens tabeller, så att databasen kan ändras utan att gamla backuper
 * slutar gå att läsa.
 */
const val BACKUP_FORMAT = 2

/**
 * Format som går att läsa in. Format 1 (FAS 6) saknar bokmärkenas bookmarkId
 * och deletedAt; övrigt är lika.
 */
val SUPPORTED_BACKUP_FORMATS = 1..2

@Serializable
data class BackupFile(
    val backupFormat: Int,
    val exportedAt: String,
    val appVersion: String,
    val issueUserStates: List<BackupUserState> = emptyList(),
    val bookmarks: List<BackupBookmark> = emptyList(),
    val readingSessions: List<BackupSession> = emptyList(),
)

@Serializable
data class BackupUserState(
    val issueId: String,
    val isFavorite: Boolean = false,
    val lastPage: Int? = null,
    val maxPageReached: Int? = null,
    /** Lästa sidor som bitmängd i Base64 (ADR 0018). */
    val seenPages: String? = null,
    val firstOpenedAt: Long? = null,
    val lastOpenedAt: Long? = null,
    val completedAt: Long? = null,
    /** "READ", "UNREAD" eller null. */
    val readStatusOverride: String? = null,
    val updatedAt: Long,
)

@Serializable
data class BackupBookmark(
    /** Bokmärkets UUID. Finns från format 2; null i format 1. */
    val bookmarkId: String? = null,
    val issueId: String,
    val pageNumber: Int,
    val title: String? = null,
    val note: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    // PDF-versionen när bokmärket skapades, för markeringen om utbytt arkivfil (C7).
    val pdfSize: Long? = null,
    val pdfEtag: String? = null,
    val pdfLastModified: String? = null,
    /** När bokmärket togs bort, eller null. Finns från format 2. */
    val deletedAt: Long? = null,
)

@Serializable
data class BackupSession(
    val issueId: String,
    val startedAt: Long,
    val endedAt: Long,
    val startPage: Int,
    val endPage: Int,
    val pagesViewed: Int,
    val durationSeconds: Int,
)

/** En backup som inte går att läsa in, med en förklaring som kan visas för användaren. */
class BackupFormatException(message: String) : Exception(message)

private val backupJson = Json {
    prettyPrint = true
    encodeDefaults = true
    // Fält som en senare version av appen har lagt till inom samma format hoppas över.
    ignoreUnknownKeys = true
}

fun encodeBackup(backup: BackupFile): String = backupJson.encodeToString(BackupFile.serializer(), backup)

/**
 * Läser och validerar en backupfil. Kastar [BackupFormatException] om filen inte
 * är en backup från appen, har ett format appen inte stöder eller innehåller
 * ogiltiga värden.
 */
fun parseBackup(text: String): BackupFile {
    val root = try {
        backupJson.parseToJsonElement(text) as? JsonObject
    } catch (e: SerializationException) {
        null
    } ?: throw BackupFormatException("Filen är inte en säkerhetskopia från DMZ Reader.")

    val format = try {
        root["backupFormat"]?.jsonPrimitive?.int
    } catch (e: Exception) {
        null
    } ?: throw BackupFormatException("Filen är inte en säkerhetskopia från DMZ Reader: backupFormat saknas.")
    if (format !in SUPPORTED_BACKUP_FORMATS) {
        throw BackupFormatException(
            "Säkerhetskopian har format $format. Den här versionen av appen kan läsa format " +
                "${SUPPORTED_BACKUP_FORMATS.first}–${SUPPORTED_BACKUP_FORMATS.last}.",
        )
    }

    val backup = try {
        backupJson.decodeFromJsonElement(BackupFile.serializer(), root)
    } catch (e: SerializationException) {
        throw BackupFormatException("Säkerhetskopian är skadad: ${e.message?.lineSequence()?.firstOrNull()}")
    } catch (e: IllegalArgumentException) {
        throw BackupFormatException("Säkerhetskopian är skadad: ${e.message?.lineSequence()?.firstOrNull()}")
    }

    for (state in backup.issueUserStates) {
        require(state.issueId.isNotBlank(), "ett nummer saknar issueId")
        require(
            state.readStatusOverride == null || ReadStatusOverride.entries.any { it.name == state.readStatusOverride },
            "okänd lässtatus \"${state.readStatusOverride}\" för ${state.issueId}",
        )
        require(state.lastPage == null || state.lastPage >= 1, "ogiltig sida för ${state.issueId}")
    }
    for (bookmark in backup.bookmarks) {
        require(bookmark.issueId.isNotBlank(), "ett bokmärke saknar issueId")
        require(bookmark.pageNumber >= 1, "ogiltig sida ${bookmark.pageNumber} i ett bokmärke för ${bookmark.issueId}")
        // Från format 2 identifieras bokmärken av sitt UUID. Utan det går de inte att matcha.
        require(
            format < 2 || !bookmark.bookmarkId.isNullOrBlank(),
            "ett bokmärke för ${bookmark.issueId} saknar bookmarkId",
        )
    }
    for (session in backup.readingSessions) {
        require(session.issueId.isNotBlank(), "en session saknar issueId")
    }
    return backup
}

private fun require(condition: Boolean, problem: String) {
    if (!condition) throw BackupFormatException("Säkerhetskopian är skadad: $problem.")
}

// --- Mellan backupformatet och databasens rader ---

fun IssueUserState.toBackup() = BackupUserState(
    issueId = issueId, isFavorite = isFavorite, lastPage = lastPage, maxPageReached = maxPageReached,
    seenPages = seenPages, firstOpenedAt = firstOpenedAt, lastOpenedAt = lastOpenedAt, completedAt = completedAt,
    readStatusOverride = readStatusOverride?.name, updatedAt = updatedAt,
)

fun BackupUserState.toEntity() = IssueUserState(
    issueId = issueId, isFavorite = isFavorite, lastPage = lastPage, maxPageReached = maxPageReached,
    seenPages = seenPages, firstOpenedAt = firstOpenedAt, lastOpenedAt = lastOpenedAt, completedAt = completedAt,
    readStatusOverride = readStatusOverride?.let(ReadStatusOverride::valueOf), updatedAt = updatedAt,
)

fun Bookmark.toBackup() = BackupBookmark(
    bookmarkId = bookmarkId,
    issueId = issueId, pageNumber = pageNumber, title = title, note = note, createdAt = createdAt,
    updatedAt = updatedAt, pdfSize = pdfSize, pdfEtag = pdfEtag, pdfLastModified = pdfLastModified,
    deletedAt = deletedAt,
)

/**
 * Radens id sätts av databasen. Ett bokmärke ur format 1 har inget UUID och får
 * ett nytt från [newId].
 */
fun BackupBookmark.toEntity(newId: () -> String = ::newBookmarkId) = Bookmark(
    bookmarkId = bookmarkId ?: newId(),
    issueId = issueId, pageNumber = pageNumber, title = title, note = note, createdAt = createdAt,
    updatedAt = updatedAt, pdfSize = pdfSize, pdfEtag = pdfEtag, pdfLastModified = pdfLastModified,
    deletedAt = deletedAt,
)

fun ReadingSession.toBackup() = BackupSession(
    issueId = issueId, startedAt = startedAt, endedAt = endedAt, startPage = startPage, endPage = endPage,
    pagesViewed = pagesViewed, durationSeconds = durationSeconds,
)

fun BackupSession.toEntity() = ReadingSession(
    issueId = issueId, startedAt = startedAt, endedAt = endedAt, startPage = startPage, endPage = endPage,
    pagesViewed = pagesViewed, durationSeconds = durationSeconds,
)
