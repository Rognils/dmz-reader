package se.c64.dmzreader.ui.reader

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import se.c64.dmzreader.ui.search.HitNavigationBar
import se.c64.dmzreader.ui.search.IssueSearchDialog
import se.c64.dmzreader.ui.search.IssueSearchViewModel
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import kotlinx.coroutines.launch
import se.c64.dmzreader.data.db.Bookmark
import se.c64.dmzreader.data.db.IssueEntity
import se.c64.dmzreader.data.pdf.DownloadProgress
import se.c64.dmzreader.data.pdf.PdfDocument
import se.c64.dmzreader.domain.downloadProgressText
import se.c64.dmzreader.domain.fullTitle
import se.c64.dmzreader.domain.ReadStatus
import se.c64.dmzreader.domain.ReadingProgress
import se.c64.dmzreader.domain.spreadsFor
import se.c64.dmzreader.ui.components.BookmarkEditDialog
import se.c64.dmzreader.ui.components.IssueCover
import se.c64.dmzreader.ui.components.StarIcon
import se.c64.dmzreader.ui.components.MonoLabel
import se.c64.dmzreader.ui.theme.DmzColors
import se.c64.dmzreader.ui.theme.MonoStyles
import kotlin.math.roundToInt

/** Läsning av ett nummer: nedladdningsvy vid behov, sedan läsaren (planens E7). */
@Composable
fun ReaderRoute(
    viewModel: ReaderViewModel,
    /** "Sök i numret" och träffraden. Följer med läsaren och försvinner när den stängs. */
    search: IssueSearchViewModel,
    imageLoader: ImageLoader,
    onClose: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val current = state) {
        ReaderUiState.Loading -> Box(Modifier.fillMaxSize().background(DmzColors.Background))

        is ReaderUiState.Downloading -> DownloadScreen(
            issue = current.issue,
            progress = current.progress,
            imageLoader = imageLoader,
            onCancel = {
                viewModel.cancelDownload()
                onClose()
            },
        )

        is ReaderUiState.Failed -> FailureScreen(
            issue = current.issue,
            message = current.message,
            imageLoader = imageLoader,
            onRetry = viewModel::retry.takeIf { current.canRetry },
            onClose = onClose,
        )

        is ReaderUiState.Reading -> ReaderScreen(current, viewModel, search, onClose)
    }
}

@Composable
private fun DownloadScreen(
    issue: IssueEntity,
    progress: DownloadProgress?,
    imageLoader: ImageLoader,
    onCancel: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    StatusLayout(issue, imageLoader) {
        MonoLabel("Hämtar numret")
        Spacer(Modifier.height(10.dp))
        val fraction = progress?.fraction
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(6.dp).testTag("download-progress"),
                color = DmzColors.Accent,
                trackColor = DmzColors.BorderStrong,
                drawStopIndicator = {},
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(6.dp).testTag("download-progress"),
                color = DmzColors.Accent,
                trackColor = DmzColors.BorderStrong,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = downloadProgressText(progress, issue.ocrPdfSize),
            style = MonoStyles.Data,
            color = DmzColors.TextPrimary,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text("Avbryt", color = DmzColors.TextPrimary)
        }
    }
}

@Composable
private fun FailureScreen(
    issue: IssueEntity?,
    message: String,
    imageLoader: ImageLoader,
    onRetry: (() -> Unit)?,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    StatusLayout(issue, imageLoader) {
        MonoLabel("Numret kunde inte öppnas", color = DmzColors.Failure)
        Spacer(Modifier.height(10.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = DmzColors.TextPrimary)
        Spacer(Modifier.height(24.dp))
        if (onRetry != null) {
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("Försök igen")
            }
            Spacer(Modifier.height(10.dp))
        }
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text("Tillbaka", color = DmzColors.TextPrimary)
        }
    }
}

/** Omslag och titel överst, innehåll under. Delas av nedladdningsvyn och felvyn. */
@Composable
private fun StatusLayout(
    issue: IssueEntity?,
    imageLoader: ImageLoader,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().background(DmzColors.Background).padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (issue != null) {
            IssueCover(issue, imageLoader, modifier = Modifier.fillMaxWidth(0.5f), cornerRadius = 8.dp)
            Spacer(Modifier.height(20.dp))
            Text(
                text = issue.fullTitle(),
                style = MaterialTheme.typography.titleLarge,
                color = DmzColors.TextPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
        }
        Column(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

/**
 * Sidangivelsen i toppraden: "Sida 7 av 28 · 25 % läst", eller "Sida 6–7 av 28 · 25 % läst"
 * för ett uppslag. Procenttalet är andelen lästa sidor (planens C10), inte läget i numret.
 */
fun pageIndicatorText(pages: List<Int>, currentPage: Int, pageCount: Int, pagesRead: Int): String {
    val label = if (pages.size == 2) "${pages[0]}–${pages[1]}" else "${pages.firstOrNull() ?: currentPage}"
    val percent = ReadingProgress(ReadStatus.STARTED, ReadStatus.STARTED, null, pagesRead, pageCount).percent ?: 0
    return "Sida $label av $pageCount · $percent % läst"
}

@Composable
private fun ReaderScreen(
    state: ReaderUiState.Reading,
    viewModel: ReaderViewModel,
    search: IssueSearchViewModel,
    onClose: () -> Unit,
) {
    // I HQ-läge visas en sida i taget, även när telefonen ligger ned (planens FAS 11).
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE && !state.hqMode
    val hqProgress by viewModel.hqProgress.collectAsStateWithLifecycle()
    var controlsVisible by rememberSaveable { mutableStateOf(false) }
    val searchState by search.state.collectAsStateWithLifecycle()
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Bokmärken: ett tryck på stjärnan ger en snackbar med en åtgärd.
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<Bookmark?>(null) }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            // Ett nytt meddelande ersätter det som visas.
            snackbar.currentSnackbarData?.dismiss()
            launch {
                when (message) {
                    is ReaderMessage.BookmarkAdded -> {
                        val result = snackbar.showSnackbar("Bokmärke sparat", "Lägg till titel", duration = SnackbarDuration.Long)
                        if (result == SnackbarResult.ActionPerformed) editing = message.bookmark
                    }

                    is ReaderMessage.BookmarkRemoved -> {
                        val result = snackbar.showSnackbar("Bokmärket borttaget", "Ångra", duration = SnackbarDuration.Long)
                        if (result == SnackbarResult.ActionPerformed) viewModel.restoreBookmarks(message.bookmarks)
                    }
                }
            }
        }
    }

    ImmersiveMode()
    BackHandler(onBack = onClose)

    // Läspositionen skrivs när läsaren lämnar skärmen: vid stängning, när appen
    // går i bakgrunden och när skärmen släcks. Vid rotation fortsätter sessionen.
    val activity = LocalActivity.current
    LifecycleStartEffect(viewModel) {
        viewModel.onForeground()
        onStopOrDispose { viewModel.onBackground(activity?.isChangingConfigurations == true) }
    }

    val screens = remember(landscape, state.pageCount, state.spreadOffset) {
        if (landscape) {
            spreadsFor(state.pageCount, state.spreadOffset).map { it.pages }
        } else {
            (1..state.pageCount).map { listOf(it) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DmzColors.ReaderBackground)
            .testTag("reader")
            // Varje beröring räknas som en interaktion för lästiden: tryck, zoom, panorering
            // och bläddring. Händelsen bara iakttas och går vidare orörd till sidorna.
            .pointerInput(viewModel) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    viewModel.onInteraction()
                }
            },
    ) {
        // Ny pager när läget eller parningen ändras, placerad på skärmen som innehåller läspositionen.
        key(landscape, state.spreadOffset) {
            val pagerState = rememberPagerState(
                initialPage = state.position.screenIndex(landscape, state.pageCount, state.spreadOffset),
            ) { screens.size }

            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.settledPage }.collect { index ->
                    viewModel.onScreenShown(index, landscape, screens.getOrNull(index).orEmpty())
                }
            }
            // Renderingskön får veta vart läsaren är på väg redan när sidbytet börjar.
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.targetPage }.collect { index ->
                    viewModel.onScreenTargeted(
                        current = screens.getOrNull(index).orEmpty(),
                        next = screens.getOrNull(index + 1).orEmpty(),
                        previous = screens.getOrNull(index - 1).orEmpty(),
                    )
                }
            }
            // Snabbhopp och miniatyrer ändrar positionen utifrån.
            val target = state.position.screenIndex(landscape, state.pageCount, state.spreadOffset)
            LaunchedEffect(target) {
                if (pagerState.settledPage != target && !pagerState.isScrollInProgress) {
                    pagerState.scrollToPage(target)
                }
            }

            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize(),
                key = { screens[it].first() },
            ) { index ->
                // En sida vars skanning är hämtad visas ur skanningen. Alla andra visas ur PDF:en.
                val hqImage = (state.hqPages[screens[index].singleOrNull()] as? HqPageState.Ready)?.image
                    .takeIf { state.hqMode }
                val pdfSource = remember(state.document) {
                    PageSource(state.document, state.document::pageSize, state.document::renderRegion)
                }
                val source = remember(hqImage, pdfSource) {
                    if (hqImage == null) {
                        pdfSource
                    } else {
                        // Skanningen har fler pixlar än PDF-sidan: utsnittet renderas ända upp till högsta zoom.
                        PageSource(hqImage, { hqImage.size }, hqImage::renderRegion, maxRenderZoom = MAX_ZOOM)
                    }
                }
                ZoomablePages(
                    pages = screens[index],
                    source = source,
                    isCurrent = pagerState.settledPage == index,
                    acquirePage = { page, width, height, preview ->
                        if (hqImage != null) {
                            viewModel.acquireHqBitmap(hqImage, width, height)
                        } else {
                            viewModel.acquirePageBitmap(state.document, page, width, height, preview)
                        }
                    },
                    releasePage = { page, width, height, bitmap ->
                        val key = if (hqImage != null) viewModel.hqKey(hqImage, width, height) else viewModel.pageKey(page, width, height)
                        viewModel.pageBitmaps.release(key, bitmap)
                    },
                    recycleLater = viewModel.pageBitmaps::recycleLater,
                    onPageShown = viewModel::onPageShown,
                    onTap = { fractionX ->
                        when {
                            fractionX < 0.3f -> scope.launch {
                                pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0))
                            }
                            fractionX > 0.7f -> scope.launch {
                                pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceAtMost(screens.lastIndex))
                            }
                            else -> controlsVisible = !controlsVisible
                        }
                    },
                )
            }
        }

        val shownPages = screens.getOrNull(state.position.screenIndex(landscape, state.pageCount, state.spreadOffset))
            ?: listOf(state.position.page)

        Column(modifier = Modifier.align(Alignment.TopCenter)) {
            AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut()) {
                ReaderTopBar(
                    title = state.issue.fullTitle(),
                    indicator = pageIndicatorText(shownPages, state.position.page, state.pageCount, state.pagesRead),
                    showSpreadShift = landscape && state.canShiftSpread,
                    onShiftSpread = viewModel::shiftSpread,
                    bookmarked = state.pageIsBookmarked,
                    bookmarkPage = state.position.page,
                    onToggleBookmark = viewModel::toggleBookmark,
                    onSearch = { searchOpen = true }.takeIf { searchState.indexAvailable && searchState.inIndex },
                    hqSupported = state.hqSupported,
                    hqMode = state.hqMode,
                    onToggleHq = viewModel::toggleHq,
                    onClose = onClose,
                )
            }
            // Träffraden: syns även när kontrollerna är dolda, tills den stängs.
            if (searchState.barVisible && searchState.query != null && !searchState.searching) {
                HitNavigationBar(
                    text = searchState.barText,
                    enabled = searchState.hits.size > 1,
                    onPrevious = { search.previous()?.let(viewModel::goToPage) },
                    onNext = { search.next()?.let(viewModel::goToPage) },
                    onClose = search::closeBar,
                    modifier = Modifier.windowInsetsPadding(if (controlsVisible) WindowInsets(0) else WindowInsets.displayCutout),
                )
            }
            // HQ-raden: förlopp, "HQ" när skanningen visas, eller varför PDF-sidan visas i stället.
            if (state.hqMode) {
                val hqState = state.hqPages[state.position.page]
                hqStatusText(hqState, hqProgress)?.let { text ->
                    HqStatusBar(
                        text = text,
                        fraction = hqProgress?.fraction.takeIf { hqState is HqPageState.Loading },
                        loading = hqState is HqPageState.Loading,
                        failed = hqState is HqPageState.Failed,
                        onRetry = viewModel::retryHq,
                        modifier = Modifier.windowInsetsPadding(
                            if (controlsVisible || searchState.barVisible) WindowInsets(0) else WindowInsets.displayCutout,
                        ),
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderBottomBar(
                document = state.document,
                currentPages = shownPages,
                pageCount = state.pageCount,
                loadThumbnail = { page -> viewModel.thumbnail(state.document, page) },
                onGoToPage = viewModel::goToPage,
            )
        }

        // Ovanför miniatyrremsan när kontrollerna visas, annars längst ned.
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(bottom = if (controlsVisible) 190.dp else 16.dp),
        )
    }

    if (searchOpen) {
        IssueSearchDialog(
            viewModel = search,
            onOpenPage = { page ->
                searchOpen = false
                viewModel.goToPage(page)
            },
            onDismiss = { searchOpen = false },
        )
    }

    editing?.let { bookmark ->
        BookmarkEditDialog(
            bookmark = bookmark,
            onSave = { title, note ->
                viewModel.editBookmark(bookmark.id, title, note)
                editing = null
            },
            onDelete = null,
            onDismiss = { editing = null },
        )
    }
}

/** Helskärm utan systemfält, och skärmen hålls tänd medan man läser. */
@Composable
private fun ImmersiveMode() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
        }
    }
    // Systemet kan visa fälten igen när appen har legat i bakgrunden. Dölj dem
    // på nytt varje gång läsaren kommer tillbaka.
    LifecycleResumeEffect(view) {
        val window = (view.context as? Activity)?.window
        window?.let { WindowCompat.getInsetsController(it, view).hide(WindowInsetsCompat.Type.systemBars()) }
        onPauseOrDispose { }
    }
}

private val ControlsBackground = Color(0xE616181D)

@Composable
private fun ReaderTopBar(
    title: String,
    indicator: String,
    showSpreadShift: Boolean,
    onShiftSpread: () -> Unit,
    bookmarked: Boolean,
    bookmarkPage: Int,
    onToggleBookmark: () -> Unit,
    /** null när numret inte går att söka i. Då visas ingen sökknapp. */
    onSearch: (() -> Unit)?,
    hqSupported: Boolean,
    hqMode: Boolean,
    onToggleHq: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ControlsBackground)
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Stäng läsaren", tint = DmzColors.TextPrimary)
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = DmzColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = indicator,
                style = MonoStyles.DataSmall,
                color = DmzColors.TextSecondary,
                modifier = Modifier.testTag("page-indicator"),
            )
        }
        if (showSpreadShift) {
            TextButton(onClick = onShiftSpread, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Flytta uppslag", color = DmzColors.Accent, style = MaterialTheme.typography.labelLarge)
            }
        }
        if (onSearch != null) {
            IconButton(onClick = onSearch, modifier = Modifier.size(48.dp).testTag("reader-search")) {
                Icon(Icons.Filled.Search, contentDescription = "Sök i numret", tint = DmzColors.TextPrimary)
            }
        }
        // Fylld stjärna: sidan har ett bokmärke, och ett tryck tar bort det.
        IconButton(
            onClick = onToggleBookmark,
            modifier = Modifier.size(48.dp).testTag("bookmark-toggle").semantics {
                contentDescription = if (bookmarked) {
                    "Ta bort bokmärket på sida $bookmarkPage"
                } else {
                    "Sätt bokmärke på sida $bookmarkPage"
                }
            },
        ) {
            StarIcon(filled = bookmarked, tint = if (bookmarked) DmzColors.Accent else DmzColors.TextPrimary)
        }
        // HQ-läget för det öppna numret: sidan som visas hämtas som originalskanning.
        TextButton(
            onClick = onToggleHq,
            enabled = hqSupported,
            modifier = Modifier.heightIn(min = 48.dp).testTag("hq-toggle").semantics {
                contentDescription = when {
                    !hqSupported -> "HQ finns inte för det här numret"
                    hqMode -> "Stäng av HQ-läget"
                    else -> "Slå på HQ-läget"
                }
            },
        ) {
            Text(
                "HQ",
                color = when {
                    !hqSupported -> DmzColors.Label.copy(alpha = 0.5f)
                    hqMode -> DmzColors.Accent
                    else -> DmzColors.TextPrimary
                },
                style = MonoStyles.Data,
            )
        }
    }
}

/** Raden under toppraden i HQ-läge. Trycket går igenom till sidan, utom på "Försök igen". */
@Composable
private fun HqStatusBar(
    text: String,
    fraction: Float?,
    loading: Boolean,
    failed: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().background(ControlsBackground).testTag("hq-status")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (failed) 2.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = if (failed) MaterialTheme.typography.bodyMedium else MonoStyles.DataSmall,
                color = if (failed) DmzColors.Failure else DmzColors.TextPrimary,
                modifier = Modifier.weight(1f).testTag("hq-status-text"),
            )
            if (failed) {
                TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp).testTag("hq-retry")) {
                    Text("Försök igen", color = DmzColors.Accent, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (loading) {
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(3.dp).testTag("hq-progress"),
                    color = DmzColors.Accent,
                    trackColor = DmzColors.BorderStrong,
                    drawStopIndicator = {},
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(3.dp).testTag("hq-progress"),
                    color = DmzColors.Accent,
                    trackColor = DmzColors.BorderStrong,
                )
            }
        }
    }
}

@Composable
private fun ReaderBottomBar(
    document: PdfDocument,
    currentPages: List<Int>,
    pageCount: Int,
    loadThumbnail: suspend (Int) -> Bitmap?,
    onGoToPage: (Int) -> Unit,
) {
    val currentPage = currentPages.first()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ControlsBackground)
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(top = 10.dp, bottom = 12.dp),
    ) {
        // Miniatyrremsan visar de faktiska PDF-sidorna. Bara de som syns renderas.
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentPage - 3).coerceAtLeast(0))
        LaunchedEffect(currentPage) {
            listState.animateScrollToItem((currentPage - 3).coerceAtLeast(0))
        }
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().testTag("thumbnail-strip"),
        ) {
            items(count = pageCount, key = { it + 1 }) { index ->
                val page = index + 1
                PageThumbnail(
                    page = page,
                    selected = page in currentPages,
                    document = document,
                    loadThumbnail = loadThumbnail,
                    onClick = { onGoToPage(page) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        // Reglaget är både förloppsvisning och snabbhopp: läget visar var i numret man är.
        var dragging by remember { mutableStateOf(false) }
        var dragValue by remember { mutableFloatStateOf(currentPage.toFloat()) }
        val shown = if (dragging) dragValue.roundToInt() else currentPage
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$shown", style = MonoStyles.Data, color = DmzColors.TextPrimary, modifier = Modifier.width(40.dp))
            Slider(
                value = if (dragging) dragValue else currentPage.toFloat(),
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    dragging = false
                    onGoToPage(dragValue.roundToInt())
                },
                valueRange = 1f..pageCount.coerceAtLeast(2).toFloat(),
                modifier = Modifier.weight(1f).semantics { contentDescription = "Gå till sida" },
                colors = SliderDefaults.colors(
                    thumbColor = DmzColors.Accent,
                    activeTrackColor = DmzColors.Accent,
                    inactiveTrackColor = DmzColors.BorderStrong,
                ),
            )
            Text(
                "$pageCount",
                style = MonoStyles.Data,
                color = DmzColors.TextSecondary,
                textAlign = TextAlign.End,
                modifier = Modifier.width(40.dp),
            )
        }
    }
}

@Composable
private fun PageThumbnail(
    page: Int,
    selected: Boolean,
    document: PdfDocument,
    loadThumbnail: suspend (Int) -> Bitmap?,
    onClick: () -> Unit,
) {
    val bitmap by produceState<Bitmap?>(null, document, page) { value = loadThumbnail(page) }
    val shape = RoundedCornerShape(4.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(56.dp)
            .clickable(role = Role.Button, onClickLabel = "Gå till sida $page", onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 78.dp)
                .clip(shape)
                .background(DmzColors.Surface)
                .border(2.dp, if (selected) DmzColors.Accent else DmzColors.Border, shape),
        ) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Sida $page",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(2.dp),
                )
            }
        }
        Text(
            text = "$page",
            style = MonoStyles.DataSmall,
            color = if (selected) DmzColors.Accent else DmzColors.Label,
        )
    }
}
