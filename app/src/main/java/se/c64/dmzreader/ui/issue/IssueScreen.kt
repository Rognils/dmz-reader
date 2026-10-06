package se.c64.dmzreader.ui.issue

import androidx.compose.foundation.clickable
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import se.c64.dmzreader.data.db.IssueEntity
import se.c64.dmzreader.data.repository.IssueDetails
import se.c64.dmzreader.data.repository.LibraryRepository
import se.c64.dmzreader.domain.ReadAction
import se.c64.dmzreader.domain.RelatedIssue
import se.c64.dmzreader.domain.fileSizeText
import se.c64.dmzreader.domain.pageCountText
import se.c64.dmzreader.domain.readAction
import se.c64.dmzreader.ui.components.DataRow
import se.c64.dmzreader.ui.components.IssueCover
import se.c64.dmzreader.ui.components.Panel
import se.c64.dmzreader.ui.theme.DmzColors
import se.c64.dmzreader.ui.theme.MonoStyles

data class IssueUiState(val loading: Boolean = true, val details: IssueDetails? = null)

class IssueViewModel(repository: LibraryRepository, issueId: String) : ViewModel() {
    val state: StateFlow<IssueUiState> = repository.observeIssueDetails(issueId)
        .map { IssueUiState(loading = false, details = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IssueUiState())
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
            details != null -> IssueContent(details.issue, details.related, imageLoader, onOpenIssue, onRead)
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
    imageLoader: ImageLoader,
    onOpenIssue: (String) -> Unit,
    onRead: (String) -> Unit,
) {
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
                    ReadAction.READ -> "Läs"
                    ReadAction.PDF_MISSING -> "PDF saknas"
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }

        val pages = pageCountText(issue.pageCount, issue.scanPageCount)
        val size = fileSizeText(issue.ocrPdfSize)
        if (pages != null || size != null) {
            Spacer(Modifier.height(16.dp))
            Panel {
                pages?.let { DataRow("Sidantal", it) }
                size?.let { DataRow("PDF-storlek", it) }
            }
        }

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
