package se.c64.dmzreader

import android.content.Context
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
