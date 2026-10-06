package se.c64.dmzreader.data.network

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.util.DebugLogger
import coil3.util.Logger
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.Path.Companion.toOkioPath
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bildladdning för omslag (ADR 0015).
 *
 * Arkivet drivs av en privatperson, så appen hämtar försiktigt: högst
 * [MAX_CONCURRENT_REQUESTS] förfrågningar åt gången, och varje bild hämtas
 * bara en gång och sparas sedan i diskcachen.
 */
object CoverImageLoader {
    const val MAX_CONCURRENT_REQUESTS = 4
    const val DISK_CACHE_BYTES = 300L * 1024 * 1024
    const val LOG_TAG = "DmzCovers"
    private const val CACHE_DIRECTORY = "cover_cache"

    fun create(context: Context): ImageLoader {
        val appContext = context.applicationContext
        val debuggable = appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val client = createHttpClient(logRequests = debuggable)
        return ImageLoader.Builder(appContext)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .diskCache {
                DiskCache.Builder()
                    .directory(appContext.cacheDir.resolve(CACHE_DIRECTORY).toOkioPath())
                    .maxSizeBytes(DISK_CACHE_BYTES)
                    .build()
            }
            // Minnescachen har Coils standardstorlek.
            .crossfade(true)
            .apply { if (debuggable) logger(DebugLogger(Logger.Level.Warn)) }
            .build()
    }

    fun createHttpClient(logRequests: Boolean): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            maxRequests = MAX_CONCURRENT_REQUESTS
            maxRequestsPerHost = MAX_CONCURRENT_REQUESTS
        }
        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .addInterceptor(ConcurrencyLimitInterceptor(MAX_CONCURRENT_REQUESTS, logRequests))
            .build()
    }
}

/**
 * Släpper igenom högst [limit] förfrågningar åt gången, oavsett hur de startats.
 * Dispatcherns gräns gäller bara asynkrona anrop; den här gäller alla.
 */
class ConcurrencyLimitInterceptor(
    private val limit: Int,
    private val logRequests: Boolean = false,
) : Interceptor {
    private val permits = Semaphore(limit, true)
    private val active = AtomicInteger(0)

    /** Högsta antal samtidiga förfrågningar som har förekommit. */
    val peak = AtomicInteger(0)

    override fun intercept(chain: Interceptor.Chain): Response {
        permits.acquire()
        val now = active.incrementAndGet()
        peak.accumulateAndGet(now, ::maxOf)
        if (logRequests) {
            Log.d(CoverImageLoader.LOG_TAG, "start active=$now ${chain.request().url.encodedPath}")
        }
        try {
            return chain.proceed(chain.request())
        } finally {
            val remaining = active.decrementAndGet()
            permits.release()
            if (logRequests) {
                Log.d(CoverImageLoader.LOG_TAG, "klar  active=$remaining ${chain.request().url.encodedPath}")
            }
        }
    }
}
