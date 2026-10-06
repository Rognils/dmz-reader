package se.c64.dmzreader.data.repository

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.c64.dmzreader.data.db.DmzDatabase
import se.c64.dmzreader.data.db.sqliteVersion
import se.c64.dmzreader.data.search.FtsSelfTest
import se.c64.dmzreader.data.search.FtsSelfTestResult
import se.c64.dmzreader.data.search.FtsTestDatabase

class DiagnosticsRepository(
    private val context: Context,
    private val database: DmzDatabase,
) {
    suspend fun sqliteVersion(): String = database.sqliteVersion()

    /** Kopierar testindexet till filesDir/data/ och kör självtestet mot det. */
    suspend fun runFtsSelfTest(): FtsSelfTestResult = withContext(Dispatchers.IO) {
        val file = try {
            FtsTestDatabase.install(context)
        } catch (e: Exception) {
            return@withContext unavailable("Testindexet gick inte att kopiera: ${e.message}")
        }
        if (file == null) {
            unavailable("Testindexet följer bara med i debug-byggen.")
        } else {
            FtsSelfTest.run(file.path)
        }
    }

    private fun unavailable(message: String) = FtsSelfTestResult(
        passed = false, sqliteVersion = null, tokenizer = null,
        readOnlyEnforced = false, cases = emptyList(), error = message,
    )
}
