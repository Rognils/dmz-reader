package se.c64.dmzreader.data.pdf

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PdfCacheTest {
    private lateinit var directory: File
    private var clock = 1_000_000L

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("dmz-cache").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun cache(maxBytes: Long) = PdfCache(directory, maxBytes) { clock }

    private fun put(issueId: String, bytes: Int, openedAt: Long, suffix: String = ".pdf") {
        File(directory, issueId + suffix).apply {
            writeBytes(ByteArray(bytes))
            setLastModified(openedAt)
        }
    }

    @Test
    fun `least recently opened files are removed first`() {
        put("a", 400, openedAt = 3_000_000)
        put("b", 400, openedAt = 1_000_000)
        put("c", 400, openedAt = 2_000_000)

        val removed = cache(maxBytes = 900).trim()

        assertEquals(listOf("b"), removed)
        assertTrue(File(directory, "a.pdf").exists())
        assertFalse(File(directory, "b.pdf").exists())
        assertTrue(File(directory, "c.pdf").exists())
    }

    @Test
    fun `several files are removed until the cache fits`() {
        put("a", 400, openedAt = 1_000_000)
        put("b", 400, openedAt = 2_000_000)
        put("c", 400, openedAt = 3_000_000)
        assertEquals(listOf("a", "b"), cache(maxBytes = 500).trim())
        assertEquals(400L, cache(500).totalBytes())
    }

    @Test
    fun `the open file is never removed even if it is the oldest and too big`() {
        put("open", 2_000, openedAt = 1_000_000)
        put("other", 400, openedAt = 5_000_000)

        val removed = cache(maxBytes = 1_000).trim(keepIssueId = "open")

        assertEquals(listOf("other"), removed)
        assertTrue(File(directory, "open.pdf").exists())
    }

    @Test
    fun `nothing is removed while the cache is under the limit`() {
        put("a", 400, openedAt = 1_000_000)
        put("b", 400, openedAt = 2_000_000)
        assertEquals(emptyList<String>(), cache(maxBytes = 800).trim())
    }

    @Test
    fun `room is made for a coming download`() {
        put("a", 400, openedAt = 1_000_000)
        put("b", 400, openedAt = 2_000_000)
        // 800 byte finns, taket är 1000, och en fil på 500 byte ska hämtas.
        assertEquals(listOf("a"), cache(maxBytes = 1_000).trim(keepIssueId = "ny", reserveBytes = 500))
    }

    @Test
    fun `opening a file moves it last in the queue`() {
        put("a", 400, openedAt = 1_000_000)
        put("b", 400, openedAt = 2_000_000)
        val cache = cache(maxBytes = 500)
        clock = 9_000_000
        cache.touch("a")

        assertEquals(listOf("b"), cache.trim())
    }

    @Test
    fun `part files count and are removed with their validator`() {
        put("old", 600, openedAt = 1_000_000, suffix = ".pdf.part")
        put("old", 10, openedAt = 1_000_000, suffix = ".pdf.part.validator")
        put("new", 400, openedAt = 2_000_000)

        // Bara färdiga PDF:er rapporteras som borttagna.
        assertEquals(emptyList<String>(), cache(maxBytes = 500).trim())
        assertFalse(File(directory, "old.pdf.part").exists())
        assertFalse(File(directory, "old.pdf.part.validator").exists())
        assertTrue(File(directory, "new.pdf").exists())
    }

    @Test
    fun `the part file of the current download is kept`() {
        put("laddas", 900, openedAt = 1_000_000, suffix = ".pdf.part")
        put("annan", 400, openedAt = 2_000_000)
        assertEquals(listOf("annan"), cache(maxBytes = 1_000).trim(keepIssueId = "laddas"))
        assertTrue(File(directory, "laddas.pdf.part").exists())
    }

    @Test
    fun `paths and state`() {
        val cache = cache(1_000)
        assertEquals(File(directory, "datormagazin-1986-01.pdf"), cache.file("datormagazin-1986-01"))
        assertEquals(File(directory, "datormagazin-1986-01.pdf.part"), cache.partFile("datormagazin-1986-01"))
        assertFalse(cache.isCached("datormagazin-1986-01"))
        put("datormagazin-1986-01", 10, openedAt = 1)
        assertTrue(cache.isCached("datormagazin-1986-01"))
        cache.remove("datormagazin-1986-01")
        assertFalse(cache.isCached("datormagazin-1986-01"))
        assertEquals(1_500L * 1024 * 1024, PdfCache.DEFAULT_MAX_BYTES)
    }
}
