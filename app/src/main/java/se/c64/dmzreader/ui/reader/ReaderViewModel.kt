package se.c64.dmzreader.ui.reader

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.c64.dmzreader.data.db.Bookmark
import se.c64.dmzreader.data.db.IssueEntity
import se.c64.dmzreader.data.pdf.DownloadProgress
import se.c64.dmzreader.data.pdf.DownloadResult
import se.c64.dmzreader.data.pdf.PdfDocument
import se.c64.dmzreader.data.pdf.PdfRepository
import se.c64.dmzreader.data.db.ReadStatusOverride
import se.c64.dmzreader.data.offline.OfflineRepository
import se.c64.dmzreader.data.repository.BookmarkRepository
import se.c64.dmzreader.data.repository.BookmarkToggle
import se.c64.dmzreader.data.repository.LibraryRepository
import se.c64.dmzreader.data.repository.ReadingRecorder
import se.c64.dmzreader.data.repository.ReadingRepository
import se.c64.dmzreader.domain.PageReadTimer
import se.c64.dmzreader.domain.ReadAction
import se.c64.dmzreader.domain.ReaderPosition
import se.c64.dmzreader.domain.RenderFocus
import se.c64.dmzreader.domain.RenderUrgency
import se.c64.dmzreader.domain.SeenPages
import se.c64.dmzreader.domain.SESSION_IDLE_LIMIT_MILLIS
import se.c64.dmzreader.domain.SESSION_PAGE_SAVE_DELAY_MILLIS
import se.c64.dmzreader.domain.SESSION_SAVE_INTERVAL_MILLIS
import se.c64.dmzreader.domain.SessionTracker
import se.c64.dmzreader.domain.effectiveSpreadOffset
import se.c64.dmzreader.domain.fileSizeText
import se.c64.dmzreader.domain.normalizeSpreadOffset
import se.c64.dmzreader.domain.readAction
import se.c64.dmzreader.domain.renderUrgency
import java.io.File

sealed interface ReaderUiState {
    data object Loading : ReaderUiState

    data class Downloading(val issue: IssueEntity, val progress: DownloadProgress?) : ReaderUiState

    /** [canRetry] är falskt när ett nytt försök inte kan hjälpa, t.ex. när PDF:en saknas i arkivet. */
    data class Failed(val issue: IssueEntity?, val message: String, val canRetry: Boolean) : ReaderUiState

    data class Reading(
        val issue: IssueEntity,
        val document: PdfDocument,
        val position: ReaderPosition,
        val spreadOffset: Int,
        /** Falskt när katalogen bestämmer parningen. Då kan användaren inte förskjuta den. */
        val canShiftSpread: Boolean,
        /** Antal sidor som räknas som lästa (planens C10). */
        val pagesRead: Int = 0,
        /** Sidor i numret som har bokmärke. */
        val bookmarkedPages: Set<Int> = emptySet(),
    ) : ReaderUiState {
        val pageCount: Int get() = document.pageCount

        /** Sant om ankarsidan har ett bokmärke: då är stjärnan fylld. */
        val pageIsBookmarked: Boolean get() = position.page in bookmarkedPages
    }
}

/** Något läsaren vill berätta med en snackbar. */
sealed interface ReaderMessage {
    /** "Bokmärke sparat", med åtgärden "Lägg till titel". */
    data class BookmarkAdded(val bookmark: Bookmark) : ReaderMessage

    /** "Bokmärket borttaget", med åtgärden "Ångra". */
    data class BookmarkRemoved(val bookmarks: List<Bookmark>) : ReaderMessage
}

/** Hur en sida visas just nu: som förhandsbild eller i full skärmupplösning. */
enum class PageQuality { PREVIEW, FULL }

/** Ett sidbyte framåt, och vad som redan fanns renderat av den nya skärmen när bytet började. */
data class PageTurn(val pages: List<Int>, val quality: PageQuality?)

/** Felmeddelanden för en misslyckad nedladdning. */
fun downloadFailureMessage(result: DownloadResult): String = when (result) {
    is DownloadResult.NetworkError ->
        "Det gick inte att nå arkivet. Kontrollera att telefonen har nät och försök igen."
    is DownloadResult.InsufficientSpace ->
        "Det finns inte plats för numret. Det behövs ${fileSizeText(result.requiredBytes)}, " +
            "och ${fileSizeText(result.availableBytes) ?: "0 MB"} är ledigt."
    is DownloadResult.HttpError -> "Arkivet svarade med ett fel (kod ${result.code})."
    is DownloadResult.LengthMismatch ->
        "Filen som kom stämde inte med den storlek arkivet uppgav. Försök igen."
    is DownloadResult.Success -> ""
}

class ReaderViewModel(
    private val issueId: String,
    private val libraryRepository: LibraryRepository,
    private val pdfRepository: PdfRepository,
    private val readingRepository: ReadingRepository,
    private val offlineRepository: OfflineRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val thumbnailRoot: File,
    /** Lever längre än läsaren, så att skrivningar vid stängning hinner bli klara. */
    writeScope: CoroutineScope,
    /** Sidan läsaren ska öppnas på, till exempel från ett bokmärke. null betyder läspositionen. */
    private val requestedPage: Int? = null,
    /** Inaktivitetsgränsen för lästid. Kortas i tester. */
    sessionIdleLimitMillis: Long = SESSION_IDLE_LIMIT_MILLIS,
    private val sessionSaveIntervalMillis: Long = SESSION_SAVE_INTERVAL_MILLIS,
) : ViewModel() {
    private val _state = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val state: StateFlow<ReaderUiState> = _state

    // Läspositionen gäller i minnet direkt och skrivs till databasen av recorder,
    // aldrig från renderingens eller gesternas kod (planens FAS 4).
    private val recorder = ReadingRecorder(issueId, readingRepository, writeScope) { error ->
        Log.w(LOG_TAG, "$issueId: läsdata kunde inte sparas", error)
    }
    private val readTimer = PageReadTimer()
    private val session = SessionTracker(issueId, sessionIdleLimitMillis)
    private var sessionSaveJob: Job? = null
    private var sessionPageSaveJob: Job? = null
    private var readTimerJob: Job? = null
    private var foreground = false
    private var seen = SeenPages.EMPTY
    private var unreadMarked = false

    // Sidan läsaren öppnades på. Att den räknas som läst igen nollställer inte "oläst".
    private var openingPage: Int? = null

    // Sidorna på skärmen som visas, och hur de övriga komponerade sidorna är renderade.
    private var screenPages: List<Int> = emptyList()
    private val shownQuality = HashMap<Int, PageQuality>()

    /** Läses av renderartråden när den väljer nästa rendering. */
    @Volatile
    private var renderFocus = RenderFocus()

    private val _messages = Channel<ReaderMessage>(Channel.BUFFERED)

    /** Händelser som visas en gång, som snackbar. */
    val messages: Flow<ReaderMessage> = _messages.receiveAsFlow()

    private var bookmarkedPages: Set<Int> = emptySet()

    private var downloadJob: Job? = null
    private var document: PdfDocument? = null
    private var thumbnailDirectory: File = thumbnailRoot

    /**
     * Grundbilder i skärmupplösning. Storleken begränsas till en åttondel av appens
     * minne, och bilder som faller ur frigörs direkt.
     */
    val pageBitmaps = PageBitmapCache((Runtime.getRuntime().maxMemory() / 8).toInt())

    /** Miniatyrer är små (cirka 0,15 MB) och får ligga kvar tills skräpsamlaren tar dem. */
    private val thumbnails = object : LruCache<Int, Bitmap>(THUMBNAIL_CACHE_BYTES) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.allocationByteCount
    }

    init {
        start()
        viewModelScope.launch {
            bookmarkRepository.observeAll().collect { all ->
                bookmarkedPages = all.filter { it.issueId == issueId }.mapTo(HashSet()) { it.pageNumber }
                _state.update { current ->
                    if (current is ReaderUiState.Reading) current.copy(bookmarkedPages = bookmarkedPages) else current
                }
            }
        }
    }

    private fun start() {
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            val issue = libraryRepository.observeIssueDetails(issueId).first()?.issue
            if (issue == null) {
                _state.value = ReaderUiState.Failed(null, "Numret finns inte i det aktuella arkivindexet.", false)
                return@launch
            }
            if (issue.readAction() == ReadAction.PDF_MISSING) {
                _state.value = ReaderUiState.Failed(issue, "Numret har ingen PDF i arkivet.", false)
                return@launch
            }
            // En permanent nedladdning används alltid före läs-cachen och före nätet.
            val local = offlineRepository.verifiedFile(issueId) ?: pdfRepository.usableCachedFile(issue)
            if (local != null) {
                open(issue, local)
                return@launch
            }
            _state.value = ReaderUiState.Downloading(issue, null)
            // Laddas numret redan ned permanent visas den nedladdningens förlopp, och
            // läsaren öppnar den färdiga filen. Numret hämtas inte en gång till.
            val downloaded = offlineRepository.awaitActiveDownload(issueId) { progress ->
                _state.update { current ->
                    if (current is ReaderUiState.Downloading) current.copy(progress = progress) else current
                }
            }
            if (downloaded != null) {
                open(issue, downloaded)
                return@launch
            }
            val result = pdfRepository.download(issue) { progress ->
                _state.update { current ->
                    if (current is ReaderUiState.Downloading) current.copy(progress = progress) else current
                }
            }
            when (result) {
                is DownloadResult.Success -> open(issue, result.file)
                else -> _state.value = ReaderUiState.Failed(issue, downloadFailureMessage(result), true)
            }
        }
    }

    private suspend fun open(issue: IssueEntity, file: File) {
        val opened = try {
            PdfDocument.open(file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // En fil som inte går att öppna ska inte ligga kvar och blockera nästa försök.
            if (file.parentFile == offlineRepository.directory) offlineRepository.remove(issueId) else file.delete()
            _state.value = ReaderUiState.Failed(issue, "PDF:en gick inte att öppna. Försök igen för att hämta den på nytt.", true)
            return
        }
        document = opened
        opened.preloadPageSizes()
        // Filens storlek i katalognamnet: miniatyrer från en utbytt PDF återanvänds inte.
        thumbnailDirectory = File(thumbnailRoot, "$issueId-${file.length()}")
        pdfRepository.recordPageCount(issueId, opened.pageCount, file.length())
        pdfRepository.markOpened(issueId)
        val local = pdfRepository.localState(issueId)
        val saved = readingRepository.get(issueId)
        seen = SeenPages.decode(saved?.seenPages)
        unreadMarked = saved?.readStatusOverride == ReadStatusOverride.UNREAD
        // Läsaren öppnas på den begärda sidan (ett bokmärke), annars där användaren senast var.
        val startPage = (requestedPage ?: saved?.lastPage ?: 1).coerceIn(1, opened.pageCount.coerceAtLeast(1))
        openingPage = startPage
        recorder.opened()
        // En sida som öppnas via ett bokmärke blir den nya läspositionen.
        if (requestedPage != null && startPage != saved?.lastPage) recorder.anchorPage(startPage)
        _state.value = ReaderUiState.Reading(
            issue = issue,
            document = opened,
            position = ReaderPosition(startPage),
            spreadOffset = effectiveSpreadOffset(issue.spreadOffset, local?.detectedSpreadOffset),
            canShiftSpread = issue.spreadOffset == null,
            pagesRead = seen.countUpTo(opened.pageCount),
            bookmarkedPages = bookmarkedPages,
        )
        startSessionIfReading()
        updateReadTimer()
    }

    fun retry() = start()

    /**
     * Avbryter läsarens nedladdning. Den påbörjade filen ligger kvar för återupptagning.
     * En permanent nedladdning av numret som läsaren bara väntade på fortsätter.
     */
    fun cancelDownload() {
        downloadJob?.cancel()
    }

    /**
     * Läsaren visar nu skärm [screenIndex] med sidorna [pages] (en sida, eller ett
     * uppslag i liggande läge).
     */
    fun onScreenShown(screenIndex: Int, landscape: Boolean, pages: List<Int>) {
        val current = _state.value as? ReaderUiState.Reading ?: return
        val position = current.position.movedTo(screenIndex, landscape, current.pageCount, current.spreadOffset)
        screenPages = pages
        if (position != current.position) {
            _state.value = current.copy(position = position)
            anchorChanged(position.page)
        }
        updateReadTimer()
    }

    /** Snabbhopp via reglaget eller miniatyrremsan. */
    fun goToPage(page: Int) {
        val current = _state.value as? ReaderUiState.Reading ?: return
        val position = ReaderPosition(page.coerceIn(1, current.pageCount))
        if (position == current.position) return
        _state.value = current.copy(position = position)
        anchorChanged(position.page)
    }

    private fun anchorChanged(page: Int) {
        recorder.anchorPage(page)
        session.moveTo(page)
        session.interaction(SystemClock.elapsedRealtime())
        // Sessionen skrivs en stund efter sidbytet, så att snabb bläddring ger en enda skrivning.
        sessionPageSaveJob?.cancel()
        sessionPageSaveJob = viewModelScope.launch {
            delay(SESSION_PAGE_SAVE_DELAY_MILLIS)
            saveSession()
        }
    }

    /** Användaren rörde skärmen: ett tryck, en zoomning eller en panorering. Håller lästiden igång. */
    fun onInteraction() {
        session.interaction(SystemClock.elapsedRealtime())
    }

    /**
     * Läsaren är på väg till skärmen med sidorna [current]. Renderingskön
     * prioriterar den, sedan [next] och sist [previous].
     */
    fun onScreenTargeted(current: List<Int>, next: List<Int>, previous: List<Int>) {
        val before = renderFocus
        if (current == before.current) return
        if (current.isNotEmpty() && current == before.next) {
            // Ett sidbyte framåt: fanns den nya skärmen redan renderad?
            val quality = when {
                current.all { shownQuality[it] == PageQuality.FULL } -> PageQuality.FULL
                current.all { it in shownQuality } -> PageQuality.PREVIEW
                else -> null
            }
            Log.d(LOG_TAG, "pageturn pages=$current prerendered=${quality ?: "NONE"}")
            synchronized(pageTurns) {
                if (pageTurns.size >= PAGE_TURN_LOG_LIMIT) pageTurns.removeAt(0)
                pageTurns += PageTurn(current, quality)
            }
        }
        renderFocus = RenderFocus(current, next, previous)
    }

    /** Sidan [page] visas nu med [quality], eller inte alls (null). */
    fun onPageShown(page: Int, quality: PageQuality?) {
        if (quality == null) shownQuality.remove(page) else shownQuality[page] = quality
        updateReadTimer()
    }

    /** Läsaren syns på skärmen. */
    fun onForeground() {
        foreground = true
        startSessionIfReading()
        updateReadTimer()
    }

    /**
     * Läsaren lämnar skärmen: appen går i bakgrunden, skärmen släcks eller läsaren
     * stängs. Läspositionen skrivs alltid direkt. Vid rotation fortsätter sessionen.
     */
    fun onBackground(changingConfiguration: Boolean) {
        recorder.flush()
        if (changingConfiguration) return
        foreground = false
        updateReadTimer()
        // Klockan stannar och sessionen skrivs, men den är inte slut: kommer läsaren
        // tillbaka inom inaktivitetsgränsen fortsätter samma session.
        session.background(SystemClock.elapsedRealtime())
        sessionSaveJob?.cancel()
        sessionPageSaveJob?.cancel()
        saveSession()
    }

    /**
     * En session börjar när läsaren visar numret (planens C6). Raden skrivs direkt och
     * uppdateras sedan löpande, så att högst ett sparintervall går förlorat om appen dör.
     */
    private fun startSessionIfReading() {
        val current = _state.value as? ReaderUiState.Reading ?: return
        if (!foreground) return
        val now = SystemClock.elapsedRealtime()
        if (session.active && !session.foreground(now)) endSession()
        if (!session.active) {
            session.start(current.position.page, System.currentTimeMillis(), now)
            saveSession()
        }
        sessionSaveJob?.cancel()
        sessionSaveJob = viewModelScope.launch {
            while (true) {
                delay(sessionSaveIntervalMillis)
                saveSession()
            }
        }
    }

    private fun saveSession() {
        session.snapshot(SystemClock.elapsedRealtime())?.let { recorder.session(it) }
    }

    private fun endSession() {
        sessionSaveJob?.cancel()
        sessionPageSaveJob?.cancel()
        session.end(SystemClock.elapsedRealtime())?.let { recorder.session(it, final = true) }
    }

    /**
     * Tidtagningen för när skärmens sidor räknas som lästa (planens C10). Klockan
     * går bara när sidorna visas i full skärmupplösning och appen är i förgrunden.
     */
    private fun updateReadTimer() {
        if (_state.value !is ReaderUiState.Reading) return
        val now = SystemClock.elapsedRealtime()
        readTimer.setScreen(screenPages)
        val readable = foreground && screenPages.isNotEmpty() &&
            screenPages.all { shownQuality[it] == PageQuality.FULL }
        if (readable) readTimer.resume(now) else readTimer.pause(now)
        readTimerJob?.cancel()
        val remaining = readTimer.remaining(now) ?: return
        readTimerJob = viewModelScope.launch {
            delay(remaining)
            // delay kan avrunda nedåt; vänta ut resten.
            while (true) {
                val left = readTimer.remaining(SystemClock.elapsedRealtime()) ?: return@launch
                if (left == 0L) break
                delay(left)
            }
            markRead(readTimer.collect(SystemClock.elapsedRealtime()))
        }
    }

    private fun markRead(pages: List<Int>) {
        val current = _state.value as? ReaderUiState.Reading ?: return
        if (pages.isEmpty()) return
        session.pagesRead(pages)
        val hasNewPage = pages.any { it !in seen }
        // "Oläst" nollställs när en ny sida räknas som läst: en sida som inte var
        // läst förut, eller en annan sida än den läsaren öppnades på.
        val clearUnread = unreadMarked && (hasNewPage || current.position.page != openingPage)
        if (!hasNewPage && !clearUnread) return
        seen += pages
        if (clearUnread) unreadMarked = false
        recorder.pagesRead(pages, current.pageCount, clearUnread)
        _state.value = current.copy(pagesRead = seen.countUpTo(current.pageCount))
    }

    /**
     * Stjärnan i toppraden: skapar ett bokmärke på ankarsidan, eller tar bort det
     * som finns där. Resultatet kommer som ett meddelande i [messages].
     */
    fun toggleBookmark() {
        val current = _state.value as? ReaderUiState.Reading ?: return
        viewModelScope.launch {
            when (val result = bookmarkRepository.toggle(issueId, current.position.page)) {
                is BookmarkToggle.Added -> _messages.send(ReaderMessage.BookmarkAdded(result.bookmark))
                is BookmarkToggle.Removed -> _messages.send(ReaderMessage.BookmarkRemoved(result.bookmarks))
            }
        }
    }

    /** Ångrar en borttagning. */
    fun restoreBookmarks(bookmarks: List<Bookmark>) {
        viewModelScope.launch { bookmarkRepository.restore(bookmarks) }
    }

    fun editBookmark(id: Long, title: String, note: String) {
        viewModelScope.launch { bookmarkRepository.edit(id, title, note) }
    }

    /** Förskjuter uppslagsparningen ett steg och sparar valet för numret. */
    fun shiftSpread() {
        val current = _state.value as? ReaderUiState.Reading ?: return
        if (!current.canShiftSpread) return
        val shifted = normalizeSpreadOffset(current.spreadOffset + 1)
        _state.value = current.copy(spreadOffset = shifted)
        viewModelScope.launch { pdfRepository.saveSpreadOffset(issueId, shifted) }
    }

    fun pageKey(pageNumber: Int, width: Int, height: Int) = "$pageNumber@${width}x$height"

    /**
     * Lånar grundbilden för en sida i den givna storleken, från minnescachen eller
     * nyrenderad. Anroparen lämnar tillbaka den med pageBitmaps.release().
     */
    suspend fun acquirePageBitmap(
        document: PdfDocument,
        pageNumber: Int,
        width: Int,
        height: Int,
        preview: Boolean,
    ): Bitmap {
        val key = pageKey(pageNumber, width, height)
        pageBitmaps.acquire(key)?.let { return it }
        val rendered = document.renderPage(pageNumber, width, height) {
            renderUrgency(pageNumber, preview, renderFocus)
        }
        return pageBitmaps.putAndAcquire(key, rendered)
    }

    /**
     * En liten miniatyr av den faktiska PDF-sidan, cachad på disk.
     */
    suspend fun thumbnail(document: PdfDocument, pageNumber: Int): Bitmap? {
        thumbnails.get(pageNumber)?.let { return it }
        val file = File(thumbnailDirectory, "%03d.jpg".format(pageNumber))
        val cached = withContext(Dispatchers.IO) {
            if (file.isFile) android.graphics.BitmapFactory.decodeFile(file.path) else null
        }
        if (cached != null) {
            thumbnails.put(pageNumber, cached)
            return cached
        }
        return try {
            val size = document.pageSize(pageNumber)
            val height = (THUMBNAIL_WIDTH / size.aspectRatio).toInt().coerceAtLeast(1)
            val bitmap = document.renderPage(pageNumber, THUMBNAIL_WIDTH, height) { RenderUrgency.THUMBNAIL }
            thumbnails.put(pageNumber, bitmap)
            withContext(Dispatchers.IO) {
                thumbnailDirectory.mkdirs()
                val temporary = File(thumbnailDirectory, file.name + ".tmp")
                temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 75, it) }
                temporary.renameTo(file)
            }
            thumbnails.put(pageNumber, bitmap)
            bitmap
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    override fun onCleared() {
        // Läspositionen skrivs alltid när läsaren stängs.
        recorder.flush()
        endSession()
        recorder.close()
        downloadJob?.cancel()
        pageBitmaps.clear()
        thumbnails.evictAll()
        document?.close()
    }

    companion object {
        const val THUMBNAIL_WIDTH = 160
        private const val THUMBNAIL_CACHE_BYTES = 12 * 1024 * 1024
        private const val LOG_TAG = "DmzReader"
        private const val PAGE_TURN_LOG_LIMIT = 500
        private val pageTurns = ArrayList<PageTurn>()

        /** De senaste sidbytena framåt, för mätning av förrenderingen. */
        fun recentPageTurns(): List<PageTurn> = synchronized(pageTurns) { pageTurns.toList() }

        fun clearPageTurnLog() = synchronized(pageTurns) { pageTurns.clear() }
    }
}
