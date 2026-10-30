package se.c64.dmzreader.data.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import okhttp3.Call

/**
 * Bryter även ett blockerande execute/read direkt när föräldern avbryts.
 * En vanlig invokeOnCompletion körs först när den blockerande kroppen redan är färdig.
 * Anroparen avslutar bevakningen i finally. Då är ett extra call.cancel() ofarligt.
 */
internal fun CoroutineScope.watchCallCancellation(call: Call): Job =
    launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { call.cancel() }
    }
