package se.c64.dmzreader.data.network

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ConcurrencyLimitInterceptorTest {

    @Test
    fun `never more than four requests at once`() {
        val limiter = ConcurrencyLimitInterceptor(limit = 4)
        val insideNetwork = AtomicInteger(0)
        val peakInsideNetwork = AtomicInteger(0)
        // Ersätter nätet: varje "förfrågan" tar en stund, så att de överlappar.
        val fakeNetwork = Interceptor { chain ->
            val now = insideNetwork.incrementAndGet()
            peakInsideNetwork.accumulateAndGet(now, ::maxOf)
            Thread.sleep(30)
            insideNetwork.decrementAndGet()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("x".toResponseBody())
                .build()
        }
        val client = OkHttpClient.Builder().addInterceptor(limiter).addInterceptor(fakeNetwork).build()

        val requests = 40
        val pool = Executors.newFixedThreadPool(requests)
        val done = CountDownLatch(requests)
        repeat(requests) { index ->
            pool.execute {
                // Synkrona anrop, som dispatcherns gräns inte gäller för.
                client.newCall(Request.Builder().url("https://example.invalid/$index.jpg").build())
                    .execute().close()
                done.countDown()
            }
        }
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdown()

        assertEquals(4, peakInsideNetwork.get())
        assertEquals(4, limiter.peak.get())
    }

    @Test
    fun `a failing request releases its slot`() {
        val limiter = ConcurrencyLimitInterceptor(limit = 1)
        val failing = Interceptor { throw java.io.IOException("inget nät") }
        val client = OkHttpClient.Builder().addInterceptor(limiter).addInterceptor(failing).build()
        repeat(3) {
            try {
                client.newCall(Request.Builder().url("https://example.invalid/a.jpg").build()).execute()
            } catch (e: java.io.IOException) {
                // väntat
            }
        }
        assertEquals(1, limiter.peak.get())
    }

    @Test
    fun `the image client is limited to four requests`() {
        val client = CoverImageLoader.createHttpClient(logRequests = false)
        assertEquals(4, CoverImageLoader.MAX_CONCURRENT_REQUESTS)
        assertEquals(4, client.dispatcher.maxRequests)
        assertEquals(4, client.dispatcher.maxRequestsPerHost)
        assertTrue(client.interceptors.any { it is ConcurrencyLimitInterceptor })
        assertEquals(300L * 1024 * 1024, CoverImageLoader.DISK_CACHE_BYTES)
    }
}
