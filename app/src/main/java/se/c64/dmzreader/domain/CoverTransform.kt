package se.c64.dmzreader.domain

/**
 * Hur en omslagsbild ska visas, enligt coverFlags i inventory. Vridningen
 * tillämpas först, därefter beskärningen.
 *
 * Transformen bestäms enbart av flaggorna, aldrig av vilket nummer det gäller.
 */
data class CoverTransform(val rotationDegrees: Int = 0, val crop: Crop = Crop.NONE) {
    enum class Crop { NONE, LEFT_HALF, RIGHT_HALF }

    val isIdentity: Boolean get() = rotationDegrees == 0 && crop == Crop.NONE

    /** Stabil nyckel för bildcachen. */
    val cacheKey: String get() = "rot$rotationDegrees-${crop.name.lowercase()}"

    /**
     * Hur stor källbilden måste vara för att resultatet ska bli [width] × [height]
     * efter vridning och beskärning. Används för att be bildladdaren om lagom
     * många pixlar: tillräckligt för att bilden ska bli skarp, men inte fler.
     */
    fun sourceSizeFor(width: Int, height: Int): Pair<Int, Int> {
        // Baklänges: först beskärningen, sedan vridningen.
        val beforeCropWidth = if (crop == Crop.NONE) width else width * 2
        return if (rotationDegrees == 90 || rotationDegrees == 270) {
            height to beforeCropWidth
        } else {
            beforeCropWidth to height
        }
    }

    /** Storleken på resultatet när transformen tillämpas på en bild med de givna måtten. */
    fun resultSizeFor(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        val (rotatedWidth, rotatedHeight) =
            if (rotationDegrees == 90 || rotationDegrees == 270) sourceHeight to sourceWidth
            else sourceWidth to sourceHeight
        val width = if (crop == Crop.NONE) rotatedWidth else rotatedWidth / 2
        return width to rotatedHeight
    }

    companion object {
        val IDENTITY = CoverTransform()

        /**
         * Okända flaggor påverkar inte visningen: en nyare inventory-fil ska
         * inte kunna göra omslagen oläsliga i en äldre app.
         */
        fun fromFlags(flags: List<String>): CoverTransform {
            val rotation = when {
                "rotate_cw" in flags -> 90
                "rotate_180" in flags -> 180
                "rotate_ccw" in flags -> 270
                else -> 0
            }
            val crop = when {
                "crop_left_half" in flags -> Crop.LEFT_HALF
                "crop_right_half" in flags -> Crop.RIGHT_HALF
                else -> Crop.NONE
            }
            return CoverTransform(rotation, crop)
        }
    }
}
