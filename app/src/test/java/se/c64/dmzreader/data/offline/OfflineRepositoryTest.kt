package se.c64.dmzreader.data.offline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.c64.dmzreader.data.db.DmzDatabase
import se.c64.dmzreader.data.db.DownloadState
import se.c64.dmzreader.data.db.IssueLocalState
import se.c64.dmzreader.data.pdf.PdfCache
import se.c64.dmzreader.data.pdf.PdfDownloader
import se.c64.dmzreader.data.pdf.PdfFiles
import se.c64.dmzreader.testing.inMemoryDatabase
import se.c64.dmzreader.testing.issueEntity
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Permanenta nedladdningar mot en lokal testserver och den riktiga databasen
 * (Room 3 med BundledSQLiteDriver, i minnet).
 */
class OfflineRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var root: File
    private lateinit var files: PdfFiles
    private lateinit var cache: PdfCache
    private lateinit var db: DmzDatabase
    private lateinit var store: LocalStateStore
    private var clock = 1_000L
    private val log = CopyOnWriteArrayList<String>()

    private val ids = listOf("datormagazin-1986-01", "datormagazin-1986-02", "datormagazin-1986-03")
    private fun content(issueId: String) = ByteArray(120_000) { (it * 7 + issueId.last().code).toByte() }
    private val etag = "\"abc-120000\""

    // Vad servern gör per sökväg. Standard: hela filen.
    private val responses = HashMap<String, (RecordedRequest) -> MockResponse>()
    private val concurrent = AtomicInteger(0)
    private val peakConcurrent = AtomicInteger(0)
    private val requested = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() = runBlocking {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val issueId = request.path!!.removePrefix("/pdf/").removeSuffix(".pdf")
                requested += issueId
                peakConcurrent.accumulateAndGet(concurrent.incrementAndGet(), ::maxOf)
                return try {
                    responses[issueId]?.invoke(request) ?: full(issueId)
                } finally {
                    concurrent.decrementAndGet()
                }
            }
        }
        server.start()
        root = Files.createTempDirectory("dmz-offline").toFile()
        files = PdfFiles(File(root, "files/issues"))
        cache = PdfCache(File(root, "cache/pdf"))
        db = inMemoryDatabase()
        store = LocalStateStore(db.issueLocalStateDao())
        db.issueDao().replaceAll(
            ids.map { issueEntity(it).copy(ocrPdfUrl = server.url("/pdf/$it.pdf").toString(), ocrPdfSize = 120_000) } +
                issueEntity("datormagazin-1986-04", ocrPdfUrl = null),
        )
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
        root.deleteRecursively()
    }

    private fun full(issueId: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("ETag", etag)
        .setBody(Buffer().write(content(issueId)))

    private fun partial(issueId: String, from: Int) = MockResponse()
        .setResponseCode(206)
        .setHeader("ETag", etag)
        .setHeader("Content-Range", "bytes $from-119999/120000")
        .setBody(Buffer().write(content(issueId), from, 120_000 - from))

    private fun repository(
        move: (File, File, File) -> Boolean = { s, t, p -> movePdf(s, t, p) },
        reconcile: Boolean = true,
    ): OfflineRepository {
        val repository = OfflineRepository(
            issueDao = db.issueDao(),
            localState = store,
            files = files,
            cache = cache,
            downloader = PdfDownloader(PdfDownloader.createHttpClient(), files),
            now = { clock++ },
            move = move,
            log = { log += it },
        )
        if (reconcile) runBlocking { repository.reconcile() }
        return repository
    }

    private fun state(issueId: String) = runBlocking { store.get(issueId) }
    private fun downloadState(issueId: String) = state(issueId)?.downloadState ?: DownloadState.NONE

    private fun putInCache(issueId: String, bytes: ByteArray = content(issueId)) {
        cache.directory.mkdirs()
        cache.file(issueId).writeBytes(bytes)
    }

    // --- Kön ---

    @Test
    fun `the queue is downloaded one file at a time in the order requested`() = runBlocking {
        val repository = repository()
        // Begärs i en annan ordning än issueId.
        for (issueId in listOf(ids[2], ids[0], ids[1])) {
            assertEquals(DownloadRequest.QUEUED, repository.requestDownload(issueId))
        }
        assertEquals(3, repository.queuedCount())
        assertEquals(360_000L, repository.estimatedQueueBytes())
        assertTrue(requested.isEmpty())

        assertEquals(QueueOutcome.EMPTY, repository.runQueue())

        assertEquals(listOf(ids[2], ids[0], ids[1]), requested.toList())
        assertEquals(1, peakConcurrent.get())
        for (issueId in ids) {
            val state = state(issueId)!!
            assertEquals(DownloadState.DOWNLOADED, state.downloadState)
            assertEquals(120_000L, state.downloadedBytes)
            assertEquals(files.file(issueId).path, state.downloadedPath)
            assertNull(state.downloadQueuedAt)
            assertEquals(etag, state.observedPdfEtag)
            assertArrayEquals(content(issueId), files.file(issueId).readBytes())
            assertFalse(files.partFile(issueId).exists())
        }
        // Senast nedladdad har högst tidsstämpel.
        assertTrue(state(ids[1])!!.downloadedAt!! > state(ids[2])!!.downloadedAt!!)
        assertNull(repository.active.value)
        assertEquals(0, repository.queuedCount())
    }

    @Test
    fun `a second runner does nothing while the queue is being worked off`() = runBlocking {
        responses[ids[0]] = { full(ids[0]).throttleBody(20_000, 100, TimeUnit.MILLISECONDS) }
        val repository = repository()
        repository.requestDownload(ids[0])
        val first = async(Dispatchers.IO) { repository.runQueue() }
        withTimeout(5_000) { while (repository.active.value == null) delay(10) }
        assertEquals(QueueOutcome.ALREADY_RUNNING, repository.runQueue())
        assertEquals(QueueOutcome.EMPTY, first.await())
        assertEquals(listOf(ids[0]), requested.toList())
    }

    @Test
    fun `a request for something already handled changes nothing`() = runBlocking {
        val repository = repository()
        assertEquals(DownloadRequest.QUEUED, repository.requestDownload(ids[0]))
        val queuedAt = state(ids[0])!!.downloadQueuedAt
        assertEquals(DownloadRequest.ALREADY_HANDLED, repository.requestDownload(ids[0]))
        assertEquals(queuedAt, state(ids[0])!!.downloadQueuedAt)
        repository.runQueue()
        assertEquals(DownloadRequest.ALREADY_HANDLED, repository.requestDownload(ids[0]))
        assertEquals(1, requested.size)
        // Ett nummer utan PDF i arkivet kan inte laddas ned.
        assertEquals(DownloadRequest.NOT_AVAILABLE, repository.requestDownload("datormagazin-1986-04"))
        assertEquals(DownloadRequest.NOT_AVAILABLE, repository.requestDownload("finns-inte"))
    }

    // --- Från läs-cachen ---

    @Test
    fun `a file in the reading cache is moved without any network traffic`() = runBlocking {
        putInCache(ids[0])
        store.update(ids[0]) { it.copy(observedPdfSize = 120_000, observedPageCount = 28, detectedSpreadOffset = 1) }
        val repository = repository()

        assertEquals(DownloadRequest.MOVED_FROM_CACHE, repository.requestDownload(ids[0]))

        assertEquals(0, server.requestCount)
        assertFalse(cache.file(ids[0]).exists())
        assertArrayEquals(content(ids[0]), files.file(ids[0]).readBytes())
        val state = state(ids[0])!!
        assertEquals(DownloadState.DOWNLOADED, state.downloadState)
        assertEquals(120_000L, state.downloadedBytes)
        assertEquals(files.file(ids[0]).path, state.downloadedPath)
        // Det läsaren har sparat om numret finns kvar.
        assertEquals(28, state.observedPageCount)
        assertEquals(1, state.detectedSpreadOffset)
        // Kön är tom: ingenting hämtas.
        assertEquals(QueueOutcome.EMPTY, repository.runQueue())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `when rename fails the file is copied, verified and the cache copy removed`() = runBlocking {
        putInCache(ids[0])
        val attempts = ArrayList<String>()
        val repository = repository(move = { source, target, part ->
            movePdf(source, target, part) { from, to ->
                attempts += "${from.name} -> ${to.name}"
                // Flytt mellan filsystem går inte. Inom målkatalogen går det.
                if (from.parentFile == to.parentFile) from.renameTo(to) else false
            }
        })

        assertEquals(DownloadRequest.MOVED_FROM_CACHE, repository.requestDownload(ids[0]))

        assertEquals(
            listOf("${ids[0]}.pdf -> ${ids[0]}.pdf", "${ids[0]}.pdf.part -> ${ids[0]}.pdf"),
            attempts,
        )
        assertEquals(0, server.requestCount)
        assertArrayEquals(content(ids[0]), files.file(ids[0]).readBytes())
        assertFalse(cache.file(ids[0]).exists())
        assertFalse(files.partFile(ids[0]).exists())
        assertEquals(DownloadState.DOWNLOADED, downloadState(ids[0]))
    }

    @Test
    fun `when the move fails completely the issue is queued and the cache file is untouched`() = runBlocking {
        putInCache(ids[0])
        val repository = repository(move = { source, target, part -> movePdf(source, target, part) { _, _ -> false } })

        assertEquals(DownloadRequest.QUEUED, repository.requestDownload(ids[0]))

        assertTrue(cache.file(ids[0]).isFile)
        assertFalse(files.file(ids[0]).exists())
        assertFalse(files.partFile(ids[0]).exists())
        assertEquals(DownloadState.QUEUED, downloadState(ids[0]))
        assertTrue(log.any { "flytten från läs-cachen misslyckades" in it })
    }

    @Test
    fun `a cache file of the wrong size is not trusted`() = runBlocking {
        putInCache(ids[0], content(ids[0]).copyOf(50_000))
        val repository = repository()
        assertEquals(DownloadRequest.QUEUED, repository.requestDownload(ids[0]))
        assertFalse(files.file(ids[0]).exists())
        repository.runQueue()
        assertEquals(120_000L, files.file(ids[0]).length())
        // Den ofullständiga kopian i cachen behövs inte längre.
        assertFalse(cache.file(ids[0]).exists())
    }

    @Test
    fun `movePdf leaves the source alone when the copy cannot be put in place`() {
        putInCache(ids[0])
        val target = files.file(ids[0])
        val part = files.partFile(ids[0])
        assertFalse(movePdf(cache.file(ids[0]), target, part) { _, _ -> false })
        assertTrue(cache.file(ids[0]).isFile)
        assertFalse(target.exists())
        assertFalse(part.exists())
        assertFalse(movePdf(File(root, "saknas.pdf"), target, part))
        // Och den vanliga vägen: renameTo.
        assertTrue(movePdf(cache.file(ids[0]), target, part))
        assertArrayEquals(content(ids[0]), target.readBytes())
        assertFalse(cache.file(ids[0]).exists())
    }

    // --- Status mot filsystemet ---

    @Test
    fun `status is checked against the file system at start`() = runBlocking {
        files.directory.mkdirs()
        // 1: nedladdad och hel. 2: nedladdad men filen saknas. 3: nedladdad men fel storlek.
        files.file(ids[0]).writeBytes(content(ids[0]))
        files.file(ids[2]).writeBytes(ByteArray(10))
        for (issueId in ids) {
            store.update(issueId) {
                it.copy(
                    downloadState = DownloadState.DOWNLOADED, downloadedBytes = 120_000,
                    downloadedPath = files.file(issueId).path, downloadedAt = 5, observedPageCount = 28,
                )
            }
        }
        // Kvarlämnad .part utan köpost, och en .part som hör till ett köat nummer.
        files.partFile("datormagazin-1987-01").writeBytes(ByteArray(100))
        files.validatorFile("datormagazin-1987-01").writeText(etag)
        files.partFile("datormagazin-1987-02").writeBytes(ByteArray(100))
        store.update("datormagazin-1987-02") { it.copy(downloadState = DownloadState.QUEUED) }
        // Appen stoppades mitt i en nedladdning.
        files.partFile("datormagazin-1987-03").writeBytes(ByteArray(100))
        store.update("datormagazin-1987-03") { it.copy(downloadState = DownloadState.DOWNLOADING) }

        repository(reconcile = false).reconcile()

        assertEquals(DownloadState.DOWNLOADED, downloadState(ids[0]))
        for (issueId in listOf(ids[1], ids[2])) {
            val state = state(issueId)!!
            assertEquals(DownloadState.NONE, state.downloadState)
            assertNull(state.downloadedBytes)
            assertNull(state.downloadedPath)
            assertNull(state.downloadedAt)
            // Bara nedladdningsfälten nollställs.
            assertEquals(28, state.observedPageCount)
            assertFalse(files.file(issueId).exists())
        }
        assertFalse(files.partFile("datormagazin-1987-01").exists())
        assertFalse(files.validatorFile("datormagazin-1987-01").exists())
        assertTrue(files.partFile("datormagazin-1987-02").isFile)
        assertEquals(DownloadState.QUEUED, downloadState("datormagazin-1987-03"))
        assertTrue(files.partFile("datormagazin-1987-03").isFile)
    }

    @Test
    fun `a downloaded file is verified when it is opened`() = runBlocking {
        val repository = repository()
        repository.requestDownload(ids[0])
        repository.runQueue()
        assertEquals(files.file(ids[0]), repository.verifiedFile(ids[0]))
        assertNull(repository.verifiedFile(ids[1]))

        // Filen har blivit kortare, till exempel efter ett diskfel.
        files.file(ids[0]).writeBytes(ByteArray(99))
        assertNull(repository.verifiedFile(ids[0]))
        assertEquals(DownloadState.NONE, downloadState(ids[0]))
        assertFalse(files.file(ids[0]).exists())
    }

    // --- Borttagning och avbrott ---

    @Test
    fun `removing a download deletes the file and resets the status`() = runBlocking {
        val repository = repository()
        ids.forEach { repository.requestDownload(it) }
        repository.runQueue()
        store.update(ids[0]) { it.copy(observedPageCount = 28) }
        assertEquals(360_000L, repository.totalBytes())

        repository.remove(ids[0])

        assertFalse(files.file(ids[0]).exists())
        val state = state(ids[0])!!
        assertEquals(DownloadState.NONE, state.downloadState)
        assertNull(state.downloadedBytes)
        assertEquals(28, state.observedPageCount)
        assertEquals(DownloadState.DOWNLOADED, downloadState(ids[1]))
        assertEquals(240_000L, repository.totalBytes())

        repository.removeAll()
        assertEquals(0L, repository.totalBytes())
        assertTrue(ids.all { downloadState(it) == DownloadState.NONE })
        assertTrue(files.directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a queued download can be cancelled before it starts`() = runBlocking {
        val repository = repository()
        repository.requestDownload(ids[0])
        repository.requestDownload(ids[1])
        repository.cancel(ids[0])
        assertEquals(DownloadState.NONE, downloadState(ids[0]))
        repository.runQueue()
        assertEquals(listOf(ids[1]), requested.toList())
        assertFalse(files.file(ids[0]).exists())
    }

    @Test
    fun `cancelling the running download removes its part file and the queue goes on`() = runBlocking {
        responses[ids[0]] = { full(ids[0]).throttleBody(10_000, 100, TimeUnit.MILLISECONDS) }
        val repository = repository()
        repository.requestDownload(ids[0])
        repository.requestDownload(ids[1])
        val run = async(Dispatchers.IO) { repository.runQueue() }
        withTimeout(5_000) { while ((repository.active.value?.progress?.bytesDownloaded ?: 0) == 0L) delay(10) }
        assertEquals(DownloadState.DOWNLOADING, downloadState(ids[0]))
        assertEquals(ids[0], repository.active.value?.issueId)

        repository.cancel(ids[0])

        assertEquals(QueueOutcome.EMPTY, withTimeout(10_000) { run.await() })
        assertEquals(DownloadState.NONE, downloadState(ids[0]))
        assertFalse(files.partFile(ids[0]).exists())
        assertFalse(files.validatorFile(ids[0]).exists())
        assertFalse(files.file(ids[0]).exists())
        // Nästa nummer i kön hämtades ändå.
        assertEquals(DownloadState.DOWNLOADED, downloadState(ids[1]))
    }

    @Test
    fun `when the network is lost the part file stays and the download is resumed later`() = runBlocking {
        var attempt = 0
        responses[ids[0]] = { request ->
            if (attempt++ == 0) {
                full(ids[0]).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            } else {
                val from = request.getHeader("Range")!!.removePrefix("bytes=").removeSuffix("-").toInt()
                assertEquals(etag, request.getHeader("If-Range"))
                partial(ids[0], from)
            }
        }
        val repository = repository()
        repository.requestDownload(ids[0])
        repository.requestDownload(ids[1])

        assertEquals(QueueOutcome.NETWORK_LOST, repository.runQueue())

        // Numret står kvar i kön med sin påbörjade fil, och nästa nummer har inte påbörjats.
        assertEquals(DownloadState.QUEUED, downloadState(ids[0]))
        val partBytes = files.partFile(ids[0]).length()
        assertTrue("part: $partBytes", partBytes in 1 until 120_000)
        assertFalse(files.file(ids[0]).exists())
        assertEquals(listOf(ids[0]), requested.toList())
        assertEquals(120_000L - partBytes + 120_000L, repository.estimatedQueueBytes())

        // Nätet är tillbaka: samma nummer först, från där det slutade.
        assertEquals(QueueOutcome.EMPTY, repository.runQueue())
        assertEquals(listOf(ids[0], ids[0], ids[1]), requested.toList())
        assertArrayEquals(content(ids[0]), files.file(ids[0]).readBytes())
        assertEquals(DownloadState.DOWNLOADED, downloadState(ids[0]))
        assertEquals(DownloadState.DOWNLOADED, downloadState(ids[1]))
        assertTrue(log.any { "återupptagen=true" in it })
    }

    @Test
    fun `a server error marks the issue as failed and the queue goes on`() = runBlocking {
        responses[ids[0]] = { MockResponse().setResponseCode(404) }
        val repository = repository()
        repository.requestDownload(ids[0])
        repository.requestDownload(ids[1])
        assertEquals(QueueOutcome.EMPTY, repository.runQueue())
        assertEquals(DownloadState.FAILED, downloadState(ids[0]))
        assertEquals(DownloadState.DOWNLOADED, downloadState(ids[1]))

        // Försök igen ställer numret i kön på nytt, och Avbryt nollställer det.
        responses.remove(ids[0])
        assertEquals(DownloadRequest.QUEUED, repository.requestDownload(ids[0]))
        repository.cancel(ids[0])
        assertEquals(DownloadState.NONE, downloadState(ids[0]))
    }

    @Test
    fun `when the job is stopped the download goes back to the queue with its part file`() = runBlocking {
        responses[ids[0]] = { full(ids[0]).throttleBody(10_000, 100, TimeUnit.MILLISECONDS) }
        val repository = repository()
        repository.requestDownload(ids[0])
        val scope = CoroutineScope(Dispatchers.IO)
        val job = scope.launch { repository.runQueue() }
        withTimeout(5_000) { while ((repository.active.value?.progress?.bytesDownloaded ?: 0) == 0L) delay(10) }

        // Systemet stoppar jobbet, till exempel när wifi försvinner.
        job.cancelAndJoin()

        assertEquals(DownloadState.QUEUED, downloadState(ids[0]))
        assertTrue(files.partFile(ids[0]).length() > 0)
        assertTrue(files.validatorFile(ids[0]).isFile)
        assertNull(repository.active.value)

        // Efter omstart av appen: avstämningen behåller .part, och kön fortsätter med Range.
        responses[ids[0]] = { request ->
            partial(ids[0], request.getHeader("Range")!!.removePrefix("bytes=").removeSuffix("-").toInt())
        }
        val restarted = repository()
        assertTrue(restarted.hasQueued())
        assertEquals(QueueOutcome.EMPTY, restarted.runQueue())
        assertArrayEquals(content(ids[0]), files.file(ids[0]).readBytes())
        assertEquals(DownloadState.DOWNLOADED, downloadState(ids[0]))
    }

    @Test
    fun `local state changes never overwrite each other's fields`() = runBlocking {
        store.update(ids[0]) { it.copy(observedPageCount = 28) }
        store.update(ids[0]) { it.copy(detectedSpreadOffset = 1) }
        store.update(ids[0]) { it.copy(downloadState = DownloadState.QUEUED, downloadQueuedAt = 3) }
        assertEquals(
            IssueLocalState(
                issueId = ids[0], observedPageCount = 28, detectedSpreadOffset = 1,
                downloadState = DownloadState.QUEUED, downloadQueuedAt = 3,
            ),
            store.get(ids[0]),
        )
    }
}
