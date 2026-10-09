package se.c64.dmzreader.data.inventory

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

    private val datasetVersionPattern = Regex("""\d{4}-\d{2}-\d{2}-\d{3}""")
    private val issueIdPattern = Regex("""[a-z][a-z0-9]*-\d{4}-\d{2}(-\d{2})?[a-z]?""")
    private val sortKeyPattern = Regex("""\d{4}-\d{2}[a-z]?""")
    private val coverAssetPattern = Regex("""covers/[a-z0-9-]+\.webp""")
    private val scanFileNamePattern = Regex("""[A-Za-z0-9][A-Za-z0-9._-]*\.(jpg|jpeg|png)""")
    private val hqRotations = setOf(90, 180, 270)
    private val coverFlags = setOf("rotate_cw", "rotate_ccw", "rotate_180", "crop_left_half", "crop_right_half")

    /**
     * Läser och validerar ett inventory (planens D5). Reglerna är desamma som i
     * docs/inventory.schema.json och toolchainens kontroller, för de fält appen
     * använder. schemaVersion kontrolleras först, så att en fil med en annan
     * struktur ger ett begripligt fel i stället för ett tolkningsfel.
     */
    @Throws(InventoryException::class)
    fun parse(text: String): InventoryDto {
        val header = try {
            headerJson.decodeFromString<InventoryHeaderDto>(text)
        } catch (e: SerializationException) {
            throw InventoryException.Malformed(notJsonOrNoVersion(text), e)
        } catch (e: IllegalArgumentException) {
            throw InventoryException.Malformed(notJsonOrNoVersion(text), e)
        }
        if (header.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw InventoryException.UnsupportedSchemaVersion(header.schemaVersion)
        }

        checkTypes(text)
        val inventory = try {
            inventoryJson.decodeFromString<InventoryDto>(text)
        } catch (e: SerializationException) {
            throw InventoryException.Malformed(describe(e), e)
        } catch (e: IllegalArgumentException) {
            throw InventoryException.Malformed(describe(e), e)
        }
        validate(inventory)
        return inventory
    }

    /** En fil som inte går att tolka alls: avbruten, tom eller inte JSON. */
    private fun notJsonOrNoVersion(text: String): String {
        val trimmed = text.trim()
        return when {
            trimmed.isEmpty() -> "filen är tom"
            !trimmed.startsWith("{") -> "filen är inte ett arkivindex (den börjar inte som JSON)"
            !trimmed.endsWith("}") -> "filen är avbruten eller skadad (JSON-texten tar slut mitt i)"
            else -> "schemaVersion saknas eller filen är inte giltig JSON"
        }
    }

    /** Översätter tolkningsfelet till något som går att förstå utan att kunna koden. */
    private fun describe(error: Exception): String {
        val message = error.message.orEmpty()
        val missing = Regex("""Fields? (.+?) (?:is|are) required""").find(message)?.groupValues?.get(1)
        if (missing != null) {
            // "Field 'year' is required" eller "Fields [year, sortKey] are required".
            val names = missing.trim('[', ']', '\'').split(",").map { it.trim().trim('\'') }
            return "obligatoriska fält saknas: ${names.joinToString(", ")}"
        }
        val path = Regex("""at path:? (\$[^\s]*)""").find(message)?.groupValues?.get(1)
        return when {
            "EOF" in message || "Expected end of the object" in message || "Unexpected EOF" in message ->
                "filen är avbruten eller skadad (JSON-texten tar slut mitt i)"
            path != null -> "ett fält har fel typ eller ogiltigt värde vid $path"
            else -> "filen är inte giltig JSON"
        }
    }

    private val stringFields = listOf(
        "issueId", "publicationId", "publicationTitle", "issueLabel", "sortKey", "sourceKey", "relatedIssueId",
        "coverUrl", "coverAsset", "ocrPdfUrl", "ocrPdfEtag", "ocrPdfLastModified",
    )
    private val numberFields = listOf("year", "ocrPdfSize", "pageCount", "scanPageCount", "spreadOffset")
    private val listFields = listOf("coverFlags", "flags", "sourceNames")

    /**
     * Kontrollerar typerna för de fält appen använder. Tolkningen efteråt är
     * generös: den hade läst "1989" inom citattecken som ett tal. Här krävs att
     * tal är tal och text är text, som i schemat.
     */
    private fun checkTypes(text: String) {
        fun fail(detail: String): Nothing = throw InventoryException.Malformed(detail)
        val root = try {
            headerJson.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            return // Felet beskrivs av tolkningen som följer.
        } ?: return
        fun JsonElement?.isText() = this is JsonPrimitive && this !is JsonNull && isString
        fun JsonElement?.isNumber() = this is JsonPrimitive && this !is JsonNull && !isString && content.toLongOrNull() != null
        fun JsonElement?.absentOrNull() = this == null || this is JsonNull

        if (!root["schemaVersion"].isNumber()) fail("schemaVersion ska vara ett tal")
        root["datasetVersion"]?.let { if (!it.isText()) fail("datasetVersion ska vara text") }
        val issues = root["issues"] ?: return
        if (issues !is JsonArray) fail("issues ska vara en lista")
        issues.forEachIndexed { index, element ->
            val issue = element as? JsonObject ?: fail("post ${index + 1} i issues är inte ett objekt")
            val name = (issue["issueId"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "post ${index + 1}"
            for (field in stringFields) {
                val value = issue[field]
                if (!value.absentOrNull() && !value.isText()) fail("$name: $field har fel typ (ska vara text)")
            }
            for (field in numberFields) {
                val value = issue[field]
                if (!value.absentOrNull() && !value.isNumber()) fail("$name: $field har fel typ (ska vara ett heltal)")
            }
            for (field in listFields) {
                val value = issue[field]
                if (value.absentOrNull()) continue
                if (value !is JsonArray || value.any { !it.isText() }) fail("$name: $field har fel typ (ska vara en lista med text)")
            }
        }
    }

    private fun validate(inventory: InventoryDto) {
        fun fail(detail: String): Nothing = throw InventoryException.Malformed(detail)

        if (!datasetVersionPattern.matches(inventory.datasetVersion)) {
            fail("datasetVersion \"${inventory.datasetVersion}\" har fel format (ska vara ÅÅÅÅ-MM-DD-NNN)")
        }
        if (inventory.publications.isEmpty()) fail("publications är tom")
        val titles = inventory.publications.associate { it.publicationId to it.publicationTitle }
        if (titles.size != inventory.publications.size) fail("samma publicationId förekommer flera gånger i publications")

        val ids = HashSet<String>()
        for (issue in inventory.issues) {
            val id = issue.issueId
            if (!issueIdPattern.matches(id)) fail("issueId \"$id\" har fel format")
            if (!ids.add(id)) fail("issueId är inte unikt: $id")
            if (issue.publicationId !in titles) fail("$id: publicationId \"${issue.publicationId}\" saknas i publications")
            if (titles[issue.publicationId] != issue.publicationTitle) fail("$id: publicationTitle stämmer inte med publications")
            if (!id.startsWith(issue.publicationId + "-")) fail("$id: issueId börjar inte med publicationId")
            if (issue.year !in 1900..2100) fail("$id: år ${issue.year} är orimligt")
            if (issue.issueLabel.isBlank()) fail("$id: issueLabel är tom")
            if (!sortKeyPattern.matches(issue.sortKey)) fail("$id: sortKey \"${issue.sortKey}\" har fel format")
            if (!issue.sortKey.startsWith("${issue.year}-")) fail("$id: sortKey stämmer inte med året")
            for ((name, url) in listOf("coverUrl" to issue.coverUrl, "ocrPdfUrl" to issue.ocrPdfUrl)) {
                if (url != null && !(url.startsWith("https://") && url.none { it.isWhitespace() })) {
                    fail("$id: $name är inte en https-adress")
                }
            }
            if (issue.coverAsset != null && !coverAssetPattern.matches(issue.coverAsset)) {
                fail("$id: coverAsset \"${issue.coverAsset}\" har fel format")
            }
            // HQ-mappningen (FAS 11). Fel här hade kunnat visa fel skanning för en sida.
            issue.hqPageMap?.forEach { (page, fileName) ->
                if (page.toIntOrNull()?.let { it >= 1 } != true) fail("$id: hqPageMap har ett ogiltigt sidnummer: \"$page\"")
                if (fileName != null && !scanFileNamePattern.matches(fileName)) fail("$id: hqPageMap har ett ogiltigt filnamn: \"$fileName\"")
            }
            issue.hqPageRotation?.forEach { (page, degrees) ->
                if (page.toIntOrNull()?.let { it >= 1 } != true) fail("$id: hqPageRotation har ett ogiltigt sidnummer: \"$page\"")
                if (degrees !in hqRotations) fail("$id: hqPageRotation har en ogiltig vridning: $degrees")
            }
            issue.coverFlags.firstOrNull { it !in coverFlags }?.let { fail("$id: okänd coverFlag \"$it\"") }
            if (issue.coverFlags.count { it.startsWith("rotate_") } > 1 || issue.coverFlags.count { it.startsWith("crop_") } > 1) {
                fail("$id: coverFlags har mer än en vridning eller beskärning")
            }
            for ((name, value) in listOf("ocrPdfSize" to issue.ocrPdfSize, "pageCount" to issue.pageCount?.toLong(), "scanPageCount" to issue.scanPageCount?.toLong())) {
                if (value != null && value < 1) fail("$id: $name måste vara minst 1")
            }
        }
        for (issue in inventory.issues) {
            val related = issue.relatedIssueId ?: continue
            if (related == issue.issueId) fail("${issue.issueId}: relatedIssueId pekar på numret självt")
            if (related !in ids) fail("${issue.issueId}: relatedIssueId pekar på $related, som inte finns")
        }
    }
}
