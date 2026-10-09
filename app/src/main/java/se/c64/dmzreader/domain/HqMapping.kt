package se.c64.dmzreader.domain

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Mappningen mellan PDF-sida och originalskanning (planens FAS 11, ADR 0009).
 * Ren logik utan Android-beroenden.
 *
 * Huvudregeln är att PDF-sida N är skanning N, med adressen ur scanPagePattern.
 * Nummer där det inte stämmer har en uttrycklig tabell i inventory (hqPageMap),
 * framtagen med bildjämförelse i toolchainen. Appen räknar aldrig fram en
 * mappning för sådana nummer: en sida som saknas i tabellen har ingen HQ.
 */

/** Skanningen som hör till en PDF-sida. */
data class HqScan(
    /** Adressen till filen, till exempel ftp://dmzarkivet.se/Original%20Scans/198601-Original/DMZ_1986-01-001.jpg. */
    val url: String,
    val fileName: String,
    /** Så många grader medurs ska skanningen vridas för att ligga som PDF-sidan: 0, 90, 180 eller 270. */
    val rotation: Int = 0,
)

/** Det ur inventory som bestämmer HQ för ett nummer. */
data class HqIssueInfo(
    val hqSource: String?,
    val scanPagePattern: String?,
    val scanPageCount: Int?,
    /** hqPageMap som JSON-text: {"1": "fil.jpg", "2": null}. null när numret följer huvudregeln. */
    val pageMapJson: String? = null,
    /** hqPageRotation som JSON-text: {"1": 90}. Bara sidor som ska vridas finns med. */
    val pageRotationJson: String? = null,
)

private val pagePlaceholder = Regex("""\{page:0(\d)d\}""")
private val mapSerializer = MapSerializer(String.serializer(), String.serializer().nullable)
private val rotationSerializer = MapSerializer(String.serializer(), Int.serializer())

/** Tolkar hqPageMap. Ger null om texten inte går att läsa: då har numret ingen HQ alls. */
internal fun parseHqPageMap(json: String): Map<Int, String?>? = try {
    Json.decodeFromString(mapSerializer, json).mapKeys { it.key.toInt() }
} catch (e: Exception) {
    null
}

internal fun parseHqPageRotation(json: String?): Map<Int, Int> = try {
    json?.let { text -> Json.decodeFromString(rotationSerializer, text).mapKeys { it.key.toInt() } }.orEmpty()
        .filterValues { it in setOf(90, 180, 270) }
} catch (e: Exception) {
    emptyMap()
}

/** Sant om numret har HQ över huvud taget. HQ-knappen är avstängd annars. */
fun hqSupported(info: HqIssueInfo): Boolean =
    info.hqSource == "scans" && info.scanPagePattern?.let { pagePlaceholder.containsMatchIn(it) } == true

/**
 * Skanningen för PDF-sidan [page], eller null när sidan inte har någon säker motsvarighet.
 *
 * [pdfPageCount] är sidantalet i den öppnade PDF:en. Utan hqPageMap gäller huvudregeln
 * bara när PDF:en har lika många sidor som det finns skanningar. Annars visas ingen HQ,
 * hellre än fel sida.
 */
fun hqScanFor(info: HqIssueInfo, page: Int, pdfPageCount: Int): HqScan? {
    if (!hqSupported(info) || page < 1) return null
    val pattern = info.scanPagePattern!!
    val rotation = parseHqPageRotation(info.pageRotationJson)[page] ?: 0
    if (info.pageMapJson != null) {
        val fileName = parseHqPageMap(info.pageMapJson)?.get(page) ?: return null
        // Filnamnet får inte kunna peka ut ur numrets katalog.
        if (fileName.isBlank() || '/' in fileName || '\\' in fileName || fileName.startsWith(".")) return null
        return HqScan(pattern.substringBeforeLast('/') + "/" + encodePathSegment(fileName), fileName, rotation)
    }
    if (info.scanPageCount == null || info.scanPageCount != pdfPageCount || page > info.scanPageCount) return null
    val url = pagePlaceholder.replace(pattern) { match -> page.toString().padStart(match.groupValues[1].toInt(), '0') }
    return HqScan(url, url.substringAfterLast('/'), rotation)
}

/** Procentkodar det som inte får stå i en adress. Filnamnen i arkivet är i praktiken ASCII utan mellanslag. */
private fun encodePathSegment(name: String): String = buildString {
    for (byte in name.toByteArray(Charsets.UTF_8)) {
        val char = byte.toInt().toChar()
        if (byte >= 0 && (char.isLetterOrDigit() || char in "-._~")) append(char) else append("%%%02X".format(byte.toInt() and 0xFF))
    }
}

/** Meddelandet på en sida som saknar skanning. */
const val HQ_PAGE_UNAVAILABLE = "HQ finns inte för den här sidan"
