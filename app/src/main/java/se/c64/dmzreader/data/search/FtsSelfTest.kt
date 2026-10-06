package se.c64.dmzreader.data.search

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class FtsCaseResult(
    val name: String,
    val query: String,
    val issueFilter: String?,
    val passed: Boolean,
    val expected: List<String>,
    val actual: List<String>,
    val topSnippet: String?,
    val detail: String?,
)

data class FtsSelfTestResult(
    val passed: Boolean,
    val sqliteVersion: String?,
    val tokenizer: String?,
    val readOnlyEnforced: Boolean,
    val cases: List<FtsCaseResult>,
    /** Sätts när testet inte gick att genomföra alls. */
    val error: String? = null,
) {
    val summary: String
        get() = when {
            error != null -> error
            else -> "${cases.count { it.passed }} av ${cases.size} sökningar gav rätt träffar"
        }
}

/**
 * Verifierar att ett förgenererat FTS5-index går att öppna skrivskyddat och
 * söka i med den paketerade SQLite-drivrutinen (ADR 0006).
 *
 * Testdatabasen (tools/build_fts_testdb.py) bär sina egna förväntningar i
 * tabellen selftest, så samma kontroll körs på datorn och på telefonen.
 */
object FtsSelfTest {

    @Serializable
    private data class Expectation(
        val hits: List<String>,
        val top: String? = null,
        val snippetContains: String? = null,
    )

    private class Case(
        val name: String,
        val query: String,
        val issueFilter: String?,
        val expectation: Expectation,
    )

    fun run(path: String): FtsSelfTestResult {
        val db = try {
            SearchDb.openReadOnly(path)
        } catch (e: Exception) {
            return failure("Testdatabasen gick inte att öppna: ${e.message}")
        }
        return try {
            db.use { run(it) }
        } catch (e: Exception) {
            failure("Testet avbröts: ${e.message}")
        }
    }

    private fun run(db: SearchDb): FtsSelfTestResult {
        val meta = db.meta()
        val cases = db.query(
            "SELECT name, query, issueFilter, expected FROM selftest ORDER BY id"
        ) { statement ->
            Case(
                name = statement.getText(0),
                query = statement.getText(1),
                issueFilter = if (statement.isNull(2)) null else statement.getText(2),
                expectation = Json.decodeFromString<Expectation>(statement.getText(3)),
            )
        }
        val results = cases.map { runCase(db, it) }
        val readOnly = isReadOnly(db)
        return FtsSelfTestResult(
            passed = results.isNotEmpty() && results.all { it.passed } && readOnly,
            sqliteVersion = db.sqliteVersion(),
            tokenizer = meta["tokenizer"],
            readOnlyEnforced = readOnly,
            cases = results,
        )
    }

    private fun runCase(db: SearchDb, case: Case): FtsCaseResult {
        val hits = try {
            db.search(case.query, case.issueFilter)
        } catch (e: Exception) {
            return FtsCaseResult(
                case.name, case.query, case.issueFilter, false,
                case.expectation.hits, emptyList(), null, "Frågan gav fel: ${e.message}",
            )
        }
        val actual = hits.map { "${it.issueId}:${it.pageNumber}" }
        val expected = case.expectation
        val problems = ArrayList<String>()
        if (actual.sorted() != expected.hits.sorted()) problems += "fel träffar"
        if (expected.top != null && actual.firstOrNull() != expected.top) {
            problems += "fel rankning (bäst: ${actual.firstOrNull()}, väntat: ${expected.top})"
        }
        if (hits.zipWithNext().any { (a, b) -> a.score > b.score }) problems += "bm25 är inte sorterad"
        val topSnippet = hits.firstOrNull()?.snippet
        if (expected.snippetContains != null && topSnippet?.contains(expected.snippetContains) != true) {
            problems += "utdraget saknar ${expected.snippetContains}"
        }
        return FtsCaseResult(
            name = case.name,
            query = case.query,
            issueFilter = case.issueFilter,
            passed = problems.isEmpty(),
            expected = expected.hits,
            actual = actual,
            topSnippet = topSnippet,
            detail = problems.joinToString("; ").ifEmpty { null },
        )
    }

    /** En skrivskyddad anslutning ska vägra skriva. */
    private fun isReadOnly(db: SearchDb): Boolean = try {
        db.execute("CREATE TABLE write_probe (x)")
        false
    } catch (e: Exception) {
        true
    }

    private fun failure(message: String) = FtsSelfTestResult(
        passed = false, sqliteVersion = null, tokenizer = null,
        readOnlyEnforced = false, cases = emptyList(), error = message,
    )
}
