package se.c64.dmzreader.testing

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import se.c64.dmzreader.data.db.DmzDatabase
import se.c64.dmzreader.data.db.IssueEntity
import se.c64.dmzreader.data.settings.ActiveInventory
import se.c64.dmzreader.data.settings.ActiveInventoryStore
import java.io.File

/** Sökvägar som Gradle skickar in till testerna (app/build.gradle.kts). */
object TestFiles {
    val inventory: File get() = fileFromProperty("dmz.inventoryPath")
    val ftsTestDb: File get() = fileFromProperty("dmz.ftsTestDb")

    private fun fileFromProperty(name: String): File {
        val path = System.getProperty(name) ?: error("Systemegenskapen $name saknas. Kör testerna via Gradle.")
        return File(path).also { check(it.isFile) { "$path saknas" } }
    }
}

/**
 * Samma databas som appen använder: Room 3 med BundledSQLiteDriver, men i
 * minnet. Room 3 har en byggare som inte kräver någon Android-kontext.
 */
fun inMemoryDatabase(): DmzDatabase =
    Room.inMemoryDatabaseBuilder<DmzDatabase>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

class FakeActiveInventoryStore(initial: ActiveInventory? = null) : ActiveInventoryStore {
    private val state = MutableStateFlow(initial)
    var writes = 0
        private set

    override fun observe(): Flow<ActiveInventory?> = state
    override suspend fun get(): ActiveInventory? = state.value
    override suspend fun set(value: ActiveInventory) {
        writes++
        state.value = value
    }
}

/** Ett litet inventory i samma format som toolchainen skriver. */
fun inventoryJson(
    datasetVersion: String = "2026-10-06-002",
    schemaVersion: Int = 1,
    issues: List<String> = listOf(issueJson("datormagazin-1989-06")),
): String = """
    {
      "schemaVersion": $schemaVersion,
      "datasetVersion": "$datasetVersion",
      "generatedAt": "2026-10-06T00:00:00Z",
      "generator": "test",
      "publications": [{"publicationId": "datormagazin", "publicationTitle": "Datormagazin"}],
      "issues": [${issues.joinToString(",")}]
    }
""".trimIndent()

fun issueJson(
    issueId: String,
    coverUrl: String? = "https://dmzarkivet.se/magazines/198906/DMZ_1989-06-001.jpg",
    ocrPdfUrl: String? = "https://dmzarkivet.se/pdf/198906.pdf",
    flags: List<String> = emptyList(),
    extra: String = "",
): String {
    fun quoted(value: String?) = value?.let { "\"$it\"" } ?: "null"
    val year = issueId.split("-")[1]
    val number = issueId.split("-")[2]
    return """
        {
          "issueId": "$issueId",
          "publicationId": "datormagazin",
          "publicationTitle": "Datormagazin",
          "year": $year,
          "issueLabel": "Nr ${number.trimStart('0')}",
          "sortKey": "$year-$number",
          "sourceKey": "$year$number",
          "sourceNames": ["OCR PDF/$year$number.pdf"],
          "relatedIssueId": null,
          "coverUrl": ${quoted(coverUrl)},
          "coverFlags": [],
          "ocrPdfUrl": ${quoted(ocrPdfUrl)},
          "ocrPdfSize": ${if (ocrPdfUrl == null) "null" else "25672804"},
          "ocrPdfEtag": null,
          "ocrPdfLastModified": null,
          "largePdfUrl": null,
          "largePdfSize": null,
          "hqSource": "scans",
          "scanPagePattern": null,
          "scanPageCount": 48,
          "pageCount": null,
          "spreadOffset": null,
          "flags": [${flags.joinToString(",") { "\"$it\"" }}]$extra
        }
    """.trimIndent()
}

/** En katalogpost för tester av visningslogik. issueId styr år och nummer. */
fun issueEntity(
    issueId: String,
    publicationId: String = "datormagazin",
    publicationTitle: String = "Datormagazin",
    issueLabel: String = "Nr ${issueId.split("-")[2].trimStart('0')}",
    relatedIssueId: String? = null,
    coverUrl: String? = "https://dmzarkivet.se/magazines/x/001.jpg",
    coverFlags: List<String> = emptyList(),
    ocrPdfUrl: String? = "https://dmzarkivet.se/pdf/x.pdf",
    flags: List<String> = emptyList(),
): IssueEntity {
    val parts = issueId.split("-")
    return IssueEntity(
        issueId = issueId,
        publicationId = publicationId,
        publicationTitle = publicationTitle,
        year = parts[1].toInt(),
        issueLabel = issueLabel,
        sortKey = "${parts[1]}-${parts[2]}",
        sourceKey = "${parts[1]}${parts[2]}",
        relatedIssueId = relatedIssueId,
        coverUrl = coverUrl,
        coverFlags = coverFlags,
        ocrPdfUrl = ocrPdfUrl,
        ocrPdfSize = if (ocrPdfUrl == null) null else 25_672_804,
        ocrPdfEtag = null,
        ocrPdfLastModified = null,
        largePdfUrl = null,
        largePdfSize = null,
        hqSource = "scans",
        scanPagePattern = null,
        scanPageCount = 48,
        pageCount = null,
        spreadOffset = null,
        flags = flags,
    )
}
