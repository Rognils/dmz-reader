package se.c64.dmzreader.data.inventory

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
)
