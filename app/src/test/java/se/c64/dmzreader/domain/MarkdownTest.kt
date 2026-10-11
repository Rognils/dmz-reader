package se.c64.dmzreader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.c64.dmzreader.BuildConfig
import se.c64.dmzreader.domain.MarkdownBlock.Heading
import se.c64.dmzreader.domain.MarkdownBlock.Paragraph
import se.c64.dmzreader.domain.MarkdownSpan.Link
import se.c64.dmzreader.domain.MarkdownSpan.Plain
import java.io.File

/** Den lilla Markdown-tolken bakom Om-sidan, och Om-texten i appens assets. */
class MarkdownTest {
    private fun parse(text: String) = parseMarkdown(text, "9.9.9")

    @Test
    fun `headings of two levels`() {
        assertEquals(
            listOf(Heading(1, listOf(Plain("Om DMZ Reader"))), Heading(2, listOf(Plain("Tack, Gustav!")))),
            parse("# Om DMZ Reader\n## Tack, Gustav!"),
        )
        // Utan mellanslag efter tecknet är det ingen rubrik, och tre tecken stöds inte.
        assertEquals(listOf(Paragraph(listOf(Plain("#inte rubrik")))), parse("#inte rubrik"))
        assertEquals(listOf(Paragraph(listOf(Plain("### tre")))), parse("### tre"))
    }

    @Test
    fun `blank lines separate paragraphs, and wrapped lines belong together`() {
        val blocks = parse("Första raden\nfortsätter här.\n\n\nAndra stycket.\n")
        assertEquals(listOf("Första raden fortsätter här.", "Andra stycket."), blocks.map { it.plainText })
        assertTrue(blocks.all { it is Paragraph })
        assertEquals(emptyList<MarkdownBlock>(), parse(""))
        assertEquals(emptyList<MarkdownBlock>(), parse("\n\n  \n"))
    }

    @Test
    fun `a heading ends the paragraph before it`() {
        val blocks = parse("Text\n## Rubrik\nMer text")
        assertEquals(listOf(Paragraph::class, Heading::class, Paragraph::class), blocks.map { it::class })
    }

    @Test
    fun `bold text`() {
        assertEquals(
            listOf(Plain("Det är "), Plain("gratis", bold = true), Plain(" och "), Plain("utan reklam", bold = true), Plain(".")),
            parse("Det är **gratis** och **utan reklam**.").single().spans,
        )
        // Stjärnor som inte stängs står kvar som de är.
        assertEquals(listOf(Plain("Inte **fet")), parse("Inte **fet").single().spans)
        assertEquals(listOf(Plain("hel", bold = true)), parse("**hel**").single().spans)
    }

    @Test
    fun links() {
        assertEquals(
            listOf(Link("dmzarkivet.se", "https://dmzarkivet.se")),
            parse("[dmzarkivet.se](https://dmzarkivet.se)").single().spans,
        )
        assertEquals(
            listOf(Plain("Se "), Link("arkivet", "https://dmzarkivet.se/x?a=1"), Plain(" och "), Link("butiken", "http://shop.datormagazin.se"), Plain(".")),
            parse("Se [arkivet](https://dmzarkivet.se/x?a=1) och [butiken](http://shop.datormagazin.se).").single().spans,
        )
        assertEquals("Se arkivet nu", parse("Se [arkivet](https://dmzarkivet.se) nu").single().plainText)
    }

    @Test
    fun `only web addresses become links`() {
        for (text in listOf("[klicka](javascript:alert(1))", "[fil](file:///data/x)", "[app](intent://x)", "[tom]()", "[x](mailto:a@b.se)")) {
            assertTrue(text, parse(text).single().spans.none { it is Link })
        }
        // Hakparenteser utan adress är vanlig text.
        assertEquals(listOf(Plain("[bara text]")), parse("[bara text]").single().spans)
    }

    @Test
    fun `bold and links in the same paragraph, and in headings`() {
        assertEquals(
            listOf(Plain("Stort", bold = true), Plain(" tack till "), Link("DMZArkivet", "https://dmzarkivet.se"), Plain("!")),
            parse("**Stort** tack till [DMZArkivet](https://dmzarkivet.se)!").single().spans,
        )
        assertEquals(
            Heading(2, listOf(Plain("Läs "), Plain("mer", bold = true))),
            parse("## Läs **mer**").single(),
        )
    }

    @Test
    fun `the version placeholder is replaced everywhere`() {
        assertEquals("Version 1.0.0-rc2", parseMarkdown("Version {version}", "1.0.0-rc2").single().plainText)
        val blocks = parseMarkdown("# App {version}\n\n**{version}** och [v{version}](https://example.org/{version})", "2.0")
        assertEquals("App 2.0", blocks[0].plainText)
        assertEquals(listOf(Plain("2.0", bold = true), Plain(" och "), Link("v2.0", "https://example.org/2.0")), blocks[1].spans)
        // Andra klamrar rörs inte.
        assertEquals("{annat}", parse("{annat}").single().plainText)
    }

    // --- Om-texten som följer med appen ---

    private val about = File("src/main/assets/about_sv.md").readText()
    private val blocks = parseMarkdown(about, BuildConfig.VERSION_NAME)

    @Test
    fun `the about text has its headings in order`() {
        assertEquals(
            listOf(
                1 to "Om DMZ Reader", 2 to "Tack, Gustav!", 2 to "Datormagazin idag",
                2 to "Material och upphovsrätt", 2 to "Om appen",
            ),
            blocks.filterIsInstance<Heading>().map { it.level to it.plainText },
        )
    }

    @Test
    fun `the about text links to the archive and the shop, and nowhere else`() {
        assertEquals(
            listOf(Link("dmzarkivet.se", "https://dmzarkivet.se"), Link("shop.datormagazin.se", "https://shop.datormagazin.se")),
            blocks.flatMap { it.spans }.filterIsInstance<Link>(),
        )
    }

    @Test
    fun `the about text says that the archive runs on a private connection`() {
        val headings = blocks.filterIsInstance<Heading>()
        val start = blocks.indexOf(headings.single { it.plainText == "Tack, Gustav!" })
        // Meningen står sist i stycket under rubriken, före länken till arkivet.
        val paragraph = blocks[start + 1] as Paragraph
        assertTrue(
            paragraph.plainText,
            paragraph.plainText.endsWith(
                "Stort tack! DMZArkivet körs på en privat uppkoppling, så ibland kan arkivet vara tillfälligt otillgängligt.",
            ),
        )
        assertEquals("dmzarkivet.se", (blocks[start + 2] as Paragraph).plainText)
    }

    @Test
    fun `the about text shows the version of the build`() {
        assertTrue(blocks.any { it is Paragraph && it.plainText == "Version ${BuildConfig.VERSION_NAME}" })
        assertFalse(blocks.any { "{version}" in it.plainText })
        assertTrue("{version}" in about)
    }

    @Test
    fun `the about text says what the app is and thanks the archive`() {
        val text = blocks.joinToString("\n") { it.plainText }
        assertTrue("ingen reklam, inga köp, inga användarkonton och ingen spårning eller analys" in text)
        assertTrue("DMZArkivet byggs och underhålls av Gustav, som lagt ner" in text)
        assertFalse("Almström" in text)
        assertTrue("DMZ Reader är ett fristående projekt och inte en officiell app från DMZArkivet." in text)
        assertTrue("under MIT-licensen, som bara gäller DMZ Readers källkod" in text)
        // Fem rubriker och nio stycken, varav två är en ensam länk.
        assertEquals(14, blocks.size)
    }
}
