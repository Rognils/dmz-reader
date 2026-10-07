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

    /** Samma fil som den hade sett ut i format 1 (FAS 6): bokmärkena saknar bookmarkId och deletedAt. */
    private fun BackupFile.asFormat1() = copy(
        backupFormat = 1,
        bookmarks = bookmarks.filter { it.deletedAt == null }.map { it.copy(bookmarkId = null, deletedAt = null) },
    )

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
    fun `format 1 - duplicate bookmarks are skipped by issue, page and title`() {
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
            ).asFormat1(),
        )
        assertEquals(
            listOf("$id/14/Test", "$id/20/Sida 20", "datormagazin-1989-07/14/Annons"),
            plan.bookmarksToInsert.map { "${it.issueId}/${it.pageNumber}/${it.title}" },
        )
        assertEquals(6, plan.summary.bookmarks)
        assertEquals(3, plan.summary.newBookmarks)
        // Nya bokmärken får sitt rad-id av databasen, och var sitt nya UUID.
        assertTrue(plan.bookmarksToInsert.all { it.id == 0L })
        assertEquals(3, plan.bookmarksToInsert.map { it.bookmarkId }.toSet().size)
        assertTrue(plan.bookmarksToInsert.none { it.bookmarkId in current.bookmarks.map { b -> b.bookmarkId } })
        assertTrue(plan.bookmarksToUpdate.isEmpty())
    }

    // --- Format 2: bokmärken matchas på bookmarkId ---

    @Test
    fun `format 2 - a changed title does not give a duplicate when an old backup is imported`() {
        val original = bookmark(7, "Sida 7", at = 100)
        val old = backup(bookmarks = listOf(original))
        // På telefonen har bokmärket sedan fått en ny titel och en anteckning.
        val renamed = original.copy(id = 5, title = "Ledare", note = "läs igen", updatedAt = 900)
        val current = UserData(emptyList(), listOf(renamed), emptyList())

        val plan = planImport(current, old)

        assertTrue(plan.bookmarksToInsert.isEmpty())
        assertTrue(plan.bookmarksToUpdate.isEmpty())
        assertTrue(plan.summary.nothingToDo)
        assertEquals(listOf(renamed), current.after(plan).bookmarks)
    }

    @Test
    fun `format 2 - the newest title and note win in both directions`() {
        val local = bookmark(7, "Gammal", note = "gammal anteckning", at = 100).copy(id = 5)
        val newer = local.copy(id = 0, title = "Ny", note = null, updatedAt = 900)

        val plan = planImport(UserData(emptyList(), listOf(local), emptyList()), backup(bookmarks = listOf(newer)))
        val updated = plan.bookmarksToUpdate.single()
        assertEquals("Ny", updated.title)
        assertNull(updated.note)
        assertEquals(900L, updated.updatedAt)
        // Raden på telefonen behålls: samma rad-id och samma identitet.
        assertEquals(5L, updated.id)
        assertEquals(local.bookmarkId, updated.bookmarkId)
        assertEquals(1, plan.summary.updatedBookmarks)
        assertEquals(0, plan.summary.newBookmarks)
        assertTrue(plan.bookmarksToInsert.isEmpty())

        // Åt andra hållet: telefonen har den nyare versionen och ändras inte.
        val reverse = planImport(UserData(emptyList(), listOf(newer.copy(id = 5)), emptyList()), backup(bookmarks = listOf(local)))
        assertTrue(reverse.summary.nothingToDo)
        // Lika tid: telefonens värden gäller.
        val tie = mergeBookmark(local, local.copy(title = "Annan"))
        assertEquals("Gammal", tie.title)
    }

    @Test
    fun `format 2 - a deleted bookmark is never brought back by an old backup`() {
        val original = bookmark(7, "Ledare", at = 100)
        val oldBackup = backup(bookmarks = listOf(original))
        // Bokmärket togs bort på telefonen efter att backupen gjordes.
        val deleted = original.copy(id = 5, deletedAt = 500, updatedAt = 500)
        val current = UserData(emptyList(), listOf(deleted), emptyList())

        val plan = planImport(current, oldBackup)

        assertTrue(plan.bookmarksToInsert.isEmpty())
        assertTrue(plan.bookmarksToUpdate.isEmpty())
        assertTrue(plan.summary.nothingToDo)
        assertEquals(500L, current.after(plan).bookmarks.single().deletedAt)

        // Även om backupens rad är nyare än borttagningen förblir bokmärket borttaget.
        val newerButAlive = backup(bookmarks = listOf(original.copy(title = "Ny titel", updatedAt = 9_000)))
        val second = planImport(current, newerButAlive)
        val merged = second.bookmarksToUpdate.single()
        assertEquals(500L, merged.deletedAt)
        assertEquals("Ny titel", merged.title)
        assertEquals(0, second.summary.newBookmarks)
        assertEquals(0, second.summary.updatedBookmarks)
        assertTrue(current.after(second).bookmarks.all { it.isDeleted })
    }

    @Test
    fun `format 2 - a deletion in the backup removes the bookmark on the phone`() {
        val local = bookmark(7, "Ledare", at = 100).copy(id = 5)
        val deletedElsewhere = local.copy(id = 0, deletedAt = 700, updatedAt = 700)
        val plan = planImport(UserData(emptyList(), listOf(local), emptyList()), backup(bookmarks = listOf(deletedElsewhere)))
        assertEquals(700L, plan.bookmarksToUpdate.single().deletedAt)
        assertEquals(1, plan.summary.updatedBookmarks)
        // Filens borttagna bokmärken räknas inte som bokmärken i sammanfattningen.
        assertEquals(0, plan.summary.bookmarks)

        // Ett borttaget bokmärke som telefonen aldrig har haft följer med som borttaget, utan att synas.
        val unknown = planImport(empty, backup(bookmarks = listOf(deletedElsewhere)))
        assertTrue(unknown.bookmarksToInsert.single().isDeleted)
        assertEquals(0, unknown.summary.newBookmarks)
        assertEquals(1, unknown.summary.hiddenChanges)
        assertFalse(unknown.summary.nothingToDo)
    }

    @Test
    fun `format 2 - the duplicate rule for issue, page and title is not used`() {
        // Två olika bokmärken på samma sida med samma titel, skapade på två telefoner.
        val onPhone = bookmark(7, "Ledare").copy(id = 5)
        val fromOtherPhone = bookmark(7, "Ledare")
        val plan = planImport(UserData(emptyList(), listOf(onPhone), emptyList()), backup(bookmarks = listOf(fromOtherPhone)))
        assertEquals(listOf(fromOtherPhone.bookmarkId), plan.bookmarksToInsert.map { it.bookmarkId })
        assertEquals(1, plan.summary.newBookmarks)
    }

    @Test
    fun `format 1 - still imports, with new uuids, and never revives a deleted bookmark`() {
        val file = backup(bookmarks = listOf(bookmark(7, "Ledare"), bookmark(9, "Test"))).asFormat1()
        assertEquals(1, parseBackup(encodeBackup(file)).backupFormat)
        var counter = 0
        val plan = planImport(empty, file) { "ny-${counter++}" }
        assertEquals(listOf("ny-0", "ny-1"), plan.bookmarksToInsert.map { it.bookmarkId })
        assertEquals(listOf("Ledare", "Test"), plan.bookmarksToInsert.map { it.title })
        assertTrue(plan.bookmarksToInsert.none { it.isDeleted })

        // Samma fil igen: dubblettregeln för format 1 hoppar över båda.
        val after = empty.after(plan)
        assertTrue(planImport(after, file).summary.nothingToDo)

        // Ett bokmärke som har tagits bort på telefonen återuppstår inte ur format 1 heller.
        val deleted = after.copy(bookmarks = after.bookmarks.map { if (it.pageNumber == 7) it.copy(deletedAt = 500) else it })
        val again = planImport(deleted, file)
        assertTrue(again.summary.nothingToDo)
        assertEquals(1, deleted.after(again).bookmarks.count { it.isDeleted })
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
        assertTrue(second.bookmarksToUpdate.isEmpty())
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
        assertTrue(text.contains("\"backupFormat\": 2"))
        assertTrue(text.contains("\"bookmarkId\": \"${file.bookmarks.single().bookmarkId}\""))
        assertTrue(text.contains("\"deletedAt\": null"))
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
        val valid = encodeBackup(backup(states = listOf(state(1, 1)), bookmarks = listOf(bookmark(3))))
        assertEquals(2, parseBackup(valid).backupFormat)
        // Format 1 går fortfarande att läsa.
        assertEquals(1, parseBackup(encodeBackup(backup(states = listOf(state(1, 1)), bookmarks = listOf(bookmark(3))).asFormat1())).backupFormat)

        assertTrue(rejected(valid.replace("\"backupFormat\": 2", "\"backupFormat\": 3")).contains("format 3"))
        assertTrue(rejected(valid.replace("\"backupFormat\": 2", "\"backupFormat\": 0")).contains("format 0"))
        assertTrue(rejected(valid.replace("\"backupFormat\": 2,", "")).contains("backupFormat saknas"))
        assertTrue(rejected(valid.replace("\"backupFormat\": 2", "\"backupFormat\": \"ett\"")).contains("backupFormat saknas"))
        assertTrue(rejected("").contains("inte en säkerhetskopia"))
        assertTrue(rejected("hej").contains("inte en säkerhetskopia"))
        assertTrue(rejected("[1, 2]").contains("inte en säkerhetskopia"))
        assertTrue(rejected("{\"schemaVersion\": 1, \"issues\": []}").contains("backupFormat saknas"))
        // I format 2 måste varje bokmärke ha sitt bookmarkId.
        val withoutId = valid.replace(Regex("\"bookmarkId\": \"[^\"]+\","), "")
        assertTrue(rejected(withoutId).contains("saknar bookmarkId"))
        assertEquals(1, parseBackup(withoutId.replace("\"backupFormat\": 2", "\"backupFormat\": 1")).bookmarks.size)
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
