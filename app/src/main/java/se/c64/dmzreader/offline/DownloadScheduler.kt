package se.c64.dmzreader.offline

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import se.c64.dmzreader.data.offline.OfflineRepository
import se.c64.dmzreader.data.settings.DownloadPreferences

/** Loggtaggen för nedladdningar. */
const val LOG_TAG = "DmzOffline"

/** Ser till att nedladdningskön betas av. */
interface DownloadScheduler {
    /** Sant om nedladdningar fortsätter när appen har lämnats. */
    val runsInBackground: Boolean

    /**
     * Startar avbetningen av kön om den inte redan pågår. Med [restart] startas
     * den om även om den pågår, så att ändrade nätverkskrav börjar gälla.
     */
    fun start(restart: Boolean = false)
}

/**
 * Android 14 och senare: kön betas av i ett användarinitierat
 * dataöverföringsjobb (UIDT), som fortsätter när appen lämnas och när skärmen
 * släcks. Systemet startar jobbet när nätverkskravet är uppfyllt och stoppar
 * det när nätet försvinner; då återupptas det när nätet kommer tillbaka.
 */
@RequiresApi(34)
class UidtDownloadScheduler(
    private val context: Context,
    private val preferences: DownloadPreferences,
    private val offline: OfflineRepository,
    private val scope: CoroutineScope,
    /** Det som återstår av en beställd hämtning av sökindexet. */
    private val indexBytes: () -> Long = { 0L },
) : DownloadScheduler {
    override val runsInBackground = true

    override fun start(restart: Boolean) {
        scope.launch {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            // Ett väntande eller pågående jobb betar redan av kön. Att schemalägga
            // om det hade avbrutit den pågående nedladdningen i onödan.
            if (!restart && scheduler.getPendingJob(JOB_ID) != null) return@launch
            val wifiOnly = preferences.wifiOnly()
            val bytes = offline.estimatedQueueBytes() + indexBytes()
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, DownloadJobService::class.java))
                .setUserInitiated(true)
                .setRequiredNetworkType(if (wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
                .setEstimatedNetworkBytes(
                    if (bytes > 0) bytes else JobInfo.NETWORK_BYTES_UNKNOWN.toLong(),
                    JobInfo.NETWORK_BYTES_UNKNOWN.toLong(),
                )
                .build()
            try {
                val result = scheduler.schedule(job)
                Log.i(LOG_TAG, "UIDT-jobb schemalagt: resultat=$result wifiOnly=$wifiOnly byte=$bytes")
            } catch (e: RuntimeException) {
                // Ett UIDT-jobb får bara startas medan appen syns. Kön ligger kvar
                // och startas nästa gång appen öppnas.
                Log.w(LOG_TAG, "UIDT-jobbet kunde inte schemaläggas", e)
            }
        }
    }

    companion object {
        const val JOB_ID = 5001
    }
}

/**
 * Android 8–13: ingen bakgrundsnedladdning. Kön betas av i appens egen process
 * och bara så länge appen är öppen.
 */
class InAppDownloadScheduler(
    private val offline: OfflineRepository,
    private val scope: CoroutineScope,
    /** Körs när kön är avbetad: en beställd hämtning av sökindexet. */
    private val afterQueue: suspend () -> Unit = {},
) : DownloadScheduler {
    override val runsInBackground = false

    override fun start(restart: Boolean) {
        // runQueue och afterQueue gör ingenting om de redan körs.
        scope.launch {
            offline.runQueue()
            afterQueue()
        }
    }
}
