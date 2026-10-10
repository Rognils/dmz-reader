package se.c64.dmzreader.data.hq

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** HQ-cachen: cacheDir/hq/{issueId}/{fil} med ett tak, äldst använda först ut (planens FAS 11). */
class HqCacheTest {
    private val root = Files.createTempDirectory("dmz-hq").toFile()
    private var clock = 1_000_000_000_000L
    private fun cache(maxBytes: Long = 1_000) = HqCache(File(root, "hq"), maxBytes) { clock }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    /** Lägger en fil i cachen som om den just hade hämtats. Klockan går en minut per fil. */
    private fun HqCache.add(issueId: String, name: String, size: Int): File {
        clock += 60_000
        val part = partFile(issueId, name)
        part.parentFile!!.mkdirs()
        part.writeBytes(ByteArray(size))
        return commit(issueId, name, size.toLong())
    }

    @Test
    fun `files are stored per issue under the cache directory`() {
        val cache = cache()
        val file = cache.add("datormagazin-1986-01", "DMZ_1986-01-001.jpg", 100)
        assertEquals(File(root, "hq/datormagazin-1986-01/DMZ_1986-01-001.jpg"), file)
        assertTrue(file.isFile)
        assertFalse(cache.partFile("datormagazin-1986-01", "DMZ_1986-01-001.jpg").exists())
        assertEquals(file, cache.cached("datormagazin-1986-01", "DMZ_1986-01-001.jpg"))
        assertNull(cache.cached("datormagazin-1986-01", "DMZ_1986-01-002.jpg"))
        assertNull(cache.cached("datormagazin-1986-02", "DMZ_1986-01-001.jpg"))
        assertEquals(100L, cache.sizeBytes())
    }

    @Test
    fun `the least recently used files are removed when the limit is passed`() {
        val cache = cache(maxBytes = 1_000)
        val a = cache.add("x", "a.jpg", 400)
        val b = cache.add("x", "b.jpg", 400)
        // a används igen och är därmed nyare än b.
        clock += 60_000
        cache.cached("x", "a.jpg")
        val c = cache.add("y", "c.jpg", 400)
        assertTrue(a.isFile)
        assertFalse(b.exists())
        assertTrue(c.isFile)
        assertEquals(800L, cache.sizeBytes())
    }

    @Test
    fun `the file just fetched is kept even when it alone is over the limit`() {
        val cache = cache(maxBytes = 1_000)
        cache.add("x", "a.jpg", 600)
        val big = cache.add("x", "stor.png", 1_500)
        assertTrue(big.isFile)
        assertEquals(1_500L, cache.sizeBytes())
        // Nästa hämtning tar bort den.
        val next = cache.add("x", "b.jpg", 300)
        assertFalse(big.exists())
        assertTrue(next.isFile)
    }

    @Test
    fun `unfinished downloads are removed before finished pages`() {
        val cache = cache(maxBytes = 1_000)
        val a = cache.add("x", "a.jpg", 400)
        val part = cache.partFile("x", "halv.jpg").apply { writeBytes(ByteArray(500)) }
        part.setLastModified(clock + 3_600_000)
        val b = cache.add("x", "b.jpg", 400)
        assertFalse(part.exists())
        assertTrue(a.isFile)
        assertTrue(b.isFile)
    }

    @Test
    fun `nothing is removed while the cache is under the limit`() {
        val cache = cache(maxBytes = 1_000)
        val files = listOf(cache.add("x", "a.jpg", 300), cache.add("x", "b.jpg", 300), cache.add("y", "c.jpg", 400))
        assertTrue(files.all { it.isFile })
        assertEquals(1_000L, cache.sizeBytes())
    }

    @Test
    fun `an empty issue directory is removed with its last file`() {
        val cache = cache(maxBytes = 500)
        cache.add("gammal", "a.jpg", 400)
        cache.add("ny", "b.jpg", 400)
        assertFalse(File(root, "hq/gammal").exists())
        assertTrue(File(root, "hq/ny").isDirectory)
    }

    @Test
    fun `clearing removes everything and reports the freed size`() {
        val cache = cache()
        cache.add("x", "a.jpg", 300)
        cache.add("y", "b.jpg", 200)
        assertEquals(500L, cache.clear())
        assertEquals(0L, cache.sizeBytes())
        assertNull(cache.cached("x", "a.jpg"))
        // En tom cache går att rensa och använda igen.
        assertEquals(0L, cache.clear())
        assertTrue(cache.add("x", "a.jpg", 10).isFile)
    }

    @Test
    fun `a file without a recorded size is not a cached page`() {
        // En fil som hamnat i katalogen på annat sätt än genom en verifierad hämtning.
        val cache = cache()
        val file = cache.file("x", "okand.jpg")
        file.parentFile!!.mkdirs()
        file.writeBytes(ByteArray(100))
        assertNull(cache.cached("x", "okand.jpg"))
        assertFalse(file.exists())
        file.writeBytes(ByteArray(0))
        assertNull(cache.cached("x", "okand.jpg"))
    }

    @Test
    fun `a truncated file in the cache is detected and removed`() {
        val cache = cache()
        val file = cache.add("x", "a.jpg", 400)
        assertEquals(file, cache.cached("x", "a.jpg"))
        // Filen kortas, som efter ett diskfel eller en avbruten skrivning.
        file.writeBytes(ByteArray(250))
        assertNull(cache.cached("x", "a.jpg"))
        assertFalse(file.exists())
        // Uppgiften om storleken är också borta, så att nästa hämtning börjar rent.
        assertEquals(0L, cache.sizeBytes())
        // Detsamma gäller en fil som blivit längre.
        val other = cache.add("x", "b.jpg", 400)
        other.appendBytes(ByteArray(10))
        assertNull(cache.cached("x", "b.jpg"))
    }

    @Test
    fun `a download of the wrong size is never moved into the cache`() {
        val cache = cache()
        val part = cache.partFile("x", "a.jpg")
        part.parentFile!!.mkdirs()
        part.writeBytes(ByteArray(250))
        for (expected in listOf(400L, 249L, 0L, -1L)) {
            try {
                cache.commit("x", "a.jpg", expected)
                org.junit.Assert.fail("skulle ha vägrat för $expected")
            } catch (e: java.io.IOException) {
                // väntat
            }
        }
        assertFalse(cache.file("x", "a.jpg").exists())
        assertNull(cache.cached("x", "a.jpg"))
        // Det hämtade ligger kvar som .part, så att hämtningen kan fortsätta.
        assertEquals(250L, part.length())
        assertEquals(cache.file("x", "a.jpg"), cache.commit("x", "a.jpg", 250L))
        assertEquals(cache.file("x", "a.jpg"), cache.cached("x", "a.jpg"))
    }

    @Test
    fun `removing a page for space removes its recorded size too`() {
        val cache = cache(maxBytes = 500)
        cache.add("x", "a.jpg", 400)
        cache.add("x", "b.jpg", 400)
        assertEquals(listOf("b.jpg", "b.jpg.size"), File(root, "hq/x").list()!!.sorted())
    }

    @Test
    fun `the default limit is 500 MB`() {
        assertEquals(500_000_000L, HqCache.MAX_BYTES)
    }
}
