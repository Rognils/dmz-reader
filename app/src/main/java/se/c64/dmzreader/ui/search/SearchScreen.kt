package se.c64.dmzreader.ui.search

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import se.c64.dmzreader.ui.components.PlaceholderScreen

@Composable
fun SearchScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = "Sök",
        message = "Här kommer du att kunna söka efter nummer, och senare i all text i arkivet.",
        modifier = modifier,
    )
}
