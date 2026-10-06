package se.c64.dmzreader.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import se.c64.dmzreader.ui.theme.DmzColors
import se.c64.dmzreader.ui.theme.MonoStyles

/** Liten etikett i mono och versaler. */
@Composable
fun MonoLabel(text: String, modifier: Modifier = Modifier, color: Color = DmzColors.Label) {
    Text(text = text.uppercase(), style = MonoStyles.Label, color = color, modifier = modifier)
}

/** En yta med kantlinje, appens grundkort. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = DmzColors.Surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, DmzColors.Border),
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

/** Rad med etikett till vänster och ett värde i mono till höger. */
@Composable
fun DataRow(label: String, value: String, valueColor: Color = DmzColors.TextPrimary) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = DmzColors.TextSecondary,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        Text(text = value, style = MonoStyles.Data, color = valueColor, textAlign = TextAlign.End)
    }
}

/** Platshållare för flikar som byggs i senare faser. */
@Composable
fun PlaceholderScreen(title: String, message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineMedium, color = DmzColors.TextPrimary)
        Spacer(Modifier.height(12.dp))
        Text(text = message, style = MaterialTheme.typography.bodyLarge, color = DmzColors.TextSecondary)
    }
}
