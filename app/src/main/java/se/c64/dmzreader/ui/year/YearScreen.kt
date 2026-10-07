package se.c64.dmzreader.ui.year

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import se.c64.dmzreader.data.repository.LibraryRepository
import se.c64.dmzreader.domain.IssueWithState
import se.c64.dmzreader.domain.ReadStatus
import se.c64.dmzreader.domain.YearFilter
import se.c64.dmzreader.domain.readStatusText
import se.c64.dmzreader.ui.components.IssueCover
import se.c64.dmzreader.ui.components.OfflineBadge
import se.c64.dmzreader.ui.components.ProgressLine
import se.c64.dmzreader.ui.theme.DmzColors
import se.c64.dmzreader.ui.theme.MonoStyles

data class YearUiState(
    val year: Int,
    val publicationTitle: String? = null,
    /** Numren som visas, efter filtrering. */
    val issues: List<IssueWithState> = emptyList(),
    val issueCount: Int = 0,
    val readCount: Int = 0,
    val filter: YearFilter = YearFilter.ALL,
    val loading: Boolean = true,
)

class YearViewModel(
    repository: LibraryRepository,
    publicationId: String,
    year: Int,
) : ViewModel() {
    private val filter = MutableStateFlow(YearFilter.ALL)

    val state: StateFlow<YearUiState> =
        combine(repository.observeIssuesWithState(publicationId, year), filter) { issues, selected ->
            YearUiState(
                year = year,
                publicationTitle = issues.firstOrNull()?.issue?.publicationTitle,
                issues = issues.filter { selected.matches(it) },
                issueCount = issues.size,
                readCount = issues.count { it.progress.status == ReadStatus.READ },
                filter = selected,
                loading = false,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), YearUiState(year))

    fun setFilter(value: YearFilter) {
        filter.value = value
    }
}

/**
 * Årgångsvyn: stort årtal, filter och ett rutnät med omslag (planens E5).
 * Filterraden går att rulla i sidled.
 */
@Composable
fun YearScreen(
    viewModel: YearViewModel,
    imageLoader: ImageLoader,
    onOpenIssue: (issueId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Rutnätets läge sparas med vyn i navigationsstacken, så att man kommer
    // tillbaka till samma ställe från en nummervy.
    val gridState = rememberLazyGridState()

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
            YearHeader(state, onBack)
        }
        item(key = "filters", span = { GridItemSpan(maxLineSpan) }) {
            FilterRow(state.filter, viewModel::setFilter)
        }
        if (!state.loading && state.issues.isEmpty()) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "Inga nummer matchar filtret.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = DmzColors.TextSecondary,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
        items(state.issues, key = { it.issue.issueId }) { item ->
            IssueCell(item, imageLoader, onClick = { onOpenIssue(item.issue.issueId) })
        }
    }
}

@Composable
private fun FilterRow(selected: YearFilter, onSelect: (YearFilter) -> Unit) {
    // Raden är bredare än skärmen. Det valda chipet rullas alltid helt in i bild.
    val requesters = remember { YearFilter.entries.associateWith { BringIntoViewRequester() } }
    LaunchedEffect(selected) { requesters.getValue(selected).bringIntoView() }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        YearFilter.entries.forEach { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                label = { Text(filter.label, style = MaterialTheme.typography.labelLarge) },
                modifier = Modifier.heightIn(min = 48.dp).bringIntoViewRequester(requesters.getValue(filter)),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = DmzColors.Surface,
                    labelColor = DmzColors.TextSecondary,
                    selectedContainerColor = DmzColors.Accent,
                    selectedLabelColor = DmzColors.Background,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = filter == selected,
                    borderColor = DmzColors.BorderStrong,
                    selectedBorderColor = DmzColors.Accent,
                ),
            )
        }
    }
}

@Composable
private fun YearHeader(state: YearUiState, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Tillbaka",
                tint = DmzColors.TextPrimary,
            )
        }
        Column(modifier = Modifier.padding(start = 4.dp)) {
            state.publicationTitle?.let {
                Text(it.uppercase(), style = MonoStyles.Label, color = DmzColors.Label)
            }
            Text(
                text = state.year.toString(),
                style = MaterialTheme.typography.displaySmall,
                color = DmzColors.TextPrimary,
            )
            if (!state.loading) {
                Text(
                    text = "${state.readCount} / ${state.issueCount} lästa",
                    style = MonoStyles.Data,
                    color = DmzColors.TextSecondary,
                    modifier = Modifier.testTag("year-read-count"),
                )
            }
        }
    }
}

@Composable
private fun IssueCell(item: IssueWithState, imageLoader: ImageLoader, onClick: () -> Unit) {
    val issue = item.issue
    val progress = item.progress
    val read = progress.status == ReadStatus.READ
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = readStatusText(progress), onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Lästa nummer tonas ned, så att det olästa syns först.
        Box {
            IssueCover(issue, imageLoader, modifier = Modifier.fillMaxWidth().alpha(if (read) READ_ALPHA else 1f))
            if (item.isDownloaded) {
                OfflineBadge(modifier = Modifier.align(Alignment.TopEnd).padding(5.dp))
            }
        }
        Spacer(Modifier.height(5.dp))
        // Ett läst nummer visar fullt streck även om det är markerat manuellt.
        val fraction = if (read) 1f else if (progress.status == ReadStatus.UNREAD) 0f else progress.fraction ?: 0.05f
        ProgressLine(fraction, modifier = Modifier.alpha(if (fraction > 0f) 1f else 0f))
        Text(
            text = issue.issueLabel,
            style = MaterialTheme.typography.labelLarge,
            color = DmzColors.TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

private const val READ_ALPHA = 0.6f
