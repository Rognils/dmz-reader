package se.c64.dmzreader.data.inventory

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

sealed class InventoryException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class UnsupportedSchemaVersion(val found: Int) : InventoryException(
        "Arkivindexet har schemaVersion $found. Den här appversionen stöder bara " +
            "schemaVersion ${InventoryParser.SUPPORTED_SCHEMA_VERSION}."
    )

    class Malformed(detail: String, cause: Throwable? = null) :
        InventoryException("Arkivindexet går inte att läsa: $detail", cause)
}

object InventoryParser {
    const val SUPPORTED_SCHEMA_VERSION = 1

    private val headerJson = Json { ignoreUnknownKeys = true }

    // Okända fält tolereras, så att ett inventory med ett tillagt valfritt fält
    // inte gör appen oanvändbar. Saknade obligatoriska fält ger fel.
    private val inventoryJson = Json { ignoreUnknownKeys = true }

    /**
     * Läser ett inventory. schemaVersion kontrolleras först, så att en fil med
     * en annan struktur ger ett begripligt fel i stället för ett tolkningsfel.
     */
    @Throws(InventoryException::class)
    fun parse(text: String): InventoryDto {
        val header = try {
            headerJson.decodeFromString<InventoryHeaderDto>(text)
        } catch (e: SerializationException) {
            throw InventoryException.Malformed("schemaVersion saknas eller filen är inte giltig JSON", e)
        } catch (e: IllegalArgumentException) {
            throw InventoryException.Malformed("filen är inte giltig JSON", e)
        }
        if (header.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw InventoryException.UnsupportedSchemaVersion(header.schemaVersion)
        }

        val inventory = try {
            inventoryJson.decodeFromString<InventoryDto>(text)
        } catch (e: SerializationException) {
            throw InventoryException.Malformed(e.message?.lineSequence()?.firstOrNull() ?: "okänt fel", e)
        } catch (e: IllegalArgumentException) {
            throw InventoryException.Malformed(e.message ?: "okänt fel", e)
        }

        val duplicates = inventory.issues.groupingBy { it.issueId }.eachCount().filterValues { it > 1 }
        if (duplicates.isNotEmpty()) {
            throw InventoryException.Malformed("issueId är inte unikt: ${duplicates.keys.sorted().first()}")
        }
        return inventory
    }
}
