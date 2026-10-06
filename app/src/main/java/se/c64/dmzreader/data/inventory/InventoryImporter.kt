package se.c64.dmzreader.data.inventory

import se.c64.dmzreader.data.db.IssueDao
import se.c64.dmzreader.data.settings.ActiveInventory
import se.c64.dmzreader.data.settings.ActiveInventoryStore

sealed interface InventoryLoadResult {
    /** Katalogen ersattes med innehållet i filen. */
    data class Replaced(val datasetVersion: String, val issueCount: Int) : InventoryLoadResult

    /** Filen har samma datasetVersion som den aktiva katalogen. Ingenting skrevs. */
    data class AlreadyActive(val datasetVersion: String, val issueCount: Int) : InventoryLoadResult

    /** Filen avvisades. Den tidigare katalogen gäller fortfarande. */
    data class Rejected(val message: String) : InventoryLoadResult
}

/**
 * Läser in ett inventory till Issue-tabellen.
 *
 * Importen skriver bara till Issue och till uppgiften om aktiv version.
 * IssueLocalState, IssueUserState, Bookmark och ReadingSession rörs aldrig.
 */
class InventoryImporter(
    private val issueDao: IssueDao,
    private val activeInventory: ActiveInventoryStore,
) {
    suspend fun load(inventoryJson: String): InventoryLoadResult {
        val inventory = try {
            InventoryParser.parse(inventoryJson)
        } catch (e: InventoryException) {
            return InventoryLoadResult.Rejected(e.message ?: "Okänt fel i arkivindexet.")
        }

        val active = activeInventory.get()
        val storedCount = issueDao.count()
        // En tom tabell läses alltid in, även om versionen råkar stämma.
        if (active?.datasetVersion == inventory.datasetVersion && storedCount > 0) {
            return InventoryLoadResult.AlreadyActive(inventory.datasetVersion, storedCount)
        }

        issueDao.replaceAll(inventory.issues.map { it.toEntity() })
        activeInventory.set(ActiveInventory(inventory.datasetVersion, inventory.schemaVersion))
        return InventoryLoadResult.Replaced(inventory.datasetVersion, inventory.issues.size)
    }
}
