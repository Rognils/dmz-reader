package se.c64.dmzreader

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.util.Log
import androidx.test.runner.AndroidJUnitRunner
import java.io.File

/**
 * Lagringen för en testkörning: en egen katalog under appens datakatalog, med egen
 * databas, egna inställningar, eget filesDir och eget cacheDir. Appens riktiga
 * databas, läs-cache och nedladdningar rörs aldrig.
 */
object TestStorage {
    /** Alla testkörningars kataloger ligger här: /data/data/se.c64.dmzreader/app_dmz_test/. */
    private const val PARENT = "dmz_test"

    /** PDF:er som datorn har lagt på telefonen för testerna, så att arkivservern inte belastas. */
    val seedDirectory = File("/data/local/tmp/dmz-test-pdfs")

    @Volatile
    var rootName: String = "run-${System.currentTimeMillis()}"

    /** Sant när katalogen ska ligga kvar efter körningen, för tester som fortsätter i en ny process. */
    @Volatile
    var keep: Boolean = false

    fun parent(base: Context): File = base.getDir(PARENT, Context.MODE_PRIVATE)
    fun root(base: Context): File = File(parent(base), rootName)

    /** Tar bort rester efter tidigare körningar som inte hann städa, till exempel efter en krasch. */
    fun deleteStale(base: Context) {
        parent(base).listFiles().orEmpty().filter { it.name != rootName }.forEach { it.deleteRecursively() }
    }

    fun delete(base: Context) {
        root(base).deleteRecursively()
    }

    /** Lägger förberedda PDF:er i testets egen läs-cache. */
    fun seedPdfs(storage: Context) {
        val target = File(storage.cacheDir, "pdf").apply { mkdirs() }
        for (file in seedDirectory.listFiles().orEmpty().filter { it.name.endsWith(".pdf") }) {
            val copy = File(target, file.name)
            if (!copy.exists()) file.copyTo(copy)
        }
    }
}

/**
 * En kontext där all lagring pekar in i testkörningens katalog. Allt annat
 * (resurser, assets, systemtjänster) kommer från den riktiga applikationen.
 */
class IsolatedContext(base: Context, private val root: File) : ContextWrapper(base) {
    private fun directory(name: String) = File(root, name).apply { mkdirs() }

    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = directory("files")
    override fun getCacheDir(): File = directory("cache")
    override fun getNoBackupFilesDir(): File = directory("no_backup")
    override fun getCodeCacheDir(): File = directory("code_cache")
    override fun getDataDir(): File = root.apply { mkdirs() }
    override fun getDatabasePath(name: String): File = File(directory("databases"), name)
    override fun getDir(name: String, mode: Int): File = directory("app_$name")
    override fun getFileStreamPath(name: String): File = File(filesDir, name)
    override fun getExternalFilesDir(type: String?): File = directory("external" + (type?.let { "/$it" } ?: ""))
    override fun getExternalCacheDir(): File = directory("external_cache")
}

/** Applikationen under test: samma app, men med isolerad lagring. */
class TestDmzReaderApp : DmzReaderApp() {
    override val storageContext: Context by lazy { IsolatedContext(this, TestStorage.root(this)) }

    override fun onCreate() {
        if (!TestStorage.keep) TestStorage.delete(this)
        TestStorage.deleteStale(this)
        TestStorage.seedPdfs(storageContext)
        Log.i("DmzTest", "Isolerad lagring: ${TestStorage.root(this)}")
        super.onCreate()
    }
}

/**
 * Testkörare som startar appen med isolerad lagring (TestDmzReaderApp) och tar bort
 * katalogen när körningen är klar.
 *
 * Argument:
 *   -e dmzTestRoot <namn>   katalogens namn. Används när ett test fortsätter i en ny process.
 *   -e dmzKeepRoot true     låt katalogen ligga kvar efter körningen (och töm den inte före).
 */
class IsolatedTestRunner : AndroidJUnitRunner() {
    private var keep = false

    override fun newApplication(classLoader: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(classLoader, TestDmzReaderApp::class.java.name, context)

    override fun onCreate(arguments: Bundle?) {
        arguments?.getString("dmzTestRoot")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) }?.let { TestStorage.rootName = it }
        keep = arguments?.getString("dmzKeepRoot") == "true"
        TestStorage.keep = keep
        super.onCreate(arguments)
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        // Läsaren skriver läsposition och session i bakgrunden när den stängs. Vänta in det.
        Thread.sleep(2_500)
        if (!keep) TestStorage.delete(targetContext.applicationContext)
        super.finish(resultCode, results)
    }
}
