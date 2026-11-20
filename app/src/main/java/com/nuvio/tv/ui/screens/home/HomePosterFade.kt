package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Stable
internal data class HomePosterFadeViewport(val scene: XlSceneState, val list: LazyListState,
    val enabled: Boolean, val ordinaryTop: Dp)

/** Fade artwork inside the viewport; row headings and ordinary metadata stay outside this layer. */
@Composable
internal fun Modifier.homePosterFade(viewport: HomePosterFadeViewport?): Modifier {
    if (viewport == null) return this
    val bodyTop = remember(viewport) { mutableFloatStateOf(Float.NaN) }
    return onGloballyPositioned { bodyTop.floatValue = it.positionInRoot().y }.drawWithCache {
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
            val rootTop = bodyTop.floatValue
            val height = scene.rowsViewportHeightPx.takeIf { it > 0f } ?: scene.screenHeight.toPx()
            val mask = if (viewport.enabled) xlSceneMetadataMask(scene, viewport.list.layoutInfo, true,
                viewport.ordinaryTop.toPx(), density, height) else 0f to fadeHeight
            if (mask == null || rootTop.isNaN()) {
                drawContent()
                return@onDrawWithContent
            }
            val maskTop = scene.rowsViewportTopPx + mask.first - rootTop
            val clearUntil = scene.rowsViewportTopPx + mask.second + clearance - rootTop
            val fadeEnd = clearUntil + fadeHeight
            if (fadeEnd < -overhang || maskTop > size.height + overhang) {
                drawContent()
                return@onDrawWithContent
            }
            // Last poster pixels become fully transparent before reaching the
            // cutoff. This also covers number overhang, outlines and shadows.
            clipRect(top = maskTop, bottom = fadeEnd, clipOp = ClipOp.Difference) {
                this@onDrawWithContent.drawContent()
            }
            clipRect(top = clearUntil, bottom = fadeEnd) {
                val canvas = drawContext.canvas.nativeCanvas
                val saved = canvas.saveLayer(0f, clearUntil, size.width, fadeEnd, null)
                this@onDrawWithContent.drawContent()
                matrix.setScale(1f, fadeHeight)
                matrix.postTranslate(0f, clearUntil)
                paint.shader.setLocalMatrix(matrix)
                canvas.drawRect(0f, clearUntil, size.width, fadeEnd, paint)
                canvas.restoreToCount(saved)
            }
        }
    }
}
