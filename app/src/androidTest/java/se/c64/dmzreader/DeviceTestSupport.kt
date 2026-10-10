package se.c64.dmzreader

import android.content.Context
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performScrollToNode
import kotlinx.coroutines.runBlocking
import se.c64.dmzreader.data.db.IssueUserState
import java.io.File

/**
 * Appen under test. Den ska alltid vara [TestDmzReaderApp], som har isolerad lagring.
 * Körs testerna med en annan testkörare stoppas de här, innan de hinner röra riktig data.
 */
private fun testApp(context: Context): TestDmzReaderApp =
    context.applicationContext as? TestDmzReaderApp
        ?: error("Testerna måste köras med se.c64.dmzreader.IsolatedTestRunner, annars rör de användarens data.")

fun appContainer(context: Context): AppContainer = testApp(context).container

/** Testkörningens egen lagring. Använd den i stället för targetContext.filesDir och cacheDir. */
fun storage(context: Context): Context = testApp(context).storageContext

/**
 * Nollställer läsdata för de nummer testerna läser i, så att de börjar från
 * sida 1. Favoritmarkeringen behålls. Databasen är testkörningens egen.
 */
fun resetReadingState(context: Context, vararg issueIds: String) = runBlocking {
    val dao = appContainer(context).database.issueUserStateDao()
    for (issueId in issueIds) {
        val current = dao.get(issueId) ?: continue
        dao.update(IssueUserState(issueId = issueId, isFavorite = current.isFavorite, updatedAt = System.currentTimeMillis()))
    }
}

/** Tar bort permanenta nedladdningar för de nummer testerna läser i. */
fun removeDownloads(context: Context, vararg issueIds: String) = runBlocking {
    for (issueId in issueIds) appContainer(context).offlineRepository.remove(issueId)
}

/** Sant om numrets PDF finns i testkörningens lagring, i läs-cachen eller nedladdad. */
fun hasLocalPdf(context: Context, issueId: String): Boolean =
    File(storage(context).cacheDir, "pdf/$issueId.pdf").isFile || File(storage(context).filesDir, "issues/$issueId.pdf").isFile

fun readingState(context: Context, issueId: String): IssueUserState? = runBlocking {
    appContainer(context).database.issueUserStateDao().get(issueId)
}

/**
 * Rullar till [target] i en rullande lista eller ett rutnät ([container]), och väntar
 * först på att innehållet har lästs in.
 *
 * Listorna i appen fylls från databasen en kort stund efter att vyn har ritats. Ett test
 * som rullar direkt när listan syns kan därför hitta en lista som ännu saknar sina rader,
 * och faller då med "No node found … in scrollable container". Det hände ungefär en gång
 * på tio i en nystartad process med tom lagring (utredningen före 1.0.0,
 * docs/reports/gates.md). Här görs försöket om tills noden finns eller tiden är ute.
 */
fun ComposeTestRule.scrollToNodeWhenLoaded(
    target: SemanticsMatcher,
    container: SemanticsMatcher = hasScrollToNodeAction(),
    timeoutMillis: Long = 15_000,
) {
    var last: AssertionError? = null
    try {
        waitUntil(timeoutMillis) {
            try {
                onNode(container).performScrollToNode(target)
                true
            } catch (e: AssertionError) {
                last = e
                false
            }
        }
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        // Det ursprungliga felet säger vad som saknades, vilket tidsgränsen inte gör.
        throw AssertionError("Listan fick aldrig noden inom $timeoutMillis ms: ${last?.message}", last ?: e)
    }
}
