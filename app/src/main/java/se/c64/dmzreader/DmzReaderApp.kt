package se.c64.dmzreader

import android.app.Application

class DmzReaderApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.loadBundledInventory()
    }
}
