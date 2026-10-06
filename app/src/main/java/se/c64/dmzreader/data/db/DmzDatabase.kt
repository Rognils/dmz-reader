package se.c64.dmzreader.data.db

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.ColumnTypeConverters
import androidx.room3.useReaderConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

/**
 * Appens databas. Room 3 med den paketerade SQLite-drivrutinen, så att
 * SQLite-versionen (och FTS5) är densamma på alla telefoner.
 */
@Database(
    entities = [
        IssueEntity::class,
        IssueLocalState::class,
        IssueUserState::class,
        Bookmark::class,
        ReadingSession::class,
    ],
    version = 1,
    exportSchema = true,
)
@ColumnTypeConverters(Converters::class)
abstract class DmzDatabase : RoomDatabase() {
    abstract fun issueDao(): IssueDao
    abstract fun issueLocalStateDao(): IssueLocalStateDao
    abstract fun issueUserStateDao(): IssueUserStateDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun readingSessionDao(): ReadingSessionDao

    companion object {
        const val FILE_NAME = "dmz-reader.db"

        fun create(context: Context): DmzDatabase =
            Room.databaseBuilder<DmzDatabase>(context.applicationContext, FILE_NAME)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
    }
}

/** SQLite-versionen hos drivrutinen som databasen faktiskt kör på. */
suspend fun RoomDatabase.sqliteVersion(): String = useReaderConnection { connection ->
    connection.usePrepared("SELECT sqlite_version()") { statement ->
        statement.step()
        statement.getText(0)
    }
}
