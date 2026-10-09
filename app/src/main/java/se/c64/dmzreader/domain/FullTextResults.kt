package se.c64.dmzreader.domain

import se.c64.dmzreader.data.db.IssueEntity
import se.c64.dmzreader.data.search.IndexedIssue
import se.c64.dmzreader.data.search.IndexedPage
import se.c64.dmzreader.data.search.RankedRow

/**
 * Fulltextsökningens resultat (planens E10, steg 2): träffarna filtreras,
 * grupperas per nummer och sorteras. Ren logik utan Android-beroenden.
 */

/** En träffsida. Lägre [score] är bättre (bm25). */
data class PageHit(val rowId: Long, val pageNumber: Int, val score: Double)

/** Alla träffar i ett nummer. */
data class IssueHits(
    val item: IssueWithState,
    /** Träffsidorna, de bästa först. */
    val pages: List<PageHit>,
    /** Sant när sökindexet byggdes av en annan version av numrets PDF än den arkivindexet anger. */
    val indexOutdated: Boolean,
) {
    val bestScore: Double get() = pages.first().score
    val top: List<PageHit> get() = pages.take(TOP_PAGES_PER_ISSUE)
}

enum class SearchSort(val label: String) {
    RELEVANCE("Relevans"),
    OLDEST("Äldst först"),
}

data class FullTextResults(
    /** Antal träffsidor efter filtren. */
    val totalPages: Int = 0,
    /** Numren med träffar, i vald ordning. */
    val groups: List<IssueHits> = emptyList(),
) {
    val totalIssues: Int get() = groups.size
    val isEmpty: Boolean get() = groups.isEmpty()
}

/** Så många sidor visas per nummer innan "Visa alla". */
const val TOP_PAGES_PER_ISSUE = 3

/** Så många nummer läggs till i listan åt gången. */
const val GROUP_PAGE_SIZE = 20

/** "1 sida i 1 nummer", "230 sidor i 85 nummer". */
fun hitSummaryText(pages: Int, issues: Int): String =
    "$pages ${if (pages == 1) "sida" else "sidor"} i $issues nummer"

const val INDEX_OUTDATED_TEXT = "Sökindexet gäller en äldre version av filen"
const val INDEX_OTHER_VERSION_TEXT = "Sökindexet är från en annan arkivversion."
const val NOT_IN_INDEX_TEXT = "Numret finns inte i sökindexet och går inte att söka i."

/**
 * Nummer där sökindexet byggdes av en annan PDF än den arkivindexet anger nu
 * (planens C7 och D2): storleken eller ETag skiljer sig. Saknas ett värde på
 * någon sida går det inte att jämföra, och då räknas det inte som en avvikelse.
 */
fun outdatedInIndex(indexed: List<IndexedIssue>, issues: List<IssueEntity>): Set<String> {
    val byId = issues.associateBy { it.issueId }
    return indexed.mapNotNullTo(HashSet()) { entry ->
        val issue = byId[entry.issueId] ?: return@mapNotNullTo null
        val sizeDiffers = entry.ocrPdfSize != null && issue.ocrPdfSize != null && entry.ocrPdfSize != issue.ocrPdfSize
        val etagDiffers = entry.ocrPdfEtag != null && issue.ocrPdfEtag != null && entry.ocrPdfEtag != issue.ocrPdfEtag
        entry.issueId.takeIf { sizeDiffers || etagDiffers }
    }
}

/**
 * Gör om rangordningen från sökindexet till det som visas.
 *
 * - Träffar i nummer som inte finns i det aktiva arkivindexet döljs (planens D3).
 * - Filtren gäller numret: publikation, år, favorit och nedladdat.
 * - Relevans: numren sorteras på sin bästa sida. Äldst först: på sortKey, och sidorna i sidordning.
 */
fun groupHits(
    ranked: List<RankedRow>,
    pages: Map<Long, IndexedPage>,
    issues: List<IssueWithState>,
    filters: SearchFilters,
    sort: SearchSort,
    outdated: Set<String> = emptySet(),
): FullTextResults {
    val byId = issues.filter { it.matchesFilters(filters) }.associateBy { it.issue.issueId }
    val perIssue = LinkedHashMap<String, MutableList<PageHit>>()
    var total = 0
    for (row in ranked) {
        val page = pages[row.rowId] ?: continue
        if (page.issueId !in byId) continue
        perIssue.getOrPut(page.issueId) { ArrayList() } += PageHit(row.rowId, page.pageNumber, row.score)
        total++
    }
    val groups = perIssue.map { (issueId, hits) ->
        val ordered = when (sort) {
            SearchSort.RELEVANCE -> hits.sortedWith(compareBy({ it.score }, { it.pageNumber }))
            SearchSort.OLDEST -> hits.sortedBy { it.pageNumber }
        }
        IssueHits(byId.getValue(issueId), ordered, issueId in outdated)
    }
    val sorted = when (sort) {
        SearchSort.RELEVANCE -> groups.sortedWith(compareBy({ it.pages.minOf { hit -> hit.score } }, { it.item.issue.sortKey }))
        SearchSort.OLDEST -> groups.sortedWith(compareBy({ it.item.issue.sortKey }, { it.item.issue.publicationId }))
    }
    return FullTextResults(total, sorted)
}

/** Träffsidorna i ett enda nummer, i sidordning. Används av "Sök i numret" och läsarens träffrad. */
fun hitsInIssue(ranked: List<RankedRow>, pages: Map<Long, IndexedPage>, issueId: String): List<PageHit> =
    ranked.mapNotNull { row ->
        pages[row.rowId]?.takeIf { it.issueId == issueId }?.let { PageHit(row.rowId, it.pageNumber, row.score) }
    }.sortedBy { it.pageNumber }

/** En bit av ett textutdrag. [hit] är sant för sökordet, som visas i accentfärg. */
data class SnippetPart(val text: String, val hit: Boolean)

/** Delar ett textutdrag från sökindexet vid markörerna [open] och [close]. */
fun splitSnippet(snippet: String, open: String, close: String): List<SnippetPart> {
    val parts = ArrayList<SnippetPart>()
    var rest = snippet
    while (rest.isNotEmpty()) {
        val start = rest.indexOf(open)
        if (start < 0) {
            parts += SnippetPart(rest.replace(close, ""), false)
            break
        }
        if (start > 0) parts += SnippetPart(rest.substring(0, start), false)
        val end = rest.indexOf(close, start + open.length)
        if (end < 0) {
            parts += SnippetPart(rest.substring(start + open.length), true)
            break
        }
        parts += SnippetPart(rest.substring(start + open.length, end), true)
        rest = rest.substring(end + close.length)
    }
    return parts.filter { it.text.isNotEmpty() }
}

/** "högtalare · träff 2 av 5". [index] räknas från 0. */
fun hitNavigationText(query: String, index: Int, count: Int): String =
    if (count == 0) "$query · inga träffar i numret" else "$query · träff ${index + 1} av $count"
