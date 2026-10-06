package se.c64.dmzreader.data.pdf

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PdfDownloaderTest {
    private lateinit var server: MockWebServer
    private lateinit var directory: File
    private lateinit var cache: PdfCache
    private var freeBytes = Long.MAX_VALUE

    private val content = ByteArray(200_000) { (it * 31 % 251).toByte() }
    private val etag = "\"1504986566.0-200000-984024500\""
    private val issueId = "datormagazin-1986-01"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        directory = Files.createTempDirectory("dmz-pdf").toFile()
        cache = PdfCache(File(directory, "pdf"))
    }

    @After
    fun tearDown() {
        server.shutdown()
        directory.deleteRecursively()
    }

    private fun downloader() = PdfDownloader(PdfDownloader.createHttpClient(), cache) { freeBytes }
    private fun url() = server.url("/pdf/198601.pdf").toString()

    private fun fullResponse(bytes: ByteArray = content) = MockResponse()
        .setResponseCode(200)
        .setHeader("ETag", etag)
        .setHeader("Last-Modified", "Sat, 09 Sep 2017 19:49:26 GMT")
        .setBody(Buffer().write(bytes))

    private fun partialResponse(from: Int) = MockResponse()
        .setResponseCode(206)
        .setHeader("ETag", etag)
        .setHeader("Content-Range", "bytes $from-${content.size - 1}/${content.size}")
        .setBody(Buffer().write(content, from, content.size - from))

    private fun download(expectedSize: Long? = content.size.toLong(), progress: (DownloadProgress) -> Unit = {}) =
        runBlocking { downloader().download(issueId, url(), expectedSize, progress) }

    private fun leavePart(bytes: Int, validator: String? = etag) {
        cache.directory.mkdirs()
        cache.partFile(issueId).writeBytes(content.copyOf(bytes))
        if (validator != null) cache.validatorFile(issueId).writeText(validator)
    }

    @Test
    fun `a whole download ends up as a complete pdf`() {
        server.enqueue(fullResponse())
        val seen = ArrayList<DownloadProgress>()

        val result = download { seen += it }

        assertTrue(result is DownloadResult.Success)
        result as DownloadResult.Success
        assertEquals(cache.file(issueId), result.file)
        assertArrayEquals(content, result.file.readBytes())
        assertEquals(content.size.toLong(), result.size)
        assertEquals(etag, result.etag)
        assertEquals("Sat, 09 Sep 2017 19:49:26 GMT", result.lastModified)
        assertFalse(result.resumed)
        assertFalse(cache.partFile(issueId).exists())
        assertFalse(cache.validatorFile(issueId).exists())

        assertEquals(0L, seen.first().bytesDownloaded)
        assertEquals(DownloadProgress(content.size.toLong(), content.size.toLong()), seen.last())
        assertEquals(1f, seen.last().fraction)
        assertTrue(seen.zipWithNext().all { (a, b) -> a.bytesDownloaded <= b.bytesDownloaded })

        val request = server.takeRequest()
        assertNull(request.getHeader("Range"))
        assertNull(request.getHeader("If-Range"))
    }

    @Test
    fun `a partial file is resumed with range and if-range`() {
        leavePart(80_000)
        server.enqueue(partialResponse(80_000))

        val result = download()

        assertTrue(result is DownloadResult.Success)
        result as DownloadResult.Success
        assertTrue(result.resumed)
        assertArrayEquals(content, result.file.readBytes())
        val request = server.takeRequest()
        assertEquals("bytes=80000-", request.getHeader("Range"))
        assertEquals(etag, request.getHeader("If-Range"))
        assertFalse(cache.partFile(issueId).exists())
    }

    @Test
    fun `a server that answers 200 to a range request makes the download start over`() {
        // .part-filen innehåller annat än den nya filen: servern har bytt fil.
        cache.directory.mkdirs()
        cache.partFile(issueId).writeBytes(ByteArray(80_000) { 7 })
        cache.validatorFile(issueId).writeText("\"gammal\"")
        server.enqueue(fullResponse())

        val result = download()

        assertTrue(result is DownloadResult.Success)
        result as DownloadResult.Success
        assertFalse(result.resumed)
        assertArrayEquals(content, result.file.readBytes())
        assertEquals("\"gammal\"", server.takeRequest().getHeader("If-Range"))
    }

    @Test
    fun `a partial file without a validator is not trusted`() {
        leavePart(80_000, validator = null)
        server.enqueue(fullResponse())

        val result = download()

        assertTrue(result is DownloadResult.Success)
        assertNull(server.takeRequest().getHeader("Range"))
        assertArrayEquals(content, (result as DownloadResult.Success).file.readBytes())
    }

    @Test
    fun `a short body never becomes a pdf`() {
        // Servern lovar 200 000 byte men stänger efter hälften.
        server.enqueue(
            fullResponse().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )

        val result = download()

        assertTrue("$result", result is DownloadResult.NetworkError || result is DownloadResult.LengthMismatch)
        assertFalse(cache.file(issueId).exists())
        // Det som kom ligger kvar för återupptagning, tillsammans med valideraren.
        assertTrue(cache.partFile(issueId).length() < content.size)
        if (cache.partFile(issueId).exists()) assertEquals(etag, cache.validatorFile(issueId).readText())
    }

    @Test
    fun `a body longer than announced is rejected and thrown away`() {
        // Content-Range säger 200 000 byte totalt, men kroppen är längre.
        leavePart(80_000)
        server.enqueue(
            MockResponse().setResponseCode(206).setHeader("ETag", etag)
                .setHeader("Content-Range", "bytes 80000-199999/200000")
                .setBody(Buffer().write(ByteArray(150_000))),
        )

        val result = download()

        assertEquals(DownloadResult.LengthMismatch(expected = 200_000, actual = 230_000), result)
        assertFalse(cache.file(issueId).exists())
        assertFalse(cache.partFile(issueId).exists())
    }

    @Test
    fun `a 206 for another range than the requested is rejected`() {
        leavePart(80_000)
        server.enqueue(
            MockResponse().setResponseCode(206).setHeader("ETag", etag)
                .setHeader("Content-Range", "bytes 0-199999/200000")
                .setBody(Buffer().write(content)),
        )

        val result = download()

        assertTrue(result is DownloadResult.LengthMismatch)
        assertFalse(cache.file(issueId).exists())
        assertEquals(80_000L, cache.partFile(issueId).length())
    }

    @Test
    fun `an interrupted download can be resumed and gives the right file`() {
        server.enqueue(fullResponse().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val first = download()
        assertFalse(first is DownloadResult.Success)
        val have = cache.partFile(issueId).length().toInt()
        assertTrue(have in 1 until content.size)

        server.enqueue(partialResponse(have))
        val second = download()

        assertTrue(second is DownloadResult.Success)
        assertArrayEquals(content, cache.file(issueId).readBytes())
        server.takeRequest()
        assertEquals("bytes=$have-", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun `cancelling leaves the part file but never a pdf`() {
        val started = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                // Långsam server: 20 kB per 100 ms, så att avbrottet hinner komma mitt i.
                fullResponse().throttleBody(20_000, 100, TimeUnit.MILLISECONDS)
        }
        val scope = CoroutineScope(Dispatchers.IO)

        runBlocking {
            val job = scope.launch {
                downloader().download(issueId, url(), content.size.toLong()) { progress ->
                    if (progress.bytesDownloaded > 0) started.countDown()
                }
            }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            val before = System.nanoTime()
            job.cancelAndJoin()
            val cancelMillis = (System.nanoTime() - before) / 1_000_000
            assertTrue(job.isCancelled)
            // Avbrottet ska inte vänta ut nedladdningen (som tar en sekund).
            assertTrue("avbrottet tog $cancelMillis ms", cancelMillis < 700)
        }

        assertFalse(cache.file(issueId).exists())
        val part = cache.partFile(issueId)
        assertTrue(part.isFile)
        assertTrue(part.length() in 1 until content.size)
        // Det som hann komma är en riktig början på filen och går att fortsätta på.
        assertArrayEquals(content.copyOf(part.length().toInt()), part.readBytes())
        assertEquals(etag, cache.validatorFile(issueId).readText())
    }

    @Test
    fun `cancellation is reported as cancellation and not as a network error`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                fullResponse().throttleBody(10_000, 200, TimeUnit.MILLISECONDS)
        }
        val scope = CoroutineScope(Dispatchers.IO)
        runBlocking {
            val started = CountDownLatch(1)
            val deferred = scope.async {
                downloader().download(issueId, url()) { if (it.bytesDownloaded > 0) started.countDown() }
            }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            deferred.cancel()
            try {
                deferred.await()
                throw AssertionError("väntade ett avbrott")
            } catch (e: CancellationException) {
                // väntat
            }
        }
    }

    @Test
    fun `too little space is reported before anything is requested`() {
        freeBytes = 150_000 // filen är 200 000 byte och kräver 220 000 med marginal

        val result = download()

        assertEquals(DownloadResult.InsufficientSpace(requiredBytes = 220_000, availableBytes = 150_000), result)
        assertEquals(0, server.requestCount)
        assertFalse(cache.partFile(issueId).exists())
    }

    @Test
    fun `space is checked against the remaining bytes when resuming`() {
        leavePart(150_000)
        freeBytes = 75_000 // återstår 50 000, plus marginal 20 000
        server.enqueue(partialResponse(150_000))

        assertTrue(download() is DownloadResult.Success)
    }

    @Test
    fun `space is checked against the server size when the inventory has none`() {
        freeBytes = 150_000
        server.enqueue(fullResponse())

        val result = download(expectedSize = null)

        assertEquals(DownloadResult.InsufficientSpace(220_000, 150_000), result)
        assertFalse(cache.file(issueId).exists())
        assertFalse(cache.partFile(issueId).exists())
    }

    @Test
    fun `http errors and an unreachable server are reported`() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(DownloadResult.HttpError(500), download())

        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(DownloadResult.HttpError(404), download())

        server.shutdown()
        assertTrue(download() is DownloadResult.NetworkError)
        assertFalse(cache.file(issueId).exists())
    }

    @Test
    fun `a part file longer than the server file is discarded`() {
        leavePart(150_000)
        server.enqueue(MockResponse().setResponseCode(416))
        assertEquals(DownloadResult.HttpError(416), download())
        assertFalse(cache.partFile(issueId).exists())

        server.enqueue(fullResponse())
        assertTrue(download() is DownloadResult.Success)
    }

    @Test
    fun `an existing pdf is replaced only by a complete new one`() {
        cache.directory.mkdirs()
        cache.file(issueId).writeBytes(ByteArray(10) { 1 })
        server.enqueue(fullResponse().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        assertFalse(download() is DownloadResult.Success)
        assertEquals(10L, cache.file(issueId).length())

        server.enqueue(partialResponse(cache.partFile(issueId).length().toInt()))
        assertTrue(download() is DownloadResult.Success)
        assertArrayEquals(content, cache.file(issueId).readBytes())
    }

    @Test
    fun `content range is parsed`() {
        assertEquals(100L to 1000L, PdfDownloader.parseContentRange("bytes 100-999/1000"))
        assertNull(PdfDownloader.parseContentRange("bytes */1000"))
        assertNull(PdfDownloader.parseContentRange(null))
    }

    @Test
    fun `the pdf client has the decided timeouts`() {
        val client = PdfDownloader.createHttpClient()
        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(60_000, client.readTimeoutMillis)
        assertEquals(0, client.callTimeoutMillis)
    }
}
