package se.c64.dmzreader

import android.app.Application
import android.content.Context

open class DmzReaderApp : Application() {
    lateinit var container: AppContainer
        private set

    /**
     * Den kontext som appens lagring utgår från: databasen, inställningarna, filesDir och cacheDir.
     * I appen är det applikationen själv. De instrumenterade testerna byter den mot en isolerad
     * katalog, så att de aldrig rör användarens data.
     */
    open val storageContext: Context get() = this

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(storageContext)
        container.loadBundledInventory()
        container.reconcileDownloads()
        container.loadSearchIndex()
    }
}
