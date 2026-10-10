package se.c64.dmzreader.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Ett stapeldiagram, för fliken Statistik. Appen har bara grunduppsättningen av
 * Material-ikoner, där ingen diagramikon ingår, så den här är ritad för hand i
 * samma rutnät (24 × 24) och med samma former som Materials "bar chart".
 */
val BarChartIcon: ImageVector by lazy {
    ImageVector.Builder(name = "BarChart", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .apply {
            // Tre staplar: låg till vänster, hög i mitten, medelhög till höger.
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 9f); horizontalLineToRelative(4f); verticalLineToRelative(11f); horizontalLineToRelative(-4f); close()
                moveTo(10f, 4f); horizontalLineToRelative(4f); verticalLineToRelative(16f); horizontalLineToRelative(-4f); close()
                moveTo(16f, 13f); horizontalLineToRelative(4f); verticalLineToRelative(7f); horizontalLineToRelative(-4f); close()
            }
        }
        .build()
}
