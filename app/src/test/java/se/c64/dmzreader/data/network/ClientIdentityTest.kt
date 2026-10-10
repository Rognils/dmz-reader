package se.c64.dmzreader.data.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.c64.dmzreader.BuildConfig
import se.c64.dmzreader.data.pdf.PdfDownloader

/** Appen presenterar sig för arkivservern med samma User-Agent i all HTTP-trafik. */
class ClientIdentityTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.shutdown()

    private fun userAgentSentBy(client: OkHttpClient, request: Request.Builder.() -> Unit = {}): String? {
        server.enqueue(MockResponse().setBody("ok"))
        client.newCall(Request.Builder().url(server.url("/pdf/198601.pdf")).apply(request).build()).execute().close()
        return server.takeRequest().getHeader("User-Agent")
    }

    @Test
    fun `the identity names the app, the version from the build and where to turn`() {
        assertEquals(
            "DMZReader/${BuildConfig.VERSION_NAME} (Android; +https://github.com/Rognils/dmz-reader)",
            ClientIdentity.userAgent,
        )
        assertEquals("DMZReader/1.0.0 (Android; +https://github.com/Rognils/dmz-reader)", ClientIdentity.userAgent("1.0.0"))
        // Versionen är inte hårdkodad: den följer bygget.
        assertTrue(BuildConfig.VERSION_NAME.isNotBlank())
        assertTrue(BuildConfig.VERSION_NAME in ClientIdentity.userAgent)
    }

    @Test
    fun `the pdf client sends the user agent`() {
        assertEquals(ClientIdentity.userAgent, userAgentSentBy(PdfDownloader.createHttpClient()))
    }

    @Test
    fun `the cover client, which coil uses, sends the same user agent`() {
        assertEquals(ClientIdentity.userAgent, userAgentSentBy(CoverImageLoader.createHttpClient(logRequests = false)))
    }

    @Test
    fun `the user agent replaces the one from the http library and any set by the caller`() {
        val client = PdfDownloader.createHttpClient()
        val sent = userAgentSentBy(client) { header("User-Agent", "en-annan-klient/1.0") }
        assertEquals(ClientIdentity.userAgent, sent)
        assertFalse(sent!!.startsWith("okhttp"))
    }

    @Test
    fun `range requests for resumed downloads carry it too`() {
        val sent = userAgentSentBy(PdfDownloader.createHttpClient()) { header("Range", "bytes=100-") }
        assertEquals(ClientIdentity.userAgent, sent)
    }

    @Test
    fun `the header that is sent can be logged`() {
        val lines = ArrayList<String>()
        userAgentSentBy(PdfDownloader.createHttpClient(logUserAgent = { lines += it }))
        assertEquals(1, lines.size)
        assertTrue(lines.single(), lines.single().startsWith("User-Agent: ${ClientIdentity.userAgent} · GET "))
        assertTrue(lines.single(), lines.single().endsWith("/pdf/198601.pdf"))
    }
}
