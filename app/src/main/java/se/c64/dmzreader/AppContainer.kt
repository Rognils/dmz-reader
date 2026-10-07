package se.c64.dmzreader

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
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
import se.c64.dmzreader.data.offline.LocalStateStore
import se.c64.dmzreader.data.offline.OfflineRepository
import se.c64.dmzreader.data.pdf.PdfCache
import se.c64.dmzreader.data.pdf.PdfDownloader
import se.c64.dmzreader.data.pdf.PdfFiles
import se.c64.dmzreader.data.pdf.PdfRepository
import se.c64.dmzreader.data.repository.DiagnosticsRepository
import se.c64.dmzreader.data.repository.LibraryRepository
import se.c64.dmzreader.data.repository.ReadingRepository
import se.c64.dmzreader.data.settings.ActiveInventoryStore
import se.c64.dmzreader.data.settings.DataStoreActiveInventoryStore
import se.c64.dmzreader.data.settings.DataStoreDownloadPreferences
import se.c64.dmzreader.data.settings.DataStoreLibraryPreferences
import se.c64.dmzreader.data.settings.DownloadPreferences
import se.c64.dmzreader.data.settings.LibraryPreferences
import se.c64.dmzreader.offline.DownloadScheduler
import se.c64.dmzreader.offline.InAppDownloadScheduler
import se.c64.dmzreader.offline.UidtDownloadScheduler

/** Läget för inläsningen av det inventory som följer med appen. */
sealed interface InventoryStatus {
    data object Loading : InventoryStatus
    data class Done(val result: InventoryLoadResult) : InventoryStatus
}

/** Appens beroenden, skapade en gång. Ingen DI-ram behövs för en app av den här storleken. */
class AppContainer(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Lever lika länge som appen. Används för skrivningar som ska bli klara även när en vy stängs. */
    val applicationScope: CoroutineScope get() = scope

    val database: DmzDatabase by lazy { DmzDatabase.create(context) }
    val activeInventory: ActiveInventoryStore by lazy { DataStoreActiveInventoryStore(context) }
    val libraryPreferences: LibraryPreferences by lazy { DataStoreLibraryPreferences(context) }

    /** Läs-PDF:er: egen HTTP-klient, skild från bildladdarens (ADR 0017). */
    val pdfRepository: PdfRepository by lazy {
        PdfRepository(pdfCache, PdfDownloader(pdfHttpClient, pdfCache), localStateStore)
    }

    private val pdfCache by lazy { PdfCache(File(context.cacheDir, "pdf")) }
    private val pdfHttpClient by lazy { PdfDownloader.createHttpClient() }
    private val localStateStore by lazy { LocalStateStore(database.issueLocalStateDao()) }

    val downloadPreferences: DownloadPreferences by lazy { DataStoreDownloadPreferences(context) }

    /** Permanenta nedladdningar i filesDir/issues/ (ADR 0013), med samma nedladdningsmotor som läs-cachen. */
    val offlineRepository: OfflineRepository by lazy {
        val files = PdfFiles(File(context.filesDir, "issues"))
        OfflineRepository(
            issueDao = database.issueDao(),
            localState = localStateStore,
            files = files,
            cache = pdfCache,
            downloader = PdfDownloader(pdfHttpClient, files),
            onMoved = ::leaveCacheGroup,
            log = { Log.i("DmzOffline", it) },
        )
    }

    /**
     * Filer i cacheDir tillhör en särskild grupp, som systemet använder för att
     * räkna appens cache. En fil som flyttas därifrån behåller gruppen och skulle
     * fortsätta räknas som cache. Den får därför appens vanliga grupp.
     */
    private fun leaveCacheGroup(file: File) {
        try {
            Os.chown(file.path, -1, Os.getgid())
        } catch (e: Exception) {
            Log.w("DmzOffline", "${file.name}: gruppen kunde inte ändras", e)
        }
    }

    /** UIDT-jobb på Android 14 och senare. Äldre versioner laddar bara ned medan appen är öppen. */
    val downloadScheduler: DownloadScheduler by lazy {
        if (Build.VERSION.SDK_INT >= 34) {
            UidtDownloadScheduler(context, downloadPreferences, offlineRepository, scope)
        } else {
            InAppDownloadScheduler(offlineRepository, scope)
        }
    }

    /** Stämmer av nedladdningarnas status mot filsystemet. Körs när appen startar. */
    fun reconcileDownloads() {
        scope.launch { offlineRepository.reconcile() }
    }

    /** Fortsätter med kön om något väntar. Anropas när appen syns, eftersom ett UIDT-jobb bara får startas då. */
    fun resumeDownloads() {
        scope.launch {
            // Pågår ingen nedladdning kan jobbet ligga och vänta, till exempel efter
            // att nätet försvunnit: systemet väntar då allt längre mellan försöken.
            // När användaren öppnar appen startas jobbet om, så att kön börjar direkt.
            if (offlineRepository.hasQueued()) {
                downloadScheduler.start(restart = offlineRepository.active.value == null)
            }
        }
    }

    /** Miniatyrer av PDF-sidor för läsarens miniatyrremsa. */
    val thumbnailRoot: File get() = File(context.cacheDir, "thumbs")

    /** En enda bildladdare för hela appen, så att cache och samtidighetsgräns delas. */
    val imageLoader: ImageLoader by lazy { CoverImageLoader.create(context) }
    val libraryRepository by lazy {
        LibraryRepository(database.issueDao(), database.issueUserStateDao(), database.issueLocalStateDao())
    }
    val readingRepository by lazy {
        ReadingRepository(database.issueUserStateDao(), database.readingSessionDao())
    }
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
