package se.c64.dmzreader.data.hq

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.c64.dmzreader.data.pdf.DownloadProgress
import se.c64.dmzreader.data.pdf.TransferCoordinator
import se.c64.dmzreader.domain.HqScan
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * HQ-sidor genom cachen och överföringskön (planens FAS 11): aldrig två överföringar
 * samtidigt, HQ före nedladdningskön, felhantering och läsning ur cachen utan nät.
 */
class HqRepositoryTest {
    private val root = Files.createTempDirectory("dmz-hq-repo").toFile()
    private val cache = HqCache(File(root, "hq"))
    private val transfers = TransferCoordinator()
    private val issueId = "datormagazin-1986-01"

    private fun scan(page: Int) = HqScan("ftp://arkiv.test/scans/sida-$page.jpg", "sida-$page.jpg")
    private fun content(url: String) = "skanning $url ".repeat(200).toByteArray()

    /** En falsk källa som räknar samtidiga överföringar och kan fås att misslyckas eller vänta. */
    private inner class FakeSource : ScanSource {
        val fetched = CopyOnWriteArrayList<String>()
        val running = AtomicInteger()
        val peak = AtomicInteger()
        var failure: ScanFetchException? = null
        var gate: CompletableDeferred<Unit>? = null
        var started: CompletableDeferred<Unit>? = null
        var delayMillis = 0L

        /** Anropas när filen är färdigskriven, medan överföringen fortfarande håller sin tur. */
        var onFinished: () -> Unit = {}

        /** Så många byte skrivs, när källan ska lämna en kortare fil än den uppger. null betyder hela filen. */
        var writeOnly: Int? = null

        override suspend fun fetch(url: String, target: File, onProgress: (Long, Long?) -> Unit): Long {
            val now = running.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            try {
                fetched += url
                started?.complete(Unit)
                failure?.let { throw it }
                val bytes = content(url)
                target.writeBytes(bytes.copyOf(bytes.size / 2))
                onProgress(bytes.size / 2L, bytes.size.toLong())
                gate?.await()
                delay(delayMillis)
                target.writeBytes(writeOnly?.let { bytes.copyOf(it) } ?: bytes)
                onProgress(bytes.size.toLong(), bytes.size.toLong())
                onFinished()
                return bytes.size.toLong()
            } finally {
                running.decrementAndGet()
            }
        }
    }

    private val source = FakeSource()
    private val repository = HqRepository(cache, source, transfers)

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `a page is fetched once and then served from the cache`() = runBlocking {
        val progress = CopyOnWriteArrayList<DownloadProgress>()
        val first = repository.page(issueId, scan(1)) { progress += it } as HqResult.Success
        assertFalse(first.fromCache)
        assertEquals(File(root, "hq/$issueId/sida-1.jpg"), first.file)
        assertArrayEquals(content(scan(1).url), first.file.readBytes())
        assertEquals(1f, progress.last().fraction)
        assertTrue(repository.isCached(issueId, scan(1)))

        val second = repository.page(issueId, scan(1)) as HqResult.Success
        assertTrue(second.fromCache)
        assertEquals(first.file, second.file)
        assertEquals(listOf(scan(1).url), source.fetched.toList())
    }

    @Test
    fun `a cached page works when the archive can not be reached`() = runBlocking {
        repository.page(issueId, scan(1))
        source.failure = ScanFetchException(ScanFailure.NETWORK, "inget nät")
        assertTrue((repository.page(issueId, scan(1)) as HqResult.Success).fromCache)
        // En sida som inte är hämtad ger ett fel, och cachen är orörd.
        val failed = repository.page(issueId, scan(2)) as HqResult.Failure
        assertEquals(ScanFailure.NETWORK, failed.failure)
        assertFalse(repository.isCached(issueId, scan(2)))
        assertTrue(repository.isCached(issueId, scan(1)))
    }

    @Test
    fun `failures give a message that says the pdf page is shown instead`() = runBlocking {
        for (failure in ScanFailure.entries) {
            source.failure = ScanFetchException(failure, "tekniskt fel")
            val result = repository.page(issueId, scan(3)) as HqResult.Failure
            assertEquals(failure, result.failure)
            assertTrue(result.message, result.message.startsWith("HQ gick inte att hämta"))
            assertTrue(result.message, result.message.endsWith("PDF-sidan visas."))
            assertFalse("tekniskt fel" in result.message)
        }
        assertEquals(
            "HQ gick inte att hämta. Det gick inte att nå DMZArkivet. Kontrollera din internetanslutning och " +
                "försök igen. Arkivet körs på en privat server och kan ibland vara tillfälligt otillgängligt. " +
                "PDF-sidan visas.",
            hqFailureMessage(ScanFailure.NETWORK),
        )
        assertTrue("finns inte" in hqFailureMessage(ScanFailure.NOT_FOUND))
        // Efter ett fel går det att försöka igen.
        source.failure = null
        assertTrue(repository.page(issueId, scan(3)) is HqResult.Success)
    }

    @Test
    fun `an unexpected error is a failure, not a crash`() = runBlocking {
        val broken = object : ScanSource {
            override suspend fun fetch(url: String, target: File, onProgress: (Long, Long?) -> Unit): Long = error("oväntat")
        }
        val result = HqRepository(cache, broken, transfers).page(issueId, scan(1))
        assertEquals(ScanFailure.SERVER, (result as HqResult.Failure).failure)
    }

    @Test
    fun `there are never two transfers at the same time`() = runBlocking {
        source.delayMillis = 20
        val results = (1..8).map { page -> async(Dispatchers.IO) { repository.page(issueId, scan(page)) } }.awaitAll()
        assertTrue(results.all { it is HqResult.Success })
        assertEquals(8, source.fetched.size)
        assertEquals(1, source.peak.get())
    }

    @Test
    fun `two requests for the same page fetch it once`() = runBlocking {
        source.delayMillis = 50
        val results = (1..4).map { async(Dispatchers.IO) { repository.page(issueId, scan(1)) } }.awaitAll()
        assertTrue(results.all { it is HqResult.Success })
        assertEquals(1, source.fetched.size)
    }

    @Test
    fun `an hq page goes before the download queue, which continues afterwards`() = runBlocking {
        val order = CopyOnWriteArrayList<String>()
        val backgroundStarted = CompletableDeferred<Unit>()
        val background = async(Dispatchers.IO) {
            transfers.background {
                backgroundStarted.complete(Unit)
                try {
                    delay(60_000)
                    "klar"
                } finally {
                    order += "kön pausad"
                }
            }
        }
        backgroundStarted.await()
        source.onFinished = { order += "hq klar" }
        val result = withTimeout(5_000) { repository.page(issueId, scan(1)) }
        assertTrue(result is HqResult.Success)
        // Köns överföring avbröts och rapporterade det med null, så att den kan fortsätta senare.
        assertNull(background.await())
        assertEquals(listOf("kön pausad", "hq klar"), order.toList())
        assertEquals(1, source.peak.get())
        assertEquals("igen", withTimeout(5_000) { transfers.background { "igen" } })
    }

    @Test
    fun `the queue does not start while an hq page is being fetched`() = runBlocking {
        source.gate = CompletableDeferred()
        source.started = CompletableDeferred()
        val order = CopyOnWriteArrayList<String>()
        // Antecknas inne i överföringen: efter att den har lämnat sin tur får kön börja när som helst.
        source.onFinished = { order += "hq klar" }
        val page = async(Dispatchers.IO) { repository.page(issueId, scan(1)) }
        source.started!!.await()
        val background = async(Dispatchers.IO) { transfers.background { order += "kön"; "x" } }
        delay(200)
        assertTrue(order.isEmpty())
        source.gate!!.complete(Unit)
        assertTrue(page.await() is HqResult.Success)
        assertEquals("x", withTimeout(5_000) { background.await() })
        assertEquals(listOf("hq klar", "kön"), order.toList())
    }

    @Test
    fun `a cancelled fetch leaves the part file and no cached page`() = runBlocking {
        source.gate = CompletableDeferred()
        source.started = CompletableDeferred()
        val job = launch(Dispatchers.IO) { repository.page(issueId, scan(1)) }
        source.started!!.await()
        withTimeout(5_000) { while (!cache.partFile(issueId, "sida-1.jpg").isFile) delay(5) }
        job.cancel()
        job.join()
        assertFalse(repository.isCached(issueId, scan(1)))
        assertTrue(cache.partFile(issueId, "sida-1.jpg").isFile)
        assertEquals(0, source.running.get())
        // Nästa försök gör sidan färdig.
        source.gate = null
        assertTrue(repository.page(issueId, scan(1)) is HqResult.Success)
        assertFalse(cache.partFile(issueId, "sida-1.jpg").exists())
    }

    @Test
    fun `a download that came up short is not shown as a complete page`() = runBlocking {
        // Källan säger att allt gick bra, men filen är kortare än servern uppgav.
        source.writeOnly = 1_000
        val result = repository.page(issueId, scan(1))
        assertEquals(ScanFailure.NETWORK, (result as HqResult.Failure).failure)
        assertFalse(repository.isCached(issueId, scan(1)))
        assertFalse(cache.file(issueId, "sida-1.jpg").exists())
        assertEquals(1_000L, cache.partFile(issueId, "sida-1.jpg").length())
        // Nästa försök, med hela filen, lyckas.
        source.writeOnly = null
        val retried = repository.page(issueId, scan(1)) as HqResult.Success
        assertArrayEquals(content(scan(1).url), retried.file.readBytes())
        assertEquals(2, source.fetched.size)
    }

    @Test
    fun `a truncated file in the cache is detected and fetched again`() = runBlocking {
        val first = repository.page(issueId, scan(1)) as HqResult.Success
        val whole = first.file.readBytes()
        // Filen i cachen kortas bakom ryggen på appen.
        first.file.writeBytes(whole.copyOf(whole.size / 3))
        assertFalse(repository.isCached(issueId, scan(1)))

        val again = repository.page(issueId, scan(1)) as HqResult.Success
        assertFalse(again.fromCache)
        assertArrayEquals(whole, again.file.readBytes())
        assertEquals(2, source.fetched.size)
        assertTrue((repository.page(issueId, scan(1)) as HqResult.Success).fromCache)
    }

    @Test
    fun `the cache is kept under its limit while pages are fetched`() = runBlocking {
        val size = content(scan(1).url).size.toLong()
        val small = HqCache(File(root, "liten"), maxBytes = size * 2 + size / 2)
        val limited = HqRepository(small, source, transfers)
        for (page in 1..5) {
            limited.page(issueId, scan(page))
            Thread.sleep(15) // filernas ändringstid avgör ordningen
        }
        assertTrue(small.sizeBytes() <= size * 2 + size / 2)
        assertTrue(limited.isCached(issueId, scan(5)))
        assertTrue(limited.isCached(issueId, scan(4)))
        assertFalse(limited.isCached(issueId, scan(1)))
        assertEquals(small.sizeBytes(), limited.cacheSizeBytes())
        assertTrue(limited.clearCache() > 0)
        assertEquals(0L, limited.cacheSizeBytes())
    }
}
