package se.c64.dmzreader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.c64.dmzreader.data.backup.ImportSummary
import se.c64.dmzreader.ui.settings.backupDateText
import se.c64.dmzreader.ui.settings.importSummaryLines
import java.time.ZoneId

class SettingsTextTest {
    @Test
    fun `the date of the last export is written out in Swedish`() {
        assertEquals("7 oktober 2026 21:06", backupDateText(1_791_400_000_000, ZoneId.of("Europe/Stockholm")))
    }

    @Test
    fun `the summary before an import says what the file holds and what will change`() {
        val lines = importSummaryLines(
            ImportSummary(
                favorites = 2, bookmarks = 4, sessions = 9, issuesWithReadingData = 3,
                newIssues = 1, updatedIssues = 2, newBookmarks = 3, updatedBookmarks = 4, newSessions = 5,
            ),
        )
        assertTrue("2 favoritnummer" in lines)
        assertTrue("4 bokmärken" in lines)
        assertTrue("9 lässessioner" in lines)
        assertTrue("3 nummer med läsdata" in lines)
        assertEquals(
            "Importen lägger till 1 nya nummer och uppdaterar 2, lägger till 3 bokmärken och uppdaterar 4, " +
                "och lägger till 5 sessioner. Ingen läsdata tas bort.",
            lines.last(),
        )
    }

    @Test
    fun `the summary says so when everything is already on the phone`() {
        val lines = importSummaryLines(
            ImportSummary(
                favorites = 2, bookmarks = 4, sessions = 9, issuesWithReadingData = 3,
                newIssues = 0, updatedIssues = 0, newBookmarks = 0, newSessions = 0,
            ),
        )
        assertEquals("Allt i filen finns redan på telefonen. Ingenting ändras.", lines.last())
    }
}
