package se.c64.dmzreader.ui.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.c64.dmzreader.domain.MarkdownBlock
import se.c64.dmzreader.domain.MarkdownSpan
import se.c64.dmzreader.domain.parseMarkdown
import se.c64.dmzreader.ui.settings.ScreenHeader
import se.c64.dmzreader.ui.theme.DmzColors

/** Filen med Om-texten i appens assets. Den går att ändra utan att röra koden. */
const val ABOUT_ASSET = "about_sv.md"

/**
 * Om-sidan. Texten läses ur assets ([readText]) och visas med appens egen
 * Markdown-tolk. Länkar öppnas i telefonens webbläsare.
 */
@Composable
fun AboutScreen(
    readText: () -> String,
    version: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val blocks by produceState<List<MarkdownBlock>>(emptyList(), version) {
        value = withContext(Dispatchers.IO) {
            try {
                parseMarkdown(readText(), version)
            } catch (e: Exception) {
                parseMarkdown("# Om DMZ Reader\n\nVersion {version}", version)
            }
        }
    }
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("about")) {
        // Sidans egen rubrik står i texten. Toppraden har bara tillbakaknappen.
        ScreenHeader("", onBack)
        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            blocks.forEachIndexed { index, block -> MarkdownBlockView(block, first = index == 0) }
        }
    }
}

@Composable
private fun MarkdownBlockView(block: MarkdownBlock, first: Boolean) {
    val text = remember(block) { annotated(block.spans) }
    when (block) {
        is MarkdownBlock.Heading -> {
            if (!first) Spacer(Modifier.height(if (block.level == 1) 24.dp else 28.dp))
            Text(
                text = text,
                style = if (block.level == 1) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                color = if (block.level == 1) DmzColors.TextPrimary else DmzColors.Accent,
                // Rubriker går att hoppa mellan med en skärmläsare.
                modifier = Modifier.fillMaxWidth().semantics { heading() },
            )
            Spacer(Modifier.height(if (block.level == 1) 12.dp else 8.dp))
        }

        is MarkdownBlock.Paragraph -> {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = DmzColors.TextPrimary,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * Gör om textbitarna till en text med fetstil och länkar. En länk blir en
 * LinkAnnotation.Url: den får länkroll för skärmläsare och öppnas i webbläsaren.
 */
private fun annotated(spans: List<MarkdownSpan>): AnnotatedString = buildAnnotatedString {
    val linkStyles = TextLinkStyles(
        style = SpanStyle(color = DmzColors.Accent, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Medium),
    )
    for (span in spans) {
        when (span) {
            is MarkdownSpan.Plain ->
                if (span.bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) } else append(span.text)

            is MarkdownSpan.Link -> withLink(LinkAnnotation.Url(span.url, linkStyles)) { append(span.text) }
        }
    }
}
