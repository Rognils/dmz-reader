package se.c64.dmzreader

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Navigering i fliken Bibliotek: Bibliotek → Årgång → Nummer → tillbaka. */
@RunWith(AndroidJUnit4::class)
class LibraryNavigationTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun waitForText(text: String) {
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForDescription(description: String) {
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Årgångsvyns rubrik: "X / Y lästa". Antalet lästa beror på vad som är läst på telefonen. */
    private fun waitForYear(issueCount: Int) {
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasText("/ $issueCount lästa", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun pressBack() {
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun openDatormagazin() {
        waitForText("Datormagazin")
        rule.onNodeWithText("Datormagazin").performClick()
        waitForText("171 nummer")
    }

    @Test
    fun libraryToYearToIssueAndBack() {
        openDatormagazin()
        rule.onNodeWithText("Bibliotek").assertIsDisplayed()

        // Bibliotek → Årgång
        rule.onNodeWithContentDescription("Datormagazin 1989, 19 nummer", substring = true).performClick()
        waitForYear(19)
        rule.onNodeWithText("Bibliotek").assertIsDisplayed()

        // Årgång → Nummer. Omslaget har en beskrivning för skärmläsare.
        waitForDescription("Datormagazin 1989 Nr 6")
        rule.onNodeWithContentDescription("Datormagazin 1989 Nr 6").performClick()
        waitForText("Läs")
        rule.onNodeWithText("Läs").assertIsEnabled()
        rule.onNodeWithText("DATORMAGAZIN 1989").assertIsDisplayed()
        // "≈ 48 sidor" från katalogen, eller det uppmätta "48 sidor" om numret har öppnats på telefonen.
        rule.onNodeWithText("48 sidor", substring = true).assertIsDisplayed()
        rule.onNodeWithText("25,7 MB").assertIsDisplayed()
        rule.onNodeWithText("Bibliotek").assertIsDisplayed()

        // Tillbaka till årgången, och sedan till biblioteket.
        pressBack()
        waitForYear(19)
        rule.onNodeWithContentDescription("Datormagazin 1989 Nr 6").assertIsDisplayed()
        pressBack()
        waitForText("171 nummer")
        rule.onNodeWithText("Årgångar").assertIsDisplayed()
    }

    @Test
    fun scrollPositionInYearIsKeptWhenReturningFromIssue() {
        openDatormagazin()
        rule.onNodeWithContentDescription("Datormagazin 1993, 22 nummer", substring = true).performClick()
        waitForYear(22)

        // Rulla ned till sista numret, som inte syns från början.
        rule.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasContentDescription("Datormagazin 1993 Nr 22"))
        rule.onNodeWithContentDescription("Datormagazin 1993 Nr 22").assertIsDisplayed()
        rule.onNodeWithContentDescription("Datormagazin 1993 Nr 22").performClick()
        waitForText("Läs")

        pressBack()
        // Utan bevarad position hade rutnätet börjat om från början. Då hade rubriken
        // synts och Nr 22 inte funnits med. Nu ska det vara tvärtom.
        waitForDescription("Datormagazin 1993 Nr 22")
        rule.onNodeWithContentDescription("Datormagazin 1993 Nr 22").assertIsDisplayed()
        rule.onNodeWithText("/ 22 lästa", substring = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("Datormagazin 1993 Nr 1").assertDoesNotExist()
    }

    @Test
    fun supplementLinksToMainIssueAndBack() {
        waitForText("HighScore")
        rule.onNodeWithText("HighScore").performClick()
        waitForText("14 nummer")
        rule.onNodeWithContentDescription("HighScore 1993, 6 nummer", substring = true).performClick()
        waitForDescription("HighScore 1993 Nr 17")
        rule.onNodeWithContentDescription("HighScore 1993 Nr 17").performClick()

        waitForText("Bilaga till Datormagazin 1993 Nr 17")
        rule.onNodeWithText("Bilaga till Datormagazin 1993 Nr 17").performClick()
        waitForText("Med bilagan HighScore Nr 17")
        rule.onNodeWithText("DATORMAGAZIN 1993").assertIsDisplayed()

        pressBack()
        waitForText("Bilaga till Datormagazin 1993 Nr 17")

        // Lämna valet som det var för nästa test och för användaren.
        pressBack()
        pressBack()
        waitForText("Datormagazin")
        rule.onNodeWithText("Datormagazin").performClick()
        waitForText("171 nummer")
    }

    @Test
    fun settingsAndDiagnosticsHideTheBottomBar() {
        openDatormagazin()
        rule.onNodeWithContentDescription("Inställningar").performClick()
        waitForText("Diagnostik")
        rule.onNodeWithText("Bibliotek").assertDoesNotExist()
        rule.onNodeWithText("Statistik").assertDoesNotExist()

        rule.onNodeWithText("Diagnostik").performClick()
        waitForText("ARKIVINDEX")
        rule.onNodeWithText("Bibliotek").assertDoesNotExist()

        pressBack()
        pressBack()
        waitForText("171 nummer")
        rule.onNodeWithText("Bibliotek").assertIsDisplayed()
    }
}
