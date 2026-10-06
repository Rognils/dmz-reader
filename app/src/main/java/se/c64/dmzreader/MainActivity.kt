package se.c64.dmzreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.toArgb
import se.c64.dmzreader.ui.DmzReaderRoot
import se.c64.dmzreader.ui.theme.DmzColors
import se.c64.dmzreader.ui.theme.DmzReaderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val bars = SystemBarStyle.dark(DmzColors.Background.toArgb())
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        super.onCreate(savedInstanceState)
        val container = (application as DmzReaderApp).container
        setContent {
            DmzReaderTheme {
                DmzReaderRoot(container)
            }
        }
    }
}
