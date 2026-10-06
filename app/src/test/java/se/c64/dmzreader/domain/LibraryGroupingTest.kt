package se.c64.dmzreader.domain

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import se.c64.dmzreader.data.db.DmzDatabase
import se.c64.dmzreader.data.db.YearCount
import se.c64.dmzreader.data.inventory.InventoryImporter
import se.c64.dmzreader.testing.FakeActiveInventoryStore
import se.c64.dmzreader.testing.TestFiles
import se.c64.dmzreader.testing.inMemoryDatabase

class LibraryGroupingTest {
    private lateinit var db: DmzDatabase

    @Before
    fun setUp() = runTest {
        db = inMemoryDatabase()
        InventoryImporter(db.issueDao(), FakeActiveInventoryStore()).load(TestFiles.inventory.readText())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun labels(publicationId: String, year: Int) =
        db.issueDao().observeIssues(publicationId, year).first().map { it.issueLabel }

    @Test
    fun `years are grouped per publication`() = runTest {
        val publications = groupByPublication(db.issueDao().observeYearCounts().first())

        assertEquals(listOf("Datormagazin", "HighScore"), publications.map { it.publicationTitle })
        val datormagazin = publications[0]
        assertEquals(171, datormagazin.issueCount)
        assertEquals((1986..1995).toList(), datormagazin.years.map { it.year })
        assertEquals(listOf(4, 10, 16, 19, 20, 22, 22, 22, 22, 14), datormagazin.years.map { it.issueCount })
        val highScore = publications[1]
        assertEquals(14, highScore.issueCount)
        assertEquals(listOf(1993 to 6, 1994 to 8), highScore.years.map { it.year to it.issueCount })
    }

    @Test
    fun `grouping does not depend on input order`() {
        val counts = listOf(
            YearCount("b", "Beta", 1991, 2), YearCount("a", "Alfa", 1990, 1),
            YearCount("b", "Beta", 1989, 3), YearCount("a", "Alfa", 1988, 4),
        )
        val grouped = groupByPublication(counts)
        assertEquals(listOf("a", "b"), grouped.map { it.publicationId })
        assertEquals(listOf(1988, 1990), grouped[0].years.map { it.year })
        assertEquals(listOf(1989, 1991), grouped[1].years.map { it.year })
        assertEquals(5, grouped[1].issueCount)
        assertEquals(emptyList<PublicationYears>(), groupByPublication(emptyList()))
    }

    @Test
    fun `saved publication is used when it still exists`() = runTest {
        val publications = groupByPublication(db.issueDao().observeYearCounts().first())
        assertEquals("highscore", selectPublication(publications, "highscore")!!.publicationId)
        assertEquals("datormagazin", selectPublication(publications, null)!!.publicationId)
        assertEquals("datormagazin", selectPublication(publications, "finns-inte")!!.publicationId)
        assertNull(selectPublication(emptyList(), "highscore"))
    }

    @Test
    fun `a b issue sorts directly after its number`() = runTest {
        assertEquals(
            listOf("Nr 1", "Nr 2", "Nr 3", "Nr 4–5", "Nr 6", "Nr 7", "Nr 8", "Nr 9", "Nr 10", "Nr 10b"),
            labels("datormagazin", 1987),
        )
        val year1989 = labels("datormagazin", 1989)
        assertEquals(listOf("Nr 15", "Nr 16", "Nr 16b", "Nr 17", "Nr 18"), year1989.takeLast(5))
        assertEquals(19, year1989.size)
    }

    @Test
    fun `double issues sort on their first number`() = runTest {
        assertEquals(
            listOf("Nr 14", "Nr 15", "Nr 16–17"),
            labels("datormagazin", 1988).takeLast(3),
        )
        assertEquals(listOf("Nr 4", "Nr 5–6", "Nr 7"), labels("datormagazin", 1995).subList(3, 6))
    }

    @Test
    fun `numbers above nine sort numerically`() = runTest {
        val year1991 = labels("datormagazin", 1991)
        assertEquals((1..22).map { "Nr $it" }, year1991)
    }

    @Test
    fun `issues are limited to one publication and year`() = runTest {
        val highScore1993 = db.issueDao().observeIssues("highscore", 1993).first()
        assertEquals((17..22).map { "highscore-1993-$it" }, highScore1993.map { it.issueId })
        assertEquals(22, db.issueDao().observeIssues("datormagazin", 1993).first().size)
        assertEquals(0, db.issueDao().observeIssues("datormagazin", 1985).first().size)
    }
}
