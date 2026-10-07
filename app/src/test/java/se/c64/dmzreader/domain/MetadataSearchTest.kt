package se.c64.dmzreader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.c64.dmzreader.data.db.Bookmark
import se.c64.dmzreader.data.db.DownloadState
import se.c64.dmzreader.data.db.IssueLocalState
import se.c64.dmzreader.data.db.IssueUserState
import se.c64.dmzreader.testing.issueEntity

class MetadataSearchTest {
    // --- Tolkningen av söktexten ---

    @Test
    fun `a four digit number is a year`() {
        assertEquals(MetadataQuery(year = 1989), parseMetadataQuery("1989"))
        assertEquals(MetadataQuery(year = 1989), parseMetadataQuery("  1989 "))
        assertEquals(MetadataQuery(year = 1989), parseMetadataQuery("år 1989"))
    }

    @Test
    fun `year and number in every way they are usually written`() {
        val expected = MetadataQuery(year = 1989, number = 12)
        for (text in listOf("1989 12", "1989/12", "89/12", "89 12", "1989 nr 12", "89/ 12", "1989, 12", "1989.12", " 89 / 12 ")) {
            assertEquals(text, expected, parseMetadataQuery(text))
        }
        assertEquals(MetadataQuery(year = 1986, number = 1), parseMetadataQuery("86/1"))
        assertEquals(MetadataQuery(year = 1995, number = 5), parseMetadataQuery("95/05"))
    }

    @Test
    fun `a small number alone is an issue number and a large one is a year`() {
        assertEquals(MetadataQuery(number = 12), parseMetadataQuery("12"))
        assertEquals(MetadataQuery(number = 12), parseMetadataQuery("nr 12"))
        assertEquals(MetadataQuery(number = 6), parseMetadataQuery("Nummer 6"))
        // Inget nummer är så högt: det är ett år.
        assertEquals(MetadataQuery(year = 1989), parseMetadataQuery("89"))
        assertEquals(MetadataQuery(year = 1995), parseMetadataQuery("95"))
    }

    @Test
    fun `other text is words, in lower case`() {
        assertEquals(MetadataQuery(words = listOf("highscore")), parseMetadataQuery("HighScore"))
        assertEquals(MetadataQuery(year = 1993, words = listOf("highscore")), parseMetadataQuery("highscore 1993"))
        assertEquals(MetadataQuery(year = 1993, number = 17, words = listOf("highscore")), parseMetadataQuery("HighScore 93/17"))
        assertEquals(MetadataQuery(words = listOf("joysticktest")), parseMetadataQuery("Joysticktest"))
        assertEquals(MetadataQuery(words = listOf("c64", "1541-ii")), parseMetadataQuery("C64 1541-II"))
        assertTrue(parseMetadataQuery("").isEmpty)
        assertTrue(parseMetadataQuery("  nr  ").isEmpty)
    }

    // --- Sökningen ---

    private fun item(issueId: String, title: String = "Datormagazin", favorite: Boolean = false, downloaded: Boolean = false) = IssueWithState(
        issue = issueEntity(issueId, publicationId = issueId.substringBefore("-"), publicationTitle = title),
        state = IssueUserState(issueId = issueId, isFavorite = favorite, updatedAt = 1),
        pageCount = 48,
        local = IssueLocalState(issueId = issueId, downloadState = if (downloaded) DownloadState.DOWNLOADED else DownloadState.NONE),
    )

    private val issues = listOf(
        item("datormagazin-1988-16-17"),
        item("datormagazin-1989-06", favorite = true),
        item("datormagazin-1989-12", downloaded = true),
        item("datormagazin-1989-16b"),
        item("datormagazin-1990-12", favorite = true, downloaded = true),
        item("highscore-1993-17", title = "HighScore"),
    )

    private fun bookmark(issueId: String, page: Int, title: String, note: String? = null, at: Long = page.toLong()) =
        Bookmark(issueId = issueId, pageNumber = page, title = title, note = note, createdAt = at, updatedAt = at)

    private val bookmarks = listOf(
        bookmark("datormagazin-1989-06", 7, "Joysticktest", "Kolla pris och betyg"),
        bookmark("datormagazin-1989-12", 3, "Sida 3"),
        bookmark("datormagazin-1990-12", 20, "Ledare", "Om joystickar och möss"),
        bookmark("datormagazin-1999-99", 4, "Joystick i ett saknat nummer"),
    )

    private fun search(text: String, filters: SearchFilters = SearchFilters()) =
        searchMetadata(parseMetadataQuery(text), filters, issues, bookmarks)

    private fun ids(text: String, filters: SearchFilters = SearchFilters()) = search(text, filters).issues.map { it.issue.issueId }

    @Test
    fun `a year finds every issue that year`() {
        assertEquals(listOf("datormagazin-1989-06", "datormagazin-1989-12", "datormagazin-1989-16b"), ids("1989"))
        assertEquals(ids("1989"), ids("89"))
        assertTrue(ids("1991").isEmpty())
        // Bara år: inga bokmärken listas, även om numren har bokmärken.
        assertTrue(search("1989").bookmarks.isEmpty())
    }

    @Test
    fun `year and number find the issue`() {
        assertEquals(listOf("datormagazin-1989-12"), ids("1989 12"))
        assertEquals(listOf("datormagazin-1989-12"), ids("89/12"))
        // Dubbelnummer hittas på båda sina nummer, och b-nummer på sitt.
        assertEquals(listOf("datormagazin-1988-16-17"), ids("88/17"))
        assertEquals(listOf("datormagazin-1988-16-17"), ids("1988 16"))
        assertEquals(listOf("datormagazin-1989-16b"), ids("89/16"))
        assertTrue(ids("89/13").isEmpty())
    }

    @Test
    fun `a number alone finds that number in every year`() {
        assertEquals(listOf("datormagazin-1989-12", "datormagazin-1990-12"), ids("nr 12"))
        assertEquals(listOf("datormagazin-1988-16-17", "highscore-1993-17"), ids("17"))
    }

    @Test
    fun `publication by name, also together with a year`() {
        assertEquals(listOf("highscore-1993-17"), ids("highscore"))
        assertEquals(listOf("highscore-1993-17"), ids("High"))
        assertEquals(5, ids("datormagazin").size)
        assertEquals(3, ids("dator 1989").size)
        assertTrue(ids("highscore 1989").isEmpty())
    }

    @Test
    fun `bookmarks are found by title and note and open their issue`() {
        val hits = search("joysticktest").bookmarks
        assertEquals(listOf(7), hits.map { it.bookmark.pageNumber })
        assertEquals("datormagazin-1989-06", hits.single().issue.issueId)
        // Del av ord, i titel eller anteckning, oavsett versaler. Nyast först.
        assertEquals(listOf(20, 7), search("JOYSTICK").bookmarks.map { it.bookmark.pageNumber })
        assertEquals(listOf(7), search("pris betyg").bookmarks.map { it.bookmark.pageNumber })
        assertTrue(search("pris saknas").bookmarks.isEmpty())
        // År begränsar till bokmärken i den årgången.
        assertEquals(listOf(20), search("joystick 1990").bookmarks.map { it.bookmark.pageNumber })
        // Ett bokmärke i ett nummer som saknas i arkivindexet går inte att öppna och är ingen träff.
        assertTrue(search("saknat").bookmarks.isEmpty())
        // Sökord som inte är en publikation ger inga nummer.
        assertTrue(search("joysticktest").issues.isEmpty())
    }

    @Test
    fun `quick filters narrow the search and list everything when the query is empty`() {
        val favorites = SearchFilters(favoritesOnly = true)
        val downloaded = SearchFilters(downloadedOnly = true)
        assertEquals(listOf("datormagazin-1989-06", "datormagazin-1990-12"), ids("", favorites))
        assertEquals(listOf("datormagazin-1989-12", "datormagazin-1990-12"), ids("", downloaded))
        assertEquals(listOf("datormagazin-1990-12"), ids("", SearchFilters(favoritesOnly = true, downloadedOnly = true)))
        assertEquals(listOf("datormagazin-1989-06"), ids("1989", favorites))
        assertEquals(listOf("datormagazin-1989-12"), ids("nr 12", downloaded.copy()).filter { it.contains("1989") })
        // Filtret gäller också bokmärkenas nummer.
        assertEquals(listOf(20), search("joystick", downloaded).bookmarks.map { it.bookmark.pageNumber })
    }

    @Test
    fun `an empty query without filters gives nothing`() {
        assertTrue(search("").isEmpty)
        assertTrue(search("   ").isEmpty)
        assertFalse(search("1989").isEmpty)
    }

    @Test
    fun `many hits are cut off and say so`() {
        val many = (1..22).flatMap { number -> (1986..1995).map { year -> item("datormagazin-$year-%02d".format(number)) } }
        val results = searchMetadata(parseMetadataQuery("datormagazin"), SearchFilters(), many, emptyList())
        assertEquals(MAX_ISSUE_HITS, results.issues.size)
        assertTrue(results.truncated)
        assertFalse(searchMetadata(parseMetadataQuery("1989"), SearchFilters(), many, emptyList()).truncated)
    }

    @Test
    fun `numbers of an issue id`() {
        assertEquals(listOf(6), issueNumbers(issueEntity("datormagazin-1989-06")))
        assertEquals(listOf(16, 17), issueNumbers(issueEntity("datormagazin-1988-16-17")))
        assertEquals(listOf(10), issueNumbers(issueEntity("datormagazin-1987-10b")))
    }
}
