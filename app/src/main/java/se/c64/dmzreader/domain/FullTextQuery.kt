package se.c64.dmzreader.domain

/**
 * Frågelagret för fulltextsökningen (planens E10, steg 2): gör om det användaren
 * skriver till ett FTS5-uttryck som aldrig kan ge syntaxfel. Ren logik utan
 * Android-beroenden.
 *
 * Regler:
 * - Varje sökord sätts inom citattecken. Flera ord betyder OCH.
 * - Citattecken från användaren ger en frassökning.
 * - Prefixsökning görs bara med en uttrycklig stjärna, och kräver minst
 *   [MIN_PREFIX_LENGTH] tecken före stjärnan. Annars tas stjärnan bort.
 * - "C64", "C-64" och "C 64" söks som alternativ till varandra.
 * - Allt annat som har en betydelse i FTS5 (AND, OR, NOT, NEAR, parenteser,
 *   kolon, plus, minus, cirkumflex) blir vanlig text eller försvinner.
 */

/** Sökningen börjar när söktexten har så här många tecken. */
const val MIN_QUERY_LENGTH = 2

/** Minst så här många tecken före stjärnan i en prefixsökning. */
const val MIN_PREFIX_LENGTH = 3

/** En tolkad fråga. [match] ges till FTS5 som den är. */
data class FullTextQuery(
    /** FTS5-uttrycket, till exempel `"högtalare" AND ("c64" OR "c 64")`. */
    val match: String,
    /** Det användaren sökte på, städat. Visas i läsarens träffrad. */
    val display: String,
)

private sealed interface Term {
    val fts: String
}

private data class Word(val text: String, val prefix: Boolean) : Term {
    override val fts: String get() = quote(text) + if (prefix) "*" else ""
}

private data class Phrase(val text: String) : Term {
    override val fts: String get() = quote(text)
}

/** Ett ord och dess skrivsätt: "c64" och "c 64" (som även täcker "C-64"). */
private data class Alternatives(val joined: String, val spaced: String) : Term {
    override val fts: String get() = "(${quote(joined)} OR ${quote(spaced)})"
}

/** Citattecken i texten dubblas, vilket är FTS5:s sätt att skriva ett citattecken i en sträng. */
private fun quote(text: String) = "\"" + text.replace("\"", "\"\"") + "\""

/** Tecken som tokenizern (unicode61) räknar som en del av ett ord. */
private fun Char.isTokenChar() = isLetterOrDigit()

private fun hasToken(text: String) = text.any { it.isTokenChar() }

/** Olika citattecken som tangentbord och kopierad text kan ge. */
private const val QUOTE_CHARS = "\"“”„«»"

/** Bokstäver följda av siffror, med eller utan bindestreck emellan: C64, C-64, A1200, VIC-20. */
private val modelName = Regex("""^(\p{L}{1,4})-?(\d{1,5})$""")
private val lettersOnly = Regex("""^\p{L}{1,3}$""")
private val digitsOnly = Regex("""^\d{1,5}$""")

/**
 * Tolkar söktexten. Ger null när den inte innehåller något att söka på:
 * för kort, tom, eller bara skiljetecken.
 */
fun parseFullTextQuery(input: String): FullTextQuery? {
    // Styrtecken blir mellanslag: ett NUL-tecken skulle annars klippa av uttrycket mitt i ett
    // citattecken. Mjuka bindestreck tas bort, som i sökindexet.
    val text = input.replace("\u00ad", "").map { if (it.isISOControl()) ' ' else it }.joinToString("").trim()
    if (text.length < MIN_QUERY_LENGTH) return null

    // 1. Dela i fraser (inom citattecken) och lösa ord.
    val raw = ArrayList<Pair<String, Boolean>>() // text, är fras
    val current = StringBuilder()
    var inPhrase = false
    fun flush(phrase: Boolean) {
        if (current.isNotBlank()) raw += current.toString().trim() to phrase
        current.clear()
    }
    for (char in text) {
        when {
            char in QUOTE_CHARS -> {
                flush(inPhrase)
                inPhrase = !inPhrase
            }
            char.isWhitespace() && !inPhrase -> flush(false)
            else -> current.append(char)
        }
    }
    // Ett citattecken som aldrig stängs: resten räknas som en fras.
    flush(inPhrase)

    // 2. Gör om till termer.
    val terms = ArrayList<Term>()
    var index = 0
    while (index < raw.size) {
        val (piece, isPhrase) = raw[index]
        index++
        if (!hasToken(piece)) continue
        if (isPhrase) {
            val model = modelName.matchEntire(piece.replace(' ', '-'))
            terms += if (model != null && piece.count { it == ' ' } <= 1) {
                Alternatives(model.groupValues[1] + model.groupValues[2], model.groupValues[1] + " " + model.groupValues[2])
            } else {
                Phrase(piece)
            }
            continue
        }
        val wantsPrefix = piece.endsWith("*")
        val word = piece.trimEnd('*')
        if (!hasToken(word)) continue
        val model = modelName.matchEntire(word)
        val next = raw.getOrNull(index)
        when {
            // "C64" och "C-64".
            model != null && !wantsPrefix ->
                terms += Alternatives(model.groupValues[1] + model.groupValues[2], model.groupValues[1] + " " + model.groupValues[2])
            // "C 64" skrivet som två ord.
            !wantsPrefix && lettersOnly.matches(word) && next != null && !next.second && digitsOnly.matches(next.first) -> {
                terms += Alternatives(word + next.first, word + " " + next.first)
                index++
            }
            else -> {
                val tokenChars = word.count { it.isTokenChar() }
                // En stjärna efter ett ord med skiljetecken i ("cd-ro*") gäller bara sista delen i FTS5.
                // Det tillåts bara när hela ordet är ett enda token.
                val prefix = wantsPrefix && tokenChars >= MIN_PREFIX_LENGTH && word.all { it.isTokenChar() }
                terms += Word(word, prefix)
            }
        }
    }
    if (terms.isEmpty()) return null
    val display = raw.filter { hasToken(it.first) }.joinToString(" ") { (piece, phrase) -> if (phrase) "\"$piece\"" else piece }
    return FullTextQuery(match = terms.joinToString(" AND ") { it.fts }, display = display)
}
