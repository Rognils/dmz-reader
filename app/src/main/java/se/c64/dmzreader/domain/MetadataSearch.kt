package se.c64.dmzreader.domain

import se.c64.dmzreader.data.db.Bookmark
import se.c64.dmzreader.data.db.IssueEntity

/**
 * Metadata-sökning (planens E10, steg 1): år, nummer, publikation och
 * bokmärkenas titlar och anteckningar. Ingen sökning i tidningarnas text; den
 * kommer med search.db i FAS 9. Ren logik utan Android-beroenden.
 */

/** En sökfråga uppdelad i det den betyder. */
data class MetadataQuery(
    /** Årgång, till exempel 1989. "89" tolkas som 1989. */
    val year: Int? = null,
    /** Nummer inom årgången, till exempel 12. */
    val number: Int? = null,
    /** Övriga ord, i gemener. Alla ska finnas i träffen. */
    val words: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = year == null && number == null && words.isEmpty()
}

private val separators = Regex("""[\s/.,;:]+""")
private val ignoredWords = setOf("nr", "nummer", "år", "årgång")

/** Tidskrifterna i arkivet kom ut 1986–1995. Tvåsiffriga år tolkas som 1900-tal. */
private fun expandYear(twoDigits: Int) = 1900 + twoDigits

/**
 * Tolkar söktexten.
 *
 * - "1989" är ett år.
 * - "1989 12", "1989/12", "89/12" och "89 12" är år och nummer.
 * - "nr 12" och "12" är ett nummer, i alla årgångar.
 * - "89" ensamt är ett år: inget nummer är så högt.
 * - Allt annat är ord som söks i publikation, nummer och bokmärken.
 */
fun parseMetadataQuery(text: String): MetadataQuery {
    val tokens = text.trim().lowercase().split(separators).filter { it.isNotEmpty() && it !in ignoredWords }
    val numbers = tokens.filter { it.all(Char::isDigit) && it.length <= 4 }.map { it.toInt() to it.length }
    val words = tokens.filterNot { it.all(Char::isDigit) && it.length <= 4 }

    var year: Int? = null
    var number: Int? = null
    when {
        numbers.isEmpty() -> Unit
        numbers.size == 1 -> {
            val (value, digits) = numbers[0]
            when {
                digits == 4 -> year = value
                digits == 3 -> return MetadataQuery(words = words + tokens.filter { it.all(Char::isDigit) })
                value in 1..MAX_ISSUE_NUMBER -> number = value
                else -> year = expandYear(value)
            }
        }
        else -> {
            // Två tal: det första är året och det andra numret.
            val (first, firstDigits) = numbers[0]
            year = if (firstDigits == 4) first else expandYear(first)
            number = numbers[1].first
        }
    }
    return MetadataQuery(year, number, words)
}

/** Inget nummer i arkivet är högre än 22. Ett ensamt tvåsiffrigt tal över gränsen är ett år. */
private const val MAX_ISSUE_NUMBER = 31

private val numbersInIssueId = Regex("""-\d{4}-(\d{2})(?:-(\d{2}))?""")

/** Numren ett issueId står för: "datormagazin-1988-16-17" ger 16 och 17, "…-1987-10b" ger 10. */
fun issueNumbers(issue: IssueEntity): List<Int> =
    numbersInIssueId.find(issue.issueId)?.groupValues?.drop(1)?.filter { it.isNotEmpty() }?.map { it.toInt() }.orEmpty()

/** Filtren i sökvyn. De gäller både metadata-sökningen och fulltextsökningen. */
data class SearchFilters(
    val favoritesOnly: Boolean = false,
    val downloadedOnly: Boolean = false,
    /** Bara den här publikationen. null betyder alla. */
    val publicationId: String? = null,
    /** Första och sista året som tas med. null betyder ingen gräns. */
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
) {
    val hasYears: Boolean get() = yearFrom != null || yearTo != null
    val any: Boolean get() = favoritesOnly || downloadedOnly || publicationId != null || hasYears

    /** Sant för de filter som ensamma ska lista nummer i metadata-sökningen. */
    val listsIssues: Boolean get() = favoritesOnly || downloadedOnly
}

/** "1993–1995", "från 1993", "till 1989" eller "alla år". */
fun yearFilterText(filters: SearchFilters): String = when {
    filters.yearFrom != null && filters.yearTo != null ->
        if (filters.yearFrom == filters.yearTo) "${filters.yearFrom}" else "${filters.yearFrom}–${filters.yearTo}"
    filters.yearFrom != null -> "från ${filters.yearFrom}"
    filters.yearTo != null -> "till ${filters.yearTo}"
    else -> "alla år"
}

/** En träff på ett bokmärke, med numret det hör till. */
data class BookmarkHit(val bookmark: Bookmark, val issue: IssueEntity)

data class SearchResults(
    val issues: List<IssueWithState> = emptyList(),
    val bookmarks: List<BookmarkHit> = emptyList(),
    /** Sant om det fanns fler nummer än som visas. */
    val truncated: Boolean = false,
) {
    val isEmpty: Boolean get() = issues.isEmpty() && bookmarks.isEmpty()
}

const val MAX_ISSUE_HITS = 60

internal fun IssueWithState.matchesFilters(filters: SearchFilters): Boolean =
    (!filters.favoritesOnly || state?.isFavorite == true) &&
        (!filters.downloadedOnly || isDownloaded) &&
        (filters.publicationId == null || issue.publicationId == filters.publicationId) &&
        (filters.yearFrom == null || issue.year >= filters.yearFrom) &&
        (filters.yearTo == null || issue.year <= filters.yearTo)

private fun IssueEntity.matchesYearAndNumber(query: MetadataQuery): Boolean =
    (query.year == null || year == query.year) && (query.number == null || query.number in issueNumbers(this))

private fun IssueEntity.searchText(): String = "$publicationTitle $publicationId $year $issueLabel".lowercase()

/**
 * Söker bland numren och bokmärkena.
 *
 * - Nummer: år och nummer ska stämma, och varje ord ska finnas i publikationens
 *   namn eller numrets beteckning.
 * - Bokmärken: träffar ges bara när frågan innehåller ord. Varje ord ska finnas
 *   i titeln eller anteckningen, och år och nummer gäller bokmärkets nummer.
 * - En tom fråga ger ingenting, om inte ett snabbfilter är valt: då listas alla
 *   favoriter eller nedladdade nummer.
 */
fun searchMetadata(
    query: MetadataQuery,
    filters: SearchFilters,
    issues: List<IssueWithState>,
    bookmarks: List<Bookmark>,
): SearchResults {
    if (query.isEmpty && !filters.listsIssues) return SearchResults()
    val candidates = issues.filter { it.matchesFilters(filters) && it.issue.matchesYearAndNumber(query) }

    val issueHits = candidates.filter { item ->
        val text = item.issue.searchText()
        query.words.all { it in text }
    }

    val bookmarkHits = if (query.words.isEmpty()) {
        emptyList()
    } else {
        val byId = candidates.associateBy { it.issue.issueId }
        newestFirst(bookmarks).mapNotNull { bookmark ->
            val item = byId[bookmark.issueId] ?: return@mapNotNull null
            val text = "${bookmark.title.orEmpty()} ${bookmark.note.orEmpty()}".lowercase()
            if (query.words.all { it in text }) BookmarkHit(bookmark, item.issue) else null
        }
    }
    return SearchResults(
        issues = issueHits.take(MAX_ISSUE_HITS),
        bookmarks = bookmarkHits,
        truncated = issueHits.size > MAX_ISSUE_HITS,
    )
}
