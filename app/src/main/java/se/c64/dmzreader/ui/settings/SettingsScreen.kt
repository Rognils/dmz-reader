package se.c64.dmzreader.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.c64.dmzreader.data.offline.OfflineRepository
import se.c64.dmzreader.data.pdf.PdfRepository
import se.c64.dmzreader.data.repository.LibraryRepository
import se.c64.dmzreader.data.settings.DownloadPreferences
import se.c64.dmzreader.domain.fileSizeText
import se.c64.dmzreader.offline.DownloadScheduler
import se.c64.dmzreader.ui.components.DataRow
import se.c64.dmzreader.ui.components.MonoLabel
import java.io.File
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import se.c64.dmzreader.ui.components.Panel
import se.c64.dmzreader.ui.theme.DmzColors

/** Rubrikrad med tillbakaknapp, för vyer som ligger under en flik. */
@Composable
fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Tillbaka",
                tint = DmzColors.TextPrimary,
            )
        }
        Text(text = title, style = MaterialTheme.typography.headlineMedium, color = DmzColors.TextPrimary)
    }
}

/** Lagringen på telefonen, för inställningarnas avsnitt Lagring. */
data class StorageUiState(
    val downloadBytes: Long = 0,
    val downloadCount: Int = 0,
    val cacheBytes: Long = 0,
    val wifiOnly: Boolean = true,
)

class SettingsViewModel(
    private val offlineRepository: OfflineRepository,
    private val pdfRepository: PdfRepository,
    libraryRepository: LibraryRepository,
    private val downloadPreferences: DownloadPreferences,
    private val downloadScheduler: DownloadScheduler,
    private val thumbnailRoot: File,
) : ViewModel() {
    private val sizes = MutableStateFlow(0L to 0L)

    val state: StateFlow<StorageUiState> = combine(
        sizes,
        libraryRepository.observeIssuesWithState(),
        downloadPreferences.observeWifiOnly(),
    ) { (downloadBytes, cacheBytes), issues, wifiOnly ->
        StorageUiState(downloadBytes, issues.count { it.isDownloaded }, cacheBytes, wifiOnly)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StorageUiState())

    init {
        // Storlekarna mäts om när ett nummers nedladdningsstatus ändras.
        viewModelScope.launch {
            libraryRepository.observeIssuesWithState()
                .map { issues -> issues.map { it.downloadState } }
                .distinctUntilChanged()
                .collect { refresh() }
        }
    }

    private suspend fun refresh() {
        sizes.value = withContext(Dispatchers.IO) {
            offlineRepository.totalBytes() to pdfRepository.cacheBytes() + directoryBytes(thumbnailRoot)
        }
    }

    /** Rensar läs-cachen: tillfälliga PDF:er och miniatyrer. Nedladdningar rörs inte. */
    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                pdfRepository.clearCache()
                thumbnailRoot.deleteRecursively()
            }
            refresh()
        }
    }

    fun removeAllDownloads() {
        viewModelScope.launch {
            offlineRepository.removeAll()
            refresh()
        }
    }

    fun setWifiOnly(value: Boolean) {
        viewModelScope.launch {
            downloadPreferences.setWifiOnly(value)
            // Ett väntande jobb startas om, så att det nya nätverkskravet gäller.
            if (offlineRepository.hasQueued()) downloadScheduler.start(restart = true)
        }
    }

    private fun directoryBytes(directory: File): Long =
        directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

/** Inställningar: lagring och nedladdningar, och ingången till Diagnostik. Import och backup kommer senare. */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val storage by viewModel.state.collectAsStateWithLifecycle()
    var confirmRemoveAll by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("Inställningar", onBack)
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            MonoLabel("Lagring")
            Spacer(Modifier.height(8.dp))
            Panel {
                DataRow(
                    label = "Nedladdningar",
                    value = "${fileSizeText(storage.downloadBytes) ?: "0 MB"} · ${storage.downloadCount} nummer",
                )
                DataRow("Läs-cache", fileSizeText(storage.cacheBytes) ?: "0 MB")
                DataRow(
                    label = "Totalt",
                    value = fileSizeText(storage.downloadBytes + storage.cacheBytes) ?: "0 MB",
                    valueColor = DmzColors.Accent,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = viewModel::clearCache,
                    enabled = storage.cacheBytes > 0,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Rensa läs-cache", color = DmzColors.TextPrimary) }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { confirmRemoveAll = true },
                    enabled = storage.downloadBytes > 0 || storage.downloadCount > 0,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Ta bort alla nedladdningar", color = DmzColors.TextPrimary) }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Läs-cachen är nummer som hämtats tillfälligt med Läs. Systemet kan rensa den när utrymmet tryter.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DmzColors.TextSecondary,
                )
            }

            Spacer(Modifier.height(12.dp))
            Panel(
                modifier = Modifier.toggleable(
                    value = storage.wifiOnly,
                    role = Role.Switch,
                    onValueChange = viewModel::setWifiOnly,
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            "Ladda ned endast via wifi",
                            style = MaterialTheme.typography.titleMedium,
                            color = DmzColors.TextPrimary,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Gäller Ladda ned. Läs hämtar numret direkt, oavsett nät.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = DmzColors.TextSecondary,
                        )
                    }
                    // Hela raden är omkopplaren; den här visar bara läget.
                    Switch(
                        checked = storage.wifiOnly,
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = DmzColors.Background,
                            checkedTrackColor = DmzColors.Accent,
                            uncheckedThumbColor = DmzColors.TextSecondary,
                            uncheckedTrackColor = DmzColors.Surface,
                            uncheckedBorderColor = DmzColors.BorderStrong,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Panel(
                modifier = Modifier.clickable(role = Role.Button, onClick = onOpenDiagnostics),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Diagnostik", style = MaterialTheme.typography.titleMedium, color = DmzColors.TextPrimary)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Arkivindex, databas och test av textsökningen",
                            style = MaterialTheme.typography.bodyMedium,
                            color = DmzColors.TextSecondary,
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = DmzColors.Label,
                    )
                }
            }
        }
    }

    if (confirmRemoveAll) {
        AlertDialog(
            onDismissRequest = { confirmRemoveAll = false },
            title = { Text("Ta bort alla nedladdningar?") },
            text = {
                Text(
                    "${storage.downloadCount} nummer tas bort från telefonen, och kön töms. " +
                        "Läsposition och lästa sidor finns kvar.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoveAll = false
                    viewModel.removeAllDownloads()
                }) { Text("Ta bort alla") }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveAll = false }) { Text("Behåll") } },
        )
    }
}
