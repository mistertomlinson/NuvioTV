package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val HOME_POSTER_FADE_HEIGHT = 20.dp

@Stable
internal data class HomePosterFadeViewport(val scene: XlSceneState, val list: LazyListState,
    val enabled: Boolean, val ordinaryTop: Dp)

private data class PosterFadeGeometry(val top: Float, val clearUntil: Float, val end: Float)
private val hiddenPoster = PosterFadeGeometry(0f, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)

/** Only the narrow feather strip needs a surface; both draws reuse the poster display list. */
@Composable
internal fun Modifier.homePosterFade(viewport: HomePosterFadeViewport?, rowKey: String): Modifier {
    if (viewport == null) return this
    val density = LocalDensity.current
    val mask by rememberHomePosterMask(viewport.scene, viewport.list, viewport.enabled, viewport.ordinaryTop)
    var bodyHeight by remember(rowKey) { mutableIntStateOf(0) }
    val geometry by remember(viewport, rowKey, density) {
        derivedStateOf {
            val bounds = mask ?: return@derivedStateOf null
            val info = viewport.list.layoutInfo
            val owner = info.visibleItemsInfo.firstOrNull { it.key == rowKey } ?: return@derivedStateOf null
            with(density) {
                val bodyTop = owner.offset + info.beforeContentPadding +
                    (viewport.scene.headroomValues[rowKey]?.toPx() ?: 0f) +
                    (viewport.scene.headerHeights[rowKey] ?: 40.dp.toPx())
                val top = bounds.first - bodyTop
                val clearUntil = bounds.second + 12.dp.toPx() - bodyTop
                val end = clearUntil + HOME_POSTER_FADE_HEIGHT.toPx()
                val height = bodyHeight.takeIf { it > 0 }?.toFloat()
                    ?: (owner.size - (bodyTop - owner.offset - info.beforeContentPadding))
                val overhang = 48.dp.toPx()
                when {
                    end <= -overhang || top >= height + overhang -> null
                    top <= -overhang && clearUntil >= height + overhang -> hiddenPoster
                    else -> PosterFadeGeometry(top, clearUntil, end)
                }
            }
        }
    }
    return onSizeChanged { bodyHeight = it.height }.drawWithCache {
        val paint = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 0f, 1f,
                android.graphics.Color.TRANSPARENT, android.graphics.Color.WHITE, android.graphics.Shader.TileMode.CLAMP)
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        }
        val matrix = android.graphics.Matrix()
        val fadeHeight = HOME_POSTER_FADE_HEIGHT.toPx()
        val overhang = 48.dp.toPx()
        onDrawWithContent {
            val edge = geometry
            when {
                edge == null -> drawContent()
                edge === hiddenPoster -> Unit
                else -> {
                    // Fully opaque artwork needs no temporary render target.
                    clipRect(top = edge.top, bottom = edge.end, clipOp = ClipOp.Difference) {
                        this@onDrawWithContent.drawContent()
                    }
                    // Bound the destination-in operation to 20dp, not a row or
                    // the screen. The inner graphics layer caches actual cards.
                    clipRect(top = edge.clearUntil, bottom = edge.end) {
                        val canvas = drawContext.canvas.nativeCanvas
                        val saved = canvas.saveLayer(-overhang, edge.clearUntil,
                            size.width + overhang, edge.end, null)
                        this@onDrawWithContent.drawContent()
                        matrix.setScale(1f, fadeHeight)
                        matrix.postTranslate(0f, edge.clearUntil)
                        paint.shader.setLocalMatrix(matrix)
                        canvas.drawRect(-overhang, edge.clearUntil, size.width + overhang, edge.end, paint)
                        canvas.restoreToCount(saved)
                    }
                }
            }
        }
    }.graphicsLayer()
}

/** Stable ordinary boundaries do not observe list motion; XL boundaries follow their scene. */
@Composable
internal fun rememberHomePosterMask(scene: XlSceneState, list: LazyListState, enabled: Boolean,
    ordinaryTop: Dp): State<Pair<Float, Float>?> {
    val density = LocalDensity.current
    return remember(scene, list, enabled, ordinaryTop, density) {
        derivedStateOf {
            with(density) {
                when {
                    // Diagnostic ablation: remove only the shared XL-exit viewport
                    // cutout AND the per-row feather during active downward motion.
                    // The row headroom, focus barrier, and spring are unchanged.
                    // Returning null lets the existing list/row draws proceed without
                    // mask clipping or poster saveLayer during this motion.
                    // Expected: temporary artwork bleed; DO NOT merge as a fix.
                    scene.transition?.downwardExit == true && scene.verticalMotion != null -> null
                    // The viewport retains its original geometry. The feather
                    // occupies its first 32dp, before the 40dp poster anchor.
                    !enabled -> 0f to 0f
                    scene.transition == null && scene.fastReturn == null ->
                        if (scene.selection?.isXl == true) null else 0f to ordinaryTop.toPx()
                    else -> list.layoutInfo.let { info ->
                        xlSceneMetadataMask(scene, info, true, ordinaryTop.toPx(), this.density, info.viewportSize.height.toFloat())
                    }
                }
            }
        }
    }
}
