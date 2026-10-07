package se.c64.dmzreader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.c64.dmzreader.domain.CoverTransform.Crop
import se.c64.dmzreader.testing.issueEntity

/** De tre vägarna till ett omslag: inbyggd miniatyr, coverUrl, platshållare. */
class CoverSourceTest {
    private val url = "https://dmzarkivet.se/magazines/198906/DMZ_1989-06-001.jpg"

    @Test
    fun `bundled thumbnail comes first and the url is the fallback`() {
        val issue = issueEntity(
            "datormagazin-1989-06", coverAsset = "covers/datormagazin-1989-06.webp", coverUrl = url,
        )
        assertEquals(
            listOf(
                CoverSource.Asset("covers/datormagazin-1989-06.webp"),
                CoverSource.Remote(url, CoverTransform.IDENTITY),
            ),
            coverSources(issue),
        )
    }

    @Test
    fun `without a bundled thumbnail the cover url is used`() {
        val issue = issueEntity("datormagazin-1989-06", coverAsset = null, coverUrl = url)
        assertEquals(listOf(CoverSource.Remote(url, CoverTransform.IDENTITY)), coverSources(issue))
    }

    @Test
    fun `without thumbnail and url there is nothing to load and the placeholder shows`() {
        val issue = issueEntity("datormagazin-1989-06", coverAsset = null, coverUrl = null)
        assertTrue(coverSources(issue).isEmpty())
    }

    @Test
    fun `a thumbnail alone is enough`() {
        val issue = issueEntity("datormagazin-1989-06", coverAsset = "covers/x.webp", coverUrl = null)
        assertEquals(listOf(CoverSource.Asset("covers/x.webp")), coverSources(issue))
    }

    @Test
    fun `cover flags apply to the url but never to the bundled thumbnail`() {
        val issue = issueEntity(
            "datormagazin-1986-01",
            coverAsset = "covers/datormagazin-1986-01.webp",
            coverUrl = url,
            coverFlags = listOf("crop_right_half", "rotate_cw"),
        )
        val sources = coverSources(issue)
        // Miniatyren är redan rättad av toolchainen och har ingen transform alls.
        assertEquals(CoverSource.Asset("covers/datormagazin-1986-01.webp"), sources[0])
        assertEquals(CoverSource.Remote(url, CoverTransform(90, Crop.RIGHT_HALF)), sources[1])
    }

    @Test
    fun `asset path becomes an android asset uri`() {
        assertEquals(
            "file:///android_asset/covers/datormagazin-1989-06.webp",
            CoverSource.Asset("covers/datormagazin-1989-06.webp").uri,
        )
    }

    @Test
    fun `the url is passed on exactly as it stands`() {
        val odd = "https://example.invalid/Omslag med mellanslag/å ä ö.jpg?x=1#y"
        val source = coverSources(issueEntity("datormagazin-1989-06", coverUrl = odd)).single()
        assertEquals(odd, (source as CoverSource.Remote).url)
    }

    /** Ett importerat arkivindex kan peka på en miniatyr som inte följer med appen (planens FAS 7). */
    @Test
    fun `coverAsset is only a hint - a thumbnail that is not bundled is skipped`() {
        val issue = issueEntity("datormagazin-1996-01", coverAsset = "covers/datormagazin-1996-01.webp")
        val bundled = setOf("covers/datormagazin-1989-06.webp")
        // Miniatyren finns inte i appen: coverUrl används direkt.
        val sources = coverSources(issue, bundled)
        assertEquals(1, sources.size)
        assertTrue(sources.single() is CoverSource.Remote)
        // Finns den används den först, som vanligt.
        val known = coverSources(issue.copy(coverAsset = "covers/datormagazin-1989-06.webp"), bundled)
        assertEquals(listOf(true, false), known.map { it is CoverSource.Asset })
        // Varken miniatyr eller adress: platshållaren.
        assertTrue(coverSources(issue.copy(coverUrl = null), bundled).isEmpty())
        // Okänt vilka miniatyrer som finns: den prövas, och laddningen får avgöra.
        assertEquals(2, coverSources(issue).size)
    }
}
