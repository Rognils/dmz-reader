package se.c64.dmzreader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.c64.dmzreader.data.db.DownloadState
import se.c64.dmzreader.data.db.IssueLocalState
import se.c64.dmzreader.data.db.IssueUserState
import se.c64.dmzreader.data.db.ReadStatusOverride
import se.c64.dmzreader.testing.issueEntity

class ReadingStatusTest {
    private fun state(
        vararg pages: Int,
        override: ReadStatusOverride? = null,
        lastPage: Int? = null,
        lastOpenedAt: Long? = null,
        issueId: String = "datormagazin-1986-01",
    ) = IssueUserState(
        issueId = issueId,
        seenPages = SeenPages.of(*pages).encode(),
        maxPageReached = SeenPages.of(*pages).maxPage,
        readStatusOverride = override,
        lastPage = lastPage,
        lastOpenedAt = lastOpenedAt,
        updatedAt = 1,
    )

    @Test
    fun `no state and no pages is unread`() {
        assertEquals(ReadStatus.UNREAD, readingProgress(null, 28).status)
        val opened = IssueUserState(issueId = "x", lastPage = 1, lastOpenedAt = 5, updatedAt = 5)
        assertEquals(ReadStatus.UNREAD, readingProgress(opened, 28).status)
        assertEquals(0, readingProgress(opened, 28).percent)
    }

    @Test
    fun `progress counts read pages and not how far the reader has jumped`() {
        // Sex lästa sidor och ett hopp till sida 25: sju sidor av 28, inte 25 av 28.
        val progress = readingProgress(state(1, 2, 3, 4, 5, 6, 25), 28)
        assertEquals(7, progress.pagesRead)
        assertEquals(25, progress.percent)
        assertEquals(0.25f, progress.fraction!!, 0.0001f)
        assertEquals(ReadStatus.STARTED, progress.status)
    }

    @Test
    fun `ninety percent of the pages is read`() {
        val pages = (1..28).toList()
        assertEquals(ReadStatus.STARTED, readingProgress(state(*pages.take(25).toIntArray()), 28).status)
        // 26 av 28 är 92,9 %.
        assertEquals(ReadStatus.READ, readingProgress(state(*pages.take(26).toIntArray()), 28).status)
        // Exakt 90 %: 9 av 10.
        assertEquals(ReadStatus.READ, readingProgress(state(*(1..9).toList().toIntArray()), 10).status)
        assertEquals(ReadStatus.STARTED, readingProgress(state(*(1..8).toList().toIntArray()), 10).status)
    }

    @Test
    fun `percent never shows zero or a hundred too early`() {
        assertEquals(1, readingProgress(state(1), 400).percent)
        assertEquals(99, readingProgress(state(*(1..399).toList().toIntArray()), 400).percent)
        assertEquals(100, readingProgress(state(*(1..400).toList().toIntArray()), 400).percent)
    }

    @Test
    fun `unknown page count gives started without percent`() {
        val progress = readingProgress(state(*(1..60).toList().toIntArray()), null)
        assertEquals(ReadStatus.STARTED, progress.status)
        assertNull(progress.percent)
        assertNull(progress.fraction)
        assertEquals(60, progress.pagesRead)
        assertEquals("Påbörjad", readStatusText(progress))
    }

    @Test
    fun `pages beyond the page count are not counted`() {
        assertEquals(2, readingProgress(state(1, 2, 40), 28).pagesRead)
    }

    @Test
    fun `a manual mark decides the status but not the progress`() {
        val read = readingProgress(state(1, 2, override = ReadStatusOverride.READ), 28)
        assertEquals(ReadStatus.READ, read.status)
        assertEquals(ReadStatus.STARTED, read.automaticStatus)
        assertEquals(2, read.pagesRead)

        val unread = readingProgress(state(*(1..28).toList().toIntArray(), override = ReadStatusOverride.UNREAD), 28)
        assertEquals(ReadStatus.UNREAD, unread.status)
        assertEquals(ReadStatus.READ, unread.automaticStatus)
        assertEquals(100, unread.percent)
    }

    @Test
    fun `status text`() {
        assertEquals("Oläst", readStatusText(readingProgress(null, 28)))
        assertEquals("Påbörjad · 25 %", readStatusText(readingProgress(state(1, 2, 3, 4, 5, 6, 25), 28)))
        assertEquals("Läst · 100 %", readStatusText(readingProgress(state(*(1..28).toList().toIntArray()), 28)))
        assertEquals(
            "Oläst · 25 % (markerad)",
            readStatusText(readingProgress(state(1, 2, 3, 4, 5, 6, 25, override = ReadStatusOverride.UNREAD), 28)),
        )
        assertEquals("Läst (markerad)", readStatusText(readingProgress(state(override = ReadStatusOverride.READ), 28)))
    }

    @Test
    fun `reading pages updates seen pages and the highest page`() {
        val start = IssueUserState(issueId = "x", isFavorite = true, lastPage = 3, updatedAt = 1)
        val next = start.withPagesRead(listOf(6, 7), pageCount = 28, now = 100, clearUnread = false)
        assertEquals(SeenPages.of(6, 7), SeenPages.decode(next.seenPages))
        assertEquals(7, next.maxPageReached)
        // Läspositionen och favoritmarkeringen rörs inte.
        assertEquals(3, next.lastPage)
        assertTrue(next.isFavorite)
        assertNull(next.completedAt)
    }

    @Test
    fun `jumping back does not lower the progress`() {
        val far = state(20, 21).withPagesRead(listOf(2), 28, now = 1, clearUnread = false)
        assertEquals(21, far.maxPageReached)
        assertEquals(3, readingProgress(far, 28).pagesRead)
    }

    @Test
    fun `an older highest page without seen pages is never lowered`() {
        val legacy = IssueUserState(issueId = "x", maxPageReached = 30, updatedAt = 1)
        assertEquals(30, legacy.withPagesRead(listOf(4), 48, now = 2, clearUnread = false).maxPageReached)
    }

    @Test
    fun `completedAt is set the first time the issue becomes read`() {
        val almost = state(*(1..25).toList().toIntArray())
        val done = almost.withPagesRead(listOf(26), 28, now = 500, clearUnread = false)
        assertEquals(500L, done.completedAt)
        // Det ändras inte av att fler sidor läses.
        assertEquals(500L, done.withPagesRead(listOf(27, 28), 28, now = 900, clearUnread = false).completedAt)
    }

    @Test
    fun `marking as read sets completedAt but touches nothing else`() {
        val before = state(1, 2, lastPage = 2)
        val marked = before.withOverride(ReadStatusOverride.READ, 28, now = 700)
        assertEquals(ReadStatusOverride.READ, marked.readStatusOverride)
        assertEquals(700L, marked.completedAt)
        assertEquals(before.seenPages, marked.seenPages)
        assertEquals(before.lastPage, marked.lastPage)
        assertEquals(before.maxPageReached, marked.maxPageReached)
    }

    @Test
    fun `marking as unread keeps the reading history`() {
        val before = state(*(1..28).toList().toIntArray(), lastPage = 28).copy(completedAt = 300)
        val marked = before.withOverride(ReadStatusOverride.UNREAD, 28, now = 700)
        assertEquals(ReadStatus.UNREAD, readingProgress(marked, 28).status)
        assertEquals(before.seenPages, marked.seenPages)
        assertEquals(28, marked.lastPage)
        assertEquals(28, marked.maxPageReached)
        assertEquals(300L, marked.completedAt)

        // Återställ: den automatiska statusen gäller igen.
        val reset = marked.withOverride(null, 28, now = 800)
        assertNull(reset.readStatusOverride)
        assertEquals(ReadStatus.READ, readingProgress(reset, 28).status)
    }

    @Test
    fun `unread is cleared when a new page is read and only then`() {
        val unread = state(1, 2, override = ReadStatusOverride.UNREAD)
        val same = unread.withPagesRead(listOf(2), 28, now = 5, clearUnread = false)
        assertEquals(ReadStatusOverride.UNREAD, same.readStatusOverride)
        val moved = unread.withPagesRead(listOf(3), 28, now = 5, clearUnread = true)
        assertNull(moved.readStatusOverride)
        assertEquals(ReadStatus.STARTED, readingProgress(moved, 28).status)
    }

    @Test
    fun `a mark as read is not cleared by reading`() {
        val read = state(1, override = ReadStatusOverride.READ)
        assertEquals(
            ReadStatusOverride.READ,
            read.withPagesRead(listOf(2), 28, now = 5, clearUnread = true).readStatusOverride,
        )
    }

    @Test
    fun `opening sets the first time once and the last time always`() {
        val first = IssueUserState(issueId = "x", updatedAt = 1).opened(100)
        assertEquals(100L, first.firstOpenedAt)
        assertEquals(100L, first.lastOpenedAt)
        val again = first.opened(900)
        assertEquals(100L, again.firstOpenedAt)
        assertEquals(900L, again.lastOpenedAt)
    }

    // --- Biblioteket ---

    private fun item(issueId: String, state: IssueUserState?, pageCount: Int? = 28) =
        IssueWithState(issueEntity(issueId), state, pageCount)

    @Test
    fun `continue reading is the most recently opened started issue`() {
        val items = listOf(
            item("datormagazin-1986-01", state(1, 2, lastPage = 2, lastOpenedAt = 100, issueId = "datormagazin-1986-01")),
            item("datormagazin-1986-02", state(1, lastPage = 1, lastOpenedAt = 300, issueId = "datormagazin-1986-02")),
            // Markerat som oläst: visas inte, trots att det öppnades senast.
            item(
                "datormagazin-1986-03",
                state(1, lastPage = 1, lastOpenedAt = 900, override = ReadStatusOverride.UNREAD, issueId = "datormagazin-1986-03"),
            ),
            // Läst: visas inte heller.
            item(
                "datormagazin-1986-04",
                state(*(1..28).toList().toIntArray(), lastPage = 28, lastOpenedAt = 800, issueId = "datormagazin-1986-04"),
            ),
            item("datormagazin-1987-01", null),
        )
        assertEquals("datormagazin-1986-02", continueReadingIssue(items)?.issue?.issueId)
        assertNull(continueReadingIssue(items.takeLast(1)))

        // Senast lästa: senast öppnade först, utan numret som visas under Fortsätt läsa.
        assertEquals(
            listOf("datormagazin-1986-03", "datormagazin-1986-04", "datormagazin-1986-01"),
            recentlyReadIssues(items, "datormagazin-1986-02").map { it.issue.issueId },
        )
    }

    @Test
    fun `read issues are counted per year`() {
        val all = (1..28).toList().toIntArray()
        val items = listOf(
            item("datormagazin-1986-01", state(*all)),
            item("datormagazin-1986-02", state(1, override = ReadStatusOverride.READ)),
            item("datormagazin-1986-03", state(1)),
            item("datormagazin-1987-01", state(*all, override = ReadStatusOverride.UNREAD)),
        )
        assertEquals(mapOf(("datormagazin" to 1986) to 2), readCountsByYear(items))
    }

    @Test
    fun `year filters`() {
        assertEquals(listOf("Alla", "Olästa", "Påbörjade", "Lästa", "Offline"), YearFilter.entries.map { it.label })
        val unread = item("datormagazin-1986-01", null)
        val started = item("datormagazin-1986-02", state(1))
        val read = item("datormagazin-1986-03", state(1, override = ReadStatusOverride.READ))
        val downloaded = item("datormagazin-1986-04", null).copy(
            local = IssueLocalState(issueId = "datormagazin-1986-04", downloadState = DownloadState.DOWNLOADED),
        )
        val queued = item("datormagazin-1987-01", null).copy(
            local = IssueLocalState(issueId = "datormagazin-1987-01", downloadState = DownloadState.QUEUED),
        )
        val all = listOf(unread, started, read, downloaded, queued)
        fun ids(filter: YearFilter) = all.filter { filter.matches(it) }.map { it.issue.issueId.takeLast(7) }
        assertEquals(5, ids(YearFilter.ALL).size)
        assertEquals(listOf("1986-01", "1986-04", "1987-01"), ids(YearFilter.UNREAD))
        assertEquals(listOf("1986-02"), ids(YearFilter.STARTED))
        assertEquals(listOf("1986-03"), ids(YearFilter.READ))
        // Bara färdigt nedladdade nummer är offline, inte köade.
        assertEquals(listOf("1986-04"), ids(YearFilter.OFFLINE))
    }

    @Test
    fun `downloaded issues are listed with the latest first`() {
        fun downloaded(issueId: String, at: Long?, state: DownloadState = DownloadState.DOWNLOADED) =
            item(issueId, null).copy(local = IssueLocalState(issueId = issueId, downloadState = state, downloadedAt = at))
        val items = listOf(
            downloaded("datormagazin-1986-01", 100),
            downloaded("datormagazin-1986-02", 900),
            downloaded("datormagazin-1986-03", 500, DownloadState.DOWNLOADING),
            item("datormagazin-1986-04", null),
        )
        assertEquals(
            listOf("datormagazin-1986-02", "datormagazin-1986-01"),
            downloadedIssues(items).map { it.issue.issueId },
        )
    }

    @Test
    fun `text for read pages on the continue card`() {
        assertEquals("7 av 28 lästa · 25 %", readPagesText(readingProgress(state(1, 2, 3, 4, 5, 6, 25), 28)))
        assertEquals("0 av 28 lästa · 0 %", readPagesText(readingProgress(null, 28)))
        assertNull(readPagesText(readingProgress(state(1), null)))
    }

    @Test
    fun `effective page count prefers the catalogue`() {
        assertEquals(28, effectivePageCount(28, 30))
        assertEquals(30, effectivePageCount(null, 30))
        assertNull(effectivePageCount(null, null))
    }
}
