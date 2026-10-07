package se.c64.dmzreader.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import se.c64.dmzreader.data.db.Bookmark
import se.c64.dmzreader.domain.bookmarkLabel
import se.c64.dmzreader.domain.defaultBookmarkTitle
import se.c64.dmzreader.ui.theme.DmzColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** En femuddig stjärna: fylld när sidan har ett bokmärke, annars bara kontur. */
@Composable
fun StarIcon(filled: Boolean, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier = modifier.size(size)) {
        val center = Offset(this.size.width / 2, this.size.height / 2)
        val outer = this.size.minDimension * 0.46f
        val inner = outer * 0.42f
        val path = Path()
        for (point in 0 until 10) {
            val radius = if (point % 2 == 0) outer else inner
            val angle = -PI / 2 + point * PI / 5
            val x = center.x + (radius * cos(angle)).toFloat()
            val y = center.y + (radius * sin(angle)).toFloat()
            if (point == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        if (filled) {
            drawPath(path, tint)
        } else {
            drawPath(path, tint, style = Stroke(width = this.size.minDimension * 0.08f, join = StrokeJoin.Round))
        }
    }
}

/** Märket på omslaget för ett favoritnummer. */
@Composable
fun FavoriteBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(DmzColors.Background.copy(alpha = 0.88f))
            .semantics { contentDescription = "Favorit" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Favorite, contentDescription = null, tint = DmzColors.Accent, modifier = Modifier.size(13.dp))
    }
}

/** En diskret upplysning, till exempel om att arkivfilen har bytts (planens C7) eller att ett nummer saknas (D6). */
@Composable
fun Notice(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = DmzColors.Accent,
        modifier = modifier,
    )
}

/**
 * En rad för ett bokmärke: "Sida X · titel" och anteckningen i sekundär text.
 * Ett tryck öppnar sidan, och pennan öppnar redigeringen.
 */
@Composable
fun BookmarkRow(
    bookmark: Bookmark,
    onOpen: (() -> Unit)?,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = bookmarkLabel(bookmark)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(
                if (onOpen != null) {
                    Modifier.clickable(role = Role.Button, onClickLabel = "Öppna sidan", onClick = onOpen)
                } else {
                    Modifier
                },
            )
            .testTag("bookmark-${bookmark.pageNumber}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StarIcon(filled = true, tint = DmzColors.Accent, size = 18.dp)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = DmzColors.TextPrimary)
            bookmark.note?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = DmzColors.TextSecondary)
            }
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Edit, contentDescription = "Redigera bokmärket $label", tint = DmzColors.Label)
        }
    }
}

/** Dialogen för titel och anteckning. Med [onDelete] går bokmärket också att ta bort. */
@Composable
fun BookmarkEditDialog(
    bookmark: Bookmark,
    onSave: (title: String, note: String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val standard = defaultBookmarkTitle(bookmark.pageNumber)
    // Standardtiteln visas som förslag, inte som text att sudda ut.
    var title by rememberSaveable(bookmark.id) { mutableStateOf(bookmark.title?.takeIf { it != standard }.orEmpty()) }
    var note by rememberSaveable(bookmark.id) { mutableStateOf(bookmark.note.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bokmärke på sida ${bookmark.pageNumber}") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Titel") },
                    placeholder = { Text(standard) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("bookmark-title"),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Anteckning") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth().testTag("bookmark-note"),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(title, note) }) { Text("Spara") } },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("Ta bort", color = DmzColors.Failure) }
                }
                TextButton(onClick = onDismiss) { Text("Avbryt") }
            }
        },
    )
}
