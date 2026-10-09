package se.c64.dmzreader.data.hq

import java.io.File

/** Varför en skanning inte gick att hämta. */
enum class ScanFailure {
    /** Arkivet gick inte att nå: inget nät, ingen anslutning eller ett avbrott. */
    NETWORK,

    /** Filen finns inte i arkivet. */
    NOT_FOUND,

    /** Arkivet svarade med ett fel, till exempel vid inloggningen. */
    SERVER,
}

class ScanFetchException(val failure: ScanFailure, message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Källan för originalskanningar (planens FAS 11, ADR 0009). Appen hämtar en
 * skanning i taget genom det här gränssnittet. I appen är det FTP ([FtpScanSource]),
 * i testerna en falsk källa.
 */
interface ScanSource {
    /**
     * Hämtar filen på [url] till [target]. Finns [target] redan fortsätter hämtningen
     * där den slutade. [onProgress] får antalet hämtade byte och filens storlek, om den är känd.
     *
     * Returnerar filens storlek enligt servern. När anropet återvänder har [target] exakt
     * så många byte. Kastar [ScanFetchException] vid fel, även när överföringen tog slut
     * för tidigt utan att servern sade ifrån. Avbryts anroparen ligger det hämtade kvar i [target].
     */
    suspend fun fetch(url: String, target: File, onProgress: (bytes: Long, total: Long?) -> Unit): Long

    /** Stänger anslutningen, om någon är öppen. */
    suspend fun close() {}
}
