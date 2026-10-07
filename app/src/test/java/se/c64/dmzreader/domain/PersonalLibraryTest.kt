package se.c64.dmzreader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.c64.dmzreader.data.db.Bookmark
import se.c64.dmzreader.data.db.IssueLocalState
import se.c64.dmzreader.data.db.IssueUserState
import se.c64.dmzreader.testing.issueEntity

class PersonalLibraryTest {
    private fun item(issueId: String, favorite: Boolean = false, local: IssueLocalState? = null) = IssueWithState(
        issue = issueEntity(issueId),
        state = IssueUserState(issueId = issueId, isFavorite = favorite, updatedAt = 1),
        pageCount = 48,
        local = local,
    )

    private fun bookmark(issueId: String, page: Int, createdAt: Long, id: Long = createdAt, title: String? = "Sida $page", size: Long? = null) =
        Bookmark(id = id, issueId = issueId, pageNumber = page, title = title, createdAt = createdAt, updatedAt = createdAt, pdfSize = size)

    private val issues = listOf(item("datormagazin-1989-06", favorite = true), item("datormagazin-1989-07"), item("datormagazin-1990-01", favorite = true))
    private val states = issues.mapNotNull { it.state }

    @Test
    fun `an empty library is empty`() {
        assertTrue(personalLibrary(issues.map { it.copy(state = null) }, emptyList(), emptyList()).isEmpty)
        assertTrue(PersonalLibrary().isEmpty)
    }

    @Test
    fun `favourites are the marked issues in catalogue order`() {
        val library = personalLibrary(issues, states, emptyList())
        assertEquals(listOf("datormagazin-1989-06", "datormagazin-1990-01"), library.favorites.map { it.issue.issueId })
        assertTrue(library.missingFavorites.isEmpty())
        assertFalse(library.isEmpty)
    }

    @Test
    fun `bookmarks are grouped per issue with the newest first`() {
        val bookmarks = listOf(
            bookmark("datormagazin-1989-06", 3, createdAt = 100),
            bookmark("datormagazin-1989-07", 9, createdAt = 500),
            bookmark("datormagazin-1989-06", 40, createdAt = 300),
            bookmark("datormagazin-1989-06", 12, createdAt = 300, id = 301),
        )
        val groups = personalLibrary(issues, states, bookmarks).bookmarkGroups
        // Numret med det nyaste bokmärket kommer först.
        assertEquals(listOf("datormagazin-1989-07", "datormagazin-1989-06"), groups.map { it.issueId })
        // Inom numret: nyast först, och vid samma tid det som skapades sist.
        assertEquals(listOf(12, 40, 3), groups[1].bookmarks.map { it.pageNumber })
        assertEquals("Datormagazin", groups[0].issue?.publicationTitle)
        assertFalse(groups[0].missingFromInventory)
        assertFalse(groups[0].fileChanged)
    }

    /** Planens D6: användardata för ett issueId som inte finns i det aktiva arkivindexet. */
    @Test
    fun `favourites and bookmarks for an issue missing from the inventory are shown as missing`() {
        val ghost = "datormagazin-1999-99"
        val allStates = states + IssueUserState(issueId = ghost, isFavorite = true, lastPage = 4, updatedAt = 9) +
            IssueUserState(issueId = "datormagazin-1998-01", isFavorite = false, updatedAt = 9)
        val bookmarks = listOf(
            bookmark(ghost, 4, createdAt = 9_000),
            bookmark(ghost, 2, createdAt = 8_000),
            bookmark("datormagazin-1989-06", 3, createdAt = 100),
        )

        val library = personalLibrary(issues, allStates, bookmarks)

        // Favoriten finns kvar, men bland de saknade, med sin läsdata.
        assertEquals(listOf(ghost), library.missingFavorites)
        assertEquals(listOf(MissingIssue(ghost, isFavorite = true, lastPage = 4, pagesRead = 0)), library.missingIssues)
        assertEquals("Favorit · läsdata sparad: sida 4", library.missingIssues.single().description)
        // Ett saknat nummer utan vare sig favoritmarkering eller läsdata visas inte.
        assertTrue(library.missingIssues.none { it.issueId == "datormagazin-1998-01" })
        // Läsdata utan favoritmarkering visas också.
        val readOnly = personalLibrary(
            issues, states + IssueUserState(issueId = "datormagazin-1997-03", lastPage = 12, seenPages = SeenPages.of(10, 11, 12).encode(), updatedAt = 1),
            emptyList(),
        ).missingIssues.single()
        assertEquals("Läsdata sparad: sida 12, 3 lästa sidor", readOnly.description)
        assertFalse(readOnly.isFavorite)
        assertEquals(listOf("datormagazin-1989-06", "datormagazin-1990-01"), library.favorites.map { it.issue.issueId })
        // Bokmärkena finns kvar, i en grupp utan nummer, sist i listan trots att de är nyast.
        val group = library.bookmarkGroups.last()
        assertEquals(ghost, group.issueId)
        assertNull(group.issue)
        assertTrue(group.missingFromInventory)
        assertEquals(listOf(4, 2), group.bookmarks.map { it.pageNumber })
        assertFalse(group.fileChanged)
        assertEquals(2, library.bookmarkGroups.size)

        // Kommer numret tillbaka i ett senare arkivindex kopplas datan ihop igen via issueId.
        val back = personalLibrary(issues + item(ghost, favorite = true), allStates, bookmarks)
        assertTrue(back.missingFavorites.isEmpty())
        assertTrue(ghost in back.favorites.map { it.issue.issueId })
        assertFalse(back.bookmarkGroups.first { it.issueId == ghost }.missingFromInventory)
    }

    @Test
    fun `a group is flagged when the archive file has changed`() {
        // issueEntity har ocrPdfSize 25 672 804. Bokmärket skapades när filen var en annan.
        val groups = personalLibrary(
            issues, states,
            listOf(bookmark("datormagazin-1989-06", 3, 100, size = 1_000), bookmark("datormagazin-1989-07", 3, 100, size = 25_672_804)),
        ).bookmarkGroups
        assertTrue(groups.first { it.issueId == "datormagazin-1989-06" }.fileChanged)
        assertFalse(groups.first { it.issueId == "datormagazin-1989-07" }.fileChanged)
    }

    @Test
    fun `bookmark labels and normalised input`() {
        assertEquals("Sida 7", bookmarkLabel(bookmark("a-1989-06", 7, 1)))
        assertEquals("Sida 7", bookmarkLabel(bookmark("a-1989-06", 7, 1, title = null)))
        assertEquals("Sida 7", bookmarkLabel(bookmark("a-1989-06", 7, 1, title = "  ")))
        assertEquals("Sida 7 · Ledare", bookmarkLabel(bookmark("a-1989-06", 7, 1, title = "Ledare")))
        assertEquals("Sida 7", normalizedBookmarkTitle("   ", 7))
        assertEquals("Ledare", normalizedBookmarkTitle("  Ledare ", 7))
        assertNull(normalizedBookmarkNote("  \n "))
        assertEquals("läs igen", normalizedBookmarkNote(" läs igen "))
    }
}
