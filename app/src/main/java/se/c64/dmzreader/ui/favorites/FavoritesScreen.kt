package se.c64.dmzreader.ui.favorites

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import se.c64.dmzreader.ui.components.PlaceholderScreen

@Composable
fun FavoritesScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = "Favoriter",
        message = "Här samlas dina favoritnummer och bokmärken.",
        modifier = modifier,
    )
}
