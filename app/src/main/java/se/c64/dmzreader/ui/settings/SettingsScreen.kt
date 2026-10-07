package se.c64.dmzreader.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.flow.update
import se.c64.dmzreader.data.backup.BackupFile
import se.c64.dmzreader.data.backup.BackupFormatException
import se.c64.dmzreader.data.backup.BackupRepository
import se.c64.dmzreader.data.backup.ImportSummary
import se.c64.dmzreader.data.backup.parseBackup
import se.c64.dmzreader.data.settings.BackupPreferences
import se.c64.dmzreader.ui.theme.MonoStyles
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
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

/** Läget för säkerhetskopian i inställningarna. */
data class BackupUiState(
    /** När den senaste exporten gjordes, eller null om ingen har gjorts. */
    val lastExport: Long? = null,
    /** En inläst backup som väntar på att användaren bekräftar importen. */
    val pendingImport: PendingImport? = null,
    /** Ett besked till användaren: klart, eller vad som gick fel. */
    val message: String? = null,
)

class PendingImport(val backup: BackupFile, val summary: ImportSummary)

/** "7 oktober 2026 20:15". */
fun backupDateText(timeMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("d MMMM yyyy HH:mm", Locale.forLanguageTag("sv"))
        .format(Instant.ofEpochMilli(timeMillis).atZone(zone))

/** Raderna i sammanfattningen som visas före en import. */
fun importSummaryLines(summary: ImportSummary): List<String> = listOf(
    "Filen innehåller:",
    "${summary.favorites} favoritnummer",
    "${summary.bookmarks} bokmärken",
    "${summary.sessions} lässessioner",
    "${summary.issuesWithReadingData} nummer med läsdata",
    "",
    if (summary.nothingToDo) {
        "Allt i filen finns redan på telefonen. Ingenting ändras."
    } else {
        "Importen lägger till ${summary.newIssues} nya nummer och uppdaterar ${summary.updatedIssues}, " +
            "lägger till ${summary.newBookmarks} bokmärken och uppdaterar ${summary.updatedBookmarks}, " +
            "och lägger till ${summary.newSessions} sessioner. Ingen läsdata tas bort."
    },
)

class SettingsViewModel(
    private val offlineRepository: OfflineRepository,
    private val pdfRepository: PdfRepository,
    libraryRepository: LibraryRepository,
    private val downloadPreferences: DownloadPreferences,
    private val downloadScheduler: DownloadScheduler,
    private val thumbnailRoot: File,
    private val backupRepository: BackupRepository,
    private val backupPreferences: BackupPreferences,
    private val readDocument: (Uri) -> String,
    private val writeDocument: (Uri, String) -> Unit,
) : ViewModel() {
    private val sizes = MutableStateFlow(0L to 0L)
    private val backupDialog = MutableStateFlow(BackupUiState())

    val backup: StateFlow<BackupUiState> =
        combine(backupDialog, backupPreferences.observeLastExport()) { dialog, lastExport ->
            dialog.copy(lastExport = lastExport)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupUiState())

    /** Föreslaget filnamn för exporten: dmz-reader-backup-ÅÅÅÅ-MM-DD.json. */
    fun exportFileName(): String = BackupRepository.fileName(System.currentTimeMillis())

    /** Skriver säkerhetskopian till filen användaren valde. */
    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            try {
                val data = backupRepository.currentData()
                val text = backupRepository.export()
                withContext(Dispatchers.IO) { writeDocument(uri, text) }
                backupPreferences.setLastExport(System.currentTimeMillis())
                backupDialog.update {
                    it.copy(
                        message = "Säkerhetskopian är sparad: ${data.userStates.size} nummer med data, " +
                            "${data.bookmarks.count { !it.isDeleted }} bokmärken och ${data.sessions.size} lässessioner.",
                    )
                }
            } catch (e: Exception) {
                backupDialog.update { it.copy(message = "Säkerhetskopian kunde inte sparas: ${e.message}") }
            }
        }
    }

    /** Läser och granskar filen användaren valde. Ingenting importeras förrän användaren bekräftar. */
    fun openImport(uri: Uri) {
        viewModelScope.launch {
            try {
                val backup = withContext(Dispatchers.IO) { parseBackup(readDocument(uri)) }
                val summary = backupRepository.preview(backup)
                backupDialog.update { it.copy(pendingImport = PendingImport(backup, summary)) }
            } catch (e: BackupFormatException) {
                backupDialog.update { it.copy(message = e.message) }
            } catch (e: Exception) {
                backupDialog.update { it.copy(message = "Filen gick inte att läsa: ${e.message}") }
            }
        }
    }

    fun confirmImport() {
        val pending = backupDialog.value.pendingImport ?: return
        backupDialog.update { it.copy(pendingImport = null) }
        viewModelScope.launch {
            try {
                val summary = backupRepository.import(pending.backup)
                backupDialog.update {
                    it.copy(
                        message = if (summary.nothingToDo) {
                            "Allt i säkerhetskopian fanns redan. Ingenting ändrades."
                        } else {
                            "Importen är klar: ${summary.newIssues} nya nummer, ${summary.updatedIssues} uppdaterade, " +
                                "${summary.newBookmarks} nya bokmärken, ${summary.updatedBookmarks} uppdaterade " +
                                "och ${summary.newSessions} nya sessioner."
                        },
                    )
                }
            } catch (e: Exception) {
                // Importen görs i en transaktion, så ett fel lämnar allt som det var.
                backupDialog.update { it.copy(message = "Importen misslyckades, och ingenting har ändrats: ${e.message}") }
            }
        }
    }

    fun dismissBackupDialog() {
        backupDialog.update { it.copy(pendingImport = null, message = null) }
    }

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

/** Inställningar: lagring och nedladdningar, säkerhetskopia och ingången till Diagnostik. */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    archiveIndexViewModel: ArchiveIndexViewModel,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val storage by viewModel.state.collectAsStateWithLifecycle()
    val backup by viewModel.backup.collectAsStateWithLifecycle()
    var confirmRemoveAll by rememberSaveable { mutableStateOf(false) }
    // Filerna väljs i systemets filväljare. Appen behöver ingen lagringsbehörighet.
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.exportTo(uri)
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.openImport(uri)
    }

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
            MonoLabel("Säkerhetskopia")
            Spacer(Modifier.height(8.dp))
            Panel {
                Text(
                    text = backup.lastExport?.let { "Senaste export: ${backupDateText(it)}" } ?: "Ingen export har gjorts",
                    style = MonoStyles.Data,
                    color = DmzColors.TextPrimary,
                    modifier = Modifier.testTag("last-export"),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { exportPicker.launch(viewModel.exportFileName()) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Exportera", color = DmzColors.TextPrimary) }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    // Filtypen kan anges olika av olika filhanterare, så alla filer går att välja.
                    onClick = { importPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Importera", color = DmzColors.TextPrimary) }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Säkerhetskopian innehåller favoriter, bokmärken, läspositioner, lästa sidor och lässessioner. " +
                        "Nedladdade nummer ingår inte. En import lägger till och slår ihop, och tar aldrig bort något.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DmzColors.TextSecondary,
                )
            }

            Spacer(Modifier.height(20.dp))
            ArchiveIndexSection(archiveIndexViewModel)

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

    backup.pendingImport?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::dismissBackupDialog,
            title = { Text("Importera säkerhetskopian?") },
            text = {
                Column(modifier = Modifier.testTag("import-summary")) {
                    Text(
                        "Exporterad ${pending.backup.exportedAt.take(10)} med appversion ${pending.backup.appVersion}.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    importSummaryLines(pending.summary).forEach { line ->
                        if (line.isEmpty()) Spacer(Modifier.height(8.dp)) else Text(line, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::confirmImport) { Text("Importera") } },
            dismissButton = { TextButton(onClick = viewModel::dismissBackupDialog) { Text("Avbryt") } },
        )
    }

    backup.message?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissBackupDialog,
            title = { Text("Säkerhetskopia") },
            text = { Text(message, modifier = Modifier.testTag("backup-message")) },
            confirmButton = { TextButton(onClick = viewModel::dismissBackupDialog) { Text("OK") } },
        )
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
