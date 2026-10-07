package se.c64.dmzreader.domain

import se.c64.dmzreader.data.db.DownloadState
import se.c64.dmzreader.data.db.IssueEntity
import se.c64.dmzreader.data.db.IssueLocalState
import se.c64.dmzreader.data.db.IssueUserState
import se.c64.dmzreader.data.db.ReadStatusOverride

/** Lässtatus och läsprogress (planens C10). Ren logik utan Android-beroenden. */

enum class ReadStatus { UNREAD, STARTED, READ }

/** Sidantalet som gäller: katalogens om det finns, annars det telefonen har mätt (planens C3). */
fun effectivePageCount(issuePageCount: Int?, observedPageCount: Int?): Int? = issuePageCount ?: observedPageCount

/**
 * Hur mycket av ett nummer som är läst.
 *
 * [status] tar hänsyn till en manuell markering, [automaticStatus] gör det
 * inte. [fraction] och [percent] är null när sidantalet är okänt.
 */
data class ReadingProgress(
    val status: ReadStatus,
    val automaticStatus: ReadStatus,
    val override: ReadStatusOverride?,
    val pagesRead: Int,
    val pageCount: Int?,
) {
    val fraction: Float?
        get() = pageCount?.takeIf { it > 0 }?.let { (pagesRead.toFloat() / it).coerceIn(0f, 1f) }

    /** Avrundad procent. Visar aldrig 0 när något är läst, och aldrig 100 förrän allt är läst. */
    val percent: Int?
        get() {
            val total = pageCount?.takeIf { it > 0 } ?: return null
            return when {
                pagesRead <= 0 -> 0
                pagesRead >= total -> 100
                else -> ((pagesRead * 100 + total / 2) / total).coerceIn(1, 99)
            }
        }
}

/** Från och med den här andelen lästa sidor räknas ett nummer som läst. */
const val READ_THRESHOLD_PERCENT = 90

fun readingProgress(state: IssueUserState?, pageCount: Int?): ReadingProgress {
    val seen = SeenPages.decode(state?.seenPages)
    val total = pageCount?.takeIf { it > 0 }
    val pagesRead = if (total != null) seen.countUpTo(total) else seen.count
    val automatic = when {
        seen.isEmpty -> ReadStatus.UNREAD
        total != null && pagesRead * 100 >= total * READ_THRESHOLD_PERCENT -> ReadStatus.READ
        else -> ReadStatus.STARTED
    }
    val status = when (state?.readStatusOverride) {
        ReadStatusOverride.READ -> ReadStatus.READ
        ReadStatusOverride.UNREAD -> ReadStatus.UNREAD
        null -> automatic
    }
    return ReadingProgress(status, automatic, state?.readStatusOverride, pagesRead, total)
}

/** Texten för lässtatus i nummervyn: "Oläst", "Påbörjad · 25 %", "Påbörjad" eller "Läst". */
fun readStatusText(progress: ReadingProgress): String {
    val base = when (progress.status) {
        ReadStatus.UNREAD -> "Oläst"
        ReadStatus.STARTED -> "Påbörjad"
        ReadStatus.READ -> "Läst"
    }
    val percent = progress.percent?.takeIf { progress.pagesRead > 0 }?.let { " · $it %" }.orEmpty()
    val manual = if (progress.override != null) " (markerad)" else ""
    return base + percent + manual
}

// --- Ändringar av IssueUserState. Ingen av dem rör isFavorite. ---

/** Numret öppnades i läsaren. */
fun IssueUserState.opened(now: Long): IssueUserState =
    copy(firstOpenedAt = firstOpenedAt ?: now, lastOpenedAt = now)

/**
 * [pages] har räknats som lästa. maxPageReached följer den högsta lästa sidan.
 * Med [clearUnread] tas en manuell markering som oläst bort (planens C10).
 */
fun IssueUserState.withPagesRead(
    pages: Collection<Int>,
    pageCount: Int?,
    now: Long,
    clearUnread: Boolean,
): IssueUserState {
    val seen = SeenPages.decode(seenPages) + pages
    val next = copy(
        seenPages = seen.encode(),
        // Ett äldre värde utan seenPages sänks aldrig.
        maxPageReached = listOfNotNull(maxPageReached, seen.maxPage).maxOrNull(),
        readStatusOverride = readStatusOverride.takeUnless { clearUnread && it == ReadStatusOverride.UNREAD },
    )
    return next.completedIfRead(pageCount, now)
}

/**
 * Manuell markering: READ, UNREAD eller null för automatisk status. Ändrar
 * aldrig seenPages, lastPage eller maxPageReached.
 */
fun IssueUserState.withOverride(override: ReadStatusOverride?, pageCount: Int?, now: Long): IssueUserState =
    copy(readStatusOverride = override).completedIfRead(pageCount, now)

/** completedAt sätts första gången numret blir läst, automatiskt eller manuellt. */
private fun IssueUserState.completedIfRead(pageCount: Int?, now: Long): IssueUserState =
    if (completedAt == null && readingProgress(this, pageCount).status == ReadStatus.READ) copy(completedAt = now) else this

// --- Biblioteket ---

/** Ett nummer med användarens läsdata, telefonens lokala status och det sidantal som gäller. */
data class IssueWithState(
    val issue: IssueEntity,
    val state: IssueUserState?,
    val pageCount: Int?,
    val local: IssueLocalState? = null,
) {
    val progress: ReadingProgress = readingProgress(state, pageCount)
    val downloadState: DownloadState get() = local?.downloadState ?: DownloadState.NONE

    /** Sant när numret är nedladdat och kan läsas utan nät. */
    val isDownloaded: Boolean get() = downloadState == DownloadState.DOWNLOADED

    /**
     * Sant när arkivindexet uppger en annan version av PDF:en än den som är
     * nedladdad (planens C7). Kopian finns kvar och går att läsa; den raderas aldrig automatiskt.
     */
    val downloadOutdated: Boolean get() = downloadIsOutdated(issue, local)

    /** Sant när en uppdatering av den nedladdade kopian är begärd och väntar eller pågår. */
    val updateRequested: Boolean get() = isDownloaded && local?.downloadQueuedAt != null
}

fun downloadIsOutdated(issue: IssueEntity, local: IssueLocalState?): Boolean {
    if (local?.downloadState != DownloadState.DOWNLOADED) return false
    val downloaded = local.observedPdfVersion() ?: PdfVersion(local.downloadedBytes, null, null)
    return issue.pdfVersion().differsFrom(downloaded)
}

const val DOWNLOAD_OUTDATED_TEXT = "Arkivfilen har uppdaterats. Din nedladdade kopia är äldre."

/** "Nedladdat" i biblioteket: de nedladdade numren, senast nedladdade först. */
fun downloadedIssues(items: List<IssueWithState>): List<IssueWithState> =
    items.filter { it.isDownloaded }.sortedByDescending { it.local?.downloadedAt ?: 0 }

/** Texten under "Sida X av Y" på Fortsätt läsa-kortet: "7 av 28 lästa · 25 %". null utan känt sidantal. */
fun readPagesText(progress: ReadingProgress): String? {
    val total = progress.pageCount ?: return null
    return "${progress.pagesRead} av $total lästa · ${progress.percent ?: 0} %"
}

/**
 * Numret för "Fortsätt läsa": det senast öppnade som är påbörjat. Nummer som
 * är lästa eller markerade som olästa ingår inte.
 */
fun continueReadingIssue(items: List<IssueWithState>): IssueWithState? =
    items.filter { it.progress.status == ReadStatus.STARTED && it.state?.lastPage != null }
        .maxByOrNull { it.state?.lastOpenedAt ?: 0 }

const val RECENTLY_READ_LIMIT = 12

/** "Senast lästa": de senast öppnade numren, utom det som redan visas under "Fortsätt läsa". */
fun recentlyReadIssues(items: List<IssueWithState>, excludeIssueId: String?): List<IssueWithState> =
    items.filter { it.state?.lastOpenedAt != null && it.issue.issueId != excludeIssueId }
        .sortedByDescending { it.state?.lastOpenedAt }
        .take(RECENTLY_READ_LIMIT)

/** Antal lästa nummer per årgång, med nyckeln (publicationId, year). */
fun readCountsByYear(items: List<IssueWithState>): Map<Pair<String, Int>, Int> =
    items.filter { it.progress.status == ReadStatus.READ }
        .groupingBy { it.issue.publicationId to it.issue.year }
        .eachCount()

enum class YearFilter(val label: String) {
    ALL("Alla"), UNREAD("Olästa"), STARTED("Påbörjade"), READ("Lästa"), OFFLINE("Offline");

    fun matches(item: IssueWithState): Boolean = when (this) {
        ALL -> true
        UNREAD -> item.progress.status == ReadStatus.UNREAD
        STARTED -> item.progress.status == ReadStatus.STARTED
        READ -> item.progress.status == ReadStatus.READ
        OFFLINE -> item.isDownloaded
    }
}
