package se.c64.dmzreader.data.search

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import se.c64.dmzreader.data.pdf.TransferCoordinator
import se.c64.dmzreader.testing.TestFiles
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hämtning av sökindexet från GitHub-releasen, mot en lokal server som står i GitHubs ställe
 * (aldrig GitHub eller arkivet). Servern har samma sökvägar: manifestet under latest, filen under
 * den versionsspecifika adressen, och en omdirigering till en "filserver" vars adress slutar gälla.
 */
class SearchIndexDownloadTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val index: ByteArray by lazy { TestFiles.searchTestDb.readBytes() }
    private val sha256: String by lazy { MessageDigest.getInstance("SHA-256").digest(index).joinToString("") { "%02x".format(it) } }

    /** En falsk GitHub. Varje fält styr hur nästa svar ser ut. */
    private inner class FakeGitHub : Dispatcher() {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        var manifest: String? = null
        var etag = "\"etag-1\""

        /** Så många av de kommande filsvaren bryts mitt i överföringen. */
        val cut = AtomicInteger(0)

        /** Bryts efter så här många byte. 0 betyder ungefär mitt i (MockWebServers eget val). */
        var ignoreRange = false
        var answerRangeWith: Int? = null
        var throttle = false

        /** Varje omdirigering går till en ny adress på filservern. De äldre ger 403, som en signerad adress som har gått ut. */
        private val cdnCounter = AtomicInteger(0)

        override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request
            val path = request.requestUrl!!.encodedPath
            return when {
                path == "/Rognils/dmz-reader/releases/latest/download/search-index.json" ->
                    manifest?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
                path == "/Rognils/dmz-reader/releases/download/v1.0.0/search-test.db" ->
                    MockResponse().setResponseCode(302).setHeader("Location", "/cdn/${cdnCounter.incrementAndGet()}/search-test.db")
                path.startsWith("/cdn/") -> {
                    val number = path.split("/")[2].toInt()
                    if (number != cdnCounter.get()) MockResponse().setResponseCode(403) else file(request)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        private fun file(request: RecordedRequest): MockResponse {
            val range = request.getHeader("Range")?.let { Regex("""bytes=(\d+)-""").matchEntire(it)?.groupValues?.get(1)?.toInt() }
            val ifRange = request.getHeader("If-Range")
            answerRangeWith?.let { code -> if (range != null) return MockResponse().setResponseCode(code) }
            val response = MockResponse().setHeader("ETag", etag).setHeader("Accept-Ranges", "bytes")
            if (range != null && !ignoreRange && (ifRange == null || ifRange == etag || etag != "\"etag-1\"")) {
                response.setResponseCode(206)
                    .setHeader("Content-Range", "bytes $range-${index.size - 1}/${index.size}")
                    .setBody(Buffer().write(index, range, index.size - range))
            } else {
                response.setBody(Buffer().write(index))
            }
            if (throttle) response.throttleBody(2_048, 50, TimeUnit.MILLISECONDS)
            if (cut.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0) response.socketPolicy = SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY
            return response
        }

        val filePaths: List<String> get() = requests.map { it.requestUrl!!.encodedPath }.filter { "/download/v1.0.0/" in it || it.startsWith("/cdn/") }
        val fileRequests: List<RecordedRequest> get() = requests.filter { it.requestUrl!!.encodedPath.startsWith("/cdn/") }
    }

    private val github = FakeGitHub()
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadDirectory by lazy { folder.newFolder("search-download") }
    private val dataDirectory by lazy { folder.newFolder("data") }
    private var available = Long.MAX_VALUE

    private fun manifestJson(
        sha: String = sha256,
        size: Long = index.size.toLong(),
        manifestVersion: Int = 1,
        schema: String = "1",
        minApp: Int = 9904,
    ) = """
        {"manifestVersion": $manifestVersion, "releaseTag": "v1.0.0", "fileName": "search-test.db", "size": $size,
         "sha256": "$sha", "searchSchemaVersion": "$schema", "inventoryDatasetVersion": "2026-10-06-006",
         "normalizerVersion": "2", "privacyFilterVersion": "3", "minAppVersionCode": $minApp}
    """.trimIndent()

    private val manifest: SearchIndexManifest get() = (parseSearchIndexManifest(manifestJson(), 9904) as ManifestResult.Ok).manifest

    private fun downloader() = SearchIndexDownloader(
        client = SearchIndexDownloader.createHttpClient(),
        source = SearchIndexSource(),
        directory = downloadDirectory,
        appVersionCode = 9904,
        availableBytes = { available },
    )

    private val manager by lazy { SearchIndexManager(dataDirectory) }

    private fun downloads(
        transfers: TransferCoordinator = TransferCoordinator(),
        networkAllowed: Boolean = true,
    ) = SearchIndexDownloads(downloader(), manager, transfers, { networkAllowed }, startTransfers = { _ -> }, scope = scope)

    /** Beställer hämtningen som knappen gör, och väntar tills beställningen är sparad. */
    private fun SearchIndexDownloads.order() = runBlocking {
        request()
        withTimeout(10_000) { while (!hasPending()) delay(10) }
    }

    @Before
    fun startServer() {
        server.dispatcher = github
        server.start()
        SearchIndexSource.testBase = server.url("/Rognils/dmz-reader/releases")
        github.manifest = manifestJson()
    }

    @After
    fun stopServer() {
        SearchIndexSource.testBase = null
        scope.cancel()
        server.shutdown()
    }

    // --- Manifestet ---

    @Test
    fun `the manifest is read`() {
        val result = parseSearchIndexManifest(manifestJson(), appVersionCode = 9904)
        val parsed = (result as ManifestResult.Ok).manifest
        assertEquals("v1.0.0", parsed.releaseTag)
        assertEquals("search-test.db", parsed.fileName)
        assertEquals(index.size.toLong(), parsed.size)
        assertEquals(sha256, parsed.sha256)
        assertEquals("3", parsed.privacyFilterVersion)
        assertEquals(9904, parsed.minAppVersionCode)
    }

    @Test
    fun `the manifest is fetched through latest and the file from the release it names`() = runBlocking {
        val fetched = downloader().fetchManifest()
        assertEquals(manifest, (fetched as ManifestResult.Ok).manifest)
        assertEquals("/Rognils/dmz-reader/releases/latest/download/search-index.json", github.requests.single().requestUrl!!.encodedPath)
        val result = downloader().download(manifest)
        assertTrue(result is IndexFileResult.Ready)
        assertEquals("/Rognils/dmz-reader/releases/download/v1.0.0/search-test.db", github.filePaths.first())
        assertArrayEquals(index, (result as IndexFileResult.Ready).file.readBytes())
        assertTrue(github.requests.all { it.getHeader("User-Agent")!!.startsWith("DMZReader/") })
    }

    @Test
    fun `an unknown manifest version, an unsupported schema and a too old app are refused`() {
        val version = parseSearchIndexManifest(manifestJson(manifestVersion = 2), 9904)
        assertTrue((version as ManifestResult.Unsupported).message.contains("manifestVersion 2"))
        val schema = parseSearchIndexManifest(manifestJson(schema = "2"), 9904)
        assertTrue((schema as ManifestResult.Unsupported).message.contains("searchSchemaVersion 2"))
        val app = parseSearchIndexManifest(manifestJson(minApp = 10_001), appVersionCode = 10_000)
        assertTrue((app as ManifestResult.Unsupported).message.contains("nyare version av appen"))
        assertTrue(parseSearchIndexManifest("inte json", 9904) is ManifestResult.Unsupported)
        assertTrue(parseSearchIndexManifest(manifestJson().replace("search-test.db", "../search-test.db"), 9904) is ManifestResult.Unsupported)
    }

    @Test
    fun `an unsupported manifest fetches nothing`() = runBlocking {
        github.manifest = manifestJson(schema = "2")
        val downloads = downloads()
        downloads.request()
        withTimeout(10_000) { while (downloads.state.value !is IndexDownloadState.Unsupported) delay(10) }
        assertFalse(downloads.hasPending())
        assertEquals(emptyList<String>(), github.filePaths)
    }

    @Test
    fun `a missing manifest is shown as unreachable with the GitHub message`() = runBlocking {
        github.manifest = null
        assertEquals(ManifestResult.Unreachable(GITHUB_UNREACHABLE_MESSAGE), downloader().fetchManifest())
        assertFalse(GITHUB_UNREACHABLE_MESSAGE.contains("DMZArkivet"))
    }

    // --- Kontroll och import ---

    @Test
    fun `a wrong checksum is never imported and the file is removed`() = runBlocking {
        github.manifest = manifestJson(sha = "0".repeat(64))
        val downloads = downloads()
        downloads.order()
        assertEquals(IndexRunOutcome.FAILED, downloads.runPending())
        assertEquals(CHECKSUM_MESSAGE, (downloads.state.value as IndexDownloadState.Failed).message.substringBefore("\n"))
        assertNull(manager.index.value)
        assertFalse(File(dataDirectory, "search.db").exists())
        assertEquals(emptyList<String>(), downloadDirectory.list()!!.toList())
    }

    @Test
    fun `a good download is imported, searchable, and the downloaded file is removed`() = runBlocking {
        val downloads = downloads()
        downloads.order()
        assertEquals(IndexRunOutcome.DONE, downloads.runPending())
        assertNotNull(manager.index.value)
        assertTrue(manager.rank("\"commodore\"").orEmpty().isNotEmpty())
        assertEquals(emptyList<String>(), downloadDirectory.list()!!.toList())
        assertFalse(downloads.hasPending())
    }

    @Test
    fun `an existing index is untouched by a failed attempt`() = runBlocking {
        manager.import({ index.inputStream() }, index.size.toLong())
        val before = File(dataDirectory, "search.db").readBytes()
        val info = manager.index.value!!.info

        // Fel kontrollsumma: ingen import.
        github.manifest = manifestJson(sha = "1".repeat(64))
        val first = downloads()
        first.order()
        assertEquals(IndexRunOutcome.FAILED, first.runPending())

        // Rätt kontrollsumma men en fil som inte är ett sökindex: importen vägrar, och det gamla gäller.
        val garbage = ByteArray(index.size) { (it * 31).toByte() }
        val garbageSha = MessageDigest.getInstance("SHA-256").digest(garbage).joinToString("") { "%02x".format(it) }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when {
                request.requestUrl!!.encodedPath.endsWith("search-index.json") -> MockResponse().setBody(manifestJson(sha = garbageSha))
                else -> MockResponse().setHeader("ETag", "\"g\"").setBody(Buffer().write(garbage))
            }
        }
        val second = downloads()
        second.order()
        assertEquals(IndexRunOutcome.FAILED, second.runPending())
        assertTrue((second.state.value as IndexDownloadState.Failed).message.contains("Det tidigare sökindexet är oförändrat."))

        assertArrayEquals(before, File(dataDirectory, "search.db").readBytes())
        assertEquals(info, manager.index.value!!.info)
        assertEquals(listOf("search.db"), dataDirectory.list()!!.toList())
    }

    // --- Återupptagning ---

    /** Ett första försök som bryts mitt i. Den påbörjade filen ligger kvar. */
    private suspend fun interrupted(downloader: SearchIndexDownloader): Long {
        github.cut.set(1)
        val first = downloader.download(manifest)
        assertTrue("$first", first is IndexFileResult.NetworkError)
        val have = downloader.bytesPresent(manifest)
        assertTrue("påbörjad fil: $have", have in 1 until index.size)
        return have
    }

    @Test
    fun `resumption with 206 continues where it stopped`() = runBlocking {
        val downloader = downloader()
        val have = interrupted(downloader)
        val result = downloader.download(manifest) as IndexFileResult.Ready
        assertTrue(result.resumed)
        assertArrayEquals(index, result.file.readBytes())
        val resumed = github.fileRequests.last()
        assertEquals("bytes=$have-", resumed.getHeader("Range"))
        assertEquals("\"etag-1\"", resumed.getHeader("If-Range"))
    }

    @Test
    fun `resumption with 200 starts over from byte 0`() = runBlocking {
        val downloader = downloader()
        interrupted(downloader)
        github.ignoreRange = true
        val result = downloader.download(manifest) as IndexFileResult.Ready
        assertFalse(result.resumed)
        assertArrayEquals(index, result.file.readBytes())
        assertNotNull(github.fileRequests.last().getHeader("Range"))
    }

    @Test
    fun `a changed ETag starts over from byte 0`() = runBlocking {
        val downloader = downloader()
        interrupted(downloader)
        // Servern bryr sig inte om If-Range och svarar 206 med en ny ETag: filen har bytts.
        github.etag = "\"etag-2\""
        val result = downloader.download(manifest) as IndexFileResult.Ready
        assertFalse(result.resumed)
        assertArrayEquals(index, result.file.readBytes())
        val last = github.fileRequests.takeLast(2)
        assertNotNull(last[0].getHeader("Range"))
        assertNull("börjar om från 0", last[1].getHeader("Range"))
    }

    @Test
    fun `416 starts over from 0 once and then fails`() = runBlocking {
        val downloader = downloader()
        interrupted(downloader)
        github.answerRangeWith = 416
        val result = downloader.download(manifest) as IndexFileResult.Ready
        assertArrayEquals(index, result.file.readBytes())
        assertNull(github.fileRequests.last().getHeader("Range"))

        // Svarar servern 416 även utan Range ger det fel efter ett nytt försök från 0.
        val alwaysWrong = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(416)
        }
        server.dispatcher = alwaysWrong
        val failed = downloader().download(manifest)
        assertTrue("$failed", failed is IndexFileResult.Failed)
        assertTrue((failed as IndexFileResult.Failed).message.startsWith(GITHUB_UNREACHABLE_MESSAGE))
        assertEquals(emptyList<String>(), downloadDirectory.list()!!.toList())
    }

    @Test
    fun `every resumption follows the redirect anew and never reuses the expired file server address`() = runBlocking {
        val downloader = downloader()
        interrupted(downloader)
        val old = github.fileRequests.last().requestUrl!!.encodedPath
        val result = downloader.download(manifest) as IndexFileResult.Ready
        assertArrayEquals(index, result.file.readBytes())
        // Det andra försöket gick till GitHub-adressen igen och följde en ny omdirigering.
        val paths = github.filePaths
        assertEquals(
            listOf(
                "/Rognils/dmz-reader/releases/download/v1.0.0/search-test.db", old,
                "/Rognils/dmz-reader/releases/download/v1.0.0/search-test.db", "/cdn/2/search-test.db",
            ),
            paths,
        )
        // Den gamla adressen har gått ut.
        val expired = SearchIndexDownloader.createHttpClient().newCall(okhttp3.Request.Builder().url(server.url(old)).build()).execute()
        assertEquals(403, expired.code)
        expired.close()
    }

    // --- Regler före start ---

    @Test
    fun `the wifi rule stops the download on a metered network`() = runBlocking {
        val downloads = downloads(networkAllowed = false)
        downloads.order()
        assertEquals(IndexRunOutcome.WAITING_FOR_WIFI, downloads.runPending())
        assertTrue(downloads.state.value is IndexDownloadState.WaitingForWifi)
        assertTrue(downloads.hasPending())
        assertEquals(emptyList<String>(), github.filePaths)
    }

    @Test
    fun `too little space gives a clear message and fetches nothing`() = runBlocking {
        available = index.size.toLong() // filen ryms, men inte importens kopia och marginal
        val downloads = downloads()
        downloads.order()
        assertEquals(IndexRunOutcome.FAILED, downloads.runPending())
        val message = (downloads.state.value as IndexDownloadState.Failed).message
        assertTrue(message, message.startsWith("Det finns inte plats för sökindexet. Det behövs cirka 51 MB ledigt"))
        assertEquals(emptyList<String>(), github.filePaths)
        assertNull(manager.index.value)
    }

    // --- En överföring åt gången ---

    @Test
    fun `a PDF reading pauses the index download, which then resumes with Range`() = runBlocking {
        val transfers = TransferCoordinator()
        val downloads = downloads(transfers)
        github.throttle = true
        downloads.order()
        val run = async(Dispatchers.IO) { downloads.runPending() }
        withTimeout(20_000) { while (downloads.state.value !is IndexDownloadState.Downloading) delay(10) }
        // Strypt till 40 kB/s: efter en halv sekund är ungefär en tredjedel hämtad.
        delay(500)
        // En läsning ("Läs" på en PDF) tar överföringen. Sökindexet står still under tiden.
        var stateDuringReading: IndexDownloadState? = null
        transfers.reading {
            delay(300)
            stateDuringReading = downloads.state.value
        }
        github.throttle = false
        assertEquals(IndexRunOutcome.DONE, withTimeout(30_000) { run.await() })
        assertTrue("$stateDuringReading", stateDuringReading is IndexDownloadState.Queued)
        assertNotNull("fortsatte med Range", github.fileRequests.last().getHeader("Range"))
        assertNotNull(manager.index.value)
    }

    @Test
    fun `cancel removes the partial file and leaves no import`() = runBlocking {
        val downloads = downloads()
        github.throttle = true
        downloads.order()
        val run = async(Dispatchers.IO) { downloads.runPending() }
        withTimeout(20_000) { while (downloads.state.value !is IndexDownloadState.Downloading) delay(10) }
        downloads.cancel()
        assertEquals(IndexRunOutcome.CANCELLED, withTimeout(10_000) { run.await() })
        assertTrue(downloads.state.value is IndexDownloadState.Available)
        assertFalse(downloads.hasPending())
        assertEquals(emptyList<String>(), downloadDirectory.list()!!.toList())
        assertNull(manager.index.value)
    }
}
