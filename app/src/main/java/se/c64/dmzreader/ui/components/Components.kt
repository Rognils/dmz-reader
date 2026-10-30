package se.c64.dmzreader.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.draw.clip
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

/**
 * Ett tunt progress-streck. [fraction] är 0–1. Strecket är dekoration: värdet
 * ska också finnas som text eller i en beskrivning för skärmläsare.
 */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(2.dp)
    Box(modifier = modifier.fillMaxWidth().height(3.dp).clip(shape).background(DmzColors.BorderStrong)) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(DmzColors.Accent),
        )
    }
}

/**
 * Märket på omslaget för ett nedladdat nummer: en pil ned mot en linje, i en
 * mörk rundel så att det syns mot både ljusa och mörka omslag.
 */
@Composable
fun OfflineBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(DmzColors.Background.copy(alpha = 0.88f))
            .border(1.dp, DmzColors.Accent, CircleShape)
            .semantics { contentDescription = "Nedladdad" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(12.dp)) {
            val stroke = size.width * 0.14f
            val middle = size.width / 2
            val tip = size.height * 0.62f
            val head = size.width * 0.28f
            fun line(from: Offset, to: Offset) =
                drawLine(DmzColors.Accent, from, to, strokeWidth = stroke, cap = StrokeCap.Round)
            line(Offset(middle, size.height * 0.05f), Offset(middle, tip))
            line(Offset(middle - head, tip - head), Offset(middle, tip))
            line(Offset(middle + head, tip - head), Offset(middle, tip))
            line(Offset(size.width * 0.12f, size.height * 0.94f), Offset(size.width * 0.88f, size.height * 0.94f))
        }
    }
}
