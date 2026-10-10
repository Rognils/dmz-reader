package se.c64.dmzreader

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.c64.dmzreader.data.db.DownloadState
import java.io.File

/**
 * Offline på en riktig enhet (FAS 5): ladda ned 1986 nr 1 (15 MB) från arkivet,
 * läs det i flygplansläge och ta bort nedladdningen.
 *
 * Testet slår på flygplansläget en kort stund och slår alltid av det igen.
 */
@RunWith(AndroidJUnit4::class)
class OfflineDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val issueId = "datormagazin-1986-01"
    private val permanentFile = File(storage(context).filesDir, "issues/$issueId.pdf")

    private fun waitFor(timeoutMillis: Long = 20_000, condition: () -> Boolean) =
        rule.waitUntil(timeoutMillis, condition)

    private fun exists(text: String, substring: Boolean = false) =
        rule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun shell(command: String) {
        val output = instrumentation.uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }

    private fun airplaneMode() = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    private fun setAirplaneMode(enabled: Boolean) {
        shell("cmd connectivity airplane-mode ${if (enabled) "enable" else "disable"}")
        val deadline = System.currentTimeMillis() + 10_000
        while (airplaneMode() != enabled && System.currentTimeMillis() < deadline) Thread.sleep(200)
        assertEquals(enabled, airplaneMode())
        if (!enabled) waitForNetwork()
    }

    /**
     * Efter flygplansläget tar det en stund innan nätet är att lita på. Nästa test ska inte börja innan dess.
     *
     * På testtelefonen ansluter wifi inom ett par sekunder, tappar anslutningen igen 5–8 sekunder
     * senare och är tillbaka efter ytterligare cirka 20. Under tiden går trafiken över mobilnätet.
     * En nedladdning som börjar i det läget bryts när nätet byts under den. Nätet räknas därför som
     * tillbaka först när ett och samma nät har varit bekräftat i 20 sekunder i följd.
     */
    private fun waitForNetwork() {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val deadline = System.currentTimeMillis() + 90_000
        var stableNetwork: Network? = null
        var stableSince = 0L
        while (System.currentTimeMillis() < deadline) {
            val network = connectivity.activeNetwork
            val capabilities = connectivity.getNetworkCapabilities(network)
            val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val now = System.currentTimeMillis()
            if (!validated || network != stableNetwork) {
                stableNetwork = network.takeIf { validated }
                stableSince = now
            } else if (now - stableSince >= 20_000) {
                return
            }
            Thread.sleep(500)
        }
    }

    private fun downloadState() = runBlocking {
        appContainer(context).database.issueLocalStateDao().get(issueId)?.downloadState ?: DownloadState.NONE
    }

    @After
    fun restoreNetwork() {
        if (airplaneMode()) setAirplaneMode(false)
    }

    @Test
    fun downloadThenReadInAirplaneModeThenRemove() {
        // Utgångsläge: numret finns varken nedladdat eller i läs-cachen, och är oläst.
        runBlocking { appContainer(context).offlineRepository.remove(issueId) }
        File(storage(context).cacheDir, "pdf/$issueId.pdf").delete()
        resetReadingState(context, issueId)
        assertFalse(permanentFile.exists())

        waitFor { exists("Datormagazin") }
        rule.onNodeWithText("Datormagazin").performClick()
        val yearCard = "Datormagazin 1986, 4 nummer"
        waitFor { rule.onAllNodes(hasContentDescription(yearCard, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription(yearCard, substring = true).performClick()
        waitFor { rule.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.scrollToNodeWhenLoaded(hasContentDescription("Datormagazin 1986 Nr 1"))
        rule.onNodeWithContentDescription("Datormagazin 1986 Nr 1").performClick()
        waitFor { exists("Ladda ned") }

        // Ladda ned. Första gången på telefonen ställs frågan om notiser; testet svarar utan att ändra behörigheten.
        rule.onNodeWithText("Ladda ned").performScrollTo().performClick()
        rule.waitForIdle()
        if (exists("Utan notis")) rule.onNodeWithText("Utan notis").performClick()

        // Köad eller Laddar ned X %, och till sist Nedladdad med storleken.
        waitFor(120_000) { exists("Nedladdad · 15,1 MB") }
        assertEquals(DownloadState.DOWNLOADED, downloadState())
        assertTrue(permanentFile.isFile)
        assertEquals(15_105_472L, permanentFile.length())
        assertFalse(File(storage(context).filesDir, "issues/$issueId.pdf.part").exists())
        assertTrue(exists("Ta bort nedladdning"))

        // Utan nät: läsaren öppnas direkt ur den nedladdade filen.
        setAirplaneMode(true)
        Thread.sleep(1_500)
        rule.onNodeWithText("Läs").performScrollTo().performClick()
        waitFor(20_000) { rule.onAllNodes(hasTestTag("reader")).fetchSemanticsNodes().isNotEmpty() }
        waitFor(30_000) { rule.onAllNodes(hasTestTag("page-full-1")).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(exists("Hämtar numret", substring = true))
        assertFalse(exists("Numret kunde inte öppnas", substring = true))
        // Ingenting hämtades till läs-cachen.
        assertFalse(File(storage(context).cacheDir, "pdf/$issueId.pdf").exists())
        assertFalse(File(storage(context).cacheDir, "pdf/$issueId.pdf.part").exists())
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor { exists("Ta bort nedladdning") }
        setAirplaneMode(false)

        // Ta bort: bekräftelse, sedan är filen och statusen borta.
        rule.onNodeWithText("Ta bort nedladdning").performScrollTo().performClick()
        waitFor { exists("Ta bort nedladdningen?") }
        rule.onNodeWithText("Ta bort").performClick()
        waitFor { exists("Ladda ned") }
        assertFalse(permanentFile.exists())
        assertEquals(DownloadState.NONE, downloadState())
    }
}
