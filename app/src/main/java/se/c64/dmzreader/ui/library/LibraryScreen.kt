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
import androidx.compose.material.icons.outlined.Info
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
import androidx.compose.ui.platform.testTag
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
import se.c64.dmzreader.domain.continueReadingIssue
import se.c64.dmzreader.domain.downloadedIssues
import se.c64.dmzreader.domain.readPagesText
import se.c64.dmzreader.domain.groupByPublication
import se.c64.dmzreader.domain.readCountsByYear
import se.c64.dmzreader.domain.recentlyReadIssues
import se.c64.dmzreader.domain.selectPublication
import se.c64.dmzreader.ui.components.IssueCover
import se.c64.dmzreader.ui.components.MonoLabel
import se.c64.dmzreader.ui.components.Panel
import se.c64.dmzreader.ui.components.ProgressLine
import se.c64.dmzreader.ui.theme.DmzColors
import se.c64.dmzreader.ui.theme.MonoStyles

/** Nummer att fortsätta läsa, med läspositionen och hur stor del som är läst. */
data class ContinueReading(
    val issue: IssueEntity,
    val page: Int,
    val pageCount: Int?,
    val fraction: Float?,
    /** "7 av 28 lästa · 25 %", eller null när sidantalet är okänt. */
    val readText: String?,
)

data class LibraryUiState(
    val loading: Boolean = true,
    val errorMessage: String? = null,
    val publications: List<PublicationYears> = emptyList(),
    val selected: PublicationYears? = null,
    val continueReading: ContinueReading? = null,
    val recentlyRead: List<IssueEntity> = emptyList(),
    /** Antal lästa nummer per årgång, med nyckeln (publicationId, year). */
    val readCounts: Map<Pair<String, Int>, Int> = emptyMap(),
    // Sektionerna nedan är dolda så länge de är tomma.
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
        repository.observeIssuesWithState(),
    ) { years, savedPublicationId, status, issues ->
        val publications = groupByPublication(years)
        val result = (status as? InventoryStatus.Done)?.result
        val continued = continueReadingIssue(issues)
        LibraryUiState(
            loading = status is InventoryStatus.Loading,
            errorMessage = (result as? InventoryLoadResult.Rejected)?.message,
            publications = publications,
            selected = selectPublication(publications, savedPublicationId),
            continueReading = continued?.let {
                ContinueReading(it.issue, it.state?.lastPage ?: 1, it.pageCount, it.progress.fraction, readPagesText(it.progress))
            },
            recentlyRead = recentlyReadIssues(issues, continued?.issue?.issueId).map { it.issue },
            readCounts = readCountsByYear(issues),
            downloaded = downloadedIssues(issues).map { it.issue },
            favorites = issues.filter { it.state?.isFavorite == true }.map { it.issue },
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
    onContinueReading: (issueId: String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
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
        fullWidth("header") { LibraryHeader(onOpenSettings, onOpenAbout) }

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
                    fullWidth("continue") { ContinueReadingCard(item, imageLoader, onContinueReading) }
                }

                fullWidth("years-title") {
                    SectionTitle(title = "Årgångar", detail = "${selected.issueCount} nummer")
                }
                items(selected.years, key = { "${it.publicationId}-${it.year}" }) { year ->
                    YearCard(
                        year = year,
                        readCount = state.readCounts[year.publicationId to year.year] ?: 0,
                        onClick = { onOpenYear(year.publicationId, year.year) },
                    )
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
private fun LibraryHeader(onOpenSettings: () -> Unit, onOpenAbout: () -> Unit) {
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
        IconButton(onClick = onOpenAbout, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = "Om DMZ Reader",
                tint = DmzColors.TextSecondary,
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
private fun YearCard(year: YearCount, readCount: Int, onClick: () -> Unit) {
    val description = "${year.publicationTitle} ${year.year}, ${year.issueCount} nummer" +
        if (readCount > 0) ", $readCount lästa" else ""
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
            Spacer(Modifier.height(8.dp))
            // Andelen lästa nummer i årgången.
            ProgressLine(if (year.issueCount > 0) readCount.toFloat() / year.issueCount else 0f)
        }
    }
}

/** Stort kort överst i biblioteket. Öppnar läsaren direkt på läspositionen. */
@Composable
private fun ContinueReadingCard(
    item: ContinueReading,
    imageLoader: ImageLoader,
    onContinue: (String) -> Unit,
) {
    Panel(
        modifier = Modifier
            .testTag("continue-reading")
            .clickable(role = Role.Button, onClickLabel = "Fortsätt läsa") { onContinue(item.issue.issueId) },
    ) {
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
                    color = DmzColors.TextPrimary,
                )
                // Texten säger vad strecket visar: andelen lästa sidor, inte läget i numret.
                item.readText?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it, style = MonoStyles.DataSmall, color = DmzColors.TextSecondary)
                }
                item.fraction?.let {
                    Spacer(Modifier.height(8.dp))
                    ProgressLine(it)
                }
                Spacer(Modifier.height(10.dp))
                Text("Fortsätt →", style = MaterialTheme.typography.titleMedium, color = DmzColors.Accent)
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
