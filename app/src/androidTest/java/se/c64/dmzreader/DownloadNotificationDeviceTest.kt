package se.c64.dmzreader

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import se.c64.dmzreader.data.offline.ActiveDownload
import se.c64.dmzreader.data.pdf.DownloadProgress
import se.c64.dmzreader.offline.createDownloadChannel
import se.c64.dmzreader.offline.downloadNotification

/**
 * Verktyg för verifiering på telefonen: visar nedladdningsnotisen stillastående,
 * så att den går att granska i lugn och ro. En riktig nedladdning av ett nummer
 * tar bara några sekunder. Körs bara med argumentet dmzShowNotification. Med
 * dmzNotificationId går det att välja notisens id, till exempel det riktiga jobbets.
 */
@RunWith(AndroidJUnit4::class)
class DownloadNotificationDeviceTest {
    @Test
    fun showSampleNotification() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("dmzShowNotification") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        createDownloadChannel(context)
        val active = ActiveDownload(
            issueId = "datormagazin-1995-14",
            title = "Datormagazin 1995 Nr 14",
            progress = DownloadProgress(57_200_000, 115_605_773),
        )
        context.getSystemService(NotificationManager::class.java)
            .notify(arguments.getString("dmzNotificationId")?.toInt() ?: SAMPLE_ID, downloadNotification(context, active, queued = 1))
    }

    private companion object {
        const val SAMPLE_ID = 5999
    }
}
