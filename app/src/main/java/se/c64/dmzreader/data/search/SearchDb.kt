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

/** En sida i indexet: rad-id i pages och pages_fts, nummer och PDF-sidindex. */
data class IndexedPage(val rowId: Long, val issueId: String, val pageNumber: Int)

/** En träff i rangordningen. Lägre [score] är bättre. */
data class RankedRow(val rowId: Long, val score: Double)

/** Ett nummer i indexet, med storlek och ETag för den PDF som indexerades. */
data class IndexedIssue(val issueId: String, val pageCount: Int, val ocrPdfSize: Long?, val ocrPdfEtag: String?)

/** Markörer kring träffarna i ett textutdrag. Tecknen förekommer inte i tidningarnas text. */
const val SNIPPET_OPEN = "\u0002"
const val SNIPPET_CLOSE = "\u0003"

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

    /** Som [query], men med parametrar. */
    internal fun <T> query(
        sql: String,
        bind: (androidx.sqlite.SQLiteStatement) -> Unit,
        row: (androidx.sqlite.SQLiteStatement) -> T,
    ): List<T> {
        val rows = ArrayList<T>()
        connection.prepare(sql).use { statement ->
            bind(statement)
            while (statement.step()) rows += row(statement)
        }
        return rows
    }

    // --- Fulltextsökningen i FAS 9: rangordning och textutdrag i två steg ---

    /** Varje sidas rad-id med nummer och sida. Läses en gång när indexet öppnas. */
    fun pageIndex(): List<IndexedPage> =
        query("SELECT id, issueId, pageNumber FROM pages") {
            IndexedPage(it.getLong(0), it.getText(1), it.getLong(2).toInt())
        }

    /**
     * Steg 1: alla träffar i rangordning, utan textutdrag och utan att läsa texten.
     * [keepGoing] frågas med jämna mellanrum; svarar den nej avbryts läsningen.
     */
    fun rank(match: String, keepGoing: () -> Boolean = { true }): List<RankedRow> {
        val rows = ArrayList<RankedRow>()
        connection.prepare("SELECT rowid, rank FROM pages_fts WHERE pages_fts MATCH ? ORDER BY rank").use { statement ->
            statement.bindText(1, match)
            while (statement.step()) {
                rows += RankedRow(statement.getLong(0), statement.getDouble(1))
                if (rows.size % 512 == 0 && !keepGoing()) break
            }
        }
        return rows
    }

    /**
     * Steg 2: textutdraget för en enda rad. Träffarna står mellan [SNIPPET_OPEN] och [SNIPPET_CLOSE].
     * null om raden inte matchar frågan.
     */
    fun snippet(rowId: Long, match: String): String? =
        query(
            "SELECT snippet(pages_fts, 0, '$SNIPPET_OPEN', '$SNIPPET_CLOSE', '…', 14) FROM pages_fts " +
                "WHERE pages_fts MATCH ? AND rowid = ?",
            bind = {
                it.bindText(1, match)
                it.bindLong(2, rowId)
            },
        ) { it.getText(0) }.firstOrNull()

    /** PDF:erna som indexet byggdes av (planens D2). */
    fun indexedIssues(): List<IndexedIssue> =
        query("SELECT issueId, pageCount, ocrPdfSize, ocrPdfEtag FROM issues") {
            IndexedIssue(
                issueId = it.getText(0),
                pageCount = it.getLong(1).toInt(),
                ocrPdfSize = if (it.isNull(2)) null else it.getLong(2),
                ocrPdfEtag = if (it.isNull(3)) null else it.getText(3),
            )
        }

    /** SQLites egen kontroll av filen. "ok" betyder att den är hel. */
    fun quickCheck(): String = queryString("PRAGMA quick_check(1)")

    fun tableNames(): Set<String> =
        query("SELECT name FROM sqlite_master WHERE type = 'table'") { it.getText(0) }.toSet()

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
