package se.c64.dmzreader.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

data class DownloadNetwork(val connected: Boolean, val metered: Boolean) {
    fun allows(wifiOnly: Boolean): Boolean = connected && (!wifiOnly || !metered)
}

/** Följer även byte från wifi till mobilnät medan appen är öppen. */
fun observeDownloadNetwork(context: Context): Flow<DownloadNetwork> = callbackFlow {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val lock = Any()
    var current = manager.activeNetwork
    fun report(capabilities: NetworkCapabilities?) = trySend(
        DownloadNetwork(
            connected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            metered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true,
        ),
    )
    report(current?.let(manager::getNetworkCapabilities))
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(lock) {
                if (current != network) {
                    current = network
                    // Nätet får inte användas med det föregående nätets kostnadsuppgifter.
                    report(null)
                }
            }
        }

        override fun onLost(network: Network) {
            synchronized(lock) {
                if (current == network) {
                    current = null
                    report(null)
                }
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            synchronized(lock) { if (current == network) report(capabilities) }
        }
    }
    manager.registerDefaultNetworkCallback(callback)
    awaitClose { manager.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()
