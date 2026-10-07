package se.c64.dmzreader.domain

import java.util.Base64

/**
 * Sidorna användaren har läst i ett nummer, som en bitmängd (planens C4).
 *
 * Lagringsformatet är Base64 (standardalfabetet, med utfyllnad) av en bytevektor
 * där sida p, PDF-sidindex med start på 1, är bit (p − 1) mod 8 i byte
 * (p − 1) div 8, med minst signifikanta biten först. Avslutande nollbyte tas
 * bort, så att samma mängd alltid ger samma text. En tom mängd lagras som null.
 *
 * Objektet är oföränderligt.
 */
class SeenPages private constructor(private val bits: ByteArray) {

    /** Antal lästa sidor. */
    val count: Int get() = bits.sumOf { Integer.bitCount(it.toInt() and 0xFF) }

    val isEmpty: Boolean get() = bits.isEmpty()

    /** Den högsta lästa sidan, eller null om ingen sida är läst. */
    val maxPage: Int?
        get() {
            if (bits.isEmpty()) return null
            val last = bits.last().toInt() and 0xFF
            return (bits.size - 1) * 8 + (32 - Integer.numberOfLeadingZeros(last))
        }

    operator fun contains(page: Int): Boolean {
        if (page !in 1..MAX_PAGE) return false
        val index = (page - 1) / 8
        return index < bits.size && (bits[index].toInt() shr ((page - 1) % 8)) and 1 == 1
    }

    /** Antal lästa sidor bland sidorna 1..[pageCount]. Sidor utanför numret räknas inte. */
    fun countUpTo(pageCount: Int): Int = (1..pageCount.coerceIn(0, MAX_PAGE)).count { it in this }

    fun pages(): List<Int> = (1..(maxPage ?: 0)).filter { it in this }

    /** Mängden utökad med [pages]. Sidnummer utanför 1..[MAX_PAGE] hoppas över. */
    operator fun plus(pages: Iterable<Int>): SeenPages {
        val valid = pages.filter { it in 1..MAX_PAGE }
        if (valid.all { it in this }) return this
        val result = bits.copyOf(maxOf(bits.size, (valid.max() - 1) / 8 + 1))
        for (page in valid) {
            val index = (page - 1) / 8
            result[index] = (result[index].toInt() or (1 shl ((page - 1) % 8))).toByte()
        }
        return SeenPages(result)
    }

    /** Unionen av två mängder (bitvis OR), för sammanslagning av backup (planens E11). */
    infix fun or(other: SeenPages): SeenPages {
        val (longer, shorter) = if (bits.size >= other.bits.size) bits to other.bits else other.bits to bits
        val result = longer.copyOf()
        for (index in shorter.indices) result[index] = (result[index].toInt() or shorter[index].toInt()).toByte()
        return SeenPages(result)
    }

    /** Texten som lagras i IssueUserState.seenPages. null för en tom mängd. */
    fun encode(): String? = if (bits.isEmpty()) null else Base64.getEncoder().encodeToString(bits)

    override fun equals(other: Any?): Boolean = other is SeenPages && bits.contentEquals(other.bits)
    override fun hashCode(): Int = bits.contentHashCode()
    override fun toString(): String = "SeenPages(${pages()})"

    companion object {
        /** Högsta sidnummer som kan lagras. Arkivets största nummer har 116 sidor. */
        const val MAX_PAGE = 4096

        val EMPTY = SeenPages(ByteArray(0))

        fun of(vararg pages: Int): SeenPages = EMPTY + pages.asIterable()

        /**
         * Läser en lagrad mängd. null, tom text och text som inte är giltig
         * Base64 ger en tom mängd: ett trasigt värde får aldrig krascha appen.
         */
        fun decode(text: String?): SeenPages {
            if (text.isNullOrEmpty()) return EMPTY
            val decoded = try {
                Base64.getDecoder().decode(text)
            } catch (e: IllegalArgumentException) {
                return EMPTY
            }
            var size = minOf(decoded.size, MAX_PAGE / 8)
            while (size > 0 && decoded[size - 1].toInt() == 0) size--
            return if (size == 0) EMPTY else SeenPages(decoded.copyOf(size))
        }
    }
}
