package se.c64.dmzreader.ui.theme

import androidx.compose.ui.graphics.Color

/** Färgerna i planens E3. Appen har bara ett mörkt tema. */
object DmzColors {
    val Background = Color(0xFF0E0F12)
    val Surface = Color(0xFF16181D)
    val Border = Color(0xFF23252B)
    val BorderStrong = Color(0xFF2A2C33)
    val TextPrimary = Color(0xFFECE8DF)
    val TextSecondary = Color(0xFFA9A499)
    val Label = Color(0xFF8E8A80)
    val Accent = Color(0xFFF5B53D)
    val ReaderBackground = Color(0xFF050506)

    // Statusfärger för diagnostiken. Valda för kontrast minst 4.5:1 mot Surface.
    val Success = Color(0xFF7BC47F)
    val Failure = Color(0xFFEF7B6E)
}
