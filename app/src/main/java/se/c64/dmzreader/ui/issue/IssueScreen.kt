package se.c64.dmzreader.ui.issue

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
    /** Användarens läsdata och gällande sidantal. null tills numret är inläst. */
    val reading: IssueWithState? = null,
)

class IssueViewModel(
    repository: LibraryRepository,
    private val readingRepository: ReadingRepository,
    private val issueId: String,
) : ViewModel() {
    val state: StateFlow<IssueUiState> =
        combine(repository.observeIssueDetails(issueId), repository.observeIssueWithState(issueId)) { details, reading ->
            IssueUiState(loading = false, details = details, reading = reading)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IssueUiState())

    /**
     * Manuell lässtatus (planens C10): READ, UNREAD eller null för automatisk
     * status. Läspositionen och de lästa sidorna ändras inte.
     */
    fun setReadStatus(override: ReadStatusOverride?) {
        val pageCount = state.value.reading?.pageCount
        viewModelScope.launch { readingRepository.setOverride(issueId, override, pageCount) }
    }
}

/** Nummervyn (planens E6). Permanent nedladdning kommer i FAS 5. */
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
