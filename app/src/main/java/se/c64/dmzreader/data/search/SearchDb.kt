package se.c64.dmzreader.data.search

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY

data class SearchHit(
    val issueId: String,
    val pageNumber: Int,
    /** bm25-poäng. Lägre är bättre. */
    val score: Double,
    /** Utdrag ur searchText med träffen inom [ och ]. */
    val snippet: String,
)

/**
 * Ett sökindex i D2-formatet, öppnat skrivskyddat med den paketerade
 * SQLite-drivrutinen. Helt skilt från Room-databasen.
 *
 * En anslutning får inte användas från flera trådar samtidigt. Anroparen
 * ansvarar för det.
 */
class SearchDb private constructor(private val connection: SQLiteConnection) : AutoCloseable {

    fun sqliteVersion(): String = queryString("SELECT sqlite_version()")

    fun meta(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        connection.prepare("SELECT key, value FROM meta ORDER BY key").use { statement ->
            while (statement.step()) {
                result[statement.getText(0)] = statement.getText(1)
            }
        }
        return result
    }

    /**
     * Fulltextsökning. [query] är ett FTS5-uttryck. Träffarna kommer bäst först.
     * Med [issueId] begränsas sökningen till ett nummer.
     */
    fun search(query: String, issueId: String? = null, limit: Int = 50): List<SearchHit> {
        val sql = buildString {
            append("SELECT p.issueId, p.pageNumber, bm25(pages_fts) AS score, ")
            append("snippet(pages_fts, 0, '[', ']', '…', 12) ")
            append("FROM pages_fts JOIN pages p ON p.rowid = pages_fts.rowid ")
            append("WHERE pages_fts MATCH ? ")
            if (issueId != null) append("AND p.issueId = ? ")
            append("ORDER BY score, p.issueId, p.pageNumber LIMIT ?")
        }
        val hits = ArrayList<SearchHit>()
        connection.prepare(sql).use { statement ->
            var index = 1
            statement.bindText(index++, query)
            if (issueId != null) statement.bindText(index++, issueId)
            statement.bindLong(index, limit.toLong())
            while (statement.step()) {
                hits += SearchHit(
                    issueId = statement.getText(0),
                    pageNumber = statement.getLong(1).toInt(),
                    score = statement.getDouble(2),
                    snippet = statement.getText(3),
                )
            }
        }
        return hits
    }

    /** Kör en godtycklig sats. Används bara av självtestet. */
    internal fun execute(sql: String) {
        connection.prepare(sql).use { statement -> statement.step() }
    }

    internal fun <T> query(sql: String, row: (androidx.sqlite.SQLiteStatement) -> T): List<T> {
        val rows = ArrayList<T>()
        connection.prepare(sql).use { statement ->
            while (statement.step()) rows += row(statement)
        }
        return rows
    }

    private fun queryString(sql: String): String =
        connection.prepare(sql).use { statement ->
            statement.step()
            statement.getText(0)
        }

    override fun close() = connection.close()

    companion object {
        fun openReadOnly(path: String): SearchDb =
            SearchDb(BundledSQLiteDriver().open(path, SQLITE_OPEN_READONLY))
    }
}
