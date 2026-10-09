package se.c64.dmzreader.data.hq

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockftpserver.core.command.Command
import org.mockftpserver.core.command.CommandNames
import org.mockftpserver.core.session.Session
import org.mockftpserver.fake.FakeFtpServer
import org.mockftpserver.fake.UserAccount
import org.mockftpserver.fake.command.PasvCommandHandler
import org.mockftpserver.fake.command.RestCommandHandler
import org.mockftpserver.fake.command.RetrCommandHandler
import org.mockftpserver.fake.command.TypeCommandHandler
import org.mockftpserver.fake.command.UserCommandHandler
import org.mockftpserver.fake.filesystem.DirectoryEntry
import org.mockftpserver.fake.filesystem.FileEntry
import org.mockftpserver.fake.filesystem.UnixFakeFileSystem
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * FTP-källan mot en falsk FTP-server på datorn (MockFtpServer): passivt läge, binär
 * överföring, en anslutning som återanvänds och stängs efter en stund utan användning.
 */
class FtpScanSourceTest {
    private val server = FakeFtpServer()
    private val commands = CopyOnWriteArrayList<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val work = Files.createTempDirectory("dmz-ftp").toFile()
    private val log = CopyOnWriteArrayList<String>()

    /** Servern bryter nästa överföring efter så här många byte. */
    @Volatile
    private var cutAfter: Int? = null

    /** Sant om servern ska svara "klart" trots avbrottet. */
    @Volatile
    private var cutSilently = false

    @Volatile
    private var sizeUnsupported = false

    // Binärt innehåll med alla bytevärden, radslut och NUL: det avslöjar en textöverföring.
    private val pageOne = ByteArray(200_000) { (it * 31 + it / 7).toByte() }
    private val pageTwo = ByteArray(50_000) { (it * 17).toByte() }
    private val directory = "/Original Scans/198601-Original"

    private fun url(name: String) = "ftp://localhost:${server.serverControlPort}/Original%20Scans/198601-Original/$name"
    private fun source(idleCloseMillis: Long = 30_000, user: String = "dmz", password: String = "dmz") =
        FtpScanSource(FtpAccount(user, password), scope, idleCloseMillis, timeoutMillis = 5_000, log = { log += it })

    @Before
    fun start() {
        val files = UnixFakeFileSystem()
        files.add(DirectoryEntry(directory))
        files.add(FileEntry("$directory/DMZ_1986-01-001.jpg").apply { setContents(pageOne) })
        files.add(FileEntry("$directory/DMZ_1986-01-002.jpg").apply { setContents(pageTwo) })
        server.fileSystem = files
        server.addUserAccount(UserAccount("dmz", "dmz", "/"))
        server.serverControlPort = 0
        // Kommandona antecknas i den ordning servern får dem.
        fun note(command: Command) {
            commands += (listOf(command.name) + command.parameters.toList()).joinToString(" ")
        }
        server.setCommandHandler(CommandNames.USER, object : UserCommandHandler() {
            override fun handleCommand(command: Command, session: Session) { note(command); super.handleCommand(command, session) }
        })
        server.setCommandHandler(CommandNames.PASV, object : PasvCommandHandler() {
            override fun handleCommand(command: Command, session: Session) { note(command); super.handleCommand(command, session) }
        })
        server.setCommandHandler(CommandNames.TYPE, object : TypeCommandHandler() {
            override fun handleCommand(command: Command, session: Session) { note(command); super.handleCommand(command, session) }
        })
        // Den falska servern svarar på REST men skickar ändå hela filen. Här följs REST, som på en riktig server.
        var restartAt = 0
        server.setCommandHandler(CommandNames.SIZE, object : org.mockftpserver.fake.command.SizeCommandHandler() {
            override fun handleCommand(command: Command, session: Session) {
                if (sizeUnsupported) session.sendReply(502, "Command not implemented") else super.handleCommand(command, session)
            }
        })
        server.setCommandHandler(CommandNames.REST, object : RestCommandHandler() {
            override fun handleCommand(command: Command, session: Session) {
                note(command)
                restartAt = command.getParameter(0).toInt()
                super.handleCommand(command, session)
            }
        })
        server.setCommandHandler(CommandNames.RETR, object : RetrCommandHandler() {
            override fun handleCommand(command: Command, session: Session) {
                note(command)
                val offset = restartAt
                restartAt = 0
                val entry = files.getEntry(command.getParameter(0)) as? FileEntry
                val cut = cutAfter
                if ((offset == 0 && cut == null) || entry == null) return super.handleCommand(command, session)
                cutAfter = null
                val bytes = entry.createInputStream().use { it.readBytes() }
                val end = if (cut == null) bytes.size else minOf(bytes.size, offset + cut)
                session.sendReply(150, "Opening data connection")
                session.openDataConnection()
                session.sendData(bytes.copyOfRange(offset, end), end - offset)
                session.closeDataConnection()
                // Antingen säger servern ifrån (426), eller så påstår den att allt gick bra fast filen tog slut.
                if (cut != null && !cutSilently) session.sendReply(426, "Connection closed; transfer aborted") else session.sendReply(226, "Transfer complete")
            }
        })
        server.start()
    }

    @After
    fun stop() {
        scope.cancel()
        server.stop()
        work.deleteRecursively()
    }

    @Test
    fun `a file is fetched in passive mode as binary`() = runBlocking {
        val source = source()
        val target = File(work, "sida.jpg.part")
        val progress = ArrayList<Pair<Long, Long?>>()
        source.fetch(url("DMZ_1986-01-001.jpg"), target) { bytes, total -> progress += bytes to total }

        assertArrayEquals(pageOne, target.readBytes())
        assertEquals(listOf("USER dmz", "TYPE I", "PASV", "RETR $directory/DMZ_1986-01-001.jpg"), commands.filterNot { it.startsWith("REST") })
        assertEquals(pageOne.size.toLong() to pageOne.size.toLong(), progress.last())
        assertTrue(progress.first().first == 0L)
        assertTrue(source.connected)
        source.close()
        assertFalse(source.connected)
    }

    @Test
    fun `the connection is reused for the following pages`() = runBlocking {
        val source = source()
        source.fetch(url("DMZ_1986-01-001.jpg"), File(work, "1.part")) { _, _ -> }
        source.fetch(url("DMZ_1986-01-002.jpg"), File(work, "2.part")) { _, _ -> }
        source.fetch(url("DMZ_1986-01-001.jpg"), File(work, "3.part")) { _, _ -> }

        assertArrayEquals(pageTwo, File(work, "2.part").readBytes())
        assertEquals(1, source.connectionsOpened)
        assertEquals(1, commands.count { it.startsWith("USER") })
        assertEquals(3, commands.count { it.startsWith("RETR") })
        source.close()
    }

    @Test
    fun `the connection is closed after a while without use, and the next page opens a new one`() = runBlocking {
        val source = source(idleCloseMillis = 300)
        source.fetch(url("DMZ_1986-01-001.jpg"), File(work, "1.part")) { _, _ -> }
        assertTrue(source.connected)
        withTimeout(5_000) { while (source.connected) delay(20) }
        assertTrue(log.any { "utan användning" in it })

        source.fetch(url("DMZ_1986-01-002.jpg"), File(work, "2.part")) { _, _ -> }
        assertEquals(2, source.connectionsOpened)
        assertArrayEquals(pageTwo, File(work, "2.part").readBytes())
        source.close()
    }

    @Test
    fun `a page fetched just before the idle limit keeps the connection open`() = runBlocking {
        val source = source(idleCloseMillis = 600)
        source.fetch(url("DMZ_1986-01-001.jpg"), File(work, "1.part")) { _, _ -> }
        delay(350)
        source.fetch(url("DMZ_1986-01-002.jpg"), File(work, "2.part")) { _, _ -> }
        delay(350)
        // 700 ms efter den första hämtningen, men bara 350 ms efter den andra.
        assertTrue(source.connected)
        assertEquals(1, source.connectionsOpened)
        source.close()
    }

    @Test
    fun `concurrent requests share one connection and run one at a time`() = runBlocking {
        val source = source()
        (1..6).map { index ->
            async(Dispatchers.IO) { source.fetch(url("DMZ_1986-01-00${index % 2 + 1}.jpg"), File(work, "$index.part")) { _, _ -> } }
        }.awaitAll()
        assertEquals(1, source.connectionsOpened)
        assertEquals(1, commands.count { it.startsWith("USER") })
        for (index in 1..6) {
            assertArrayEquals(if (index % 2 == 0) pageOne else pageTwo, File(work, "$index.part").readBytes())
        }
        source.close()
    }

    @Test
    fun `an interrupted download continues where it stopped`() = runBlocking {
        val source = source()
        val target = File(work, "halv.part")
        target.writeBytes(pageOne.copyOf(120_000))
        val progress = ArrayList<Long>()
        source.fetch(url("DMZ_1986-01-001.jpg"), target) { bytes, _ -> progress += bytes }

        assertArrayEquals(pageOne, target.readBytes())
        assertTrue(commands.toString(), "REST 120000" in commands)
        assertEquals(120_000L, progress.first())
        source.close()
    }

    @Test
    fun `a transfer that the server aborts halfway fails and leaves only the part file`() = runBlocking {
        val source = source()
        val target = File(work, "avbruten.part")
        cutAfter = 80_000
        try {
            source.fetch(url("DMZ_1986-01-001.jpg"), target) { _, _ -> }
            fail("skulle ha kastat")
        } catch (e: ScanFetchException) {
            assertTrue(e.failure == ScanFailure.SERVER || e.failure == ScanFailure.NETWORK)
        }
        // Det som kom fram ligger kvar och är början på filen, inte en hel fil.
        assertEquals(80_000L, target.length())
        assertArrayEquals(pageOne.copyOf(80_000), target.readBytes())

        // Nästa försök fortsätter där det slutade och ger hela filen.
        assertEquals(pageOne.size.toLong(), source.fetch(url("DMZ_1986-01-001.jpg"), target) { _, _ -> })
        assertArrayEquals(pageOne, target.readBytes())
        assertTrue(commands.toString(), "REST 80000" in commands)
        source.close()
    }

    @Test
    fun `a transfer that ends early is an error even when the server says it went well`() = runBlocking {
        val source = source()
        val target = File(work, "tyst.part")
        cutAfter = 50_000
        cutSilently = true
        try {
            source.fetch(url("DMZ_1986-01-001.jpg"), target) { _, _ -> }
            fail("skulle ha kastat")
        } catch (e: ScanFetchException) {
            assertEquals(ScanFailure.NETWORK, e.failure)
            assertTrue(e.message, "50000 av 200000" in e.message!!)
        }
        assertEquals(50_000L, target.length())
        cutSilently = false
        assertEquals(200_000L, source.fetch(url("DMZ_1986-01-001.jpg"), target) { _, _ -> })
        assertArrayEquals(pageOne, target.readBytes())
        source.close()
    }

    @Test
    fun `an aborted transfer never becomes a cached page, and the retry completes it`() = runBlocking {
        // Hela kedjan: FTP-källan, cachen och överföringskön.
        val cache = HqCache(File(work, "hq"))
        val repository = HqRepository(cache, source(), se.c64.dmzreader.data.pdf.TransferCoordinator())
        val scan = se.c64.dmzreader.domain.HqScan(url("DMZ_1986-01-001.jpg"), "DMZ_1986-01-001.jpg")
        for (silent in listOf(false, true)) {
            cache.clear()
            cutAfter = 70_000
            cutSilently = silent
            val failed = repository.page("datormagazin-1986-01", scan)
            assertTrue("$silent: $failed", failed is HqResult.Failure)
            assertFalse(repository.isCached("datormagazin-1986-01", scan))
            assertFalse(cache.file("datormagazin-1986-01", scan.fileName).exists())
            assertEquals(70_000L, cache.partFile("datormagazin-1986-01", scan.fileName).length())

            val retried = repository.page("datormagazin-1986-01", scan) as HqResult.Success
            assertArrayEquals(pageOne, retried.file.readBytes())
            assertFalse(cache.partFile("datormagazin-1986-01", scan.fileName).exists())
        }
    }

    @Test
    fun `a server that does not tell the size is refused`() = runBlocking {
        // Utan SIZE går det inte att veta att hela filen kom fram.
        sizeUnsupported = true
        val source = source()
        try {
            source.fetch(url("DMZ_1986-01-001.jpg"), File(work, "x.part")) { _, _ -> }
            fail("skulle ha kastat")
        } catch (e: ScanFetchException) {
            assertEquals(ScanFailure.SERVER, e.failure)
        }
        assertFalse(File(work, "x.part").exists())
        assertEquals(0, commands.count { it.startsWith("RETR") })
    }

    @Test
    fun `a file that is already complete is not fetched again`() = runBlocking {
        val source = source()
        val target = File(work, "hel.part")
        target.writeBytes(pageTwo)
        source.fetch(url("DMZ_1986-01-002.jpg"), target) { _, _ -> }
        assertArrayEquals(pageTwo, target.readBytes())
        assertEquals(0, commands.count { it.startsWith("RETR") })
        source.close()
    }

    @Test
    fun `a missing file is reported as not found, and the connection stays usable`() = runBlocking {
        val source = source()
        try {
            source.fetch(url("DMZ_1986-01-099.jpg"), File(work, "x.part")) { _, _ -> }
            fail("skulle ha kastat")
        } catch (e: ScanFetchException) {
            assertEquals(ScanFailure.NOT_FOUND, e.failure)
        }
        source.fetch(url("DMZ_1986-01-002.jpg"), File(work, "2.part")) { _, _ -> }
        assertEquals(1, source.connectionsOpened)
        source.close()
    }

    @Test
    fun `a rejected login is a server failure`() = runBlocking {
        val source = source(password = "fel")
        try {
            source.fetch(url("DMZ_1986-01-001.jpg"), File(work, "x.part")) { _, _ -> }
            fail("skulle ha kastat")
        } catch (e: ScanFetchException) {
            assertEquals(ScanFailure.SERVER, e.failure)
        }
        assertFalse(source.connected)
        assertFalse(File(work, "x.part").exists())
    }

    @Test
    fun `a server that can not be reached is a network failure`() = runBlocking {
        val port = server.serverControlPort
        server.stop()
        val source = source()
        try {
            source.fetch("ftp://localhost:$port/x/a.jpg", File(work, "x.part")) { _, _ -> }
            fail("skulle ha kastat")
        } catch (e: ScanFetchException) {
            assertEquals(ScanFailure.NETWORK, e.failure)
        }
        assertFalse(source.connected)
    }

    @Test
    fun `a connection closed by the server is replaced without the user noticing`() = runBlocking {
        val source = source()
        source.fetch(url("DMZ_1986-01-001.jpg"), File(work, "1.part")) { _, _ -> }
        // Servern startas om: den gamla anslutningen är död, och porten är ny.
        server.stop()
        server.serverControlPort = url("x").substringAfter("localhost:").substringBefore("/").toInt()
        server.start()
        source.fetch(url("DMZ_1986-01-002.jpg"), File(work, "2.part")) { _, _ -> }
        assertArrayEquals(pageTwo, File(work, "2.part").readBytes())
        assertEquals(2, source.connectionsOpened)
        source.close()
    }

    @Test
    fun `only ftp addresses are accepted`() = runBlocking {
        val source = source()
        for (bad in listOf("https://dmzarkivet.se/a.jpg", "inte en adress", "ftp:///a.jpg")) {
            try {
                source.fetch(bad, File(work, "x.part")) { _, _ -> }
                fail("skulle ha kastat för $bad")
            } catch (e: ScanFetchException) {
                assertEquals(ScanFailure.SERVER, e.failure)
            }
        }
        assertEquals(0, source.connectionsOpened)
    }
}
