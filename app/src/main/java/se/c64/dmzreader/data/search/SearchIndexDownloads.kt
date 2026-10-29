package se.c64.dmzreader.data.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import se.c64.dmzreader.data.offline.ActiveDownload
import se.c64.dmzreader.data.pdf.DownloadProgress
import se.c64.dmzreader.data.pdf.TransferCoordinator
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Var hämtningen av sökindexet står. Visas i Sök och under Inställningar → Sökindex. */
sealed interface IndexDownloadState {
    /** Manifestet är inte hämtat i den här körningen. */
    data object Idle : IndexDownloadState

    data object Checking : IndexDownloadState

    /** Manifestet är hämtat och indexet passar appen. */
    data class Available(val manifest: SearchIndexManifest) : IndexDownloadState

    /** Manifestet säger att indexet inte passar den här appen. Ingenting hämtas. */
    data class Unsupported(val message: String) : IndexDownloadState

    /** GitHub gick inte att nå, eller manifestet saknas. */
    data class Unreachable(val message: String) : IndexDownloadState

    /** Väntar på sin tur: en annan överföring, eller att jobbet startar. */
    data class Queued(val manifest: SearchIndexManifest, val progress: DownloadProgress?) : IndexDownloadState

    /** "Ladda ned endast via wifi" är på, och telefonen är på mobilnät. */
    data class WaitingForWifi(val manifest: SearchIndexManifest, val progress: DownloadProgress?) : IndexDownloadState

    data class Downloading(val manifest: SearchIndexManifest, val progress: DownloadProgress) : IndexDownloadState

    data class Verifying(val manifest: SearchIndexManifest) : IndexDownloadState

    data class Importing(val manifest: SearchIndexManifest, val progress: CopyProgress?) : IndexDownloadState

    /** Anslutningen bröts. Den påbörjade filen ligger kvar, och nästa försök fortsätter där den slutade. */
    data class Interrupted(val manifest: SearchIndexManifest, val progress: DownloadProgress?) : IndexDownloadState

    data class Failed(val message: String, val manifest: SearchIndexManifest?) : IndexDownloadState

    data class Done(val info: SearchIndexInfo) : IndexDownloadState
}

/** Sant medan en hämtning är beställd, pågår eller importeras. */
val IndexDownloadState.inProgress: Boolean
    get() = this is IndexDownloadState.Queued || this is IndexDownloadState.WaitingForWifi ||
        this is IndexDownloadState.Downloading || this is IndexDownloadState.Verifying || this is IndexDownloadState.Importing

/** Sant medan Avbryt går att trycka: före kontrollen och importen. */
val IndexDownloadState.cancellable: Boolean
    get() = this is IndexDownloadState.Queued || this is IndexDownloadState.WaitingForWifi ||
        this is IndexDownloadState.Downloading || this is IndexDownloadState.Interrupted

/** Varför en körning av [SearchIndexDownloads.runPending] slutade. */
enum class IndexRunOutcome {
    /** Ingen hämtning var beställd. */
    NOTHING,

    DONE,
    FAILED,
    CANCELLED,

    /** Nätet försvann. Hämtningen står kvar och fortsätter vid nästa körning. */
    NETWORK_LOST,

    /** Väntar på wifi. Hämtningen står kvar. */
    WAITING_FOR_WIFI,

    ALREADY_RUNNING,
}

/** Id:t i nedladdningsnotisen, så att Avbryt i notisen hittar rätt. */
const val SEARCH_INDEX_DOWNLOAD_ID = "search-index"

const val WAITING_FOR_WIFI_MESSAGE =
    "Väntar på wifi. Sökindexet laddas ned när telefonen är ansluten till wifi, eftersom " +
        "\"Ladda ned endast via wifi\" är på under Inställningar."

/**
 * Hämtning av sökindexet från GitHub-releasen, användarens val och aldrig automatiskt.
 *
 * Knappen beställer hämtningen ([request]). Själva överföringen körs av samma nedladdningsjobb
 * som PDF:erna (UIDT på Android 14 och senare), genom samma [TransferCoordinator]: en
 * överföring åt gången, och en läsning går före. Jobbet anropar [runPending].
 *
 * När filen är hämtad och kontrollerad importeras den med den vanliga importen
 * ([SearchIndexManager.import]), som bara byter in den om den går igenom. Den hämtade filen tas
 * sedan bort. Ett misslyckat försök rör aldrig ett redan importerat sökindex, och användardata
 * rörs aldrig.
 *
 * Beställningen sparas i [directory]/pending.json, så att den överlever att appen stängs.
 */
class SearchIndexDownloads(
    private val downloader: SearchIndexDownloader,
    private val manager: SearchIndexManager,
    private val transfers: TransferCoordinator,
    /** Falskt när "Ladda ned endast via wifi" är på och telefonen är på ett nät med datakostnad. */
    private val networkAllowed: suspend () -> Boolean,
    /** Startar nedladdningsjobbet. Med true startas det om, så att ett nytt försök börjar direkt. */
    private val startTransfers: (restart: Boolean) -> Unit,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {
    private val runLock = Mutex()
    private val cancelRequested = AtomicBoolean(false)

    @Volatile
    private var activeJob: Deferred<*>? = null

    private val _state = MutableStateFlow<IndexDownloadState>(IndexDownloadState.Idle)
    val state: StateFlow<IndexDownloadState> = _state

    /** Hämtningen som pågår, för nedladdningsnotisen. */
    val active: StateFlow<ActiveDownload?> = _state.map { state ->
        when (state) {
            is IndexDownloadState.Downloading -> ActiveDownload(SEARCH_INDEX_DOWNLOAD_ID, "sökindexet", state.progress)
            is IndexDownloadState.Queued, is IndexDownloadState.Verifying, is IndexDownloadState.Importing ->
                ActiveDownload(SEARCH_INDEX_DOWNLOAD_ID, "sökindexet", null)
            else -> null
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private val pendingFile get() = File(downloader.directory, SearchIndexDownloader.PENDING_NAME)

    /** Hämtar manifestet, om det inte redan är hämtat eller en hämtning pågår. [force] hämtar det igen. */
    fun checkManifest(force: Boolean = false) {
        val current = _state.value
        if (current.inProgress || current is IndexDownloadState.Checking || current is IndexDownloadState.Interrupted) return
        if (!force && current !is IndexDownloadState.Idle && current !is IndexDownloadState.Done) return
        // Syns direkt, innan förfrågan har gått iväg.
        _state.value = IndexDownloadState.Checking
        scope.launch { fetchManifest() }
    }

    private suspend fun fetchManifest(): ManifestResult {
        _state.value = IndexDownloadState.Checking
        val result = downloader.fetchManifest()
        _state.value = when (result) {
            is ManifestResult.Ok -> IndexDownloadState.Available(result.manifest)
            is ManifestResult.Unsupported -> IndexDownloadState.Unsupported(result.message)
            is ManifestResult.Unreachable -> IndexDownloadState.Unreachable(result.message)
        }
        log("Manifest: ${result.javaClass.simpleName}")
        return result
    }

    /**
     * "Ladda ned sökindex" och "Försök igen": beställer hämtningen med det hämtade manifestet,
     * eller hämtar manifestet först. En avbruten hämtning fortsätter där den slutade.
     */
    fun request() {
        scope.launch {
            val retry = _state.value is IndexDownloadState.Interrupted || _state.value is IndexDownloadState.Failed
            val manifest = when (val current = _state.value) {
                is IndexDownloadState.Available -> current.manifest
                is IndexDownloadState.Interrupted -> current.manifest
                is IndexDownloadState.Queued, is IndexDownloadState.Downloading, is IndexDownloadState.Verifying,
                is IndexDownloadState.Importing, is IndexDownloadState.Checking -> return@launch
                // Ett nytt manifest efter ett fel: releasen kan ha bytts under tiden.
                else -> (fetchManifest() as? ManifestResult.Ok)?.manifest ?: return@launch
            }
            cancelRequested.set(false)
            savePending(manifest)
            _state.value = IndexDownloadState.Queued(manifest, progressOf(manifest))
            log("Hämtning beställd: ${manifest.releaseTag}/${manifest.fileName}, ${manifest.size} byte")
            // Efter ett avbrott kan jobbet vänta på systemets nästa försök. Försök igen ska börja direkt.
            startTransfers(retry)
        }
    }

    /** Tar bort en beställd eller påbörjad hämtning och glömmer manifestet. Används av testerna. */
    fun reset() {
        activeJob?.let {
            cancelRequested.set(true)
            it.cancel()
        }
        clearPending()
        _state.value = IndexDownloadState.Idle
    }

    /** Sant om en hämtning är beställd och inte klar. Nedladdningsjobbet ska då köras. */
    fun hasPending(): Boolean = pendingFile.isFile

    /** Ungefär hur många byte som återstår, för nedladdningsjobbets uppskattning. */
    fun pendingBytes(): Long = loadPending()?.let { (it.size - downloader.bytesPresent(it)).coerceAtLeast(0) } ?: 0L

    /** Avbryter hämtningen. Den påbörjade filen tas bort. Ett importerat sökindex rörs inte. */
    fun cancel() {
        val job = activeJob
        if (job != null) {
            cancelRequested.set(true)
            job.cancel()
            return
        }
        val state = _state.value
        if (!state.cancellable) return
        val manifest = loadPending()
        clearPending()
        _state.value = manifest?.let { IndexDownloadState.Available(it) } ?: IndexDownloadState.Idle
        log("Hämtningen avbröts av användaren")
    }

    /** Glömmer ett avslutat utfall, så att kortet kan visa manifestet igen. */
    fun dismiss() {
        val state = _state.value
        if (state is IndexDownloadState.Done || state is IndexDownloadState.Failed) _state.value = IndexDownloadState.Idle
    }

    /**
     * Kör en beställd hämtning till slut: överföring, kontroll och import. Anropas av
     * nedladdningsjobbet efter PDF-kön. Avbryts anropet (jobbet stoppas) står hämtningen kvar
     * med sin påbörjade fil och fortsätter vid nästa körning.
     */
    suspend fun runPending(): IndexRunOutcome {
        if (!runLock.tryLock()) return IndexRunOutcome.ALREADY_RUNNING
        var manifest: SearchIndexManifest? = null
        try {
            manifest = loadPending() ?: return IndexRunOutcome.NOTHING
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!networkAllowed()) {
                    _state.value = IndexDownloadState.WaitingForWifi(manifest, progressOf(manifest))
                    log("Sökindexet väntar på wifi")
                    return IndexRunOutcome.WAITING_FOR_WIFI
                }
                // En läsning går före. Ingenting påbörjas medan den pågår.
                transfers.awaitNoReading()
                _state.value = IndexDownloadState.Downloading(manifest, progressOf(manifest) ?: DownloadProgress(0, manifest.size))
                var paused = false
                val result = try {
                    coroutineScope {
                        val download = async {
                            transfers.background {
                                var lastReport = 0L
                                downloader.download(manifest) { progress ->
                                    val tick = System.nanoTime()
                                    if (progress.bytesDownloaded == progress.totalBytes || tick - lastReport >= PROGRESS_INTERVAL_NANOS) {
                                        lastReport = tick
                                        _state.value = IndexDownloadState.Downloading(manifest, progress)
                                    }
                                }
                            }
                        }
                        activeJob = download
                        download.await().also { paused = it == null }
                    }
                } catch (e: CancellationException) {
                    // Avbröts hela körningen, eller bara hämtningen av användaren?
                    currentCoroutineContext().ensureActive()
                    null
                } finally {
                    activeJob = null
                }

                if (cancelRequested.getAndSet(false)) {
                    clearPending()
                    _state.value = IndexDownloadState.Available(manifest)
                    log("Hämtningen avbröts av användaren")
                    return IndexRunOutcome.CANCELLED
                }
                if (paused || result == null) {
                    // Pausad för en läsning: fortsätter efteråt på den påbörjade filen.
                    _state.value = IndexDownloadState.Queued(manifest, progressOf(manifest))
                    log("Sökindexet pausat för en läsning, fortsätter efteråt")
                    continue
                }
                return when (result) {
                    is IndexFileResult.Ready -> importDownloaded(manifest, result)
                    is IndexFileResult.NetworkError -> {
                        _state.value = IndexDownloadState.Interrupted(manifest, progressOf(manifest))
                        log("Sökindexet: nätet försvann (${result.message}), fortsätter vid nästa försök")
                        IndexRunOutcome.NETWORK_LOST
                    }
                    is IndexFileResult.InsufficientSpace -> {
                        clearPending()
                        fail(spaceMessage(result.requiredBytes, result.availableBytes), manifest)
                    }
                    is IndexFileResult.Failed -> {
                        clearPending()
                        fail(result.message, manifest)
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                // Stoppades jobbet mitt i står hämtningen kvar och fortsätter senare.
                val state = _state.value
                if (manifest != null && (state is IndexDownloadState.Downloading || state is IndexDownloadState.Queued) && hasPending()) {
                    _state.value = IndexDownloadState.Interrupted(manifest, progressOf(manifest))
                }
            }
            runLock.unlock()
        }
    }

    private suspend fun importDownloaded(manifest: SearchIndexManifest, ready: IndexFileResult.Ready): IndexRunOutcome {
        val hadIndex = manager.index.value != null
        _state.value = IndexDownloadState.Importing(manifest, null)
        return try {
            val info = manager.import(
                open = { ready.file.inputStream() },
                totalBytes = ready.file.length(),
                onProgress = { progress -> _state.value = IndexDownloadState.Importing(manifest, progress) },
            )
            _state.value = IndexDownloadState.Done(info)
            log("Sökindexet ${manifest.releaseTag}/${manifest.fileName} hämtat och importerat (återupptaget=${ready.resumed})")
            IndexRunOutcome.DONE
        } catch (e: SearchIndexException) {
            fail("${e.message}\n\n${unchangedText(hadIndex)}", manifest)
        } finally {
            // Den hämtade filen behövs inte efter importen, vare sig den lyckades eller inte.
            withContext(NonCancellable + Dispatchers.IO) { clearPending() }
        }
    }

    private fun fail(message: String, manifest: SearchIndexManifest): IndexRunOutcome {
        _state.value = IndexDownloadState.Failed(message, manifest)
        log("Sökindexet kunde inte hämtas: $message")
        return IndexRunOutcome.FAILED
    }

    private fun unchangedText(hadIndex: Boolean) =
        if (hadIndex) "Det tidigare sökindexet är oförändrat." else "Inget sökindex är importerat."

    private fun progressOf(manifest: SearchIndexManifest): DownloadProgress? =
        downloader.bytesPresent(manifest).takeIf { it > 0 }?.let { DownloadProgress(it, manifest.size) }

    private fun savePending(manifest: SearchIndexManifest) {
        downloader.directory.mkdirs()
        pendingFile.writeText(manifestJson(manifest))
    }

    private fun loadPending(): SearchIndexManifest? {
        val text = pendingFile.takeIf { it.isFile }?.readText() ?: return null
        // Beställningen prövades redan när den gjordes. Versionen av appen kan inte ha sjunkit sedan dess.
        return (parseSearchIndexManifest(text, appVersionCode = Int.MAX_VALUE) as? ManifestResult.Ok)?.manifest
            ?: run {
                clearPending()
                null
            }
    }

    private fun clearPending() {
        downloader.clear()
    }

    /** Efter en omstart av appen: visa att en hämtning står kvar. */
    fun restoreAfterStart() {
        val manifest = loadPending() ?: return
        if (_state.value is IndexDownloadState.Idle) _state.value = IndexDownloadState.Interrupted(manifest, progressOf(manifest))
    }

    companion object {
        private const val PROGRESS_INTERVAL_NANOS = 200_000_000L

        fun spaceMessage(required: Long, available: Long): String =
            "Det finns inte plats för sökindexet. Det behövs cirka ${(required + 999_999) / 1_000_000} MB ledigt, " +
                "och det finns ${available / 1_000_000} MB. Frigör utrymme och försök igen."

        fun manifestJson(manifest: SearchIndexManifest): String = kotlinx.serialization.json.buildJsonObject {
            put("manifestVersion", kotlinx.serialization.json.JsonPrimitive(manifest.manifestVersion))
            put("releaseTag", kotlinx.serialization.json.JsonPrimitive(manifest.releaseTag))
            put("fileName", kotlinx.serialization.json.JsonPrimitive(manifest.fileName))
            put("size", kotlinx.serialization.json.JsonPrimitive(manifest.size))
            put("sha256", kotlinx.serialization.json.JsonPrimitive(manifest.sha256))
            put("searchSchemaVersion", kotlinx.serialization.json.JsonPrimitive(manifest.searchSchemaVersion))
            put("inventoryDatasetVersion", kotlinx.serialization.json.JsonPrimitive(manifest.inventoryDatasetVersion))
            put("normalizerVersion", kotlinx.serialization.json.JsonPrimitive(manifest.normalizerVersion))
            put("privacyFilterVersion", kotlinx.serialization.json.JsonPrimitive(manifest.privacyFilterVersion))
            put("minAppVersionCode", kotlinx.serialization.json.JsonPrimitive(manifest.minAppVersionCode))
        }.toString()
    }
}
