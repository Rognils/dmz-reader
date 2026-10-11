package se.c64.dmzreader

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.request.CachePolicy
import coil3.request.SuccessResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.c64.dmzreader.data.inventory.InventoryParser
import se.c64.dmzreader.data.inventory.toEntity
import se.c64.dmzreader.data.network.ConcurrencyLimitInterceptor
import se.c64.dmzreader.data.network.CoverImageLoader
import se.c64.dmzreader.domain.CoverSource
import se.c64.dmzreader.domain.coverSources
import se.c64.dmzreader.ui.components.IssueCover
import se.c64.dmzreader.ui.components.coverImageRequest
import se.c64.dmzreader.ui.theme.DmzReaderTheme
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Reservvägen: nummer utan inbyggd miniatyr hämtar omslaget via coverUrl.
 *
 * Omslagens adresser kommer ur det riktiga arkivindexet, men förfrågningarna tas emot av en
 * lokal server på telefonen ([LocalArchive]), som svarar med en påhittad bild. Arkivet nås aldrig.
 */
@RunWith(AndroidJUnit4::class)
class CoverFallbackNetworkTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun useLocalArchive() {
        LocalArchive.start()
        // Varje bild dröjer en stund, så att alla tolv förfrågningarna hinner stå i kö samtidigt.
        LocalArchive.imageDelayMillis = 300
    }

    @After
    fun stopLocalArchive() = LocalArchive.stop()

    /** Ett testinventory: tolv nummer ur det riktiga, men utan coverAsset. */
    private fun issuesWithoutThumbnail() = InventoryParser.parse(
        context.assets.open("archive_inventory.json").bufferedReader().use { it.readText() },
    ).issues
        .filter { it.publicationId == "datormagazin" && it.year == 1993 }
        .take(12)
        .map { it.toEntity().copy(coverAsset = null) }

    @Test
    fun atMostFourCoverRequestsAtOnce() {
        val client = CoverImageLoader.createHttpClient(logRequests = true)
        val limiter = client.interceptors.filterIsInstance<ConcurrencyLimitInterceptor>().single()
        val loader = CoverImageLoader.create(context, client)
        val issues = issuesWithoutThumbnail()
        assertEquals(12, issues.size)

        val results = runBlocking {
            coroutineScope {
                issues.map { issue ->
                    async {
                        val source = coverSources(issue).single()
                        assertTrue(source is CoverSource.Remote)
                        val request = coverImageRequest(context, source, 440, 611).newBuilder()
                            .memoryCachePolicy(CachePolicy.DISABLED)
                            .diskCachePolicy(CachePolicy.DISABLED)
                            .build()
                        loader.execute(request)
                    }
                }.awaitAll()
            }
        }

        val failures = results.filterNot { it is SuccessResult }
        assertTrue("misslyckade hämtningar: $failures", failures.isEmpty())
        // Alla tolv startades samtidigt, men aldrig fler än fyra var igång.
        assertEquals(4, limiter.peak.get())
        // Bilderna avkodades nedskalade, inte i originalets 950 px bredd.
        assertTrue(results.all { it.image!!.width in 400..700 })
        // Servern fick en förfrågan per omslag, till samma sökvägar som står i arkivindexet, med appens User-Agent.
        assertEquals(issues.map { it.coverUrl!!.toHttpUrl().encodedPath }.sorted(), LocalArchive.requests.map { it.path }.sorted())
        assertTrue(LocalArchive.requests.all { it.userAgent!!.startsWith("DMZReader/") })
    }

    @Test
    fun coverWithoutThumbnailIsLoadedFromItsUrl() {
        val requested = CopyOnWriteArrayList<String>()
        // Först i kedjan, så att den ser adressen appen begärde och inte den lokala serverns.
        val client = CoverImageLoader.createHttpClient(logRequests = true).newBuilder()
            .apply {
                interceptors().add(0, Interceptor { chain ->
                    requested += chain.request().url.toString()
                    chain.proceed(chain.request())
                })
            }
            .build()
        val loader = CoverImageLoader.create(context, client)
        // En miniatyr som inte finns, så att även felvägen prövas: assets först, sedan nätet.
        val issue = issuesWithoutThumbnail().first().copy(coverAsset = "covers/finns-inte.webp")
        // Bilden kan ligga i diskcachen från en tidigare körning. Då görs inget anrop alls.
        loader.diskCache?.remove(issue.coverUrl!!)

        composeRule.setContent {
            DmzReaderTheme { IssueCover(issue, loader, modifier = Modifier.width(140.dp)) }
        }
        composeRule.waitUntil(timeoutMillis = 20_000) { requested.isNotEmpty() }

        // Adressen är exakt den som står i inventory, och den lokala servern fick samma sökväg.
        assertEquals(listOf(issue.coverUrl), requested.distinct())
        composeRule.waitUntil(timeoutMillis = 20_000) { LocalArchive.requests.isNotEmpty() }
        assertEquals(issue.coverUrl!!.toHttpUrl().encodedPath, LocalArchive.requests.first().path)
    }
}
