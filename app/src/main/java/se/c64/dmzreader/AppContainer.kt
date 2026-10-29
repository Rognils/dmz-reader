package se.c64.dmzreader

import android.content.Context
import androidx.core.content.edit
import android.net.Uri
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
import java.io.IOException
import java.io.InputStream
import se.c64.dmzreader.data.backup.BackupRepository
import se.c64.dmzreader.data.db.DmzDatabase
import se.c64.dmzreader.data.hq.FtpAccount
import se.c64.dmzreader.data.hq.FtpScanSource
import se.c64.dmzreader.data.hq.HqCache
import se.c64.dmzreader.data.hq.HqRepository
import se.c64.dmzreader.data.hq.ScanSource
import se.c64.dmzreader.data.inventory.InventoryManager
import se.c64.dmzreader.data.inventory.InventoryLoadResult
import se.c64.dmzreader.data.network.ClientIdentity
import se.c64.dmzreader.data.network.CoverImageLoader
import se.c64.dmzreader.data.offline.LocalStateStore
import se.c64.dmzreader.data.offline.OfflineRepository
import se.c64.dmzreader.data.pdf.PdfCache
import se.c64.dmzreader.data.pdf.PdfDownloader
import se.c64.dmzreader.data.pdf.PdfFiles
import se.c64.dmzreader.data.pdf.TransferCoordinator
import se.c64.dmzreader.data.pdf.PdfRepository
import se.c64.dmzreader.data.repository.BookmarkRepository
import se.c64.dmzreader.data.repository.DiagnosticsRepository
import se.c64.dmzreader.data.repository.LibraryRepository
import se.c64.dmzreader.data.repository.ReadingRepository
import se.c64.dmzreader.data.search.SearchIndexDownloader
import se.c64.dmzreader.data.search.SearchIndexDownloads
import se.c64.dmzreader.data.search.SearchIndexManager
import se.c64.dmzreader.data.search.SearchIndexSource
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import se.c64.dmzreader.data.settings.ActiveInventoryStore
import se.c64.dmzreader.data.settings.BackupPreferences
import se.c64.dmzreader.data.settings.DataStoreActiveInventoryStore
import se.c64.dmzreader.data.settings.DataStoreBackupPreferences
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
        PdfRepository(pdfCache, PdfDownloader(pdfHttpClient, pdfCache), localStateStore, transferCoordinator)
    }

    private val pdfCache by lazy { PdfCache(File(context.cacheDir, "pdf")) }
    private val pdfHttpClient by lazy {
        // I debug-bygget loggas vilken User-Agent som skickas till arkivet.
        PdfDownloader.createHttpClient(logUserAgent = if (BuildConfig.DEBUG) { message -> Log.d("DmzNet", message) } else null)
    }

    /** En PDF-överföring åt gången i hela appen: "Läs" och nedladdningskön delar på den. */
    private val transferCoordinator = TransferCoordinator()
    private val localStateStore by lazy { LocalStateStore(database.issueLocalStateDao()) }

    /** HQ-sidornas cache i cacheDir/hq/, med ett tak på 500 MB (planens FAS 11). */
    val hqCache: HqCache by lazy { HqCache(File(context.cacheDir, "hq")) }

    /**
     * Källan för originalskanningar: arkivets FTP-server, med en enda anslutning som
     * stängs efter 30 sekunder utan användning. De instrumenterade testerna byter den mot en falsk.
     */
    @Volatile
    var scanSource: ScanSource = FtpScanSource(
        account = FtpAccount(BuildConfig.ARCHIVE_FTP_USER, BuildConfig.ARCHIVE_FTP_PASSWORD),
        scope = scope,
        clientIdentity = ClientIdentity.userAgent,
        log = { Log.i("DmzHq", it) },
    )

    /** HQ-sidor: ur cachen eller från arkivet, genom samma överföringskö som PDF:erna. */
    val hqRepository: HqRepository
        get() = HqRepository(hqCache, scanSource, transferCoordinator, log = { Log.i("DmzHq", it) })

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
            transfers = transferCoordinator,
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
            UidtDownloadScheduler(context, downloadPreferences, offlineRepository, scope, indexBytes = { searchIndexDownloads.pendingBytes() })
        } else {
            InAppDownloadScheduler(offlineRepository, scope, afterQueue = { searchIndexDownloads.runPending() })
        }
    }

    /**
     * Debug-bygget kan hämta manifestet från en valfri adress, till exempel en prerelease:
     *   adb shell am start -S -n se.c64.dmzreader.debug/se.c64.dmzreader.MainActivity --es dmzSearchIndexManifest ADRESS
     * -S behövs: adressen läses när appen startar. En tom adress ('' inom citattecken) tar bort den.
     * I release-bygget används alltid latest.
     */
    private val debugPreferences by lazy { context.getSharedPreferences("dmz_debug", Context.MODE_PRIVATE) }

    var searchIndexManifestOverride: String?
        get() = if (BuildConfig.DEBUG) debugPreferences.getString(MANIFEST_OVERRIDE_KEY, null) else null
        set(value) {
            if (!BuildConfig.DEBUG) return
            debugPreferences.edit { if (value.isNullOrBlank()) remove(MANIFEST_OVERRIDE_KEY) else putString(MANIFEST_OVERRIDE_KEY, value) }
            Log.i("DmzSearch", "Manifestadress i debug-bygget: ${value?.takeIf { it.isNotBlank() } ?: "latest"}")
        }

    /** Var sökindexet hämtas: GitHub-releasen, aldrig arkivservern. */
    val searchIndexSource: SearchIndexSource by lazy {
        SearchIndexSource(manifestOverride = { searchIndexManifestOverride?.toHttpUrlOrNull() })
    }

    /** Hämtning av sökindexet från GitHub, genom samma nedladdningsjobb och överföringskö som PDF:erna. */
    val searchIndexDownloads: SearchIndexDownloads by lazy {
        SearchIndexDownloads(
            downloader = SearchIndexDownloader(
                client = SearchIndexDownloader.createHttpClient(
                    log = if (BuildConfig.DEBUG) { message -> Log.d(SearchIndexDownloader.LOG_TAG, message) } else null,
                ),
                source = searchIndexSource,
                directory = File(context.filesDir, "search-download"),
                appVersionCode = BuildConfig.VERSION_CODE,
            ),
            manager = searchIndex,
            transfers = transferCoordinator,
            networkAllowed = {
                val metered = context.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
                !metered || !downloadPreferences.wifiOnly()
            },
            // Ett omstartat jobb avbryter en pågående PDF-nedladdning (den fortsätter sedan), så bara när ingen pågår.
            startTransfers = { restart -> downloadScheduler.start(restart = restart && offlineRepository.active.value == null) },
            scope = scope,
            log = { Log.i("DmzSearch", it) },
        )
    }

    /** Inaktivitetsgränsen för lästid (ADR 0021). De instrumenterade testerna kortar den. */
    @Volatile
    var sessionIdleLimitMillis: Long = se.c64.dmzreader.domain.SESSION_IDLE_LIMIT_MILLIS

    /** Så ofta en pågående session skrivs. De instrumenterade testerna kortar den. */
    @Volatile
    var sessionSaveIntervalMillis: Long = se.c64.dmzreader.domain.SESSION_SAVE_INTERVAL_MILLIS

    /** Tar bort sessioner som blev kvar med för lite aktiv tid, till exempel efter en krasch. Körs när appen startar. */
    fun removeShortSessions() {
        scope.launch {
            try {
                val removed = readingRepository.removeShortSessions()
                if (removed > 0) Log.i("DmzReading", "$removed korta lässessioner togs bort")
            } catch (e: Exception) {
                Log.w("DmzReading", "Korta sessioner kunde inte städas", e)
            }
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
            if (offlineRepository.hasQueued() || searchIndexDownloads.hasPending()) {
                downloadScheduler.start(restart = offlineRepository.active.value == null && searchIndexDownloads.active.value == null)
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
    val bookmarkRepository by lazy {
        BookmarkRepository(database.bookmarkDao(), database.issueDao(), database.issueLocalStateDao())
    }

    /** Export och import av användarens data (ADR 0012). */
    val backupRepository by lazy {
        BackupRepository(
            database.issueUserStateDao(),
            database.bookmarkDao(),
            database.readingSessionDao(),
            database.backupDao(),
            appVersion = BuildConfig.VERSION_NAME,
        )
    }
    val backupPreferences: BackupPreferences by lazy { DataStoreBackupPreferences(context) }

    /** Läser en fil som användaren har valt i systemets filväljare. */
    fun readDocument(uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IOException("Filen gick inte att öppna.")

    /** Skriver till en fil som användaren har skapat i systemets filväljare. */
    fun writeDocument(uri: Uri, text: String) {
        // "wt" kortar filen, så att en äldre och längre fil inte lämnar rester.
        (context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Filen gick inte att skriva."))
            .bufferedWriter().use { it.write(text) }
    }
    val diagnosticsRepository by lazy { DiagnosticsRepository(context, database) }

    private val _inventoryStatus = MutableStateFlow<InventoryStatus>(InventoryStatus.Loading)
    val inventoryStatus: StateFlow<InventoryStatus> = _inventoryStatus

    /**
     * Arkivindexet: det inbyggda i assets eller ett importerat i filesDir/data/ (planens D4 och D5).
     */
    val inventoryManager: InventoryManager by lazy {
        InventoryManager(
            directory = File(context.filesDir, "data"),
            activeInventory = activeInventory,
            currentIssues = { database.issueDao().getAll() },
            replaceIssues = { database.issueDao().replaceAll(it) },
            readBundled = { context.assets.open(BUNDLED_INVENTORY).bufferedReader().use { it.readText() } },
            log = { Log.i("DmzInventory", it) },
        )
    }

    /**
     * Sökindexet i filesDir/data/search.db (planens D2). Importeras av användaren och
     * är skilt från appens databas.
     */
    val searchIndex: SearchIndexManager by lazy {
        SearchIndexManager(
            directory = File(context.filesDir, "data"),
            logQueries = BuildConfig.DEBUG,
            log = { Log.i("DmzSearch", it) },
        )
    }

    /** Öppnar det importerade sökindexet, om det finns. Körs när appen startar. */
    fun loadSearchIndex() {
        scope.launch {
            try {
                searchIndex.loadAtStartup()
                searchIndexDownloads.restoreAfterStart()
            } catch (e: Exception) {
                Log.w("DmzSearch", "Sökindexet gick inte att öppna", e)
            }
        }
    }

    /** Storleken på en fil som användaren har valt i filväljaren. null om den inte är känd. */
    fun documentSize(uri: Uri): Long? =
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }

    /** Miniatyrerna som följer med appen. Ett coverAsset används bara om filen finns här. */
    val bundledCovers: Set<String> by lazy {
        context.assets.list("covers").orEmpty().mapTo(HashSet()) { "covers/$it" }
    }

    /** Öppnar en fil som användaren har valt i systemets filväljare. */
    fun openDocument(uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri) ?: throw IOException("Filen gick inte att öppna.")

    /** Läser in det importerade arkivindexet om det finns och är giltigt, annars det inbyggda. */
    fun loadBundledInventory() {
        scope.launch {
            val result = try {
                inventoryManager.loadAtStartup()
            } catch (e: Exception) {
                InventoryLoadResult.Rejected("Arkivindexet gick inte att läsa in: ${e.message}")
            }
            _inventoryStatus.value = InventoryStatus.Done(result)
        }
    }

    /** Om-sidans text ur appens assets (about_sv.md). */
    fun readAboutText(): String =
        context.assets.open(se.c64.dmzreader.ui.about.ABOUT_ASSET).bufferedReader().use { it.readText() }

    private companion object {
        const val BUNDLED_INVENTORY = "archive_inventory.json"
        const val MANIFEST_OVERRIDE_KEY = "searchIndexManifestUrl"
    }
}
