package se.c64.dmzreader

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Om-sidan (1.0.0-rc2): nås från biblioteket och från inställningarna, visar texten ur
 * assets med appens version, och märker rubriker och länkar för skärmläsare.
 */
@RunWith(AndroidJUnit4::class)
class AboutDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun waitFor(condition: () -> Boolean) = rule.waitUntil(15_000, condition)
    private fun exists(text: String) = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    private fun described(text: String) = rule.onAllNodes(hasContentDescription(text)).fetchSemanticsNodes().isNotEmpty()
    private val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    private fun assertAboutShown() {
        waitFor { rule.onAllNodes(hasTestTag("about")).fetchSemanticsNodes().isNotEmpty() && exists("Tack, Gustav!") }
        rule.onNodeWithText("Om DMZ Reader").assertIsDisplayed()
        rule.onNodeWithText("Tack, Gustav!").performScrollTo().assertIsDisplayed()
        // Stycket under rubriken slutar med upplysningen om att arkivet kan vara nere.
        rule.onNodeWithText(
            "DMZArkivet körs på en privat uppkoppling, så ibland kan arkivet vara tillfälligt otillgängligt.",
            substring = true,
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aboutOpensFromTheLibrary() {
        waitFor { described("Om DMZ Reader") }
        // Info-ikonen ligger bredvid inställningarna i bibliotekets toppfält.
        assertTrue(described("Inställningar"))
        rule.onNodeWithContentDescription("Om DMZ Reader").performClick()
        assertAboutShown()
        // Som inställningarna visas sidan utan bottenfältet.
        rule.onNodeWithText("Statistik").assertDoesNotExist()
        rule.onNodeWithText("Bibliotek").assertDoesNotExist()

        // Tillbaka leder till biblioteket.
        rule.onNodeWithContentDescription("Tillbaka").performClick()
        waitFor { described("Inställningar") && exists("Datormagazin") }
    }

    @Test
    fun aboutOpensFromSettings() {
        waitFor { described("Inställningar") }
        rule.onNodeWithContentDescription("Inställningar").performClick()
        waitFor { rule.onAllNodes(hasTestTag("open-about")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("open-about").performScrollTo()
        rule.onNodeWithText("Om DMZ Reader").assertIsDisplayed()
        rule.onNodeWithTag("open-about").performClick()
        assertAboutShown()

        // Tillbaka leder till inställningarna.
        rule.onNodeWithContentDescription("Tillbaka").performClick()
        waitFor { exists("Inställningar") && rule.onAllNodes(hasTestTag("open-about")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aboutShowsTheVersionHeadingsAndLinks() {
        waitFor { described("Om DMZ Reader") }
        rule.onNodeWithContentDescription("Om DMZ Reader").performClick()
        assertAboutShown()

        // Platshållaren är ersatt med byggets version.
        rule.onNodeWithText("Version ${BuildConfig.VERSION_NAME}").performScrollTo().assertIsDisplayed()
        assertTrue(rule.onAllNodes(hasText("{version}", substring = true)).fetchSemanticsNodes().isEmpty())

        // Rubrikerna är märkta som rubriker, så att en skärmläsare kan hoppa mellan dem.
        val headings = rule.onAllNodes(isHeading).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text }
        assertEquals(
            listOf("Om DMZ Reader", "Tack, Gustav!", "Datormagazin idag", "Material och upphovsrätt", "Om appen"),
            headings,
        )

        // Länkarna är riktiga länkar med adress, inte bara färgad text.
        for ((label, url) in listOf("dmzarkivet.se" to "https://dmzarkivet.se", "shop.datormagazin.se" to "https://shop.datormagazin.se")) {
            val node = rule.onNodeWithText(label).performScrollTo().fetchSemanticsNode()
            val text = node.config[SemanticsProperties.Text].first()
            val links = text.getLinkAnnotations(0, text.length).map { (it.item as androidx.compose.ui.text.LinkAnnotation.Url).url }
            assertEquals(label, listOf(url), links)
        }
        // Två länkar står inne i löpande text: till ärendena och till källkoden.
        for ((fragment, url) in listOf(
            "kontakta projektet via GitHub" to "https://github.com/Rognils/dmz-reader/issues",
            "Källkoden finns på GitHub" to "https://github.com/Rognils/dmz-reader",
        )) {
            val node = rule.onNodeWithText(fragment, substring = true).performScrollTo().fetchSemanticsNode()
            val text = node.config[SemanticsProperties.Text].first()
            val links = text.getLinkAnnotations(0, text.length).map { (it.item as androidx.compose.ui.text.LinkAnnotation.Url).url }
            assertEquals(fragment, listOf(url), links)
        }
    }
}
