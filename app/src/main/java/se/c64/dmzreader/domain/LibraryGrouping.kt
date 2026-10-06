package se.c64.dmzreader.domain

import se.c64.dmzreader.data.db.YearCount

/** En publikation med sina årgångar, för biblioteksvyn. */
data class PublicationYears(
    val publicationId: String,
    val publicationTitle: String,
    val issueCount: Int,
    val years: List<YearCount>,
)

/** Grupperar årgångarna per publikation, sorterade på titel och sedan år. */
fun groupByPublication(yearCounts: List<YearCount>): List<PublicationYears> =
    yearCounts.groupBy { it.publicationId }
        .map { (publicationId, years) ->
            PublicationYears(
                publicationId = publicationId,
                publicationTitle = years.first().publicationTitle,
                issueCount = years.sumOf { it.issueCount },
                years = years.sortedBy { it.year },
            )
        }
        .sortedWith(compareBy({ it.publicationTitle }, { it.publicationId }))

/**
 * Vilken publikation som ska visas: den sparade om den finns kvar i katalogen,
 * annars den första.
 */
fun selectPublication(publications: List<PublicationYears>, savedPublicationId: String?): PublicationYears? =
    publications.firstOrNull { it.publicationId == savedPublicationId } ?: publications.firstOrNull()
