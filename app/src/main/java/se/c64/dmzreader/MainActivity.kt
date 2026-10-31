package se.c64.dmzreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toArgb
import se.c64.dmzreader.ui.components.LocalBundledCovers
import se.c64.dmzreader.ui.DmzReaderRoot
import se.c64.dmzreader.ui.theme.DmzColors
import se.c64.dmzreader.ui.theme.DmzReaderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val bars = SystemBarStyle.dark(DmzColors.Background.toArgb())
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        super.onCreate(savedInstanceState)
        val container = (application as DmzReaderApp).container
        // Debug-bygget: hämta sökindexets manifest från en annan adress, till exempel en prerelease.
        if (BuildConfig.DEBUG && intent?.hasExtra(EXTRA_MANIFEST) == true) {
            container.searchIndexManifestOverride = intent.getStringExtra(EXTRA_MANIFEST)
        }
        setContent {
            DmzReaderTheme {
                // Omslagen behöver veta vilka miniatyrer som finns i den här appversionen.
                CompositionLocalProvider(LocalBundledCovers provides container.bundledCovers) {
                    DmzReaderRoot(container)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as DmzReaderApp).container.downloadScheduler.setForeground(true)
        // Nummer som står kvar i nedladdningskön, till exempel efter att appen stoppats.
        (application as DmzReaderApp).container.resumeDownloads()
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            (application as DmzReaderApp).container.downloadScheduler.setForeground(false)
        }
        super.onStop()
    }

    private companion object {
        const val EXTRA_MANIFEST = "dmzSearchIndexManifest"
    }
}
