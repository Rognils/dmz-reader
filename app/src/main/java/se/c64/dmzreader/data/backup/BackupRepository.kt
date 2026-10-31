package se.c64.dmzreader.data.backup

import se.c64.dmzreader.data.db.BackupDao
import se.c64.dmzreader.data.db.BookmarkDao
import se.c64.dmzreader.data.db.IssueUserStateDao
import se.c64.dmzreader.data.db.ReadingSessionDao
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Export och import av användarens data (planens E11, ADR 0012):
 * IssueUserState, Bookmark och ReadingSession. PDF:er, cache, katalogen och
 * IssueLocalState ingår inte.
 */
class BackupRepository(
    private val userStateDao: IssueUserStateDao,
    private val bookmarkDao: BookmarkDao,
    private val sessionDao: ReadingSessionDao,
    private val backupDao: BackupDao,
    private val appVersion: String,
    private val now: () -> Long = System::currentTimeMillis,
    /** Körs inne i importens transaktion, efter skrivningarna. För tester av återställning vid fel. */
    private val afterWrite: suspend () -> Unit = {},
) {
    suspend fun currentData() = backupDao.inTransaction {
        UserData(userStateDao.getAll(), bookmarkDao.getAll(), sessionDao.getAll())
    }

    /** Hela backupen som JSON-text. */
    suspend fun export(): String {
        val data = currentData()
        return encodeBackup(
            BackupFile(
                backupFormat = BACKUP_FORMAT,
                exportedAt = Instant.ofEpochMilli(now()).toString(),
                appVersion = appVersion,
                issueUserStates = data.userStates.map { it.toBackup() },
                bookmarks = data.bookmarks.map { it.toBackup() },
                readingSessions = data.sessions.map { it.toBackup() },
            ),
        )
    }

    /** Vad en import skulle göra, utan att något skrivs. Visas för användaren före importen. */
    suspend fun preview(backup: BackupFile): ImportSummary = planImport(currentData(), backup).summary

    /**
     * Importerar backupen. Allt skrivs i en transaktion: går något fel har
     * ingenting ändrats, och felet kastas vidare. Import tar aldrig bort något.
     */
    suspend fun import(backup: BackupFile): ImportSummary = backupDao.inTransaction {
        val before = currentData()
        val plan = planImport(before, backup)
        backupDao.applyImport(
            userStatesToInsert = plan.userStatesToInsert,
            userStatesToUpdate = plan.userStatesToUpdate,
            bookmarksToInsert = plan.bookmarksToInsert,
            bookmarksToUpdate = plan.bookmarksToUpdate,
            sessionsToInsert = plan.sessionsToInsert,
            verify = {
                afterWrite()
                // Antalet rader ska vara det gamla plus det nya, aldrig mindre.
                val after = currentData()
                check(after.userStates.size == before.userStates.size + plan.userStatesToInsert.size) { "antalet nummer stämmer inte" }
                check(after.bookmarks.size == before.bookmarks.size + plan.bookmarksToInsert.size) { "antalet bokmärken stämmer inte" }
                check(after.sessions.size == before.sessions.size + plan.sessionsToInsert.size) { "antalet sessioner stämmer inte" }
            },
        )
        plan.summary
    }

    companion object {
        private val fileDate = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        /** Föreslaget filnamn: dmz-reader-backup-ÅÅÅÅ-MM-DD.json. */
        fun fileName(timeMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
            "dmz-reader-backup-${fileDate.format(Instant.ofEpochMilli(timeMillis).atZone(zone))}.json"
    }
}
