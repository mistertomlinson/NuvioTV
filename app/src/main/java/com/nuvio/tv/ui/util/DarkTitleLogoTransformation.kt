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

    /*
     * v7 preserves real filled logo plates by detecting dense opaque
     * rectangles rather than inferring banners from dark pixels. Standalone
     * dark lettering remains eligible for conversion to soft white.
     */
    override val cacheKey: String =
        "nuvio_dark_title_logo_to_soft_white_v7"

    private data class PreservedOpaquePlate(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        fun contains(
            x: Int,
            y: Int
        ): Boolean =
            x in left..right &&
                y in top..bottom
    }

    /*
     * Lightweight banner detector.
     *
     * This deliberately avoids connected-component analysis. We scan the
     * bitmap once and collect only per-row statistics. A legitimate black
     * banner tends to form a wide, dense horizontal band over several rows,
     * while ordinary black lettering contains much more transparent space
     * inside its overall bounds.
     *
     * Memory cost is O(height), not O(width * height).
     */
    /*
     * Detect a real filled backing plate from ALPHA coverage.
     *
     * A white banner with black lettering and a black banner with white
     * lettering both share the important property that the banner itself
     * fills the transparent space between and around the glyphs.
     *
     * Standalone title lettering does not: its bounding rows contain
     * transparent gaps between glyphs and around their shapes.
     *
     * This is intentionally color-agnostic.
     *
     * Runtime cost:
     *   - one linear alpha scan
     *   - three IntArray(height) buffers
     *   - no flood fill
     *   - no per-pixel preservation mask
     */
    private fun findPreservedOpaquePlate(
        pixels: IntArray,
        width: Int,
        height: Int
    ): PreservedOpaquePlate? {
        if (
            width <= 0 ||
            height <= 0 ||
            pixels.isEmpty()
        ) {
            return null
        }

        val opaqueCountByRow =
            IntArray(height)

        val opaqueMinXByRow =
            IntArray(height) {
                width
            }

        val opaqueMaxXByRow =
            IntArray(height) {
                -1
            }

        /*
         * Use strongly opaque pixels for the core detector. Antialiased
         * outer edges are recovered by the small halo added at the end.
         */
        for (index in pixels.indices) {
            if (
                Color.alpha(
                    pixels[index]
                ) < 200
            ) {
                continue
            }

            val x =
                index % width

            val y =
                index / width

            opaqueCountByRow[y]++

            opaqueMinXByRow[y] =
                minOf(
                    opaqueMinXByRow[y],
                    x
                )

            opaqueMaxXByRow[y] =
                maxOf(
                    opaqueMaxXByRow[y],
                    x
                )
        }

        /*
         * A true filled plate should have almost no transparent holes across
         * its horizontal span. 90% is deliberately strict so ordinary text,
         * even bold text, does not qualify.
         */
        fun rowLooksLikePlate(
            row: Int
        ): Boolean {
            val minX =
                opaqueMinXByRow[row]

            val maxX =
                opaqueMaxXByRow[row]

            if (
                maxX < minX ||
                minX !in 0 until width
            ) {
                return false
            }

            val span =
                maxX - minX + 1

            if (
                span.toFloat() /
                    width.toFloat() <
                0.20f
            ) {
                return false
            }

            val fillRatio =
                opaqueCountByRow[row]
                    .toFloat() /
                    span.toFloat()

            return fillRatio >= 0.90f
        }

        var bestPlate:
            PreservedOpaquePlate? =
            null

        var bestArea = 0
        var row = 0

        while (row < height) {
            if (!rowLooksLikePlate(row)) {
                row++
                continue
            }

            val top =
                row

            var bottom =
                row

            var left =
                opaqueMinXByRow[row]

            var right =
                opaqueMaxXByRow[row]

            var opaquePixelCount =
                opaqueCountByRow[row]

            row++

            while (
                row < height &&
                rowLooksLikePlate(row)
            ) {
                bottom =
                    row

                left =
                    minOf(
                        left,
                        opaqueMinXByRow[row]
                    )

                right =
                    maxOf(
                        right,
                        opaqueMaxXByRow[row]
                    )

                opaquePixelCount +=
                    opaqueCountByRow[row]

                row++
            }

            val plateWidth =
                right - left + 1

            val plateHeight =
                bottom - top + 1

            val plateArea =
                plateWidth *
                    plateHeight

            if (plateArea <= 0) {
                continue
            }

            val fillRatio =
                opaquePixelCount
                    .toFloat() /
                    plateArea.toFloat()

            val widthShare =
                plateWidth.toFloat() /
                    width.toFloat()

            val heightShare =
                plateHeight.toFloat() /
                    height.toFloat()

            val aspectRatio =
                plateWidth.toFloat() /
                    plateHeight
                        .coerceAtLeast(1)
                        .toFloat()

            /*
             * Require a meaningful multi-row filled plate. These constraints,
             * combined with the 90% alpha-fill test above, are intentionally
             * hostile to false positives from standalone glyphs.
             */
            val minimumPlateHeight =
                maxOf(
                    3,
                    (height * 0.05f)
                        .toInt()
                )

            val preserve =
                plateHeight >=
                    minimumPlateHeight &&
                    widthShare >= 0.20f &&
                    heightShare >= 0.05f &&
                    fillRatio >= 0.90f &&
                    aspectRatio >= 1.5f

            if (
                preserve &&
                plateArea > bestArea
            ) {
                bestArea =
                    plateArea

                bestPlate =
                    PreservedOpaquePlate(
                        left =
                            (left - 2)
                                .coerceAtLeast(0),
                        top =
                            (top - 2)
                                .coerceAtLeast(0),
                        right =
                            (right + 2)
                                .coerceAtMost(
                                    width - 1
                                ),
                        bottom =
                            (bottom + 2)
                                .coerceAtMost(
                                    height - 1
                                )
                    )
            }
        }

        return bestPlate
    }

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

        /*
         * Protect any real filled backing plate exactly as authored before
         * applying black/dark-gray -> white conversion elsewhere.
         */
        val preservedOpaquePlate =
            findPreservedOpaquePlate(
                pixels = pixels,
                width = width,
                height = height
            )

        var changed = false

        for (index in pixels.indices) {
            val preservedPlate =
                preservedOpaquePlate

            if (preservedPlate != null) {
                val x =
                    index % width

                val y =
                    index / width

                if (
                    preservedPlate.contains(
                        x = x,
                        y = y
                    )
                ) {
                    continue
                }
            }

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
