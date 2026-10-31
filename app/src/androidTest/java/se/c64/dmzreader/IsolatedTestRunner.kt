package se.c64.dmzreader

import android.app.Application
import android.content.Context
import android.content.ComponentCallbacks
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.net.TrafficStats
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnitRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import se.c64.dmzreader.data.network.LiveArchiveGuard
import se.c64.dmzreader.data.search.SearchIndexSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File

/**
 * Lagringen för en testkörning: en egen katalog under appens datakatalog, med egen
 * databas, egna inställningar, eget filesDir och eget cacheDir. Appens riktiga
 * databas, läs-cache och nedladdningar rörs aldrig.
 */
object TestStorage {
    /** Alla testkörningars kataloger ligger här: /data/data/se.c64.dmzreader/app_dmz_test/. */
    private const val PARENT = "dmz_test"

    /**
     * Arkivfiler som någon har lagt på telefonen för de manuella kontrollerna
     * (tools/manual_archive_file_test.sh). Den vanliga testsviten lägger ingenting här.
     */
    val seedDirectory = File("/data/local/tmp/dmz-test-pdfs")

    /** Numret som testerna läser i. Dess PDF i testkörningen är det påhittade testnumret, test_issue.pdf. */
    const val READING_ISSUE = "datormagazin-1986-01"

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

    /**
     * Fyller testets egen läs-cache: arkivfiler ur [seedDirectory] om det finns några, och annars det
     * påhittade testnumret under namnet [READING_ISSUE]. Ingen arkivfil behövs för testsviten.
     */
    fun seedPdfs(storage: Context, app: Context, testAssets: AssetManager) {
        val target = File(storage.cacheDir, "pdf").apply { mkdirs() }
        for (file in seedDirectory.listFiles().orEmpty().filter { it.name.endsWith(".pdf") }) {
            val copy = File(target, file.name)
            if (!copy.exists()) file.copyTo(copy)
        }
        val reading = File(target, "$READING_ISSUE.pdf")
        if (!reading.exists()) {
            val pdf = testAssets.open("test_issue.pdf").use { it.readBytes() }
            reading.writeBytes(paddedPdf(pdf, catalogueSize(app, READING_ISSUE)))
        }
    }

    /** Storleken som arkivindexet anger för numrets PDF. */
    private fun catalogueSize(app: Context, issueId: String): Long {
        val inventory = Json.parseToJsonElement(app.assets.open("archive_inventory.json").bufferedReader().use { it.readText() })
        val issue = inventory.jsonObject.getValue("issues").jsonArray
            .first { it.jsonObject.getValue("issueId").jsonPrimitive.content == issueId }
        return issue.jsonObject.getValue("ocrPdfSize").jsonPrimitive.long
    }

    /**
     * [pdf] utfylld till exakt [size] byte, med samma sidor. Läsaren kastar en cachefil vars storlek
     * inte stämmer med arkivindexet och hämtar om den, och det får testerna inte göra.
     *
     * Blanktecken läggs mellan det sista objektet och korsreferenstabellen, där de är tillåtna, och
     * talet efter startxref pekas om till tabellens nya plats. Objektens platser ändras inte.
     */
    fun paddedPdf(pdf: ByteArray, size: Long): ByteArray {
        val text = String(pdf, Charsets.ISO_8859_1)
        val table = text.lastIndexOf("\nxref\n") + 1
        val start = text.lastIndexOf("\nstartxref\n")
        require(table > 0 && start > table && size > pdf.size) { "test_issue.pdf går inte att fylla ut till $size byte" }
        val head = pdf.copyOfRange(0, table)
        val tableAndTrailer = pdf.copyOfRange(table, start)
        // Talets längd beror på var tabellen hamnar. Räkna med slutlängden och kontrollera.
        var end = "\nstartxref\n${table}\n%%EOF\n"
        repeat(2) {
            val padding = size - head.size - tableAndTrailer.size - end.length
            end = "\nstartxref\n${table + padding}\n%%EOF\n"
        }
        val padding = (size - head.size - tableAndTrailer.size - end.length).toInt()
        val out = ByteArray(size.toInt())
        head.copyInto(out)
        out.fill(' '.code.toByte(), head.size, head.size + padding)
        tableAndTrailer.copyInto(out, head.size + padding)
        end.toByteArray(Charsets.ISO_8859_1).copyInto(out, head.size + padding + tableAndTrailer.size)
        check(end == "\nstartxref\n${table + padding}\n%%EOF\n") { "startxref stämmer inte efter utfyllnaden" }
        return out
    }
}

/**
 * En kontext där all lagring pekar in i testkörningens katalog. Allt annat
 * (resurser, assets, systemtjänster) kommer från den riktiga applikationen.
 */
class IsolatedContext(base: Context, private val root: File) : ContextWrapper(base) {
    private fun directory(name: String) = File(root, name).apply { mkdirs() }

    override fun getApplicationContext(): Context = this

    // Context skickar registreringen vidare till getApplicationContext(), alltså hit igen. På Android 9
    // blev det en oändlig rekursion när bildladdaren registrerade sig för minnesvarningar. Den riktiga
    // applikationen tar emot den i stället.
    override fun registerComponentCallbacks(callback: ComponentCallbacks) =
        baseContext.applicationContext.registerComponentCallbacks(callback)

    override fun unregisterComponentCallbacks(callback: ComponentCallbacks) =
        baseContext.applicationContext.unregisterComponentCallbacks(callback)
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
        TestStorage.seedPdfs(storageContext, this, InstrumentationRegistry.getInstrumentation().context.assets)
        Log.i("DmzTest", "Isolerad lagring: ${TestStorage.root(this)}")
        super.onCreate()
    }
}

/**
 * Testkörare som startar appen med isolerad lagring (TestDmzReaderApp) och tar bort
 * katalogen när körningen är klar.
 *
 * Körningen får inte nå arkivservern (CLAUDE.md, regel 12): [LiveArchiveGuard] spärrar varje försök,
 * och när körningen är klar skrivs antalet spärrade försök i loggen, med taggen DmzGuard.
 * tools/device_test.sh läser de raderna.
 *
 * Argument:
 *   -e dmzTestRoot <namn>    katalogens namn. Används när ett test fortsätter i en ny process.
 *   -e dmzKeepRoot true      låt katalogen ligga kvar efter körningen (och töm den inte före).
 *   -e dmzLiveArchive true   släpp spärren, för de manuella kontrollerna mot den riktiga servern.
 */
private const val GUARD_TAG = "DmzGuard"

/** En lokal adress där ingen svarar: port 9, discard. Testernas standard i stället för GitHub. */
val NO_GITHUB = "http://127.0.0.1:9/Rognils/dmz-reader/releases".toHttpUrl()

class IsolatedTestRunner : AndroidJUnitRunner() {
    private var keep = false
    private var receivedAtStart = 0L

    override fun newApplication(classLoader: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(classLoader, TestDmzReaderApp::class.java.name, context)

    override fun onCreate(arguments: Bundle?) {
        arguments?.getString("dmzTestRoot")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) }?.let { TestStorage.rootName = it }
        keep = arguments?.getString("dmzKeepRoot") == "true"
        TestStorage.keep = keep
        LiveArchiveGuard.blocked = arguments?.getString("dmzLiveArchive") != "true"
        // Sök-fliken utan index frågar efter manifestet. I testerna går den frågan till en lokal
        // adress där ingen svarar, aldrig till GitHub. Testerna av hämtningen byter till sin egen server.
        SearchIndexSource.testBase = NO_GITHUB
        receivedAtStart = TrafficStats.getUidRxBytes(Process.myUid())
        Log.i(GUARD_TAG, "START spärr=${LiveArchiveGuard.blocked}")
        super.onCreate(arguments)
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        // Läsaren skriver läsposition och session i bakgrunden när den stängs. Vänta in det.
        Thread.sleep(2_500)
        // Räknar också trafiken till den lokala testservern. Vad som kom över wifi och mobilnät
        // mäter tools/device_test.sh i systemets statistik.
        val received = TrafficStats.getUidRxBytes(Process.myUid()) - receivedAtStart
        Log.i(GUARD_TAG, "KLAR spärrade=${LiveArchiveGuard.violations.get()} mottagetInklusiveLokalServer=$received byte spärr=${LiveArchiveGuard.blocked}")
        if (!keep) TestStorage.delete(targetContext.applicationContext)
        super.finish(resultCode, results)
    }
}
