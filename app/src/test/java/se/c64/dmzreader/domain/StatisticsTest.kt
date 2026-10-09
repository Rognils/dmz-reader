package se.c64.dmzreader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.c64.dmzreader.data.db.Bookmark
import se.c64.dmzreader.data.db.IssueUserState
import se.c64.dmzreader.data.db.ReadStatusOverride
import se.c64.dmzreader.data.db.ReadingSession
import se.c64.dmzreader.testing.issueEntity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Statistikens definitioner (planens E12, ADR 0021), med kontrollerade testdata. */
class StatisticsTest {
    private val zone = ZoneId.of("Europe/Stockholm")
    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
    private val now = at("2026-10-09T12:00:00") // en fredag, vecka 41

    private fun state(
        issueId: String,
        pages: IntRange? = null,
        favorite: Boolean = false,
        override: ReadStatusOverride? = null,
    ) = IssueUserState(
        issueId = issueId,
        isFavorite = favorite,
        seenPages = pages?.let { SeenPages.of(*it.toList().toIntArray()).encode() },
        readStatusOverride = override,
        updatedAt = 1,
    )

    private fun item(issueId: String, pageCount: Int?, state: IssueUserState? = null, title: String = "Datormagazin") =
        IssueWithState(issueEntity(issueId, publicationId = issueId.substringBefore("-"), publicationTitle = title), state, pageCount)

    private fun session(start: String, seconds: Int, issueId: String = "datormagazin-1989-01") =
        ReadingSession(issueId = issueId, startedAt = at(start), endedAt = at(start) + seconds * 1000L, startPage = 1, endPage = 2, pagesViewed = 1, durationSeconds = seconds)

    // Katalogen: tre nummer 1989, två 1990 och ett HighScore 1993.
    private val readAll = state("datormagazin-1989-01", 1..50)                       // 50 av 50: läst
    private val readMost = state("datormagazin-1989-02", 1..45)                      // 45 av 50 = 90 %: läst
    private val started = state("datormagazin-1989-03", 1..10, favorite = true)      // 10 av 40: påbörjad
    private val markedRead = state("datormagazin-1990-01", 1..2, override = ReadStatusOverride.READ)
    private val markedUnread = state("datormagazin-1990-02", 1..30, override = ReadStatusOverride.UNREAD)
    private val highScore = state("highscore-1993-17", 1..8)                         // okänt sidantal: påbörjad
    // Användardata för nummer som inte finns i katalogen (planens D6).
    private val missingStarted = state("datormagazin-1996-01", 1..7, favorite = true)
    private val missingMarkedRead = state("datormagazin-1996-02", null, override = ReadStatusOverride.READ)
    private val missingFavoriteOnly = state("datormagazin-1996-03", null, favorite = true)

    private val issues = listOf(
        item("datormagazin-1989-01", 50, readAll),
        item("datormagazin-1989-02", 50, readMost),
        item("datormagazin-1989-03", 40, started),
        item("datormagazin-1989-04", 60, null),
        item("datormagazin-1990-01", 48, markedRead),
        item("datormagazin-1990-02", 48, markedUnread),
        item("highscore-1993-17", null, highScore, title = "HighScore"),
        item("highscore-1993-18", 32, null, title = "HighScore"),
    )
    private val userStates = listOf(
        readAll, readMost, started, markedRead, markedUnread, highScore, missingStarted, missingMarkedRead, missingFavoriteOnly,
    )
    private val bookmarks = listOf(
        Bookmark(id = 1, issueId = "datormagazin-1989-01", pageNumber = 3, createdAt = 1, updatedAt = 1),
        Bookmark(id = 2, issueId = "datormagazin-1996-01", pageNumber = 4, createdAt = 1, updatedAt = 1),
        Bookmark(id = 3, issueId = "datormagazin-1989-01", pageNumber = 9, createdAt = 1, updatedAt = 2, deletedAt = 2),
    )
    private val sessions = listOf(
        session("2026-10-08T20:00:00", 1_800),     // vecka 41
        session("2026-10-05T00:00:01", 600),       // måndag, vecka 41
        session("2026-10-04T23:59:59", 300),       // söndag, vecka 40
        session("2026-07-21T10:00:00", 3_600),     // vecka 30, den äldsta av de tolv
        session("2026-07-19T10:00:00", 7_200),     // vecka 29: utanför diagrammet men med i lästiden
    )

    private val statistics = computeStatistics(issues, userStates, bookmarks, sessions, now, zone)

    @Test
    fun `pages read is the number of set bits in seenPages, for all user data`() {
        // 50 + 45 + 10 + 2 + 30 + 8 i katalogen, och 7 i ett nummer som saknas där.
        assertEquals(50 + 45 + 10 + 2 + 30 + 8 + 7, statistics.pagesRead)
    }

    @Test
    fun `reading time is the sum of the session durations`() {
        assertEquals(1_800L + 600 + 300 + 3_600 + 7_200, statistics.readingSeconds)
        assertEquals(5, statistics.sessionCount)
        assertEquals("3 h 45 min", durationText(statistics.readingSeconds))
    }

    @Test
    fun `read, started and unread follow C10 with the manual override`() {
        // Lästa: två automatiskt, ett markerat som läst, och ett saknat nummer markerat som läst.
        assertEquals(4, statistics.issuesRead)
        // Påbörjade: 1989 nr 3, HighScore med okänt sidantal, och ett saknat nummer med lästa sidor.
        assertEquals(3, statistics.issuesStarted)
        // Olästa: 1989 nr 4, HighScore nr 18, och 1990 nr 2 som är markerat som oläst trots 30 lästa sidor.
        assertEquals(3, statistics.issuesUnread)
    }

    @Test
    fun `an issue with unknown page count is started, never read, without a manual mark`() {
        val only = computeStatistics(listOf(item("highscore-1993-17", null, state("highscore-1993-17", 1..200), title = "HighScore")),
            listOf(state("highscore-1993-17", 1..200)), emptyList(), emptyList(), now, zone)
        assertEquals(0, only.issuesRead)
        assertEquals(1, only.issuesStarted)
        assertEquals(200, only.pagesRead)
        // Årgångens sidandel går inte att räkna utan sidantal.
        assertEquals(0, only.years.single().pageCount)
        assertEquals(0f, only.years.single().pageFraction, 0f)
        assertEquals(0, yearPagePercent(only.years.single()))
    }

    @Test
    fun `year progress per publication and year`() {
        val byKey = statistics.years.associateBy { "${it.publicationId}-${it.year}" }
        assertEquals(listOf("datormagazin-1989", "datormagazin-1990", "highscore-1993"), statistics.years.map { "${it.publicationId}-${it.year}" })

        val y1989 = byKey.getValue("datormagazin-1989")
        assertEquals(4, y1989.issueCount)
        assertEquals(2, y1989.issuesRead)
        assertEquals(1, y1989.issuesStarted)
        assertEquals(50 + 45 + 10, y1989.pagesRead)
        assertEquals(50 + 50 + 40 + 60, y1989.pageCount)
        assertEquals(0.525f, y1989.pageFraction, 0.0001f)
        assertEquals(0.5f, y1989.issueFraction, 0f)
        assertEquals(53, yearPagePercent(y1989))
        assertEquals("2 av 4 nummer lästa", yearIssuesText(y1989))

        // 1990: ett markerat som läst och ett som oläst. Sidorna räknas som de är lästa.
        val y1990 = byKey.getValue("datormagazin-1990")
        assertEquals(1, y1990.issuesRead)
        assertEquals(0, y1990.issuesStarted)
        assertEquals(32, y1990.pagesRead)
        assertEquals(96, y1990.pageCount)

        // HighScore: numret med okänt sidantal räknas inte i sidsumman, men dess lästa sidor räknas.
        val hs = byKey.getValue("highscore-1993")
        assertEquals("HighScore", hs.publicationTitle)
        assertEquals(2, hs.issueCount)
        assertEquals(32, hs.pageCount)
        assertEquals(8, hs.pagesRead)
    }

    @Test
    fun `pages beyond the page count are not counted in the year`() {
        // seenPages har bitar för sidor som inte finns i numret, till exempel efter att PDF:en bytts.
        val tooMany = state("datormagazin-1989-01", 1..60)
        val result = computeStatistics(listOf(item("datormagazin-1989-01", 50, tooMany)), listOf(tooMany), emptyList(), emptyList(), now, zone)
        assertEquals(50, result.years.single().pagesRead)
        assertEquals(100, yearPagePercent(result.years.single()))
        // Nyckeltalet är definierat som antalet satta bitar.
        assertEquals(60, result.pagesRead)
    }

    @Test
    fun `most read year is the one with most pages read`() {
        assertEquals(1989, statistics.mostReadYear!!.year)
        assertEquals(105, statistics.mostReadYear!!.pagesRead)
        assertNull(computeStatistics(issues.map { it.copy(state = null) }, emptyList(), emptyList(), emptyList(), now, zone).mostReadYear)
    }

    @Test
    fun `favorites and bookmarks are counted, also for missing issues, but not deleted bookmarks`() {
        assertEquals(3, statistics.favorites)
        assertEquals(2, statistics.bookmarks)
    }

    @Test
    fun `activity is reading time per week, Monday to Sunday, for the last twelve weeks`() {
        val activity = statistics.activity
        assertEquals(12, activity.size)
        assertEquals(LocalDate.parse("2026-10-05"), activity.last().start)   // innevarande vecka börjar på måndagen
        assertEquals(LocalDate.parse("2026-07-20"), activity.first().start)  // elva veckor tidigare
        assertTrue(activity.zipWithNext().all { (a, b) -> a.start.plusWeeks(1) == b.start })
        // Måndag 00:00:01 hör till den nya veckan, söndag 23:59:59 till den gamla.
        assertEquals(1_800L + 600, activity.last().seconds)
        assertEquals(300L, activity[10].seconds)
        assertEquals(3_600L, activity.first().seconds)
        // Sessionen i vecka 29 ligger utanför diagrammet.
        assertEquals(1_800L + 600 + 300 + 3_600, activity.sumOf { it.seconds })
        assertEquals("v. 41", weekLabel(activity.last().start))
        assertEquals("v. 30", weekLabel(activity.first().start))
    }

    @Test
    fun `weeks are right across the turn of the year`() {
        // Söndag 3 januari 2027 hör till vecka 53, som började måndag 28 december 2026.
        val newYear = at("2027-01-03T18:00:00")
        val around = listOf(
            session("2026-12-27T23:30:00", 100),   // söndag, vecka 52
            session("2026-12-28T00:30:00", 200),   // måndag, vecka 53
            session("2026-12-31T23:59:00", 400),   // nyårsafton
            session("2027-01-01T00:01:00", 800),   // nyårsdagen, samma vecka
            session("2027-01-03T17:00:00", 1_600), // söndag, samma vecka
        )
        val activity = weeklyActivity(around, newYear, zone)

        assertEquals(LocalDate.parse("2026-12-28"), activity.last().start)
        assertEquals(200L + 400 + 800 + 1_600, activity.last().seconds)
        assertEquals(LocalDate.parse("2026-12-21"), activity[10].start)
        assertEquals(100L, activity[10].seconds)
        assertEquals("v. 53", weekLabel(activity.last().start))
        assertEquals("v. 52", weekLabel(activity[10].start))

        // Dagen efter, måndag 4 januari 2027, är vecka 1.
        val next = weeklyActivity(around, at("2027-01-04T08:00:00"), zone)
        assertEquals(LocalDate.parse("2027-01-04"), next.last().start)
        assertEquals(0L, next.last().seconds)
        assertEquals("v. 1", weekLabel(next.last().start))
        assertEquals(3_000L, next[10].seconds)
    }

    @Test
    fun `the week follows local time, not UTC`() {
        // Måndag 00:30 i Stockholm är söndag 22:30 UTC.
        val monday = listOf(session("2026-10-05T00:30:00", 60))
        assertEquals(60L, weeklyActivity(monday, now, zone).last().seconds)
        assertEquals(0L, weeklyActivity(monday, now, ZoneId.of("UTC")).last().seconds)
        assertEquals(60L, weeklyActivity(monday, now, ZoneId.of("UTC"))[10].seconds)
    }

    @Test
    fun `empty data gives an empty state`() {
        val empty = computeStatistics(issues.map { it.copy(state = null) }, emptyList(), emptyList(), emptyList(), now, zone)
        assertTrue(empty.isEmpty)
        assertEquals(8, empty.issuesUnread)
        assertEquals(12, empty.activity.size)
        assertTrue(empty.activity.all { it.seconds == 0L })
        assertEquals(3, empty.years.size)
        assertFalse(statistics.isEmpty)
        // En favorit räcker för att det ska finnas något att visa.
        assertFalse(computeStatistics(issues, listOf(missingFavoriteOnly), emptyList(), emptyList(), now, zone).isEmpty)
    }

    @Test
    fun `duration text`() {
        assertEquals("42 h 18 min", durationText(42 * 3600L + 18 * 60 + 59))
        assertEquals("1 h 0 min", durationText(3600))
        assertEquals("18 min", durationText(18 * 60 + 30L))
        assertEquals("45 s", durationText(45))
        assertEquals("0 min", durationText(0))
    }

    @Test
    fun `year percent never shows 0 when something is read or 100 before everything is`() {
        fun year(read: Int, total: Int) = YearProgress("datormagazin", "Datormagazin", 1989, 1, 0, 0, read, total)
        assertEquals(0, yearPagePercent(year(0, 1000)))
        assertEquals(1, yearPagePercent(year(1, 1000)))
        assertEquals(99, yearPagePercent(year(999, 1000)))
        assertEquals(100, yearPagePercent(year(1000, 1000)))
        assertEquals(50, yearPagePercent(year(500, 1000)))
    }
}
