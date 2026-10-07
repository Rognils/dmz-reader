package se.c64.dmzreader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import se.c64.dmzreader.data.inventory.InventoryException
import se.c64.dmzreader.data.settings.InventorySource
import java.io.File

/**
 * Import av arkivindex och återgång till det inbyggda, på en riktig enhet med
 * appens egen databas, DataStore och privata lagring (FAS 7).
 *
 * Testet körs bara när appen står på det inbyggda indexet, och lämnar den där.
 * Användardata rörs inte: testet kontrollerar att den är oförändrad.
 */
@RunWith(AndroidJUnit4::class)
class ArchiveIndexDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = appContainer(context)
    private val database get() = container.database
    private val removed = "datormagazin-1986-04"
    private val added = "datormagazin-1996-01"
    private var imported = false

    /** Det inbyggda indexet med ett nummer borttaget, ett tillagt och ny version. */
    private fun modifiedInventory(): String {
        val bundled = Json.parseToJsonElement(
            context.assets.open("archive_inventory.json").bufferedReader().use { it.readText() },
        ).jsonObject
        val issues = bundled.getValue("issues").jsonArray
        val template = issues.first { it.jsonObject.getValue("issueId").jsonPrimitive.content == "datormagazin-1986-01" }.jsonObject
        val newIssue = JsonObject(
            template + mapOf(
                "issueId" to JsonPrimitive(added),
                "year" to JsonPrimitive(1996),
                "sortKey" to JsonPrimitive("1996-01"),
                "sourceKey" to JsonPrimitive("199601"),
                "coverAsset" to JsonPrimitive("covers/$added.webp"),
            ),
        )
        val kept = issues.filterNot { it.jsonObject.getValue("issueId").jsonPrimitive.content == removed }
        return JsonObject(
            bundled + mapOf("datasetVersion" to JsonPrimitive("2026-10-08-950"), "issues" to JsonArray(kept + newIssue)),
        ).toString()
    }

    private suspend fun userData() = listOf(
        database.issueUserStateDao().getAll(), database.bookmarkDao().getAll(),
        database.readingSessionDao().getAll(), database.issueLocalStateDao().getAll(),
    )

    @After
    fun backToBundled() = runBlocking<Unit> {
        if (imported) container.inventoryManager.revertToBundled()
    }

    @Test
    fun importThenRevert() = runBlocking<Unit> {
        val manager = container.inventoryManager
        manager.loadAtStartup()
        val bundledVersion = container.activeInventory.get()!!.datasetVersion
        assumeTrue("appen står inte på det inbyggda indexet", container.activeInventory.get()!!.source == InventorySource.BUNDLED)
        assertEquals(185, database.issueDao().count())
        assertNotNull(database.issueDao().get(removed))
        val before = userData()
        val activeFile = File(context.filesDir, "data/inventory.json")
        assertFalse(activeFile.exists())

        // Import: förbered, granska skillnaden, genomför.
        val staged = manager.stage { modifiedInventory().byteInputStream() }
        assertEquals(listOf(added), staged.diff.added)
        assertEquals(listOf(removed), staged.diff.removed)
        assertEquals(bundledVersion, staged.activeDatasetVersion)
        assertNotNull(database.issueDao().get(removed))
        imported = true
        manager.commit(staged)

        assertEquals(185, database.issueDao().count())
        assertNull(database.issueDao().get(removed))
        assertEquals(1996, database.issueDao().get(added)!!.year)
        assertTrue(activeFile.isFile)
        val active = container.activeInventory.get()!!
        assertEquals("2026-10-08-950", active.datasetVersion)
        assertEquals(InventorySource.IMPORTED, active.source)
        assertNotNull(active.importedAt)
        assertEquals(before, userData())
        // Miniatyren i det nya numrets coverAsset följer inte med appen.
        assertFalse("covers/$added.webp" in container.bundledCovers)
        assertTrue("covers/datormagazin-1986-01.webp" in container.bundledCovers)

        // En trasig fil avvisas och ändrar ingenting.
        try {
            manager.stage { "{\"schemaVersion\": 2}".byteInputStream() }
            throw AssertionError("skulle ha avvisats")
        } catch (e: InventoryException) {
            assertTrue(e.message!!.contains("schemaVersion 2"))
        }
        assertEquals("2026-10-08-950", container.activeInventory.get()!!.datasetVersion)
        assertNull(database.issueDao().get(removed))

        // Det importerade indexet gäller också vid nästa start.
        manager.loadAtStartup()
        assertNull(database.issueDao().get(removed))

        // Återgå: det inbyggda indexet gäller igen, och numret är tillbaka.
        manager.revertToBundled()
        imported = false
        assertNotNull(database.issueDao().get(removed))
        assertNull(database.issueDao().get(added))
        assertEquals(185, database.issueDao().count())
        assertFalse(activeFile.exists())
        assertEquals(bundledVersion, container.activeInventory.get()!!.datasetVersion)
        assertEquals(InventorySource.BUNDLED, container.activeInventory.get()!!.source)
        assertEquals(before, userData())
    }
}
