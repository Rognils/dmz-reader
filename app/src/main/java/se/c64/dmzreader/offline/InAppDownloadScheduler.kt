package se.c64.dmzreader.offline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Android 8–13: bara i förgrunden och på ett nät som inställningen tillåter. */
class InAppDownloadScheduler(
    scope: CoroutineScope,
    networkAllowed: Flow<Boolean>,
    private val runQueue: suspend () -> Unit,
    private val afterQueue: suspend () -> Unit = {},
) : DownloadScheduler {
    override val runsInBackground = false
    private val foreground = MutableStateFlow(false)
    private val generation = MutableStateFlow(0L)
    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            combine(foreground, networkAllowed, generation) { visible, allowed, version ->
                (visible && allowed) to version
            }.distinctUntilChanged().collectLatest { (allowed, _) ->
                // collectLatest inväntar avbrottets städning innan nästa körning startar.
                if (allowed) {
                    requests.receiveAsFlow().onStart { emit(Unit) }.collect {
                        runQueue()
                        afterQueue()
                    }
                }
            }
        }
    }

    override fun setForeground(foreground: Boolean) {
        this.foreground.value = foreground
    }

    override fun start(restart: Boolean) {
        if (restart) generation.update { it + 1 } else requests.trySend(Unit)
    }
}
