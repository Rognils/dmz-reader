package se.c64.dmzreader

import android.content.Context
import coil3.ImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import se.c64.dmzreader.data.db.DmzDatabase
import se.c64.dmzreader.data.inventory.InventoryImporter
import se.c64.dmzreader.data.inventory.InventoryLoadResult
import se.c64.dmzreader.data.network.CoverImageLoader
import se.c64.dmzreader.data.pdf.PdfCache
import se.c64.dmzreader.data.pdf.PdfDownloader
import se.c64.dmzreader.data.pdf.PdfRepository
import se.c64.dmzreader.data.repository.DiagnosticsRepository
import se.c64.dmzreader.data.repository.LibraryRepository
import se.c64.dmzreader.data.settings.ActiveInventoryStore
import se.c64.dmzreader.data.settings.DataStoreActiveInventoryStore
import se.c64.dmzreader.data.settings.DataStoreLibraryPreferences
import se.c64.dmzreader.data.settings.LibraryPreferences

/** Läget för inläsningen av det inventory som följer med appen. */
sealed interface InventoryStatus {
    data object Loading : InventoryStatus
    data class Done(val result: InventoryLoadResult) : InventoryStatus
}

/** Appens beroenden, skapade en gång. Ingen DI-ram behövs för en app av den här storleken. */
class AppContainer(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: DmzDatabase by lazy { DmzDatabase.create(context) }
    val activeInventory: ActiveInventoryStore by lazy { DataStoreActiveInventoryStore(context) }
    val libraryPreferences: LibraryPreferences by lazy { DataStoreLibraryPreferences(context) }

    /** Läs-PDF:er: egen HTTP-klient, skild från bildladdarens (ADR 0017). */
    val pdfRepository: PdfRepository by lazy {
        val cache = PdfCache(File(context.cacheDir, "pdf"))
        PdfRepository(cache, PdfDownloader(PdfDownloader.createHttpClient(), cache), database.issueLocalStateDao())
    }

    /** Miniatyrer av PDF-sidor för läsarens miniatyrremsa. */
    val thumbnailRoot: File get() = File(context.cacheDir, "thumbs")

    /** En enda bildladdare för hela appen, så att cache och samtidighetsgräns delas. */
    val imageLoader: ImageLoader by lazy { CoverImageLoader.create(context) }
    val libraryRepository by lazy { LibraryRepository(database.issueDao()) }
    val diagnosticsRepository by lazy { DiagnosticsRepository(context, database) }

    private val _inventoryStatus = MutableStateFlow<InventoryStatus>(InventoryStatus.Loading)
    val inventoryStatus: StateFlow<InventoryStatus> = _inventoryStatus

    /** Läser in assets/archive_inventory.json om dess version inte redan är aktiv. */
    fun loadBundledInventory() {
        scope.launch {
            val result = try {
                val text = context.assets.open(BUNDLED_INVENTORY).bufferedReader().use { it.readText() }
                InventoryImporter(database.issueDao(), activeInventory).load(text)
            } catch (e: Exception) {
                InventoryLoadResult.Rejected("Arkivindexet gick inte att läsa in: ${e.message}")
            }
            _inventoryStatus.value = InventoryStatus.Done(result)
        }
    }

    private companion object {
        const val BUNDLED_INVENTORY = "archive_inventory.json"
    }
}
