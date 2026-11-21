package com.nuvio.tv.ui.screens.home

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.clearAndSetSemantics

internal data class XlMetadataSlot(val rowKey: String, val epoch: Int)

/** Premeasure only XL neighbors; ordinary metadata uses its original simple layout. */
@Composable
internal fun XlMetadataHost(
    activeSlot: XlMetadataSlot,
    activeSnapshot: XlMetadataSnapshot?,
    neighbors: List<XlMetadataSnapshot>,
    nextEpoch: Int,
    modifier: Modifier,
    retainPreparedLayout: Boolean = false,
    content: @Composable (XlMetadataSnapshot, Boolean) -> Unit
) {
    val latestContent by rememberUpdatedState(content)
    val renderers = remember {
        mutableMapOf<XlMetadataSlot, @Composable (XlMetadataSnapshot, Boolean) -> Unit>()
    }
    fun renderer(slot: XlMetadataSlot): @Composable (XlMetadataSnapshot, Boolean) -> Unit =
        renderers.getOrPut(slot) {
            movableContentOf<XlMetadataSnapshot, Boolean> { snapshot, active ->
                Box(if (active) Modifier else Modifier.clearAndSetSemantics { }) { latestContent(snapshot, active) }
            }
        }
    var retained by remember { mutableStateOf<List<Pair<XlMetadataSlot, XlMetadataSnapshot>>>(emptyList()) }
    val requested = neighbors.distinctBy { it.rowKey }.take(2).map { XlMetadataSlot(it.rowKey, nextEpoch) to it }
    val prepared = if (requested.isNotEmpty()) requested else if (retainPreparedLayout) retained else emptyList()
    SideEffect { if (retained != prepared) retained = prepared }
    val entries = buildList {
        if (activeSnapshot != null) add(Triple(activeSlot, activeSnapshot, true))
        prepared.forEach { (key, snapshot) ->
            if (key != activeSlot || activeSnapshot == null) add(Triple(key, snapshot, false))
        }
    }
    val contentBySlot = entries.associate { it.first to renderer(it.first) }
    SideEffect { renderers.keys.retainAll(contentBySlot.keys) }
    if (prepared.isEmpty()) {
        // The same movable content leaves its measured XL slot with logo state
        // intact. No subcomposition remains on the ordinary-row measure path.
        Box(modifier, propagateMinConstraints = true) {
            if (activeSnapshot != null) contentBySlot.getValue(activeSlot)(activeSnapshot, true)
        }
    } else {
        SubcomposeLayout(modifier = modifier) { constraints ->
            val placed = entries.flatMap { (key, snapshot, active) ->
                val children = subcompose(key) { contentBySlot.getValue(key)(snapshot, active) }
                    .map { it.measure(constraints.copy(minHeight = 0)) }
                if (active) children else emptyList()
            }
            val width = (placed.maxOfOrNull { it.width } ?: 0).coerceIn(constraints.minWidth, constraints.maxWidth)
            val height = (placed.maxOfOrNull { it.height } ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(width, height) { placed.forEach { it.place(0, 0) } }
        }
    }
}
