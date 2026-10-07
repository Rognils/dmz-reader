package se.c64.dmzreader

import android.util.Log
import androidx.room3.useWriterConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Verktyg: tömmer telefonen på allt som verifieringen har lämnat efter sig, så
 * att appen är som nyinstallerad men ligger kvar. Körs bara med argumentet
 * dmzWipe, eftersom det raderar användarens läsdata:
 *
 *   adb shell am instrument -w -e class se.c64.dmzreader.PhoneCleanupDeviceTest \
 *     -e dmzWipe true se.c64.dmzreader.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Tar bort: IssueUserState, ReadingSession, Bookmark, IssueLocalState, alla
 * nedladdningar, läs-cachen och miniatyrerna. Katalogen (Issue) och
 * inställningarna rörs inte.
 */
@RunWith(AndroidJUnit4::class)
class PhoneCleanupDeviceTest {
    @Test
    fun wipeAllUserDataOnRequest() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("dmzWipe") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = appContainer(context)

        container.offlineRepository.removeAll()
        File(context.filesDir, "issues").listFiles().orEmpty().forEach { it.delete() }
        File(context.cacheDir, "pdf").listFiles().orEmpty().forEach { it.delete() }
        File(context.cacheDir, "thumbs").deleteRecursively()

        val database = container.database
        database.useWriterConnection { connection ->
            for (table in listOf("IssueUserState", "ReadingSession", "Bookmark", "IssueLocalState")) {
                connection.usePrepared("DELETE FROM $table") { it.step() }
            }
        }

        assertTrue(database.issueUserStateDao().getAll().isEmpty())
        assertTrue(database.readingSessionDao().getAll().isEmpty())
        assertTrue(database.bookmarkDao().getAll().isEmpty())
        assertTrue(database.issueLocalStateDao().getAll().isEmpty())
        assertEquals(0, File(context.filesDir, "issues").listFiles().orEmpty().size)
        assertEquals(0, File(context.cacheDir, "pdf").listFiles().orEmpty().size)
        // Katalogen finns kvar.
        assertEquals(185, database.issueDao().count())
        Log.i("DmzVerify", "WIPE klar: användardata, lokal status, nedladdningar och läs-cache är borta")
    }
}
