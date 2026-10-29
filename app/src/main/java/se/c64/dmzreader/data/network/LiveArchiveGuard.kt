package se.c64.dmzreader.data.network

import android.util.Log
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import se.c64.dmzreader.BuildConfig
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Skyddsnät för tester: automatiska tester får aldrig hämta från arkivservern
 * (CLAUDE.md, regel 12).
 *
 * När [blocked] är satt kastar appens HTTP-klienter och FTP-källan ett fel i stället för
 * att ansluta till dmzarkivet.se, och försöket räknas i [violations]. Ett test som vill pröva
 * nätvägen pekar om arkivets adresser till en lokal server med [replacement].
 *
 * Spärren verkar bara i debug-bygget. I release-bygget går den inte att slå på.
 */
object LiveArchiveGuard {
    const val ARCHIVE_HOST = "dmzarkivet.se"
    private const val LOG_TAG = "DmzGuard"

    /** Systemegenskap som slår på spärren från start, för JVM-testerna. */
    const val BLOCK_PROPERTY = "dmz.blockLiveArchive"

    /** Systemegenskap med en fil där varje spärrat försök skrivs, så att en testkörning kan kontrolleras efteråt. */
    const val LOG_PROPERTY = "dmz.liveArchiveGuardLog"

    @Volatile
    var blocked: Boolean = BuildConfig.DEBUG && System.getProperty(BLOCK_PROPERTY) == "true"
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** En lokal server som tar emot arkivets förfrågningar i stället, med samma sökväg. Bara i debug-bygget. */
    @Volatile
    var replacement: HttpUrl? = null
        set(value) {
            field = value.takeIf { BuildConfig.DEBUG }
        }

    /** Antalet försök att nå arkivet som har spärrats sedan processen startade. */
    val violations = AtomicInteger(0)

    fun isArchive(host: String): Boolean = host.equals(ARCHIVE_HOST, ignoreCase = true) ||
        host.endsWith(".$ARCHIVE_HOST", ignoreCase = true)

    /** GitHub och GitHubs filserver, där sökindexet hämtas. Testerna använder en lokal server i stället. */
    fun isGitHub(host: String): Boolean = GITHUB_HOSTS.any { host.equals(it, ignoreCase = true) || host.endsWith(".$it", ignoreCase = true) }

    private val GITHUB_HOSTS = listOf("github.com", "githubusercontent.com")

    /**
     * Kastar om [host] är GitHub och spärren är på. Automatiska tester ska inte heller nå GitHub:
     * hämtningen av sökindexet prövas mot en lokal server.
     */
    @Throws(IOException::class)
    fun checkGitHub(host: String, what: String) {
        if (!blocked || !isGitHub(host)) return
        violations.incrementAndGet()
        val message = "SPÄRRAD: ett test försökte nå GitHub ($what). Automatiska tester hämtar sökindexet från en lokal server."
        runCatching { Log.e(LOG_TAG, message) }
        System.getProperty(LOG_PROPERTY)?.let { path -> runCatching { File(path).appendText("$what\n") } }
        throw IOException(message)
    }

    /** Kastar om [host] är arkivet och spärren är på. Anropas före varje anslutning. */
    @Throws(IOException::class)
    fun check(host: String, what: String) {
        if (!blocked || !isArchive(host)) return
        violations.incrementAndGet()
        val message = "SPÄRRAD: ett test försökte nå arkivservern ($what). Automatiska tester får inte hämta från $ARCHIVE_HOST."
        // Loggen finns inte i JVM-testerna, där android.util.Log är en tom kuliss.
        runCatching { Log.e(LOG_TAG, message) }
        System.getProperty(LOG_PROPERTY)?.let { path -> runCatching { File(path).appendText("$what\n") } }
        throw IOException(message)
    }
}

/**
 * Läggs först på alla appens HTTP-klienter. Utanför tester gör den ingenting.
 * I tester pekar den om arkivets adresser till den lokala servern, eller spärrar dem.
 */
class LiveArchiveGuardInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!BuildConfig.DEBUG || !LiveArchiveGuard.isArchive(request.url.host)) return chain.proceed(request)
        val local = LiveArchiveGuard.replacement
        if (local != null) {
            val url = request.url.newBuilder().scheme(local.scheme).host(local.host).port(local.port).build()
            return chain.proceed(request.newBuilder().url(url).build())
        }
        LiveArchiveGuard.check(request.url.host, "${request.method} ${request.url.host}${request.url.encodedPath}")
        return chain.proceed(request)
    }
}
