package se.c64.dmzreader.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import se.c64.dmzreader.data.db.Bookmark
import se.c64.dmzreader.data.db.IssueUserState
import se.c64.dmzreader.data.db.ReadStatusOverride
import se.c64.dmzreader.data.db.ReadingSession
import se.c64.dmzreader.domain.SeenPages

class BackupMergeTest {
    private val id = "datormagazin-1989-06"

    private fun state(
        updatedAt: Long,
        vararg pages: Int,
        issueId: String = id,
        favorite: Boolean = false,
        lastPage: Int? = null,
        override: ReadStatusOverride? = null,
        first: Long? = null,
        last: Long? = null,
        completed: Long? = null,
        max: Int? = SeenPages.of(*pages).maxPage,
    ) = IssueUserState(
        issueId = issueId, isFavorite = favorite, lastPage = lastPage, maxPageReached = max,
        seenPages = SeenPages.of(*pages).encode(), firstOpenedAt = first, lastOpenedAt = last,
        completedAt = completed, readStatusOverride = override, updatedAt = updatedAt,
    )

    private fun bookmark(page: Int, title: String? = "Sida $page", issueId: String = id, note: String? = null, at: Long = 10) =
        Bookmark(issueId = issueId, pageNumber = page, title = title, note = note, createdAt = at, updatedAt = at)

    private fun session(startedAt: Long, issueId: String = id) = ReadingSession(
        issueId = issueId, startedAt = startedAt, endedAt = startedAt + 60_000, startPage = 1, endPage = 5,
        pagesViewed = 5, durationSeconds = 60,
    )

    private fun backup(
        states: List<IssueUserState> = emptyList(),
        bookmarks: List<Bookmark> = emptyList(),
        sessions: List<ReadingSession> = emptyList(),
    ) = BackupFile(
        backupFormat = BACKUP_FORMAT, exportedAt = "2026-10-07T12:00:00Z", appVersion = "0.1.0",
        issueUserStates = states.map { it.toBackup() }, bookmarks = bookmarks.map { it.toBackup() },
        readingSessions = sessions.map { it.toBackup() },
    )

    private val empty = UserData(emptyList(), emptyList(), emptyList())

    // --- IssueUserState ---

    @Test
    fun `the newest row wins for last page, manual status and favourite`() {
        val local = state(100, 1, 2, favorite = true, lastPage = 2, override = ReadStatusOverride.UNREAD)
        val incoming = state(200, 3, favorite = false, lastPage = 40, override = ReadStatusOverride.READ)

        val merged = mergeUserState(local, incoming)
        assertEquals(40, merged.lastPage)
        assertEquals(ReadStatusOverride.READ, merged.readStatusOverride)
        assertFalse(merged.isFavorite)
        assertEquals(200L, merged.updatedAt)

        // Åt andra hållet: telefonens rad är nyast och behåller sina värden.
        val other = mergeUserState(incoming, local)
        assertEquals(40, other.lastPage)
        assertEquals(ReadStatusOverride.READ, other.readStatusOverride)
        assertFalse(other.isFavorite)
    }

    @Test
    fun `an older backup does not overwrite newer choices but its reading history is added`() {
        val local = state(900, 10, 11, favorite = true, lastPage = 11, override = null)
        val incoming = state(100, 1, 2, 3, favorite = false, lastPage = 3, override = ReadStatusOverride.UNREAD)
        val merged = mergeUserState(local, incoming)
        assertEquals(11, merged.lastPage)
        assertNull(merged.readStatusOverride)
        assertTrue(merged.isFavorite)
        assertEquals(SeenPages.of(1, 2, 3, 10, 11), SeenPages.decode(merged.seenPages))
        assertEquals(900L, merged.updatedAt)
    }

    @Test
    fun `equal timestamps keep the phone's values`() {
        val local = state(500, favorite = true, lastPage = 7)
        val incoming = state(500, favorite = false, lastPage = 9)
        val merged = mergeUserState(local, incoming)
        assertTrue(merged.isFavorite)
        assertEquals(7, merged.lastPage)
    }

    @Test
    fun `seen pages are merged with or and the highest page is recomputed`() {
        val merged = mergeUserState(state(1, 1, 2, 3, 30), state(2, 3, 4, 9))
        assertEquals(SeenPages.of(1, 2, 3, 4, 9, 30), SeenPages.decode(merged.seenPages))
        assertEquals(30, merged.maxPageReached)
        // Åt andra hållet blir mängden densamma.
        assertEquals(merged.seenPages, mergeUserState(state(2, 3, 4, 9), state(1, 1, 2, 3, 30)).seenPages)
    }

    @Test
    fun `an import never reduces the seen pages`() {
        val local = state(100, *(1..48).toList().toIntArray())
        // En nyare backup med mycket mindre läst, eller ingenting alls.
        for (incoming in listOf(state(900, 1), state(900))) {
            val merged = mergeUserState(local, incoming)
            assertEquals(48, SeenPages.decode(merged.seenPages).count)
            assertEquals(48, merged.maxPageReached)
        }
    }

    @Test
    fun `an old highest page without seen pages is kept`() {
        assertEquals(30, mergeUserState(state(1, max = 30), state(2, 4)).maxPageReached)
        assertEquals(30, mergeUserState(state(1, 4), state(2, max = 30)).maxPageReached)
        assertEquals(30, mergeUserState(null, state(2, 4, max = 30)).maxPageReached)
    }

    @Test
    fun `first opened is the earliest, last opened and completed the latest`() {
        val merged = mergeUserState(
            state(1, first = 500, last = 600, completed = 700),
            state(2, first = 100, last = 900, completed = 650),
        )
        assertEquals(100L, merged.firstOpenedAt)
        assertEquals(900L, merged.lastOpenedAt)
        assertEquals(700L, merged.completedAt)
        // Saknas ett värde på ena sidan gäller det andra.
        val oneSided = mergeUserState(state(1), state(2, first = 100, last = 900, completed = 650))
        assertEquals(100L, oneSided.firstOpenedAt)
        assertEquals(900L, oneSided.lastOpenedAt)
        assertEquals(650L, oneSided.completedAt)
        assertNull(mergeUserState(state(1), state(2)).completedAt)
    }

    @Test
    fun `a row that only exists in the backup is taken as it is`() {
        val incoming = state(200, 1, 2, favorite = true, lastPage = 2, override = ReadStatusOverride.READ, first = 5, last = 6, completed = 7)
        assertEquals(incoming, mergeUserState(null, incoming))
    }

    // --- Planen ---

    @Test
    fun `plan separates new, updated and unchanged rows`() {
        val current = UserData(
            userStates = listOf(state(100, 1, issueId = "a"), state(100, 1, issueId = "b")),
            bookmarks = emptyList(), sessions = emptyList(),
        )
        val plan = planImport(
            current,
            backup(states = listOf(state(100, 1, issueId = "a"), state(200, 1, 2, issueId = "b", favorite = true), state(50, 9, issueId = "c", lastPage = 9))),
        )
        assertEquals(listOf("c"), plan.userStatesToInsert.map { it.issueId })
        assertEquals(listOf("b"), plan.userStatesToUpdate.map { it.issueId })
        assertEquals(1, plan.summary.newIssues)
        assertEquals(1, plan.summary.updatedIssues)
        assertEquals(1, plan.summary.favorites)
        assertEquals(3, plan.summary.issuesWithReadingData)
        assertFalse(plan.summary.nothingToDo)
    }

    @Test
    fun `duplicate bookmarks are skipped`() {
        val current = UserData(emptyList(), listOf(bookmark(14, "Annons"), bookmark(20, null)), emptyList())
        val plan = planImport(
            current,
            backup(
                bookmarks = listOf(
                    bookmark(14, "Annons", note = "annan anteckning"), // dubblett: samma nummer, sida och titel
                    bookmark(14, "Test"), // samma sida, annan titel: nytt
                    bookmark(20, null), // dubblett utan titel
                    bookmark(20, "Sida 20"), // nytt
                    bookmark(14, "Annons", issueId = "datormagazin-1989-07"), // annat nummer: nytt
                    bookmark(14, "Test"), // dubblett inom filen
                ),
            ),
        )
        assertEquals(
            listOf("$id/14/Test", "$id/20/Sida 20", "datormagazin-1989-07/14/Annons"),
            plan.bookmarksToInsert.map { "${it.issueId}/${it.pageNumber}/${it.title}" },
        )
        assertEquals(6, plan.summary.bookmarks)
        assertEquals(3, plan.summary.newBookmarks)
        // Nya bokmärken får sitt id av databasen.
        assertTrue(plan.bookmarksToInsert.all { it.id == 0L })
    }

    @Test
    fun `duplicate sessions are skipped`() {
        val current = UserData(emptyList(), emptyList(), listOf(session(1_000)))
        val plan = planImport(
            current,
            backup(sessions = listOf(session(1_000), session(2_000), session(1_000, issueId = "annat"), session(2_000))),
        )
        assertEquals(listOf(2_000L, 1_000L), plan.sessionsToInsert.map { it.startedAt })
        assertEquals(listOf(id, "annat"), plan.sessionsToInsert.map { it.issueId })
        assertEquals(4, plan.summary.sessions)
        assertEquals(2, plan.summary.newSessions)
    }

    @Test
    fun `importing the same backup twice changes nothing the second time`() {
        val file = backup(
            states = listOf(state(200, 1, 2, 3, favorite = true, lastPage = 3, first = 10, last = 20), state(300, issueId = "b", favorite = true)),
            bookmarks = listOf(bookmark(3, "Sida 3"), bookmark(7, "Ledare", note = "läs igen")),
            sessions = listOf(session(1_000), session(5_000)),
        )
        // Telefonen har redan egen data.
        val start = UserData(listOf(state(100, 9, lastPage = 9)), listOf(bookmark(9)), listOf(session(9_000)))

        val first = planImport(start, file)
        assertFalse(first.summary.nothingToDo)
        val afterFirst = start.after(first)

        val second = planImport(afterFirst, file)
        assertTrue(second.summary.nothingToDo)
        assertTrue(second.userStatesToInsert.isEmpty())
        assertTrue(second.userStatesToUpdate.isEmpty())
        assertTrue(second.bookmarksToInsert.isEmpty())
        assertTrue(second.sessionsToInsert.isEmpty())
        assertEquals(afterFirst, afterFirst.after(second))
        assertEquals(3, afterFirst.bookmarks.size)
        assertEquals(3, afterFirst.sessions.size)
        assertEquals(2, afterFirst.userStates.size)
    }

    @Test
    fun `an import never removes anything`() {
        val start = UserData(
            userStates = listOf(state(900, 1, 2, 3, favorite = true, lastPage = 3), state(900, 5, issueId = "bara-pa-telefonen")),
            bookmarks = listOf(bookmark(3), bookmark(8, "Eget")),
            sessions = listOf(session(1), session(2)),
        )
        // En tom backup, och en som bara rör ett annat nummer.
        for (file in listOf(backup(), backup(states = listOf(state(1, issueId = "x")), bookmarks = listOf(bookmark(1, issueId = "x"))))) {
            val after = start.after(planImport(start, file))
            assertTrue(after.userStates.containsAll(start.userStates))
            assertTrue(after.bookmarks.containsAll(start.bookmarks))
            assertTrue(after.sessions.containsAll(start.sessions))
        }
        assertTrue(planImport(start, backup()).summary.nothingToDo)
    }

    @Test
    fun `two rows for the same issue inside the file are merged`() {
        val plan = planImport(empty, backup(states = listOf(state(100, 1, lastPage = 1), state(200, 2, lastPage = 2))))
        val merged = plan.userStatesToInsert.single()
        assertEquals(2, merged.lastPage)
        assertEquals(SeenPages.of(1, 2), SeenPages.decode(merged.seenPages))
    }

    // --- Formatet ---

    @Test
    fun `export and parse round trip keeps every field`() {
        val file = backup(
            states = listOf(state(200, 1, 2, 25, favorite = true, lastPage = 25, override = ReadStatusOverride.UNREAD, first = 1, last = 2, completed = 3)),
            bookmarks = listOf(
                Bookmark(
                    issueId = id, pageNumber = 7, title = "Ledare \"å ä ö\"", note = "rad 1\nrad 2", createdAt = 5, updatedAt = 6,
                    pdfSize = 25_672_804, pdfEtag = "\"abc\"", pdfLastModified = "Sat, 09 Sep 2017 19:49:26 GMT",
                ),
            ),
            sessions = listOf(session(1_000)),
        )
        val text = encodeBackup(file)
        assertTrue(text.contains("\"backupFormat\": 1"))
        assertTrue(text.contains("\"seenPages\": \"AwAAAQ==\""))
        assertTrue(text.contains("\"readStatusOverride\": \"UNREAD\""))
        assertEquals(file, parseBackup(text))
        // Och tillbaka till databasens rader utan förlust.
        assertEquals(state(200, 1, 2, 25, favorite = true, lastPage = 25, override = ReadStatusOverride.UNREAD, first = 1, last = 2, completed = 3), parseBackup(text).issueUserStates.single().toEntity())
    }

    private fun rejected(text: String): String = try {
        parseBackup(text)
        fail("skulle ha avvisats")
        ""
    } catch (e: BackupFormatException) {
        e.message!!
    }

    @Test
    fun `the backup format is validated`() {
        val valid = encodeBackup(backup(states = listOf(state(1, 1))))
        assertEquals(1, parseBackup(valid).backupFormat)

        assertTrue(rejected(valid.replace("\"backupFormat\": 1", "\"backupFormat\": 2")).contains("format 2"))
        assertTrue(rejected(valid.replace("\"backupFormat\": 1", "\"backupFormat\": 0")).contains("format 0"))
        assertTrue(rejected(valid.replace("\"backupFormat\": 1,", "")).contains("backupFormat saknas"))
        assertTrue(rejected(valid.replace("\"backupFormat\": 1", "\"backupFormat\": \"ett\"")).contains("backupFormat saknas"))
        assertTrue(rejected("").contains("inte en säkerhetskopia"))
        assertTrue(rejected("hej").contains("inte en säkerhetskopia"))
        assertTrue(rejected("[1, 2]").contains("inte en säkerhetskopia"))
        assertTrue(rejected("{\"schemaVersion\": 1, \"issues\": []}").contains("backupFormat saknas"))
    }

    @Test
    fun `invalid values are rejected with an explanation`() {
        fun file(states: String = "[]", bookmarks: String = "[]", sessions: String = "[]") =
            """{"backupFormat": 1, "exportedAt": "x", "appVersion": "y", "issueUserStates": $states, "bookmarks": $bookmarks, "readingSessions": $sessions}"""
        assertEquals(0, parseBackup(file()).bookmarks.size)
        assertTrue(rejected(file(states = """[{"issueId": "a", "updatedAt": 1, "readStatusOverride": "KANSKE"}]""")).contains("okänd lässtatus"))
        assertTrue(rejected(file(states = """[{"issueId": " ", "updatedAt": 1}]""")).contains("saknar issueId"))
        assertTrue(rejected(file(states = """[{"issueId": "a"}]""")).contains("skadad"))
        assertTrue(rejected(file(states = """[{"issueId": "a", "updatedAt": 1, "lastPage": 0}]""")).contains("ogiltig sida"))
        assertTrue(rejected(file(bookmarks = """[{"issueId": "a", "pageNumber": 0, "createdAt": 1, "updatedAt": 1}]""")).contains("ogiltig sida 0"))
        assertTrue(rejected(file(bookmarks = """[{"issueId": "a", "pageNumber": "sju", "createdAt": 1, "updatedAt": 1}]""")).contains("skadad"))
        assertTrue(rejected(file(sessions = """[{"issueId": "", "startedAt": 1, "endedAt": 2, "startPage": 1, "endPage": 1, "pagesViewed": 0, "durationSeconds": 1}]""")).contains("saknar issueId"))
    }

    @Test
    fun `unknown fields and missing lists are tolerated`() {
        val backup = parseBackup("""{"backupFormat": 1, "exportedAt": "x", "appVersion": "9.9", "nyttFält": true,
            "issueUserStates": [{"issueId": "a", "updatedAt": 1, "framtida": 5}]}""")
        assertEquals("a", backup.issueUserStates.single().issueId)
        assertTrue(backup.bookmarks.isEmpty())
        assertTrue(backup.readingSessions.isEmpty())
    }
}
