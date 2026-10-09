package se.c64.dmzreader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.c64.dmzreader.data.db.DownloadState
import se.c64.dmzreader.data.db.IssueLocalState
import se.c64.dmzreader.data.db.IssueUserState
import se.c64.dmzreader.data.search.IndexedIssue
import se.c64.dmzreader.data.search.IndexedPage
import se.c64.dmzreader.data.search.RankedRow
import se.c64.dmzreader.testing.issueEntity
import se.c64.dmzreader.ui.search.nextYearSelection

/** Gruppering, sortering, filter och markeringar för fulltextsökningens träffar. */
class FullTextResultsTest {
    private fun item(issueId: String, favorite: Boolean = false, downloaded: Boolean = false) = IssueWithState(
        issue = issueEntity(
            issueId,
            publicationId = issueId.substringBefore("-"),
            publicationTitle = if (issueId.startsWith("highscore")) "HighScore" else "Datormagazin",
        ),
        state = IssueUserState(issueId = issueId, isFavorite = favorite, updatedAt = 1),
        pageCount = 48,
        local = IssueLocalState(issueId = issueId, downloadState = if (downloaded) DownloadState.DOWNLOADED else DownloadState.NONE),
    )

    private val issues = listOf(
        item("datormagazin-1986-01"),
        item("datormagazin-1989-06", favorite = true),
        item("datormagazin-1993-03", downloaded = true),
        item("datormagazin-1995-14", favorite = true, downloaded = true),
        item("highscore-1993-17"),
    )

    // Rad-id → sida. Rad 9 hör till ett nummer som inte finns i arkivindexet.
    private val pages = listOf(
        IndexedPage(1, "datormagazin-1986-01", 4),
        IndexedPage(2, "datormagazin-1989-06", 46),
        IndexedPage(3, "datormagazin-1993-03", 12),
        IndexedPage(4, "datormagazin-1995-14", 26),
        IndexedPage(5, "datormagazin-1995-14", 27),
        IndexedPage(6, "datormagazin-1995-14", 28),
        IndexedPage(7, "datormagazin-1995-14", 3),
        IndexedPage(8, "highscore-1993-17", 6),
        IndexedPage(9, "datormagazin-1999-01", 3),
    ).associateBy { it.rowId }

    // Rangordningen från sökindexet: bäst först. Lägre poäng är bättre.
    private val ranked = listOf(
        RankedRow(8, -9.0), RankedRow(9, -8.5), RankedRow(5, -8.0), RankedRow(3, -7.0), RankedRow(4, -6.0),
        RankedRow(1, -5.0), RankedRow(7, -4.0), RankedRow(6, -3.0), RankedRow(2, -2.0),
    )

    private fun group(filters: SearchFilters = SearchFilters(), sort: SearchSort = SearchSort.RELEVANCE, outdated: Set<String> = emptySet()) =
        groupHits(ranked, pages, issues, filters, sort, outdated)

    private fun FullTextResults.ids() = groups.map { it.item.issue.issueId.replace("datormagazin-", "") }

    @Test
    fun `hits are grouped per issue and issues are ordered by their best page`() {
        val result = group()

        assertEquals(listOf("highscore-1993-17", "1995-14", "1993-03", "1986-01", "1989-06"), result.ids())
        assertEquals(8, result.totalPages)
        assertEquals(5, result.totalIssues)
        assertEquals("8 sidor i 5 nummer", hitSummaryText(result.totalPages, result.totalIssues))
        // Inom numret kommer den bästa sidan först.
        val issue = result.groups[1]
        assertEquals(listOf(27, 26, 3, 28), issue.pages.map { it.pageNumber })
        assertEquals(listOf(27, 26, 3), issue.top.map { it.pageNumber })
        assertEquals(-8.0, issue.bestScore, 0.0)
    }

    @Test
    fun `hits in issues missing from the archive index are hidden`() {
        val result = group()
        assertFalse(result.groups.any { it.item.issue.issueId == "datormagazin-1999-01" })
        assertEquals(8, result.totalPages) // nio rader i rangordningen, en dold
    }

    @Test
    fun `oldest first sorts on sortKey and pages in page order`() {
        val result = group(sort = SearchSort.OLDEST)

        assertEquals(listOf("1986-01", "1989-06", "1993-03", "highscore-1993-17", "1995-14"), result.ids())
        assertEquals(listOf(3, 26, 27, 28), result.groups.last().pages.map { it.pageNumber })
    }

    @Test
    fun `filters apply to the issue`() {
        assertEquals(listOf("1995-14", "1989-06"), group(SearchFilters(favoritesOnly = true)).ids())
        assertEquals(listOf("1995-14", "1993-03"), group(SearchFilters(downloadedOnly = true)).ids())
        assertEquals(listOf("1995-14"), group(SearchFilters(favoritesOnly = true, downloadedOnly = true)).ids())
        assertEquals(listOf("highscore-1993-17"), group(SearchFilters(publicationId = "highscore")).ids())
        assertEquals(
            listOf("highscore-1993-17", "1995-14", "1993-03"),
            group(SearchFilters(yearFrom = 1993, yearTo = 1995)).ids(),
        )
        assertEquals(listOf("1986-01", "1989-06"), group(SearchFilters(yearTo = 1989), SearchSort.OLDEST).ids())
        assertEquals(listOf("1993-03"), group(SearchFilters(publicationId = "datormagazin", yearFrom = 1993, yearTo = 1993)).ids())
        // Antalet sidor räknas efter filtren.
        assertEquals(5, group(SearchFilters(favoritesOnly = true)).totalPages)
        assertTrue(group(SearchFilters(yearFrom = 1996)).isEmpty)
    }

    @Test
    fun `year filter text`() {
        assertEquals("1993–1995", yearFilterText(SearchFilters(yearFrom = 1993, yearTo = 1995)))
        assertEquals("1993", yearFilterText(SearchFilters(yearFrom = 1993, yearTo = 1993)))
        assertEquals("från 1993", yearFilterText(SearchFilters(yearFrom = 1993)))
        assertEquals("till 1989", yearFilterText(SearchFilters(yearTo = 1989)))
        assertEquals("alla år", yearFilterText(SearchFilters()))
        assertTrue(SearchFilters(yearFrom = 1993).any)
        assertFalse(SearchFilters(yearFrom = 1993).listsIssues)
    }

    @Test
    fun `year picker selects one year, then a range, then starts over`() {
        val pick = ::nextYearSelection
        assertEquals(1993..1993, pick(null, 1993))
        assertEquals(1993..1995, pick(1993..1993, 1995))
        assertEquals(1989..1993, pick(1993..1993, 1989))
        assertEquals(1993..1993, pick(1993..1993, 1993))
        assertEquals(1990..1990, pick(1993..1995, 1990))
    }

    @Test
    fun `groups are shown a batch at a time`() {
        val many = (1..65).map { item("datormagazin-19%02d-%02d".format(86 + it % 10, 1 + it % 22 ).let { id -> id + if (it > 44) "b" else if (it > 22) "a" else "" }) }
            .distinctBy { it.issue.issueId }
        val manyPages = many.mapIndexed { index, issue -> IndexedPage(index + 1L, issue.issue.issueId, 1) }.associateBy { it.rowId }
        val manyRanked = manyPages.keys.map { RankedRow(it, -it.toDouble()) }
        val result = groupHits(manyRanked, manyPages, many, SearchFilters(), SearchSort.RELEVANCE)

        assertEquals(many.size, result.totalIssues)
        assertEquals(20, GROUP_PAGE_SIZE)
        assertEquals(GROUP_PAGE_SIZE, result.groups.take(GROUP_PAGE_SIZE).size)
        assertEquals(3, TOP_PAGES_PER_ISSUE)
    }

    @Test
    fun `hits in one issue come in page order`() {
        val hits = hitsInIssue(ranked, pages, "datormagazin-1995-14")
        assertEquals(listOf(3, 26, 27, 28), hits.map { it.pageNumber })
        assertEquals(listOf(7L, 4L, 5L, 6L), hits.map { it.rowId })
        assertTrue(hitsInIssue(ranked, pages, "datormagazin-1987-01").isEmpty())
    }

    @Test
    fun `hit navigation text`() {
        assertEquals("högtalare · träff 2 av 5", hitNavigationText("högtalare", 1, 5))
        assertEquals("\"Commodore 128\" · träff 1 av 1", hitNavigationText("\"Commodore 128\"", 0, 1))
        assertEquals("högtalare · inga träffar i numret", hitNavigationText("högtalare", 0, 0))
    }

    @Test
    fun `issues whose PDF differs from the search index are marked`() {
        val entities = listOf(
            issueEntity("datormagazin-1986-01").copy(ocrPdfSize = 100, ocrPdfEtag = "\"a\""),
            issueEntity("datormagazin-1989-06").copy(ocrPdfSize = 200, ocrPdfEtag = "\"b\""),
            issueEntity("datormagazin-1993-03").copy(ocrPdfSize = 300, ocrPdfEtag = "\"c\""),
            issueEntity("datormagazin-1995-14").copy(ocrPdfSize = null, ocrPdfEtag = null),
        )
        val indexed = listOf(
            IndexedIssue("datormagazin-1986-01", 28, 100, "\"a\""),          // samma fil
            IndexedIssue("datormagazin-1989-06", 48, 201, "\"b\""),          // annan storlek
            IndexedIssue("datormagazin-1993-03", 68, 300, "\"annan\""),      // annan ETag
            IndexedIssue("datormagazin-1995-14", 84, 400, "\"d\""),          // går inte att jämföra
            IndexedIssue("datormagazin-1999-01", 3, 1, "\"e\""),             // finns inte i arkivindexet
        )

        assertEquals(setOf("datormagazin-1989-06", "datormagazin-1993-03"), outdatedInIndex(indexed, entities))
        assertTrue(group(outdated = setOf("datormagazin-1993-03")).groups.single { it.indexOutdated }.item.issue.issueId == "datormagazin-1993-03")
        assertEquals("Sökindexet gäller en äldre version av filen", INDEX_OUTDATED_TEXT)
    }

    @Test
    fun `snippet is split at the markers`() {
        val open = "\u0002"
        val close = "\u0003"
        assertEquals(
            listOf(SnippetPart("…Nya ", false), SnippetPart("högtalare", true), SnippetPart(" är ett bra sätt…", false)),
            splitSnippet("…Nya ${open}högtalare$close är ett bra sätt…", open, close),
        )
        assertEquals(
            listOf(SnippetPart("Amiga", true), SnippetPart(" och ", false), SnippetPart("Amiga", true)),
            splitSnippet("${open}Amiga$close och ${open}Amiga$close", open, close),
        )
        assertEquals(listOf(SnippetPart("ingen träff", false)), splitSnippet("ingen träff", open, close))
        // Hakparenteser i tidningens text är vanlig text.
        assertEquals(listOf(SnippetPart("[RETURN] ", false), SnippetPart("basic", true)), splitSnippet("[RETURN] ${open}basic$close", open, close))
        assertTrue(splitSnippet("", open, close).isEmpty())
        assertEquals(listOf(SnippetPart("trasig", true)), splitSnippet("${open}trasig", open, close))
    }
}
