package se.c64.dmzreader.data.pdf

import java.io.File

/**
 * En katalog med PDF:er, en per nummer. Används både för läs-cachen och för
 * permanenta nedladdningar (ADR 0013 och 0017).
 *
 * En färdig fil heter {issueId}.pdf. En pågående nedladdning heter
 * {issueId}.pdf.part och har sin validerare (ETag eller Last-Modified) i
 * {issueId}.pdf.part.validator. En .pdf-fil är alltid komplett.
 */
open class PdfFiles(val directory: File) {
    fun file(issueId: String) = File(directory, "$issueId.pdf")
    fun partFile(issueId: String) = File(directory, "$issueId.pdf.part")
    fun validatorFile(issueId: String) = File(directory, "$issueId.pdf.part.validator")

    fun totalBytes(): Long = directory.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }

    /** Tar bort allt som hör till ett nummer. */
    fun remove(issueId: String) {
        file(issueId).delete()
        removePart(issueId)
    }

    /** Tar bort en påbörjad nedladdning. Den färdiga filen rörs inte. */
    fun removePart(issueId: String) {
        partFile(issueId).delete()
        validatorFile(issueId).delete()
    }

    /** Nummer som har en påbörjad nedladdning (.part eller validerare) i katalogen. */
    fun issueIdsWithParts(): Set<String> = directory.listFiles().orEmpty()
        .filter { it.isFile && !it.name.endsWith(".pdf") }
        .mapNotNull { issueIdOf(it) }
        .toSet()

    /** Tar bort alla filer i katalogen. */
    fun clear() {
        directory.listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }
    }

    protected fun issueIdOf(file: File): String? = SUFFIXES.firstOrNull { file.name.endsWith(it) }
        ?.let { file.name.removeSuffix(it) }

    private companion object {
        // Längsta suffixet först, så att .pdf.part inte tolkas som .pdf.
        val SUFFIXES = listOf(".pdf.part.validator", ".pdf.part", ".pdf")
    }
}

/** Läs-cachen i cacheDir/pdf/, med ett tak där de minst nyligen öppnade filerna rensas först (ADR 0017). */
class PdfCache(
    directory: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val now: () -> Long = System::currentTimeMillis,
) : PdfFiles(directory) {
    fun isCached(issueId: String): Boolean = file(issueId).isFile

    /** Markerar filen som nyss öppnad. Ordningen styr vilka filer som rensas först. */
    fun touch(issueId: String) {
        file(issueId).takeIf { it.isFile }?.setLastModified(now())
    }

    /**
     * Håller cachen under taket genom att ta bort de minst nyligen öppnade
     * filerna först. [keepIssueId] (numret som är öppet eller laddas ned) tas
     * aldrig bort. [reserveBytes] är utrymme som en kommande nedladdning behöver.
     *
     * Returnerar de nummer vars färdiga PDF togs bort.
     */
    fun trim(keepIssueId: String? = null, reserveBytes: Long = 0): List<String> {
        val entries = directory.listFiles().orEmpty()
            .filter { it.isFile }
            .groupBy { issueIdOf(it) }
            .filterKeys { it != null && it != keepIssueId }
            .map { (issueId, files) -> Entry(issueId!!, files, files.maxOf { it.lastModified() }) }
            .sortedWith(compareBy({ it.lastUsed }, { it.issueId }))

        var total = totalBytes() + reserveBytes
        val removed = ArrayList<String>()
        for (entry in entries) {
            if (total <= maxBytes) break
            val hadPdf = entry.files.any { it.name.endsWith(".pdf") }
            total -= entry.files.sumOf { it.length() }
            entry.files.forEach { it.delete() }
            if (hadPdf) removed += entry.issueId
        }
        return removed
    }

    private class Entry(val issueId: String, val files: List<File>, val lastUsed: Long)

    companion object {
        const val DEFAULT_MAX_BYTES = 1_500L * 1024 * 1024
    }
}
