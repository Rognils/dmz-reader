package se.c64.dmzreader

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.c64.dmzreader.ui.search.INDEX_UNREACHABLE_CARD_TEXT
import androidx.compose.ui.test.performTextClearance
import se.c64.dmzreader.data.search.SearchIndexSource
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hämtning av sökindexet från Sök-fliken, hela vägen: kortet, knappen, nedladdningen genom
 * nedladdningsjobbet, kontrollen, importen och en fungerande sökning.
 *
 * GitHub ersätts av en lokal server på telefonen med samma sökvägar: manifestet under latest,
 * filen under den versionsspecifika adressen, och en omdirigering till en "filserver" vars adress
 * byts vid varje försök. Filen är det lilla, påhittade testindexet i debug-bygget (search_test.db).
 * Testerna når aldrig GitHub eller arkivet: testkörarens spärr stoppar båda.
 */
@RunWith(AndroidJUnit4::class)
class SearchIndexDownloadDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = appContainer(context)
    private val index: ByteArray by lazy { context.assets.open("search_test.db").use { it.readBytes() } }
    private val sha256: String by lazy { MessageDigest.getInstance("SHA-256").digest(index).joinToString("") { "%02x".format(it) } }

    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val cdn = AtomicInteger(0)
    private val cut = AtomicInteger(0)

    @Volatile
    private var manifestAvailable = true

    @Volatile
    private var throttled = false

    private val server = MockWebServer()

    private fun waitFor(timeoutMillis: Long = 30_000, condition: () -> Boolean) = rule.waitUntil(timeoutMillis, condition)
    private fun tagged(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun exists(text: String, substring: Boolean = false) =
        rule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private val fileRequests get() = requests.filter { it.requestUrl!!.encodedPath.startsWith("/cdn/") }

    private fun manifest() = """
        {"manifestVersion": 1, "releaseTag": "v1.0.0", "fileName": "search-test.db", "size": ${index.size},
         "sha256": "$sha256", "searchSchemaVersion": "1", "inventoryDatasetVersion": "2026-10-06-006",
         "normalizerVersion": "2", "privacyFilterVersion": "3", "minAppVersionCode": 9904}
    """.trimIndent()

    @Before
    fun startLocalGitHub() {
        runBlocking { container.searchIndex.remove() }
        container.searchIndexDownloads.reset()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.requestUrl!!.encodedPath
                return when {
                    path.endsWith("/latest/download/search-index.json") ->
                        if (manifestAvailable) MockResponse().setBody(manifest()) else MockResponse().setResponseCode(404)
                    path.endsWith("/download/v1.0.0/search-test.db") ->
                        MockResponse().setResponseCode(302).setHeader("Location", "/cdn/${cdn.incrementAndGet()}/search-test.db")
                    path.startsWith("/cdn/") ->
                        if (path.split("/")[2].toInt() != cdn.get()) MockResponse().setResponseCode(403) else file(request)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
        SearchIndexSource.testBase = server.url("/Rognils/dmz-reader/releases")
    }

    private fun file(request: RecordedRequest): MockResponse {
        val from = request.getHeader("Range")?.let { Regex("""bytes=(\d+)-""").matchEntire(it)?.groupValues?.get(1)?.toInt() }
        val response = MockResponse().setHeader("ETag", "\"test-index-1\"")
        if (from != null && request.getHeader("If-Range") == "\"test-index-1\"") {
            response.setResponseCode(206)
                .setHeader("Content-Range", "bytes $from-${index.size - 1}/${index.size}")
                .setBody(Buffer().write(index, from, index.size - from))
        } else {
            response.setBody(Buffer().write(index))
        }
        if (throttled) response.throttleBody(1_024, 100, TimeUnit.MILLISECONDS)
        if (cut.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0) response.socketPolicy = SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY
        return response
    }

    @After
    fun stopLocalGitHub() {
        container.searchIndexDownloads.reset()
        runBlocking { container.searchIndex.remove() }
        SearchIndexSource.testBase = null
        server.shutdown()
    }

    /** Sök-fliken, med kortet för sökindexet synligt. */
    private fun openSearchCard() {
        waitFor { exists("Bibliotek") }
        rule.onNodeWithText("Sök").performClick()
        waitFor { tagged("search") }
        rule.onNodeWithTag("search").performScrollToNode(hasTestTag("index-card"))
    }

    private fun cardText(): String? = rule.onAllNodes(hasTestTag("index-card-text")).fetchSemanticsNodes().firstOrNull()
        ?.config?.firstOrNull { it.key.name == "Text" }?.value?.toString()?.trim('[', ']')

    private val downloadDirectory get() = File(storage(context).filesDir, "search-download")

    @Test
    fun downloadFromTheCardAndSearch() {
        openSearchCard()
        waitFor { tagged("index-download") }
        assertEquals("Fulltextsökning kräver ett sökindex på cirka 1 MB. Allt annat i appen fungerar utan det.", cardText())
        rule.onNodeWithTag("index-download").performClick()
        waitFor(60_000) { container.searchIndex.index.value != null }

        // Kortet är borta och sökningen går i tidningarnas text.
        waitFor { !tagged("index-card") }
        rule.onNodeWithTag("search-field").performTextInput("Commodore")
        waitFor { tagged("hit-summary") }
        assertEquals(6, container.searchIndex.index.value!!.info.issueCount)
        // Manifestet via latest, filen från releasen det anger, och den hämtade filen är borttagen.
        val paths = requests.map { it.requestUrl!!.encodedPath }
        assertEquals("/Rognils/dmz-reader/releases/latest/download/search-index.json", paths.first())
        assertTrue(paths.contains("/Rognils/dmz-reader/releases/download/v1.0.0/search-test.db"))
        assertEquals(emptyList<String>(), downloadDirectory.list().orEmpty().toList())
    }

    @Test
    fun interruptedDownloadResumesWithRange() {
        cut.set(1)
        openSearchCard()
        waitFor { tagged("index-download") }
        rule.onNodeWithTag("index-download").performClick()
        // Överföringen bryts mitt i: kortet säger det, och den påbörjade filen ligger kvar.
        waitFor(60_000) { tagged("index-resume") }
        assertNull(container.searchIndex.index.value)
        assertTrue(File(downloadDirectory, "search-test.db.part").length() in 1 until index.size)

        rule.onNodeWithTag("index-resume").performClick()
        waitFor(60_000) { container.searchIndex.index.value != null }
        val resumed = fileRequests.last()
        assertNotNull("återupptogs med Range", resumed.getHeader("Range"))
        assertEquals("\"test-index-1\"", resumed.getHeader("If-Range"))
        // Varje försök följde en ny omdirigering.
        assertEquals(listOf("/cdn/1/search-test.db", "/cdn/2/search-test.db"), fileRequests.map { it.requestUrl!!.encodedPath })
    }

    @Test
    fun cancelStopsTheDownloadAndKeepsNothing() {
        throttled = true
        openSearchCard()
        waitFor { tagged("index-download") }
        rule.onNodeWithTag("index-download").performClick()
        waitFor(30_000) { tagged("index-cancel") && tagged("index-progress") }
        rule.onNodeWithTag("index-cancel").performClick()
        waitFor { tagged("index-download") }
        assertNull(container.searchIndex.index.value)
        assertFalse(container.searchIndexDownloads.hasPending())
        assertEquals(emptyList<String>(), downloadDirectory.list().orEmpty().toList())
    }

    @Test
    fun cardWorksWithoutTheManifest() {
        manifestAvailable = false
        openSearchCard()
        waitFor { tagged("index-retry") && !exists("Söker efter sökindexet på GitHub…") }
        assertEquals(INDEX_UNREACHABLE_CARD_TEXT, cardText())
        assertTrue(tagged("index-import"))
        // Sökningen på titel, år och nummer fungerar ändå.
        rule.onNodeWithTag("search-field").performTextInput("1986")
        waitFor { exists("Datormagazin 1986 Nr 1", substring = true) }

        // När manifestet går att nå igen räcker Försök igen.
        manifestAvailable = true
        rule.onNodeWithTag("search-field").performTextClearance()
        rule.onNodeWithTag("search").performScrollToNode(hasTestTag("index-card"))
        rule.onNodeWithTag("index-retry").performClick()
        waitFor { tagged("index-download") }
        assertEquals(emptyList<String>(), fileRequests.map { it.requestUrl!!.encodedPath })
    }
}
