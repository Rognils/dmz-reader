package se.c64.dmzreader.data.pdf

import java.io.File

/**
 * Läs-cachen för PDF:er i cacheDir/pdf/ (ADR 0017).
 *
 * En färdig fil heter {issueId}.pdf. En pågående nedladdning heter
 * {issueId}.pdf.part och har sin validerare (ETag eller Last-Modified) i
 * {issueId}.pdf.part.validator. En .pdf-fil är alltid komplett.
 */
class PdfCache(
    val directory: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun file(issueId: String) = File(directory, "$issueId.pdf")
    fun partFile(issueId: String) = File(directory, "$issueId.pdf.part")
    fun validatorFile(issueId: String) = File(directory, "$issueId.pdf.part.validator")

    fun isCached(issueId: String): Boolean = file(issueId).isFile

    /** Markerar filen som nyss öppnad. Ordningen styr vilka filer som rensas först. */
    fun touch(issueId: String) {
        file(issueId).takeIf { it.isFile }?.setLastModified(now())
    }

    fun totalBytes(): Long = directory.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }

    /** Tar bort allt som hör till ett nummer. */
    fun remove(issueId: String) {
        file(issueId).delete()
        partFile(issueId).delete()
        validatorFile(issueId).delete()
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

    private fun issueIdOf(file: File): String? = SUFFIXES.firstOrNull { file.name.endsWith(it) }
        ?.let { file.name.removeSuffix(it) }

    companion object {
        const val DEFAULT_MAX_BYTES = 1_500L * 1024 * 1024

        // Längsta suffixet först, så att .pdf.part inte tolkas som .pdf.
        private val SUFFIXES = listOf(".pdf.part.validator", ".pdf.part", ".pdf")
    }
}
