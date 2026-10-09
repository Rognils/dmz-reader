package se.c64.dmzreader.data.search

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import se.c64.dmzreader.testing.TestFiles
import java.io.File
import java.io.InputStream

/** Import av search.db: kopiering, kontroll, atomiskt byte och återgång vid fel. */
class SearchIndexManagerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val good: File get() = TestFiles.searchTestDb
    private val directory: File by lazy { folder.newFolder("data") }
    private var clock = 1_000_000L

    private fun manager(free: Long = Long.MAX_VALUE) =
        SearchIndexManager(directory, freeBytes = { free }, now = { clock })

    private val active get() = File(directory, "search.db")
    private val leftovers get() = directory.listFiles().orEmpty().map { it.name }.sorted()

    /** En kopia av testindexet, ändrad med SQL. */
    private fun variant(name: String, vararg sql: String): File {
        val file = folder.newFile(name)
        good.copyTo(file, overwrite = true)
        BundledSQLiteDriver().open(file.path).use { connection -> sql.forEach { connection.execSQL(it) } }
        return file
    }

    private fun importFails(manager: SearchIndexManager, file: File, total: Long? = file.length()): String = runBlocking {
        try {
            manager.import({ file.inputStream() }, total)
            fail("importen borde ha misslyckats")
            ""
        } catch (e: SearchIndexException) {
            e.message.orEmpty()
        }
    }

    @Test
    fun `a valid index is imported and described`() = runBlocking {
        val manager = manager()
        assertNull(manager.index.value)
        val progress = ArrayList<CopyProgress>()

        val info = manager.import({ good.inputStream() }, good.length(), progress::add)

        assertEquals("2026-10-06-005", info.inventoryDatasetVersion)
        assertEquals("2", info.normalizerVersion)
        assertEquals("unicode61 remove_diacritics 0", info.tokenizer)
        assertEquals(6, info.issueCount)
        assertEquals(good.length(), info.sizeBytes)
        assertEquals(clock, info.importedAt)
        assertEquals(listOf("search.db"), leftovers)
        assertEquals(good.readBytes().toList(), active.readBytes().toList())
        // Förloppet börjar på noll och slutar på hela filen.
        assertEquals(CopyProgress(0, good.length()), progress.first())
        assertEquals(CopyProgress(good.length(), good.length()), progress.last())
        assertEquals(100, progress.last().percent)
        val loaded = manager.index.value!!
        assertEquals(info.pageCount, loaded.pages.size)
        assertTrue("datormagazin-1995-14" in loaded.issueIds)
    }

    @Test
    fun `progress text`() {
        assertEquals("62 av 149 MB · 41 %", copyProgressText(CopyProgress(62_000_000, 148_877_312)))
        assertEquals("0 av 149 MB · 0 %", copyProgressText(CopyProgress(0, 148_877_312)))
        assertEquals("149 av 149 MB · 100 %", copyProgressText(CopyProgress(148_877_312, 148_877_312)))
        assertEquals("62 MB", copyProgressText(CopyProgress(62_000_000, null)))
        assertNull(CopyProgress(5, null).percent)
    }

    @Test
    fun `ranking and snippets come in two steps`() = runBlocking {
        val manager = manager()
        manager.import({ good.inputStream() }, null)
        val pages = manager.index.value!!.pages

        val ranked = manager.rank("\"högtalare\"")!!
        assertEquals(4, ranked.size)
        assertTrue(ranked.zipWithNext().all { (a, b) -> a.score <= b.score })
        val best = pages.getValue(ranked.first().rowId)
        assertEquals("datormagazin-1995-14", best.issueId)

        val page26 = ranked.first { pages.getValue(it.rowId).pageNumber == 26 }
        val snippet = manager.snippet(page26.rowId, "\"högtalare\"")!!
        assertTrue(snippet, snippet.startsWith("Nya ${SNIPPET_OPEN}högtalare$SNIPPET_CLOSE är ett bra sätt"))
        // En rad som inte matchar frågan ger inget utdrag.
        val other = pages.values.first { it.issueId == "datormagazin-1986-01" && it.pageNumber == 4 }
        assertNull(manager.snippet(other.rowId, "\"högtalare\""))
        assertNotNull(manager.snippet(other.rowId, "\"Commodore 128\""))
    }

    @Test
    fun `without an index there is nothing to search`() = runBlocking {
        val manager = manager()
        assertNull(manager.rank("\"amiga\""))
        assertNull(manager.snippet(1, "\"amiga\""))
    }

    @Test
    fun `a file that is not a database is rejected`() {
        val text = folder.newFile("text.db").apply { writeText("Det här är inte en databas. ".repeat(500)) }
        val message = importFails(manager(), text)

        assertTrue(message, message.startsWith("Filen är inte ett sökindex"))
        assertEquals(emptyList<String>(), leftovers)
    }

    @Test
    fun `a truncated file is rejected`() {
        val truncated = folder.newFile("half.db").apply { writeBytes(good.readBytes().copyOf((good.length() / 2).toInt())) }
        val message = importFails(manager(), truncated)

        assertTrue(message, message.startsWith("Filen är skadad") || message.startsWith("Filen är inte ett sökindex") || message.startsWith("Sökindexet går inte"))
        assertEquals(emptyList<String>(), leftovers)
    }

    @Test
    fun `an unsupported schema version is rejected with the version in the message`() {
        val newer = variant("v2.db", "UPDATE meta SET value = '2' WHERE key = 'searchSchemaVersion'")
        assertEquals(
            "Sökindexet har searchSchemaVersion 2. Den här versionen av appen stöder bara 1. " +
                "Uppdatera appen eller bygg om sökindexet.",
            importFails(manager(), newer),
        )
        val none = variant("none.db", "DELETE FROM meta WHERE key = 'searchSchemaVersion'")
        assertEquals("Filen saknar searchSchemaVersion och är inte ett sökindex för DMZ Reader.", importFails(manager(), none))
    }

    @Test
    fun `missing tables are named`() {
        assertEquals(
            "Filen saknar tabellen issues och är inte ett sökindex för DMZ Reader.",
            importFails(manager(), variant("noissues.db", "DROP TABLE issues")),
        )
        assertEquals(
            "Filen saknar tabellen pages_fts och är inte ett sökindex för DMZ Reader.",
            importFails(manager(), variant("nofts.db", "DROP TABLE pages_fts")),
        )
        assertEquals(
            "Filen saknar tabellen meta och är inte ett sökindex för DMZ Reader.",
            importFails(manager(), variant("nometa.db", "DROP TABLE meta")),
        )
        // En annan SQLite-databas, till exempel den gamla testdatabasen från FAS 1, är inget sökindex.
        val message = importFails(manager(), TestFiles.ftsTestDb)
        assertTrue(message, message.startsWith("Filen saknar"))
    }

    @Test
    fun `an empty index is rejected`() {
        val empty = variant("empty.db", "DELETE FROM issues")
        assertEquals("Sökindexet är tomt: det innehåller inga nummer eller inga sidor.", importFails(manager(), empty))
    }

    @Test
    fun `a failed import leaves the active index untouched`() = runBlocking {
        val manager = manager()
        manager.import({ good.inputStream() }, good.length())
        val before = active.readBytes().toList()
        val importedAt = manager.index.value!!.info.importedAt
        clock += 5_000

        val broken = variant("broken.db", "UPDATE meta SET value = '7' WHERE key = 'searchSchemaVersion'")
        importFails(manager, broken)
        importFails(manager, folder.newFile("junk.db").apply { writeText("skräp") })

        assertEquals(listOf("search.db"), leftovers)
        assertEquals(before, active.readBytes().toList())
        assertEquals(importedAt, manager.index.value!!.info.importedAt)
        // Det går fortfarande att söka.
        assertEquals(4, manager.rank("\"högtalare\"")!!.size)
    }

    @Test
    fun `a new index replaces the old one`() = runBlocking {
        val manager = manager()
        manager.import({ good.inputStream() }, good.length())
        clock += 60_000
        val smaller = variant("smaller.db", "DELETE FROM issues WHERE issueId = 'datormagazin-1999-01'")

        val info = manager.import({ smaller.inputStream() }, smaller.length())

        assertEquals(5, info.issueCount)
        assertEquals(clock, info.importedAt)
        assertEquals(listOf("search.db"), leftovers)
        assertFalse("datormagazin-1999-01" in manager.index.value!!.issueIds)
    }

    @Test
    fun `an index built from another archive version is imported and searchable`() = runBlocking {
        // Planens D3: en annan arkivversion är en varning i gränssnittet, ingen spärr.
        val other = variant("other.db", "UPDATE meta SET value = '2020-01-01-001' WHERE key = 'inventoryDatasetVersion'")
        val manager = manager()

        val info = manager.import({ other.inputStream() }, other.length())

        assertEquals("2020-01-01-001", info.inventoryDatasetVersion)
        assertEquals(4, manager.rank("\"högtalare\"")!!.size)
        val active = se.c64.dmzreader.data.settings.ActiveInventory("2026-10-06-005", 1)
        assertTrue(se.c64.dmzreader.ui.settings.SearchIndexUiState(info = info, active = active).otherVersion)
        assertFalse(se.c64.dmzreader.ui.settings.SearchIndexUiState(info = info.copy(inventoryDatasetVersion = "2026-10-06-005"), active = active).otherVersion)
        assertFalse(se.c64.dmzreader.ui.settings.SearchIndexUiState(info = null, active = active).otherVersion)
    }

    @Test
    fun `too little free space stops the import before copying`() {
        val message = importFails(manager(free = 10_000), good, total = 148_877_312)
        assertEquals("Det finns inte plats för sökindexet. Det behövs 198 MB ledigt.", message)
        assertEquals(emptyList<String>(), leftovers)
    }

    @Test
    fun `a read error during copying is reported and cleaned up`() {
        val failing = object : InputStream() {
            var served = 0
            override fun read(): Int = throw java.io.IOException("läsfel")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (served > 0) throw java.io.IOException("anslutningen bröts")
                served = 1000
                return 1000
            }
        }
        val message = runBlocking {
            try {
                manager().import({ failing }, null)
                ""
            } catch (e: SearchIndexException) {
                e.message.orEmpty()
            }
        }
        assertEquals("Filen gick inte att kopiera: anslutningen bröts", message)
        assertEquals(emptyList<String>(), leftovers)
    }

    @Test
    fun `a cancelled import removes the partial file`() = runBlocking {
        val manager = manager()
        val endless = object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                Thread.sleep(5)
                return length
            }
        }
        val job = launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                manager.import({ endless }, null)
                fail("importen borde ha avbrutits")
            } catch (e: CancellationException) {
                throw e
            }
        }
        while (!File(directory, "search.db.part").exists()) Thread.sleep(5)
        job.cancelAndJoin()

        assertEquals(emptyList<String>(), leftovers)
        assertNull(manager.index.value)
    }

    @Test
    fun `remove deletes the file and closes the index`() = runBlocking {
        val manager = manager()
        manager.import({ good.inputStream() }, good.length())

        manager.remove()

        assertNull(manager.index.value)
        assertEquals(emptyList<String>(), leftovers)
        assertNull(manager.rank("\"amiga\""))
    }

    @Test
    fun `startup opens the imported index and cleans up after an interrupted import`() = runBlocking {
        manager().import({ good.inputStream() }, good.length())
        File(directory, "search.db.part").writeText("halvfärdig kopia")

        val restarted = manager()
        restarted.loadAtStartup()

        assertEquals(listOf("search.db"), leftovers)
        assertEquals(6, restarted.index.value!!.info.issueCount)
        assertEquals(4, restarted.rank("\"högtalare\"")!!.size)
    }

    @Test
    fun `startup restores the previous index if the swap was never confirmed`() = runBlocking {
        manager().import({ good.inputStream() }, good.length())
        // Som om appen dog mitt i bytet: den gamla filen undanflyttad och en trasig ny på plats.
        active.renameTo(File(directory, "search.db.previous"))
        active.writeText("trasig")

        val restarted = manager()
        restarted.loadAtStartup()

        assertEquals(listOf("search.db"), leftovers)
        assertEquals(good.readBytes().toList(), active.readBytes().toList())
        assertEquals(6, restarted.index.value!!.info.issueCount)
    }

    @Test
    fun `startup ignores an unreadable file without crashing`() = runBlocking {
        directory.mkdirs()
        active.writeText("inte en databas")

        val manager = manager()
        manager.loadAtStartup()

        assertNull(manager.index.value)
        assertNull(manager.rank("\"amiga\""))
    }
}
