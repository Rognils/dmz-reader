package se.c64.dmzreader.data.inventory

import kotlinx.serialization.Serializable

/**
 * archive_inventory.json så som toolchainen skriver den. Speglar
 * docs/inventory.schema.json fullt ut. Fält som schemat kräver saknar
 * standardvärde här, så en fil utan dem avvisas.
 */
@Serializable
data class InventoryDto(
    val schemaVersion: Int,
    val datasetVersion: String,
    val generatedAt: String,
    val generator: String,
    val publications: List<InventoryPublicationDto>,
    val issues: List<InventoryIssueDto>,
)

@Serializable
data class InventoryPublicationDto(
    val publicationId: String,
    val publicationTitle: String,
)

@Serializable
data class InventoryIssueDto(
    val issueId: String,
    val publicationId: String,
    val publicationTitle: String,
    val year: Int,
    val issueLabel: String,
    val sortKey: String,
    val sourceKey: String,
    val sourceNames: List<String>,
    val relatedIssueId: String? = null,
    // Måste finnas i filen, men får vara null (flaggan missing_cover).
    val coverUrl: String?,
    val coverFlags: List<String>,
    val coverAsset: String? = null,
    // Måste finnas i filen, men får vara null (flaggan missing_ocr_pdf).
    val ocrPdfUrl: String?,
    val ocrPdfSize: Long? = null,
    val ocrPdfEtag: String? = null,
    val ocrPdfLastModified: String? = null,
    val largePdfUrl: String? = null,
    val largePdfSize: Long? = null,
    val hqSource: String? = null,
    val scanPagePattern: String? = null,
    val scanPageCount: Int? = null,
    val pageCount: Int? = null,
    val spreadOffset: Int? = null,
    val flags: List<String>,
)

/** Bara det som behövs för att avgöra om filen alls går att läsa. */
@Serializable
internal data class InventoryHeaderDto(val schemaVersion: Int)
