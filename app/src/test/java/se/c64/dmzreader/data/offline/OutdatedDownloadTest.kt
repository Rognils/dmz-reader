package se.c64.dmzreader.data.offline

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.c64.dmzreader.data.db.DmzDatabase
import se.c64.dmzreader.data.db.DownloadState
import se.c64.dmzreader.data.pdf.PdfCache
import se.c64.dmzreader.data.pdf.PdfDownloader
import se.c64.dmzreader.data.pdf.PdfFiles
import se.c64.dmzreader.data.pdf.PdfRepository
import se.c64.dmzreader.domain.DOWNLOAD_OUTDATED_TEXT
import se.c64.dmzreader.domain.IssueWithState
import se.c64.dmzreader.domain.downloadIsOutdated
import se.c64.dmzreader.testing.inMemoryDatabase
import se.c64.dmzreader.testing.issueEntity
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * En nedladdad kopia som är äldre än arkivfilen (planens C7): den raderas
 * aldrig automatiskt, och "Uppdatera nedladdning" ersätter den först när den
 * nya filen är komplett.
 */
class OutdatedDownloadTest {
    private lateinit var server: MockWebServer
    private lateinit var root: File
    private lateinit var files: PdfFiles
    private lateinit var cache: PdfCache
    private lateinit var db: DmzDatabase
    private lateinit var store: LocalStateStore
    private lateinit var offline: OfflineRepository
    private val log = CopyOnWriteArrayList<String>()

    private val id = "datormagazin-1995-14"
    private val oldContent = ByteArray(80_000) { (it * 3).toByte() }
    private val newContent = ByteArray(120_000) { (it * 7 + 1).toByte() }
    private val oldEtag = "\"gammal-80000\""
    private val newEtag = "\"ny-120000\""

    @Volatile
    private var respond: (RecordedRequest) -> MockResponse = { serve(oldContent, oldEtag) }

    private fun serve(content: ByteArray, etag: String) =
        MockResponse().setResponseCode(200).setHeader("ETag", etag).setBody(Buffer().write(content))

    @Before
    fun setUp() = runBlocking {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = respond(request)
        }
        server.start()
        root = Files.createTempDirectory("dmz-outdated").toFile()
        files = PdfFiles(File(root, "files/issues"))
        cache = PdfCache(File(root, "cache/pdf"))
        db = inMemoryDatabase()
        store = LocalStateStore(db.issueLocalStateDao())
        setCatalogue(oldContent.size.toLong(), oldEtag)
        offline = OfflineRepository(
            issueDao = db.issueDao(), localState = store, files = files, cache = cache,
            downloader = PdfDownloader(PdfDownloader.createHttpClient(), files), log = { log += it },
        )
        offline.reconcile()
        // Numret laddas ned när arkivet har den gamla filen.
        offline.requestDownload(id)
        offline.runQueue()
        assertEquals(DownloadState.DOWNLOADED, state().downloadState)
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
        root.deleteRecursively()
    }

    private suspend fun setCatalogue(size: Long, etag: String) {
        db.issueDao().replaceAll(
            listOf(issueEntity(id).copy(ocrPdfUrl = server.url("/pdf/199514.pdf").toString(), ocrPdfSize = size, ocrPdfEtag = etag)),
        )
    }

    private suspend fun state() = store.get(id)!!
    private suspend fun outdated() = downloadIsOutdated(db.issueDao().get(id)!!, store.get(id))

    /** Ett nytt arkivindex säger att filen har bytts. */
    private suspend fun archiveFileChanges() {
        setCatalogue(newContent.size.toLong(), newEtag)
        respond = { serve(newContent, newEtag) }
    }

    @Test
    fun `a new index never deletes the downloaded copy, it only marks it as older`() = runBlocking {
        assertFalse(outdated())
        archiveFileChanges()

        assertTrue(outdated())
        assertEquals("Arkivfilen har uppdaterats. Din nedladdade kopia är äldre.", DOWNLOAD_OUTDATED_TEXT)
        // Avstämningen vid appstart rör inte kopian, och den går fortfarande att läsa.
        offline.reconcile()
        assertEquals(DownloadState.DOWNLOADED, state().downloadState)
        assertArrayEquals(oldContent, files.file(id).readBytes())
        assertEquals(files.file(id), offline.verifiedFile(id))
        // Ingenting hämtas av sig självt.
        assertEquals(QueueOutcome.EMPTY, offline.runQueue())
        assertEquals(1, server.requestCount)
        val item = IssueWithState(db.issueDao().get(id)!!, null, 84, state())
        assertTrue(item.isDownloaded && item.downloadOutdated && !item.updateRequested)
    }

    @Test
    fun `only a difference in size, etag or date makes a copy outdated`() = runBlocking {
        val issue = db.issueDao().get(id)!!
        val local = state()
        assertFalse(downloadIsOutdated(issue, local))
        assertTrue(downloadIsOutdated(issue.copy(ocrPdfSize = 1), local))
        assertTrue(downloadIsOutdated(issue.copy(ocrPdfEtag = "\"annan\""), local))
        assertTrue(downloadIsOutdated(issue.copy(ocrPdfLastModified = "Mon, 01 Jan 2024"), local.copy(observedPdfLastModified = "Sat, 09 Sep 2017")))
        // Okända uppgifter räknas inte, och ett nummer som inte är nedladdat är aldrig föråldrat.
        assertFalse(downloadIsOutdated(issue.copy(ocrPdfSize = null, ocrPdfEtag = null), local))
        assertFalse(downloadIsOutdated(issue.copy(ocrPdfSize = 1), local.copy(downloadState = DownloadState.NONE)))
        assertFalse(downloadIsOutdated(issue.copy(ocrPdfSize = 1), null))
    }

    @Test
    fun `updating replaces the copy when the new file is complete`() = runBlocking {
        archiveFileChanges()
        val before = state()
        assertTrue(offline.requestUpdate(id))
        // Begärd men inte hämtad: status är fortfarande nedladdad, och den gamla kopian går att läsa.
        assertEquals(DownloadState.DOWNLOADED, state().downloadState)
        assertTrue(IssueWithState(db.issueDao().get(id)!!, null, 84, state()).updateRequested)
        assertEquals(files.file(id), offline.verifiedFile(id))
        assertTrue(offline.hasQueued())

        assertEquals(QueueOutcome.EMPTY, offline.runQueue())

        assertArrayEquals(newContent, files.file(id).readBytes())
        val after = state()
        assertEquals(DownloadState.DOWNLOADED, after.downloadState)
        assertEquals(120_000L, after.downloadedBytes)
        assertEquals(newEtag, after.observedPdfEtag)
        assertNull(after.downloadQueuedAt)
        assertTrue(after.downloadedAt!! > before.downloadedAt!!)
        assertFalse(outdated())
        assertFalse(files.partFile(id).exists())
        assertTrue(offline.failedUpdates.value.isEmpty())
    }

    @Test
    fun `a failed update leaves the old copy in place`() = runBlocking {
        archiveFileChanges()
        respond = { MockResponse().setResponseCode(500) }
        offline.requestUpdate(id)

        assertEquals(QueueOutcome.EMPTY, offline.runQueue())

        assertArrayEquals(oldContent, files.file(id).readBytes())
        val state = state()
        assertEquals(DownloadState.DOWNLOADED, state.downloadState)
        assertEquals(80_000L, state.downloadedBytes)
        assertEquals(oldEtag, state.observedPdfEtag)
        assertNull(state.downloadQueuedAt)
        assertEquals(files.file(id), offline.verifiedFile(id))
        assertEquals(setOf(id), offline.failedUpdates.value)
        assertTrue(outdated())
        assertTrue(log.any { "uppdateringen misslyckades" in it && "den gamla kopian finns kvar" in it })
        // Ett nytt försök tar bort felmarkeringen och lyckas.
        respond = { serve(newContent, newEtag) }
        offline.requestUpdate(id)
        assertTrue(offline.failedUpdates.value.isEmpty())
        offline.runQueue()
        assertArrayEquals(newContent, files.file(id).readBytes())
    }

    @Test
    fun `an update interrupted by the network keeps the old copy and resumes later`() = runBlocking {
        archiveFileChanges()
        var attempt = 0
        respond = { request ->
            if (attempt++ == 0) {
                serve(newContent, newEtag).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            } else {
                val from = request.getHeader("Range")!!.removePrefix("bytes=").removeSuffix("-").toInt()
                MockResponse().setResponseCode(206).setHeader("ETag", newEtag)
                    .setHeader("Content-Range", "bytes $from-119999/120000")
                    .setBody(Buffer().write(newContent, from, newContent.size - from))
            }
        }
        offline.requestUpdate(id)

        assertEquals(QueueOutcome.NETWORK_LOST, offline.runQueue())
        // Den gamla kopian är hel och läsbar, den nya ligger påbörjad bredvid.
        assertArrayEquals(oldContent, files.file(id).readBytes())
        assertEquals(files.file(id), offline.verifiedFile(id))
        assertTrue(files.partFile(id).length() in 1 until 120_000)
        assertEquals(DownloadState.DOWNLOADED, state().downloadState)
        // Appen startas om: avstämningen behåller både kopian och den påbörjade filen.
        offline.reconcile()
        assertTrue(files.partFile(id).isFile)
        assertArrayEquals(oldContent, files.file(id).readBytes())

        assertEquals(QueueOutcome.EMPTY, offline.runQueue())
        assertArrayEquals(newContent, files.file(id).readBytes())
        assertFalse(outdated())
    }

    @Test
    fun `cancelling an update keeps the downloaded copy`() = runBlocking {
        archiveFileChanges()
        // Avbruten innan den har börjat.
        offline.requestUpdate(id)
        offline.cancel(id)
        assertNull(state().downloadQueuedAt)
        assertEquals(DownloadState.DOWNLOADED, state().downloadState)
        assertEquals(QueueOutcome.EMPTY, offline.runQueue())
        assertEquals(1, server.requestCount)

        // Avbruten medan den pågår.
        respond = { serve(newContent, newEtag).throttleBody(10_000, 100, TimeUnit.MILLISECONDS) }
        offline.requestUpdate(id)
        val run = async(Dispatchers.IO) { offline.runQueue() }
        withTimeout(5_000) { while ((offline.active.value?.progress?.bytesDownloaded ?: 0) == 0L) delay(10) }
        assertEquals(DownloadState.DOWNLOADED, state().downloadState)
        offline.cancel(id)
        assertEquals(QueueOutcome.EMPTY, withTimeout(10_000) { run.await() })

        assertArrayEquals(oldContent, files.file(id).readBytes())
        assertEquals(DownloadState.DOWNLOADED, state().downloadState)
        assertNull(state().downloadQueuedAt)
        assertFalse(files.partFile(id).exists())
        assertTrue(log.any { "uppdateringen avbröts" in it })
    }

    @Test
    fun `an update can only be requested for a downloaded issue`() = runBlocking {
        offline.remove(id)
        assertFalse(offline.requestUpdate(id))
        assertFalse(offline.requestUpdate("finns-inte"))
    }

    @Test
    fun `an outdated file in the reading cache is fetched again, but only once`() = runBlocking {
        val reading = PdfRepository(cache, PdfDownloader(PdfDownloader.createHttpClient(), cache), store, log = { log += it })
        val issue = db.issueDao().get(id)!!
        cache.directory.mkdirs()
        cache.file(id).writeBytes(oldContent)
        // Stämmer med indexet: används.
        assertEquals(cache.file(id), reading.usableCachedFile(issue))

        // Indexet säger en annan storlek: cachefilen kastas så att läsaren hämtar om.
        val changed = issue.copy(ocrPdfSize = 120_000)
        assertNull(reading.usableCachedFile(changed))
        assertFalse(cache.file(id).exists())
        // Är filen fortfarande en annan än indexet säger efter omhämtningen är det
        // indexet som är fel. Då används den, i stället för att hämtas om varje gång.
        cache.file(id).writeBytes(oldContent)
        assertEquals(cache.file(id), reading.usableCachedFile(changed))
        // Utan uppgift om storlek i indexet finns inget att jämföra med.
        assertEquals(cache.file(id), reading.usableCachedFile(issue.copy(ocrPdfSize = null)))
    }
}
