package se.c64.dmzreader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import se.c64.dmzreader.InventoryStatus
import se.c64.dmzreader.data.db.PublicationCount
import se.c64.dmzreader.data.inventory.InventoryLoadResult
import se.c64.dmzreader.data.repository.DiagnosticsRepository
import se.c64.dmzreader.data.repository.LibraryRepository
import se.c64.dmzreader.data.search.FtsCaseResult
import se.c64.dmzreader.data.search.FtsSelfTestResult
import se.c64.dmzreader.data.settings.ActiveInventory
import se.c64.dmzreader.data.settings.ActiveInventoryStore
import se.c64.dmzreader.ui.components.DataRow
import se.c64.dmzreader.ui.components.MonoLabel
import se.c64.dmzreader.ui.components.Panel
import se.c64.dmzreader.ui.theme.DmzColors
import se.c64.dmzreader.ui.theme.MonoStyles

data class DiagnosticsUiState(
    val publications: List<PublicationCount> = emptyList(),
    val activeInventory: ActiveInventory? = null,
    val inventoryStatus: InventoryStatus = InventoryStatus.Loading,
    val sqliteVersion: String? = null,
    val ftsRunning: Boolean = false,
    val ftsResult: FtsSelfTestResult? = null,
)

private data class LocalState(
    val sqliteVersion: String? = null,
    val ftsRunning: Boolean = false,
    val ftsResult: FtsSelfTestResult? = null,
)

class DiagnosticsViewModel(
    libraryRepository: LibraryRepository,
    activeInventory: ActiveInventoryStore,
    inventoryStatus: StateFlow<InventoryStatus>,
    private val diagnostics: DiagnosticsRepository,
) : ViewModel() {
    private val local = MutableStateFlow(LocalState())

    val state: StateFlow<DiagnosticsUiState> = combine(
        libraryRepository.observePublicationCounts(),
        activeInventory.observe(),
        inventoryStatus,
        local,
    ) { publications, active, status, localState ->
        DiagnosticsUiState(
            publications = publications,
            activeInventory = active,
            inventoryStatus = status,
            sqliteVersion = localState.sqliteVersion,
            ftsRunning = localState.ftsRunning,
            ftsResult = localState.ftsResult,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsUiState())

    init {
        viewModelScope.launch {
            val version = try {
                diagnostics.sqliteVersion()
            } catch (e: Exception) {
                "fel: ${e.message}"
            }
            local.update { it.copy(sqliteVersion = version) }
        }
        runFtsTest()
    }

    fun runFtsTest() {
        if (local.value.ftsRunning) return
        local.update { it.copy(ftsRunning = true) }
        viewModelScope.launch {
            val result = diagnostics.runFtsSelfTest()
            local.update { it.copy(ftsRunning = false, ftsResult = result) }
        }
    }
}

@Composable
fun DiagnosticsScreen(
    viewModel: DiagnosticsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader("Diagnostik", onBack)
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { InventoryPanel(state) }
            item { DatabasePanel(state) }
            item { FtsPanel(state, onRunAgain = viewModel::runFtsTest) }
            state.ftsResult?.cases?.forEach { case ->
                item(key = case.name) { FtsCasePanel(case) }
            }
        }
    }
}

@Composable
private fun InventoryPanel(state: DiagnosticsUiState) {
    Panel {
        MonoLabel("Arkivindex")
        Spacer(Modifier.height(8.dp))
        when (val status = state.inventoryStatus) {
            InventoryStatus.Loading -> DataRow("Inläsning", "pågår")
            is InventoryStatus.Done -> when (val result = status.result) {
                is InventoryLoadResult.Replaced ->
                    DataRow("Inläsning", "inläst nu (${result.issueCount} nummer)")
                is InventoryLoadResult.AlreadyActive ->
                    DataRow("Inläsning", "redan aktivt")
                is InventoryLoadResult.Rejected -> {
                    DataRow("Inläsning", "AVVISAT", valueColor = DmzColors.Failure)
                    Text(result.message, style = MaterialTheme.typography.bodyMedium, color = DmzColors.TextSecondary)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
        DataRow("datasetVersion", state.activeInventory?.datasetVersion ?: "–")
        DataRow("schemaVersion", state.activeInventory?.schemaVersion?.toString() ?: "–")
        HorizontalDivider(color = DmzColors.Border, modifier = Modifier.padding(vertical = 6.dp))
        if (state.publications.isEmpty()) {
            DataRow("Nummer", "0")
        } else {
            state.publications.forEach { DataRow(it.publicationTitle, "${it.issueCount} nummer") }
            DataRow("Totalt", "${state.publications.sumOf { it.issueCount }} nummer")
        }
    }
}

@Composable
private fun DatabasePanel(state: DiagnosticsUiState) {
    Panel {
        MonoLabel("Databas")
        Spacer(Modifier.height(8.dp))
        DataRow("SQLite (BundledSQLiteDriver)", state.sqliteVersion ?: "…")
    }
}

@Composable
private fun FtsPanel(state: DiagnosticsUiState, onRunAgain: () -> Unit) {
    val result = state.ftsResult
    Panel {
        MonoLabel("FTS5-test")
        Spacer(Modifier.height(8.dp))
        when {
            state.ftsRunning && result == null -> DataRow("Resultat", "körs …")
            result == null -> DataRow("Resultat", "inte kört")
            else -> {
                DataRow(
                    label = "Resultat",
                    value = if (result.passed) "GODKÄNT" else "UNDERKÄNT",
                    valueColor = if (result.passed) DmzColors.Success else DmzColors.Failure,
                )
                Text(result.summary, style = MaterialTheme.typography.bodyMedium, color = DmzColors.TextSecondary)
                if (result.error == null) {
                    Spacer(Modifier.height(6.dp))
                    DataRow("SQLite (testindexet)", result.sqliteVersion ?: "–")
                    DataRow("Tokenizer", result.tokenizer ?: "–")
                    DataRow(
                        label = "Öppnat skrivskyddat",
                        value = if (result.readOnlyEnforced) "ja" else "NEJ",
                        valueColor = if (result.readOnlyEnforced) DmzColors.TextPrimary else DmzColors.Failure,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onRunAgain,
            enabled = !state.ftsRunning,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(if (state.ftsRunning) "Testet körs …" else "Kör FTS5-test igen")
        }
    }
}

@Composable
private fun FtsCasePanel(case: FtsCaseResult) {
    Panel {
        MonoLabel(
            text = if (case.passed) "Rätt" else "Fel",
            color = if (case.passed) DmzColors.Success else DmzColors.Failure,
        )
        Spacer(Modifier.height(4.dp))
        Text(case.name, style = MaterialTheme.typography.titleMedium, color = DmzColors.TextPrimary)
        Spacer(Modifier.height(6.dp))
        DataRow("Sökfråga", case.query)
        case.issueFilter?.let { DataRow("I numret", it) }
        DataRow("Träffar", "${case.actual.size} (väntat ${case.expected.size})")
        case.actual.forEach { hit ->
            Text(hit, style = MonoStyles.DataSmall, color = DmzColors.TextSecondary)
        }
        case.topSnippet?.let { snippet ->
            Spacer(Modifier.height(8.dp))
            Text(snippet, style = MaterialTheme.typography.bodyMedium, color = DmzColors.TextPrimary)
        }
        case.detail?.let { detail ->
            Spacer(Modifier.height(8.dp))
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = DmzColors.Failure)
        }
    }
}
