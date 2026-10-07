package se.c64.dmzreader.data.inventory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import se.c64.dmzreader.data.db.IssueEntity
import se.c64.dmzreader.data.settings.ActiveInventory
import se.c64.dmzreader.data.settings.ActiveInventoryStore
import se.c64.dmzreader.data.settings.InventorySource
import java.io.File
import java.io.InputStream

/** Skillnaden mellan det aktiva arkivindexet och ett nytt, så som den visas före en import. */
data class InventoryDiff(
    /** issueId som finns i det nya indexet men inte i det aktiva. */
    val added: List<String>,
    /** issueId som finns i det aktiva indexet men inte i det nya. */
    val removed: List<String>,
    /** issueId som finns i båda, men där någon uppgift skiljer sig. */
    val changed: List<String>,
) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()
}

fun diffInventories(current: List<IssueEntity>, new: List<IssueEntity>): InventoryDiff {
    val before = current.associateBy { it.issueId }
    val after = new.associateBy { it.issueId }
    return InventoryDiff(
        added = (after.keys - before.keys).sorted(),
        removed = (before.keys - after.keys).sorted(),
        changed = after.filter { (id, issue) -> before[id]?.let { it != issue } == true }.keys.sorted(),
    )
}

/** Ett validerat index som väntar på att användaren bekräftar importen. */
class StagedInventory internal constructor(
    internal val file: File,
    internal val issues: List<IssueEntity>,
    val datasetVersion: String,
    val schemaVersion: Int,
    val issueCount: Int,
    val diff: InventoryDiff,
    /** datasetVersion för det index som gäller nu, eller null om inget är inläst. */
    val activeDatasetVersion: String?,
)

/**
 * Arkivindexet appen använder (planens D4 och D5): det som följer med i assets,
 * eller ett som användaren har importerat till filesDir/data/inventory.json.
 *
 * En import går i steg: filen kopieras till en temporär fil i privat lagring,
 * valideras, och byter först därefter plats med den aktiva filen. Issue-tabellen
 * ersätts i en enda transaktion. Går något fel gäller den tidigare filen och den
 * tidigare tabellen. Användardata och IssueLocalState rörs aldrig.
 */
class InventoryManager(
    private val directory: File,
    private val activeInventory: ActiveInventoryStore,
    private val currentIssues: suspend () -> List<IssueEntity>,
    /** Ersätter hela Issue-tabellen i en transaktion. */
    private val replaceIssues: suspend (List<IssueEntity>) -> Unit,
    private val readBundled: () -> String,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private val lock = Mutex()
    private val activeFile get() = File(directory, ACTIVE_NAME)
    private val previousFile get() = File(directory, "$ACTIVE_NAME.previous")
    private val stagedFile get() = File(directory, "$ACTIVE_NAME.tmp")

    /** Sant om ett importerat index ligger i privat lagring. */
    fun hasImportedFile(): Boolean = activeFile.isFile

    /**
     * Läser in rätt index när appen startar: det importerade om det finns och
     * är giltigt, annars det inbyggda.
     */
    suspend fun loadAtStartup(): InventoryLoadResult = lock.withLock {
        withContext(Dispatchers.IO) {
            // Rester efter en import som avbröts mitt i.
            stagedFile.delete()
            if (previousFile.isFile) {
                // Bytet hann göras men inte bekräftas. Den tidigare filen gäller.
                if (activeFile.isFile) activeFile.delete()
                previousFile.renameTo(activeFile)
            }
            if (activeFile.isFile) {
                val imported = try {
                    InventoryParser.parse(activeFile.readText())
                } catch (e: Exception) {
                    log("Det importerade arkivindexet går inte att läsa (${e.message}). Det inbyggda används.")
                    null
                }
                if (imported != null) {
                    return@withContext activate(imported, InventorySource.IMPORTED, importedAt = activeFile.lastModified())
                }
                activeFile.delete()
            }
            loadBundled()
        }
    }

    private suspend fun loadBundled(): InventoryLoadResult {
        val bundled = try {
            InventoryParser.parse(readBundled())
        } catch (e: InventoryException) {
            return InventoryLoadResult.Rejected(e.message ?: "Okänt fel i arkivindexet.")
        } catch (e: Exception) {
            return InventoryLoadResult.Rejected("Arkivindexet gick inte att läsa in: ${e.message}")
        }
        return activate(bundled, InventorySource.BUNDLED, importedAt = null)
    }

    /** Skriver indexet till Issue-tabellen om det inte redan är det som ligger där. */
    private suspend fun activate(inventory: InventoryDto, source: InventorySource, importedAt: Long?): InventoryLoadResult {
        val active = activeInventory.get()
        val storedCount = currentIssues().size
        // En tom tabell läses alltid in, även om versionen råkar stämma.
        if (active?.datasetVersion == inventory.datasetVersion && active.source == source && storedCount > 0) {
            return InventoryLoadResult.AlreadyActive(inventory.datasetVersion, storedCount)
        }
        replaceIssues(inventory.issues.map { it.toEntity() })
        activeInventory.set(ActiveInventory(inventory.datasetVersion, inventory.schemaVersion, source, importedAt))
        return InventoryLoadResult.Replaced(inventory.datasetVersion, inventory.issues.size)
    }

    /**
     * Första steget i en import: kopierar filen till privat lagring och validerar
     * den. Ingenting i appen ändras. Kastar [InventoryException] om filen inte
     * är ett giltigt arkivindex.
     */
    suspend fun stage(open: () -> InputStream): StagedInventory = lock.withLock {
        withContext(Dispatchers.IO) {
            directory.mkdirs()
            val staged = stagedFile
            try {
                open().use { input -> staged.outputStream().use { output -> input.copyTo(output) } }
                if (staged.length() > MAX_BYTES) {
                    throw InventoryException.Malformed("filen är för stor för att vara ett arkivindex")
                }
                val inventory = InventoryParser.parse(staged.readText())
                val issues = inventory.issues.map { it.toEntity() }
                StagedInventory(
                    file = staged,
                    issues = issues,
                    datasetVersion = inventory.datasetVersion,
                    schemaVersion = inventory.schemaVersion,
                    issueCount = issues.size,
                    diff = diffInventories(currentIssues(), issues),
                    activeDatasetVersion = activeInventory.get()?.datasetVersion,
                )
            } catch (e: Exception) {
                staged.delete()
                throw e
            }
        }
    }

    /** Användaren avbröt: den temporära filen tas bort. */
    suspend fun discard(staged: StagedInventory) = withContext(Dispatchers.IO) {
        staged.file.delete()
        Unit
    }

    /**
     * Andra steget: byter aktiv fil och ersätter Issue-tabellen. Misslyckas
     * något läggs den tidigare filen tillbaka, tabellen är orörd och felet kastas vidare.
     */
    suspend fun commit(staged: StagedInventory) = lock.withLock {
        withContext(Dispatchers.IO) {
            check(staged.file.isFile) { "Den förberedda filen finns inte längre." }
            val active = activeFile
            val previous = previousFile
            previous.delete()
            val hadPrevious = active.isFile
            // Den tidigare filen behålls tills den nya fungerar.
            if (hadPrevious && !active.renameTo(previous)) error("Den aktiva filen gick inte att flytta undan.")
            try {
                if (!staged.file.renameTo(active)) error("Den nya filen gick inte att lägga på plats.")
                replaceIssues(staged.issues)
            } catch (e: Throwable) {
                // Tillbaka till läget före: tabellen ändrades aldrig (transaktionen rullades tillbaka).
                active.delete()
                if (hadPrevious) previous.renameTo(active)
                staged.file.delete()
                throw e
            }
            activeInventory.set(
                ActiveInventory(staged.datasetVersion, staged.schemaVersion, InventorySource.IMPORTED, now()),
            )
            previous.delete()
            log("Arkivindex ${staged.datasetVersion} importerat: ${staged.issueCount} nummer")
        }
    }

    /** Återgår till indexet som följer med appen. Det importerade tas bort. */
    suspend fun revertToBundled(): InventoryLoadResult = lock.withLock {
        withContext(Dispatchers.IO) {
            val bundled = InventoryParser.parse(readBundled())
            // Tabellen först: misslyckas det ligger det importerade kvar och gäller.
            replaceIssues(bundled.issues.map { it.toEntity() })
            activeInventory.set(ActiveInventory(bundled.datasetVersion, bundled.schemaVersion, InventorySource.BUNDLED, null))
            activeFile.delete()
            previousFile.delete()
            stagedFile.delete()
            log("Återgick till det inbyggda arkivindexet ${bundled.datasetVersion}")
            InventoryLoadResult.Replaced(bundled.datasetVersion, bundled.issues.size)
        }
    }

    companion object {
        const val ACTIVE_NAME = "inventory.json"

        /** Det riktiga indexet är 0,2 MB. Större filer än så här är något annat. */
        private const val MAX_BYTES = 20L * 1024 * 1024
    }
}
