package se.c64.dmzreader

import android.content.Context
import kotlinx.coroutines.runBlocking
import se.c64.dmzreader.data.db.IssueUserState
import java.io.File

fun appContainer(context: Context): AppContainer = (context.applicationContext as DmzReaderApp).container

/**
 * Nollställer läsdata för de nummer testerna läser i, så att de börjar från
 * sida 1. Favoritmarkeringen behålls. Andra nummer på telefonen rörs inte.
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

/** Sant om numrets PDF finns på telefonen, i läs-cachen eller nedladdad. */
fun hasLocalPdf(context: Context, issueId: String): Boolean =
    File(context.cacheDir, "pdf/$issueId.pdf").isFile || File(context.filesDir, "issues/$issueId.pdf").isFile

fun readingState(context: Context, issueId: String): IssueUserState? = runBlocking {
    appContainer(context).database.issueUserStateDao().get(issueId)
}
