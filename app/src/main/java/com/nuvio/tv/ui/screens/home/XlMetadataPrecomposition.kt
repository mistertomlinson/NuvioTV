package com.nuvio.tv.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.clearAndSetSemantics

internal data class XlMetadataSlot(val rowKey: String, val epoch: Int)

/** Measured neighbors retain logo state, but are never placed, drawn or focusable. */
@Composable
internal fun XlMetadataHost(
    activeSlot: XlMetadataSlot,
    activeSnapshot: XlMetadataSnapshot?,
    neighbors: List<XlMetadataSnapshot>,
    nextEpoch: Int,
    modifier: Modifier,
    content: @Composable (XlMetadataSnapshot, Boolean) -> Unit
) {
    SubcomposeLayout(modifier = modifier) { constraints ->
        val entries = buildList {
            if (activeSnapshot != null) add(Triple(activeSlot, activeSnapshot, true))
            neighbors.distinctBy { it.rowKey }.take(2).forEach {
                val key = XlMetadataSlot(it.rowKey, nextEpoch)
                if (key != activeSlot || activeSnapshot == null) add(Triple(key, it, false))
            }
        }
        val placed = entries.flatMap { (key, snapshot, active) ->
            // One content call site allows the chosen neighbor's existing
            // composition and loaded logo to become the visible slot intact.
            val children = subcompose(key) {
                Box(if (active) Modifier else Modifier.clearAndSetSemantics { }) { content(snapshot, active) }
            }
                .map { it.measure(constraints.copy(minHeight = 0)) }
            if (active) children else emptyList()
        }
        val width = (placed.maxOfOrNull { it.width } ?: 0).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = (placed.maxOfOrNull { it.height } ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) { placed.forEach { it.place(0, 0) } }
    }
}
