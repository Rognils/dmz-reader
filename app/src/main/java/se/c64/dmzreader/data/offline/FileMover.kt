package se.c64.dmzreader.data.offline

import java.io.File
import java.io.IOException

/**
 * Flyttar en färdig PDF från läs-cachen till den permanenta lagringen, utan
 * nätverkstrafik (ADR 0013).
 *
 * Först prövas renameTo, som är omedelbar när katalogerna ligger på samma
 * filsystem. Går det inte kopieras filen till [part] i målkatalogen, storleken
 * kontrolleras, kopian döps om till [target] och källfilen tas bort. [target]
 * finns därför aldrig som en halvfärdig fil.
 *
 * Ger falskt om flytten inte gick. Då ligger källfilen kvar orörd.
 */
fun movePdf(
    source: File,
    target: File,
    part: File,
    rename: (File, File) -> Boolean = File::renameTo,
): Boolean {
    if (!source.isFile) return false
    val size = source.length()
    target.parentFile?.mkdirs()
    if (rename(source, target)) return true
    return try {
        source.inputStream().use { input ->
            part.outputStream().use { output ->
                input.copyTo(output, 256 * 1024)
                output.fd.sync()
            }
        }
        if (part.length() != size || !rename(part, target)) {
            part.delete()
            false
        } else {
            source.delete()
            true
        }
    } catch (e: IOException) {
        part.delete()
        false
    }
}
