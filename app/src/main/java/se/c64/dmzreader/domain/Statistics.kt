package se.c64.dmzreader.domain

import se.c64.dmzreader.data.db.Bookmark
import se.c64.dmzreader.data.db.IssueUserState
import se.c64.dmzreader.data.db.ReadingSession
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Statistiken (planens E12 och ADR 0021). Ren logik utan Android-beroenden.
 *
 * Definitioner:
 * - Lästa sidor: summan av antalet satta bitar i seenPages, för all användardata.
 * - Lästid: summan av durationSeconds i ReadingSession. Det är aktiv tid, se SessionTracker.
 * - Lästa, påbörjade och olästa nummer: lässtatus enligt C10, med readStatusOverride.
 * - Årgångsprogress: andel lästa nummer och andel lästa sidor per år och publikation.
 * - Aktivitet: lästid per vecka (måndag–söndag, lokal tid) för de senaste tolv veckorna.
 *   Sessioner delas vid lokal midnatt (SessionTracker), så ingen session går över en veckogräns.
 * - Mest lästa årgång: den årgång, i någon publikation, där flest sidor är lästa.
 *   Har flera årgångar lika många visas alla.
 */

/** Antal veckor i aktivitetsdiagrammet. */
const val ACTIVITY_WEEKS = 12

data class YearProgress(
    val publicationId: String,
    val publicationTitle: String,
    val year: Int,
    val issueCount: Int,
    val issuesRead: Int,
    val issuesStarted: Int,
    /** Lästa sidor i årgångens nummer. Sidor över numrets sidantal räknas inte. */
    val pagesRead: Int,
    /** Summan av sidantalen. Nummer med okänt sidantal ingår inte. */
    val pageCount: Int,
) {
    /** Andel lästa sidor, 0–1. 0 om inget sidantal är känt. */
    val pageFraction: Float get() = if (pageCount > 0) (pagesRead.toFloat() / pageCount).coerceIn(0f, 1f) else 0f
    val issueFraction: Float get() = if (issueCount > 0) issuesRead.toFloat() / issueCount else 0f
}

/** Lästiden under en vecka. [start] är veckans måndag. */
data class WeekActivity(val start: LocalDate, val seconds: Long)

data class Statistics(
    val issuesRead: Int = 0,
    val issuesStarted: Int = 0,
    val issuesUnread: Int = 0,
    val pagesRead: Int = 0,
    val readingSeconds: Long = 0,
    val sessionCount: Int = 0,
    /** De senaste tolv veckorna, äldst först. Den sista är innevarande vecka. */
    val activity: List<WeekActivity> = emptyList(),
    /** En rad per publikation och år, i tidsordning. */
    val years: List<YearProgress> = emptyList(),
    /** Årgångarna med flest lästa sidor, i tidsordning. Fler än en när de har lika många. Tom när inget är läst. */
    val mostReadYears: List<YearProgress> = emptyList(),
    val favorites: Int = 0,
    val bookmarks: Int = 0,
) {
    /** Sant när det inte finns något att visa: inget läst, inga favoriter och inga bokmärken. */
    val isEmpty: Boolean
        get() = issuesRead == 0 && issuesStarted == 0 && pagesRead == 0 && readingSeconds == 0L &&
            favorites == 0 && bookmarks == 0
}

/** Måndagen i den vecka som [date] ligger i. */
fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

/**
 * Lästid per vecka för de [weeks] senaste veckorna, räknat från veckan som [now] ligger i.
 * En session räknas till den vecka den började, i telefonens tidszon.
 */
fun weeklyActivity(sessions: List<ReadingSession>, now: Long, zone: ZoneId, weeks: Int = ACTIVITY_WEEKS): List<WeekActivity> {
    val current = weekStart(Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
    val starts = (weeks - 1 downTo 0).map { current.minusWeeks(it.toLong()) }
    val seconds = HashMap<LocalDate, Long>()
    for (session in sessions) {
        val week = weekStart(Instant.ofEpochMilli(session.startedAt).atZone(zone).toLocalDate())
        seconds[week] = (seconds[week] ?: 0L) + session.durationSeconds
    }
    return starts.map { WeekActivity(it, seconds[it] ?: 0L) }
}

/**
 * Räknar fram statistiken.
 *
 * [issues] är katalogen med läsdata. [userStates] är all användardata, även för
 * nummer som saknas i katalogen (planens D6): deras lästa sidor och favoriter
 * räknas, och de räknas som lästa eller påbörjade, men de hör inte till någon årgång.
 */
fun computeStatistics(
    issues: List<IssueWithState>,
    userStates: List<IssueUserState>,
    bookmarks: List<Bookmark>,
    sessions: List<ReadingSession>,
    now: Long,
    zone: ZoneId,
): Statistics {
    val inCatalogue = issues.mapTo(HashSet()) { it.issue.issueId }
    // Nummer utan katalogpost har inget känt sidantal. De kan bara bli lästa genom en manuell markering.
    val missing = userStates.filter { it.issueId !in inCatalogue }.map { readingProgress(it, null).status }
    val statuses = issues.map { it.progress.status }

    val years = issues.groupBy { Triple(it.issue.publicationId, it.issue.publicationTitle, it.issue.year) }
        .map { (key, items) ->
            YearProgress(
                publicationId = key.first,
                publicationTitle = key.second,
                year = key.third,
                issueCount = items.size,
                issuesRead = items.count { it.progress.status == ReadStatus.READ },
                issuesStarted = items.count { it.progress.status == ReadStatus.STARTED },
                pagesRead = items.sumOf { it.progress.pagesRead },
                pageCount = items.sumOf { it.pageCount ?: 0 },
            )
        }
        .sortedWith(compareBy({ it.year }, { it.publicationTitle }))

    return Statistics(
        issuesRead = statuses.count { it == ReadStatus.READ } + missing.count { it == ReadStatus.READ },
        issuesStarted = statuses.count { it == ReadStatus.STARTED } + missing.count { it == ReadStatus.STARTED },
        issuesUnread = statuses.count { it == ReadStatus.UNREAD },
        pagesRead = userStates.sumOf { SeenPages.decode(it.seenPages).count },
        readingSeconds = sessions.sumOf { it.durationSeconds.toLong() },
        sessionCount = sessions.size,
        activity = weeklyActivity(sessions, now, zone),
        years = years,
        mostReadYears = years.maxOfOrNull { it.pagesRead }?.takeIf { it > 0 }
            ?.let { most -> years.filter { it.pagesRead == most } }.orEmpty(),
        favorites = userStates.count { it.isFavorite },
        bookmarks = bookmarks.count { it.deletedAt == null },
    )
}

/** "42 h 18 min", "18 min", "45 s" eller "0 min". */
fun durationText(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 -> "$hours h $minutes min"
        minutes > 0 -> "$minutes min"
        seconds > 0 -> "$seconds s"
        else -> "0 min"
    }
}

/** "vecka 41", för skärmläsare och rubriker. ISO-vecka. */
fun weekLabel(start: LocalDate): String = "v. ${start.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)}"

/** "3 av 22 nummer lästa". */
fun yearIssuesText(year: YearProgress): String = "${year.issuesRead} av ${year.issueCount} nummer lästa"

/**
 * "Datormagazin 1988 · 2 sidor", eller "Datormagazin 1988, 1991 · 2 sidor" när flera årgångar
 * har lika många lästa sidor. Publikationens namn skrivs en gång. "–" när inget är läst.
 */
fun mostReadYearsText(years: List<YearProgress>): String {
    val pages = years.firstOrNull()?.pagesRead ?: return "–"
    val names = years.groupBy { it.publicationTitle }.entries
        .sortedBy { (_, items) -> items.minOf { it.year } }
        .joinToString(", ") { (title, items) -> "$title " + items.map { it.year }.sorted().joinToString(", ") }
    return "$names · $pages ${if (pages == 1) "sida" else "sidor"}"
}

/** "1 % sidor": andelen lästa sidor i årgången, med enhet så att den inte läses som andelen nummer. */
fun yearPageText(year: YearProgress): String = "${yearPagePercent(year)} % sidor"

/** Andelen lästa sidor i procent. Visar aldrig 0 när något är läst, och aldrig 100 förrän allt är läst. */
fun yearPagePercent(year: YearProgress): Int = when {
    year.pageCount <= 0 || year.pagesRead <= 0 -> 0
    year.pagesRead >= year.pageCount -> 100
    else -> ((year.pagesRead * 100L + year.pageCount / 2) / year.pageCount).toInt().coerceIn(1, 99)
}
