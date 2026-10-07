package se.c64.dmzreader.domain

import se.c64.dmzreader.data.db.IssueEntity

/** Varifrån ett omslag hämtas. */
sealed interface CoverSource {
    /**
     * Färdig miniatyr som följer med appen. Den är redan vriden, beskuren och
     * skalad av toolchainen, så inga coverFlags tillämpas.
     */
    data class Asset(val path: String) : CoverSource {
        /** Adressen som bildladdaren förstår. */
        val uri: String get() = "file:///android_asset/$path"
    }

    /** Webbplatsens sidbild 1, exakt som adressen står i inventory, rättad enligt coverFlags. */
    data class Remote(val url: String, val transform: CoverTransform) : CoverSource
}

/**
 * Källorna för ett nummers omslag, i den ordning de ska prövas (ADR 0016):
 * inbyggd miniatyr, sedan coverUrl. Är listan tom, eller misslyckas alla,
 * visas platshållaren.
 *
 * coverAsset i ett arkivindex är bara en ledtråd: ett importerat index kan
 * peka på en miniatyr som inte följer med den här appversionen. Med
 * [bundledCovers] (miniatyrerna som finns i assets) används coverAsset bara om
 * filen finns. null betyder att det inte är känt, och då prövas den.
 */
fun coverSources(issue: IssueEntity, bundledCovers: Set<String>? = null): List<CoverSource> = buildList {
    issue.coverAsset?.takeIf { bundledCovers == null || it in bundledCovers }?.let { add(CoverSource.Asset(it)) }
    issue.coverUrl?.let { add(CoverSource.Remote(it, CoverTransform.fromFlags(issue.coverFlags))) }
}
