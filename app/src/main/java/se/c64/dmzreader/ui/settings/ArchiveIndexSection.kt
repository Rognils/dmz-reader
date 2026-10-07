package se.c64.dmzreader.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import se.c64.dmzreader.data.inventory.InventoryDiff
import se.c64.dmzreader.data.inventory.InventoryException
import se.c64.dmzreader.data.inventory.InventoryManager
import se.c64.dmzreader.data.inventory.StagedInventory
import se.c64.dmzreader.data.settings.ActiveInventory
import se.c64.dmzreader.data.settings.ActiveInventoryStore
import se.c64.dmzreader.data.settings.InventorySource
import se.c64.dmzreader.ui.components.DataRow
import se.c64.dmzreader.ui.components.MonoLabel
import se.c64.dmzreader.ui.components.Panel
import se.c64.dmzreader.ui.theme.DmzColors
import java.io.InputStream

data class ArchiveIndexUiState(
    val active: ActiveInventory? = null,
    /** Ett validerat index som väntar på att användaren bekräftar importen. */
    val staged: StagedInventory? = null,
    val message: String? = null,
    val working: Boolean = false,
)

/** "Inbyggt i appen" eller "Importerat 7 oktober 2026 21:40". */
fun inventorySourceText(active: ActiveInventory?): String = when {
    active == null -> "Inget index är inläst"
    active.source == InventorySource.IMPORTED ->
        "Importerat" + (active.importedAt?.let { " " + backupDateText(it) } ?: "")
    else -> "Inbyggt i appen"
}

/** Raderna i sammanfattningen som visas före import av ett arkivindex. */
fun inventoryDiffLines(activeVersion: String?, newVersion: String, issueCount: Int, diff: InventoryDiff): List<String> {
    fun examples(ids: List<String>) = if (ids.isEmpty()) "" else
        ": " + ids.take(3).joinToString(", ") + if (ids.size > 3) " med flera" else ""
    return listOf(
        "Aktivt index: ${activeVersion ?: "inget"}",
        "Nytt index: $newVersion, $issueCount nummer",
        "",
        "${diff.added.size} nya nummer" + examples(diff.added),
        "${diff.removed.size} borttagna nummer" + examples(diff.removed),
        "${diff.changed.size} ändrade nummer" + examples(diff.changed),
        "",
        if (diff.isEmpty) {
            "Det nya indexet har samma innehåll som det aktiva."
        } else {
            "Favoriter, bokmärken, läsdata och nedladdade nummer ändras inte. Nummer som försvinner " +
                "ur indexet visas som saknade."
        },
    )
}

class ArchiveIndexViewModel(
    private val manager: InventoryManager,
    activeInventory: ActiveInventoryStore,
    private val openDocument: (Uri) -> InputStream,
) : ViewModel() {
    private val dialog = MutableStateFlow(ArchiveIndexUiState())

    val state: StateFlow<ArchiveIndexUiState> = combine(dialog, activeInventory.observe()) { dialog, active ->
        dialog.copy(active = active)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArchiveIndexUiState())

    /** Kopierar filen till privat lagring och validerar den. Ingenting ändras förrän användaren bekräftar. */
    fun open(uri: Uri) {
        dialog.update { it.copy(working = true) }
        viewModelScope.launch {
            try {
                val staged = manager.stage { openDocument(uri) }
                dialog.update { it.copy(staged = staged, working = false) }
            } catch (e: InventoryException) {
                dialog.update { it.copy(working = false, message = "${e.message}\n\nDet aktiva arkivindexet är oförändrat.") }
            } catch (e: Exception) {
                dialog.update {
                    it.copy(working = false, message = "Filen gick inte att läsa: ${e.message}\n\nDet aktiva arkivindexet är oförändrat.")
                }
            }
        }
    }

    fun confirmImport() {
        val staged = dialog.value.staged ?: return
        dialog.update { it.copy(staged = null, working = true) }
        viewModelScope.launch {
            try {
                manager.commit(staged)
                dialog.update {
                    it.copy(working = false, message = "Arkivindex ${staged.datasetVersion} är importerat: ${staged.issueCount} nummer.")
                }
            } catch (e: Exception) {
                dialog.update {
                    it.copy(working = false, message = "Importen misslyckades, och det tidigare arkivindexet gäller fortfarande: ${e.message}")
                }
            }
        }
    }

    fun cancelImport() {
        val staged = dialog.value.staged ?: return
        dialog.update { it.copy(staged = null) }
        viewModelScope.launch { manager.discard(staged) }
    }

    fun revertToBundled() {
        dialog.update { it.copy(working = true) }
        viewModelScope.launch {
            try {
                manager.revertToBundled()
                dialog.update { it.copy(working = false, message = "Appen använder nu det inbyggda arkivindexet.") }
            } catch (e: Exception) {
                dialog.update { it.copy(working = false, message = "Det gick inte att återgå: ${e.message}") }
            }
        }
    }

    fun dismissMessage() = dialog.update { it.copy(message = null) }
}

/** Inställningar → Arkivindex: aktiv version, källa, import och återgång till det inbyggda (planens D4 och D5). */
@Composable
fun ArchiveIndexSection(viewModel: ArchiveIndexViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmRevert by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.open(uri)
    }
    val imported = state.active?.source == InventorySource.IMPORTED

    MonoLabel("Arkivindex")
    Spacer(Modifier.height(8.dp))
    Panel(modifier = Modifier.testTag("archive-index")) {
        DataRow("Version", state.active?.datasetVersion ?: "–")
        DataRow("Källa", inventorySourceText(state.active))
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) },
            enabled = !state.working,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("Importera arkivindex", color = DmzColors.TextPrimary) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { confirmRevert = true },
            enabled = imported && !state.working,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("Återgå till inbyggt index", color = DmzColors.TextPrimary) }
        Spacer(Modifier.height(6.dp))
        Text(
            "Arkivindexet är listan över nummer i arkivet. Ett nyare index görs på datorn och importeras här, " +
                "utan att appen behöver uppdateras.",
            style = MaterialTheme.typography.bodyMedium,
            color = DmzColors.TextSecondary,
        )
    }

    state.staged?.let { staged ->
        AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            title = { Text("Importera arkivindexet?") },
            text = {
                Column(modifier = Modifier.testTag("index-summary")) {
                    inventoryDiffLines(staged.activeDatasetVersion, staged.datasetVersion, staged.issueCount, staged.diff).forEach { line ->
                        if (line.isEmpty()) Spacer(Modifier.height(8.dp)) else Text(line, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::confirmImport) { Text("Importera") } },
            dismissButton = { TextButton(onClick = viewModel::cancelImport) { Text("Avbryt") } },
        )
    }

    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            title = { Text("Arkivindex") },
            text = { Text(message, modifier = Modifier.testTag("index-message")) },
            confirmButton = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
        )
    }

    if (confirmRevert) {
        AlertDialog(
            onDismissRequest = { confirmRevert = false },
            title = { Text("Återgå till det inbyggda indexet?") },
            text = { Text("Det importerade arkivindexet tas bort. Favoriter, bokmärken, läsdata och nedladdade nummer ändras inte.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRevert = false
                    viewModel.revertToBundled()
                }) { Text("Återgå") }
            },
            dismissButton = { TextButton(onClick = { confirmRevert = false }) { Text("Avbryt") } },
        )
    }
}
