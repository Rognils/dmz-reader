package se.c64.dmzreader

import android.util.Log
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.c64.dmzreader.data.search.IndexDownloadState
import se.c64.dmzreader.data.search.SearchIndexSource
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Manuell kontroll av nedladdningskön under störningar: en PDF och sökindexet hämtas samtidigt i
 * kö, båda strypta, från lokala servrar på enheten (aldrig arkivet eller GitHub). Medan de pågår
 * växlar man från datorn nät, wifi-regel, hemknapp, skärm och rotation, och läser av förloppet i
 * loggen (taggen DmzScenario, en rad i sekunden).
 *
 * Körs bara på begäran, och tar den tid störningarna tar (högst [LIMIT_MILLIS]):
 *
 *   adb shell am instrument -w -e dmzScenario true -e class se.c64.dmzreader.DownloadScenarioDeviceTest \
 *       se.c64.dmzreader.debug.test/se.c64.dmzreader.IsolatedTestRunner
 *
 * Wifi-regeln ändras från datorn genom att skriva "wifiOnly on" eller "wifiOnly off" i
 * /data/local/tmp/dmz-scenario-cmd, och "retry" trycker Försök igen i Sök-kortet. Testet läser och tar
 * bort filen varje sekund. Sök-fliken är öppen, och kortets text skrivs i loggen (KORT=…) som den ritas.
 *
 * När båda hämtningarna är klara kontrolleras PDF:ens SHA-256 mot testnumret och att sökindexet
 * går att söka i.
 */
@RunWith(AndroidJUnit4::class)
class DownloadScenarioDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = appContainer(context)
    private val issueId = "datormagazin-1986-02"
    private val index: ByteArray by lazy { context.assets.open("search_test.db").use { it.readBytes() } }
    private val github = MockWebServer()
    private var restoreIssue: (() -> Unit)? = null
    private val command = File("/data/local/tmp/dmz-scenario-cmd")

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Before
    fun prepare() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("körs bara med -e dmzScenario true", arguments.getString("dmzScenario") == "true")
        val pdfRate = arguments.getString("dmzPdfRate")?.toLongOrNull() ?: 2_048L
        val indexRate = arguments.getString("dmzIndexRate")?.toLongOrNull() ?: 512L

        LocalArchive.start()
        LocalArchive.pdfBytesPerSecond = pdfRate
        restoreIssue = LocalArchive.useTestPdfFor(context, issueId)
        removeDownloads(context, issueId)

        val manifest = """
            {"manifestVersion": 1, "releaseTag": "v1.0.0", "fileName": "search-test.db", "size": ${index.size},
             "sha256": "${sha256(index)}", "searchSchemaVersion": "1", "inventoryDatasetVersion": "2026-10-06-006",
             "normalizerVersion": "2", "privacyFilterVersion": "3", "minAppVersionCode": 9904}
        """.trimIndent()
        github.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                Log.i(TAG, "INDEXSERVER ${request.method} $path Range=${request.getHeader("Range")}")
                return when {
                    path.endsWith("/latest/download/search-index.json") -> MockResponse().setBody(manifest)
                    path.endsWith("/download/v1.0.0/search-test.db") -> {
                        val from = request.getHeader("Range")?.let { Regex("""bytes=(\d+)-""").matchEntire(it)?.groupValues?.get(1)?.toInt() }
                        val response = MockResponse().setHeader("ETag", "\"scenario-1\"")
                        if (from != null && request.getHeader("If-Range") == "\"scenario-1\"") {
                            response.setResponseCode(206)
                                .setHeader("Content-Range", "bytes $from-${index.size - 1}/${index.size}")
                                .setBody(Buffer().write(index, from, index.size - from))
                        } else {
                            response.setBody(Buffer().write(index))
                        }
                        response.throttleBody(indexRate / 4, 250, TimeUnit.MILLISECONDS)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        github.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
        SearchIndexSource.testBase = github.url("/Rognils/dmz-reader/releases")
        runBlocking {
            container.searchIndex.remove()
            container.downloadPreferences.setWifiOnly(true)
        }
        container.searchIndexDownloads.reset()
        command.delete()
    }

    @After
    fun restore() {
        LocalArchive.stop()
        restoreIssue?.invoke()
        removeDownloads(context, issueId)
        container.searchIndexDownloads.reset()
        runBlocking {
            container.searchIndex.remove()
            container.downloadPreferences.setWifiOnly(true)
        }
        SearchIndexSource.testBase = NO_GITHUB
        github.shutdown()
    }

    private fun indexText(state: IndexDownloadState): String = when (state) {
        is IndexDownloadState.Downloading -> "Downloading ${state.progress.bytesDownloaded}"
        is IndexDownloadState.Interrupted -> "Interrupted ${state.progress?.bytesDownloaded}"
        is IndexDownloadState.WaitingForWifi -> "WaitingForWifi"
        is IndexDownloadState.WaitingForNetwork -> "WaitingForNetwork"
        is IndexDownloadState.Queued -> "Queued ${state.progress?.bytesDownloaded}"
        else -> state.javaClass.simpleName
    }

    /** Sant medan appen syns. I bakgrunden finns ingenting att rita, och testramen ska inte vänta på det. */
    private fun visible(): Boolean {
        var resumed = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).isNotEmpty()
        }
        return resumed
    }

    /** Texten i Sök-kortet som den ritas just nu, eller "-" om kortet inte syns. */
    private fun cardText(): String {
        if (!visible()) return "-(i bakgrunden)"
        return runCatching {
            rule.onNode(hasTestTag("search")).performScrollToNode(hasTestTag("index-card"))
            listOf("index-card-text", "index-error").firstNotNullOfOrNull { tag ->
                rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().firstOrNull()
                    ?.config?.firstOrNull { it.key.name == "Text" }?.value?.toString()?.trim('[', ']')
            } ?: "-"
        }.getOrElse { "-" }.replace(' ', '_')
    }

    @Test
    fun downloadsUnderDisturbances() {
        val pdfPart = File(storage(context).filesDir, "issues/$issueId.pdf.part")
        val pdfFile = File(storage(context).filesDir, "issues/$issueId.pdf")
        val indexPart = File(storage(context).filesDir, "search-download/search-test.db.part")

        // Sök-fliken, med kortet för sökindexet synligt.
        rule.waitUntil(30_000) { rule.onAllNodes(hasText("Bibliotek")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Sök").performClick()
        rule.waitUntil(30_000) { rule.onAllNodes(hasTestTag("search")).fetchSemanticsNodes().isNotEmpty() }
        runBlocking { container.offlineRepository.requestDownload(issueId) }
        container.downloadScheduler.start()
        container.searchIndexDownloads.request()
        Log.i(TAG, "START pdf=${LocalArchive.pdf.size} byte index=${index.size} byte")

        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < LIMIT_MILLIS) {
            if (command.isFile) {
                val text = command.readText().trim()
                command.delete()
                when (text) {
                    "wifiOnly on" -> runBlocking { container.downloadPreferences.setWifiOnly(true) }
                    "wifiOnly off" -> runBlocking { container.downloadPreferences.setWifiOnly(false) }
                    "retry" -> runCatching {
                        val button = listOf("index-resume", "index-retry-download", "index-retry")
                            .first { rule.onAllNodes(hasTestTag(it)).fetchSemanticsNodes().isNotEmpty() }
                        rule.onNodeWithTag(button).performClick()
                    }.onFailure { Log.i(TAG, "KOMMANDO retry: ingen knapp (${it.message})") }
                }
                Log.i(TAG, "KOMMANDO $text")
            }
            val state = runBlocking { container.database.issueLocalStateDao().get(issueId) }
            val indexState = container.searchIndexDownloads.state.value
            Log.i(
                TAG,
                "SCEN t=${(System.currentTimeMillis() - started) / 1000} " +
                    "pdf=${state?.downloadState} pdfPart=${pdfPart.length()} pdfKlar=${pdfFile.length()} " +
                    "index=${indexText(indexState)} indexPart=${indexPart.length()} " +
                    "indexKlart=${container.searchIndex.index.value != null} " +
                    "KORT=${cardText()}",
            )
            if (pdfFile.isFile && container.searchIndex.index.value != null) break
            // Skärmen ritas bara om när testet gör något. Den här väntan låter den rita, som på en riktig skärm.
            if (visible()) rule.letAppRun(1_000) else Thread.sleep(1_000)
        }

        val pdf = pdfFile.readBytes()
        Log.i(TAG, "RESULTAT pdf ${pdf.size} byte SHA-256 ${sha256(pdf)}, testnumret ${sha256(LocalArchive.pdf)}")
        assertEquals(sha256(LocalArchive.pdf), sha256(pdf))
        val loaded = container.searchIndex.index.value
        assertNotNull("sökindexet blev aldrig importerat", loaded)
        val hits = runBlocking { container.searchIndex.rank("\"commodore\"") }.orEmpty()
        Log.i(TAG, "RESULTAT index ${loaded!!.info.issueCount} nummer, ${loaded.info.pageCount} sidor, ${hits.size} träffar på commodore")
        assertEquals(6, loaded.info.issueCount)
    }

    companion object {
        const val TAG = "DmzScenario"
        const val LIMIT_MILLIS = 20 * 60_000L
    }
}
