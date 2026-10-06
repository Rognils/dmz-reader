package se.c64.dmzreader.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SpreadsTest {
    private fun pairs(pageCount: Int, offset: Int) = spreadsFor(pageCount, offset).map { it.pages }

    @Test
    fun `standard pairing puts page one alone`() {
        assertEquals(listOf(listOf(1), listOf(2, 3), listOf(4, 5), listOf(6)), pairs(6, 0))
        assertEquals(listOf(listOf(1), listOf(2, 3), listOf(4, 5), listOf(6, 7)), pairs(7, 0))
    }

    @Test
    fun `shifted pairing starts with pages one and two`() {
        assertEquals(listOf(listOf(1, 2), listOf(3, 4), listOf(5, 6)), pairs(6, 1))
        assertEquals(listOf(listOf(1, 2), listOf(3, 4), listOf(5, 6), listOf(7)), pairs(7, 1))
    }

    @Test
    fun `the real sample issues`() {
        // 1986 nr 1 har 28 sidor: omslaget ensamt, 2–3 … 26–27, baksidan ensam.
        val small = pairs(28, 0)
        assertEquals(15, small.size)
        assertEquals(listOf(1), small.first())
        assertEquals(listOf(28), small.last())
        // 1995 nr 14 har 84 sidor, och uppslaget 42–43 hör ihop.
        assertEquals(listOf(42, 43), pairs(84, 0)[spreadIndexForPage(42, 84, 0)])
        assertEquals(listOf(42, 43), pairs(84, 0)[spreadIndexForPage(43, 84, 0)])
    }

    @Test
    fun `small and empty documents`() {
        assertEquals(emptyList<List<Int>>(), pairs(0, 0))
        assertEquals(listOf(listOf(1)), pairs(1, 0))
        assertEquals(listOf(listOf(1)), pairs(1, 1))
        assertEquals(listOf(listOf(1), listOf(2)), pairs(2, 0))
        assertEquals(listOf(listOf(1, 2)), pairs(2, 1))
    }

    @Test
    fun `every page is in exactly one spread and index lookup agrees`() {
        for (pageCount in 1..40) {
            for (offset in 0..1) {
                val spreads = spreadsFor(pageCount, offset)
                assertEquals((1..pageCount).toList(), spreads.flatMap { it.pages })
                for (page in 1..pageCount) {
                    val index = spreadIndexForPage(page, pageCount, offset)
                    assertEquals("sida $page av $pageCount, offset $offset", true, page in spreads[index])
                }
            }
        }
    }

    @Test
    fun `offset is normalised to two possibilities`() {
        assertEquals(0, normalizeSpreadOffset(null))
        assertEquals(0, normalizeSpreadOffset(0))
        assertEquals(1, normalizeSpreadOffset(1))
        assertEquals(0, normalizeSpreadOffset(2))
        assertEquals(1, normalizeSpreadOffset(-1))
        assertEquals(pairs(9, 1), pairs(9, 3))
    }

    @Test
    fun `the catalogue offset wins over the one chosen on the phone`() {
        assertEquals(1, effectiveSpreadOffset(issueSpreadOffset = 1, detectedSpreadOffset = 0))
        assertEquals(0, effectiveSpreadOffset(issueSpreadOffset = 0, detectedSpreadOffset = 1))
        assertEquals(1, effectiveSpreadOffset(issueSpreadOffset = null, detectedSpreadOffset = 1))
        assertEquals(0, effectiveSpreadOffset(issueSpreadOffset = null, detectedSpreadOffset = null))
    }

    @Test
    fun `rotation keeps the page`() {
        // Stående sida 7 → liggande uppslag 6–7 → tillbaka till sida 7.
        val portrait = ReaderPosition(7)
        assertEquals(6, portrait.screenIndex(landscape = false, pageCount = 28, offset = 0))

        val spreadIndex = portrait.screenIndex(landscape = true, pageCount = 28, offset = 0)
        assertEquals(listOf(6, 7), spreadsFor(28, 0)[spreadIndex].pages)

        // Läsaren rapporterar skärmen den visar. Positionen ska inte ändras av det.
        val inLandscape = portrait.movedTo(spreadIndex, landscape = true, pageCount = 28, offset = 0)
        assertEquals(ReaderPosition(7), inLandscape)
        assertEquals(6, inLandscape.screenIndex(landscape = false, pageCount = 28, offset = 0))
    }

    @Test
    fun `rotation keeps the page for every page and both pairings`() {
        for (pageCount in listOf(1, 2, 27, 28, 84)) {
            for (offset in 0..1) {
                for (page in 1..pageCount) {
                    val position = ReaderPosition(page)
                    val spread = position.screenIndex(true, pageCount, offset)
                    val back = position.movedTo(spread, true, pageCount, offset)
                    assertEquals(page - 1, back.screenIndex(false, pageCount, offset))
                }
            }
        }
    }

    @Test
    fun `turning to another spread moves to its left page`() {
        val position = ReaderPosition(7)
        val next = position.movedTo(4, landscape = true, pageCount = 28, offset = 0)
        assertEquals(ReaderPosition(8), next)
        assertEquals(7, next.screenIndex(landscape = false, pageCount = 28, offset = 0))
    }

    @Test
    fun `turning pages in portrait`() {
        assertEquals(ReaderPosition(8), ReaderPosition(7).movedTo(7, landscape = false, pageCount = 28, offset = 0))
        assertEquals(ReaderPosition(28), ReaderPosition(7).movedTo(99, landscape = false, pageCount = 28, offset = 0))
        assertEquals(ReaderPosition(1), ReaderPosition(7).movedTo(-1, landscape = false, pageCount = 28, offset = 0))
    }

    @Test
    fun `shifting the pairing keeps the page on screen`() {
        val position = ReaderPosition(7)
        val shifted = position.screenIndex(landscape = true, pageCount = 28, offset = 1)
        assertEquals(listOf(7, 8), spreadsFor(28, 1)[shifted].pages)
    }

    @Test
    fun `progress in percent`() {
        assertEquals(4, progressPercent(1, 28))
        assertEquals(50, progressPercent(14, 28))
        assertEquals(100, progressPercent(28, 28))
        assertEquals(0, progressPercent(1, 0))
    }
}
