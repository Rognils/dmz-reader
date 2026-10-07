package se.c64.dmzreader.ui.components

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import coil3.transform.Transformation
import se.c64.dmzreader.data.db.IssueEntity
import se.c64.dmzreader.domain.CoverSource
import se.c64.dmzreader.domain.CoverTransform
import se.c64.dmzreader.domain.coverSources
import se.c64.dmzreader.domain.fullTitle
import se.c64.dmzreader.ui.theme.DmzColors
import se.c64.dmzreader.ui.theme.MonoStyles

/** Omslagets format, bredd genom höjd (planens E5). */
const val COVER_ASPECT_RATIO = 0.72f

/**
 * Det bildladdaren ska hämta för ett nummer: coverUrl precis som den står i
 * inventory. Appen bygger aldrig adresser själv.
 */
fun coverImageData(issue: IssueEntity): String? = issue.coverUrl

/**
 * Bildförfrågan för ett omslag som ska visas i [widthPx] × [heightPx].
 * Bilden avkodas nedskalad till den storleken, aldrig i full upplösning.
 */
fun coverImageRequest(
    context: android.content.Context,
    source: CoverSource,
    widthPx: Int,
    heightPx: Int,
): ImageRequest = when (source) {
    is CoverSource.Asset ->
        ImageRequest.Builder(context)
            .data(source.uri)
            .size(Size(widthPx, heightPx))
            .scale(Scale.FILL)
            .precision(Precision.INEXACT)
            .build()

    is CoverSource.Remote -> {
        val (sourceWidth, sourceHeight) = source.transform.sourceSizeFor(widthPx, heightPx)
        ImageRequest.Builder(context)
            .data(source.url)
            .size(Size(sourceWidth, sourceHeight))
            .scale(Scale.FILL)
            .precision(Precision.INEXACT)
            .apply {
                if (!source.transform.isIdentity) transformations(CoverFlagsTransformation(source.transform))
            }
            .build()
    }
}

/** Vrider och beskär en avkodad omslagsbild enligt [transform]. */
class CoverFlagsTransformation(private val transform: CoverTransform) : Transformation() {
    override val cacheKey: String = "cover-${transform.cacheKey}"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val rotated = if (transform.rotationDegrees == 0) {
            input
        } else {
            val matrix = Matrix().apply { postRotate(transform.rotationDegrees.toFloat()) }
            Bitmap.createBitmap(input, 0, 0, input.width, input.height, matrix, true)
        }
        val half = rotated.width / 2
        return when (transform.crop) {
            CoverTransform.Crop.NONE -> rotated
            CoverTransform.Crop.LEFT_HALF -> Bitmap.createBitmap(rotated, 0, 0, half, rotated.height)
            CoverTransform.Crop.RIGHT_HALF ->
                Bitmap.createBitmap(rotated, rotated.width - half, 0, half, rotated.height)
        }
    }
}

/**
 * Miniatyrerna som följer med appen, som sökvägar under assets ("covers/….webp").
 * null när det inte är känt, till exempel i en förhandsvisning.
 */
val LocalBundledCovers = staticCompositionLocalOf<Set<String>?> { null }

/**
 * Omslaget för ett nummer i formatet [COVER_ASPECT_RATIO].
 *
 * Källorna prövas i tur och ordning: den inbyggda miniatyren, sedan coverUrl.
 * Finns ingen källa, eller misslyckas alla, visas en platshållare med
 * publikation, år och nummer (planens B3).
 */
@Composable
fun IssueCover(
    issue: IssueEntity,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 6.dp,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val description = issue.fullTitle()
    val bundledCovers = LocalBundledCovers.current
    val sources = remember(issue.coverAsset, issue.coverUrl, issue.coverFlags, bundledCovers) {
        coverSources(issue, bundledCovers)
    }
    // Hur många källor som har misslyckats. Nästa i listan prövas.
    var failed by remember(sources) { mutableIntStateOf(0) }

    BoxWithConstraints(
        modifier = modifier
            .aspectRatio(COVER_ASPECT_RATIO)
            .clip(shape)
            .semantics { contentDescription = description },
    ) {
        // Platshållaren ligger underst och syns tills en bild har ritats ovanpå.
        CoverPlaceholder(issue, cornerRadius)
        val source = sources.getOrNull(failed)
        if (source != null) {
            val context = LocalContext.current
            val widthPx = constraints.maxWidth
            val heightPx = (widthPx / COVER_ASPECT_RATIO).toInt()
            val request = remember(source, widthPx) { coverImageRequest(context, source, widthPx, heightPx) }
            AsyncImage(
                model = request,
                imageLoader = imageLoader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { failed += 1 },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun CoverPlaceholder(issue: IssueEntity, cornerRadius: Dp) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = DmzColors.Surface,
        shape = RoundedCornerShape(cornerRadius),
        border = BorderStroke(1.dp, DmzColors.Border),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = issue.publicationTitle.uppercase(),
                style = MonoStyles.Label,
                color = DmzColors.Label,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = issue.year.toString(),
                style = MaterialTheme.typography.titleLarge,
                color = DmzColors.TextPrimary,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text = issue.issueLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = DmzColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}
