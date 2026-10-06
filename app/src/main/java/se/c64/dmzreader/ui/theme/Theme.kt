package se.c64.dmzreader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DmzColorScheme = darkColorScheme(
    primary = DmzColors.Accent,
    onPrimary = DmzColors.Background,
    secondary = DmzColors.Accent,
    onSecondary = DmzColors.Background,
    background = DmzColors.Background,
    onBackground = DmzColors.TextPrimary,
    surface = DmzColors.Surface,
    onSurface = DmzColors.TextPrimary,
    surfaceVariant = DmzColors.Surface,
    onSurfaceVariant = DmzColors.TextSecondary,
    surfaceContainer = DmzColors.Surface,
    surfaceContainerHigh = DmzColors.Surface,
    outline = DmzColors.BorderStrong,
    outlineVariant = DmzColors.Border,
    error = DmzColors.Failure,
    onError = DmzColors.Background,
)

@Composable
fun DmzReaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DmzColorScheme,
        typography = DmzTypography,
        content = content,
    )
}
