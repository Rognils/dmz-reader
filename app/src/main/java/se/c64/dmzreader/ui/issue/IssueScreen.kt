package se.c64.dmzreader.ui.issue

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import se.c64.dmzreader.data.db.DownloadState
import se.c64.dmzreader.data.offline.DownloadRequest
import se.c64.dmzreader.data.offline.OfflineRepository
import se.c64.dmzreader.data.pdf.DownloadProgress
import se.c64.dmzreader.data.settings.DownloadPreferences
import se.c64.dmzreader.domain.downloadProgressText
import se.c64.dmzreader.offline.DownloadScheduler
import se.c64.dmzreader.ui.components.OfflineBadge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.c64.dmzreader.data.db.IssueEntity
import se.c64.dmzreader.data.db.ReadStatusOverride
import se.c64.dmzreader.data.repository.IssueDetails
import se.c64.dmzreader.data.repository.LibraryRepository
import se.c64.dmzreader.data.repository.ReadingRepository
import se.c64.dmzreader.domain.IssueWithState
import se.c64.dmzreader.domain.ReadAction
import se.c64.dmzreader.domain.RelatedIssue
import se.c64.dmzreader.domain.fileSizeText
import se.c64.dmzreader.domain.pageCountText
import se.c64.dmzreader.domain.readAction
import se.c64.dmzreader.domain.readStatusText
import se.c64.dmzreader.domain.readingProgress
import se.c64.dmzreader.ui.components.DataRow
import se.c64.dmzreader.ui.components.IssueCover
import se.c64.dmzreader.ui.components.Panel
import se.c64.dmzreader.ui.components.ProgressLine
import se.c64.dmzreader.ui.theme.DmzColors
import se.c64.dmzreader.ui.theme.MonoStyles

data class IssueUiState(
    val loading: Boolean = true,
    val details: IssueDetails? = null,
    /** Användarens läsdata, nedladdningsstatus och gällande sidantal. null tills numret är inläst. */
    val reading: IssueWithState? = null,
    /** Förloppet, om det är det här numret som laddas ned just nu. */
    val downloadProgress: DownloadProgress? = null,
)

class IssueViewModel(
    repository: LibraryRepository,
    private val readingRepository: ReadingRepository,
    private val offlineRepository: OfflineRepository,
    private val downloadScheduler: DownloadScheduler,
    private val downloadPreferences: DownloadPreferences,
    private val issueId: String,
) : ViewModel() {
    val state: StateFlow<IssueUiState> = combine(
        repository.observeIssueDetails(issueId),
        repository.observeIssueWithState(issueId),
        offlineRepository.active,
    ) { details, reading, active ->
        IssueUiState(
            loading = false,
            details = details,
            reading = reading,
            downloadProgress = active?.takeIf { it.issueId == issueId }?.progress,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IssueUiState())

    /** Falskt på Android 8–13, där nedladdningen bara pågår medan appen är öppen. */
    val downloadsRunInBackground: Boolean get() = downloadScheduler.runsInBackground

    /** Sant första gången användaren laddar ned något: då ställs frågan om notiser. */
    suspend fun shouldAskAboutNotifications(): Boolean = !downloadPreferences.notificationQuestionShown()

    fun notificationQuestionShown() {
        viewModelScope.launch { downloadPreferences.setNotificationQuestionShown() }
    }

    /** Laddar ned numret för läsning utan nät. Finns det i läs-cachen flyttas det, utan nätverkstrafik. */
    fun download() {
        viewModelScope.launch {
            if (offlineRepository.requestDownload(issueId) == DownloadRequest.QUEUED) downloadScheduler.start()
        }
    }

    fun cancelDownload() {
        viewModelScope.launch { offlineRepository.cancel(issueId) }
    }

    fun removeDownload() {
        viewModelScope.launch { offlineRepository.remove(issueId) }
    }

    /**
     * Manuell lässtatus (planens C10): READ, UNREAD eller null för automatisk
     * status. Läspositionen och de lästa sidorna ändras inte.
     */
    fun setReadStatus(override: ReadStatusOverride?) {
        val pageCount = state.value.reading?.pageCount
        viewModelScope.launch { readingRepository.setOverride(issueId, override, pageCount) }
    }
}

/** Nummervyn (planens E6). */
@Composable
fun IssueScreen(
    viewModel: IssueViewModel,
    imageLoader: ImageLoader,
    onOpenIssue: (issueId: String) -> Unit,
    onRead: (issueId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val details = state.details

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        IconButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp, top = 8.dp).size(48.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Tillbaka",
                tint = DmzColors.TextPrimary,
            )
        }
        when {
            details != null -> IssueContent(
                issue = details.issue,
                related = details.related,
                reading = state.reading,
                imageLoader = imageLoader,
                onOpenIssue = onOpenIssue,
                onRead = onRead,
                onSetReadStatus = viewModel::setReadStatus,
                download = {
                    if (details.issue.readAction() == ReadAction.READ) {
                        DownloadSection(
                            state = state.reading?.downloadState ?: DownloadState.NONE,
                            progress = state.downloadProgress,
                            downloadedBytes = state.reading?.local?.downloadedBytes,
                            expectedBytes = details.issue.ocrPdfSize,
                            runsInBackground = viewModel.downloadsRunInBackground,
                            shouldAskAboutNotifications = viewModel::shouldAskAboutNotifications,
                            onNotificationQuestionShown = viewModel::notificationQuestionShown,
                            onDownload = viewModel::download,
                            onCancel = viewModel::cancelDownload,
                            onRemove = viewModel::removeDownload,
                        )
                    }
                },
            )
            !state.loading -> Text(
                text = "Numret finns inte i det aktuella arkivindexet.",
                style = MaterialTheme.typography.bodyLarge,
                color = DmzColors.TextSecondary,
                modifier = Modifier.padding(20.dp),
            )
        }
    }
}

@Composable
private fun IssueContent(
    issue: IssueEntity,
    related: RelatedIssue?,
    reading: IssueWithState?,
    imageLoader: ImageLoader,
    onOpenIssue: (String) -> Unit,
    onRead: (String) -> Unit,
    onSetReadStatus: (ReadStatusOverride?) -> Unit,
    download: @Composable () -> Unit,
) {
    val progress = reading?.progress ?: readingProgress(null, null)
    val lastPage = reading?.state?.lastPage
    Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            IssueCover(issue, imageLoader, modifier = Modifier.fillMaxWidth(0.62f), cornerRadius = 8.dp)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = "${issue.publicationTitle} ${issue.year}".uppercase(),
            style = MonoStyles.Label,
            color = DmzColors.Label,
        )
        Text(
            text = issue.issueLabel,
            style = MaterialTheme.typography.displaySmall,
            color = DmzColors.TextPrimary,
        )
        Spacer(Modifier.height(16.dp))

        val action = issue.readAction()
        Button(
            onClick = { onRead(issue.issueId) },
            enabled = action == ReadAction.READ,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = DmzColors.Accent,
                contentColor = DmzColors.Background,
                disabledContainerColor = DmzColors.Surface,
                disabledContentColor = DmzColors.TextSecondary,
            ),
        ) {
            Text(
                text = when (action) {
                    ReadAction.READ -> lastPage?.let { "Fortsätt på sida $it" } ?: "Läs"
                    ReadAction.PDF_MISSING -> "PDF saknas"
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }

        download()

        val pages = pageCountText(reading?.pageCount ?: issue.pageCount, issue.scanPageCount)
        val size = fileSizeText(issue.ocrPdfSize)
        Spacer(Modifier.height(16.dp))
        Panel {
            pages?.let { DataRow("Sidantal", it) }
            size?.let { DataRow("PDF-storlek", it) }
            // Procent visas bara när sidantalet är känt; annars bara status.
            DataRow("Lässtatus", readStatusText(progress))
            if (progress.pagesRead > 0) {
                DataRow(
                    label = "Lästa sidor",
                    value = progress.pageCount?.let { "${progress.pagesRead} av $it" } ?: "${progress.pagesRead}",
                )
                progress.fraction?.let {
                    Spacer(Modifier.height(6.dp))
                    ProgressLine(it)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        ReadStatusActions(progress.override, onSetReadStatus)

        if (related != null) {
            Spacer(Modifier.height(12.dp))
            Panel(modifier = Modifier.clickable(role = Role.Button) { onOpenIssue(related.issueId) }) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = related.linkText,
                        style = MaterialTheme.typography.titleMedium,
                        color = DmzColors.Accent,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = DmzColors.Accent,
                    )
                }
            }
        }
    }
}

/**
 * Manuell lässtatus. Bara de val som ändrar något visas: den markering som
 * redan gäller erbjuds inte igen, och "Återställ" finns bara när en markering är satt.
 */
@Composable
private fun ReadStatusActions(current: ReadStatusOverride?, onSet: (ReadStatusOverride?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (current != ReadStatusOverride.READ) {
                StatusButton("Markera som läst", Modifier.weight(1f)) { onSet(ReadStatusOverride.READ) }
            }
            if (current != ReadStatusOverride.UNREAD) {
                StatusButton("Markera som oläst", Modifier.weight(1f)) { onSet(ReadStatusOverride.UNREAD) }
            }
        }
        if (current != null) {
            StatusButton("Återställ", Modifier.fillMaxWidth()) { onSet(null) }
        }
    }
}

@Composable
private fun StatusButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        border = BorderStroke(1.dp, DmzColors.BorderStrong),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = DmzColors.TextPrimary, maxLines = 1)
    }
}

/**
 * Nedladdning för läsning utan nät: Ladda ned, Köad, Laddar ned X % (med
 * Avbryt), Nedladdad · storlek och Ta bort nedladdning (med bekräftelse).
 */
@Composable
private fun DownloadSection(
    state: DownloadState,
    progress: DownloadProgress?,
    downloadedBytes: Long?,
    expectedBytes: Long?,
    runsInBackground: Boolean,
    shouldAskAboutNotifications: suspend () -> Boolean,
    onNotificationQuestionShown: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var explainNotifications by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    // Nedladdningen startar oavsett svaret. Utan behörigheten visas bara ingen notis.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onDownload() }

    fun startDownload() {
        scope.launch {
            val needsPermission = Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            if (needsPermission && shouldAskAboutNotifications()) explainNotifications = true else onDownload()
        }
    }

    Spacer(Modifier.height(12.dp))
    Panel(modifier = Modifier.testTag("download-section")) {
        when (state) {
            DownloadState.NONE -> {
                StatusButton("Ladda ned", Modifier.fillMaxWidth(), onClick = ::startDownload)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Sparar numret på telefonen, så att det går att läsa utan nät.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DmzColors.TextSecondary,
                )
            }

            DownloadState.QUEUED, DownloadState.DOWNLOADING -> {
                val downloading = state == DownloadState.DOWNLOADING
                val percent = progress?.fraction?.let { (it * 100).toInt() }
                Text(
                    text = when {
                        !downloading -> "Köad"
                        percent != null -> "Laddar ned $percent %"
                        else -> "Laddar ned …"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = DmzColors.TextPrimary,
                    modifier = Modifier.testTag("download-status"),
                )
                if (downloading) {
                    Spacer(Modifier.height(8.dp))
                    ProgressLine(progress?.fraction ?: 0f)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = downloadProgressText(progress, expectedBytes),
                        style = MonoStyles.DataSmall,
                        color = DmzColors.TextSecondary,
                    )
                } else {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (runsInBackground) {
                            "Numret hämtas när det blir dess tur och nätet tillåter."
                        } else {
                            "Numret hämtas när det blir dess tur."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = DmzColors.TextSecondary,
                    )
                }
                if (!runsInBackground) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Håll appen öppen tills nedladdningen är klar.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = DmzColors.Accent,
                    )
                }
                Spacer(Modifier.height(10.dp))
                StatusButton("Avbryt", Modifier.fillMaxWidth(), onClick = onCancel)
            }

            DownloadState.DOWNLOADED -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OfflineBadge()
                    Text(
                        text = "Nedladdad" + (fileSizeText(downloadedBytes)?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.titleMedium,
                        color = DmzColors.TextPrimary,
                        modifier = Modifier.padding(start = 10.dp).testTag("download-status"),
                    )
                }
                Spacer(Modifier.height(10.dp))
                StatusButton("Ta bort nedladdning", Modifier.fillMaxWidth()) { confirmRemove = true }
            }

            DownloadState.FAILED -> {
                Text(
                    text = "Nedladdningen misslyckades",
                    style = MaterialTheme.typography.titleMedium,
                    color = DmzColors.Failure,
                    modifier = Modifier.testTag("download-status"),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusButton("Försök igen", Modifier.weight(1f), onClick = onDownload)
                    StatusButton("Avbryt", Modifier.weight(1f), onClick = onCancel)
                }
            }
        }
    }

    if (explainNotifications) {
        AlertDialog(
            onDismissRequest = { explainNotifications = false },
            title = { Text("Visa nedladdningen i en notis?") },
            text = {
                Text(
                    "Nedladdningen fortsätter när du lämnar appen. Med en notis ser du hur långt den har " +
                        "kommit och kan avbryta den. Appen skickar inga andra notiser.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    explainNotifications = false
                    onNotificationQuestionShown()
                    permission.launch(POST_NOTIFICATIONS)
                }) { Text("Fortsätt") }
            },
            dismissButton = {
                TextButton(onClick = {
                    explainNotifications = false
                    onNotificationQuestionShown()
                    onDownload()
                }) { Text("Utan notis") }
            },
        )
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Ta bort nedladdningen?") },
            text = { Text("Filen tas bort från telefonen. Läsposition och lästa sidor finns kvar.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    onRemove()
                }) { Text("Ta bort") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Behåll") } },
        )
    }
}

// Behörigheten finns från Android 13. Frågan ställs bara där.
private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
