package se.c64.dmzreader.ui.settings

import androidx.compose.foundation.clickable
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

/**
 * FAS 1: bara ingången till Diagnostik. Import, backup och lagring kommer i
 * senare faser.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader("Inställningar", onBack)
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
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
}
