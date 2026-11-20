package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Stable
internal data class HomePosterFadeViewport(val scene: XlSceneState, val list: LazyListState,
    val enabled: Boolean, val ordinaryTop: Dp)

/** Fade the last visible poster strip without a bitmap surface or destination-in mask. */
@Composable
internal fun Modifier.homePosterFade(viewport: HomePosterFadeViewport?, rowKey: String): Modifier {
    if (viewport == null) return this
    return graphicsLayer {
        compositingStrategy = CompositingStrategy.ModulateAlpha
        val scene = viewport.scene
        val info = viewport.list.layoutInfo
        val owner = info.visibleItemsInfo.firstOrNull { it.key == rowKey }
        val mask = if (viewport.enabled) xlSceneMetadataMask(scene, info, true,
            viewport.ordinaryTop.toPx(), density, info.viewportSize.height.toFloat()) else 0f to HOME_POSTER_FADE_HEIGHT.toPx()
        alpha = if (owner == null || mask == null) 1f else {
            val top = owner.offset + info.beforeContentPadding +
                (scene.headroomValues[rowKey]?.toPx() ?: 0f) + (scene.headerHeights[rowKey] ?: 40.dp.toPx())
            // Artwork above a travelling hero remains part of that scene.
            if (top < mask.first && mask.first > 0f) 1f
            else ((top + size.height - mask.second - 12.dp.toPx()) / HOME_POSTER_FADE_HEIGHT.toPx()).coerceIn(0f, 1f)
        }
    }.drawWithCache {
        val clearance = 12.dp.toPx()
        onDrawWithContent {
            val scene = viewport.scene
            val info = viewport.list.layoutInfo
            val owner = info.visibleItemsInfo.firstOrNull { it.key == rowKey }
            val mask = if (viewport.enabled) xlSceneMetadataMask(scene, info, true,
                viewport.ordinaryTop.toPx(), density, info.viewportSize.height.toFloat()) else 0f to HOME_POSTER_FADE_HEIGHT.toPx()
            if (owner == null || mask == null) drawContent() else {
                val top = owner.offset + info.beforeContentPadding +
                    (scene.headroomValues[rowKey]?.toPx() ?: 0f) + (scene.headerHeights[rowKey] ?: 40.dp.toPx())
                clipRect(top = mask.first - top, bottom = mask.second + clearance - top, clipOp = ClipOp.Difference) {
                    this@onDrawWithContent.drawContent()
                }
            }
        }
    }
}
