package se.c64.dmzreader.domain

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import se.c64.dmzreader.data.db.DmzDatabase
import se.c64.dmzreader.data.inventory.InventoryImporter
import se.c64.dmzreader.data.repository.LibraryRepository
import se.c64.dmzreader.testing.FakeActiveInventoryStore
import se.c64.dmzreader.testing.TestFiles
import se.c64.dmzreader.testing.inMemoryDatabase
import se.c64.dmzreader.testing.issueEntity

class RelatedIssueTest {
    private val main = issueEntity("datormagazin-1993-17")
    private val supplement = issueEntity(
        "highscore-1993-17", publicationId = "highscore", publicationTitle = "HighScore",
        relatedIssueId = "datormagazin-1993-17",
    )

    @Test
    fun `a supplement links to its main issue`() {
        assertEquals(
            RelatedIssue("datormagazin-1993-17", "Bilaga till Datormagazin 1993 Nr 17"),
            relatedIssueFor(supplement, target = main, pointingHere = emptyList()),
        )
    }

    @Test
    fun `a main issue links to its supplement`() {
        assertEquals(
            RelatedIssue("highscore-1993-17", "Med bilagan HighScore Nr 17"),
            relatedIssueFor(main, target = null, pointingHere = listOf(supplement)),
        )
    }

    @Test
    fun `an issue without relations has no link`() {
        assertNull(relatedIssueFor(issueEntity("datormagazin-1989-06"), target = null, pointingHere = emptyList()))
    }

    @Test
    fun `a supplement whose main issue is missing has no link`() {
        assertNull(relatedIssueFor(supplement, target = null, pointingHere = emptyList()))
    }

    @Test
    fun `the texts come from the data and not from a publication name`() {
        val other = issueEntity(
            "spelbilagan-1990-03", publicationId = "spelbilagan", publicationTitle = "Spelbilagan",
            relatedIssueId = "datormagazin-1990-03",
        )
        val target = issueEntity("datormagazin-1990-03")
        assertEquals("Bilaga till Datormagazin 1990 Nr 3", relatedIssueFor(other, target, emptyList())!!.linkText)
        assertEquals("Med bilagan Spelbilagan Nr 3", relatedIssueFor(target, null, listOf(other))!!.linkText)
    }

    /** Samma sak genom databasen och repositoryt, med det riktiga inventoryt. */
    class WithRealInventory {
        private lateinit var db: DmzDatabase
        private lateinit var repository: LibraryRepository

        @Before
        fun setUp() = runTest {
            db = inMemoryDatabase()
            InventoryImporter(db.issueDao(), FakeActiveInventoryStore()).load(TestFiles.inventory.readText())
            repository = LibraryRepository(db.issueDao(), db.issueUserStateDao(), db.issueLocalStateDao())
        }

        @After
        fun tearDown() {
            db.close()
        }

        @Test
        fun `related issue is found in both directions`() = runTest {
            val supplement = repository.observeIssueDetails("highscore-1993-17").first()!!
            assertEquals(
                RelatedIssue("datormagazin-1993-17", "Bilaga till Datormagazin 1993 Nr 17"),
                supplement.related,
            )
            val main = repository.observeIssueDetails("datormagazin-1993-17").first()!!
            assertEquals(RelatedIssue("highscore-1993-17", "Med bilagan HighScore Nr 17"), main.related)
        }

        @Test
        fun `all fourteen supplements are linked both ways`() = runTest {
            val supplements = db.issueDao().getAll().filter { it.relatedIssueId != null }
            assertEquals(14, supplements.size)
            for (supplement in supplements) {
                val main = repository.observeIssueDetails(supplement.relatedIssueId!!).first()!!
                assertEquals(supplement.issueId, main.related?.issueId)
                val back = repository.observeIssueDetails(supplement.issueId).first()!!
                assertEquals(supplement.relatedIssueId, back.related?.issueId)
            }
        }

        @Test
        fun `ordinary issues and unknown ids`() = runTest {
            assertNull(repository.observeIssueDetails("datormagazin-1989-06").first()!!.related)
            assertNull(repository.observeIssueDetails("finns-inte-1999-01").first())
        }
    }
}
