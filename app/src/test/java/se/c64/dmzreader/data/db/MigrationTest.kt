package se.c64.dmzreader.data.db

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Migreringen från databasversion 1 (FAS 1) till 2 (Issue.coverAsset).
 * Version 1 byggs upp ur det exporterade schemat, precis som den såg ut.
 */
class MigrationTest {
    private val schemaDirectory =
        File(System.getProperty("dmz.schemaDir") ?: error("dmz.schemaDir saknas"))

    private fun createVersion1(file: File) {
        val schema = Json.parseToJsonElement(File(schemaDirectory, "1.json").readText())
            .jsonObject.getValue("database").jsonObject
        BundledSQLiteDriver().open(file.path).use { connection ->
            for (entity in schema.getValue("entities").jsonArray) {
                val table = entity.jsonObject.getValue("tableName").jsonPrimitive.content
                val createSql = entity.jsonObject.getValue("createSql").jsonPrimitive.content
                connection.execSQL(createSql.replace("\${TABLE_NAME}", table))
                for (index in entity.jsonObject["indices"]?.jsonArray.orEmpty()) {
                    connection.execSQL(
                        index.jsonObject.getValue("createSql").jsonPrimitive.content
                            .replace("\${TABLE_NAME}", table),
                    )
                }
            }
            for (query in schema.getValue("setupQueries").jsonArray) {
                connection.execSQL(query.jsonPrimitive.content)
            }
            connection.execSQL("PRAGMA user_version = 1")
            // En katalogpost och användardata, så som de kunde se ut i FAS 1.
            connection.execSQL(
                "INSERT INTO Issue (issueId, publicationId, publicationTitle, year, issueLabel, sortKey, " +
                    "sourceKey, coverFlags, flags) VALUES ('datormagazin-1989-06', 'datormagazin', " +
                    "'Datormagazin', 1989, 'Nr 6', '1989-06', '198906', '[]', '[\"x\"]')",
            )
            connection.execSQL(
                "INSERT INTO IssueUserState (issueId, isFavorite, lastPage, maxPageReached, " +
                    "readStatusOverride, updatedAt) VALUES ('datormagazin-1989-06', 1, 12, 30, 'UNREAD', 2000)",
            )
            connection.execSQL(
                "INSERT INTO Bookmark (issueId, pageNumber, title, createdAt, updatedAt) " +
                    "VALUES ('datormagazin-1989-06', 14, 'Annons', 1500, 1500)",
            )
            connection.execSQL(
                "INSERT INTO ReadingSession (issueId, startedAt, endedAt, startPage, endPage, " +
                    "pagesViewed, durationSeconds) VALUES ('datormagazin-1989-06', 1000, 1600, 1, 30, 30, 600)",
            )
            connection.execSQL(
                "INSERT INTO IssueLocalState (issueId, observedPageCount, downloadState) " +
                    "VALUES ('datormagazin-1989-06', 48, 'DOWNLOADED')",
            )
        }
    }

    @Test
    fun `migration from version 1 keeps all user data`() = runTest {
        val file = File.createTempFile("dmz-migration", ".db").apply { delete() }
        try {
            createVersion1(file)

            val db = Room.databaseBuilder<DmzDatabase>(file.path)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
            try {
                val issue = db.issueDao().get("datormagazin-1989-06")!!
                assertNull(issue.coverAsset)
                assertEquals(listOf("x"), issue.flags)

                assertEquals(
                    listOf(
                        IssueUserState(
                            issueId = "datormagazin-1989-06", isFavorite = true, lastPage = 12,
                            maxPageReached = 30, readStatusOverride = ReadStatusOverride.UNREAD,
                            updatedAt = 2000,
                        ),
                    ),
                    db.issueUserStateDao().getAll(),
                )
                assertEquals("Annons", db.bookmarkDao().getAll().single().title)
                assertEquals(600, db.readingSessionDao().getAll().single().durationSeconds)
                assertEquals(DownloadState.DOWNLOADED, db.issueLocalStateDao().getAll().single().downloadState)
            } finally {
                db.close()
            }
        } finally {
            file.delete()
            File(file.path + "-wal").delete()
            File(file.path + "-shm").delete()
        }
    }
}
