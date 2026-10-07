package se.c64.dmzreader.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.c64.dmzreader.DmzReaderApp
import se.c64.dmzreader.MainActivity
import se.c64.dmzreader.data.offline.ActiveDownload
import se.c64.dmzreader.data.offline.QueueOutcome
import se.c64.dmzreader.domain.downloadProgressText

/**
 * Det användarinitierade dataöverföringsjobbet som betar av nedladdningskön,
 * en fil i taget (planens FAS 5).
 *
 * Tjänsten schemaläggs bara på Android 14 och senare (UidtDownloadScheduler).
 */
class DownloadJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        val offline = (application as DmzReaderApp).container.offlineRepository
        createDownloadChannel(this)
        // Ett UIDT-jobb måste visa en notis så fort det startar.
        setNotification(
            params,
            NOTIFICATION_ID,
            downloadNotification(this, null, 0),
            JOB_END_NOTIFICATION_POLICY_REMOVE,
        )
        Log.i(LOG_TAG, "UIDT-jobbet startar")
        running = scope.launch {
            val notifier = launch {
                offline.active.collect { active ->
                    if (active != null) {
                        setNotification(
                            params,
                            NOTIFICATION_ID,
                            downloadNotification(this@DownloadJobService, active, offline.queuedCount()),
                            JOB_END_NOTIFICATION_POLICY_REMOVE,
                        )
                        active.progress?.let { updateTransferredNetworkBytes(params, it.bytesDownloaded, 0) }
                    }
                    // Systemet begränsar hur ofta en notis får uppdateras.
                    delay(NOTIFICATION_INTERVAL_MILLIS)
                }
            }
            var outcome: QueueOutcome
            do {
                outcome = offline.runQueue()
                // Ett nummer kan ha ställts i kön precis när den blev tom.
            } while (outcome == QueueOutcome.EMPTY && offline.queuedCount() > 0)
            notifier.cancel()
            Log.i(LOG_TAG, "UIDT-jobbet klart: $outcome")
            // Försvann nätet utan att systemet stoppade jobbet ska det köras igen.
            jobFinished(params, outcome == QueueOutcome.NETWORK_LOST)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        Log.i(LOG_TAG, "UIDT-jobbet stoppas av systemet, skäl=${params.stopReason}")
        running?.cancel()
        // Det som återstår står kvar i kön. Jobbet körs igen när villkoren är
        // uppfyllda, utom när användaren själv har stoppat det.
        return params.stopReason != JobParameters.STOP_REASON_USER
    }

    override fun onNetworkChanged(params: JobParameters) {
        // Nedladdningen märker själv om anslutningen bryts, och fortsätter då på sin .part-fil.
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 5001
        private const val NOTIFICATION_INTERVAL_MILLIS = 700L
    }
}

private const val CHANNEL_ID = "downloads"

fun createDownloadChannel(context: Context) {
    val channel = NotificationChannel(CHANNEL_ID, "Nedladdningar", NotificationManager.IMPORTANCE_LOW).apply {
        description = "Förlopp för nummer som laddas ned för läsning utan nät"
        setShowBadge(false)
    }
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
}

/** Notisen för en pågående nedladdning: förlopp i MB och procent, och en knapp för att avbryta. */
fun downloadNotification(context: Context, active: ActiveDownload?, queued: Int): Notification {
    val openApp = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val builder = Notification.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(active?.let { "Laddar ned ${it.title}" } ?: "Förbereder nedladdning")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openApp)
        .setCategory(Notification.CATEGORY_PROGRESS)
    if (active != null) {
        val fraction = active.progress?.fraction
        builder.setContentText(downloadProgressText(active.progress, null))
        if (fraction != null) {
            builder.setProgress(100, (fraction * 100).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        if (queued > 0) builder.setSubText("$queued i kö")
        val cancel = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, DownloadCancelReceiver::class.java).putExtra(DownloadCancelReceiver.EXTRA_ISSUE_ID, active.issueId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        builder.addAction(Notification.Action.Builder(null, "Avbryt", cancel).build())
    }
    return builder.build()
}

/** Knappen "Avbryt" i notisen: avbryter nedladdningen som pågår. Kön fortsätter med nästa nummer. */
class DownloadCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as DmzReaderApp).container
        val pending = goAsync()
        container.applicationScope.launch {
            try {
                val issueId = intent.getStringExtra(EXTRA_ISSUE_ID) ?: container.offlineRepository.active.value?.issueId
                Log.i(LOG_TAG, "Avbryt från notisen: $issueId")
                if (issueId != null) container.offlineRepository.cancel(issueId)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_ISSUE_ID = "issueId"
    }
}
