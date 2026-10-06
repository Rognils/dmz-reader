package se.c64.dmzreader.ui.year

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import se.c64.dmzreader.data.db.IssueEntity
import se.c64.dmzreader.data.repository.LibraryRepository
import se.c64.dmzreader.ui.components.IssueCover
import se.c64.dmzreader.ui.theme.DmzColors
import se.c64.dmzreader.ui.theme.MonoStyles

data class YearUiState(
    val year: Int,
    val publicationTitle: String? = null,
    val issues: List<IssueEntity> = emptyList(),
    val loading: Boolean = true,
)

class YearViewModel(
    repository: LibraryRepository,
    publicationId: String,
    year: Int,
) : ViewModel() {
    val state: StateFlow<YearUiState> = repository.observeIssues(publicationId, year)
        .map { issues ->
            YearUiState(
                year = year,
                publicationTitle = issues.firstOrNull()?.publicationTitle,
                issues = issues,
                loading = false,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), YearUiState(year))
}

/**
 * Årgångsvyn: stort årtal och ett rutnät med omslag (planens E5).
 * Filterchips och progress läggs till i FAS 4–5, när det finns data för dem.
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
        items(state.issues, key = { it.issueId }) { issue ->
            IssueCell(issue, imageLoader, onClick = { onOpenIssue(issue.issueId) })
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
                Text("${state.issues.size} nummer", style = MonoStyles.Data, color = DmzColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun IssueCell(issue: IssueEntity, imageLoader: ImageLoader, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IssueCover(issue, imageLoader, modifier = Modifier.fillMaxWidth())
        Text(
            text = issue.issueLabel,
            style = MaterialTheme.typography.labelLarge,
            color = DmzColors.TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
