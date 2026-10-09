package se.c64.dmzreader.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import se.c64.dmzreader.data.repository.LibraryRepository
import se.c64.dmzreader.data.search.SNIPPET_CLOSE
import se.c64.dmzreader.data.search.SNIPPET_OPEN
import se.c64.dmzreader.data.search.SearchIndexManager
import se.c64.dmzreader.domain.FullTextQuery
import se.c64.dmzreader.domain.INDEX_OUTDATED_TEXT
import se.c64.dmzreader.domain.NOT_IN_INDEX_TEXT
import se.c64.dmzreader.domain.PageHit
import se.c64.dmzreader.domain.hitNavigationText
import se.c64.dmzreader.domain.hitsInIssue
import se.c64.dmzreader.domain.outdatedInIndex
import se.c64.dmzreader.domain.parseFullTextQuery
import se.c64.dmzreader.domain.splitSnippet
import se.c64.dmzreader.ui.components.Notice
import se.c64.dmzreader.ui.theme.DmzColors
import se.c64.dmzreader.ui.theme.MonoStyles

/** Paus efter senaste tangenttryckningen innan sökningen körs. */
const val SEARCH_DEBOUNCE_MILLIS = 300L

data class IssueSearchUiState(
    /** Sant när ett sökindex är importerat. */
    val indexAvailable: Boolean = false,
    /** Falskt när numret saknas i sökindexet (planens D3). */
    val inIndex: Boolean = true,
    /** Sant när sökindexet byggdes av en annan version av numrets PDF. */
    val outdated: Boolean = false,
    val text: String = "",
    val query: FullTextQuery? = null,
    val searching: Boolean = false,
    /** Träffsidorna i numret, i sidordning. */
    val hits: List<PageHit> = emptyList(),
    /** Vilken träff läsaren står på, räknat från 0. */
    val current: Int = 0,
    /** Sant när träffraden visas i läsaren. */
    val barVisible: Boolean = false,
    val snippets: Map<Long, String> = emptyMap(),
) {
    val barText: String get() = hitNavigationText(query?.display ?: text, current, hits.size)
}

/**
 * "Sök i numret" och läsarens träffrad (planens E10): samma frågelager som den
 * stora sökningen, begränsat till ett nummer.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class IssueSearchViewModel(
    private val issueId: String,
    private val searchIndex: SearchIndexManager,
    libraryRepository: LibraryRepository,
    /** Sökningen som läsaren öppnades med, om den öppnades från en träff. */
    initialQuery: String? = null,
    /** Sidan läsaren öppnades på. Träffraden börjar på den träffen. */
    private val initialPage: Int? = null,
) : ViewModel() {
    private val text = MutableStateFlow(initialQuery.orEmpty())
    private val local = MutableStateFlow(
        IssueSearchUiState(text = initialQuery.orEmpty(), barVisible = !initialQuery.isNullOrBlank()),
    )
    private var firstSearch = true

    val state: StateFlow<IssueSearchUiState> = combine(
        local,
        searchIndex.index,
        libraryRepository.observeIssueWithState(issueId),
    ) { local, index, item ->
        local.copy(
            indexAvailable = index != null,
            inIndex = index == null || issueId in index.issueIds,
            outdated = index != null && item != null && issueId in outdatedInIndex(index.issues, listOf(item.issue)),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        viewModelScope.launch {
            combine(text, searchIndex.index) { text, index -> text to index }.collectLatest { (text, index) ->
                val query = parseFullTextQuery(text)
                if (query == null || index == null) {
                    local.update { it.copy(text = text, query = query, hits = emptyList(), searching = false, snippets = emptyMap()) }
                    return@collectLatest
                }
                // Sökningen som läsaren öppnades med körs direkt. Det som skrivs väntar på en paus.
                if (!firstSearch) delay(SEARCH_DEBOUNCE_MILLIS)
                firstSearch = false
                local.update { it.copy(text = text, searching = true) }
                val ranked = searchIndex.rank(query.match).orEmpty()
                val hits = hitsInIssue(ranked, index.pages, issueId)
                local.update { state ->
                    val anchor = initialPage?.takeIf { state.query == null }
                    state.copy(
                        query = query,
                        hits = hits,
                        searching = false,
                        snippets = emptyMap(),
                        current = anchor?.let { page -> hits.indexOfFirst { it.pageNumber == page } }?.coerceAtLeast(0) ?: 0,
                    )
                }
            }
        }
    }

    fun setText(value: String) {
        firstSearch = false
        text.value = value
        local.update { it.copy(text = value) }
    }

    /** Textutdraget för en synlig rad hämtas först när raden visas. */
    fun requestSnippet(rowId: Long) {
        val query = local.value.query ?: return
        if (rowId in local.value.snippets) return
        viewModelScope.launch {
            val snippet = searchIndex.snippet(rowId, query.match) ?: return@launch
            local.update { if (it.query == query) it.copy(snippets = it.snippets + (rowId to snippet)) else it }
        }
    }

    /** Användaren valde en träff i listan. Returnerar sidan som ska öppnas. */
    fun select(hit: PageHit): Int {
        local.update { state -> state.copy(current = state.hits.indexOf(hit).coerceAtLeast(0), barVisible = true) }
        return hit.pageNumber
    }

    /** Nästa träff i numret, runt till den första efter den sista. Returnerar sidan, eller null utan träffar. */
    fun next(): Int? = step(+1)
    fun previous(): Int? = step(-1)

    private fun step(delta: Int): Int? {
        val hits = local.value.hits
        if (hits.isEmpty()) return null
        val index = (local.value.current + delta + hits.size) % hits.size
        local.update { it.copy(current = index) }
        return hits[index].pageNumber
    }

    fun closeBar() = local.update { it.copy(barVisible = false) }
}

/** "Sida 26 · …Nya högtalare är ett bra sätt…", med sökordet i accentfärg. */
fun snippetText(pageNumber: Int, snippet: String?, accent: Color, secondary: Color): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = secondary)) { append("Sida $pageNumber") }
    if (snippet != null) {
        withStyle(SpanStyle(color = secondary)) { append(" · ") }
        for (part in splitSnippet(snippet, SNIPPET_OPEN, SNIPPET_CLOSE)) {
            if (part.hit) {
                withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) { append(part.text) }
            } else {
                append(part.text)
            }
        }
    }
}

/** En träffsida med textutdrag. Utdraget hämtas när raden blir synlig. */
@Composable
fun HitRow(
    hit: PageHit,
    snippet: String?,
    onVisible: (rowId: Long) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(hit.rowId, snippet == null) { if (snippet == null) onVisible(hit.rowId) }
    Text(
        text = snippetText(hit.pageNumber, snippet, DmzColors.Accent, DmzColors.Label),
        style = MaterialTheme.typography.bodyMedium,
        color = DmzColors.TextPrimary,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Öppna sidan", onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp)
            .testTag("hit-row"),
    )
}

/** Knappen "Sök i numret" i nummervyn, med markeringarna om sökindexet (planens D3 och C7). */
@Composable
fun IssueSearchSection(viewModel: IssueSearchViewModel, onOpenPage: (pageNumber: Int, query: String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    if (!state.indexAvailable) return

    if (state.inIndex) {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("search-in-issue"),
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = DmzColors.TextPrimary, modifier = Modifier.size(18.dp))
            Text("Sök i numret", color = DmzColors.TextPrimary, modifier = Modifier.padding(start = 8.dp))
        }
        if (state.outdated) {
            Spacer(Modifier.height(6.dp))
            Notice("$INDEX_OUTDATED_TEXT. Träffarna kan peka på fel sida.", modifier = Modifier.testTag("index-outdated"))
        }
    } else {
        Notice(NOT_IN_INDEX_TEXT, modifier = Modifier.testTag("not-in-index"))
    }
    Spacer(Modifier.height(12.dp))

    if (open) {
        IssueSearchDialog(
            viewModel = viewModel,
            onOpenPage = { page ->
                open = false
                onOpenPage(page, state.query?.display ?: state.text)
            },
            onDismiss = { open = false },
        )
    }
}

/** Sökfält och träfflista för ett nummer. Används i nummervyn och i läsaren. */
@Composable
fun IssueSearchDialog(viewModel: IssueSearchViewModel, onOpenPage: (pageNumber: Int) -> Unit, onDismiss: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sök i numret") },
        text = {
            Column(modifier = Modifier.testTag("issue-search")) {
                OutlinedTextField(
                    value = state.text,
                    onValueChange = viewModel::setText,
                    singleLine = true,
                    placeholder = { Text("Sökord") },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("issue-search-field"),
                )
                Spacer(Modifier.height(8.dp))
                val status = when {
                    state.query == null -> "Skriv minst två tecken."
                    state.searching -> "Söker…"
                    state.hits.isEmpty() -> "Inga träffar i numret."
                    state.hits.size == 1 -> "1 sida"
                    else -> "${state.hits.size} sidor"
                }
                Text(status, style = MonoStyles.DataSmall, color = DmzColors.Label, modifier = Modifier.testTag("issue-search-status"))
                if (state.outdated) Notice(INDEX_OUTDATED_TEXT)
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(state.hits, key = { it.rowId }) { hit ->
                        HitRow(
                            hit = hit,
                            snippet = state.snippets[hit.rowId],
                            onVisible = viewModel::requestSnippet,
                            onClick = { onOpenPage(viewModel.select(hit)) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Stäng") } },
    )
}

/** Läsarens träffrad: "högtalare · träff 2 av 5 ‹ ›", med ett kryss som stänger den. */
@Composable
fun HitNavigationBar(
    text: String,
    enabled: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(Color(0xE616181D), RoundedCornerShape(24.dp))
            .padding(start = 16.dp)
            .testTag("hit-bar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MonoStyles.DataSmall,
            color = DmzColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).testTag("hit-bar-text"),
        )
        IconButton(onClick = onPrevious, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Föregående träff", tint = DmzColors.Accent)
        }
        IconButton(onClick = onNext, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Nästa träff", tint = DmzColors.Accent)
        }
        IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Stäng träffraden", tint = DmzColors.TextSecondary)
        }
    }
}
