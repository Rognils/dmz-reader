package se.c64.dmzreader.ui.reader

import android.graphics.Bitmap
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.c64.dmzreader.data.db.IssueEntity
import se.c64.dmzreader.data.pdf.DownloadProgress
import se.c64.dmzreader.data.pdf.DownloadResult
import se.c64.dmzreader.data.pdf.PdfDocument
import se.c64.dmzreader.data.pdf.PdfRepository
import se.c64.dmzreader.data.pdf.RenderPriority
import se.c64.dmzreader.data.repository.LibraryRepository
import se.c64.dmzreader.domain.ReadAction
import se.c64.dmzreader.domain.ReaderPosition
import se.c64.dmzreader.domain.effectiveSpreadOffset
import se.c64.dmzreader.domain.fileSizeText
import se.c64.dmzreader.domain.normalizeSpreadOffset
import se.c64.dmzreader.domain.readAction
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
    ) : ReaderUiState {
        val pageCount: Int get() = document.pageCount
    }
}

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
    private val thumbnailRoot: File,
) : ViewModel() {
    private val _state = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val state: StateFlow<ReaderUiState> = _state

    // Läspositionen finns bara i minnet i FAS 3. ViewModel överlever rotation,
    // så positionen gör det också.
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
            val cached = pdfRepository.cachedFile(issueId)
            if (cached != null) {
                open(issue, cached)
                return@launch
            }
            _state.value = ReaderUiState.Downloading(issue, null)
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
            file.delete()
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
        _state.value = ReaderUiState.Reading(
            issue = issue,
            document = opened,
            position = ReaderPosition(1),
            spreadOffset = effectiveSpreadOffset(issue.spreadOffset, local?.detectedSpreadOffset),
            canShiftSpread = issue.spreadOffset == null,
        )
    }

    fun retry() = start()

    /** Avbryter en pågående nedladdning. Den påbörjade filen ligger kvar för återupptagning. */
    fun cancelDownload() {
        downloadJob?.cancel()
    }

    /** Läsaren visar nu skärm [screenIndex] (en sida, eller ett uppslag i liggande läge). */
    fun onScreenShown(screenIndex: Int, landscape: Boolean) {
        _state.update { current ->
            if (current !is ReaderUiState.Reading) return@update current
            current.copy(
                position = current.position.movedTo(screenIndex, landscape, current.pageCount, current.spreadOffset),
            )
        }
    }

    fun goToPage(page: Int) {
        _state.update { current ->
            if (current is ReaderUiState.Reading) {
                current.copy(position = ReaderPosition(page.coerceIn(1, current.pageCount)))
            } else {
                current
            }
        }
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
    suspend fun acquirePageBitmap(document: PdfDocument, pageNumber: Int, width: Int, height: Int): Bitmap {
        val key = pageKey(pageNumber, width, height)
        pageBitmaps.acquire(key)?.let { return it }
        return pageBitmaps.putAndAcquire(key, document.renderPage(pageNumber, width, height))
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
            val bitmap = document.renderPage(pageNumber, THUMBNAIL_WIDTH, height, RenderPriority.LOW)
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
        downloadJob?.cancel()
        pageBitmaps.clear()
        thumbnails.evictAll()
        document?.close()
    }

    companion object {
        const val THUMBNAIL_WIDTH = 160
        private const val THUMBNAIL_CACHE_BYTES = 12 * 1024 * 1024
    }
}
