package se.c64.dmzreader.data.search

import android.content.Context
import java.io.File
import java.io.FileNotFoundException

/**
 * Lägger testindexet från assets på plats i filesDir/data/, på samma sätt som
 * ett riktigt search.db ska importeras senare (planens D5): kopiera till en
 * tillfällig fil och byt sedan namn.
 */
object FtsTestDatabase {
    const val ASSET_NAME = "fts_test.db"

    /** Returnerar filen, eller null om tillgången inte finns i den här byggvarianten. */
    fun install(context: Context): File? {
        val directory = File(context.filesDir, "data").apply { mkdirs() }
        val target = File(directory, ASSET_NAME)
        val temporary = File(directory, "$ASSET_NAME.tmp")
        try {
            context.assets.open(ASSET_NAME).use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: FileNotFoundException) {
            temporary.delete()
            return null
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IllegalStateException("Kunde inte lägga testindexet på plats i ${directory.path}")
        }
        return target
    }
}
