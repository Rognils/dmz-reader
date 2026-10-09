package se.c64.dmzreader.data.search

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/** Det appen visar om det importerade sökindexet (planens D2). */
data class SearchIndexInfo(
    val inventoryDatasetVersion: String,
    val normalizerVersion: String?,
    val tokenizer: String?,
    val issueCount: Int,
    val pageCount: Int,
    val sizeBytes: Long,
    /** När filen importerades, i millisekunder. */
    val importedAt: Long,
)

/** Ett öppnat sökindex: det som behövs för att tolka rangordningen utan att fråga databasen igen. */
class LoadedSearchIndex internal constructor(
    val info: SearchIndexInfo,
    /** Rad-id → nummer och sida. */
    val pages: Map<Long, IndexedPage>,
    /** PDF:erna som indexet byggdes av. */
    val issues: List<IndexedIssue>,
) {
    val issueIds: Set<String> = issues.mapTo(HashSet()) { it.issueId }
}

/** Ett fel vid import, med ett meddelande som kan visas för användaren. */
class SearchIndexException(message: String) : Exception(message)

/** Förloppet i kopieringen. [totalBytes] är null när filens storlek inte är känd. */
data class CopyProgress(val copiedBytes: Long, val totalBytes: Long?) {
    val percent: Int? get() = totalBytes?.takeIf { it > 0 }?.let { ((copiedBytes * 100) / it).toInt().coerceIn(0, 100) }
}

/** "62 av 149 MB · 41 %", eller "62 MB" när storleken inte är känd. */
fun copyProgressText(progress: CopyProgress): String {
    fun megabytes(bytes: Long) = (bytes + 500_000) / 1_000_000
    val total = progress.totalBytes ?: return "${megabytes(progress.copiedBytes)} MB"
    return "${megabytes(progress.copiedBytes)} av ${megabytes(total)} MB · ${progress.percent} %"
}

/**
 * Sökindexet i filesDir/data/search.db (planens D2 och D5). Det följer inte med
 * appen utan importeras av användaren.
 *
 * En import går i steg: filen kopieras till search.db.part, kontrolleras, och
 * byter först därefter plats med den aktiva filen. Den tidigare filen ligger
 * kvar tills den nya har öppnats. Går något fel gäller den tidigare filen.
 *
 * Databasen är skrivskyddad och helt skild från appens Room-databas. Alla
 * frågor går genom en enda tråd, eftersom en anslutning inte får delas.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchIndexManager(
    private val directory: File,
    private val freeBytes: () -> Long = { directory.usableSpace },
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    /** Sant om sökorden får skrivas i loggen. Bara i debug-bygget; annars loggas bara svarstiden. */
    private val logQueries: Boolean = false,
    private val log: (String) -> Unit = {},
) {
    private val queries = dispatcher.limitedParallelism(1)
    private val lock = Mutex()
    private val activeFile get() = File(directory, ACTIVE_NAME)
    private val previousFile get() = File(directory, "$ACTIVE_NAME.previous")
    private val partFile get() = File(directory, "$ACTIVE_NAME.part")

    private var db: SearchDb? = null
    private val _index = MutableStateFlow<LoadedSearchIndex?>(null)

    /** Det öppnade indexet, eller null om inget är importerat. */
    val index: StateFlow<LoadedSearchIndex?> = _index

    /** Öppnar det importerade indexet när appen startar, och städar efter en import som avbröts. */
    suspend fun loadAtStartup() = lock.withLock {
        withContext(queries) {
            partFile.delete()
            if (previousFile.isFile) {
                // Bytet hann göras men inte bekräftas. Den tidigare filen gäller.
                activeFile.delete()
                previousFile.renameTo(activeFile)
            }
            if (activeFile.isFile && db == null) {
                try {
                    val started = System.nanoTime()
                    open(activeFile)
                    log("Sökindex öppnat på %.1f ms: %d sidor".format((System.nanoTime() - started) / 1e6, _index.value?.pages?.size ?: 0))
                } catch (e: Exception) {
                    log("Sökindexet går inte att öppna (${e.message}). Det används inte.")
                }
            }
        }
    }

    private fun open(file: File) {
        val opened = SearchDb.openReadOnly(file.path)
        try {
            val loaded = describe(opened, file)
            db = opened
            _index.value = loaded
        } catch (e: Exception) {
            opened.close()
            throw e
        }
    }

    private fun describe(db: SearchDb, file: File): LoadedSearchIndex {
        val meta = db.meta()
        val pages = db.pageIndex()
        val issues = db.indexedIssues()
        return LoadedSearchIndex(
            info = SearchIndexInfo(
                inventoryDatasetVersion = meta["inventoryDatasetVersion"] ?: "okänd",
                normalizerVersion = meta["normalizerVersion"],
                tokenizer = meta["tokenizer"],
                issueCount = issues.size,
                pageCount = pages.size,
                sizeBytes = file.length(),
                importedAt = file.lastModified(),
            ),
            pages = pages.associateBy { it.rowId },
            issues = issues,
        )
    }

    private fun close() {
        db?.close()
        db = null
        _index.value = null
    }

    /**
     * Importerar ett sökindex. [onProgress] anropas under kopieringen.
     * Kastar [SearchIndexException] om filen inte går att använda; då är det aktiva indexet orört.
     */
    suspend fun import(
        open: () -> InputStream,
        totalBytes: Long?,
        onProgress: (CopyProgress) -> Unit = {},
    ): SearchIndexInfo = lock.withLock {
        withContext(Dispatchers.IO) {
            directory.mkdirs()
            val part = partFile
            part.delete()
            try {
                // Under bytet finns både den gamla och den nya filen.
                if (totalBytes != null && freeBytes() < totalBytes + FREE_MARGIN_BYTES) {
                    throw SearchIndexException(
                        "Det finns inte plats för sökindexet. Det behövs ${(totalBytes + FREE_MARGIN_BYTES) / 1_000_000} MB ledigt.",
                    )
                }
                copy(open, part, totalBytes, onProgress)
                validate(part)
            } catch (e: SearchIndexException) {
                part.delete()
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                part.delete()
                throw e
            } catch (e: Exception) {
                part.delete()
                throw SearchIndexException("Filen gick inte att kopiera: ${e.message ?: e.javaClass.simpleName}")
            }
            withContext(queries) { swap(part) }
        }
    }

    private suspend fun copy(open: () -> InputStream, target: File, totalBytes: Long?, onProgress: (CopyProgress) -> Unit) {
        var copied = 0L
        var reported = 0L
        onProgress(CopyProgress(0, totalBytes))
        open().use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    if (copied - reported >= PROGRESS_STEP_BYTES) {
                        reported = copied
                        onProgress(CopyProgress(copied, totalBytes))
                    }
                }
                output.fd.sync()
            }
        }
        onProgress(CopyProgress(copied, totalBytes ?: copied))
    }

    /** Kontrollerar att filen är ett helt sökindex i ett format appen förstår. */
    private fun validate(file: File) {
        val candidate = try {
            SearchDb.openReadOnly(file.path)
        } catch (e: Exception) {
            throw SearchIndexException("Filen är inte ett sökindex: den går inte att öppna som en databas.")
        }
        candidate.use { db ->
            val check = try {
                db.quickCheck()
            } catch (e: Exception) {
                throw SearchIndexException("Filen är inte ett sökindex: den går inte att läsa som en databas.")
            }
            if (check != "ok") throw SearchIndexException("Filen är skadad (SQLite: $check). Kopiera den till telefonen igen.")
            val tables = db.tableNames()
            for (table in REQUIRED_TABLES) {
                if (table !in tables) {
                    throw SearchIndexException("Filen saknar tabellen $table och är inte ett sökindex för DMZ Reader.")
                }
            }
            val meta = db.meta()
            val schemaVersion = meta["searchSchemaVersion"]
                ?: throw SearchIndexException("Filen saknar searchSchemaVersion och är inte ett sökindex för DMZ Reader.")
            if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
                throw SearchIndexException(
                    "Sökindexet har searchSchemaVersion $schemaVersion. Den här versionen av appen stöder bara " +
                        "$SUPPORTED_SCHEMA_VERSION. Uppdatera appen eller bygg om sökindexet.",
                )
            }
            try {
                db.rank("\"a\"")
                if (db.indexedIssues().isEmpty() || db.pageIndex().isEmpty()) {
                    throw SearchIndexException("Sökindexet är tomt: det innehåller inga nummer eller inga sidor.")
                }
            } catch (e: SearchIndexException) {
                throw e
            } catch (e: Exception) {
                throw SearchIndexException("Sökindexet går inte att söka i: ${e.message}")
            }
        }
    }

    /** Byter aktiv fil. Den tidigare ligger kvar tills den nya har öppnats. */
    private fun swap(part: File): SearchIndexInfo {
        val active = activeFile
        val previous = previousFile
        close()
        previous.delete()
        val hadPrevious = active.isFile
        if (hadPrevious && !active.renameTo(previous)) {
            part.delete()
            open(active)
            throw SearchIndexException("Det tidigare sökindexet gick inte att flytta undan. Det gäller fortfarande.")
        }
        try {
            if (!part.renameTo(active)) error("den nya filen gick inte att lägga på plats")
            active.setLastModified(now())
            open(active)
        } catch (e: Exception) {
            active.delete()
            part.delete()
            if (hadPrevious) {
                previous.renameTo(active)
                open(active)
            }
            throw SearchIndexException("Importen misslyckades: ${e.message}. " + if (hadPrevious) "Det tidigare sökindexet gäller fortfarande." else "")
        }
        previous.delete()
        val info = _index.value!!.info
        log("Sökindex importerat: ${info.issueCount} nummer, ${info.pageCount} sidor, ${info.sizeBytes} byte")
        return info
    }

    /** Tar bort sökindexet. Sökningen går då tillbaka till metadata. */
    suspend fun remove() = lock.withLock {
        withContext(queries) {
            close()
            activeFile.delete()
            previousFile.delete()
            partFile.delete()
            log("Sökindexet togs bort")
        }
    }

    /**
     * Steg 1 i sökningen: alla träffar i rangordning. null om inget index finns.
     * Avbryts när den anropande korutinen avbryts.
     */
    suspend fun rank(match: String): List<RankedRow>? = withContext(queries) {
        val context = coroutineContext
        val started = System.nanoTime()
        db?.rank(match) { context.isActive }?.also { rows ->
            context.ensureActive()
            log("Sökning%s: %d sidor på %.1f ms".format(if (logQueries) " $match" else "", rows.size, (System.nanoTime() - started) / 1e6))
        }
    }

    /** Steg 2: textutdraget för en enda träff. */
    suspend fun snippet(rowId: Long, match: String): String? = withContext(queries) { db?.snippet(rowId, match) }

    companion object {
        const val ACTIVE_NAME = "search.db"
        const val SUPPORTED_SCHEMA_VERSION = "1"
        private val REQUIRED_TABLES = listOf("meta", "issues", "pages", "pages_fts")
        private const val PROGRESS_STEP_BYTES = 1_000_000L
        private const val FREE_MARGIN_BYTES = 50_000_000L
    }
}
