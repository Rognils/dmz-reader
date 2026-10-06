package se.c64.dmzreader.ui

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.c64.dmzreader.data.inventory.InventoryImporter
import se.c64.dmzreader.data.inventory.InventoryParser
import se.c64.dmzreader.testing.FakeActiveInventoryStore
import se.c64.dmzreader.testing.TestFiles
import se.c64.dmzreader.testing.inMemoryDatabase
import se.c64.dmzreader.testing.issueEntity
import se.c64.dmzreader.ui.components.coverImageData

class CoverImageDataTest {

    @Test
    fun `the cover url reaches the image loader exactly as it stands in the inventory`() = runTest {
        val json = TestFiles.inventory.readText()
        val expected = InventoryParser.parse(json).issues.associate { it.issueId to it.coverUrl }
        val db = inMemoryDatabase()
        try {
            InventoryImporter(db.issueDao(), FakeActiveInventoryStore()).load(json)
            val fromDatabase = db.issueDao().getAll()

            assertEquals(185, fromDatabase.size)
            for (issue in fromDatabase) {
                assertEquals(issue.issueId, expected[issue.issueId], coverImageData(issue))
            }
            // Adresserna följer skanningarnas avvikande filnamn. De går inte att räkna ut.
            assertEquals(
                "https://dmzarkivet.se/magazines/199303/DMZ_1993-03-01.jpg",
                coverImageData(fromDatabase.first { it.issueId == "datormagazin-1993-03" }),
            )
            assertEquals(
                "https://dmzarkivet.se/magazines/19870405/DMZ_1987-04-001.jpg",
                coverImageData(fromDatabase.first { it.issueId == "datormagazin-1987-04-05" }),
            )
            assertTrue(fromDatabase.all { coverImageData(it)!!.startsWith("https://") })
        } finally {
            db.close()
        }
    }

    @Test
    fun `an odd url is passed on untouched`() {
        val odd = "https://example.invalid/Omslag med mellanslag/å ä ö.jpg?x=1#y"
        assertEquals(odd, coverImageData(issueEntity("datormagazin-1989-06", coverUrl = odd)))
    }

    @Test
    fun `no cover url means no image data and so a placeholder`() {
        assertNull(coverImageData(issueEntity("datormagazin-1989-06", coverUrl = null)))
    }
}
