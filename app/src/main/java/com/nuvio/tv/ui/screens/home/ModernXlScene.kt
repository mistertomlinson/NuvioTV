package com.nuvio.tv.ui.screens.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal const val XL_BACKDROP_REVEAL_MS = 180
internal val HOME_POSTER_FADE_HEIGHT = 20.dp

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
        XlSceneSelection(key, it.key, rowIndex, isXl, showXlSummary,
            if (isXl) it.xlBackdropPreview() else it.heroPreview)
    }

/** Never resurrect XL's retained date in the ordinary badge during its exit. */
internal fun ordinaryComingSoonText(
    scene: XlSceneState,
    backdrop: XlBackdropPresentation,
    resolvedHero: HeroPreview?,
    activeRowIsXl: Boolean
): String? {
    if (activeRowIsXl || scene.selection?.isXl == true) return null
    val move = scene.transition
    if (move != null && !move.rowsSettled) return null
    val managed = move != null || backdrop.owned || scene.stationaryMetadata != null
    return (if (managed) scene.selection?.preview else resolvedHero)?.comingSoonText?.takeIf { it.isNotBlank() }
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
    val outgoingMetadataClipTop: Float = 0f,
    val rowsSettled: Boolean = false
) {
    val downwardExit = from.isXl && !to.isXl && to.rowIndex > from.rowIndex
    val compactExit = downwardExit && !from.summary
    val deferOrdinaryReveal = !keepMetadataVisible && outgoingMetadata != null && !to.isXl
}

@Stable
internal class XlSceneState(initial: XlSceneSelection? = null, rows: List<HeroCarouselRow> = emptyList()) {
    val artwork = XlArtworkPresentation()
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
    private var paintedClipTop = 0f
    private var paintedBackdrop: HeroPreview? = null
    val expandedXlRows = mutableStateMapOf<String, Boolean>()
    val headroomValues = mutableStateMapOf<String, Dp>()
    val headroomMoving = mutableStateMapOf<String, Boolean>()
    val headerHeights = mutableStateMapOf<String, Float>()
    val rowHeights = mutableStateMapOf<String, Dp>()
    val summaryRowHeights = mutableStateMapOf<String, Dp>()
    var screenHeight by mutableStateOf(540.dp)
    var fullWidthIcons by mutableStateOf(false)
    var estimatedXlHeight by mutableStateOf(440.dp)
    var ordinaryTop by mutableStateOf(260.dp)
    var metadataRestingTop by mutableStateOf(54.dp)
    var metadataHeightPx by mutableFloatStateOf(0f)
    var rowsViewportTopPx by mutableFloatStateOf(0f)
    var rowsViewportHeightPx by mutableFloatStateOf(0f)
    var stationaryMetadata by mutableStateOf<XlMetadataSnapshot?>(null)
        private set

    fun xlTop(rowKey: String): Dp = xlBalancedTop(screenHeight.value,
        (summaryRowHeights[rowKey] ?: estimatedXlHeight).value, fullWidthIcons).dp

    fun followingHeadroom(previousKey: String, rowKey: String, density: Float, clearXl: Boolean): Dp {
        val bottom = xlTop(previousKey) + (rowHeights[previousKey] ?: estimatedXlHeight)
        return xlFollowingHeadroom(screenHeight.value, bottom.value, ordinaryTop.value,
            metadataRestingTop.value, (headerHeights[rowKey] ?: 40f * density) / density - 40f, clearXl).dp
    }

    fun resolveStationaryMetadata(preview: HeroPreview?) {
        if (selection?.isXl == false && selection?.preview == preview) stationaryMetadata = null
    }

    init { initial?.let { openEarlierXlGaps(it, rows) } }

    private fun openEarlierXlGaps(next: XlSceneSelection, rows: List<HeroCarouselRow>) {
        for (index in 0 until next.rowIndex.coerceIn(0, rows.size)) {
            val row = rows[index]
            if (row.isXl) expandedXlRows[row.key] = true
        }
    }

    fun recordMetadata(snapshot: XlMetadataSnapshot, translation: Float = 0f, alpha: Float = 1f,
        epoch: Int = metadataEpoch, clipTop: Float = 0f) {
        paintedMetadata = snapshot
        paintedTranslation = translation
        paintedAlpha = alpha
        paintedEpoch = epoch
        paintedClipTop = clipTop
    }

    fun hideMetadata() { paintedAlpha = 0f }

    fun recordBackdrop(preview: HeroPreview?) { paintedBackdrop = preview }

    fun focus(next: XlSceneSelection, layout: LazyListLayoutInfo, animate: Boolean = true, rows: List<HeroCarouselRow> = emptyList()) {
        val previous = selection
        val previousMove = transition
        val heldMetadata = stationaryMetadata
        if (previous?.rowKey != next.rowKey) openEarlierXlGaps(next, rows)
        selection = next
        if (previous?.rowKey == next.rowKey) return
        // Once an XL exit reaches a second ordinary row, that hero resumes the
        // normal stationary presentation. Never drag the first incoming logo
        // down with an unrelated row on a rapid second Up.
        if (animate && previous != null && !previous.isXl && !next.isXl &&
            (previousMove?.from?.isXl == true && previousMove.outgoingMetadata == null || stationaryMetadata != null)) {
            // The first XL exit is immediate. Further ordinary navigation uses
            // the normal resolved/debounced hero, holding the last painted block.
            if (stationaryMetadata == null) stationaryMetadata = paintedMetadata
                ?.takeIf { paintedAlpha > 0f && it.rowKey == previous.rowKey }
                ?: XlMetadataSnapshot(previous.rowKey, previous.preview)
            transition = null
            return
        }
        stationaryMetadata = null
        val carrying = paintedAlpha > 0f && paintedMetadata != null &&
            (previousMove != null && (paintedMetadata?.rowKey == previous?.rowKey ||
                paintedMetadata?.rowKey == previousMove.outgoingMetadata?.rowKey) ||
                heldMetadata != null && paintedMetadata?.rowKey == heldMetadata.rowKey)
        if (!animate || previous == null || (!previous.isXl && !next.isXl && !carrying)) {
            transition = null
            if (next.isXl) expandedXlRows.remove(next.rowKey)
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
            paintedEpoch, sameOwnerReturn, if (outgoing == paintedMetadata) paintedClipTop else 0f
        )
        transition = move
        if (move.downwardExit) expandedXlRows[previous.rowKey] = true
    }

    fun rowsSettled(id: Int) {
        transition?.takeIf { it.id == id }?.let { transition = it.copy(rowsSettled = true) }
    }

    fun complete(id: Int) {
        if (transition?.id != id) return
        val move = transition
        transition = null
        if (move?.from?.isXl == true && selection?.isXl == false) {
            val selected = checkNotNull(selection)
            stationaryMetadata = paintedMetadata?.takeIf { paintedAlpha > 0f && it.rowKey == selected.rowKey }
                ?: XlMetadataSnapshot(selected.rowKey, selected.preview)
        }
        // Close the preview gap only after the outgoing ordinary metadata has
        // left the screen. It cannot reappear while the next-row peek returns.
        selection?.takeIf { it.isXl }?.let { expandedXlRows.remove(it.rowKey) }
    }

    fun outgoingTranslation(layout: LazyListLayoutInfo): Float = transition?.let {
        it.initialMetadataTranslation + it.scrollAnchor.displacement(layout.rowPositions())
    } ?: 0f

    fun incomingTranslation(layout: LazyListLayoutInfo, ordinaryTopPx: Float, density: Float): Float? {
        val key = selection?.rowKey ?: return null
        val owner = layout.visibleItemsInfo.firstOrNull { it.key == key } ?: return null
        // BringIntoView aligns the focused poster at ordinaryTop + 40dp. A
        // measured heading may be a few pixels taller/shorter than that inset.
        // Use the very same poster anchor so the final moving frame is zero.
        return owner.offset + layout.beforeContentPadding + (headroomValues[key]?.value ?: 0f) * density +
            (headerHeights[key] ?: 40f * density) - ordinaryTopPx - 40f * density
    }

    fun outgoingXlBottom(layout: LazyListLayoutInfo): Float? {
        val move = transition?.takeIf { it.downwardExit } ?: return null
        val owner = layout.visibleItemsInfo.firstOrNull { it.key == move.from.rowKey } ?: return 0f
        return (owner.offset + layout.beforeContentPadding + owner.size).toFloat().coerceAtLeast(0f)
    }
}

internal fun xlSceneOutgoingMetadataVisible(scene: XlSceneState, layout: LazyListLayoutInfo,
    ordinaryTopPx: Float, viewportHeightPx: Float, density: Float = 1f): Boolean {
    val move = scene.transition ?: return false
    if (move.outgoingMetadata == null || move.rowsSettled) return false
    val offset = scene.outgoingTranslation(layout)
    val clip = if (move.outgoingMetadataClipTop > 0f)
        scene.metadataRestingTop.value * density + move.outgoingMetadataClipTop else 0f
    return offset + clip < viewportHeightPx && offset + ordinaryTopPx > 0f && clip < ordinaryTopPx
}

internal fun xlSceneMetadataMask(scene: XlSceneState, layout: LazyListLayoutInfo, enabled: Boolean,
    ordinaryTopPx: Float, density: Float, viewportHeightPx: Float = Float.POSITIVE_INFINITY): Pair<Float, Float>? {
    if (!enabled) return null
    val move = scene.transition
    val offset = when {
        xlSceneOutgoingMetadataVisible(scene, layout, ordinaryTopPx, viewportHeightPx, density) -> scene.outgoingTranslation(layout)
        scene.selection?.isXl == true -> return null
        move?.deferOrdinaryReveal == true -> if (move.rowsSettled) 0f else return null
        move != null -> scene.incomingTranslation(layout, ordinaryTopPx, density) ?: return null
        else -> 0f
    }
    val showingOutgoing = xlSceneOutgoingMetadataVisible(scene, layout, ordinaryTopPx, viewportHeightPx, density)
    val edge = if (showingOutgoing && move != null && move.outgoingMetadataClipTop > 0f)
        offset + scene.metadataRestingTop.value * density + move.outgoingMetadataClipTop
        else if (showingOutgoing) null else scene.outgoingXlBottom(layout)
    val top = edge?.let { maxOf(offset, it) } ?: offset
    return if (top < offset + ordinaryTopPx) top to offset + ordinaryTopPx else null
}

/** Keep the hero's empty space attached to its row; never uncover an older row behind it. */
internal fun Modifier.xlSceneRowsViewport(scene: XlSceneState, list: LazyListState, enabled: Boolean,
    ordinaryTop: Dp): Modifier = onGloballyPositioned {
        scene.rowsViewportTopPx = it.positionInRoot().y
        scene.rowsViewportHeightPx = it.size.height.toFloat()
    }.drawWithCache {
    val fadeHeight = HOME_POSTER_FADE_HEIGHT.toPx()
    val fadePaint = android.graphics.Paint().apply {
        shader = android.graphics.LinearGradient(0f, 0f, 0f, 1f,
            android.graphics.Color.TRANSPARENT, android.graphics.Color.WHITE, android.graphics.Shader.TileMode.CLAMP)
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
    }
    val matrix = android.graphics.Matrix()
    val itemClip = Path()
    onDrawWithContent {
        val mask = if (enabled) xlSceneMetadataMask(scene, list.layoutInfo, true, ordinaryTop.toPx(), density, size.height)
            else 0f to fadeHeight
        clipRect {
            val top = mask?.first?.coerceAtLeast(0f) ?: 0f
            val bottom = mask?.second?.coerceAtMost(size.height) ?: 0f
            if (top < bottom) {
                clipRect(top = top, bottom = bottom, clipOp = ClipOp.Difference) {
                    this@onDrawWithContent.drawContent()
                }
                // Only this narrow band needs an offscreen surface. Keep the
                // backdrop outside it untouched, and never mask departing XL.
                val fadeTop = maxOf(top, bottom - fadeHeight)
                // A focused card can draw beyond its row's measured bottom.
                // Do not reintroduce that overhang in the empty gap after the
                // row has cleared the cutoff; it leaves a stationary sliver.
                itemClip.reset()
                val layout = list.layoutInfo
                if (layout.visibleItemsInfo.isEmpty()) itemClip.addRect(Rect(0f, fadeTop, size.width, bottom))
                else layout.visibleItemsInfo.forEach { item ->
                    val itemTop = (item.offset + layout.beforeContentPadding).toFloat()
                    val from = maxOf(fadeTop, itemTop)
                    val to = minOf(bottom, itemTop + item.size)
                    if (from < to) itemClip.addRect(Rect(0f, from, size.width, to))
                }
                if (!itemClip.isEmpty) clipPath(itemClip) {
                    val canvas = drawContext.canvas.nativeCanvas
                    val layer = canvas.saveLayer(0f, fadeTop, size.width, bottom, null)
                    this@onDrawWithContent.drawContent()
                    matrix.setScale(1f, bottom - fadeTop)
                    matrix.postTranslate(0f, fadeTop)
                    fadePaint.shader.setLocalMatrix(matrix)
                    canvas.drawRect(0f, fadeTop, size.width, bottom, fadePaint)
                    canvas.restoreToCount(layer)
                }
            } else this@onDrawWithContent.drawContent()
        }
    }
}

/** Start the next hero just off screen, then grow enough space for XL to clear. */
@Composable
internal fun XlSceneRowHeadroom(
    scene: XlSceneState,
    rowKey: String,
    previous: HeroCarouselRow?,
    currentXl: Boolean
) {
    val followsXl = previous?.isXl == true && !currentXl
    val compact = followsXl && previous?.showXlSummary == false
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val expanded = previous?.key?.let { scene.expandedXlRows[it] } == true
    val target = when {
        // The gap and prefix together clear the previous row beyond y=0 at
        // XL's measured landing position, including the balanced full-width mode.
        currentXl && previous != null -> (scene.xlTop(rowKey) - 24.dp + 1.dp).coerceAtLeast(0.dp)
        followsXl && (expanded || !compact) ->
            scene.followingHeadroom(checkNotNull(previous).key, rowKey, density, clearXl = expanded)
        else -> 0.dp
    }
    val animated = animateDpAsState(target, tween(360), label = "xlMetadataHeadroom")
    val height = if (followsXl) animated.value else target
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
    val gradientAlpha = Animatable(if (initiallyXl) 0f else 1f)
    var preview by mutableStateOf(initial)
    var owned by mutableStateOf(initiallyXl)
    var revealing by mutableStateOf(false)
    var token by mutableIntStateOf(0)
    var blurred by mutableStateOf(initiallyXl)
    var directEntry by mutableStateOf(false)
    private var renderedToken by mutableIntStateOf(-1)
    private var expectedUrl by mutableStateOf<String?>(null)
    private var readyToken by mutableIntStateOf(-1)
    private var readyUrl by mutableStateOf<String?>(null)
    private var expectedBlurred by mutableStateOf(false)
    private var readyBlurred by mutableStateOf(false)

    fun expectFrame(renderToken: Int, url: String?, blurred: Boolean = this.blurred) {
        if (expectedUrl == url && expectedBlurred == blurred && readyToken == renderedToken && readyUrl == url &&
            readyBlurred == blurred) readyToken = renderToken
        renderedToken = renderToken
        expectedUrl = url
        expectedBlurred = blurred
    }

    fun frameReady(renderToken: Int, url: String?, blurred: Boolean = this.blurred) {
        readyToken = renderToken
        readyUrl = url
        readyBlurred = blurred
    }

    fun ready(): Boolean = renderedToken == token && readyToken == token && readyUrl == expectedUrl &&
        readyBlurred == expectedBlurred
}

/** Upward XL entry crossfades directly; an XL exit reveals its destination during the scroll. */
@Composable
internal fun rememberXlBackdropPresentation(
    scene: XlSceneState,
    enabled: Boolean,
    resolvedHero: HeroPreview?,
    carouselFocused: Boolean,
    pageStartsWithXl: Boolean = false
): XlBackdropPresentation {
    val selected = scene.selection
    val darkTarget = enabled && if (carouselFocused) pageStartsWithXl else selected?.isXl == true
    val state = remember(scene) { XlBackdropPresentation(if (darkTarget) selected?.preview ?: resolvedHero else resolvedHero, darkTarget) }
    val latestResolved by rememberUpdatedState(resolvedHero)
    val selectionKey = selected?.let { it.rowKey to it.itemKey }
    LaunchedEffect(scene, selectionKey, selected?.preview?.backdrop, selected?.preview?.imageUrl,
        selected?.preview?.poster, carouselFocused, pageStartsWithXl, enabled) {
        val target = selected ?: return@LaunchedEffect
        val boundary = scene.transition
        val boundaryId = boundary?.id
        val dark = enabled && if (carouselFocused) pageStartsWithXl else target.isXl
        val exitingXl = !dark && (state.blurred || boundary?.from?.isXl == true)
        state.directEntry = false
        if (!enabled) {
            state.owned = false
            state.blurred = false
            state.dim.snapTo(0f)
            state.gradientAlpha.snapTo(1f)
            if (boundaryId != null) scene.complete(boundaryId)
            return@LaunchedEffect
        }
        if (!target.isXl && !dark && boundaryId == null && !state.blurred) {
            if (scene.stationaryMetadata != null) {
                state.preview = scene.stationaryMetadata?.preview
                state.owned = true
            } else if (latestResolved == target.preview) state.owned = false
            state.revealing = true
            state.dim.animateTo(0f, tween(XL_BACKDROP_REVEAL_MS, easing = LinearEasing))
            scene.resolveStationaryMetadata(latestResolved)
            if (latestResolved == target.preview) state.owned = false
            return@LaunchedEffect
        }
        if (!state.owned && !dark && boundaryId == null) return@LaunchedEffect
        if (dark && target.isXl && boundary != null && target.rowIndex < boundary.from.rowIndex) {
            if (!state.owned) state.preview = boundary.outgoingBackdrop ?: latestResolved
            state.owned = true
            // Keep the current composite visible while the prepared landscape
            // and blur are pending. Upward entry has no black beat or row-settle wait.
            if (!carouselFocused) snapshotFlow { scene.artwork.ready(target.rowKey, target.itemKey,
                firstNonBlank(target.preview.backdrop, target.preview.imageUrl)) }.first { it }
            state.token++
            state.preview = target.preview
            state.blurred = true
            state.directEntry = true
            state.revealing = true
            coroutineScope {
                launch { state.dim.animateTo(XL_BACKDROP_DIM_ALPHA, tween(220, easing = LinearEasing)) }
                launch { state.gradientAlpha.animateTo(0f, tween(220)) }
            }
            snapshotFlow { scene.transition?.let { it.id != boundary.id || it.rowsSettled } ?: true }.first { it }
            scene.complete(boundary.id)
            state.directEntry = false
            return@LaunchedEffect
        }
        if (dark && state.blurred && boundaryId == null) {
            // No normal-row debounce or fade-to-black on a horizontal XL move.
            // The row releases this barrier when its matching landscape and
            // blur are prepared; the image crossfade starts on that commit.
            if (!carouselFocused) snapshotFlow { scene.artwork.ready(target.rowKey, target.itemKey,
                firstNonBlank(target.preview.backdrop, target.preview.imageUrl)) }.first { it }
            state.token++
            state.dim.snapTo(XL_BACKDROP_DIM_ALPHA)
            state.gradientAlpha.snapTo(0f)
            state.preview = target.preview
            state.revealing = true
            return@LaunchedEffect
        }
        if (!state.owned) state.preview = scene.transition?.outgoingBackdrop ?: latestResolved
        state.owned = true
        state.revealing = false
        if (state.preview != target.preview || state.blurred != dark || boundaryId != null) {
            // A true black beat masks both the outgoing frame and image loading.
            if (exitingXl) state.dim.snapTo(1f)
            else state.dim.animateTo(1f, tween(if (state.dim.value > 0.9f) 80 else 240))
            if (dark && !carouselFocused) snapshotFlow { scene.artwork.ready(target.rowKey, target.itemKey,
                firstNonBlank(target.preview.backdrop, target.preview.imageUrl)) }.first { it }
            state.token++
            state.preview = target.preview
            state.blurred = dark
            state.gradientAlpha.snapTo(if (dark) 0f else 1f)
            // Let the renderer bind the committed token before checking readiness.
            repeat(2) { withFrameNanos { } }
            withTimeoutOrNull(2_000L) { snapshotFlow { state.ready() }.first { it } }
        }
        if (boundaryId != null && !exitingXl) {
            snapshotFlow { scene.transition?.let { it.id != boundaryId || it.rowsSettled } ?: true }.first { it }
        }
        state.revealing = true
        state.dim.animateTo(if (dark) XL_BACKDROP_DIM_ALPHA else 0f,
            tween(if (dark) 280 else XL_BACKDROP_REVEAL_MS, easing = LinearEasing))
        // Keep row-attached metadata and the viewport mask alive until landing,
        // even when the destination image has already faded in.
        if (boundaryId != null && exitingXl) {
            snapshotFlow { scene.transition?.let { it.id != boundaryId || it.rowsSettled } ?: true }.first { it }
        }
        if (boundaryId != null) scene.complete(boundaryId)
        if (!dark && latestResolved == target.preview) state.owned = false
    }
    LaunchedEffect(resolvedHero, state.owned, scene.transition, selected?.isXl) {
        scene.resolveStationaryMetadata(resolvedHero)
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
    modifier: Modifier,
    adjacentMetadata: List<XlMetadataSnapshot> = emptyList()
) {
    val selected = scene.selection
    val move = scene.transition
    val viewportHeight = LocalConfiguration.current.screenHeightDp * density
    val outgoing by remember(scene, list, enabled, ordinaryTopPx, viewportHeight) {
        derivedStateOf { enabled && xlSceneOutgoingMetadataVisible(scene, list.layoutInfo, ordinaryTopPx, viewportHeight, density) }
    }
    val snapshot = when {
        !enabled -> resolvedHero?.let { XlMetadataSnapshot(selected?.rowKey.orEmpty(), it) }
        outgoing -> move?.outgoingMetadata
        selected?.isXl == true -> null
        move != null -> selected?.let { XlMetadataSnapshot(it.rowKey, it.preview) }
        else -> scene.stationaryMetadata ?: (if (backdrop.owned) selected?.preview else resolvedHero)?.let {
            XlMetadataSnapshot(selected?.rowKey.orEmpty(), it)
        }
    }
    if (snapshot == null) {
        SideEffect { scene.hideMetadata() }
    }
    val epoch = if (outgoing) move?.outgoingMetadataEpoch ?: scene.metadataEpoch else scene.metadataEpoch
    val metadataLayoutTop = remember(scene) { floatArrayOf(0f) }
    val paintedLayer = remember(scene) { floatArrayOf(0f, 1f) }
    // Keep a stable slot through ordinary row changes. An XL exit promotes the
    // destination slot that was already precomposed with the next epoch.
    val slot = remember(scene, epoch) { XlMetadataSlot(snapshot?.rowKey ?: selected?.rowKey.orEmpty(), epoch) }
    XlMetadataHost(
        activeSlot = slot,
        activeSnapshot = snapshot,
        neighbors = if (enabled && selected?.isXl == true) adjacentMetadata else emptyList(),
        nextEpoch = scene.metadataEpoch + 1,
        modifier = modifier.zIndex(1f)
    ) { active, isActive ->
        HeroTitleBlock(
            preview = active.preview,
            portraitMode = portraitMode,
            selectedPlatformId = selectedPlatformId,
            platformNavDirection = platformNavDirection,
            platformTransitionSnap = platformTransitionSnap,
            fullWidthIconRowEnabled = fullWidthIconRowEnabled,
            heroMetadataLarge = heroMetadataLarge,
            animateTitleLogo = isActive && (!enabled || (move == null && !backdrop.owned)),
            contentModifier = if (isActive) Modifier.onGloballyPositioned { scene.metadataHeightPx = it.size.height.toFloat() } else Modifier,
            modifier = if (!isActive) Modifier else Modifier.testTag("ordinary-metadata-${active.rowKey}").graphicsLayer {
                val incomingOffset = if (enabled && move != null && !outgoing && !move.deferOrdinaryReveal)
                    scene.incomingTranslation(list.layoutInfo, ordinaryTopPx, density) else 0f
                translationY = when {
                    outgoing -> scene.outgoingTranslation(list.layoutInfo)
                    enabled && move != null && !move.deferOrdinaryReveal -> incomingOffset ?: 0f
                    else -> 0f
                }
                alpha = if (enabled && move?.deferOrdinaryReveal == true && !outgoing) {
                    if (move.rowsSettled) 1f else 0f
                } else if (outgoing) move?.initialMetadataAlpha ?: 0f else if (incomingOffset == null) 0f else 1f
                paintedLayer[0] = translationY
                paintedLayer[1] = alpha
                val edge = if (!outgoing) scene.outgoingXlBottom(list.layoutInfo) else null
                val clipTop = if (outgoing) move?.outgoingMetadataClipTop ?: 0f
                    else if (edge != null) (edge - metadataLayoutTop[0] - translationY).coerceAtLeast(0f) else 0f
                val top = metadataLayoutTop[0] + translationY
                val visible = maxOf(top + clipTop, 0f) < minOf(top + size.height, viewportHeight)
                scene.recordMetadata(active, translationY, if (visible) alpha else 0f, epoch, clipTop)
            }.onGloballyPositioned {
                val translation = if (outgoing) scene.outgoingTranslation(list.layoutInfo)
                    else if (move != null && !move.deferOrdinaryReveal)
                        scene.incomingTranslation(list.layoutInfo, ordinaryTopPx, density) ?: 0f else 0f
                metadataLayoutTop[0] = it.positionInRoot().y - translation
            }.drawWithContent {
                // Reveal below the departing row's edge. The next hero may be
                // nearer than a full hero-height gap, especially in compact XL.
                // Never cut its artwork or final summary lines with that mask.
                val offset = paintedLayer[0]
                val edge = scene.outgoingXlBottom(list.layoutInfo)
                val top = when {
                    enabled && outgoing -> move?.outgoingMetadataClipTop ?: 0f
                    enabled && edge != null -> (edge - metadataLayoutTop[0] - offset).coerceAtLeast(0f)
                    else -> 0f
                }
                val screenTop = metadataLayoutTop[0] + offset
                val visible = maxOf(screenTop + top, 0f) < minOf(screenTop + size.height, viewportHeight)
                scene.recordMetadata(active, offset, if (visible) paintedLayer[1] else 0f, epoch, top)
                if (top < size.height) {
                    if (top > 0f) clipRect(top = top) { this@drawWithContent.drawContent() } else drawContent()
                }
            }
        )
    }
}
