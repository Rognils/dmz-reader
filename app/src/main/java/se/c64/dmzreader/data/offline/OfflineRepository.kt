package se.c64.dmzreader.data.offline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import se.c64.dmzreader.data.db.DownloadState
import se.c64.dmzreader.data.db.IssueDao
import se.c64.dmzreader.data.db.IssueEntity
import se.c64.dmzreader.data.db.IssueLocalState
import se.c64.dmzreader.data.pdf.DownloadProgress
import se.c64.dmzreader.data.pdf.DownloadResult
import se.c64.dmzreader.data.pdf.PdfCache
import se.c64.dmzreader.data.pdf.PdfDownloader
import se.c64.dmzreader.data.pdf.PdfFiles
import se.c64.dmzreader.data.pdf.TransferCoordinator
import java.io.File
import java.util.Collections

/** Nedladdningen som pågår just nu. */
data class ActiveDownload(val issueId: String, val title: String, val progress: DownloadProgress?)

/** Vad som hände när användaren tryckte "Ladda ned". */
enum class DownloadRequest {
    /** Filen fanns i läs-cachen och flyttades. Ingen nätverkstrafik behövs. */
    MOVED_FROM_CACHE,

    /** Numret står i kön och hämtas när det blir dess tur. */
    QUEUED,

    /** Numret är redan nedladdat eller står redan i kön. */
    ALREADY_HANDLED,

    /** Numret har ingen PDF i arkivet. */
    NOT_AVAILABLE,
}

/** Varför körningen av kön slutade. */
enum class QueueOutcome {
    /** Kön är tom. */
    EMPTY,

    /** Nätet försvann. Det som återstår står kvar i kön med sina .part-filer. */
    NETWORK_LOST,

    /** Kön betas redan av någon annan. */
    ALREADY_RUNNING,
}

/**
 * Permanenta nedladdningar för offline-läsning (planens FAS 5, ADR 0013).
 *
 * Filerna ligger i filesDir/issues/{issueId}.pdf. Status finns i
 * IssueLocalState: QUEUED → DOWNLOADING → DOWNLOADED, eller FAILED. Kön betas
 * av en fil i taget med [runQueue], så att servern aldrig får mer än en
 * nedladdning åt gången härifrån.
 */
class OfflineRepository(
    private val issueDao: IssueDao,
    private val localState: LocalStateStore,
    private val files: PdfFiles,
    private val cache: PdfCache,
    private val downloader: PdfDownloader,
    /** Delas med "Läs": bara en PDF-överföring åt gången i hela appen. */
    private val transfers: TransferCoordinator = TransferCoordinator(),
    private val now: () -> Long = System::currentTimeMillis,
    private val move: (source: File, target: File, part: File) -> Boolean = { s, t, p -> movePdf(s, t, p) },
    /** Anropas när en fil har flyttats in från läs-cachen. */
    private val onMoved: (File) -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    private val runLock = Mutex()
    private val reconciled = CompletableDeferred<Unit>()
    private val cancelledByUser: MutableSet<String> = Collections.synchronizedSet(HashSet())

    @Volatile
    private var activeJob: Pair<String, Deferred<DownloadResult?>>? = null

    private val _active = MutableStateFlow<ActiveDownload?>(null)

    /** Nedladdningen som pågår, med förlopp. null när ingenting hämtas. */
    val active: StateFlow<ActiveDownload?> = _active

    val directory: File get() = files.directory

    /**
     * Stämmer av status mot filsystemet. Körs en gång när appen startar, innan
     * kön får börja betas av.
     *
     * - DOWNLOADED utan fil, eller med fel storlek, nollställs.
     * - DOWNLOADING betyder att appen stoppades mitt i en nedladdning. Numret
     *   ställs tillbaka i kön och fortsätter på sin .part-fil.
     * - .part-filer för nummer som inte står i kön tas bort.
     */
    suspend fun reconcile() = withContext(Dispatchers.IO) {
        try {
            val states = localState.getAll()
            for (state in states) {
                when (state.downloadState) {
                    DownloadState.DOWNLOADED -> if (!isIntact(state)) {
                        log("${state.issueId}: nedladdad fil saknas eller har fel storlek, status nollställs")
                        files.file(state.issueId).delete()
                        localState.update(state.issueId) { it.cleared() }
                    }

                    DownloadState.DOWNLOADING ->
                        localState.update(state.issueId) { it.copy(downloadState = DownloadState.QUEUED) }

                    else -> Unit
                }
            }
            val waiting = localState.getAll()
                .filter { it.downloadState == DownloadState.QUEUED || it.downloadState == DownloadState.DOWNLOADING }
                .map { it.issueId }
                .toSet()
            for (issueId in files.issueIdsWithParts() - waiting) {
                log("$issueId: kvarlämnad .part-fil tas bort")
                files.removePart(issueId)
            }
        } finally {
            reconciled.complete(Unit)
        }
    }

    private fun isIntact(state: IssueLocalState): Boolean {
        val file = files.file(state.issueId)
        return file.isFile && state.downloadedBytes != null && file.length() == state.downloadedBytes
    }

    /**
     * Den permanenta filen för numret, om den finns och har rätt storlek.
     * Annars nollställs statusen och null returneras.
     */
    suspend fun verifiedFile(issueId: String): File? = withContext(Dispatchers.IO) {
        val state = localState.get(issueId) ?: return@withContext null
        if (state.downloadState != DownloadState.DOWNLOADED) return@withContext null
        if (isIntact(state)) {
            files.file(issueId)
        } else {
            log("$issueId: nedladdad fil saknas eller har fel storlek, status nollställs")
            files.file(issueId).delete()
            localState.update(issueId) { it.cleared() }
            null
        }
    }

    /**
     * Användaren vill ha numret nedladdat. Finns en komplett fil i läs-cachen
     * flyttas den, utan nätverkstrafik. Annars ställs numret i kön.
     */
    suspend fun requestDownload(issueId: String): DownloadRequest = withContext(Dispatchers.IO) {
        val issue = issueDao.get(issueId)
        if (issue?.ocrPdfUrl == null) return@withContext DownloadRequest.NOT_AVAILABLE
        val state = localState.get(issueId)
        when (state?.downloadState) {
            DownloadState.DOWNLOADED, DownloadState.QUEUED, DownloadState.DOWNLOADING ->
                return@withContext DownloadRequest.ALREADY_HANDLED
            else -> Unit
        }

        if (moveFromCache(issue, state)) return@withContext DownloadRequest.MOVED_FROM_CACHE

        val queuedAt = now()
        localState.update(issueId) {
            it.copy(downloadState = DownloadState.QUEUED, downloadQueuedAt = queuedAt)
        }
        DownloadRequest.QUEUED
    }

    /**
     * Flyttar numret från läs-cachen om det finns där, komplett och verifierat.
     * Ingen nätverkstrafik. Ger falskt om filen inte finns eller inte gick att flytta.
     */
    private suspend fun moveFromCache(issue: IssueEntity, state: IssueLocalState?): Boolean {
        val issueId = issue.issueId
        val cached = cache.file(issueId)
        if (!cached.isFile || !isVerifiedCacheFile(cached, issue, state)) return false
        val size = cached.length()
        if (!move(cached, files.file(issueId), files.partFile(issueId))) {
            log("$issueId: flytten från läs-cachen misslyckades, numret hämtas i stället")
            return false
        }
        files.removePart(issueId)
        onMoved(files.file(issueId))
        localState.update(issueId) { it.downloaded(files.file(issueId), size, now()) }
        log("$issueId: flyttad från läs-cachen, $size byte, ingen nätverkstrafik")
        return true
    }

    /**
     * Väntar på den permanenta nedladdning av numret som pågår just nu och ger
     * den färdiga filen. Används när användaren trycker "Läs" på ett nummer som
     * håller på att laddas ned: det hämtas då inte en gång till.
     *
     * Ger null om numret inte laddas ned just nu, eller om nedladdningen inte
     * blev klar (avbruten, misslyckad eller pausad).
     */
    suspend fun awaitActiveDownload(issueId: String, onProgress: (DownloadProgress?) -> Unit): File? {
        if (active.value?.issueId != issueId) return null
        // Status skrivs innan nästa nummer blir det aktiva, så när flödet lämnar
        // det här numret står resultatet redan i databasen.
        active.takeWhile { it?.issueId == issueId }.collect { onProgress(it?.progress) }
        return verifiedFile(issueId)
    }

    /**
     * En cachefil räknas som verifierad när storleken stämmer med den som
     * mättes när filen hämtades, eller med inventory om ingen mätning finns.
     */
    private fun isVerifiedCacheFile(file: File, issue: IssueEntity, state: IssueLocalState?): Boolean {
        val expected = state?.observedPdfSize ?: issue.ocrPdfSize ?: return false
        return file.length() == expected
    }

    /**
     * Avbryter en nedladdning som pågår, står i kön eller har misslyckats. Den
     * påbörjade filen tas bort.
     */
    suspend fun cancel(issueId: String) = withContext(Dispatchers.IO) {
        val running = activeJob?.takeIf { it.first == issueId }
        if (running != null) {
            // Den som betar av kön städar efter sig när nedladdningen har stannat.
            cancelledByUser += issueId
            running.second.cancel()
            return@withContext
        }
        val state = localState.get(issueId) ?: return@withContext
        if (state.downloadState == DownloadState.QUEUED || state.downloadState == DownloadState.FAILED ||
            state.downloadState == DownloadState.DOWNLOADING
        ) {
            files.removePart(issueId)
            localState.update(issueId) { it.cleared() }
        }
    }

    /** Tar bort en nedladdning: filen raderas och statusen nollställs. */
    suspend fun remove(issueId: String) = withContext(Dispatchers.IO) {
        cancel(issueId)
        files.remove(issueId)
        localState.update(issueId) {
            if (it.downloadState == DownloadState.DOWNLOADED) it.cleared() else it
        }
    }

    /** Tar bort alla nedladdningar och tömmer kön. */
    suspend fun removeAll() = withContext(Dispatchers.IO) {
        for (state in localState.getAll()) {
            if (state.downloadState != DownloadState.NONE) {
                cancel(state.issueId)
                remove(state.issueId)
            }
        }
        // Filer som inte hör till någon rad ska inte heller ligga kvar.
        if (activeJob == null) files.clear()
    }

    suspend fun hasQueued(): Boolean {
        reconciled.await()
        return localState.queuedCount() > 0
    }

    suspend fun queuedCount(): Int = localState.queuedCount()

    /** Antal byte som återstår att hämta för kön, enligt inventory. Okända storlekar räknas inte. */
    suspend fun estimatedQueueBytes(): Long = localState.getAll()
        .filter { it.downloadState == DownloadState.QUEUED }
        .sumOf { state ->
            val size = issueDao.get(state.issueId)?.ocrPdfSize ?: 0L
            (size - files.partFile(state.issueId).length()).coerceAtLeast(0)
        }

    /**
     * Betar av kön, en fil i taget, tills den är tom eller nätet försvinner.
     *
     * Avbryts anropet (appen stängs, eller systemet stoppar jobbet) ställs den
     * pågående nedladdningen tillbaka i kön, och dess .part-fil ligger kvar.
     */
    suspend fun runQueue(): QueueOutcome {
        if (!runLock.tryLock()) return QueueOutcome.ALREADY_RUNNING
        var current: String? = null
        try {
            reconciled.await()
            while (true) {
                currentCoroutineContext().ensureActive()
                // En läsning går före kön. Ingenting nytt påbörjas medan den pågår.
                transfers.awaitNoReading()
                val next = localState.nextQueued() ?: return QueueOutcome.EMPTY
                val issueId = next.issueId
                val issue = issueDao.get(issueId)
                val url = issue?.ocrPdfUrl
                if (issue == null || url == null) {
                    localState.update(issueId) { it.copy(downloadState = DownloadState.FAILED) }
                    continue
                }
                // Numret kan ha hämtats till läs-cachen med "Läs" medan det stod i kön.
                if (moveFromCache(issue, next)) continue

                current = issueId
                localState.update(issueId) { it.copy(downloadState = DownloadState.DOWNLOADING) }
                val title = "${issue.publicationTitle} ${issue.year} ${issue.issueLabel}"
                _active.value = ActiveDownload(issueId, title, null)

                // null betyder att användaren avbröt just den här filen.
                var paused = false
                val result = try {
                    coroutineScope {
                        val download = async {
                            transfers.background {
                                var lastReport = 0L
                                downloader.download(issueId, url, issue.ocrPdfSize) { progress ->
                                    // Förloppet rapporteras för varje 64 kB. Gränssnittet och notisen
                                    // behöver inte mer än några uppdateringar i sekunden.
                                    val tick = System.nanoTime()
                                    val done = progress.totalBytes == progress.bytesDownloaded
                                    if (done || tick - lastReport >= PROGRESS_INTERVAL_NANOS) {
                                        lastReport = tick
                                        _active.value = ActiveDownload(issueId, title, progress)
                                    }
                                }
                            }
                        }
                        activeJob = issueId to download
                        download.await().also { paused = it == null }
                    }
                } catch (e: CancellationException) {
                    // Avbröts hela körningen, eller bara den här filen av användaren?
                    currentCoroutineContext().ensureActive()
                    null
                } finally {
                    activeJob = null
                }

                // Markeringen tas alltid bort här. Ligger den kvar skulle nästa
                // nedladdning av samma nummer räknas som avbruten när den blir klar.
                val cancelled = cancelledByUser.remove(issueId)
                if (paused && !cancelled) {
                    // Pausad för en läsning: numret står kvar först i kön med sin .part-fil.
                    localState.update(issueId) { it.copy(downloadState = DownloadState.QUEUED) }
                    _active.value = null
                    log("$issueId: pausad för en läsning, fortsätter efteråt")
                    current = null
                    continue
                }
                if (result == null || cancelled) {
                    files.remove(issueId)
                    localState.update(issueId) { it.cleared() }
                    log("$issueId: nedladdningen avbröts av användaren")
                    current = null
                    continue
                }
                when (result) {
                    is DownloadResult.Success -> {
                        localState.update(issueId) {
                            it.downloaded(result.file, result.size, now()).copy(
                                observedPdfSize = result.size,
                                observedPdfEtag = result.etag,
                                observedPdfLastModified = result.lastModified,
                                // Ett sidantal som mättes på en annan fil gäller inte längre.
                                observedPageCount = it.observedPageCount.takeIf { _ -> it.observedPdfSize == result.size },
                            )
                        }
                        // En äldre kopia i läs-cachen behövs inte längre.
                        cache.remove(issueId)
                        log("$issueId: nedladdad, ${result.size} byte, återupptagen=${result.resumed}")
                    }

                    is DownloadResult.NetworkError -> {
                        localState.update(issueId) { it.copy(downloadState = DownloadState.QUEUED) }
                        log("$issueId: nätet försvann (${result.message}), numret står kvar i kön")
                        current = null
                        return QueueOutcome.NETWORK_LOST
                    }

                    else -> {
                        localState.update(issueId) { it.copy(downloadState = DownloadState.FAILED) }
                        log("$issueId: nedladdningen misslyckades med $result")
                    }
                }
                current = null
            }
        } finally {
            val interrupted = current
            withContext(NonCancellable) {
                if (interrupted != null) {
                    if (cancelledByUser.remove(interrupted)) {
                        files.remove(interrupted)
                        localState.update(interrupted) { it.cleared() }
                    } else {
                        localState.update(interrupted) {
                            if (it.downloadState == DownloadState.DOWNLOADING) it.copy(downloadState = DownloadState.QUEUED) else it
                        }
                    }
                }
                _active.value = null
            }
            runLock.unlock()
        }
    }

    /** Antal byte som nedladdningarna tar på telefonen, inklusive påbörjade filer. */
    fun totalBytes(): Long = files.totalBytes()

    private companion object {
        const val PROGRESS_INTERVAL_NANOS = 250_000_000L
    }
}

private fun IssueLocalState.cleared(): IssueLocalState = copy(
    downloadState = DownloadState.NONE,
    downloadedPath = null,
    downloadedBytes = null,
    downloadedAt = null,
    downloadQueuedAt = null,
)

private fun IssueLocalState.downloaded(file: File, size: Long, at: Long): IssueLocalState = copy(
    downloadState = DownloadState.DOWNLOADED,
    downloadedPath = file.path,
    downloadedBytes = size,
    downloadedAt = at,
    downloadQueuedAt = null,
    observedPdfSize = observedPdfSize ?: size,
)
