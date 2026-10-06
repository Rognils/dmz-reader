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
}
