package se.c64.dmzreader.ui.favorites

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.c64.dmzreader.data.db.Bookmark
import se.c64.dmzreader.data.repository.BookmarkRepository
import se.c64.dmzreader.data.repository.LibraryRepository
import se.c64.dmzreader.data.repository.ReadingRepository
import se.c64.dmzreader.domain.ARCHIVE_FILE_CHANGED_TEXT
import se.c64.dmzreader.domain.BookmarkGroup
import se.c64.dmzreader.domain.IssueWithState
import se.c64.dmzreader.domain.MISSING_FROM_INVENTORY_TEXT
import se.c64.dmzreader.domain.PersonalLibrary
import se.c64.dmzreader.domain.fullTitle
import se.c64.dmzreader.domain.personalLibrary
import se.c64.dmzreader.ui.components.BookmarkEditDialog
import se.c64.dmzreader.ui.components.BookmarkRow
import se.c64.dmzreader.ui.components.IssueCover
import se.c64.dmzreader.ui.components.MonoLabel
import se.c64.dmzreader.ui.components.Notice
import se.c64.dmzreader.ui.components.Panel
import se.c64.dmzreader.ui.theme.DmzColors
import se.c64.dmzreader.ui.theme.MonoStyles

data class FavoritesUiState(val loading: Boolean = true, val library: PersonalLibrary = PersonalLibrary())

class FavoritesViewModel(
    libraryRepository: LibraryRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val readingRepository: ReadingRepository,
) : ViewModel() {
    val state: StateFlow<FavoritesUiState> = combine(
        libraryRepository.observeIssuesWithState(),
        libraryRepository.observeUserStates(),
        bookmarkRepository.observeAll(),
    ) { issues, userStates, bookmarks ->
        FavoritesUiState(loading = false, library = personalLibrary(issues, userStates, bookmarks))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FavoritesUiState())

    fun editBookmark(id: Long, title: String, note: String) {
        viewModelScope.launch { bookmarkRepository.edit(id, title, note) }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { bookmarkRepository.delete(id) }
    }

    /** Tar bort favoritmarkeringen, också för ett nummer som saknas i arkivindexet. */
    fun removeFavorite(issueId: String) {
        viewModelScope.launch { readingRepository.setFavorite(issueId, false) }
    }
}

private const val COLUMNS = 3

/** Fliken Favoriter: favoritnummer och bokmärken grupperade per nummer (planens FAS 6). */
@Composable
fun FavoritesScreen(
    viewModel: FavoritesViewModel,
    imageLoader: ImageLoader,
    onOpenIssue: (issueId: String) -> Unit,
    onReadPage: (issueId: String, pageNumber: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val library = state.library
    var editing by remember { mutableStateOf<Bookmark?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("favorites"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 24.dp),
    ) {
        item(key = "title") {
            Text("Favoriter", style = MaterialTheme.typography.headlineMedium, color = DmzColors.TextPrimary)
            Spacer(Modifier.height(12.dp))
        }

        if (!state.loading && library.isEmpty) {
            item(key = "empty") { EmptyState() }
        }

        if (library.favorites.isNotEmpty() || library.missingFavorites.isNotEmpty()) {
            item(key = "favorites-title") {
                SectionTitle("Favoritnummer", "${library.favorites.size + library.missingFavorites.size}")
            }
            // Omslagsrutnät med tre kolumner.
            items(library.favorites.chunked(COLUMNS), key = { row -> "fav-" + row.first().issue.issueId }) { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { item -> FavoriteCell(item, imageLoader, onOpenIssue, Modifier.weight(1f)) }
                    repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            items(library.missingFavorites, key = { "missing-fav-$it" }) { issueId ->
                MissingFavorite(issueId, onRemove = { viewModel.removeFavorite(issueId) })
                Spacer(Modifier.height(8.dp))
            }
        }

        if (library.bookmarkGroups.isNotEmpty()) {
            item(key = "bookmarks-title") {
                SectionTitle("Bokmärken", "${library.bookmarkGroups.sumOf { it.bookmarks.size }}")
            }
            items(library.bookmarkGroups, key = { "group-" + it.issueId }) { group ->
                BookmarkGroupPanel(
                    group = group,
                    imageLoader = imageLoader,
                    onOpenIssue = onOpenIssue,
                    onReadPage = onReadPage,
                    onEdit = { editing = it },
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    editing?.let { bookmark ->
        BookmarkEditDialog(
            bookmark = bookmark,
            onSave = { title, note ->
                viewModel.editBookmark(bookmark.id, title, note)
                editing = null
            },
            onDelete = {
                viewModel.deleteBookmark(bookmark.id)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun EmptyState() {
    Panel {
        Text("Här samlas dina favoriter och bokmärken", style = MaterialTheme.typography.titleMedium, color = DmzColors.TextPrimary)
        Spacer(Modifier.height(8.dp))
        Text(
            "Favoritnummer: tryck på hjärtat i ett nummers vy.",
            style = MaterialTheme.typography.bodyLarge,
            color = DmzColors.TextSecondary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Bokmärken: tryck mitt på sidan i läsaren så att kontrollerna visas, och sedan på stjärnan.",
            style = MaterialTheme.typography.bodyLarge,
            color = DmzColors.TextSecondary,
        )
    }
}

@Composable
private fun SectionTitle(title: String, count: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = DmzColors.TextPrimary, modifier = Modifier.weight(1f))
        Text(count, style = MonoStyles.DataSmall, color = DmzColors.Label)
    }
}

@Composable
private fun FavoriteCell(
    item: IssueWithState,
    imageLoader: ImageLoader,
    onOpenIssue: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.clickable(role = Role.Button) { onOpenIssue(item.issue.issueId) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IssueCover(item.issue, imageLoader, modifier = Modifier.fillMaxWidth())
        Text(
            text = "${item.issue.year} ${item.issue.issueLabel}",
            style = MaterialTheme.typography.labelLarge,
            color = DmzColors.TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** En favorit vars nummer inte finns i det aktiva arkivindexet (planens D6). */
@Composable
private fun MissingFavorite(issueId: String, onRemove: () -> Unit) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(issueId, style = MonoStyles.Data, color = DmzColors.TextPrimary)
                Spacer(Modifier.height(2.dp))
                Notice(MISSING_FROM_INVENTORY_TEXT)
            }
            TextButton(onClick = onRemove, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Ta bort", color = DmzColors.TextPrimary)
            }
        }
    }
}

@Composable
private fun BookmarkGroupPanel(
    group: BookmarkGroup,
    imageLoader: ImageLoader,
    onOpenIssue: (String) -> Unit,
    onReadPage: (String, Int) -> Unit,
    onEdit: (Bookmark) -> Unit,
) {
    val issue = group.issue
    Panel(modifier = Modifier.testTag("bookmark-group-${group.issueId}")) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (issue != null) Modifier.clickable(role = Role.Button) { onOpenIssue(group.issueId) } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (issue != null) {
                IssueCover(issue, imageLoader, modifier = Modifier.width(44.dp), cornerRadius = 4.dp)
            } else {
                // Inget omslag att visa: numret finns inte i arkivindexet.
                Box(modifier = Modifier.width(44.dp).height(60.dp), contentAlignment = Alignment.Center) {
                    MonoLabel("?")
                }
            }
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    text = issue?.fullTitle() ?: group.issueId,
                    style = if (issue != null) MaterialTheme.typography.titleMedium else MonoStyles.Data,
                    color = DmzColors.TextPrimary,
                )
                when {
                    group.missingFromInventory -> Notice(MISSING_FROM_INVENTORY_TEXT)
                    group.fileChanged -> Notice(ARCHIVE_FILE_CHANGED_TEXT)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        group.bookmarks.forEach { bookmark ->
            BookmarkRow(
                bookmark = bookmark,
                // Ett nummer som saknas i arkivindexet går inte att öppna, men bokmärket går att ta bort.
                onOpen = if (issue != null) ({ onReadPage(group.issueId, bookmark.pageNumber) }) else null,
                onEdit = { onEdit(bookmark) },
            )
        }
    }
}
