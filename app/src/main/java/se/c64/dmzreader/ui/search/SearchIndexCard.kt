package se.c64.dmzreader.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import se.c64.dmzreader.data.pdf.DownloadProgress
import se.c64.dmzreader.data.search.CopyProgress
import se.c64.dmzreader.data.search.IndexDownloadState
import se.c64.dmzreader.data.search.NO_NETWORK_MESSAGE
import se.c64.dmzreader.data.search.WAITING_FOR_WIFI_MESSAGE
import se.c64.dmzreader.data.search.copyProgressText
import se.c64.dmzreader.ui.components.Panel
import se.c64.dmzreader.ui.theme.DmzColors
import se.c64.dmzreader.ui.theme.MonoStyles

const val INDEX_UNREACHABLE_CARD_TEXT =
    "Fulltextsökning kräver ett separat sökindex. Anslut till internet för att hämta det, eller importera en fil manuellt."

fun indexAvailableCardText(sizeText: String) =
    "Fulltextsökning kräver ett sökindex på cirka $sizeText. Allt annat i appen fungerar utan det."

const val INDEX_INTERRUPTED_TEXT = "Hämtningen av sökindexet avbröts. Den fortsätter där den slutade."

/** "34 av 91 MB · 37 %". */
fun downloadProgressLine(progress: DownloadProgress): String =
    copyProgressText(CopyProgress(progress.bytesDownloaded, progress.totalBytes))

/**
 * Kortet i Sök när inget sökindex finns. Det fungerar utan manifestet: går GitHub inte att nå
 * finns alltid "Försök igen" och "Importera manuellt".
 *
 * [manualImport] är förloppet i en manuell import, om en pågår.
 */
@Composable
fun SearchIndexCard(
    state: IndexDownloadState,
    manualImport: String?,
    onDownload: () -> Unit,
    onRetryManifest: () -> Unit,
    onCancel: () -> Unit,
    onImportManually: () -> Unit,
) {
    Panel(modifier = Modifier.testTag("index-card")) {
        Text(
            "Sök i tidningarnas text",
            style = MaterialTheme.typography.titleMedium,
            color = DmzColors.TextPrimary,
        )
        Spacer(Modifier.height(6.dp))
        if (manualImport != null) {
            CardText("Importerar sökindexet från filen…")
            ProgressLine(null, manualImport)
            return@Panel
        }
        SearchIndexDownloadContent(
            state = state,
            onDownload = onDownload,
            onRetryManifest = onRetryManifest,
            onCancel = onCancel,
            onImportManually = onImportManually,
        )
    }
}

/** Texten, förloppet och knapparna för hämtningen. Används i Sök-kortet och under Inställningar → Sökindex. */
@Composable
fun ColumnScope.SearchIndexDownloadContent(
    state: IndexDownloadState,
    onDownload: () -> Unit,
    onRetryManifest: () -> Unit,
    onCancel: () -> Unit,
    onImportManually: (() -> Unit)?,
) {
    when (state) {
        is IndexDownloadState.Available -> {
            CardText(indexAvailableCardText(state.manifest.sizeText))
            PrimaryButton("Ladda ned sökindex", "index-download", onDownload)
        }

        IndexDownloadState.Idle, IndexDownloadState.Checking, is IndexDownloadState.Unreachable -> {
            CardText(INDEX_UNREACHABLE_CARD_TEXT)
            if (state is IndexDownloadState.Checking) {
                Text("Söker efter sökindexet på GitHub…", style = MonoStyles.DataSmall, color = DmzColors.Label)
            }
            Buttons {
                SecondaryButton("Försök igen", "index-retry", enabled = state !is IndexDownloadState.Checking, onRetryManifest)
                onImportManually?.let { SecondaryButton("Importera manuellt", "index-import", onClick = it) }
            }
        }

        is IndexDownloadState.Unsupported -> {
            CardText(state.message)
            onImportManually?.let { Buttons { SecondaryButton("Importera manuellt", "index-import", onClick = it) } }
        }

        is IndexDownloadState.Queued -> {
            CardText("Sökindexet laddas ned när det blir dess tur.")
            ProgressLine(state.progress, state.progress?.let(::downloadProgressLine))
            Buttons { SecondaryButton("Avbryt", "index-cancel", onClick = onCancel) }
        }

        is IndexDownloadState.WaitingForWifi -> {
            CardText(WAITING_FOR_WIFI_MESSAGE)
            Buttons { SecondaryButton("Avbryt", "index-cancel", onClick = onCancel) }
        }

        is IndexDownloadState.WaitingForNetwork -> {
            CardText(NO_NETWORK_MESSAGE)
            Buttons { SecondaryButton("Avbryt", "index-cancel", onClick = onCancel) }
        }

        is IndexDownloadState.Downloading -> {
            CardText("Laddar ned sökindexet från GitHub.")
            ProgressLine(state.progress, downloadProgressLine(state.progress))
            Buttons { SecondaryButton("Avbryt", "index-cancel", onClick = onCancel) }
        }

        is IndexDownloadState.Verifying -> {
            CardText("Kontrollerar den hämtade filen…")
            ProgressLine(null, null)
        }

        is IndexDownloadState.Importing -> {
            CardText("Importerar sökindexet…")
            ProgressLine(null, state.progress?.let(::copyProgressText))
        }

        is IndexDownloadState.Interrupted -> {
            CardText(INDEX_INTERRUPTED_TEXT)
            state.progress?.let { ProgressLine(it, downloadProgressLine(it)) }
            Buttons {
                PrimaryButton("Försök igen", "index-resume", onDownload)
                SecondaryButton("Avbryt", "index-cancel", onClick = onCancel)
            }
        }

        is IndexDownloadState.Failed -> {
            CardText(state.message, tag = "index-error")
            Buttons {
                PrimaryButton("Försök igen", "index-retry-download", onDownload)
                onImportManually?.let { SecondaryButton("Importera manuellt", "index-import", onClick = it) }
            }
        }

        is IndexDownloadState.Done -> CardText(
            "Sökindexet är hämtat och importerat: ${state.info.issueCount} nummer och ${state.info.pageCount} sidor.",
            tag = "index-done",
        )
    }
}

@Composable
private fun CardText(text: String, tag: String = "index-card-text") {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = DmzColors.TextSecondary,
        modifier = Modifier.testTag(tag),
    )
}

@Composable
private fun ProgressLine(progress: DownloadProgress?, text: String?) {
    Spacer(Modifier.height(10.dp))
    val fraction = progress?.fraction
    if (fraction != null) {
        LinearProgressIndicator(
            progress = { fraction },
            color = DmzColors.Accent,
            trackColor = DmzColors.Border,
            modifier = Modifier.fillMaxWidth().height(6.dp),
        )
    } else {
        LinearProgressIndicator(color = DmzColors.Accent, trackColor = DmzColors.Border, modifier = Modifier.fillMaxWidth().height(6.dp))
    }
    if (text != null) {
        Spacer(Modifier.height(4.dp))
        Text(text, style = MonoStyles.DataSmall, color = DmzColors.Label, modifier = Modifier.testTag("index-progress"))
    }
}

@Composable
private fun Buttons(content: @Composable () -> Unit) {
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) { content() }
}

@Composable
private fun PrimaryButton(label: String, tag: String, onClick: () -> Unit) {
    Spacer(Modifier.height(10.dp))
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = DmzColors.Accent, contentColor = DmzColors.Background),
        modifier = Modifier.heightIn(min = 48.dp).testTag(tag),
    ) { Text(label) }
}

@Composable
private fun SecondaryButton(label: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp).testTag(tag),
    ) { Text(label, color = DmzColors.TextPrimary) }
}
