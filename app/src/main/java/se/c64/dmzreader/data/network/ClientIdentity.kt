package se.c64.dmzreader.data.network

import okhttp3.Interceptor
import okhttp3.Response
import se.c64.dmzreader.BuildConfig

/**
 * Hur appen presenterar sig för arkivservern. Arkivet drivs av en privatperson,
 * som ska kunna se i sina loggar vilken klient trafiken kommer från och var den
 * som har frågor kan vända sig.
 *
 * Samma sträng används överallt: som User-Agent i all HTTP-trafik, och som
 * argument till CLNT när FTP-servern säger sig stödja det.
 */
object ClientIdentity {
    const val PROJECT_URL = "https://github.com/Rognils/dmz-reader"

    /** "DMZReader/1.0.0 (Android; +https://github.com/Rognils/dmz-reader)". */
    fun userAgent(versionName: String): String = "DMZReader/$versionName (Android; +$PROJECT_URL)"

    /** Identiteten för den här versionen av appen. Versionen kommer från bygget. */
    val userAgent: String = userAgent(BuildConfig.VERSION_NAME)
}

/**
 * Sätter User-Agent på varje förfrågan. Läggs på alla appens HTTP-klienter.
 * Med [log] skrivs den header som faktiskt skickas, för kontroll i debug-bygget.
 */
class UserAgentInterceptor(
    private val userAgent: String = ClientIdentity.userAgent,
    private val log: ((String) -> Unit)? = null,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder().header("User-Agent", userAgent).build()
        log?.invoke("User-Agent: ${request.header("User-Agent")} · ${request.method} ${request.url.host}${request.url.encodedPath}")
        return chain.proceed(request)
    }
}
