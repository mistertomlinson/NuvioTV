package com.nuvio.tv.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext

/*
 * Missing-artwork titles are treated as generated logos rather than ordinary
 * independently-wrapped text.
 *
 * One canonical layout decides:
 *   - font
 *   - font size
 *   - line breaks
 *   - line baselines / spacing
 *
 * Every destination scales that exact canonical canvas uniformly into its own
 * logo slot. No destination is allowed to re-wrap or re-space the title.
 */
internal enum class FallbackTitleLogoGeometry(
    val referenceWidthPx: Int,
    val referenceHeightPx: Int,
    val referenceBaseFontPx: Float
) {
    /*
     * Home hero with "large metadata" enabled.
     * Existing geometry: 220dp x 100dp, headlineLarge = 28sp.
     */
    LargeMetadata(
        referenceWidthPx = 220,
        referenceHeightPx = 100,
        referenceBaseFontPx = 28f
    ),

    /*
     * Home hero with "large metadata" disabled.
     * Existing geometry:
     *   width  = 340dp * 0.7 = 238dp
     *   height = 100dp * 0.7 = 70dp
     *   font   = 28sp * 0.7 = 19.6sp
     *
     * This is intentionally a separate canonical geometry rather than a
     * scalar copy of LargeMetadata because its width-to-font ratio differs.
     */
    SmallMetadata(
        referenceWidthPx = 238,
        referenceHeightPx = 70,
        referenceBaseFontPx = 19.6f
    )
}

internal enum class FallbackTitleLogoHorizontalAlignment {
    Start,
    Center,
    End
}

internal enum class FallbackTitleLogoVerticalAlignment {
    Top,
    Center,
    Bottom
}

private const val FALLBACK_LINE_SPACING_MULTIPLIER = 0.90f
private const val FALLBACK_MAX_LINES = 3
private const val FALLBACK_MIN_FONT_PX = 1f

/*
 * Generated-logo styling must be deterministic.
 *
 * A title always receives the same font on every surface and after every
 * app restart. This is intentionally NOT runtime randomness.
 *
 * Keep this ordering stable once released; changing it will reshuffle
 * existing title/font assignments.
 */
private val FALLBACK_FONT_ASSET_PATHS =
    listOf(
        "fonts/28_days_later.ttf",
        "fonts/afton_james.ttf",
        "fonts/anton.ttf",
        "fonts/bebas_neue.ttf",
        "fonts/cinzel_bold.ttf"
    )

private val FALLBACK_STYLE_WHITESPACE = Regex("""\s+""")

private fun fallbackTitleLogoStyleKey(title: String): String =
    FALLBACK_STYLE_WHITESPACE
        .replace(title.trim().lowercase(), " ")

/*
 * Small stable FNV-1a hash. Unlike runtime object hashes, this produces the
 * same value for the same normalized title across processes and restarts.
 */
private fun stableFallbackTitleLogoHash(value: String): Int {
    var hash = 0x811C9DC5.toInt()

    value.forEach { character ->
        hash = hash xor character.code
        hash *= 16777619
    }

    return hash
}

private fun fallbackTitleLogoFontAsset(title: String): String {
    val styleKey = fallbackTitleLogoStyleKey(title)

    val index =
        Math.floorMod(
            stableFallbackTitleLogoHash(styleKey),
            FALLBACK_FONT_ASSET_PATHS.size
        )

    return FALLBACK_FONT_ASSET_PATHS[index]
}

/*
 * Generated fallback logos use the same neutral soft white on every surface.
 * Font choice remains deterministic; color no longer varies by title.
 */
private val FALLBACK_TITLE_LOGO_SOFT_WHITE =
    Color(0xFFF4F1EA)
/*
 * A long title that technically fits on one line can become visually tiny
 * after the finished generated-logo bounds are ContentScale.Fit into a logo
 * slot. Prefer a balanced two-line composition once a 4+ word title occupies
 * most of the canonical width.
 */
private const val FALLBACK_LONG_SINGLE_LINE_MIN_WORDS = 4
private const val FALLBACK_LONG_SINGLE_LINE_WIDTH_FRACTION = 0.78f

private data class FallbackTitleLogoLayout(
    val lines: List<String>,
    /*
     * The canonical layout determines wrapping and baseline spacing once.
     * These baselines are normalized to the visible generated-logo bounds,
     * so every destination can ContentScale.Fit the SAME composition.
     */
    val normalizedLineBaselinesPx: List<Float>,
    val fontSizePx: Float,
    /*
     * Horizontal bounds are the actual visible glyph ink relative to the
     * centered draw anchor. This removes font side-bearing from logo alignment.
     */
    val lineLeftPx: List<Float>,
    val lineRightPx: List<Float>,
    val contentWidthPx: Float,
    val contentHeightPx: Float
)

private fun buildStaticLayout(
    title: String,
    typeface: Typeface,
    fontSizePx: Float,
    geometry: FallbackTitleLogoGeometry
): StaticLayout {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textSize = fontSizePx
    }

    return StaticLayout.Builder
        .obtain(
            title,
            0,
            title.length,
            paint,
            geometry.referenceWidthPx
        )
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setLineSpacing(
            0f,
            FALLBACK_LINE_SPACING_MULTIPLIER
        )
        .setIncludePad(false)
        .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        .build()
}

private fun chooseBalancedTwoLineTitle(
    title: String,
    typeface: Typeface,
    fontSizePx: Float
): String? {
    val words =
        title
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

    if (words.size < FALLBACK_LONG_SINGLE_LINE_MIN_WORDS) {
        return null
    }

    /*
     * Keep at least two words on each side. That avoids trading one awkward
     * single-line logo for a two-line logo with one stranded word.
     */
    val candidateSplits =
        2..(words.size - 2)

    if (candidateSplits.isEmpty()) {
        return null
    }

    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textSize = fontSizePx
    }

    val bestSplit =
        candidateSplits.minByOrNull { splitIndex ->
            val firstLine =
                words
                    .take(splitIndex)
                    .joinToString(" ")

            val secondLine =
                words
                    .drop(splitIndex)
                    .joinToString(" ")

            kotlin.math.abs(
                paint.measureText(firstLine) -
                    paint.measureText(secondLine)
            )
        } ?: return null

    return buildString {
        append(
            words
                .take(bestSplit)
                .joinToString(" ")
        )
        append('\n')
        append(
            words
                .drop(bestSplit)
                .joinToString(" ")
        )
    }
}

private fun createFallbackTitleLogoLayout(
    title: String,
    typeface: Typeface,
    geometry: FallbackTitleLogoGeometry
): FallbackTitleLogoLayout {
    val fitStepPx =
        geometry.referenceBaseFontPx * 0.04f

    fun fitText(
        text: String
    ): Pair<Float, StaticLayout> {
        var candidateFontSizePx =
            geometry.referenceBaseFontPx

        var candidateLayout =
            buildStaticLayout(
                title = text,
                typeface = typeface,
                fontSizePx = candidateFontSizePx,
                geometry = geometry
            )

        while (
            candidateFontSizePx >
                FALLBACK_MIN_FONT_PX
        ) {
            val consumedAllText =
                candidateLayout.lineCount > 0 &&
                    candidateLayout.getLineEnd(
                        candidateLayout.lineCount - 1
                    ) >= text.length

            val fits =
                consumedAllText &&
                    candidateLayout.lineCount <=
                        FALLBACK_MAX_LINES &&
                    candidateLayout.height <=
                        geometry.referenceHeightPx

            if (fits) {
                break
            }

            candidateFontSizePx =
                (
                    candidateFontSizePx -
                        fitStepPx
                    )
                    .coerceAtLeast(
                        FALLBACK_MIN_FONT_PX
                    )

            candidateLayout =
                buildStaticLayout(
                    title = text,
                    typeface = typeface,
                    fontSizePx = candidateFontSizePx,
                    geometry = geometry
                )

            if (
                candidateFontSizePx <=
                    FALLBACK_MIN_FONT_PX
            ) {
                break
            }
        }

        return candidateFontSizePx to
            candidateLayout
    }

    var layoutText = title

    var fitted =
        fitText(layoutText)

    var fontSizePx =
        fitted.first

    var layout =
        fitted.second

    /*
     * Logo-specific refinement:
     *
     * If Android's balanced breaker still leaves a 4+ word title on one line
     * and that line consumes most of the canonical width, deliberately turn
     * it into the most visually balanced two-line composition.
     *
     * We then refit FROM the normal base font size, so the new two-line logo
     * can grow rather than retaining the smaller visual result caused by its
     * previously wide one-line bounds.
     */
    if (
        layout.lineCount == 1 &&
        layoutText.indexOf('\n') < 0
    ) {
        val oneLinePaint =
            TextPaint(
                Paint.ANTI_ALIAS_FLAG
            ).apply {
                this.typeface = typeface
                textSize = fontSizePx
            }

        val oneLineWidthPx =
            oneLinePaint.measureText(
                title.trim()
            )

        val widthFraction =
            oneLineWidthPx /
                geometry.referenceWidthPx

        if (
            widthFraction >=
                FALLBACK_LONG_SINGLE_LINE_WIDTH_FRACTION
        ) {
            val forcedTwoLineTitle =
                chooseBalancedTwoLineTitle(
                    title = title,
                    typeface = typeface,
                    fontSizePx = fontSizePx
                )

            if (forcedTwoLineTitle != null) {
                layoutText =
                    forcedTwoLineTitle

                fitted =
                    fitText(layoutText)

                fontSizePx =
                    fitted.first

                layout =
                    fitted.second
            }
        }
    }

    val lineCount =
        layout.lineCount
            .coerceAtMost(
                FALLBACK_MAX_LINES
            )

    val lines =
        (0 until lineCount).map { lineIndex ->
            val start =
                layout.getLineStart(
                    lineIndex
                )

            val end =
                layout.getLineEnd(
                    lineIndex
                )

            layoutText
                .substring(start, end)
                .trimEnd('\n', '\r')
                .trim()
        }

    /*
     * Measure the FINISHED canonical composition, not the whole reference
     * canvas. The reference geometry decides wrapping only. Rendering later
     * behaves like an artwork logo with ContentScale.Fit: the actual generated
     * logo grows until either the destination's max width or max height is hit.
     */
    val paint =
        TextPaint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            this.typeface = typeface
            textSize = fontSizePx
        }

    /*
     * Paint.Align.CENTER positions text by its advance width, not its visible
     * glyph edge. Measure each line's real ink bounds in that same centered
     * coordinate system so Start alignment means visible ink starts at x=0.
     *
     * Landscape still keeps its own explicit 10.dp start padding outside this
     * renderer; this only removes the font's invisible side-bearing.
     */
    val centeredLineInkBounds =
        lines.map { line ->
            val advanceWidthPx =
                paint.measureText(line)

            /*
             * getTextBounds() rounds to integer pixels, which can leave a
             * title-dependent sliver of apparent side-bearing with distressed
             * fonts. Use the actual vector glyph outline so A, T, etc. align
             * by their real visible edge.
             */
            val glyphPath =
                android.graphics.Path()

            paint.getTextPath(
                line,
                0,
                line.length,
                0f,
                0f,
                glyphPath
            )

            val glyphBounds =
                android.graphics.RectF()

            glyphPath.computeBounds(
                glyphBounds,
                true
            )

            val advanceCenterPx =
                advanceWidthPx / 2f

            if (glyphPath.isEmpty) {
                Pair(
                    -advanceCenterPx,
                    advanceCenterPx
                )
            } else {
                Pair(
                    glyphBounds.left -
                        advanceCenterPx,
                    glyphBounds.right -
                        advanceCenterPx
                )
            }
        }

    val lineLeftPx =
        centeredLineInkBounds.map { it.first }

    val lineRightPx =
        centeredLineInkBounds.map { it.second }

    /*
     * All lines share one visible left edge. The logo width is therefore the
     * widest visible line after each line has been normalized to x=0.
     */
    val contentWidthPx =
        centeredLineInkBounds
            .maxOfOrNull { bounds ->
                (bounds.second - bounds.first)
                    .coerceAtLeast(1f)
            }
            ?: 1f

    val fontMetrics =
        paint.fontMetrics

    val rawLineBaselinesPx =
        (0 until lineCount).map { lineIndex ->
            layout
                .getLineBaseline(
                    lineIndex
                )
                .toFloat()
        }

    val contentTopPx =
        if (
            rawLineBaselinesPx
                .isNotEmpty()
        ) {
            rawLineBaselinesPx.first() +
                fontMetrics.ascent
        } else {
            0f
        }

    val contentBottomPx =
        if (
            rawLineBaselinesPx
                .isNotEmpty()
        ) {
            rawLineBaselinesPx.last() +
                fontMetrics.descent
        } else {
            1f
        }

    val normalizedLineBaselinesPx =
        rawLineBaselinesPx.map { baseline ->
            baseline - contentTopPx
        }

    return FallbackTitleLogoLayout(
        lines = lines,
        normalizedLineBaselinesPx =
            normalizedLineBaselinesPx,
        fontSizePx = fontSizePx,
        lineLeftPx = lineLeftPx,
        lineRightPx = lineRightPx,
        contentWidthPx = contentWidthPx,
        contentHeightPx =
            (
                contentBottomPx -
                    contentTopPx
                )
                .coerceAtLeast(1f)
    )
}

@Composable
internal fun FallbackTitleLogo(
    title: String,
    geometry: FallbackTitleLogoGeometry,
    modifier: Modifier = Modifier,
    color: Color? = null,
    horizontalAlignment: FallbackTitleLogoHorizontalAlignment =
        FallbackTitleLogoHorizontalAlignment.Center,
    verticalAlignment: FallbackTitleLogoVerticalAlignment =
        FallbackTitleLogoVerticalAlignment.Center
) {
    val context = LocalContext.current

    val fontAssetPath =
        remember(title) {
            fallbackTitleLogoFontAsset(title)
        }

    val typeface =
        remember(context, fontAssetPath) {
            Typeface.Builder(
                context.assets,
                fontAssetPath
            ).build()
        }

    val resolvedColor =
        color ?: FALLBACK_TITLE_LOGO_SOFT_WHITE

    /*
     * This is the only place where the title is ever wrapped.
     */
    val canonicalLayout =
        remember(title, typeface, geometry) {
            createFallbackTitleLogoLayout(
                title = title,
                typeface = typeface,
                geometry = geometry
            )
        }

    Canvas(modifier = modifier) {
        if (
            title.isBlank() ||
            canonicalLayout.lines.isEmpty() ||
            size.width <= 0f ||
            size.height <= 0f
        ) {
            return@Canvas
        }

        /*
         * Match a real logo using ContentScale.Fit. The canonical reference
         * box determined the line breaks, but the FINISHED generated logo's
         * actual bounds determine its rendered size.
         */
        val widthScale =
            size.width /
                canonicalLayout.contentWidthPx

        val heightScale =
            size.height /
                canonicalLayout.contentHeightPx

        val scale =
            minOf(widthScale, heightScale)
                .coerceAtLeast(0f)

        if (scale <= 0f) {
            return@Canvas
        }

        val renderedContentWidth =
            canonicalLayout.contentWidthPx * scale

        val renderedContentHeight =
            canonicalLayout.contentHeightPx * scale

        val originX =
            when (horizontalAlignment) {
                FallbackTitleLogoHorizontalAlignment.Start ->
                    0f

                FallbackTitleLogoHorizontalAlignment.Center ->
                    (size.width - renderedContentWidth) / 2f

                FallbackTitleLogoHorizontalAlignment.End ->
                    size.width - renderedContentWidth
            }

        val originY =
            when (verticalAlignment) {
                FallbackTitleLogoVerticalAlignment.Top ->
                    0f

                FallbackTitleLogoVerticalAlignment.Center ->
                    (size.height - renderedContentHeight) / 2f

                FallbackTitleLogoVerticalAlignment.Bottom ->
                    size.height - renderedContentHeight
            }

        /*
         * Individual lines stay centered relative to each other, but the
         * generated logo as a whole honors Start/Center/End exactly like an
         * image logo. Font and baseline spacing share the same scale.
         */
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize =
                canonicalLayout.fontSizePx * scale
            this.color = resolvedColor.toArgb()
            textAlign = Paint.Align.CENTER
        }

        drawIntoCanvas { canvas ->
            canonicalLayout.lines.forEachIndexed { index, line ->
                /*
                 * Each line is independently normalized by its actual visible
                 * glyph edge, so every stacked line begins at the same x.
                 * Surface-level alignment still positions the entire generated
                 * logo; Landscape's explicit 10.dp inset remains untouched.
                 */
                val lineDrawCenterX =
                    originX -
                        canonicalLayout.lineLeftPx[index] *
                        scale

                canvas.nativeCanvas.drawText(
                    line,
                    lineDrawCenterX,
                    originY +
                        canonicalLayout
                            .normalizedLineBaselinesPx[index] *
                        scale,
                    paint
                )
            }
        }
    }
}

/*
 * Rasterized generated-logo renderer for animated surfaces.
 *
 * The canonical title composition is rendered once at 4x resolution.
 * Animation then transforms ONE bitmap rectangle rather than rerasterizing
 * individual text glyphs at a changing scale.
 */
@Composable
internal fun RasterizedFallbackTitleLogo(
    title: String,
    geometry: FallbackTitleLogoGeometry,
    targetWidth: androidx.compose.ui.unit.Dp,
    targetHeight: androidx.compose.ui.unit.Dp,
    pulseScale: Float = 1f,
    alpha: Float = 1f,
    modifier: Modifier = Modifier,
    color: Color? = null,
    horizontalAlignment: FallbackTitleLogoHorizontalAlignment =
        FallbackTitleLogoHorizontalAlignment.Center,
    verticalAlignment: FallbackTitleLogoVerticalAlignment =
        FallbackTitleLogoVerticalAlignment.Center
) {
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current

    val fontAssetPath =
        remember(title) {
            fallbackTitleLogoFontAsset(title)
        }

    val typeface =
        remember(context, fontAssetPath) {
            Typeface.Builder(
                context.assets,
                fontAssetPath
            ).build()
        }

    val resolvedColor =
        color ?: FALLBACK_TITLE_LOGO_SOFT_WHITE

    val canonicalLayout =
        remember(title, typeface, geometry) {
            createFallbackTitleLogoLayout(
                title = title,
                typeface = typeface,
                geometry = geometry
            )
        }

    val targetWidthPx =
        with(density) {
            targetWidth.toPx()
        }

    val targetHeightPx =
        with(density) {
            targetHeight.toPx()
        }

    val supersample = 4

    val rasterWidthPx =
        (targetWidthPx * supersample)
            .toInt()
            .coerceAtLeast(1)

    val rasterHeightPx =
        (targetHeightPx * supersample)
            .toInt()
            .coerceAtLeast(1)

    val colorArgb = resolvedColor.toArgb()

    val bitmap =
        remember(
            title,
            typeface,
            geometry,
            rasterWidthPx,
            rasterHeightPx,
            colorArgb,
            horizontalAlignment,
            verticalAlignment
        ) {
            val result =
                android.graphics.Bitmap.createBitmap(
                    rasterWidthPx,
                    rasterHeightPx,
                    android.graphics.Bitmap.Config.ARGB_8888
                )

            if (
                title.isBlank() ||
                canonicalLayout.lines.isEmpty() ||
                canonicalLayout.contentWidthPx <= 0f ||
                canonicalLayout.contentHeightPx <= 0f
            ) {
                return@remember result
            }

            val rasterWidth =
                rasterWidthPx.toFloat()

            val rasterHeight =
                rasterHeightPx.toFloat()

            val scale =
                minOf(
                    rasterWidth /
                        canonicalLayout.contentWidthPx,
                    rasterHeight /
                        canonicalLayout.contentHeightPx
                )
                    .coerceAtLeast(0f)

            if (scale <= 0f) {
                return@remember result
            }

            val renderedContentWidth =
                canonicalLayout.contentWidthPx * scale

            val renderedContentHeight =
                canonicalLayout.contentHeightPx * scale

            val originX =
                when (horizontalAlignment) {
                    FallbackTitleLogoHorizontalAlignment.Start ->
                        0f

                    FallbackTitleLogoHorizontalAlignment.Center ->
                        (rasterWidth - renderedContentWidth) / 2f

                    FallbackTitleLogoHorizontalAlignment.End ->
                        rasterWidth - renderedContentWidth
                }

            val originY =
                when (verticalAlignment) {
                    FallbackTitleLogoVerticalAlignment.Top ->
                        0f

                    FallbackTitleLogoVerticalAlignment.Center ->
                        (rasterHeight - renderedContentHeight) / 2f

                    FallbackTitleLogoVerticalAlignment.Bottom ->
                        rasterHeight - renderedContentHeight
                }

            val paint =
                TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.typeface = typeface
                    textSize =
                        canonicalLayout.fontSizePx * scale
                    this.color = colorArgb
                    textAlign = Paint.Align.CENTER
                }

            val canvas =
                android.graphics.Canvas(result)

            canonicalLayout.lines.forEachIndexed { index, line ->
                val lineDrawCenterX =
                    originX -
                        canonicalLayout.lineLeftPx[index] *
                        scale

                canvas.drawText(
                    line,
                    lineDrawCenterX,
                    originY +
                        canonicalLayout
                            .normalizedLineBaselinesPx[index] *
                        scale,
                    paint
                )
            }

            result
        }

    Canvas(modifier = modifier) {
        if (
            bitmap.width <= 0 ||
            bitmap.height <= 0 ||
            size.width <= 0f ||
            size.height <= 0f
        ) {
            return@Canvas
        }

        /*
         * Keep the normal visible logo at targetWidth x targetHeight.
         * The Canvas itself is slightly larger so a 1.04 pulse cannot clip.
         */
        val baseWidth =
            targetWidthPx

        val baseHeight =
            targetHeightPx

        val drawWidth =
            baseWidth * pulseScale

        val drawHeight =
            baseHeight * pulseScale

        val left =
            (size.width - drawWidth) / 2f

        val top =
            (size.height - drawHeight) / 2f

        val destination =
            android.graphics.RectF(
                left,
                top,
                left + drawWidth,
                top + drawHeight
            )

        val bitmapPaint =
            android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG or
                    android.graphics.Paint.FILTER_BITMAP_FLAG
            ).apply {
                this.alpha =
                    (alpha.coerceIn(0f, 1f) * 255f)
                        .toInt()
            }

        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawBitmap(
                bitmap,
                null,
                destination,
                bitmapPaint
            )
        }
    }
}
