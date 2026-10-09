package se.c64.dmzreader.data.inventory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import se.c64.dmzreader.data.hq.ScanFailure
import se.c64.dmzreader.data.pdf.DownloadProgress
import se.c64.dmzreader.domain.HQ_PAGE_UNAVAILABLE
import se.c64.dmzreader.domain.hqScanFor
import se.c64.dmzreader.domain.hqSupported
import se.c64.dmzreader.testing.TestFiles
import se.c64.dmzreader.testing.inventoryJson
import se.c64.dmzreader.testing.issueJson
import se.c64.dmzreader.ui.reader.HqPageState
import se.c64.dmzreader.ui.reader.hqInfo
import se.c64.dmzreader.ui.reader.hqStatusText

/** HQ-mappningen från inventory till appen (planens FAS 11): hqPageMap och hqPageRotation. */
class InventoryHqTest {
    private val issues = InventoryParser.parse(TestFiles.inventory.readText()).issues.associateBy { it.issueId }
    private fun entity(issueId: String) = issues.getValue(issueId).toEntity()

    @Test
    fun `issues that follow the rule have no map`() {
        val issue = issues.getValue("datormagazin-1991-22")
        assertNull(issue.hqPageMap)
        assertNull(issue.hqPageRotation)
        assertNull(issue.toEntity().hqPageMap)
        assertEquals(listOf("datormagazin-1986-04", "datormagazin-1993-03"), issues.values.filter { it.hqPageMap != null }.map { it.issueId })
    }

    @Test
    fun `every issue in the real inventory supports hq`() {
        assertTrue(issues.values.all { hqSupported(it.toEntity().hqInfo()) })
    }

    @Test
    fun `every page of every issue that follows the rule has a scan`() {
        for (issue in issues.values.filter { it.hqPageMap == null }) {
            val info = issue.toEntity().hqInfo()
            val pages = issue.pageCount!!
            assertEquals(issue.issueId, issue.scanPageCount, issue.pageCount)
            val names = (1..pages).map { hqScanFor(info, it, pages)!!.fileName }
            assertEquals(issue.issueId, pages, names.toSet().size)
            assertTrue(issue.issueId, names.all { Regex("""[A-Za-z]+_[A-Za-z\d-]+[_-]\d{2,3}\.jpg""").matches(it) })
        }
    }

    @Test
    fun `the game supplement of 1986 nr 4 is reached through the map`() {
        val info = entity("datormagazin-1986-04").hqInfo()
        assertEquals("DMZ_1986-04-033.png", hqScanFor(info, 33, 49)!!.fileName)
        val supplement = hqScanFor(info, 34, 49)!!
        assertEquals("DMZ_1986-04-Spelbilaga-001.png", supplement.fileName)
        assertEquals("ftp://dmzarkivet.se/Original%20Scans/198604-Original/DMZ_1986-04-Spelbilaga-001.png", supplement.url)
        assertEquals("DMZ_1986-04-Spelbilaga-016.png", hqScanFor(info, 49, 49)!!.fileName)
        assertNull(hqScanFor(info, 50, 49))
    }

    @Test
    fun `1993 nr 3 is shifted two scans, and two pages have no scan`() {
        val info = entity("datormagazin-1993-03").hqInfo()
        assertEquals("DMZ_1993-03-03.jpg", hqScanFor(info, 1, 68)!!.fileName)
        assertEquals("DMZ_1993-03-43.jpg", hqScanFor(info, 41, 68)!!.fileName)
        assertEquals("DMZ_1993-03-70.jpg", hqScanFor(info, 68, 68)!!.fileName)
        assertNull(hqScanFor(info, 39, 68))
        assertNull(hqScanFor(info, 40, 68))
        // Sida 27 ligger upp och ned i skanningen jämfört med PDF:en.
        assertEquals(180, hqScanFor(info, 27, 68)!!.rotation)
        assertEquals(0, hqScanFor(info, 26, 68)!!.rotation)
    }

    @Test
    fun `landscape covers are turned`() {
        val info = entity("datormagazin-1987-04-05").hqInfo()
        val cover = hqScanFor(info, 1, 48)!!
        assertEquals("DMZ_1987-04-001.jpg", cover.fileName)
        assertEquals(90, cover.rotation)
        assertEquals(0, hqScanFor(info, 2, 48)!!.rotation)
        // 1986 nr 1: sida 1 ligger likadant i PDF:en och i skanningen.
        assertEquals(0, hqScanFor(entity("datormagazin-1986-01").hqInfo(), 1, 28)!!.rotation)
    }

    @Test
    fun `the fields are read from the file and stored as json text`() {
        val json = inventoryJson(
            issues = listOf(
                issueJson(
                    "datormagazin-1989-06",
                    extra = """, "hqPageMap": {"1": "a-01.jpg", "2": null}, "hqPageRotation": {"1": 270}""",
                ),
            ),
        )
        val issue = InventoryParser.parse(json).issues.single()
        assertEquals(mapOf("1" to "a-01.jpg", "2" to null), issue.hqPageMap)
        assertEquals(mapOf("1" to 270), issue.hqPageRotation)
        val entity = issue.toEntity()
        assertEquals("""{"1":"a-01.jpg","2":null}""", entity.hqPageMap)
        assertEquals("""{"1":270}""", entity.hqPageRotation)
    }

    @Test
    fun `null for the fields is the same as absent`() {
        val json = inventoryJson(issues = listOf(issueJson("datormagazin-1989-06", extra = """, "hqPageMap": null, "hqPageRotation": null""")))
        val issue = InventoryParser.parse(json).issues.single()
        assertNull(issue.hqPageMap)
        assertNull(issue.toEntity().hqPageRotation)
    }

    @Test
    fun `bad maps are rejected`() {
        fun rejected(extra: String) = assertThrows(InventoryException.Malformed::class.java) {
            InventoryParser.parse(inventoryJson(issues = listOf(issueJson("datormagazin-1989-06", extra = ", $extra"))))
        }
        assertTrue("hqPageMap" in rejected(""""hqPageMap": {"0": "a.jpg"}""").message!!)
        assertTrue("hqPageMap" in rejected(""""hqPageMap": {"x": "a.jpg"}""").message!!)
        assertTrue("filnamn" in rejected(""""hqPageMap": {"1": "../a.jpg"}""").message!!)
        assertTrue("filnamn" in rejected(""""hqPageMap": {"1": "a/b.jpg"}""").message!!)
        assertTrue("filnamn" in rejected(""""hqPageMap": {"1": "a.exe"}""").message!!)
        assertTrue("vridning" in rejected(""""hqPageRotation": {"1": 45}""").message!!)
        assertTrue("hqPageRotation" in rejected(""""hqPageRotation": {"-1": 90}""").message!!)
        assertNotNull(rejected(""""hqPageMap": "inte ett objekt""""))
    }

    @Test
    fun `the status line tells what is shown`() {
        assertNull(hqStatusText(null, null))
        assertEquals("HQ · ansluter …", hqStatusText(HqPageState.Loading, null))
        assertEquals("HQ · hämtar 1,2 av 4,0 MB · 30 %", hqStatusText(HqPageState.Loading, DownloadProgress(1_200_000, 4_000_000)))
        assertEquals("HQ · hämtar 1,2 MB", hqStatusText(HqPageState.Loading, DownloadProgress(1_200_000, null)))
        assertEquals("HQ finns inte för den här sidan", hqStatusText(HqPageState.Unavailable, null))
        assertEquals(HQ_PAGE_UNAVAILABLE, hqStatusText(HqPageState.Unavailable, null))
        assertEquals("fel", hqStatusText(HqPageState.Failed("fel"), null))
        assertTrue(ScanFailure.entries.size == 3)
    }
}
