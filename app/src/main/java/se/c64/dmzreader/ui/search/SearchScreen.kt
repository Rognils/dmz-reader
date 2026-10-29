package se.c64.dmzreader.ui.search

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import se.c64.dmzreader.data.search.RankedRow
import se.c64.dmzreader.data.search.SearchIndexDownloads
import se.c64.dmzreader.ui.settings.SearchIndexViewModel
import se.c64.dmzreader.ui.settings.SearchIndexMessageDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import se.c64.dmzreader.data.search.copyProgressText
import se.c64.dmzreader.ui.settings.SEARCH_INDEX_MIME_TYPES
import se.c64.dmzreader.data.search.SearchIndexManager
import se.c64.dmzreader.data.settings.ActiveInventoryStore
import se.c64.dmzreader.domain.FullTextQuery
import se.c64.dmzreader.domain.FullTextResults
import se.c64.dmzreader.domain.GROUP_PAGE_SIZE
import se.c64.dmzreader.domain.INDEX_OTHER_VERSION_TEXT
import se.c64.dmzreader.domain.INDEX_OUTDATED_TEXT
import se.c64.dmzreader.domain.IssueHits
import se.c64.dmzreader.domain.SearchSort
import se.c64.dmzreader.domain.TOP_PAGES_PER_ISSUE
import se.c64.dmzreader.domain.groupHits
import se.c64.dmzreader.domain.hitSummaryText
import se.c64.dmzreader.domain.outdatedInIndex
import se.c64.dmzreader.domain.parseFullTextQuery
import se.c64.dmzreader.domain.yearFilterText
import android.util.Log
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
    val sort: SearchSort = SearchSort.RELEVANCE,
    /** Metadata-sökningen: nummer och bokmärken. */
    val results: SearchResults = SearchResults(),
    /** Sant när ett sökindex är importerat. */
    val indexAvailable: Boolean = false,
    /** Sant när sökindexet byggdes av ett annat arkivindex än det aktiva (planens D3). */
    val indexOtherVersion: Boolean = false,
    /** Frågan som fulltextresultatet gäller. null när texten är för kort att söka på. */
    val query: FullTextQuery? = null,
    val searching: Boolean = false,
    /** Fulltextresultatet. null innan någon sökning är gjord. */
    val fullText: FullTextResults? = null,
    /** Så många nummer visas. Fler läggs till när listan rullas mot slutet. */
    val visibleGroups: Int = GROUP_PAGE_SIZE,
    /** Nummer där alla träffar visas, inte bara de tre bästa. */
    val expanded: Set<String> = emptySet(),
    /** Textutdrag som har hämtats, per rad-id. */
    val snippets: Map<Long, String> = emptyMap(),
    /** Publikationerna i arkivet, för filtren. */
    val publications: List<Pair<String, String>> = emptyList(),
    /** Första och sista året i arkivet. */
    val years: IntRange? = null,
) {
    /** Sant när användaren har bett om något: skrivit text eller valt ett filter. */
    val active: Boolean get() = text.isNotBlank() || filters.any
}

private data class FullTextState(
    val query: FullTextQuery? = null,
    val ranked: List<RankedRow> = emptyList(),
    val searching: Boolean = false,
)

private data class ViewState(
    val sort: SearchSort = SearchSort.RELEVANCE,
    val visibleGroups: Int = GROUP_PAGE_SIZE,
    val expanded: Set<String> = emptySet(),
    val snippets: Map<Long, String> = emptyMap(),
)

/**
 * Sökfliken (planens E10). Metadata-sökningen i katalogen och bokmärkena fungerar
 * alltid. När ett sökindex är importerat söks också tidningarnas text: först
 * rangordnas alla träffar, sedan hämtas textutdrag bara för de rader som syns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel(
    libraryRepository: LibraryRepository,
    bookmarkRepository: BookmarkRepository,
    private val searchIndex: SearchIndexManager,
    activeInventory: ActiveInventoryStore,
) : ViewModel() {
    private val text = MutableStateFlow("")
    private val filters = MutableStateFlow(SearchFilters())
    private val view = MutableStateFlow(ViewState())
    private val fullText = MutableStateFlow(FullTextState())

    init {
        // Sökningen körs medan man skriver: efter en paus, och en ny sökning avbryter den pågående.
        viewModelScope.launch {
            combine(text, searchIndex.index) { text, index -> text to (index != null) }.collectLatest { (text, hasIndex) ->
                val query = parseFullTextQuery(text)
                if (query == null || !hasIndex) {
                    fullText.value = FullTextState()
                    return@collectLatest
                }
                if (query == fullText.value.query) return@collectLatest
                delay(SEARCH_DEBOUNCE_MILLIS)
                fullText.update { it.copy(searching = true) }
                val ranked = searchIndex.rank(query.match).orEmpty()
                view.update { it.copy(visibleGroups = GROUP_PAGE_SIZE, expanded = emptySet(), snippets = emptyMap()) }
                fullText.value = FullTextState(query, ranked, searching = false)
            }
        }
    }

    private val issues = libraryRepository.observeIssuesWithState()

    private val metadata = combine(text, filters, issues, bookmarkRepository.observeAll()) { text, filters, issues, bookmarks ->
        searchMetadata(parseMetadataQuery(text), filters, issues, bookmarks)
    }

    // Bara en ny rangordning ger en ny gruppering, inte att "söker" tänds och släcks.
    private val rankedResult = fullText.distinctUntilChanged { old, new -> old.query == new.query && old.ranked === new.ranked }

    private val grouped = combine(rankedResult, searchIndex.index, issues, filters, view.map { it.sort }.distinctUntilChanged()) {
            fullText, index, issues, filters, sort ->
        if (index == null || fullText.query == null) {
            null
        } else {
            val started = System.nanoTime()
            groupHits(fullText.ranked, index.pages, issues, filters, sort, outdatedInIndex(index.issues, issues.map { it.issue }))
                .also { Log.i("DmzSearch", "Gruppering: ${it.totalPages} sidor i ${it.totalIssues} nummer på %.1f ms".format((System.nanoTime() - started) / 1e6)) }
        }
    }.flowOn(Dispatchers.Default)

    val state: StateFlow<SearchUiState> = combine(
        combine(text, filters, view) { text, filters, view -> Triple(text, filters, view) },
        combine(metadata, grouped, fullText) { metadata, grouped, fullText -> Triple(metadata, grouped, fullText) },
        combine(searchIndex.index, activeInventory.observe(), issues) { index, active, issues -> Triple(index, active, issues) },
    ) { (text, filters, view), (metadata, grouped, fullText), (index, active, issues) ->
        SearchUiState(
            text = text,
            filters = filters,
            sort = view.sort,
            results = metadata,
            indexAvailable = index != null,
            indexOtherVersion = index != null && active != null && index.info.inventoryDatasetVersion != active.datasetVersion,
            query = fullText.query,
            searching = fullText.searching,
            fullText = grouped,
            visibleGroups = view.visibleGroups,
            expanded = view.expanded,
            snippets = view.snippets,
            publications = issues.map { it.issue.publicationId to it.issue.publicationTitle }.distinct().sortedBy { it.second },
            years = if (issues.isEmpty()) null else issues.minOf { it.issue.year }..issues.maxOf { it.issue.year },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun setText(value: String) {
        text.value = value
    }

    private fun changeFilters(change: (SearchFilters) -> SearchFilters) {
        filters.update(change)
        view.update { it.copy(visibleGroups = GROUP_PAGE_SIZE) }
    }

    fun toggleFavorites() = changeFilters { it.copy(favoritesOnly = !it.favoritesOnly) }
    fun toggleDownloaded() = changeFilters { it.copy(downloadedOnly = !it.downloadedOnly) }
    fun togglePublication(id: String) = changeFilters { it.copy(publicationId = if (it.publicationId == id) null else id) }
    fun setYears(from: Int?, to: Int?) = changeFilters { it.copy(yearFrom = from, yearTo = to) }

    fun setSort(sort: SearchSort) = view.update { it.copy(sort = sort, visibleGroups = GROUP_PAGE_SIZE) }

    /** Listan har rullats till slutet: visa fler nummer. */
    fun showMoreGroups() = view.update { it.copy(visibleGroups = it.visibleGroups + GROUP_PAGE_SIZE) }

    fun toggleExpanded(issueId: String) = view.update {
        it.copy(expanded = if (issueId in it.expanded) it.expanded - issueId else it.expanded + issueId)
    }

    /** Textutdraget för en synlig rad hämtas först när raden visas, en rad i taget. */
    fun requestSnippet(rowId: Long) {
        val query = fullText.value.query ?: return
        if (rowId in view.value.snippets) return
        viewModelScope.launch {
            val snippet = searchIndex.snippet(rowId, query.match) ?: return@launch
            if (fullText.value.query == query) view.update { it.copy(snippets = it.snippets + (rowId to snippet)) }
        }
    }
}

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    imageLoader: ImageLoader,
    onOpenIssue: (issueId: String) -> Unit,
    onReadPage: (issueId: String, pageNumber: Int) -> Unit,
    onReadHit: (issueId: String, pageNumber: Int, query: String) -> Unit,
    indexDownloads: SearchIndexDownloads,
    importViewModel: SearchIndexViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloadState by indexDownloads.state.collectAsStateWithLifecycle()
    val importState by importViewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importViewModel.import(uri)
    }
    val focus = LocalFocusManager.current
    var choosingYears by rememberSaveable { mutableStateOf(false) }
    val fullText = state.fullText
    val hasMetadata = !state.results.isEmpty

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
                placeholder = {
                    Text(if (state.indexAvailable) "Sök i tidningarnas text" else "År, nummer, publikation eller bokmärke")
                },
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()).testTag("search-filters"),
            ) {
                QuickFilter("Favoriter", state.filters.favoritesOnly, onToggle = viewModel::toggleFavorites)
                QuickFilter("Nedladdade", state.filters.downloadedOnly, onToggle = viewModel::toggleDownloaded)
                for ((id, title) in state.publications) {
                    QuickFilter(title, state.filters.publicationId == id) { viewModel.togglePublication(id) }
                }
                if (state.years != null) {
                    QuickFilter(
                        label = if (state.filters.hasYears) yearFilterText(state.filters) else "År",
                        selected = state.filters.hasYears,
                        tag = "year-filter",
                        onToggle = { choosingYears = true },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (!state.active) {
            item(key = "hint") { SearchHint(state.indexAvailable) }
        } else {
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

        when {
            !state.indexAvailable -> {
                if (state.active && !hasMetadata) {
                    item(key = "none") {
                        Text(
                            text = "Inga träffar. Utan sökindex gäller sökningen år, nummer, publikation och dina bokmärken, " +
                                "inte texten i tidningarna.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = DmzColors.TextSecondary,
                            modifier = Modifier.padding(bottom = 12.dp).testTag("search-empty"),
                        )
                    }
                }
                item(key = "index-card") {
                    // Manifestet hämtas först när kortet visas, aldrig när appen startar.
                    LaunchedEffect(Unit) { indexDownloads.checkManifest() }
                    SearchIndexCard(
                        // Ett tidigare importerat index kan ha tagits bort. Då gäller kortet igen.
                        state = downloadState.takeIf { it !is se.c64.dmzreader.data.search.IndexDownloadState.Done }
                            ?: se.c64.dmzreader.data.search.IndexDownloadState.Idle,
                        manualImport = importState.progress?.let { if (importState.checking) "Kontrollerar filen…" else "Kopierar ${copyProgressText(it)}" },
                        onDownload = indexDownloads::request,
                        onRetryManifest = { indexDownloads.checkManifest(force = true) },
                        onCancel = indexDownloads::cancel,
                        onImportManually = { picker.launch(SEARCH_INDEX_MIME_TYPES) },
                    )
                }
            }

            state.query == null -> Unit

            fullText == null || (state.searching && fullText.isEmpty) -> item(key = "searching") {
                Text("Söker…", style = MonoStyles.DataSmall, color = DmzColors.Label, modifier = Modifier.padding(top = 8.dp))
            }

            else -> {
                item(key = "text-title") {
                    Column(modifier = Modifier.padding(top = 4.dp)) {
                        Text("I tidningarnas text", style = MaterialTheme.typography.titleLarge, color = DmzColors.TextPrimary)
                        Text(
                            hitSummaryText(fullText.totalPages, fullText.totalIssues),
                            style = MonoStyles.DataSmall,
                            color = DmzColors.Label,
                            modifier = Modifier.testTag("hit-summary"),
                        )
                        if (state.indexOtherVersion) {
                            Text(INDEX_OTHER_VERSION_TEXT, style = MaterialTheme.typography.bodyMedium, color = DmzColors.TextSecondary)
                        }
                        if (!fullText.isEmpty) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                                for (sort in SearchSort.entries) {
                                    QuickFilter(sort.label, state.sort == sort) { viewModel.setSort(sort) }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
                if (fullText.isEmpty) {
                    item(key = "no-text-hits") {
                        Text(
                            if (state.filters.any) "Inga träffar i texten med de valda filtren." else "Inga träffar i texten.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = DmzColors.TextSecondary,
                            modifier = Modifier.testTag("search-empty"),
                        )
                    }
                }
                val visible = fullText.groups.take(state.visibleGroups)
                items(visible, key = { "g-${it.item.issue.issueId}" }) { group ->
                    HitGroup(
                        group = group,
                        expanded = group.item.issue.issueId in state.expanded,
                        snippets = state.snippets,
                        imageLoader = imageLoader,
                        onOpenIssue = { onOpenIssue(group.item.issue.issueId) },
                        onOpenPage = { page -> onReadHit(group.item.issue.issueId, page, state.query?.display ?: state.text) },
                        onToggleExpanded = { viewModel.toggleExpanded(group.item.issue.issueId) },
                        onSnippetVisible = viewModel::requestSnippet,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (fullText.groups.size > visible.size) {
                    item(key = "more-groups-${visible.size}") {
                        // När raden blir synlig läggs nästa omgång nummer till.
                        LaunchedEffect(visible.size) { viewModel.showMoreGroups() }
                        Text(
                            "Visar ${visible.size} av ${fullText.groups.size} nummer…",
                            style = MonoStyles.DataSmall,
                            color = DmzColors.Label,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }

    importState.message?.let { message -> SearchIndexMessageDialog(message, importViewModel::dismissMessage) }

    val years = state.years
    if (choosingYears && years != null) {
        YearRangeDialog(
            range = years,
            from = state.filters.yearFrom,
            to = state.filters.yearTo,
            onChoose = { from, to ->
                viewModel.setYears(from, to)
                choosingYears = false
            },
            onDismiss = { choosingYears = false },
        )
    }
}

/** Alla träffar i ett nummer: omslag, titel, antal och de bästa sidorna med textutdrag. */
@Composable
private fun HitGroup(
    group: IssueHits,
    expanded: Boolean,
    snippets: Map<Long, String>,
    imageLoader: ImageLoader,
    onOpenIssue: () -> Unit,
    onOpenPage: (Int) -> Unit,
    onToggleExpanded: () -> Unit,
    onSnippetVisible: (Long) -> Unit,
) {
    val issue = group.item.issue
    Panel(modifier = Modifier.testTag("group-${issue.issueId}")) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Öppna numret", onClick = onOpenIssue),
        ) {
            IssueCover(issue, imageLoader, modifier = Modifier.width(44.dp), cornerRadius = 4.dp)
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(issue.fullTitle(), style = MaterialTheme.typography.titleMedium, color = DmzColors.TextPrimary)
                Text(
                    if (group.pages.size == 1) "1 träff" else "${group.pages.size} träffar",
                    style = MonoStyles.DataSmall,
                    color = DmzColors.Label,
                )
            }
            if (group.item.isDownloaded) OfflineBadge()
        }
        if (group.indexOutdated) {
            Text(INDEX_OUTDATED_TEXT, style = MaterialTheme.typography.bodyMedium, color = DmzColors.Accent, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(4.dp))
        for (hit in if (expanded) group.pages else group.top) {
            HitRow(hit, snippets[hit.rowId], onVisible = onSnippetVisible, onClick = { onOpenPage(hit.pageNumber) })
        }
        if (group.pages.size > TOP_PAGES_PER_ISSUE) {
            TextButton(onClick = onToggleExpanded, modifier = Modifier.heightIn(min = 48.dp).testTag("show-all")) {
                Text(
                    if (expanded) "Visa bara de bästa" else "Visa alla ${group.pages.size} träffar",
                    color = DmzColors.Accent,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/**
 * Nästa urval när ett år trycks i årsväljaren: första trycket väljer ett år, det andra
 * utökar till ett intervall, och ett tredje börjar om.
 */
fun nextYearSelection(current: IntRange?, year: Int): IntRange = when {
    current == null || current.first != current.last -> year..year
    else -> minOf(current.first, year)..maxOf(current.first, year)
}

/** Val av årsintervall. Båda ändarna tas med. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun YearRangeDialog(range: IntRange, from: Int?, to: Int?, onChoose: (Int?, Int?) -> Unit, onDismiss: () -> Unit) {
    var chosen by remember {
        mutableStateOf(if (from == null && to == null) null else (from ?: range.first)..(to ?: range.last))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("År") },
        text = {
            Column(modifier = Modifier.testTag("year-range")) {
                val selection = chosen
                Text(
                    when {
                        selection == null -> "Tryck på ett år, och på ett till för ett intervall."
                        selection.first == selection.last -> "${selection.first}"
                        else -> "${selection.first}–${selection.last}"
                    },
                    style = MonoStyles.Data,
                    color = DmzColors.Accent,
                    modifier = Modifier.testTag("year-range-text"),
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (year in range) {
                        QuickFilter("$year", selection != null && year in selection, tag = "year-$year") {
                            chosen = nextYearSelection(chosen, year)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { chosen.let { if (it == null) onChoose(null, null) else onChoose(it.first, it.last) } }) {
                Text("Använd")
            }
        },
        dismissButton = { TextButton(onClick = { onChoose(null, null) }) { Text("Alla år") } },
    )
}

@Composable
private fun QuickFilter(label: String, selected: Boolean, tag: String? = null, onToggle: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onToggle,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        modifier = Modifier.heightIn(min = 48.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier),
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
private fun SearchHint(indexAvailable: Boolean) {
    Panel {
        Text("Så söker du", style = MaterialTheme.typography.titleMedium, color = DmzColors.TextPrimary)
        Spacer(Modifier.height(8.dp))
        val textExamples = listOf(
            "högtalare" to "ordet i tidningarnas text",
            "\"Commodore 128\"" to "en fras, ord för ord",
            "amiga joystick" to "sidor med båda orden",
            "amig*" to "ord som börjar så (minst tre tecken)",
        )
        for ((example, meaning) in (if (indexAvailable) textExamples else emptyList()) + listOf(
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
