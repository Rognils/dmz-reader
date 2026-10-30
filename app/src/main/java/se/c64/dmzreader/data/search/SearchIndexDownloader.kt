package se.c64.dmzreader.data.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import se.c64.dmzreader.data.network.LiveArchiveGuard
import se.c64.dmzreader.data.network.UserAgentInterceptor
import se.c64.dmzreader.data.network.watchCallCancellation
import se.c64.dmzreader.data.pdf.DownloadProgress
import se.c64.dmzreader.data.pdf.PdfDownloader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Utfallet av en hämtning av sökindexet. */
sealed interface IndexFileResult {
    /** Filen är hämtad, har rätt storlek och SHA-256 och ligger i [file]. */
    data class Ready(val file: File, val resumed: Boolean) : IndexFileResult

    /** Anslutningen bröts eller gick inte att upprätta. Den påbörjade filen ligger kvar. */
    data class NetworkError(val message: String) : IndexFileResult

    data class InsufficientSpace(val requiredBytes: Long, val availableBytes: Long) : IndexFileResult

    /** Hämtningen gick inte att slutföra. Den påbörjade filen är borttagen. */
    data class Failed(val message: String) : IndexFileResult
}

const val CHECKSUM_MESSAGE =
    "Den hämtade filen stämmer inte med uppgifterna i GitHub-releasen. Den har tagits bort, och ingenting har importerats."

/**
 * Hämtar manifestet och sökindexet från GitHub-releasen (aldrig från arkivservern).
 *
 * Filen skrivs till [directory]/<fileName>.part. Varje start och varje återupptagning utgår
 * från GitHub-adressen och följer omdirigeringen till GitHubs filserver på nytt: den
 * signerade adressen dit slutar gälla efter en stund och sparas aldrig.
 *
 * Återupptagning med Range och If-Range:
 * - 206 med rätt Content-Range: fortsätt.
 * - 200: den påbörjade filen kastas, och hämtningen börjar om från byte 0.
 * - ändrad ETag eller storlek: börja om från 0.
 * - 416 eller ett annat oväntat svar: börja om från 0, högst en gång, sedan fel.
 *
 * När filen är hel kontrolleras storlek och SHA-256 mot manifestet. Stämmer de inte tas
 * filen bort.
 */
class SearchIndexDownloader(
    private val client: OkHttpClient,
    private val source: SearchIndexSource,
    val directory: File,
    private val appVersionCode: Int,
    private val availableBytes: (File) -> Long = { it.usableSpace },
    /** Det importen behöver utöver den hämtade filen: en kopia av den och en marginal. */
    private val importNeeds: (Long) -> Long = { size -> size + SearchIndexManager.IMPORT_MARGIN_BYTES },
) {
    suspend fun fetchManifest(): ManifestResult = withContext(Dispatchers.IO) {
        val call = client.newCall(Request.Builder().url(source.manifestUrl).build())
        try {
            cancelWith(call) {
                call.execute().use { response ->
                    if (response.code != 200) return@cancelWith ManifestResult.Unreachable(GITHUB_UNREACHABLE_MESSAGE)
                    val bytes = response.body.source().let { body ->
                        body.request(MAX_MANIFEST_BYTES + 1)
                        body.buffer.size.takeIf { it <= MAX_MANIFEST_BYTES } ?: return@cancelWith ManifestResult.Unsupported(
                            "Uppgifterna om sökindexet på GitHub är för stora för att vara ett manifest. Ingenting har hämtats.",
                        )
                        body.readByteArray()
                    }
                    parseSearchIndexManifest(bytes.toString(Charsets.UTF_8), appVersionCode)
                }
            }
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            ManifestResult.Unreachable(GITHUB_UNREACHABLE_MESSAGE)
        }
    }

    fun partFile(manifest: SearchIndexManifest) = File(directory, "${manifest.fileName}.part")
    private fun stateFile(manifest: SearchIndexManifest) = File(directory, "${manifest.fileName}.part.json")
    fun readyFile(manifest: SearchIndexManifest) = File(directory, manifest.fileName)

    /** Antal byte som redan finns för [manifest], om den påbörjade filen hör till samma release och fil. */
    fun bytesPresent(manifest: SearchIndexManifest): Long = if (partState(manifest) != null) partFile(manifest).length() else 0L

    /** Tar bort allt i katalogen: påbörjade och färdiga filer. */
    fun clear() {
        directory.listFiles().orEmpty().forEach { it.delete() }
    }

    suspend fun download(
        manifest: SearchIndexManifest,
        onVerifying: () -> Unit = {},
        onProgress: (DownloadProgress) -> Unit = {},
    ): IndexFileResult =
        withContext(Dispatchers.IO) {
            directory.mkdirs()
            // Ett systemstopp under importen ska inte tvinga fram en ny nätöverföring.
            val ready = readyFile(manifest)
            if (ready.isFile) {
                onVerifying()
                if (ready.length() == manifest.size && sha256(ready) == manifest.sha256) {
                    return@withContext IndexFileResult.Ready(ready, resumed = true)
                }
                ready.delete()
            }
            val part = partFile(manifest)
            val stateFile = stateFile(manifest)
            // Bara den här filen får ligga här. Rester från en annan release tas bort, men inte beställningen.
            directory.listFiles().orEmpty()
                .filter { it != part && it != stateFile && it.name != PENDING_NAME }
                .forEach { it.delete() }
            var restartedOnce = false

            while (true) {
                coroutineContext.ensureActive()
                val validator = partState(manifest)
                if (validator == null || part.length() > manifest.size) discard(manifest)
                val have = part.length().takeIf { part.isFile } ?: 0L

                if (have == manifest.size) {
                    onVerifying()
                    return@withContext verify(manifest, resumed = true)
                }

                val required = (manifest.size - have) + importNeeds(manifest.size)
                val available = availableBytes(directory)
                if (available < required) return@withContext IndexFileResult.InsufficientSpace(required, available)

                val request = Request.Builder().url(source.fileUrl(manifest)).apply {
                    if (have > 0 && validator != null) {
                        header("Range", "bytes=$have-")
                        header("If-Range", validator)
                    }
                }.build()
                val call = client.newCall(request)
                val outcome: Step = try {
                    cancelWith(call) {
                        call.execute().use { response -> transfer(response, manifest, have, validator, onProgress) }
                    }
                } catch (e: IOException) {
                    coroutineContext.ensureActive()
                    return@withContext IndexFileResult.NetworkError(e.message ?: e.javaClass.simpleName)
                }
                when (outcome) {
                    is Step.Complete -> {
                        onVerifying()
                        return@withContext verify(manifest, resumed = outcome.resumed)
                    }
                    Step.Restart -> discard(manifest)
                    is Step.Unexpected -> {
                        discard(manifest)
                        if (restartedOnce) return@withContext IndexFileResult.Failed(outcome.message)
                        restartedOnce = true
                    }
                    is Step.Incomplete -> return@withContext IndexFileResult.NetworkError(outcome.message)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("oåtkomligt")
        }

    private sealed interface Step {
        data class Complete(val resumed: Boolean) : Step

        /** Filen på servern har ändrats. Börja om från 0. */
        data object Restart : Step

        /** Ett svar som inte går att fortsätta på. Börja om från 0 en gång. */
        data class Unexpected(val message: String) : Step

        /** Överföringen slutade i förtid. Den påbörjade filen ligger kvar. */
        data class Incomplete(val message: String) : Step
    }

    private suspend fun transfer(
        response: Response,
        manifest: SearchIndexManifest,
        have: Long,
        validator: String?,
        onProgress: (DownloadProgress) -> Unit,
    ): Step {
        val etag = response.header("ETag") ?: response.header("Last-Modified")
        val append: Boolean
        when (response.code) {
            206 -> {
                if (have == 0L) return Step.Unexpected(unexpectedMessage(206))
                val range = PdfDownloader.parseContentRange(response.header("Content-Range"))
                    ?: return Step.Unexpected(unexpectedMessage(206))
                // Ändrad storlek eller ändrad fil: börja om från 0.
                if (range.second != manifest.size || (validator != null && etag != null && etag != validator)) return Step.Restart
                if (range.first != have) return Step.Unexpected(unexpectedMessage(206))
                append = true
            }

            200 -> {
                // Hela filen från början: den påbörjade delen gäller inte längre.
                append = false
            }

            else -> return Step.Unexpected(unexpectedMessage(response.code))
        }
        val start = if (append) have else 0L
        // Valideraren sparas innan något skrivs, så att ett avbrott går att återuppta.
        writePartState(manifest, etag)
        val written = copy(response, partFile(manifest), append, start, manifest.size, onProgress)
        return when {
            written == manifest.size -> Step.Complete(resumed = append)
            written > manifest.size -> Step.Unexpected("Filen på GitHub är större än uppgifterna i releasen anger. Ingenting har importerats.")
            else -> Step.Incomplete("Överföringen slutade efter $written av ${manifest.size} byte.")
        }
    }

    private fun unexpectedMessage(code: Int) =
        "$GITHUB_UNREACHABLE_MESSAGE (Svaret var HTTP $code.)"

    /** Kontrollerar storlek och SHA-256. Stämmer de inte tas filen bort. */
    private fun verify(manifest: SearchIndexManifest, resumed: Boolean): IndexFileResult {
        val part = partFile(manifest)
        if (part.length() != manifest.size || sha256(part) != manifest.sha256) {
            discard(manifest)
            return IndexFileResult.Failed(CHECKSUM_MESSAGE)
        }
        val ready = readyFile(manifest)
        ready.delete()
        if (!part.renameTo(ready)) {
            discard(manifest)
            return IndexFileResult.Failed("Den hämtade filen kunde inte läggas på plats. Ingenting har importerats.")
        }
        stateFile(manifest).delete()
        return IndexFileResult.Ready(ready, resumed)
    }

    private suspend fun copy(
        response: Response,
        part: File,
        append: Boolean,
        startAt: Long,
        total: Long,
        onProgress: (DownloadProgress) -> Unit,
    ): Long {
        var written = startAt
        onProgress(DownloadProgress(written, total))
        FileOutputStream(part, append).use { output ->
            val input = response.body.byteStream()
            val buffer = ByteArray(BUFFER_BYTES)
            var sinceReport = 0L
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                written += read
                sinceReport += read
                if (sinceReport >= REPORT_EVERY_BYTES) {
                    sinceReport = 0
                    onProgress(DownloadProgress(written, total))
                }
                if (written > total) break
            }
            output.fd.sync()
        }
        onProgress(DownloadProgress(written, total))
        return written
    }

    /** Valideraren (ETag) för den påbörjade filen, om den hör till samma release, fil, storlek och kontrollsumma. */
    private fun partState(manifest: SearchIndexManifest): String? {
        val file = stateFile(manifest)
        if (!file.isFile || !partFile(manifest).isFile) return null
        return try {
            val state = Json.parseToJsonElement(file.readText()).jsonObject
            fun field(name: String) = state[name]?.jsonPrimitive?.content
            val same = field("releaseTag") == manifest.releaseTag && field("fileName") == manifest.fileName &&
                field("size") == manifest.size.toString() && field("sha256") == manifest.sha256
            field("etag")?.takeIf { same && it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    private fun writePartState(manifest: SearchIndexManifest, etag: String?) {
        val file = stateFile(manifest)
        if (etag == null) {
            // Utan validerare går en återupptagning inte att lita på. Nästa försök börjar om.
            file.delete()
            return
        }
        file.writeText(
            buildJsonObject {
                put("releaseTag", manifest.releaseTag)
                put("fileName", manifest.fileName)
                put("size", manifest.size.toString())
                put("sha256", manifest.sha256)
                put("etag", etag)
            }.toString(),
        )
    }

    private fun discard(manifest: SearchIndexManifest) {
        partFile(manifest).delete()
        stateFile(manifest).delete()
    }

    private suspend fun <T> cancelWith(call: Call, block: suspend () -> T): T = coroutineScope {
        // Ett avbrott ska bryta även en förfrågan som står och väntar på nätet.
        val cancellation = watchCallCancellation(call)
        try {
            block()
        } finally {
            cancellation.cancel()
        }
    }

    companion object {
        private const val BUFFER_BYTES = 64 * 1024
        private const val REPORT_EVERY_BYTES = 256 * 1024L
        private const val MAX_MANIFEST_BYTES = 64 * 1024L
        const val LOG_TAG = "DmzSearchNet"

        /** Beställningen, som [SearchIndexDownloads] sparar i samma katalog. */
        const val PENDING_NAME = "pending.json"

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /**
         * Klienten för GitHub: samma User-Agent som PDF:erna, omdirigeringar följs, och varje
         * steg (även omdirigeringen till GitHubs filserver) går genom [GitHubGuardInterceptor].
         */
        fun createHttpClient(log: ((String) -> Unit)? = null): OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(UserAgentInterceptor())
            .addNetworkInterceptor(GitHubGuardInterceptor(log))
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Loggar varje förfrågan i debug-bygget (taggen DmzSearchNet, som tools/device_test.sh räknar) och
 * stoppar den i testerna om den går till GitHub eller arkivet i stället för den lokala testservern.
 */
class GitHubGuardInterceptor(private val log: ((String) -> Unit)? = null) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val what = "${request.method} ${request.url.host}${request.url.encodedPath}"
        LiveArchiveGuard.check(request.url.host, what)
        LiveArchiveGuard.checkGitHub(request.url.host, what)
        val response = chain.proceed(request)
        log?.invoke("$what → ${response.code}")
        return response
    }
}
