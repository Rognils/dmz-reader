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

    /** Filen som minns hur stor skanningen ska vara, enligt servern när den hämtades. */
    private fun sizeFile(issueId: String, fileName: String): File = File(issueDirectory(issueId), "$fileName$SIZE_SUFFIX")

    /**
     * Den cachade filen, eller null. Att hämta den räknas som användning.
     *
     * En fil lämnas bara ut om den har exakt den storlek servern uppgav när den hämtades.
     * En fil som är kortare, längre eller saknar uppgiften tas bort, så att sidan hämtas om.
     */
    @Synchronized
    fun cached(issueId: String, fileName: String): File? {
        val file = file(issueId, fileName)
        if (!file.isFile) return null
        val expected = try {
            sizeFile(issueId, fileName).readText().trim().toLongOrNull()
        } catch (e: java.io.IOException) {
            null
        }
        if (expected == null || expected <= 0L || file.length() != expected) {
            file.delete()
            sizeFile(issueId, fileName).delete()
            return null
        }
        file.setLastModified(now())
        return file
    }

    /**
     * Gör en färdig hämtning till en cachad fil, och håller cachen under taket.
     * [expectedBytes] är filens storlek enligt servern. Stämmer den inte med det hämtade
     * flyttas ingenting in i cachen.
     */
    @Synchronized
    fun commit(issueId: String, fileName: String, expectedBytes: Long): File {
        val part = partFile(issueId, fileName)
        val file = file(issueId, fileName)
        if (expectedBytes <= 0L || part.length() != expectedBytes) {
            throw java.io.IOException("${file.name} har ${part.length()} byte, men $expectedBytes väntades")
        }
        // Storleken skrivs först. Dör appen mellan stegen finns uppgiften men ingen fil, och sidan hämtas om.
        val size = sizeFile(issueId, fileName)
        val temporary = File(size.path + ".tmp")
        temporary.writeText(expectedBytes.toString())
        if (!temporary.renameTo(size)) throw java.io.IOException("${size.name} gick inte att skriva")
        file.delete()
        if (!part.renameTo(file)) {
            size.delete()
            throw java.io.IOException("${file.name} gick inte att lägga i cachen")
        }
        file.setLastModified(now())
        trim(keep = file)
        return file
    }

    /** Skanningar och påbörjade hämtningar. Filerna som bara minns en storlek räknas inte. */
    private fun allFiles(): List<File> = directory.walkTopDown()
        .filter { it.isFile && !it.name.endsWith(SIZE_SUFFIX) && !it.name.endsWith("$SIZE_SUFFIX.tmp") }.toList()

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
            if (file.delete()) {
                total -= size
                File(file.path + SIZE_SUFFIX).delete()
            }
        }
        removeEmptyDirectories()
    }

    private fun removeEmptyDirectories() {
        for (issue in directory.listFiles().orEmpty().filter { it.isDirectory }) {
            val names = issue.list().orEmpty()
            // En kvarlämnad storleksuppgift utan sin fil gör ingen nytta.
            names.filter { it.endsWith(SIZE_SUFFIX) && it.removeSuffix(SIZE_SUFFIX) !in names }.forEach { File(issue, it).delete() }
            if (issue.list().isNullOrEmpty()) issue.delete()
        }
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
        const val SIZE_SUFFIX = ".size"
    }
}
