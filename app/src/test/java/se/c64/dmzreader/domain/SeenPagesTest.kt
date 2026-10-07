package se.c64.dmzreader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SeenPagesTest {
    @Test
    fun `an empty set has no pages and is stored as null`() {
        assertTrue(SeenPages.EMPTY.isEmpty)
        assertEquals(0, SeenPages.EMPTY.count)
        assertNull(SeenPages.EMPTY.maxPage)
        assertNull(SeenPages.EMPTY.encode())
        assertFalse(1 in SeenPages.EMPTY)
    }

    @Test
    fun `pages are bits with page one as the lowest bit of the first byte`() {
        // Sida 1 → byte 0 bit 0, sida 8 → byte 0 bit 7, sida 9 → byte 1 bit 0.
        assertEquals(listOf(0x01.toByte()), bytes(SeenPages.of(1)))
        assertEquals(listOf(0x80.toByte()), bytes(SeenPages.of(8)))
        assertEquals(listOf(0x00.toByte(), 0x01.toByte()), bytes(SeenPages.of(9)))
        assertEquals(listOf(0x3F.toByte()), bytes(SeenPages.of(1, 2, 3, 4, 5, 6)))
    }

    @Test
    fun `base64 text is stable and round trips`() {
        val seen = SeenPages.of(1, 2, 3, 4, 5, 6, 25)
        assertEquals("PwAAAQ==", seen.encode())
        val decoded = SeenPages.decode("PwAAAQ==")
        assertEquals(seen, decoded)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 25), decoded.pages())
        assertEquals(7, decoded.count)
        assertEquals(25, decoded.maxPage)
    }

    @Test
    fun `trailing zero bytes do not change the set or its text`() {
        val padded = Base64.getEncoder().encodeToString(byteArrayOf(0x3F, 0, 0, 0))
        assertEquals(SeenPages.of(1, 2, 3, 4, 5, 6), SeenPages.decode(padded))
        assertEquals("Pw==", SeenPages.decode(padded).encode())
        assertTrue(SeenPages.decode(Base64.getEncoder().encodeToString(byteArrayOf(0, 0))).isEmpty)
    }

    @Test
    fun `adding pages returns a new set and leaves the old one alone`() {
        val before = SeenPages.of(3)
        val after = before + listOf(3, 7, 116)
        assertEquals(listOf(3), before.pages())
        assertEquals(listOf(3, 7, 116), after.pages())
        assertEquals(116, after.maxPage)
        // Ingenting nytt: samma objekt, så anroparen ser att inget behöver sparas.
        assertSame(after, after + listOf(7))
    }

    @Test
    fun `page numbers outside the valid range are ignored`() {
        val seen = SeenPages.of(0, -4, 5, SeenPages.MAX_PAGE, SeenPages.MAX_PAGE + 1)
        assertEquals(listOf(5, SeenPages.MAX_PAGE), seen.pages())
        assertFalse(0 in seen)
        assertFalse(SeenPages.MAX_PAGE + 1 in seen)
        assertFalse(Int.MAX_VALUE in seen)
        assertSame(SeenPages.EMPTY, SeenPages.EMPTY + listOf(0, -1))
    }

    @Test
    fun `counting is limited to the pages of the issue`() {
        val seen = SeenPages.of(1, 2, 28, 29, 40)
        assertEquals(5, seen.count)
        assertEquals(3, seen.countUpTo(28))
        assertEquals(0, seen.countUpTo(0))
        assertEquals(0, seen.countUpTo(-3))
    }

    @Test
    fun `broken text gives an empty set instead of a crash`() {
        assertTrue(SeenPages.decode(null).isEmpty)
        assertTrue(SeenPages.decode("").isEmpty)
        assertTrue(SeenPages.decode("inte base64 !!").isEmpty)
    }

    @Test
    fun `text longer than the limit is cut off`() {
        val huge = Base64.getEncoder().encodeToString(ByteArray(2_000) { 0xFF.toByte() })
        val seen = SeenPages.decode(huge)
        assertEquals(SeenPages.MAX_PAGE, seen.maxPage)
        assertEquals(SeenPages.MAX_PAGE, seen.count)
    }

    @Test
    fun `merging is a bitwise or in both directions`() {
        val phone = SeenPages.of(1, 2, 3, 30)
        val backup = SeenPages.of(3, 4, 9)
        val merged = phone or backup
        assertEquals(listOf(1, 2, 3, 4, 9, 30), merged.pages())
        assertEquals(merged, backup or phone)
        assertEquals(30, merged.maxPage)
        assertEquals(phone, phone or SeenPages.EMPTY)
        assertEquals(phone, SeenPages.EMPTY or phone)
        assertEquals(phone, phone or phone)
    }

    private fun bytes(seen: SeenPages): List<Byte> = Base64.getDecoder().decode(seen.encode()).toList()
}
