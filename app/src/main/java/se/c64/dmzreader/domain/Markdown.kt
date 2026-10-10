package se.c64.dmzreader.domain

/**
 * En liten Markdown-tolk för appens egna texter (Om-sidan). Ren logik utan
 * Android-beroenden.
 *
 * Stödet är medvetet smalt: rubriker med # och ##, stycken, **fet** text och
 * länkar [text](adress). Allt annat visas som vanlig text. Texterna ligger i
 * appens assets och skrivs av projektet, så tolken behöver inte klara godtycklig Markdown.
 */

/** En bit text i ett stycke eller en rubrik. */
sealed interface MarkdownSpan {
    val text: String

    data class Plain(override val text: String, val bold: Boolean = false) : MarkdownSpan

    /** En länk. Bara http- och https-adresser blir länkar. */
    data class Link(override val text: String, val url: String) : MarkdownSpan
}

sealed interface MarkdownBlock {
    val spans: List<MarkdownSpan>

    /** Texten utan formatering, till exempel för skärmläsare och tester. */
    val plainText: String get() = spans.joinToString("") { it.text }

    /** [level] är 1 för # och 2 för ##. */
    data class Heading(val level: Int, override val spans: List<MarkdownSpan>) : MarkdownBlock

    data class Paragraph(override val spans: List<MarkdownSpan>) : MarkdownBlock
}

private val linkPattern = Regex("""\[([^\]]+)]\(([^)\s]+)\)""")
private val boldPattern = Regex("""\*\*(.+?)\*\*""")

/**
 * Tolkar [text]. Platshållaren {version} ersätts med [version] före tolkningen.
 * Tomma rader skiljer block. Rader som följer på varandra hör till samma stycke.
 */
fun parseMarkdown(text: String, version: String): List<MarkdownBlock> {
    val blocks = ArrayList<MarkdownBlock>()
    val paragraph = ArrayList<String>()
    fun flush() {
        if (paragraph.isNotEmpty()) blocks += MarkdownBlock.Paragraph(parseSpans(paragraph.joinToString(" ")))
        paragraph.clear()
    }
    for (raw in text.replace("{version}", version).lines()) {
        val line = raw.trim()
        when {
            line.isEmpty() -> flush()
            line.startsWith("## ") -> {
                flush()
                blocks += MarkdownBlock.Heading(2, parseSpans(line.removePrefix("## ").trim()))
            }
            line.startsWith("# ") -> {
                flush()
                blocks += MarkdownBlock.Heading(1, parseSpans(line.removePrefix("# ").trim()))
            }
            else -> paragraph += line
        }
    }
    flush()
    return blocks
}

/** Delar en rad i vanlig text, fet text och länkar. */
internal fun parseSpans(text: String): List<MarkdownSpan> {
    val spans = ArrayList<MarkdownSpan>()
    var position = 0
    for (match in linkPattern.findAll(text)) {
        val url = match.groupValues[2]
        // En adress som inte är en vanlig webbadress lämnas som den står, hellre än att bli en länk.
        if (!url.startsWith("https://") && !url.startsWith("http://")) continue
        spans += plainSpans(text.substring(position, match.range.first))
        spans += MarkdownSpan.Link(match.groupValues[1], url)
        position = match.range.last + 1
    }
    spans += plainSpans(text.substring(position))
    return spans
}

private fun plainSpans(text: String): List<MarkdownSpan> {
    val spans = ArrayList<MarkdownSpan>()
    var position = 0
    for (match in boldPattern.findAll(text)) {
        if (match.range.first > position) spans += MarkdownSpan.Plain(text.substring(position, match.range.first))
        spans += MarkdownSpan.Plain(match.groupValues[1], bold = true)
        position = match.range.last + 1
    }
    if (position < text.length) spans += MarkdownSpan.Plain(text.substring(position))
    return spans
}
