package se.c64.dmzreader.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import se.c64.dmzreader.data.search.CopyProgress
import se.c64.dmzreader.data.search.IndexDownloadState
import se.c64.dmzreader.data.search.SearchIndexDownloads
import se.c64.dmzreader.data.search.inProgress
import se.c64.dmzreader.ui.search.SearchIndexDownloadContent
import androidx.compose.runtime.LaunchedEffect
import se.c64.dmzreader.data.search.SearchIndexException
import se.c64.dmzreader.data.search.SearchIndexInfo
import se.c64.dmzreader.data.search.SearchIndexManager
import se.c64.dmzreader.data.search.copyProgressText
import se.c64.dmzreader.data.settings.ActiveInventory
import se.c64.dmzreader.data.settings.ActiveInventoryStore
import se.c64.dmzreader.domain.INDEX_OTHER_VERSION_TEXT
import se.c64.dmzreader.ui.components.DataRow
import se.c64.dmzreader.ui.components.MonoLabel
import se.c64.dmzreader.ui.components.Panel
import se.c64.dmzreader.ui.theme.DmzColors
import se.c64.dmzreader.ui.theme.MonoStyles
import java.io.InputStream

data class SearchIndexUiState(
    val info: SearchIndexInfo? = null,
    val active: ActiveInventory? = null,
    /** Förloppet i kopieringen. null när ingen import pågår. */
    val progress: CopyProgress? = null,
    /** Sant medan filen kontrolleras efter kopieringen. */
    val checking: Boolean = false,
    val message: String? = null,
) {
    val working: Boolean get() = progress != null || checking

    /** Sant när sökindexet byggdes av ett annat arkivindex än det aktiva (planens D3). */
    val otherVersion: Boolean
        get() = info != null && active != null && info.inventoryDatasetVersion != active.datasetVersion
}

/** Raderna som beskriver det importerade sökindexet. */
fun searchIndexRows(info: SearchIndexInfo): List<Pair<String, String>> = listOf(
    "Arkivversion" to info.inventoryDatasetVersion,
    "Normalisering" to (info.normalizerVersion?.let { "version $it" } ?: "okänd"),
    "Innehåll" to "${info.issueCount} nummer, ${info.pageCount} sidor",
    "Storlek" to "${(info.sizeBytes + 500_000) / 1_000_000} MB",
    "Importerat" to backupDateText(info.importedAt),
)

class SearchIndexViewModel(
    private val manager: SearchIndexManager,
    activeInventory: ActiveInventoryStore,
    private val openDocument: (Uri) -> InputStream,
    private val documentSize: (Uri) -> Long?,
    /** Lever längre än vyn, så att en import inte avbryts av att användaren lämnar inställningarna. */
    private val importScope: CoroutineScope,
) : ViewModel() {
    private val local = MutableStateFlow(SearchIndexUiState())

    val state: StateFlow<SearchIndexUiState> = combine(local, manager.index, activeInventory.observe()) { local, index, active ->
        local.copy(info = index?.info, active = active)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchIndexUiState())

    /** Kopierar filen till privat lagring, kontrollerar den och byter in den. */
    fun import(uri: Uri) {
        if (local.value.working) return
        local.update { it.copy(progress = CopyProgress(0, null)) }
        importScope.launch {
            val started = System.currentTimeMillis()
            try {
                val total = try {
                    documentSize(uri)
                } catch (e: Exception) {
                    null
                }
                val info = manager.import(
                    open = { openDocument(uri) },
                    totalBytes = total,
                    onProgress = { progress ->
                        // Vid 100 % är kopieringen klar och kontrollen börjar.
                        local.update { it.copy(progress = progress, checking = progress.totalBytes == progress.copiedBytes) }
                    },
                )
                val seconds = (System.currentTimeMillis() - started) / 1000.0
                local.update {
                    it.copy(
                        progress = null,
                        checking = false,
                        message = "Sökindexet är importerat: ${info.issueCount} nummer och ${info.pageCount} sidor, " +
                            "på %.1f sekunder.".format(seconds),
                    )
                }
            } catch (e: SearchIndexException) {
                local.update { it.copy(progress = null, checking = false, message = "${e.message}\n\n${unchangedText()}") }
            } catch (e: Exception) {
                local.update {
                    it.copy(progress = null, checking = false, message = "Importen misslyckades: ${e.message}\n\n${unchangedText()}")
                }
            }
        }
    }

    private fun unchangedText() =
        if (manager.index.value != null) "Det tidigare sökindexet är oförändrat." else "Inget sökindex är importerat."

    fun remove() {
        viewModelScope.launch {
            manager.remove()
            local.update { it.copy(message = "Sökindexet är borttaget. Sökningen gäller nu år, nummer, publikation och bokmärken.") }
        }
    }

    fun dismissMessage() = local.update { it.copy(message = null) }
}

/** Filtyperna som filväljaren erbjuder för ett sökindex. */
val SEARCH_INDEX_MIME_TYPES = arrayOf("application/octet-stream", "application/vnd.sqlite3", "application/x-sqlite3", "*/*")

@Composable
fun SearchIndexMessageDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sökindex") },
        text = { Text(message, modifier = Modifier.testTag("search-index-message")) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

/**
 * Inställningar → Sökindex: hämtning från GitHub, manuell import, uppgifter om det importerade
 * indexet och borttagning (planens D2 och D5).
 */
@Composable
fun SearchIndexSection(
    viewModel: SearchIndexViewModel,
    downloads: SearchIndexDownloads,
    /** Manifestadressen i debug-bygget, om den är ändrad. Visas bara där. */
    debugManifestUrl: String? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val download by downloads.state.collectAsStateWithLifecycle()
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    // Knappen trycktes medan ett index finns: fråga när manifestet är hämtat.
    var askBeforeReplacing by rememberSaveable { mutableStateOf(false) }
    var showDownload by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.import(uri)
    }
    val info = state.info
    val downloading = download.inProgress || download is IndexDownloadState.Interrupted
    LaunchedEffect(downloading) { if (downloading) showDownload = true }

    MonoLabel("Sökindex")
    Spacer(Modifier.height(8.dp))
    Panel(modifier = Modifier.testTag("search-index")) {
        if (info == null) {
            DataRow("Status", "Inget sökindex är importerat")
        } else {
            searchIndexRows(info).forEach { (label, value) -> DataRow(label, value) }
            if (state.otherVersion) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "$INDEX_OTHER_VERSION_TEXT Aktivt arkivindex: ${state.active?.datasetVersion}. Sökningen fungerar ändå.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DmzColors.TextSecondary,
                    modifier = Modifier.testTag("search-index-other-version"),
                )
            }
        }
        state.progress?.let { progress ->
            Spacer(Modifier.height(10.dp))
            val fraction = progress.percent
            if (fraction != null && !state.checking) {
                LinearProgressIndicator(
                    progress = { fraction / 100f },
                    color = DmzColors.Accent,
                    trackColor = DmzColors.Border,
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
            } else {
                LinearProgressIndicator(
                    color = DmzColors.Accent,
                    trackColor = DmzColors.Border,
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (state.checking) "Kontrollerar filen…" else "Kopierar ${copyProgressText(progress)}",
                style = MonoStyles.DataSmall,
                color = DmzColors.Label,
                modifier = Modifier.testTag("search-index-progress"),
            )
        }
        if (debugManifestUrl != null) DataRow("Manifest (debug)", debugManifestUrl)
        if (showDownload && download !is IndexDownloadState.Idle) {
            Spacer(Modifier.height(10.dp))
            SearchIndexDownloadContent(
                state = download,
                onDownload = downloads::request,
                onRetryManifest = { downloads.checkManifest(force = true) },
                onCancel = downloads::cancel,
                onImportManually = null,
            )
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = {
                showDownload = true
                if (info == null) {
                    downloads.request()
                } else {
                    // Ett index finns redan: visa vad som finns på GitHub och fråga först.
                    askBeforeReplacing = true
                    downloads.checkManifest(force = true)
                }
            },
            enabled = !state.working && !downloading,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("settings-index-download"),
        ) { Text("Ladda ned sökindex", color = DmzColors.TextPrimary) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { picker.launch(SEARCH_INDEX_MIME_TYPES) },
            enabled = !state.working && !downloading,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(if (info == null) "Importera sökindex" else "Importera nytt sökindex", color = DmzColors.TextPrimary) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { confirmRemove = true },
            enabled = info != null && !state.working,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("Ta bort sökindex", color = DmzColors.TextPrimary) }
        Spacer(Modifier.height(6.dp))
        Text(
            "Sökindexet gör det möjligt att söka i tidningarnas text. Det importeras separat och påverkar inte " +
                "favoriter, bokmärken eller läsdata.",
            style = MaterialTheme.typography.bodyMedium,
            color = DmzColors.TextSecondary,
        )
    }

    state.message?.let { message -> SearchIndexMessageDialog(message, viewModel::dismissMessage) }

    val available = download as? IndexDownloadState.Available
    if (askBeforeReplacing && available != null && info != null) {
        AlertDialog(
            onDismissRequest = { askBeforeReplacing = false },
            title = { Text("Ladda ned sökindex?") },
            text = {
                Text(
                    "Sökindexet i GitHub-releasen ${available.manifest.releaseTag} är cirka ${available.manifest.sizeText} " +
                        "(arkivversion ${available.manifest.inventoryDatasetVersion}). Det ersätter det nuvarande sökindexet " +
                        "först när hämtningen och kontrollen har lyckats.",
                    modifier = Modifier.testTag("index-replace-question"),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askBeforeReplacing = false
                    downloads.request()
                }) { Text("Ladda ned") }
            },
            dismissButton = { TextButton(onClick = { askBeforeReplacing = false }) { Text("Avbryt") } },
        )
    } else if (askBeforeReplacing && download !is IndexDownloadState.Checking && download !is IndexDownloadState.Available) {
        // Manifestet gick inte att hämta eller passar inte. Beskedet står i panelen.
        LaunchedEffect(download) { askBeforeReplacing = false }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Ta bort sökindexet?") },
            text = { Text("Sökning i tidningarnas text slutar fungera tills ett sökindex importeras igen. Inget annat ändras.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    viewModel.remove()
                }) { Text("Ta bort") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Avbryt") } },
        )
    }
}
