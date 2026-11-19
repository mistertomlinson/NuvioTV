package com.nuvio.tv.ui.screens.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal data class XlSceneSelection(
    val rowKey: String,
    val itemKey: String,
    val rowIndex: Int,
    val isXl: Boolean,
    val summary: Boolean,
    val preview: HeroPreview
)

internal fun HeroCarouselRow.sceneSelection(index: Int, rowIndex: Int): XlSceneSelection? =
    items.getOrNull(index.coerceIn(0, items.lastIndex.coerceAtLeast(0)))?.let {
        XlSceneSelection(key, it.key, rowIndex, isXl, showXlSummary, it.heroPreview)
    }

/** Follows measured scroll displacement even after the original row is recycled. */
internal class XlScrollAnchor(initialPositions: Map<Any, Int>) {
    private var positions = initialPositions
    private var displacement = 0f

    fun displacement(current: Map<Any, Int>): Float {
        current.entries.firstOrNull { it.key in positions }?.let {
            displacement += it.value - checkNotNull(positions[it.key])
        }
        positions = current
        return displacement
    }
}

private fun LazyListLayoutInfo.rowPositions(): Map<Any, Int> =
    visibleItemsInfo.associate { it.key to it.offset + beforeContentPadding }

internal data class XlMetadataSnapshot(val rowKey: String, val preview: HeroPreview)

internal data class XlSceneTransition(
    val id: Int,
    val from: XlSceneSelection,
    val to: XlSceneSelection,
    val outgoingMetadata: XlMetadataSnapshot?,
    val outgoingBackdrop: HeroPreview?,
    val scrollAnchor: XlScrollAnchor,
    val initialMetadataTranslation: Float,
    val initialMetadataAlpha: Float,
    val outgoingMetadataEpoch: Int,
    val keepMetadataVisible: Boolean,
    val rowsSettled: Boolean = false
) {
    val compactExit = from.isXl && !from.summary && !to.isXl && to.rowIndex > from.rowIndex
    val deferOrdinaryReveal = !keepMetadataVisible && (compactExit || outgoingMetadata != null && !to.isXl)
}

@Stable
internal class XlSceneState(initial: XlSceneSelection? = null, rows: List<HeroCarouselRow> = emptyList()) {
    var selection by mutableStateOf(initial)
        private set
    var transition by mutableStateOf<XlSceneTransition?>(null)
        private set
    var metadataEpoch by mutableIntStateOf(0)
        private set
    private var generation = 0
    private var paintedMetadata: XlMetadataSnapshot? = null
    private var paintedTranslation = 0f
    private var paintedAlpha = 0f
    private var paintedEpoch = 0
    private var paintedBackdrop: HeroPreview? = null
    val expandedCompactRows = mutableStateMapOf<String, Boolean>()
    val headroomValues = mutableStateMapOf<String, Dp>()
    val headroomMoving = mutableStateMapOf<String, Boolean>()

    init { initial?.let { openEarlierCompactGaps(it, rows) } }

    private fun openEarlierCompactGaps(next: XlSceneSelection, rows: List<HeroCarouselRow>) {
        for (index in 0 until next.rowIndex.coerceIn(0, rows.size)) {
            val row = rows[index]
            if (row.isXl && !row.showXlSummary) expandedCompactRows[row.key] = true
        }
    }

    fun recordMetadata(snapshot: XlMetadataSnapshot, translation: Float = 0f, alpha: Float = 1f, epoch: Int = metadataEpoch) {
        paintedMetadata = snapshot
        paintedTranslation = translation
        paintedAlpha = alpha
        paintedEpoch = epoch
    }

    fun hideMetadata() { paintedAlpha = 0f }

    fun recordBackdrop(preview: HeroPreview?) { paintedBackdrop = preview }

    fun focus(next: XlSceneSelection, layout: LazyListLayoutInfo, animate: Boolean = true, rows: List<HeroCarouselRow> = emptyList()) {
        val previous = selection
        val previousMove = transition
        if (previous?.rowKey != next.rowKey) openEarlierCompactGaps(next, rows)
        selection = next
        if (previous?.rowKey == next.rowKey) return
        val carrying = previousMove != null && paintedAlpha > 0f && paintedMetadata != null &&
            (paintedMetadata?.rowKey == previous?.rowKey || paintedMetadata?.rowKey == previousMove.outgoingMetadata?.rowKey)
        if (!animate || previous == null || (!previous.isXl && !next.isXl && !carrying)) {
            transition = null
            if (next.isXl) expandedCompactRows.remove(next.rowKey)
            return
        }
        val sameOwnerReturn = paintedMetadata?.rowKey == next.rowKey && paintedAlpha > 0f
        val outgoing = if (!sameOwnerReturn && (!previous.isXl || carrying)) {
            paintedMetadata?.takeIf { paintedAlpha > 0f && (it.rowKey == previous.rowKey || carrying) }
        } else null
        if (sameOwnerReturn) metadataEpoch = paintedEpoch
        else if (!next.isXl && (previous.isXl || carrying)) metadataEpoch++
        val move = XlSceneTransition(
            ++generation, previous, next, outgoing, paintedBackdrop ?: previous.preview, XlScrollAnchor(layout.rowPositions()),
            if (outgoing == paintedMetadata) paintedTranslation else 0f,
            if (outgoing == paintedMetadata) paintedAlpha else 0f,
            paintedEpoch, sameOwnerReturn
        )
        transition = move
        if (move.compactExit) expandedCompactRows[previous.rowKey] = true
    }

    fun rowsSettled(id: Int) {
        transition?.takeIf { it.id == id }?.let { transition = it.copy(rowsSettled = true) }
    }

    fun complete(id: Int) {
        if (transition?.id != id) return
        transition = null
        // Close the preview gap only after the outgoing ordinary metadata has
        // left the screen. It cannot reappear while the next-row peek returns.
        selection?.takeIf { it.isXl }?.let { expandedCompactRows.remove(it.rowKey) }
    }

    fun outgoingTranslation(layout: LazyListLayoutInfo): Float = transition?.let {
        it.initialMetadataTranslation + it.scrollAnchor.displacement(layout.rowPositions())
    } ?: 0f

    fun incomingTranslation(layout: LazyListLayoutInfo, ordinaryTopPx: Float, density: Float): Float? {
        val key = selection?.rowKey ?: return null
        val owner = layout.visibleItemsInfo.firstOrNull { it.key == key } ?: return null
        return owner.offset + layout.beforeContentPadding + (headroomValues[key]?.value ?: 0f) * density - ordinaryTopPx
    }
}

internal fun xlSceneOutgoingMetadataVisible(scene: XlSceneState, layout: LazyListLayoutInfo,
    ordinaryTopPx: Float, viewportHeightPx: Float): Boolean {
    val move = scene.transition ?: return false
    if (move.outgoingMetadata == null || move.rowsSettled) return false
    val offset = scene.outgoingTranslation(layout)
    return offset < viewportHeightPx && offset + ordinaryTopPx > 0f
}

internal fun xlSceneMetadataMask(scene: XlSceneState, layout: LazyListLayoutInfo, enabled: Boolean,
    ordinaryTopPx: Float, density: Float, viewportHeightPx: Float = Float.POSITIVE_INFINITY): Pair<Float, Float>? {
    if (!enabled) return null
    val move = scene.transition
    val offset = when {
        xlSceneOutgoingMetadataVisible(scene, layout, ordinaryTopPx, viewportHeightPx) -> scene.outgoingTranslation(layout)
        scene.selection?.isXl == true -> return null
        move?.deferOrdinaryReveal == true -> if (move.rowsSettled) 0f else return null
        move != null -> scene.incomingTranslation(layout, ordinaryTopPx, density) ?: return null
        else -> 0f
    }
    return offset to offset + ordinaryTopPx
}

/** Keep the hero's empty space attached to its row; never uncover an older row behind it. */
internal fun Modifier.xlSceneRowsViewport(scene: XlSceneState, list: LazyListState, enabled: Boolean,
    ordinaryTop: Dp): Modifier = drawWithContent {
    val mask = xlSceneMetadataMask(scene, list.layoutInfo, enabled, ordinaryTop.toPx(), density, size.height)
    clipRect {
        val top = mask?.first?.coerceAtLeast(0f) ?: 0f
        val bottom = mask?.second?.coerceAtMost(size.height) ?: 0f
        if (top < bottom) clipRect(top = top, bottom = bottom, clipOp = ClipOp.Difference) {
            this@drawWithContent.drawContent()
        } else this@drawWithContent.drawContent()
    }
}

/** The real gap grows during a compact downward exit, so XL can leave the screen. */
@Composable
internal fun XlSceneRowHeadroom(
    scene: XlSceneState,
    rowKey: String,
    previous: HeroCarouselRow?,
    currentXl: Boolean,
    fullHeadroom: Dp
) {
    val followsXl = previous?.isXl == true && !currentXl
    val compact = followsXl && previous?.showXlSummary == false
    val target = when {
        // The preceding row must be entirely above y=0 when XL's heading lands
        // at y=54: the shared 24dp gap plus this inset supplies those 54dp.
        currentXl && previous != null -> 30.dp
        followsXl && (!compact || previous?.key?.let { scene.expandedCompactRows[it] } == true) -> fullHeadroom
        else -> 0.dp
    }
    val animated = animateDpAsState(target, tween(360), label = "xlMetadataHeadroom")
    val height = if (compact) animated.value else target
    SideEffect {
        scene.headroomValues[rowKey] = height
        scene.headroomMoving[rowKey] = height != target
    }
    DisposableEffect(scene, rowKey) {
        onDispose { scene.headroomValues.remove(rowKey); scene.headroomMoving.remove(rowKey) }
    }
    Spacer(Modifier.height(height).testTag("xl-headroom-$rowKey"))
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
internal fun ObserveXlSceneMotion(scene: XlSceneState, list: LazyListState) {
    val id = scene.transition?.id
    LaunchedEffect(scene, list, id) {
        if (id == null) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        snapshotFlow { !list.isScrollInProgress && scene.headroomMoving.values.none { it } }
            .debounce(80L).first { it }
        scene.rowsSettled(id)
    }
}

@Stable
internal class XlBackdropPresentation(initial: HeroPreview?, initiallyXl: Boolean) {
    val dim = Animatable(if (initiallyXl) XL_BACKDROP_DIM_ALPHA else 0f)
    var preview by mutableStateOf(initial)
    var owned by mutableStateOf(initiallyXl)
    var revealing by mutableStateOf(false)
    var token by mutableIntStateOf(0)
    private var renderedToken by mutableIntStateOf(-1)
    private var expectedUrl by mutableStateOf<String?>(null)
    private var readyToken by mutableIntStateOf(-1)
    private var readyUrl by mutableStateOf<String?>(null)

    fun expectFrame(renderToken: Int, url: String?) {
        if (expectedUrl == url && readyToken == renderedToken && readyUrl == url) readyToken = renderToken
        renderedToken = renderToken
        expectedUrl = url
    }

    fun frameReady(renderToken: Int, url: String?) {
        readyToken = renderToken
        readyUrl = url
    }

    fun ready(): Boolean = renderedToken == token && readyToken == token && readyUrl == expectedUrl
}

/** Swap while black, wait for the actual frame and row landing, then reveal together. */
@Composable
internal fun rememberXlBackdropPresentation(
    scene: XlSceneState,
    enabled: Boolean,
    resolvedHero: HeroPreview?,
    carouselFocused: Boolean
): XlBackdropPresentation {
    val selected = scene.selection
    val state = remember(scene) { XlBackdropPresentation(resolvedHero, selected?.isXl == true && !carouselFocused) }
    val latestResolved by rememberUpdatedState(resolvedHero)
    val selectionKey = selected?.let { it.rowKey to it.itemKey }
    LaunchedEffect(scene, selectionKey, selected?.preview?.backdrop, selected?.preview?.imageUrl,
        selected?.preview?.poster, carouselFocused, enabled) {
        val target = selected ?: return@LaunchedEffect
        val boundaryId = scene.transition?.id
        val dark = enabled && target.isXl && !carouselFocused
        if (!enabled) {
            state.owned = false
            state.dim.snapTo(0f)
            if (boundaryId != null) scene.complete(boundaryId)
            return@LaunchedEffect
        }
        if (!target.isXl && boundaryId == null) {
            state.owned = false
            state.dim.animateTo(0f, tween(280))
            return@LaunchedEffect
        }
        if (!state.owned && !dark && boundaryId == null) return@LaunchedEffect
        if (!state.owned) state.preview = scene.transition?.outgoingBackdrop ?: latestResolved
        state.owned = true
        state.revealing = false
        if (state.preview != target.preview || boundaryId != null) {
            // A true black beat masks both the outgoing frame and image loading.
            state.dim.animateTo(1f, tween(if (state.dim.value > 0.9f) 80 else 240))
            state.token++
            state.preview = target.preview
            // Let the renderer bind the committed token before checking readiness.
            repeat(2) { withFrameNanos { } }
            withTimeoutOrNull(2_000L) { snapshotFlow { state.ready() }.first { it } }
        }
        if (boundaryId != null) {
            snapshotFlow { scene.transition?.let { it.id != boundaryId || it.rowsSettled } ?: true }.first { it }
        }
        state.revealing = true
        state.dim.animateTo(if (dark) XL_BACKDROP_DIM_ALPHA else 0f, tween(280))
        if (boundaryId != null) scene.complete(boundaryId)
        if (!dark && latestResolved == target.preview) state.owned = false
    }
    LaunchedEffect(resolvedHero, state.owned, scene.transition, selected?.isXl) {
        if (scene.transition == null && selected?.isXl != true && state.dim.value == 0f &&
            resolvedHero == selected?.preview) state.owned = false
    }
    return state
}

/** Exactly one ordinary block; XL's own metadata remains inside its scrolling row. */
@Composable
internal fun ModernXlAwareHeroTitleBlock(
    scene: XlSceneState,
    backdrop: XlBackdropPresentation,
    enabled: Boolean,
    resolvedHero: HeroPreview?,
    list: LazyListState,
    ordinaryTopPx: Float,
    density: Float,
    portraitMode: Boolean,
    selectedPlatformId: String,
    platformNavDirection: Int,
    platformTransitionSnap: Boolean,
    fullWidthIconRowEnabled: Boolean,
    heroMetadataLarge: Boolean,
    modifier: Modifier
) {
    val selected = scene.selection
    val move = scene.transition
    val viewportHeight = LocalConfiguration.current.screenHeightDp * density
    val outgoing by remember(scene, list, enabled, ordinaryTopPx, viewportHeight) {
        derivedStateOf { enabled && xlSceneOutgoingMetadataVisible(scene, list.layoutInfo, ordinaryTopPx, viewportHeight) }
    }
    val snapshot = when {
        !enabled -> resolvedHero?.let { XlMetadataSnapshot(selected?.rowKey.orEmpty(), it) }
        outgoing -> move?.outgoingMetadata
        selected?.isXl == true -> null
        move != null -> selected?.let { XlMetadataSnapshot(it.rowKey, it.preview) }
        else -> (if (backdrop.owned) selected?.preview else resolvedHero)?.let {
            XlMetadataSnapshot(selected?.rowKey.orEmpty(), it)
        }
    }
    if (snapshot == null) {
        SideEffect { scene.hideMetadata() }
        return
    }
    val epoch = if (outgoing) move?.outgoingMetadataEpoch ?: scene.metadataEpoch else scene.metadataEpoch
    key(epoch) {
        HeroTitleBlock(
            preview = snapshot.preview,
            portraitMode = portraitMode,
            selectedPlatformId = selectedPlatformId,
            platformNavDirection = platformNavDirection,
            platformTransitionSnap = platformTransitionSnap,
            fullWidthIconRowEnabled = fullWidthIconRowEnabled,
            heroMetadataLarge = heroMetadataLarge,
            modifier = modifier.testTag("ordinary-metadata-${snapshot.rowKey}").graphicsLayer {
                val incomingOffset = if (enabled && move != null && !outgoing && !move.deferOrdinaryReveal)
                    scene.incomingTranslation(list.layoutInfo, ordinaryTopPx, density) else 0f
                translationY = when {
                    outgoing -> scene.outgoingTranslation(list.layoutInfo)
                    enabled && move != null && !move.deferOrdinaryReveal -> incomingOffset ?: 0f
                    else -> 0f
                }
                alpha = if (enabled && move?.deferOrdinaryReveal == true && !outgoing) {
                    if (move.rowsSettled && backdrop.revealing) (1f - backdrop.dim.value).coerceIn(0f, 1f) else 0f
                } else if (outgoing) move?.initialMetadataAlpha ?: 0f else if (incomingOffset == null) 0f else 1f
                scene.recordMetadata(snapshot, translationY, alpha, epoch)
            }
        )
    }
    SideEffect { if (move == null) scene.recordMetadata(snapshot) }
}
