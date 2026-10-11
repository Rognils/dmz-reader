package se.c64.dmzreader.data.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.c64.dmzreader.data.hq.FtpAccount
import se.c64.dmzreader.data.hq.FtpScanSource
import se.c64.dmzreader.data.hq.ScanFetchException
import se.c64.dmzreader.data.pdf.PdfDownloader
import java.io.IOException
import java.nio.file.Files

/**
 * Skyddsnätet som hindrar tester från att nå arkivservern (CLAUDE.md, regel 12).
 * Testerna här försöker med flit, och ska bli stoppade innan något lämnar datorn.
 */
class LiveArchiveGuardTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var logProperty: String? = null
    private var violationsBefore = 0

    @Before
    fun setUp() {
        // De avsiktliga försöken här ska inte hamna i filen som visar att testkörningen var ren.
        logProperty = System.clearProperty(LiveArchiveGuard.LOG_PROPERTY)
        violationsBefore = LiveArchiveGuard.violations.get()
        LiveArchiveGuard.blocked = true
        LiveArchiveGuard.replacement = null
        server.start()
    }

    @After
    fun tearDown() {
        LiveArchiveGuard.replacement = null
        LiveArchiveGuard.blocked = true
        LiveArchiveGuard.violations.set(violationsBefore)
        logProperty?.let { System.setProperty(LiveArchiveGuard.LOG_PROPERTY, it) }
        server.shutdown()
        scope.cancel()
    }

    private fun get(client: OkHttpClient, url: String) = client.newCall(Request.Builder().url(url).build()).execute()

    @Test
    fun `the test run starts with the guard on`() {
        assertEquals("true", System.getProperty(LiveArchiveGuard.BLOCK_PROPERTY))
        assertTrue(LiveArchiveGuard.blocked)
    }

    @Test
    fun `the test jvm cannot even look up the archive`() {
        // Namnuppslag går mot en fil som bara känner den egna datorn (jdk.net.hosts.file).
        assertThrows(java.net.UnknownHostException::class.java) { java.net.InetAddress.getByName("dmzarkivet.se") }
        assertTrue(java.net.InetAddress.getByName("localhost").isLoopbackAddress)
    }

    @Test
    fun `the archive is recognised with and without a subdomain`() {
        assertTrue(LiveArchiveGuard.isArchive("dmzarkivet.se"))
        assertTrue(LiveArchiveGuard.isArchive("DMZArkivet.se"))
        assertTrue(LiveArchiveGuard.isArchive("ftp.dmzarkivet.se"))
        assertFalse(LiveArchiveGuard.isArchive("localhost"))
        assertFalse(LiveArchiveGuard.isArchive("inte-dmzarkivet.se"))
        assertFalse(LiveArchiveGuard.isArchive("dmzarkivet.se.example.org"))
    }

    @Test
    fun `both http clients refuse to reach the archive`() {
        for (client in listOf(PdfDownloader.createHttpClient(), CoverImageLoader.createHttpClient(logRequests = false))) {
            val error = assertThrows(IOException::class.java) { get(client, "https://dmzarkivet.se/pdf/198601.pdf") }
            assertTrue(error.message, error.message!!.startsWith("SPÄRRAD"))
        }
        assertEquals(violationsBefore + 2, LiveArchiveGuard.violations.get())
    }

    @Test
    fun `other hosts are not affected`() {
        server.enqueue(MockResponse().setBody("ok"))
        get(PdfDownloader.createHttpClient(), server.url("/pdf/198601.pdf").toString()).use { response ->
            assertEquals("ok", response.body.string())
        }
        assertEquals(violationsBefore, LiveArchiveGuard.violations.get())
    }

    @Test
    fun `with a replacement the archive's addresses go to the local server with the same path`() {
        LiveArchiveGuard.replacement = server.url("/")
        server.enqueue(MockResponse().setBody("lokal"))
        get(PdfDownloader.createHttpClient(), "https://dmzarkivet.se/magazines/199301/DMZ_1993-01-01.jpg?x=1").use { response ->
            assertEquals("lokal", response.body.string())
        }
        val request = server.takeRequest()
        assertEquals("/magazines/199301/DMZ_1993-01-01.jpg?x=1", request.path)
        assertTrue(request.getHeader("User-Agent")!!.startsWith("DMZReader/"))
        assertEquals(violationsBefore, LiveArchiveGuard.violations.get())
    }

    @Test
    fun `the ftp source refuses to connect to the archive`() {
        val source = FtpScanSource(account = FtpAccount("dmz", "dmz"), scope = scope)
        val target = Files.createTempFile("dmz-guard", ".part").toFile()
        val error = assertThrows(ScanFetchException::class.java) {
            runBlocking { source.fetch("ftp://dmzarkivet.se/Original%20Scans/198601-Original/DMZ_1986-01-001.jpg", target) { _, _ -> } }
        }
        assertTrue(error.message, "SPÄRRAD" in error.message.orEmpty() || "SPÄRRAD" in error.cause?.message.orEmpty())
        assertEquals(violationsBefore + 1, LiveArchiveGuard.violations.get())
        target.delete()
    }

    @Test
    fun `without the guard nothing is stopped by it`() {
        LiveArchiveGuard.blocked = false
        // Kastar inte. (Anropet ansluter inte: det är bara kontrollen som prövas.)
        LiveArchiveGuard.check("dmzarkivet.se", "prov")
        assertEquals(violationsBefore, LiveArchiveGuard.violations.get())
        assertNull(LiveArchiveGuard.replacement)
    }
}
