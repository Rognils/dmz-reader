package se.c64.dmzreader

import android.content.Context
import androidx.room3.useWriterConnection
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import se.c64.dmzreader.data.network.LiveArchiveGuard
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * En lokal server på telefonen som står i stället för arkivet i de instrumenterade testerna.
 * Automatiska tester får aldrig hämta från dmzarkivet.se (CLAUDE.md, regel 12).
 *
 * Medan servern är igång pekas appens förfrågningar till arkivet om hit, med samma sökväg
 * ([LiveArchiveGuard.replacement]). Den svarar med egengjort material ur testpaketets assets:
 *
 *   sökvägar som slutar på .pdf            test_issue.pdf, ett påhittat nummer på sex sidor. Stöder Range och If-Range.
 *   sökvägar som slutar på .jpg eller .png   test_cover.jpg, ett påhittat omslag
 *
 * Filerna görs av tools/make_test_media.py.
 */
object LocalArchive {
    const val PDF_ETAG = "\"dmz-test-pdf-1\""
    const val PDF_PAGES = 6

    /** En förfrågan som servern har tagit emot. */
    data class Seen(val path: String, val range: String?, val ifRange: String?, val userAgent: String?)

    val requests = CopyOnWriteArrayList<Seen>()

    /** Så många av de kommande PDF-svaren bryts mitt i överföringen, som när nätet försvinner. */
    val cutPdfResponses = AtomicInteger(0)

    /** Fördröjning före varje bild, så att flera förfrågningar hinner vara igång samtidigt. */
    @Volatile
    var imageDelayMillis = 0L

    /** Stryper PDF-svaret, så att nedladdningsvyn hinner synas. 0 betyder full fart. */
    @Volatile
    var pdfBytesPerSecond = 0L

    private val testContext get() = InstrumentationRegistry.getInstrumentation().context
    val pdf: ByteArray by lazy { testContext.assets.open("test_issue.pdf").use { it.readBytes() } }
    val cover: ByteArray by lazy { testContext.assets.open("test_cover.jpg").use { it.readBytes() } }

    private var server: MockWebServer? = null

    fun start() {
        stop()
        requests.clear()
        cutPdfResponses.set(0)
        imageDelayMillis = 0
        pdfBytesPerSecond = 0
        val created = MockWebServer()
        created.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = respond(request)
        }
        created.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
        server = created
        LiveArchiveGuard.replacement = created.url("/")
    }

    fun stop() {
        LiveArchiveGuard.replacement = null
        server?.shutdown()
        server = null
    }

    val pdfRequests: List<Seen> get() = requests.filter { it.path.endsWith(".pdf") }

    private fun respond(request: RecordedRequest): MockResponse {
        val path = request.requestUrl?.encodedPath.orEmpty()
        val range = request.getHeader("Range")
        requests += Seen(path, range, request.getHeader("If-Range"), request.getHeader("User-Agent"))
        return when {
            path.endsWith(".pdf") -> pdfResponse(range, request.getHeader("If-Range"))
            path.endsWith(".jpg") || path.endsWith(".png") -> MockResponse()
                .setHeader("Content-Type", "image/jpeg")
                .setBody(Buffer().write(cover))
                .setBodyDelay(imageDelayMillis, TimeUnit.MILLISECONDS)
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun pdfResponse(range: String?, ifRange: String?): MockResponse {
        // "bytes=1000-" ger resten av filen, om den som frågar har rätt version (If-Range).
        val from = Regex("""bytes=(\d+)-""").matchEntire(range.orEmpty())?.groupValues?.get(1)?.toLong()
            ?.takeIf { (ifRange == null || ifRange == PDF_ETAG) && it < pdf.size }
        val response = MockResponse()
            .setHeader("Content-Type", "application/pdf")
            .setHeader("ETag", PDF_ETAG)
            .setHeader("Accept-Ranges", "bytes")
        if (from != null) {
            response.setResponseCode(206)
                .setHeader("Content-Range", "bytes $from-${pdf.size - 1}/${pdf.size}")
                .setBody(Buffer().write(pdf, from.toInt(), pdf.size - from.toInt()))
        } else {
            response.setBody(Buffer().write(pdf))
        }
        if (pdfBytesPerSecond > 0) response.throttleBody(pdfBytesPerSecond / 10, 100, TimeUnit.MILLISECONDS)
        if (cutPdfResponses.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0) {
            response.socketPolicy = SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY
        }
        return response
    }

    /**
     * Gör numret [issueId] i testkörningens databas till testnumret: storlek, ETag och sidantal
     * som test_issue.pdf. Ger en funktion som ställer tillbaka raden.
     */
    fun useTestPdfFor(context: Context, issueId: String): () -> Unit {
        val database = appContainer(context).database
        var original: List<Any?> = emptyList()
        runBlocking {
            database.useWriterConnection { connection ->
                connection.usePrepared("SELECT ocrPdfSize, ocrPdfEtag, ocrPdfLastModified, pageCount FROM Issue WHERE issueId = ?") { statement ->
                    statement.bindText(1, issueId)
                    check(statement.step()) { "$issueId finns inte i testkörningens arkivindex" }
                    original = listOf(
                        if (statement.isNull(0)) null else statement.getLong(0),
                        if (statement.isNull(1)) null else statement.getText(1),
                        if (statement.isNull(2)) null else statement.getText(2),
                        if (statement.isNull(3)) null else statement.getLong(3),
                    )
                }
                connection.usePrepared("UPDATE Issue SET ocrPdfSize = ?, ocrPdfEtag = ?, ocrPdfLastModified = NULL, pageCount = ? WHERE issueId = ?") { statement ->
                    statement.bindLong(1, pdf.size.toLong())
                    statement.bindText(2, PDF_ETAG)
                    statement.bindLong(3, PDF_PAGES.toLong())
                    statement.bindText(4, issueId)
                    statement.step()
                }
            }
        }
        return {
            runBlocking {
                database.useWriterConnection { connection ->
                    connection.usePrepared("UPDATE Issue SET ocrPdfSize = ?, ocrPdfEtag = ?, ocrPdfLastModified = ?, pageCount = ? WHERE issueId = ?") { statement ->
                        original.forEachIndexed { index, value ->
                            when (value) {
                                null -> statement.bindNull(index + 1)
                                is Long -> statement.bindLong(index + 1, value)
                                else -> statement.bindText(index + 1, value.toString())
                            }
                        }
                        statement.bindText(5, issueId)
                        statement.step()
                    }
                }
            }
        }
    }
}
