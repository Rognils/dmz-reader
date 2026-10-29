package se.c64.dmzreader.data.search

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import se.c64.dmzreader.BuildConfig

/**
 * Manifestet search-index.json, som ligger bredvid sökindexet i varje GitHub-release.
 * Det görs av tools/make_search_index_manifest.py.
 */
data class SearchIndexManifest(
    val manifestVersion: Int,
    /** Releasen som filen hämtas ur. Filen hämtas alltid från den här releasen, aldrig via latest. */
    val releaseTag: String,
    val fileName: String,
    val size: Long,
    val sha256: String,
    val searchSchemaVersion: String,
    val inventoryDatasetVersion: String,
    val normalizerVersion: String,
    val privacyFilterVersion: String,
    /** Lägsta versionCode som kan använda indexet. */
    val minAppVersionCode: Int,
) {
    /** "91 MB". */
    val sizeText: String get() = "${((size + 500_000) / 1_000_000).coerceAtLeast(1)} MB"
}

/** Utfallet av att läsa manifestet. */
sealed interface ManifestResult {
    data class Ok(val manifest: SearchIndexManifest) : ManifestResult

    /** Manifestet går att läsa, men indexet passar inte den här appen. Ingenting hämtas. */
    data class Unsupported(val message: String) : ManifestResult

    /** GitHub gick inte att nå, eller manifestet saknas. */
    data class Unreachable(val message: String) : ManifestResult
}

const val SUPPORTED_MANIFEST_VERSION = 1

/** Feltexten när GitHub inte går att nå. Den nämner inte arkivet: sökindexet hämtas aldrig därifrån. */
const val GITHUB_UNREACHABLE_MESSAGE = "Det gick inte att hämta sökindexet från GitHub. Försök igen senare."

private const val UNREADABLE_MESSAGE =
    "Uppgifterna om sökindexet på GitHub går inte att läsa. Ingenting har hämtats. Du kan importera en fil manuellt."

private val SAFE_NAME = Regex("""[A-Za-z0-9][A-Za-z0-9._-]{0,127}""")
private val SHA256 = Regex("""[0-9a-f]{64}""")
private const val MAX_INDEX_BYTES = 2_000_000_000L

/**
 * Tolkar och prövar manifestet. Ett okänt format, ett schema som appen inte stöder eller en
 * för gammal app ger ett begripligt besked, och då hämtas ingenting.
 */
fun parseSearchIndexManifest(
    text: String,
    appVersionCode: Int,
    supportedSchemaVersion: String = SearchIndexManager.SUPPORTED_SCHEMA_VERSION,
): ManifestResult {
    val root: JsonObject = try {
        Json.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
        return ManifestResult.Unsupported(UNREADABLE_MESSAGE)
    }
    fun primitive(name: String) = root[name] as? JsonPrimitive
    fun string(name: String) = primitive(name)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
    fun int(name: String) = primitive(name)?.takeIf { !it.isString }?.intOrNull
    fun long(name: String) = primitive(name)?.takeIf { !it.isString }?.longOrNull

    val manifestVersion = int("manifestVersion") ?: return ManifestResult.Unsupported(UNREADABLE_MESSAGE)
    if (manifestVersion != SUPPORTED_MANIFEST_VERSION) {
        return ManifestResult.Unsupported(
            "Sökindexet på GitHub har ett format som den här versionen av appen inte känner till " +
                "(manifestVersion $manifestVersion). Uppdatera appen för att hämta det. Ingenting har hämtats.",
        )
    }
    val manifest = SearchIndexManifest(
        manifestVersion = manifestVersion,
        releaseTag = string("releaseTag")?.takeIf { SAFE_NAME.matches(it) } ?: return ManifestResult.Unsupported(UNREADABLE_MESSAGE),
        fileName = string("fileName")?.takeIf { SAFE_NAME.matches(it) && it.endsWith(".db") }
            ?: return ManifestResult.Unsupported(UNREADABLE_MESSAGE),
        size = long("size")?.takeIf { it in 1..MAX_INDEX_BYTES } ?: return ManifestResult.Unsupported(UNREADABLE_MESSAGE),
        sha256 = string("sha256")?.lowercase()?.takeIf { SHA256.matches(it) } ?: return ManifestResult.Unsupported(UNREADABLE_MESSAGE),
        searchSchemaVersion = string("searchSchemaVersion") ?: return ManifestResult.Unsupported(UNREADABLE_MESSAGE),
        inventoryDatasetVersion = string("inventoryDatasetVersion") ?: return ManifestResult.Unsupported(UNREADABLE_MESSAGE),
        normalizerVersion = string("normalizerVersion") ?: return ManifestResult.Unsupported(UNREADABLE_MESSAGE),
        privacyFilterVersion = string("privacyFilterVersion") ?: return ManifestResult.Unsupported(UNREADABLE_MESSAGE),
        minAppVersionCode = int("minAppVersionCode") ?: return ManifestResult.Unsupported(UNREADABLE_MESSAGE),
    )
    if (manifest.searchSchemaVersion != supportedSchemaVersion) {
        return ManifestResult.Unsupported(
            "Sökindexet på GitHub har searchSchemaVersion ${manifest.searchSchemaVersion}, som den här versionen av appen " +
                "inte stöder. Uppdatera appen för att hämta det. Ingenting har hämtats.",
        )
    }
    if (appVersionCode < manifest.minAppVersionCode) {
        return ManifestResult.Unsupported(
            "Sökindexet på GitHub kräver en nyare version av appen. Uppdatera appen för att hämta det. Ingenting har hämtats.",
        )
    }
    return ManifestResult.Ok(manifest)
}

/**
 * Var manifestet och sökindexet hämtas: GitHub-releaserna för repot. Basadressen finns bara här.
 *
 * Manifestet hämtas via latest. Själva filen hämtas från den versionsspecifika adressen
 * …/download/<releaseTag>/<fileName>, så att en ny release mitt i en nedladdning inte kan
 * blanda ihop filer.
 *
 * I debug-bygget kan basen bytas mot en lokal testserver ([testBase]), och manifestet kan
 * hämtas från en valfri adress, till exempel en prerelease ([manifestOverride]). Filen hämtas då
 * från samma bas som manifestet: …/releases/download/v1.0.0/search-index.json och
 * …/releases/latest/download/search-index.json ger basen …/releases.
 */
class SearchIndexSource(
    private val manifestOverride: () -> HttpUrl? = { null },
) {
    private val override: HttpUrl? get() = if (BuildConfig.DEBUG) manifestOverride() else null

    private val base: HttpUrl get() = testBase ?: override?.let(::releasesBaseOf) ?: RELEASES

    val manifestUrl: HttpUrl
        get() = testBase?.let { manifestUrlFor(it) } ?: override ?: manifestUrlFor(base)

    fun fileUrl(manifest: SearchIndexManifest): HttpUrl = base.newBuilder()
        .addPathSegment("download")
        .addPathSegment(manifest.releaseTag)
        .addPathSegment(manifest.fileName)
        .build()

    companion object {
        val RELEASES: HttpUrl = "https://github.com/Rognils/dmz-reader/releases".toHttpUrl()
        const val MANIFEST_NAME = "search-index.json"

        /** Basen …/releases i en manifestadress, eller GitHubs om adressen inte har den formen. */
        fun releasesBaseOf(manifest: HttpUrl): HttpUrl {
            val segments = manifest.pathSegments
            val at = segments.lastIndexOf("releases").takeIf { it >= 0 && segments.size - it in 4..4 } ?: return RELEASES
            return manifest.newBuilder().query(null).encodedPath("/" + segments.take(at + 1).joinToString("/")).build()
        }

        fun manifestUrlFor(base: HttpUrl): HttpUrl =
            base.newBuilder().addPathSegment("latest").addPathSegment("download").addPathSegment(MANIFEST_NAME).build()

        /** En lokal testserver i stället för GitHub, med samma sökvägar. Bara i debug-bygget och i testerna. */
        @Volatile
        var testBase: HttpUrl? = null
            set(value) {
                field = value.takeIf { BuildConfig.DEBUG }
            }
    }
}
