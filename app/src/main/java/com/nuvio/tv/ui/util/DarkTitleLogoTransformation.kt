package com.nuvio.tv.ui.util

import android.graphics.Bitmap
import android.graphics.Color
import coil.size.Size
import coil.transform.Transformation

/**
 * Improves dark title-logo readability without recoloring the entire logo.
 *
 * Only pixels that are black, near-black, or dark neutral gray are converted
 * to a soft white. Saturated/colorful pixels are preserved.
 *
 * A small amount of feathering is applied to neutral gray boundary pixels so
 * antialiased edges and gradients remain smooth.
 *
 * Opaque images are left untouched as protection against accidentally
 * processing photographs or artwork with a baked-in background.
 */
object DarkTitleLogoTransformation : Transformation {

    // v3 uses a softer white and intentionally invalidates earlier transformed-logo caches.
    override val cacheKey: String =
        "nuvio_dark_title_logo_to_soft_white_v3"

    override suspend fun transform(
        input: Bitmap,
        size: Size
    ): Bitmap {
        val width = input.width
        val height = input.height
        if (width <= 0 || height <= 0) return input

        val output =
            input.copy(Bitmap.Config.ARGB_8888, true)
                ?: return input

        val pixels = IntArray(width * height)
        output.getPixels(
            pixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        /*
         * Protect opaque artwork. Real title logos normally have a meaningful
         * transparent background.
         */
        var transparentPixels = 0
        for (pixel in pixels) {
            if (Color.alpha(pixel) < 32) {
                transparentPixels++
            }
        }

        val transparencyRatio =
            transparentPixels.toFloat() / pixels.size.toFloat()

        if (transparencyRatio < 0.02f) {
            return input
        }

        var changed = false

        for (index in pixels.indices) {
            val pixel = pixels[index]
            val alpha = Color.alpha(pixel)

            if (alpha < 16) continue

            val red = Color.red(pixel)
            val green = Color.green(pixel)
            val blue = Color.blue(pixel)

            val maxChannel = maxOf(red, green, blue)
            val minChannel = minOf(red, green, blue)
            val chroma = maxChannel - minChannel

            // Relative chroma keeps even very dark saturated colors intact.
            // For example, a deep red can have low absolute RGB values while
            // still being strongly colored relative to its brightness.
            val chromaRatio =
                if (maxChannel == 0) 0f
                else chroma.toFloat() / maxChannel.toFloat()

            /*
             * Only neutral black / dark gray is converted. Strongly colored
             * reds, blues, greens, etc. are preserved even when very dark.
             */
            val isNearBlack =
                maxChannel <= 56 &&
                    chromaRatio <= 0.20f

            val isDarkNeutral =
                maxChannel <= 125 &&
                    chromaRatio <= 0.16f

            if (isNearBlack || isDarkNeutral) {
                pixels[index] =
                    Color.argb(
                        alpha,
                        240,
                        240,
                        240
                    )
                changed = true
                continue
            }

            /*
             * Feather the edge of neutral dark-gray artwork instead of using
             * a hard cutoff. Strongly colored pixels never enter this branch.
             */
            if (
                maxChannel in 126..160 &&
                chromaRatio <= 0.10f
            ) {
                val strength =
                    ((160 - maxChannel) / 34f)
                        .coerceIn(0f, 1f) *
                        0.65f

                if (strength > 0f) {
                    val newRed =
                        (red + (240 - red) * strength)
                            .toInt()
                            .coerceIn(0, 255)

                    val newGreen =
                        (green + (240 - green) * strength)
                            .toInt()
                            .coerceIn(0, 255)

                    val newBlue =
                        (blue + (240 - blue) * strength)
                            .toInt()
                            .coerceIn(0, 255)

                    pixels[index] =
                        Color.argb(
                            alpha,
                            newRed,
                            newGreen,
                            newBlue
                        )

                    changed = true
                }
            }
        }

        if (!changed) {
            return input
        }

        output.setPixels(
            pixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        return output
    }
}
