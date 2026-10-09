package se.c64.dmzreader.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.c64.dmzreader.data.db.IssueUserState
import se.c64.dmzreader.data.db.IssueUserStateDao
import se.c64.dmzreader.data.db.ReadStatusOverride
import se.c64.dmzreader.data.db.ReadingSession
import se.c64.dmzreader.data.db.ReadingSessionDao
import se.c64.dmzreader.domain.SeenPages
import se.c64.dmzreader.testing.inMemoryDatabase

/** Tabellen i minnet, så att testet styr tiden helt och ser varje skrivning. */
private class FakeUserStateDao : IssueUserStateDao {
    val rows = LinkedHashMap<String, IssueUserState>()
    val writes = ArrayList<IssueUserState>()

    override suspend fun upsert(state: IssueUserState) {
        rows[state.issueId] = state
        writes += state
    }

    override suspend fun insert(state: IssueUserState) {
        check(state.issueId !in rows) { "raden finns redan" }
        upsert(state)
    }

    override suspend fun update(state: IssueUserState): Int {
        if (state.issueId !in rows) return 0
        upsert(state)
        return 1
    }

    override suspend fun get(issueId: String): IssueUserState? = rows[issueId]
    override suspend fun getAll(): List<IssueUserState> = rows.values.toList()
    override fun observeAll(): Flow<List<IssueUserState>> = MutableStateFlow(rows.values.toList())
}

private class FakeSessionDao : ReadingSessionDao {
    val sessions = ArrayList<ReadingSession>()
    private var nextId = 1L

    /** Varje skrivning, i ordning: "insert 1", "update 1", "delete 1". */
    val log = ArrayList<String>()

    override suspend fun insert(session: ReadingSession): Long {
        val id = nextId++
        sessions += session.copy(id = id)
        log += "insert $id"
        return id
    }

    override suspend fun update(session: ReadingSession): Int {
        val index = sessions.indexOfFirst { it.id == session.id }
        if (index < 0) return 0
        sessions[index] = session
        log += "update ${session.id}"
        return 1
    }

    override suspend fun delete(id: Long): Int {
        log += "delete $id"
        return if (sessions.removeAll { it.id == id }) 1 else 0
    }

    override suspend fun deleteShorterThan(minSeconds: Int): Int {
        val before = sessions.size
        sessions.removeAll { it.durationSeconds < minSeconds }
        return before - sessions.size
    }

    override suspend fun getAll(): List<ReadingSession> = sessions
    override fun observeAll(): Flow<List<ReadingSession>> = MutableStateFlow(sessions.toList())
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingRecorderTest {
    private val issueId = "datormagazin-1986-01"
    private val userStates = FakeUserStateDao()
    private val sessions = FakeSessionDao()
    private var clock = 50_000L

    private fun TestScope.recorder(): ReadingRecorder {
        val repository = ReadingRepository(userStates, sessions) { clock }
        return ReadingRecorder(issueId, repository, backgroundScope, now = { clock })
    }

    private val lastPage get() = userStates.rows[issueId]?.lastPage

    @Test
    fun `the position is written one second after it changed`() = runTest {
        val recorder = recorder()
        recorder.anchorPage(5)
        runCurrent()
        assertNull(lastPage)
        advanceTimeBy(999)
        runCurrent()
        assertNull(lastPage)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(5, lastPage)
        assertEquals(1, userStates.writes.size)
    }

    @Test
    fun `turning many pages gives one write with the latest page`() = runTest {
        val recorder = recorder()
        for (page in 2..9) {
            recorder.anchorPage(page)
            advanceTimeBy(100)
            runCurrent()
        }
        // 800 ms har gått: ingenting är skrivet ännu.
        assertTrue(userStates.writes.isEmpty())
        advanceTimeBy(200)
        runCurrent()
        // Skrivningen kom en sekund efter den första ändringen, inte efter den sista.
        assertEquals(listOf(9), userStates.writes.map { it.lastPage })
    }

    @Test
    fun `continuous page turning still writes about once a second`() = runTest {
        val recorder = recorder()
        for (page in 1..30) {
            recorder.anchorPage(page)
            advanceTimeBy(250)
            runCurrent()
        }
        // 7,5 s bläddring: skrivningarna skjuts inte upp i det oändliga.
        assertTrue("antal skrivningar: ${userStates.writes.size}", userStates.writes.size in 6..8)
    }

    @Test
    fun `flush writes at once and the delayed write does nothing more`() = runTest {
        val recorder = recorder()
        recorder.anchorPage(12)
        recorder.flush()
        runCurrent()
        assertEquals(12, lastPage)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, userStates.writes.size)
        // Ingenting väntar: en ny flush skriver inte igen.
        recorder.flush()
        runCurrent()
        assertEquals(1, userStates.writes.size)
    }

    @Test
    fun `closing the reader forces the write`() = runTest {
        val recorder = recorder()
        recorder.anchorPage(3)
        advanceTimeBy(200)
        recorder.anchorPage(17)
        recorder.close()
        runCurrent()
        assertEquals(17, lastPage)
        // Efter stängning tas ingenting emot, och ingenting kraschar.
        recorder.anchorPage(20)
        recorder.flush()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(17, lastPage)
    }

    @Test
    fun `writes are applied in order and never touch each other's fields`() = runTest {
        userStates.rows[issueId] = IssueUserState(
            issueId = issueId, isFavorite = true, readStatusOverride = ReadStatusOverride.READ, updatedAt = 1,
        )
        val recorder = recorder()
        recorder.opened()
        recorder.pagesRead(listOf(1), pageCount = 28, clearUnread = false)
        recorder.anchorPage(2)
        recorder.pagesRead(listOf(2, 3), pageCount = 28, clearUnread = false)
        recorder.close()
        runCurrent()

        val state = userStates.rows.getValue(issueId)
        assertEquals(SeenPages.of(1, 2, 3), SeenPages.decode(state.seenPages))
        assertEquals(3, state.maxPageReached)
        assertEquals(2, state.lastPage)
        assertEquals(50_000L, state.firstOpenedAt)
        assertEquals(50_000L, state.lastOpenedAt)
        assertEquals(50_000L, state.updatedAt)
        // Favoriten och den manuella markeringen finns kvar.
        assertTrue(state.isFavorite)
        assertEquals(ReadStatusOverride.READ, state.readStatusOverride)
    }

    @Test
    fun `reading a new page clears a mark as unread`() = runTest {
        userStates.rows[issueId] = IssueUserState(
            issueId = issueId, seenPages = SeenPages.of(1, 2).encode(), maxPageReached = 2, lastPage = 2,
            readStatusOverride = ReadStatusOverride.UNREAD, updatedAt = 1,
        )
        val recorder = recorder()
        recorder.pagesRead(listOf(2), pageCount = 28, clearUnread = false)
        runCurrent()
        assertEquals(ReadStatusOverride.UNREAD, userStates.rows.getValue(issueId).readStatusOverride)
        recorder.pagesRead(listOf(3), pageCount = 28, clearUnread = true)
        runCurrent()
        assertNull(userStates.rows.getValue(issueId).readStatusOverride)
        assertEquals(SeenPages.of(1, 2, 3), SeenPages.decode(userStates.rows.getValue(issueId).seenPages))
    }

    private fun session(seconds: Int, endPage: Int = 6) = ReadingSession(
        issueId = issueId, startedAt = 1_000, endedAt = 1_000 + seconds * 1000L, startPage = 1, endPage = endPage,
        pagesViewed = endPage, durationSeconds = seconds,
    )

    @Test
    fun `a running session is written to one row, first as an insert and then as updates`() = runTest {
        val recorder = recorder()
        recorder.session(session(0, endPage = 1))
        recorder.session(session(30, endPage = 3))
        recorder.session(session(60))
        recorder.awaitWritten()

        assertEquals(listOf("insert 1", "update 1", "update 1"), sessions.log)
        assertEquals(listOf(session(60).copy(id = 1)), sessions.sessions)
    }

    @Test
    fun `the last written state survives when the app dies`() = runTest {
        // Appen dör efter den andra skrivningen: ingen avslutande skrivning kommer.
        val recorder = recorder()
        recorder.session(session(0, endPage = 1))
        recorder.session(session(30, endPage = 3))
        recorder.awaitWritten()

        val saved = sessions.sessions.single()
        assertEquals(30, saved.durationSeconds)
        assertEquals(3, saved.endPage)
    }

    @Test
    fun `a session that ends with too little active time is removed`() = runTest {
        val recorder = recorder()
        recorder.session(session(0, endPage = 1))
        recorder.session(session(4), final = true)
        recorder.awaitWritten()

        assertEquals(listOf("insert 1", "update 1", "delete 1"), sessions.log)
        assertTrue(sessions.sessions.isEmpty())
    }

    @Test
    fun `a finished session is kept and the next session gets a new row`() = runTest {
        val recorder = recorder()
        recorder.session(session(0, endPage = 1))
        recorder.session(session(42), final = true)
        recorder.session(session(0, endPage = 2).copy(startedAt = 900_000))
        recorder.session(session(10).copy(startedAt = 900_000), final = true)
        recorder.awaitWritten()

        assertEquals(listOf("insert 1", "update 1", "insert 2", "update 2"), sessions.log)
        assertEquals(listOf(42, 10), sessions.sessions.map { it.durationSeconds })
        assertEquals(listOf(1L, 2L), sessions.sessions.map { it.id })
    }

    @Test
    fun `short sessions left behind are cleaned up at start`() = runTest {
        val repository = ReadingRepository(userStates, sessions) { clock }
        // Kvar efter en krasch strax efter att läsaren öppnades, och två riktiga sessioner.
        sessions.sessions += listOf(session(0).copy(id = 7), session(4).copy(id = 8), session(5).copy(id = 9), session(61).copy(id = 10))

        assertEquals(2, repository.removeShortSessions())

        assertEquals(listOf(9L, 10L), sessions.sessions.map { it.id })
        assertEquals(0, repository.removeShortSessions())
    }

    @Test
    fun `a failing write is reported and the next one still runs`() = runTest {
        val failing = object : IssueUserStateDao by userStates {
            var fail = true
            override suspend fun insert(state: IssueUserState) {
                if (fail) {
                    fail = false
                    error("disken är full")
                }
                userStates.insert(state)
            }
        }
        val errors = ArrayList<Exception>()
        val recorder = ReadingRecorder(
            issueId, ReadingRepository(failing, sessions) { clock }, backgroundScope, now = { clock },
            onError = { errors += it },
        )
        recorder.anchorPage(4)
        recorder.flush()
        recorder.anchorPage(5)
        recorder.flush()
        runCurrent()
        assertEquals(1, errors.size)
        assertEquals(5, lastPage)
    }
}

/** Repositoryt mot den riktiga databasen (Room 3 med BundledSQLiteDriver, i minnet). */
class ReadingRepositoryTest {
    private val issueId = "datormagazin-1989-06"

    @Test
    fun `manual status never changes position or read pages`() = runTest {
        val db = inMemoryDatabase()
        try {
            var clock = 1_000L
            val repository = ReadingRepository(db.issueUserStateDao(), db.readingSessionDao()) { clock }
            repository.update(issueId) {
                it.copy(lastPage = 7, seenPages = SeenPages.of(1, 2, 3, 4, 5, 6, 7).encode(), maxPageReached = 7, isFavorite = true)
            }

            clock = 2_000
            repository.setOverride(issueId, ReadStatusOverride.UNREAD, pageCount = 28)
            val unread = repository.get(issueId)!!
            assertEquals(ReadStatusOverride.UNREAD, unread.readStatusOverride)
            assertEquals(SeenPages.of(1, 2, 3, 4, 5, 6, 7), SeenPages.decode(unread.seenPages))
            assertEquals(7, unread.lastPage)
            assertEquals(7, unread.maxPageReached)
            assertTrue(unread.isFavorite)
            assertNull(unread.completedAt)
            assertEquals(2_000L, unread.updatedAt)

            clock = 3_000
            repository.setOverride(issueId, ReadStatusOverride.READ, pageCount = 28)
            val read = repository.get(issueId)!!
            assertEquals(ReadStatusOverride.READ, read.readStatusOverride)
            assertEquals(3_000L, read.completedAt)
            assertEquals(unread.seenPages, read.seenPages)
            assertEquals(7, read.lastPage)

            clock = 4_000
            repository.setOverride(issueId, null, pageCount = 28)
            val reset = repository.get(issueId)!!
            assertNull(reset.readStatusOverride)
            assertEquals(3_000L, reset.completedAt)
            assertEquals(unread.seenPages, reset.seenPages)
        } finally {
            db.close()
        }
    }

    @Test
    fun `an unchanged row is not written again`() = runTest {
        val db = inMemoryDatabase()
        try {
            var clock = 1_000L
            val repository = ReadingRepository(db.issueUserStateDao(), db.readingSessionDao()) { clock }
            repository.update(issueId) { it.copy(lastPage = 3) }
            clock = 9_000
            repository.update(issueId) { it.copy(lastPage = 3) }
            assertEquals(1_000L, repository.get(issueId)!!.updatedAt)
            // Ingen rad skapas bara av att numret tillfrågas.
            repository.update("datormagazin-1989-07") { it }
            assertNull(repository.get("datormagazin-1989-07"))
        } finally {
            db.close()
        }
    }
}
