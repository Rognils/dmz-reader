package se.c64.dmzreader.data.hq

import java.io.File

/**
 * Cachen för hämtade skanningar: cacheDir/hq/{issueId}/{fil} (planens FAS 11).
 *
 * Den har ett tak. När det överskrids tas de filer bort som användes för längst
 * sedan. Tiden för senaste användning är filens ändringstid, som sätts varje
 * gång filen lämnas ut. Systemet får också tömma katalogen när utrymmet tryter.
 */
class HqCache(
    val directory: File,
    private val maxBytes: Long = MAX_BYTES,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private fun issueDirectory(issueId: String) = File(directory, issueId)

    /** Den färdiga filens plats, vare sig den finns eller inte. */
    fun file(issueId: String, fileName: String): File = File(issueDirectory(issueId), fileName)

    /** Filen som en pågående hämtning skriver till. */
    fun partFile(issueId: String, fileName: String): File = File(issueDirectory(issueId), "$fileName$PART_SUFFIX")

    /** Den cachade filen, eller null. Att hämta den räknas som användning. */
    @Synchronized
    fun cached(issueId: String, fileName: String): File? {
        val file = file(issueId, fileName)
        if (!file.isFile || file.length() == 0L) return null
        file.setLastModified(now())
        return file
    }

    /** Gör en färdig hämtning till en cachad fil, och håller cachen under taket. */
    @Synchronized
    fun commit(issueId: String, fileName: String): File {
        val part = partFile(issueId, fileName)
        val file = file(issueId, fileName)
        file.delete()
        if (!part.renameTo(file)) throw java.io.IOException("${file.name} gick inte att lägga i cachen")
        file.setLastModified(now())
        trim(keep = file)
        return file
    }

    private fun allFiles(): List<File> = directory.walkTopDown().filter { it.isFile }.toList()

    /** Tar bort de äldst använda filerna tills cachen ryms under taket. [keep] tas aldrig bort. */
    @Synchronized
    fun trim(keep: File? = null) {
        val files = allFiles()
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        // Påbörjade hämtningar går först, sedan de filer som användes för längst sedan.
        val candidates = files.filter { it != keep }
            .sortedWith(compareBy<File>({ !it.name.endsWith(PART_SUFFIX) }, { it.lastModified() }, { it.path }))
        for (file in candidates) {
            if (total <= maxBytes) break
            val size = file.length()
            if (file.delete()) total -= size
        }
        removeEmptyDirectories()
    }

    private fun removeEmptyDirectories() {
        directory.listFiles()?.filter { it.isDirectory && it.list().isNullOrEmpty() }?.forEach { it.delete() }
    }

    @Synchronized
    fun sizeBytes(): Long = allFiles().sumOf { it.length() }

    /** Tömmer cachen. Returnerar antalet frigjorda byte. */
    @Synchronized
    fun clear(): Long {
        val size = sizeBytes()
        directory.deleteRecursively()
        return size
    }

    companion object {
        const val MAX_BYTES = 500_000_000L
        const val PART_SUFFIX = ".part"
    }
}
