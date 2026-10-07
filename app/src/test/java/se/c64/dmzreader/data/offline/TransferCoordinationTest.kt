package se.c64.dmzreader.data.offline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import se.c64.dmzreader.data.db.DmzDatabase
import se.c64.dmzreader.data.db.DownloadState
import se.c64.dmzreader.data.pdf.DownloadResult
import se.c64.dmzreader.data.pdf.PdfCache
import se.c64.dmzreader.data.pdf.PdfDownloader
import se.c64.dmzreader.data.pdf.PdfFiles
import se.c64.dmzreader.data.pdf.PdfRepository
import se.c64.dmzreader.data.pdf.TransferCoordinator
import se.c64.dmzreader.testing.inMemoryDatabase
import se.c64.dmzreader.testing.issueEntity
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * En PDF-överföring åt gången i hela appen: "Läs" (PdfRepository) och
 * nedladdningskön (OfflineRepository) delar på en TransferCoordinator.
 *
 * Antalet samtidiga förfrågningar mäts i HTTP-klienten, där en förfrågan
 * räknas från att den startar tills den är avslutad. En avbruten förfrågan
 * avslutas också; klienten skiljer inte på de två i sina händelser.
 */
class TransferCoordinationTest {
    private lateinit var server: MockWebServer
    private lateinit var root: File
    private lateinit var files: PdfFiles
    private lateinit var cache: PdfCache
    private lateinit var db: DmzDatabase
    private lateinit var offline: OfflineRepository
    private lateinit var reading: PdfRepository
    private val transfers = TransferCoordinator()

    private val a = "datormagazin-1986-01"
    private val b = "datormagazin-1986-02"
    private val c = "datormagazin-1986-03"
    private fun content(issueId: String) = ByteArray(120_000) { (it * 7 + issueId.last().code).toByte() }
    private val etag = "\"abc-120000\""

    private val slow = HashSet<String>()
    private val requested = CopyOnWriteArrayList<String>()
    private val events = CopyOnWriteArrayList<String>()
    private val activeCalls = AtomicInteger(0)
    private val peakCalls = AtomicInteger(0)
    private val log = CopyOnWriteArrayList<String>()

    private val listener = object : EventListener() {
        private fun name(call: Call) = call.request().url.encodedPath.removePrefix("/pdf/").removeSuffix(".pdf").takeLast(2)

        override fun callStart(call: Call) {
            peakCalls.accumulateAndGet(activeCalls.incrementAndGet(), ::maxOf)
            events += "start ${name(call)}"
        }

        override fun callEnd(call: Call) {
            activeCalls.decrementAndGet()
            events += "slut ${name(call)}"
        }

        override fun callFailed(call: Call, ioe: IOException) {
            activeCalls.decrementAndGet()
            events += "slut ${name(call)}"
        }
    }

    @Before
    fun setUp() = runBlocking {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val issueId = request.path!!.removePrefix("/pdf/").removeSuffix(".pdf")
                requested += issueId
                val from = request.getHeader("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
                val response = if (from == null) {
                    MockResponse().setResponseCode(200).setHeader("ETag", etag).setBody(Buffer().write(content(issueId)))
                } else {
                    MockResponse().setResponseCode(206).setHeader("ETag", etag)
                        .setHeader("Content-Range", "bytes $from-119999/120000")
                        .setBody(Buffer().write(content(issueId), from, 120_000 - from))
                }
                return if (issueId in slow) response.throttleBody(10_000, 100, TimeUnit.MILLISECONDS) else response
            }
        }
        server.start()
        root = Files.createTempDirectory("dmz-transfer").toFile()
        files = PdfFiles(File(root, "files/issues"))
        cache = PdfCache(File(root, "cache/pdf"))
        db = inMemoryDatabase()
        db.issueDao().replaceAll(
            listOf(a, b, c).map {
                issueEntity(it).copy(ocrPdfUrl = server.url("/pdf/$it.pdf").toString(), ocrPdfSize = 120_000)
            },
        )
        val store = LocalStateStore(db.issueLocalStateDao())
        // En HTTP-klient för all PDF-trafik, som i appen.
        val client: OkHttpClient = PdfDownloader.createHttpClient().newBuilder().eventListener(listener).build()
        offline = OfflineRepository(
            issueDao = db.issueDao(), localState = store, files = files, cache = cache,
            downloader = PdfDownloader(client, files), transfers = transfers, log = { log += it },
        )
        reading = PdfRepository(cache, PdfDownloader(client, cache), store, transfers, log = { log += it })
        offline.reconcile()
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
        root.deleteRecursively()
    }

    private suspend fun issue(issueId: String) = db.issueDao().get(issueId)!!
    private suspend fun downloadState(issueId: String) =
        db.issueLocalStateDao().get(issueId)?.downloadState ?: DownloadState.NONE

    private suspend fun awaitQueueProgress(issueId: String) = withTimeout(5_000) {
        while (offline.active.value?.takeIf { it.issueId == issueId }?.progress?.bytesDownloaded.let { it == null || it == 0L }) delay(10)
    }

    @Test
    fun `reading another issue pauses the queue and the queue resumes afterwards`() = runBlocking {
        slow += a
        offline.requestDownload(a)
        offline.requestDownload(c)
        val queue = async(Dispatchers.IO) { offline.runQueue() }
        awaitQueueProgress(a)

        // Användaren trycker "Läs" på ett annat nummer mitt i nedladdningen.
        val read = reading.download(issue(b)) {}

        assertTrue(read is DownloadResult.Success)
        assertArrayEquals(content(b), cache.file(b).readBytes())
        // Kön pausades: dess förfrågan avbröts och .part-filen låg kvar.
        assertTrue(log.any { "$a: pausad för en läsning" in it })
        slow.clear()
        assertEquals(QueueOutcome.EMPTY, withTimeout(20_000) { queue.await() })

        // Aldrig två förfrågningar samtidigt, och ordningen är: kön, läsningen, kön igen.
        assertEquals(1, peakCalls.get())
        assertEquals(
            listOf("start 01", "slut 01", "start 02", "slut 02", "start 01", "slut 01", "start 03", "slut 03"),
            events.toList(),
        )
        assertEquals(listOf(a, b, a, c), requested.toList())
        // Det pausade numret fortsatte där det slutade och blev komplett.
        assertTrue(log.any { "$a: nedladdad, 120000 byte, återupptagen=true" in it })
        assertArrayEquals(content(a), files.file(a).readBytes())
        assertEquals(DownloadState.DOWNLOADED, downloadState(a))
        assertEquals(DownloadState.DOWNLOADED, downloadState(c))
        // Läsningen hamnade i läs-cachen och ändrade inte numrets nedladdningsstatus.
        assertEquals(DownloadState.NONE, downloadState(b))
    }

    @Test
    fun `many readings and a queue never overlap`() = runBlocking {
        slow += a
        offline.requestDownload(a)
        val queue = async(Dispatchers.IO) { offline.runQueue() }
        awaitQueueProgress(a)
        val first = async(Dispatchers.IO) { reading.download(issue(b)) {} }
        val second = async(Dispatchers.IO) { reading.download(issue(c)) {} }
        assertTrue(first.await() is DownloadResult.Success)
        assertTrue(second.await() is DownloadResult.Success)
        slow.clear()
        assertEquals(QueueOutcome.EMPTY, withTimeout(20_000) { queue.await() })
        assertEquals(1, peakCalls.get())
        assertEquals(0, activeCalls.get())
        // Kön fick vänta tills båda läsningarna var klara.
        assertEquals(listOf("start 01", "slut 01"), events.take(2))
        assertEquals("slut 01", events.last())
        assertEquals(DownloadState.DOWNLOADED, downloadState(a))
    }

    @Test
    fun `reading the issue that is being downloaded waits for it instead of fetching it again`() = runBlocking {
        slow += a
        offline.requestDownload(a)
        val queue = async(Dispatchers.IO) { offline.runQueue() }
        awaitQueueProgress(a)

        // Läsaren: numret laddas redan ned, så den följer förloppet och får den färdiga filen.
        val seen = CopyOnWriteArrayList<Long>()
        val file = withTimeout(20_000) { offline.awaitActiveDownload(a) { it?.let { p -> seen += p.bytesDownloaded } } }

        assertEquals(files.file(a), file)
        assertArrayEquals(content(a), file!!.readBytes())
        assertTrue(seen.isNotEmpty())
        assertEquals(seen.sorted(), seen.toList())
        queue.await()
        // En enda förfrågan för numret, och ingenting i läs-cachen.
        assertEquals(listOf(a), requested.toList())
        assertFalse(cache.file(a).exists())
        assertFalse(cache.partFile(a).exists())
        assertEquals(1, peakCalls.get())
    }

    @Test
    fun `waiting for a download that is not running returns at once`() = runBlocking {
        assertNull(offline.awaitActiveDownload(a) {})
        // Avbryts nedladdningen medan läsaren väntar får läsaren null och hämtar numret själv.
        slow += a
        offline.requestDownload(a)
        val queue = async(Dispatchers.IO) { offline.runQueue() }
        awaitQueueProgress(a)
        val waiting = async { offline.awaitActiveDownload(a) {} }
        delay(100)
        offline.cancel(a)
        assertNull(withTimeout(10_000) { waiting.await() })
        queue.await()
        assertEquals(DownloadState.NONE, downloadState(a))
    }

    @Test
    fun `an issue read while it waited in the queue is moved from the cache without a second request`() = runBlocking {
        slow += a
        offline.requestDownload(a)
        offline.requestDownload(b)
        val queue = async(Dispatchers.IO) { offline.runQueue() }
        awaitQueueProgress(a)
        // "Läs" på numret som står på tur i kön: det hämtas till läs-cachen.
        assertTrue(reading.download(issue(b)) {} is DownloadResult.Success)
        slow.clear()
        queue.await()

        assertEquals(listOf(a, b, a), requested.toList())
        assertEquals(DownloadState.DOWNLOADED, downloadState(b))
        assertArrayEquals(content(b), files.file(b).readBytes())
        assertFalse(cache.file(b).exists())
        assertTrue(log.any { "$b: flyttad från läs-cachen" in it })
        assertEquals(1, peakCalls.get())
    }

    // --- Koordinatorn för sig ---

    @Test
    fun `a reading pauses a background transfer, which reports it with null`() = runBlocking {
        val coordinator = TransferCoordinator()
        val started = CompletableDeferred<Unit>()
        val order = CopyOnWriteArrayList<String>()
        val background = async(Dispatchers.IO) {
            coordinator.background {
                started.complete(Unit)
                try {
                    delay(60_000)
                    "klar"
                } finally {
                    order += "bakgrund slut"
                }
            }
        }
        started.await()
        val result = coordinator.reading {
            order += "läsning"
            "läst"
        }
        assertEquals("läst", result)
        assertNull(background.await())
        // Läsningen började först när bakgrundsöverföringen var helt avslutad.
        assertEquals(listOf("bakgrund slut", "läsning"), order.toList())
        assertFalse(coordinator.readingActive)
        assertEquals("igen", coordinator.background { "igen" })
    }

    @Test
    fun `a background transfer does not start while a reading is going on`() = runBlocking {
        val coordinator = TransferCoordinator()
        val release = CompletableDeferred<Unit>()
        val order = CopyOnWriteArrayList<String>()
        val reading = launch(Dispatchers.IO) {
            coordinator.reading {
                order += "läsning börjar"
                release.await()
                order += "läsning slut"
            }
        }
        withTimeout(5_000) { while (!coordinator.readingActive) delay(5) }
        val background = async(Dispatchers.IO) { coordinator.background { order += "bakgrund"; "x" } }
        delay(200)
        assertEquals(listOf("läsning börjar"), order.toList())
        release.complete(Unit)
        reading.join()
        assertEquals("x", withTimeout(5_000) { background.await() })
        assertEquals(listOf("läsning börjar", "läsning slut", "bakgrund"), order.toList())
    }

    @Test
    fun `cancelling the caller of a background transfer is a real cancellation`() = runBlocking {
        val coordinator = TransferCoordinator()
        val started = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) {
            try {
                coordinator.background {
                    started.complete(Unit)
                    delay(60_000)
                    "klar"
                }
                fail("skulle ha avbrutits")
            } catch (e: CancellationException) {
                throw e
            }
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        // Koordinatorn är fri igen.
        assertEquals("ny", withTimeout(5_000) { coordinator.reading { "ny" } })
    }
}
