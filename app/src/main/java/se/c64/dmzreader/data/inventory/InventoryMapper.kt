package se.c64.dmzreader.data.inventory

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import se.c64.dmzreader.data.db.IssueEntity

/** Entiteten är en delmängd av inventory: allt utom sourceNames. */
fun InventoryIssueDto.toEntity(): IssueEntity = IssueEntity(
    issueId = issueId,
    publicationId = publicationId,
    publicationTitle = publicationTitle,
    year = year,
    issueLabel = issueLabel,
    sortKey = sortKey,
    sourceKey = sourceKey,
    relatedIssueId = relatedIssueId,
    coverUrl = coverUrl,
    coverFlags = coverFlags,
    coverAsset = coverAsset,
    ocrPdfUrl = ocrPdfUrl,
    ocrPdfSize = ocrPdfSize,
    ocrPdfEtag = ocrPdfEtag,
    ocrPdfLastModified = ocrPdfLastModified,
    largePdfUrl = largePdfUrl,
    largePdfSize = largePdfSize,
    hqSource = hqSource,
    scanPagePattern = scanPagePattern,
    scanPageCount = scanPageCount,
    pageCount = pageCount,
    spreadOffset = spreadOffset,
    flags = flags,
    hqPageMap = hqPageMap?.let { Json.encodeToString(pageMapSerializer, it) },
    hqPageRotation = hqPageRotation?.let { Json.encodeToString(pageRotationSerializer, it) },
)

private val pageMapSerializer = MapSerializer(String.serializer(), String.serializer().nullable)
private val pageRotationSerializer = MapSerializer(String.serializer(), Int.serializer())
