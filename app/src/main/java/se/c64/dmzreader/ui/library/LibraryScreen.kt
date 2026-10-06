package se.c64.dmzreader.ui.library

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import se.c64.dmzreader.InventoryStatus
import se.c64.dmzreader.data.db.IssueEntity
import se.c64.dmzreader.data.db.YearCount
import se.c64.dmzreader.data.inventory.InventoryLoadResult
import se.c64.dmzreader.data.repository.LibraryRepository
import se.c64.dmzreader.data.settings.LibraryPreferences
import se.c64.dmzreader.domain.PublicationYears
import se.c64.dmzreader.domain.groupByPublication
import se.c64.dmzreader.domain.selectPublication
import se.c64.dmzreader.ui.components.IssueCover
import se.c64.dmzreader.ui.components.MonoLabel
import se.c64.dmzreader.ui.components.Panel
import se.c64.dmzreader.ui.theme.DmzColors
import se.c64.dmzreader.ui.theme.MonoStyles

/** Nummer att fortsätta läsa, med läspositionen. Fylls i från FAS 4. */
data class ContinueReading(val issue: IssueEntity, val page: Int, val pageCount: Int?)

data class LibraryUiState(
    val loading: Boolean = true,
    val errorMessage: String? = null,
    val publications: List<PublicationYears> = emptyList(),
    val selected: PublicationYears? = null,
    // Sektionerna nedan har ingen data förrän FAS 4–6 och är dolda så länge de är tomma.
    val continueReading: ContinueReading? = null,
    val recentlyRead: List<IssueEntity> = emptyList(),
    val favorites: List<IssueEntity> = emptyList(),
    val downloaded: List<IssueEntity> = emptyList(),
)

class LibraryViewModel(
    repository: LibraryRepository,
    private val preferences: LibraryPreferences,
    inventoryStatus: StateFlow<InventoryStatus>,
) : ViewModel() {
    val state: StateFlow<LibraryUiState> = combine(
        repository.observeYearCounts(),
        preferences.observeSelectedPublication(),
        inventoryStatus,
    ) { years, savedPublicationId, status ->
        val publications = groupByPublication(years)
        val result = (status as? InventoryStatus.Done)?.result
        LibraryUiState(
            loading = status is InventoryStatus.Loading,
            errorMessage = (result as? InventoryLoadResult.Rejected)?.message,
            publications = publications,
            selected = selectPublication(publications, savedPublicationId),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun selectPublication(publicationId: String) {
        viewModelScope.launch { preferences.setSelectedPublication(publicationId) }
    }
}

private const val COLUMNS = 3

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    imageLoader: ImageLoader,
    onOpenYear: (publicationId: String, year: Int) -> Unit,
    onOpenIssue: (issueId: String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selected = state.selected

    LazyVerticalGrid(
        columns = GridCells.Fixed(COLUMNS),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        fullWidth("header") { LibraryHeader(onOpenSettings) }

        when {
            state.errorMessage != null && state.publications.isEmpty() -> fullWidth("error") {
                Panel {
                    Text("Arkivindexet kunde inte läsas in", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        state.errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = DmzColors.TextSecondary,
                    )
                }
            }

            selected == null -> fullWidth("loading") {
                Text(
                    text = if (state.loading) "Läser in arkivet …" else "Arkivet är tomt.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = DmzColors.TextSecondary,
                )
            }

            else -> {
                if (state.publications.size > 1) {
                    fullWidth("publications") {
                        PublicationSelector(
                            publications = state.publications,
                            selectedId = selected.publicationId,
                            onSelect = viewModel::selectPublication,
                        )
                    }
                }

                state.continueReading?.let { item ->
                    fullWidth("continue") { ContinueReadingCard(item, imageLoader, onOpenIssue) }
                }

                fullWidth("years-title") {
                    SectionTitle(title = "Årgångar", detail = "${selected.issueCount} nummer")
                }
                items(selected.years, key = { "${it.publicationId}-${it.year}" }) { year ->
                    YearCard(year, onClick = { onOpenYear(year.publicationId, year.year) })
                }

                issueRowSection("Senast lästa", state.recentlyRead, imageLoader, onOpenIssue)
                issueRowSection("Favoriter", state.favorites, imageLoader, onOpenIssue)
                issueRowSection("Nedladdat", state.downloaded, imageLoader, onOpenIssue)
            }
        }
    }
}

private fun LazyGridScope.fullWidth(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun LibraryHeader(onOpenSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            MonoLabel("READY.", color = DmzColors.Accent)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "DMZ Reader",
                style = MaterialTheme.typography.displaySmall,
                color = DmzColors.TextPrimary,
            )
        }
        IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Inställningar",
                tint = DmzColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun PublicationSelector(
    publications: List<PublicationYears>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        publications.forEachIndexed { index, publication ->
            SegmentedButton(
                selected = publication.publicationId == selectedId,
                onClick = { onSelect(publication.publicationId) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = publications.size),
                modifier = Modifier.heightIn(min = 48.dp),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = DmzColors.Accent,
                    activeContentColor = DmzColors.Background,
                    activeBorderColor = DmzColors.Accent,
                    inactiveContainerColor = DmzColors.Surface,
                    inactiveContentColor = DmzColors.TextSecondary,
                    inactiveBorderColor = DmzColors.BorderStrong,
                ),
                icon = {},
            ) {
                Text(publication.publicationTitle, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, detail: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = DmzColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        if (detail != null) {
            Text(detail, style = MonoStyles.DataSmall, color = DmzColors.Label)
        }
    }
}

@Composable
private fun YearCard(year: YearCount, onClick: () -> Unit) {
    val description = "${year.publicationTitle} ${year.year}, ${year.issueCount} nummer"
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick),
        color = DmzColors.Surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, DmzColors.Border),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Text(
                text = year.year.toString(),
                style = MaterialTheme.typography.titleLarge,
                color = DmzColors.TextPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text("${year.issueCount} nummer", style = MonoStyles.DataSmall, color = DmzColors.Label)
        }
    }
}

/** Stort kort överst i biblioteket. Visas först när det finns en läsposition (FAS 4). */
@Composable
private fun ContinueReadingCard(
    item: ContinueReading,
    imageLoader: ImageLoader,
    onOpenIssue: (String) -> Unit,
) {
    Panel(modifier = Modifier.clickable(role = Role.Button) { onOpenIssue(item.issue.issueId) }) {
        MonoLabel("Fortsätt läsa")
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IssueCover(item.issue, imageLoader, modifier = Modifier.width(88.dp))
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(
                    "${item.issue.publicationTitle} ${item.issue.year}",
                    style = MaterialTheme.typography.titleMedium,
                    color = DmzColors.TextPrimary,
                )
                Text(item.issue.issueLabel, style = MaterialTheme.typography.bodyLarge, color = DmzColors.TextSecondary)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = item.pageCount?.let { "Sida ${item.page} av $it" } ?: "Sida ${item.page}",
                    style = MonoStyles.Data,
                    color = DmzColors.Accent,
                )
            }
        }
    }
}

/** Horisontell rad med omslag. Läggs inte till alls när listan är tom. */
private fun LazyGridScope.issueRowSection(
    title: String,
    issues: List<IssueEntity>,
    imageLoader: ImageLoader,
    onOpenIssue: (String) -> Unit,
) {
    if (issues.isEmpty()) return
    fullWidth("section-$title") {
        Column {
            SectionTitle(title)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(issues, key = { it.issueId }) { issue ->
                    IssueCover(
                        issue = issue,
                        imageLoader = imageLoader,
                        modifier = Modifier
                            .width(104.dp)
                            .clickable(role = Role.Button) { onOpenIssue(issue.issueId) },
                    )
                }
            }
        }
    }
}
