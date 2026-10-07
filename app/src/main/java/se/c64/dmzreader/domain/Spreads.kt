package se.c64.dmzreader.domain

/**
 * Uppslagsparning i liggande läge. Sidnummer är PDF-sidindex med start på 1.
 *
 * [offset] 0 är standardregeln: sida 1 ensam, därefter 2–3, 4–5 … Med offset 1
 * börjar parningen ett steg tidigare: 1–2, 3–4 … Det finns bara de två
 * möjligheterna, så knappen "förskjut parningen" växlar mellan dem.
 */
data class Spread(val left: Int, val right: Int?) {
    val pages: List<Int> get() = listOfNotNull(left, right)
    operator fun contains(page: Int): Boolean = page == left || page == right
}

fun normalizeSpreadOffset(offset: Int?): Int = ((offset ?: 0) % 2 + 2) % 2

/**
 * Vilken parning som gäller: katalogens värde först, sedan det användaren har
 * valt på telefonen, annars standardregeln.
 */
fun effectiveSpreadOffset(issueSpreadOffset: Int?, detectedSpreadOffset: Int?): Int =
    normalizeSpreadOffset(issueSpreadOffset ?: detectedSpreadOffset)

/** Alla uppslag i ett nummer med [pageCount] sidor. */
fun spreadsFor(pageCount: Int, offset: Int): List<Spread> {
    if (pageCount <= 0) return emptyList()
    val spreads = ArrayList<Spread>()
    var page = 1
    if (normalizeSpreadOffset(offset) == 0) {
        spreads += Spread(1, null)
        page = 2
    }
    while (page <= pageCount) {
        spreads += Spread(page, (page + 1).takeIf { it <= pageCount })
        page += 2
    }
    return spreads
}

/** Index för uppslaget som innehåller [page]. */
fun spreadIndexForPage(page: Int, pageCount: Int, offset: Int): Int {
    val clamped = page.coerceIn(1, pageCount.coerceAtLeast(1))
    return if (normalizeSpreadOffset(offset) == 0) clamped / 2 else (clamped - 1) / 2
}

/**
 * Läspositionen i läsaren, oberoende av om sidor visas en och en eller i
 * uppslag. [page] är sidan användaren senast valde, och den behålls vid
 * rotation: stående sida 7 → liggande uppslag 6–7 → stående sida 7.
 */
data class ReaderPosition(val page: Int) {

    /** Vilken skärm (sida eller uppslag) som ska visas. */
    fun screenIndex(landscape: Boolean, pageCount: Int, offset: Int): Int =
        if (landscape) spreadIndexForPage(page, pageCount, offset) else (page - 1).coerceIn(0, (pageCount - 1).coerceAtLeast(0))

    /**
     * Ny position när användaren har bläddrat till skärm [screenIndex].
     * Ligger den gamla sidan redan på den skärmen ändras ingenting, så att en
     * rotation fram och tillbaka inte flyttar läspositionen.
     */
    fun movedTo(screenIndex: Int, landscape: Boolean, pageCount: Int, offset: Int): ReaderPosition {
        if (pageCount <= 0) return this
        if (!landscape) return ReaderPosition((screenIndex + 1).coerceIn(1, pageCount))
        val spreads = spreadsFor(pageCount, offset)
        val spread = spreads[screenIndex.coerceIn(0, spreads.lastIndex)]
        return if (page in spread) this else ReaderPosition(spread.left)
    }
}
