package se.c64.dmzreader.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import se.c64.dmzreader.data.repository.BookmarkRepository
import se.c64.dmzreader.data.repository.LibraryRepository
import se.c64.dmzreader.domain.BookmarkHit
import se.c64.dmzreader.domain.IssueWithState
import se.c64.dmzreader.domain.SearchFilters
import se.c64.dmzreader.domain.SearchResults
import se.c64.dmzreader.domain.bookmarkLabel
import se.c64.dmzreader.domain.fullTitle
import se.c64.dmzreader.domain.parseMetadataQuery
import se.c64.dmzreader.domain.readStatusText
import se.c64.dmzreader.domain.searchMetadata
import se.c64.dmzreader.ui.components.IssueCover
import se.c64.dmzreader.ui.components.OfflineBadge
import se.c64.dmzreader.ui.components.Panel
import se.c64.dmzreader.ui.components.StarIcon
import se.c64.dmzreader.ui.theme.DmzColors
import se.c64.dmzreader.ui.theme.MonoStyles

data class SearchUiState(
    val text: String = "",
    val filters: SearchFilters = SearchFilters(),
    val results: SearchResults = SearchResults(),
) {
    /** Sant när användaren har bett om något: skrivit text eller valt ett filter. */
    val active: Boolean get() = text.isNotBlank() || filters.any
}

/** Metadata-sökning (planens E10, steg 1). Söker i katalogen och bokmärkena, inte i tidningarnas text. */
class SearchViewModel(
    libraryRepository: LibraryRepository,
    bookmarkRepository: BookmarkRepository,
) : ViewModel() {
    private val text = MutableStateFlow("")
    private val filters = MutableStateFlow(SearchFilters())

    val state: StateFlow<SearchUiState> = combine(
        text,
        filters,
        libraryRepository.observeIssuesWithState(),
        bookmarkRepository.observeAll(),
    ) { text, filters, issues, bookmarks ->
        SearchUiState(text, filters, searchMetadata(parseMetadataQuery(text), filters, issues, bookmarks))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun setText(value: String) {
        text.value = value
    }

    fun toggleFavorites() = filters.update { it.copy(favoritesOnly = !it.favoritesOnly) }
    fun toggleDownloaded() = filters.update { it.copy(downloadedOnly = !it.downloadedOnly) }
}

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    imageLoader: ImageLoader,
    onOpenIssue: (issueId: String) -> Unit,
    onReadPage: (issueId: String, pageNumber: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("search"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 24.dp),
    ) {
        item(key = "header") {
            Text("Sök", style = MaterialTheme.typography.headlineMedium, color = DmzColors.TextPrimary)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.text,
                onValueChange = viewModel::setText,
                singleLine = true,
                placeholder = { Text("År, nummer, publikation eller bokmärke") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = DmzColors.Label) },
                trailingIcon = {
                    if (state.text.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setText("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Töm sökfältet", tint = DmzColors.Label)
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth().testTag("search-field"),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickFilter("Favoriter", state.filters.favoritesOnly, viewModel::toggleFavorites)
                QuickFilter("Nedladdade", state.filters.downloadedOnly, viewModel::toggleDownloaded)
            }
            Spacer(Modifier.height(12.dp))
        }

        when {
            !state.active -> item(key = "hint") { SearchHint() }

            state.results.isEmpty -> item(key = "none") {
                Text(
                    text = "Inga träffar. Sökningen gäller år, nummer, publikation och dina bokmärken, " +
                        "inte texten i tidningarna.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = DmzColors.TextSecondary,
                    modifier = Modifier.testTag("search-empty"),
                )
            }

            else -> {
                if (state.results.bookmarks.isNotEmpty()) {
                    item(key = "bookmarks-title") { ResultTitle("Bokmärken", state.results.bookmarks.size) }
                    items(state.results.bookmarks, key = { "b-${it.bookmark.id}" }) { hit ->
                        BookmarkResult(hit, imageLoader, onClick = { onReadPage(hit.issue.issueId, hit.bookmark.pageNumber) })
                        Spacer(Modifier.height(8.dp))
                    }
                }
                if (state.results.issues.isNotEmpty()) {
                    item(key = "issues-title") { ResultTitle("Nummer", state.results.issues.size) }
                    items(state.results.issues, key = { "i-${it.issue.issueId}" }) { item ->
                        IssueResult(item, imageLoader, onClick = { onOpenIssue(item.issue.issueId) })
                        Spacer(Modifier.height(8.dp))
                    }
                    if (state.results.truncated) {
                        item(key = "more") {
                            Text(
                                "Fler nummer matchar. Lägg till ett år eller ett nummer för att begränsa sökningen.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = DmzColors.TextSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickFilter(label: String, selected: Boolean, onToggle: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onToggle,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        modifier = Modifier.heightIn(min = 48.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = DmzColors.Surface,
            labelColor = DmzColors.TextSecondary,
            selectedContainerColor = DmzColors.Accent,
            selectedLabelColor = DmzColors.Background,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = DmzColors.BorderStrong,
            selectedBorderColor = DmzColors.Accent,
        ),
    )
}

@Composable
private fun SearchHint() {
    Panel {
        Text("Så söker du", style = MaterialTheme.typography.titleMedium, color = DmzColors.TextPrimary)
        Spacer(Modifier.height(8.dp))
        for ((example, meaning) in listOf(
            "1989" to "alla nummer från 1989",
            "1989 12 eller 89/12" to "nummer 12 från 1989",
            "nr 12" to "nummer 12 i alla årgångar",
            "highscore" to "en publikation",
            "ett ord" to "dina bokmärkens titlar och anteckningar",
        )) {
            Row(modifier = Modifier.padding(vertical = 3.dp)) {
                Text(example, style = MonoStyles.Data, color = DmzColors.Accent, modifier = Modifier.width(150.dp))
                Text(meaning, style = MaterialTheme.typography.bodyMedium, color = DmzColors.TextSecondary)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Sökning i tidningarnas text kommer i en senare version.",
            style = MaterialTheme.typography.bodyMedium,
            color = DmzColors.TextSecondary,
        )
    }
}

@Composable
private fun ResultTitle(title: String, count: Int) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = DmzColors.TextPrimary, modifier = Modifier.weight(1f))
        Text("$count", style = MonoStyles.DataSmall, color = DmzColors.Label)
    }
}

@Composable
private fun IssueResult(item: IssueWithState, imageLoader: ImageLoader, onClick: () -> Unit) {
    Panel(modifier = Modifier.clickable(role = Role.Button, onClick = onClick).testTag("hit-${item.issue.issueId}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IssueCover(item.issue, imageLoader, modifier = Modifier.width(44.dp), cornerRadius = 4.dp)
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(item.issue.fullTitle(), style = MaterialTheme.typography.titleMedium, color = DmzColors.TextPrimary)
                Text(readStatusText(item.progress), style = MonoStyles.DataSmall, color = DmzColors.Label)
            }
            if (item.isDownloaded) OfflineBadge()
        }
    }
}

@Composable
private fun BookmarkResult(hit: BookmarkHit, imageLoader: ImageLoader, onClick: () -> Unit) {
    Panel(modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Öppna sidan", onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IssueCover(hit.issue, imageLoader, modifier = Modifier.width(44.dp), cornerRadius = 4.dp)
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StarIcon(filled = true, tint = DmzColors.Accent, size = 16.dp)
                    Text(
                        bookmarkLabel(hit.bookmark),
                        style = MaterialTheme.typography.titleMedium,
                        color = DmzColors.TextPrimary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                hit.bookmark.note?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = DmzColors.TextSecondary)
                }
                Text(hit.issue.fullTitle(), style = MonoStyles.DataSmall, color = DmzColors.Label)
            }
        }
    }
}
