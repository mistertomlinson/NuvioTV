package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Stable
internal data class HomePosterFadeViewport(val scene: XlSceneState, val list: LazyListState,
    val enabled: Boolean, val ordinaryTop: Dp)

/** Fade artwork inside the viewport; row headings and ordinary metadata stay outside this layer. */
@Composable
internal fun Modifier.homePosterFade(viewport: HomePosterFadeViewport?, rowKey: String): Modifier {
    if (viewport == null) return this
    return drawWithCache {
        val paint = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 0f, 1f,
                android.graphics.Color.TRANSPARENT, android.graphics.Color.WHITE, android.graphics.Shader.TileMode.CLAMP)
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        }
        val matrix = android.graphics.Matrix()
        val fadeHeight = HOME_POSTER_FADE_HEIGHT.toPx()
        val clearance = 12.dp.toPx()
        val overhang = 48.dp.toPx()
        onDrawWithContent {
            val scene = viewport.scene
            val layout = viewport.list.layoutInfo
            val owner = layout.visibleItemsInfo.firstOrNull { it.key == rowKey }
            val mask = if (viewport.enabled) xlSceneMetadataMask(scene, layout, true,
                viewport.ordinaryTop.toPx(), density, layout.viewportSize.height.toFloat()) else 0f to fadeHeight
            if (mask == null || owner == null) {
                drawContent()
                return@onDrawWithContent
            }
            // Read the list's existing layout during drawing. No per-row
            // global-position callbacks or snapshot writes on every scroll frame.
            val bodyTop = owner.offset + layout.beforeContentPadding +
                (scene.headroomValues[rowKey]?.toPx() ?: 0f) + (scene.headerHeights[rowKey] ?: 40.dp.toPx())
            val maskTop = mask.first - bodyTop
            val clearUntil = mask.second + clearance - bodyTop
            val fadeEnd = clearUntil + fadeHeight
            if (fadeEnd <= 0f || maskTop > size.height + overhang) {
                drawContent()
                return@onDrawWithContent
            }
            if (maskTop <= -overhang && clearUntil >= size.height + overhang) return@onDrawWithContent
            // Draw the row once. The temporary surface is clipped to the
            // visible body; apply the alpha ramp only inside the soft strip.
            clipRect(top = maskTop, bottom = clearUntil, clipOp = ClipOp.Difference) {
                val canvas = drawContext.canvas.nativeCanvas
                val saved = canvas.saveLayer(-overhang, -overhang, size.width + overhang, size.height + overhang, null)
                this@onDrawWithContent.drawContent()
                clipRect(top = clearUntil, bottom = fadeEnd) {
                    matrix.setScale(1f, fadeHeight)
                    matrix.postTranslate(0f, clearUntil)
                    paint.shader.setLocalMatrix(matrix)
                    canvas.drawRect(0f, clearUntil, size.width, fadeEnd, paint)
                }
                canvas.restoreToCount(saved)
            }
        }
    }
}
