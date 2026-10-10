package se.c64.dmzreader.data.hq

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import kotlin.coroutines.coroutineContext

/** Kontot som appen loggar in med. Arkivets konto är publikt (planens A2). */
data class FtpAccount(val user: String, val password: String)

/**
 * Hämtar skanningar via FTP: passivt läge, binär överföring, en enda anslutning.
 *
 * Anslutningen återanvänds för följande sidor och stängs efter [idleCloseMillis]
 * utan användning. Alla anrop går i tur och ordning genom ett lås, så det finns
 * aldrig mer än en anslutning och aldrig mer än en överföring.
 */
class FtpScanSource(
    private val account: FtpAccount,
    /** Lever lika länge som appen: här körs den fördröjda stängningen. */
    private val scope: CoroutineScope,
    private val idleCloseMillis: Long = IDLE_CLOSE_MILLIS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMillis: Int = TIMEOUT_MILLIS,
    /**
     * Hur appen presenterar sig för servern, eller null. Skickas med CLNT efter inloggningen,
     * men bara om servern annonserar stöd för det. FTP har ingen User-Agent.
     */
    private val clientIdentity: String? = null,
    private val log: (String) -> Unit = {},
) : ScanSource {
    private val lock = Mutex()
    private var client: FTPClient? = null
    private var connectedTo: String? = null
    private var closeJob: Job? = null

    /** Antal anslutningar som har öppnats sedan start. För tester och mätning. */
    @Volatile
    var connectionsOpened = 0
        private set

    /** Sant medan en anslutning är öppen. */
    val connected: Boolean get() = client != null

    override suspend fun fetch(url: String, target: File, onProgress: (bytes: Long, total: Long?) -> Unit): Long =
        lock.withLock {
            closeJob?.cancel()
            try {
                withContext(dispatcher) { transfer(url, target, onProgress) }
            } finally {
                closeLater()
            }
        }

    private suspend fun transfer(url: String, target: File, onProgress: (Long, Long?) -> Unit): Long {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            throw ScanFetchException(ScanFailure.SERVER, "Adressen till skanningen är ogiltig.")
        }
        if (uri.scheme != "ftp" || uri.host.isNullOrBlank() || uri.rawPath.isNullOrBlank()) {
            throw ScanFetchException(ScanFailure.SERVER, "Adressen till skanningen är inte en FTP-adress.")
        }
        val path = URLDecoder.decode(uri.rawPath.replace("+", "%2B"), "UTF-8")
        val port = if (uri.port > 0) uri.port else FTP.DEFAULT_PORT
        // En återanvänd anslutning kan ha stängts av servern. Då görs ett nytt försök med en ny.
        var reused = client != null
        while (true) {
            try {
                return retrieve(connect(uri.host, port), path, target, onProgress)
            } catch (e: CancellationException) {
                // En avbruten överföring lämnar anslutningen i ett okänt läge.
                disconnect("avbruten hämtning")
                throw e
            } catch (e: ScanFetchException) {
                if (e.failure != ScanFailure.NOT_FOUND) disconnect("fel")
                throw e
            } catch (e: IOException) {
                disconnect("avbrott")
                if (!reused) {
                    throw ScanFetchException(ScanFailure.NETWORK, "Det gick inte att nå arkivet: ${e.message ?: e.javaClass.simpleName}", e)
                }
                reused = false
            }
        }
    }

    private fun connect(host: String, port: Int): FTPClient {
        val key = "$host:$port"
        client?.let { existing ->
            if (connectedTo == key && existing.isConnected) return existing
            disconnect("annan server")
        }
        val created = FTPClient()
        created.connectTimeout = timeoutMillis
        created.defaultTimeout = timeoutMillis
        created.setDataTimeout(java.time.Duration.ofMillis(timeoutMillis.toLong()))
        created.bufferSize = BUFFER_BYTES
        try {
            created.connect(host, port)
            if (!FTPReply.isPositiveCompletion(created.replyCode)) {
                throw ScanFetchException(ScanFailure.SERVER, "Arkivet tog inte emot anslutningen (kod ${created.replyCode}).")
            }
            created.soTimeout = timeoutMillis
            if (!created.login(account.user, account.password)) {
                throw ScanFetchException(ScanFailure.SERVER, "Arkivet godtog inte inloggningen (kod ${created.replyCode}).")
            }
            identify(created)
            created.enterLocalPassiveMode()
            if (!created.setFileType(FTP.BINARY_FILE_TYPE)) {
                throw ScanFetchException(ScanFailure.SERVER, "Arkivet stöder inte binär överföring (kod ${created.replyCode}).")
            }
        } catch (e: Exception) {
            try {
                created.disconnect()
            } catch (ignored: IOException) {
            }
            throw e
        }
        client = created
        connectedTo = key
        connectionsOpened++
        log("FTP ansluten till $key (anslutning $connectionsOpened)")
        return created
    }

    /**
     * Talar om för servern vilken klient det är (CLNT), om servern säger sig stödja det i FEAT.
     * Det är en artighet och får aldrig påverka hämtningen: saknas FEAT eller CLNT, eller nekar
     * servern kommandot, fortsätter anslutningen som om ingenting hade hänt. Inga omförsök.
     */
    private fun identify(ftp: FTPClient) {
        val identity = clientIdentity ?: return
        try {
            if (!ftp.hasFeature("CLNT")) {
                log("FTP-servern annonserar inte CLNT; klienten presenteras inte")
                return
            }
            val reply = ftp.sendCommand("CLNT", identity)
            log(
                if (FTPReply.isPositiveCompletion(reply)) "FTP-servern stöder CLNT; klienten presenterad som $identity"
                else "FTP-servern annonserar CLNT men svarade $reply; fortsätter utan",
            )
        } catch (e: Exception) {
            // Är anslutningen bruten märks det i nästa kommando, som hanterar det som vanligt.
            log("FTP: klienten kunde inte presenteras (${e.javaClass.simpleName}); fortsätter utan")
        }
    }

    private suspend fun retrieve(ftp: FTPClient, path: String, target: File, onProgress: (Long, Long?) -> Unit): Long {
        // Filens storlek enligt servern (SIZE). Utan den går det inte att veta att hela filen kom fram.
        val total = try {
            ftp.getSize(path)?.trim()?.toLongOrNull()
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (total == null) {
            throw when (ftp.replyCode) {
                FTPReply.FILE_UNAVAILABLE -> ScanFetchException(ScanFailure.NOT_FOUND, "Skanningen finns inte i arkivet.")
                else -> ScanFetchException(ScanFailure.SERVER, "Arkivet uppger inte filens storlek (kod ${ftp.replyCode}).")
            }
        }
        var offset = if (target.isFile) target.length() else 0L
        if (offset > total) {
            target.delete()
            offset = 0
        }
        if (offset == total && total > 0) {
            onProgress(offset, total)
            return total
        }
        ftp.restartOffset = offset
        val input = ftp.retrieveFileStream(path) ?: throw when (ftp.replyCode) {
            FTPReply.FILE_UNAVAILABLE -> ScanFetchException(ScanFailure.NOT_FOUND, "Skanningen finns inte i arkivet.")
            else -> ScanFetchException(ScanFailure.SERVER, "Arkivet svarade med ett fel (kod ${ftp.replyCode}).")
        }
        onProgress(offset, total)
        var received = offset
        input.use { stream ->
            FileOutputStream(target, offset > 0).use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                var reported = received
                while (true) {
                    coroutineContext.ensureActive()
                    val read = stream.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    received += read
                    if (received - reported >= PROGRESS_STEP_BYTES) {
                        reported = received
                        onProgress(received, total)
                    }
                }
            }
        }
        if (!ftp.completePendingCommand()) {
            throw ScanFetchException(ScanFailure.SERVER, "Överföringen avslutades med ett fel (kod ${ftp.replyCode}).")
        }
        // Servern kan säga att allt gick bra fast överföringen tog slut i förtid. Storleken avgör.
        if (received != total || target.length() != total) {
            // För många byte går inte att fortsätta på. För få ligger kvar och fortsätts nästa gång.
            if (target.length() > total) target.delete()
            throw ScanFetchException(ScanFailure.NETWORK, "Överföringen bröts: $received av $total byte kom fram.")
        }
        onProgress(received, total)
        return total
    }

    private fun disconnect(reason: String) {
        val existing = client ?: return
        client = null
        connectedTo = null
        try {
            if (existing.isConnected) existing.disconnect()
        } catch (ignored: IOException) {
        }
        log("FTP stängd ($reason)")
    }

    private fun closeLater() {
        if (client == null) return
        closeJob = scope.launch {
            delay(idleCloseMillis)
            lock.withLock {
                withContext(dispatcher + NonCancellable) {
                    val existing = client
                    if (existing != null) {
                        try {
                            if (existing.isConnected) existing.logout()
                        } catch (ignored: IOException) {
                        }
                        disconnect("${idleCloseMillis / 1000} s utan användning")
                    }
                }
            }
        }
    }

    override suspend fun close() {
        lock.withLock {
            closeJob?.cancel()
            withContext(dispatcher + NonCancellable) { disconnect("stängd av appen") }
        }
    }

    companion object {
        const val IDLE_CLOSE_MILLIS = 30_000L
        const val TIMEOUT_MILLIS = 20_000
        private const val BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_STEP_BYTES = 64 * 1024L
    }
}
