package se.c64.dmzreader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mappningen mellan PDF-sida och skanning (planens FAS 11, ADR 0009). */
class HqMappingTest {
    private val threeDigits = "ftp://dmzarkivet.se/Original%20Scans/198601-Original/DMZ_1986-01-{page:03d}.jpg"
    private val twoDigits = "ftp://dmzarkivet.se/Original%20Scans/199213-Original/DMZ_1992-13-{page:02d}.jpg"

    private fun info(
        pattern: String? = threeDigits,
        scanPageCount: Int? = 28,
        map: String? = null,
        rotation: String? = null,
        source: String? = "scans",
    ) = HqIssueInfo(source, pattern, scanPageCount, map, rotation)

    @Test
    fun `without a map page N is scan N from the pattern`() {
        val first = hqScanFor(info(), 1, 28)!!
        assertEquals("ftp://dmzarkivet.se/Original%20Scans/198601-Original/DMZ_1986-01-001.jpg", first.url)
        assertEquals("DMZ_1986-01-001.jpg", first.fileName)
        assertEquals(0, first.rotation)
        assertEquals("DMZ_1986-01-028.jpg", hqScanFor(info(), 28, 28)!!.fileName)
        assertEquals("DMZ_1986-01-014.jpg", hqScanFor(info(), 14, 28)!!.fileName)
    }

    @Test
    fun `two digit page numbers follow the pattern`() {
        assertEquals("DMZ_1992-13-01.jpg", hqScanFor(info(twoDigits, 80), 1, 80)!!.fileName)
        assertEquals("DMZ_1992-13-80.jpg", hqScanFor(info(twoDigits, 80), 80, 80)!!.fileName)
        // Tre siffror i ett tvåsiffrigt mönster kortas inte.
        assertEquals("DMZ_1992-13-100.jpg", hqScanFor(info(twoDigits, 120), 100, 120)!!.fileName)
    }

    @Test
    fun `pages outside the issue have no scan`() {
        assertNull(hqScanFor(info(), 0, 28))
        assertNull(hqScanFor(info(), -1, 28))
        assertNull(hqScanFor(info(), 29, 28))
    }

    @Test
    fun `without a map the rule only holds when the pdf has as many pages as there are scans`() {
        // 1993 nr 3 utan tabell: 68 PDF-sidor och 72 skanningar. Hellre ingen HQ än fel sida.
        assertNull(hqScanFor(info(twoDigits, 72), 5, 68))
        assertNull(hqScanFor(info(twoDigits, null), 5, 68))
        assertTrue(hqSupported(info(twoDigits, 72)))
    }

    @Test
    fun `a map decides the file for every page, and null means no hq`() {
        val pattern = "ftp://dmzarkivet.se/Original%20Scans/199303-Original/DMZ_1993-03-{page:02d}.jpg"
        val map = """{"1":"DMZ_1993-03-03.jpg","2":"DMZ_1993-03-04.jpg","39":null,"41":"DMZ_1993-03-43.jpg"}"""
        val mapped = info(pattern, 72, map)
        assertEquals("ftp://dmzarkivet.se/Original%20Scans/199303-Original/DMZ_1993-03-03.jpg", hqScanFor(mapped, 1, 68)!!.url)
        assertEquals("DMZ_1993-03-43.jpg", hqScanFor(mapped, 41, 68)!!.fileName)
        assertNull(hqScanFor(mapped, 39, 68))
        // En sida som saknas i tabellen räknas aldrig fram ur mönstret.
        assertNull(hqScanFor(mapped, 3, 68))
    }

    @Test
    fun `a supplement with its own file names is reached through the map`() {
        val pattern = "ftp://dmzarkivet.se/Original%20Scans/198604-Original/DMZ_1986-04-{page:03d}.png"
        val map = """{"33":"DMZ_1986-04-033.png","34":"DMZ_1986-04-Spelbilaga-001.png","49":"DMZ_1986-04-Spelbilaga-016.png"}"""
        val scan = hqScanFor(info(pattern, 33, map), 34, 49)!!
        assertEquals("ftp://dmzarkivet.se/Original%20Scans/198604-Original/DMZ_1986-04-Spelbilaga-001.png", scan.url)
        assertEquals("DMZ_1986-04-Spelbilaga-016.png", hqScanFor(info(pattern, 33, map), 49, 49)!!.fileName)
    }

    @Test
    fun `rotation applies to the listed pages only`() {
        val rotated = info(rotation = """{"1":90,"5":180}""")
        assertEquals(90, hqScanFor(rotated, 1, 28)!!.rotation)
        assertEquals(180, hqScanFor(rotated, 5, 28)!!.rotation)
        assertEquals(0, hqScanFor(rotated, 2, 28)!!.rotation)
        // Ogiltiga värden och trasig text ger ingen vridning.
        assertEquals(0, hqScanFor(info(rotation = """{"1":45}"""), 1, 28)!!.rotation)
        assertEquals(0, hqScanFor(info(rotation = "inte json"), 1, 28)!!.rotation)
    }

    @Test
    fun `a broken map gives no hq at all rather than a guess`() {
        assertNull(hqScanFor(info(map = "inte json"), 1, 28))
        assertNull(hqScanFor(info(map = """{"ett":"a.jpg"}"""), 1, 28))
        assertNull(hqScanFor(info(map = "{}"), 1, 28))
    }

    @Test
    fun `a file name can not point out of the issue directory`() {
        for (name in listOf("../199001-Original/x.jpg", "a/b.jpg", "..", ".dold.jpg", "", " ", "a\\\\b.jpg")) {
            assertNull(name, hqScanFor(info(map = """{"1":"$name"}"""), 1, 28))
        }
        // Tecken som inte får stå i en adress kodas.
        assertEquals(
            "ftp://dmzarkivet.se/Original%20Scans/198601-Original/sida%201.jpg",
            hqScanFor(info(map = """{"1":"sida 1.jpg"}"""), 1, 28)!!.url,
        )
    }

    @Test
    fun `issues without scans have no hq`() {
        assertFalse(hqSupported(info(source = "large_pdf")))
        assertFalse(hqSupported(info(source = null)))
        assertFalse(hqSupported(info(pattern = null)))
        assertFalse(hqSupported(info(pattern = "ftp://dmzarkivet.se/x/utan-platshallare.jpg")))
        assertNull(hqScanFor(info(source = "large_pdf"), 1, 28))
        assertNull(hqScanFor(info(pattern = null), 1, 28))
        assertTrue(hqSupported(info()))
    }
}
