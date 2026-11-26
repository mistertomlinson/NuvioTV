package com.nuvio.tv.ui.screens.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal const val XL_BACKDROP_REVEAL_MS = 180
internal const val XL_CONTINUOUS_NAV_INTERVAL_MS = 400L
internal const val XL_EXIT_SEPARATION_DELAY_MS = 250L
internal const val XL_CONTENT_LIFT_DP = 8f
internal val LocalHomeSceneInspection = staticCompositionLocalOf<((XlSceneState, LazyListState) -> Unit)?> { null }

internal data class XlSceneSelection(
    val rowKey: String,
    val itemKey: String,
    val rowIndex: Int,
    val isXl: Boolean,
    val summary: Boolean,
    val preview: HeroPreview
)

internal fun restoredHomeSceneSelection(rows: List<HeroCarouselRow>, rowKey: String?, index: Int): XlSceneSelection? {
    val rowIndex = rows.indexOfFirst { it.key == rowKey }
    return rows.getOrNull(rowIndex)?.sceneSelection(index, rowIndex)
}

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
    if (scene.navigationPresentationFrozen || activeRowIsXl || scene.selection?.isXl == true) return null
    val move = scene.transition
    if (move != null && !move.rowsSettled) return null
    val managed = move != null || backdrop.owned || scene.stationaryMetadata != null
    return (if (managed) scene.selection?.preview else resolvedHero)?.comingSoonText?.takeIf { it.isNotBlank() }
}

/**
 * Retains the same tracked scroll displacement after the original row recycles.
 * The draw/layer path consumes visible item info directly rather than creating
 * a fresh Map and map entries on every frame.
 *
 * Only a handful of LazyColumn rows are visible. Two reusable key/offset
 * buffers keep the exact "first shared visible key" anchor behavior, including
 * the no-overlap frame, without per-frame collection allocations.
 */
internal class XlScrollAnchor(initialPositions: Map<Any, Int>) {
    private var keys: Array<Any?> = arrayOfNulls(maxOf(8, initialPositions.size))
    private var offsets = IntArray(keys.size)
    private var count = 0
    private var scratchKeys: Array<Any?> = arrayOfNulls(keys.size)
    private var scratchOffsets = IntArray(keys.size)
    private var traveledPx = 0f

    init {
        initialPositions.forEach { (key, y) ->
            keys[count] = key
            offsets[count++] = y
        }
    }

    companion object {
        /** Named factory avoids ambiguous overload resolution in test compilation. */
        fun fromLayout(layout: LazyListLayoutInfo): XlScrollAnchor =
            XlScrollAnchor(emptyMap()).apply {
                val visible = layout.visibleItemsInfo
                ensureActiveCapacity(visible.size)
                // An empty window may not expose content padding.
                val padding = if (visible.isEmpty()) 0 else layout.beforeContentPadding
                visible.forEachIndexed { index, item ->
                    keys[index] = item.key
                    offsets[index] = item.offset + padding
                }
                count = visible.size
            }
    }

    private fun ensureActiveCapacity(needed: Int) {
        if (keys.size < needed) {
            val capacity = maxOf(needed, keys.size * 2)
            keys = keys.copyOf(capacity)
            offsets = offsets.copyOf(capacity)
        }
    }

    private fun ensureScratchCapacity(needed: Int) {
        if (scratchKeys.size < needed) {
            val capacity = maxOf(needed, scratchKeys.size * 2)
            scratchKeys = scratchKeys.copyOf(capacity)
            scratchOffsets = scratchOffsets.copyOf(capacity)
        }
    }

    private fun priorOffset(key: Any): Int? {
        for (i in 0 until count) if (keys[i] == key) return offsets[i]
        return null
    }

    private fun publish(size: Int) {
        val oldCount = count
        val oldKeys = keys
        val oldOffsets = offsets
        keys = scratchKeys
        offsets = scratchOffsets
        count = size
        scratchKeys = oldKeys
        scratchOffsets = oldOffsets
        // The recycled buffer must not keep old poster keys alive.
        for (i in 0 until oldCount) scratchKeys[i] = null
    }

    /** Map input remains available for pure state tests; production uses layout. */
    fun displacement(current: Map<Any, Int>): Float {
        ensureScratchCapacity(current.size)
        var matched = false
        var index = 0
        for ((key, y) in current) {
            if (!matched) priorOffset(key)?.let { old ->
                traveledPx += y - old
                matched = true
            }
            scratchKeys[index] = key
            scratchOffsets[index++] = y
        }
        publish(index)
        return traveledPx
    }

    /** Allocation-free hot path, including row recycling and content padding. */
    fun displacementFromLayout(layout: LazyListLayoutInfo): Float {
        val visible = layout.visibleItemsInfo
        ensureScratchCapacity(visible.size)
        // An empty visible window has no positions and may not expose padding.
        val padding = if (visible.isEmpty()) 0 else layout.beforeContentPadding
        var matched = false
        for (i in visible.indices) {
            val item = visible[i]
            val position = item.offset + padding
            if (!matched) priorOffset(item.key)?.let { old ->
                traveledPx += position - old
                matched = true
            }
            scratchKeys[i] = item.key
            scratchOffsets[i] = position
        }
        publish(visible.size)
        return traveledPx
    }
}

internal data class XlMetadataSnapshot(val rowKey: String, val preview: HeroPreview)
internal data class XlFastReturn(val target: XlSceneSelection, val metadata: XlMetadataSnapshot?,
    val anchor: XlScrollAnchor, val translation: Float, val alpha: Float, val epoch: Int, val clipTop: Float)

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
    val rowsSettled: Boolean = false,
    val outgoingHeadroomPx: Float = 0f,
    val outgoingMetadataCleared: Boolean = false,
    val earlyPreviewReturn: Boolean = false,
    // Carry the fixed follower's paint offset across an interrupted compact
    // downward exit. The reverse spring can then reuse the SAME measured gap
    // instead of briefly materializing it and snapping the LazyColumn anchor.
    val interruptedFollowerPaintPx: Float? = null
) {
    val downwardExit = from.isXl && !to.isXl && to.rowIndex > from.rowIndex
    val compactExit = downwardExit && !from.summary
    val summaryExit = downwardExit && from.summary
    val deferOrdinaryReveal = !keepMetadataVisible && outgoingMetadata != null && !to.isXl
}

@Stable
internal class XlSceneState(initial: XlSceneSelection? = null, rows: List<HeroCarouselRow> = emptyList()) {
    val artwork = XlArtworkPresentation()
    var selection by mutableStateOf(initial)
        private set
    var transition by mutableStateOf<XlSceneTransition?>(null)
        private set
    var fastReturn by mutableStateOf<XlFastReturn?>(null)
        private set
    // Double-Up clears fastReturn before the destination backdrop composes.
    // Keep a one-shot arrival token so it cannot be mistaken for a cold
    // first render, whose intentional zero-duration initialization differs.
    internal var pendingFastReturnXlBackdropKey by mutableStateOf<String?>(null)
        private set
    fun consumeFastReturnXlBackdropReveal(rowKey: String): Boolean {
        if (pendingFastReturnXlBackdropKey != rowKey) return false
        pendingFastReturnXlBackdropKey = null
        return true
    }
    var previewReturn by mutableStateOf<XlPreviewReturn?>(null)
        internal set
    var previewReturnPaintOffsetPx by mutableFloatStateOf(0f)
        internal set

    fun previewReturnOffsetForIndex(index: Int): Float {
        val preview = previewReturn ?: return 0f
        return if (index >= preview.rowIndex) previewReturnPaintOffsetPx else 0f
    }

    // Materialize the actually painted gap exactly once if navigation
    // interrupts the independent title-peek animation.
    fun cancelPreviewReturn() {
        val preview = previewReturn ?: return
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            val visualGap = (gapPx(preview.rowKey) + previewReturnPaintOffsetPx.roundToInt()).coerceAtLeast(0)
            headroomValues[preview.rowKey] = (visualGap / density).dp
            headroomMoving[preview.rowKey] = false
            previewReturnPaintOffsetPx = 0f
            previewReturn = null
        }
    }
    private var confirmedRowKey: String? = null
    var boundaryFocusArmed: Boolean = false
        private set
    fun armBoundaryFocus() { if (!continuousVerticalNavigation) boundaryFocusArmed = true }
    fun clearBoundaryFocusIntent() { boundaryFocusArmed = false }

    // The held-key scroll owner and repeated same-direction taps share one
    // compact scene. These flags change at input/landing boundaries, not on
    // individual scroll frames. Metadata stays suppressed through alignment.
    var continuousVerticalNavigation by mutableStateOf(false)
        private set
    var continuousGapsCollapsed by mutableStateOf(false)
        private set
    var continuousInputEpoch by mutableIntStateOf(0)
        private set
    private var lastVerticalPressTime = Long.MIN_VALUE
    // UI-thread only; captured before canceling the preceding XL spring.
    var verticalVelocityPxPerSec = 0f
    var verticalVelocityRecordedAt = Long.MIN_VALUE
    fun recordVerticalVelocity(value: Float) {
        verticalVelocityPxPerSec = value
        verticalVelocityRecordedAt = android.os.SystemClock.uptimeMillis()
    }
    fun inheritedVerticalVelocity(): Float = if (verticalVelocityRecordedAt != Long.MIN_VALUE &&
        android.os.SystemClock.uptimeMillis() - verticalVelocityRecordedAt in 0L..100L)
        verticalVelocityPxPerSec else 0f
    var verticalInputIntent = false
        private set
    private var lastVerticalPressSign = 0
    private var rapidVerticalStreak = 0
    var verticalKeyHeld by mutableStateOf(false)
        private set
    var continuousHeldScroll by mutableStateOf(false)
        private set
    var deferredDownwardExit by mutableStateOf(false)
        private set
    private var deferredExitSource: String? = null
    private var deferredExitTarget: XlSceneSelection? = null
    private var deferredExitConfirmed = false
    var navigationFrozenBackdrop by mutableStateOf<HeroPreview?>(null)
        private set
    val navigationPresentationFrozen: Boolean
        get() = deferredDownwardExit || continuousVerticalNavigation
    private var pendingContinuousBackdrop: Pair<String, String>? = null
    // Only the final held-scroll XL destination can reveal before the
    // geometry settles. Metadata and poster focus suppression remain intact.
    private var earlyContinuousBackdrop by mutableStateOf<Pair<String, String>?>(null)
    val backdropPresentationFrozen: Boolean
        get() = navigationPresentationFrozen && !(continuousHeldScroll && !verticalKeyHeld &&
            earlyContinuousBackdrop != null &&
            earlyContinuousBackdrop == selection?.let { it.rowKey to it.itemKey })

    fun armHeldLandingBackdrop(target: XlSceneSelection) {
        if (!continuousVerticalNavigation || !continuousHeldScroll || verticalKeyHeld || !target.isXl ||
            selection?.let { it.rowKey to it.itemKey } != (target.rowKey to target.itemKey)) return
        val key = target.rowKey to target.itemKey
        if (earlyContinuousBackdrop == key) return
        pendingContinuousBackdrop = key
        earlyContinuousBackdrop = key
        XlNavigationTrace.event("BACKDROP_LANDING_ARM") { "row=${target.rowKey} item=${target.itemKey}" }
    }

    fun isEarlyContinuousBackdrop(target: XlSceneSelection): Boolean =
        earlyContinuousBackdrop == (target.rowKey to target.itemKey)

    fun armDeferredDownwardExit(sourceKey: String) {
        XlNavigationTrace.event("ARM_EXIT") { "source=$sourceKey" }
        if (continuousVerticalNavigation || deferredDownwardExit || fastReturn != null) return
        deferredExitSource = sourceKey
        deferredExitTarget = null
        deferredExitConfirmed = false
        navigationFrozenBackdrop = paintedBackdrop ?: selection?.preview
        deferredDownwardExit = true
    }

    private fun clearDeferredExit() {
        deferredExitSource = null
        deferredExitTarget = null
        deferredExitConfirmed = false
        deferredDownwardExit = false
        clearBoundaryFocusIntent()
    }

    fun cancelDeferredDownwardExit() {
        if (!deferredDownwardExit) return
        clearDeferredExit()
        navigationFrozenBackdrop = null
        verticalKeyHeld = false
    }

    fun resolveDeferredDownwardExit(layout: LazyListLayoutInfo, rows: List<HeroCarouselRow>) {
        XlNavigationTrace.event("EXIT_DECISION") { "held=$verticalKeyHeld target=${deferredExitTarget?.rowKey} pending=$deferredDownwardExit" }
        if (!deferredDownwardExit) return
        if (verticalKeyHeld) {
            beginContinuousNavigation()
            return
        }
        val target = deferredExitTarget
        val confirmed = deferredExitConfirmed
        clearDeferredExit()
        navigationFrozenBackdrop = null
        if (target != null && selection?.rowKey != target.rowKey)
            focus(target, layout, rows = rows, focusConfirmed = confirmed)
    }

    fun noteVerticalRelease() { verticalKeyHeld = false }

    fun startHeldVerticalScroll() {
        continuousHeldScroll = true
        verticalKeyHeld = true
        beginContinuousNavigation()
    }

    fun hasContinuousBackdropReveal(target: XlSceneSelection): Boolean =
        pendingContinuousBackdrop == (target.rowKey to target.itemKey)

    fun consumeContinuousBackdropReveal(target: XlSceneSelection): Boolean {
        if (!hasContinuousBackdropReveal(target)) return false
        pendingContinuousBackdrop = null
        return true
    }

    fun willCollapseOnVerticalPress(eventTime: Long, repeated: Boolean): Boolean =
        !continuousGapsCollapsed && (continuousVerticalNavigation || repeated ||
            lastVerticalPressTime != Long.MIN_VALUE && eventTime - lastVerticalPressTime in 0L..XL_CONTINUOUS_NAV_INTERVAL_MS)

    fun noteVerticalPress(sign: Int, eventTime: Long, repeated: Boolean) {
        verticalKeyHeld = true
        verticalInputIntent = true
        val close = lastVerticalPressTime != Long.MIN_VALUE &&
            eventTime - lastVerticalPressTime in 0L..XL_CONTINUOUS_NAV_INTERVAL_MS
        rapidVerticalStreak = if (close) rapidVerticalStreak + 1 else 1
        // Both rapid reversals and same-direction taps use one compact owner.
        val rapid = close
        lastVerticalPressTime = eventTime
        lastVerticalPressSign = sign
        if (repeated || rapid || continuousVerticalNavigation) beginContinuousNavigation()
    }

    fun beginContinuousNavigation() {
        XlNavigationTrace.event("CONTINUOUS_START") { "selected=${selection?.rowKey} collapsed=$continuousGapsCollapsed" }
        if (fastReturn != null) return
        continuousInputEpoch++
        // A new input cancels an early reveal, even if compact geometry
        // was already active. It must not reappear for an obsolete target.
        if (earlyContinuousBackdrop != null) {
            earlyContinuousBackdrop = null
            pendingContinuousBackdrop = null
        }
        if (continuousGapsCollapsed) {
            // Suppress obsolete metadata synchronously; the scroll driver
            // interpolates the existing row translations on its next frame.
            rapidLandingMetadata = false
            rapidLandingMetadataAlpha = 0f
            rapidLandingReady = false
            return
        }
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            if (!navigationPresentationFrozen || navigationFrozenBackdrop == null)
                navigationFrozenBackdrop = paintedBackdrop ?: selection?.preview
            pendingContinuousBackdrop = null
            clearContinuousLandingPaint()
            continuousVerticalNavigation = true
            clearDeferredExit()
            continuousGapsCollapsed = true
            clearBoundaryFocusIntent()
            cancelVerticalMotion()
            interruptedRelocation = null
            cancelPreviewReturn()
            transition = null
            stationaryMetadata = null
            paintedMetadata = null
            paintedAlpha = 0f
            exitMetadataAlpha = 0f
            compactRowRested = false
            expandedXlRows.clear()
            heldHeadroomGoals.clear()
            headroomMoving.clear()
            landscapePreXlCompression.clear()
            headroomValues.keys.toList().forEach { headroomValues[it] = 0.dp }
        }
    }

    // Restore the normal geometry once, while the metadata and its mask are
    // still hidden. The landing owner aligns the final row before revealing it.
    // Landing geometry is calculated without mutating LazyColumn. Animate
    // neighboring rows on the compact geometry, then commit the spacers once.
    var continuousLandingOffsets by mutableStateOf<Map<String, Float>>(emptyMap())
    private var continuousLandingStartOffsets by mutableStateOf<Map<String, Float>>(emptyMap())
    var continuousLandingProgress by mutableFloatStateOf(0f)
    var rapidLandingMetadata by mutableStateOf(false)
        private set
    var rapidLandingMetadataAlpha by mutableFloatStateOf(0f)
        private set
    private var rapidLandingEpoch = -1
    private var rapidLandingReleasedSeconds = 0f
    private var rapidLandingPlan: XlLandingPaintPlan? = null
    private var rapidLandingRowKey: String? = null
    private var rapidLandingItemKey: String? = null
    private var rapidLandingBlendSeconds = 0f
    var rapidLandingReady = false
        private set

    fun hasContinuousLandingOffset(rowKey: String): Boolean =
        rowKey in continuousLandingOffsets || rowKey in continuousLandingStartOffsets

    fun continuousLandingOffsetPx(rowKey: String): Float {
        val from = continuousLandingStartOffsets[rowKey] ?: 0f
        val to = continuousLandingOffsets[rowKey] ?: 0f
        return from + (to - from) * continuousLandingProgress
    }

    private fun retargetLandingPaint(offsets: Map<String, Float>) {
        // Capture exactly the currently painted position, not a stale target.
        val start = (continuousLandingOffsets.keys + continuousLandingStartOffsets.keys)
            .associateWith { continuousLandingOffsetPx(it) }.filterValues { it != 0f }
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            continuousLandingStartOffsets = start
            continuousLandingOffsets = offsets
            continuousLandingProgress = 0f
        }
        rapidLandingBlendSeconds = 0f
    }

    private fun advanceLandingPaint(dt: Float) {
        rapidLandingBlendSeconds = (rapidLandingBlendSeconds + dt).coerceAtMost(0.24f)
        val t = rapidLandingBlendSeconds / 0.24f
        continuousLandingProgress = t * t * (3f - 2f * t)
    }

    private fun resetRapidLandingIntent() {
        rapidLandingEpoch = -1
        rapidLandingReleasedSeconds = 0f
        rapidLandingRowKey = null
        rapidLandingItemKey = null
        rapidLandingPlan = null
        rapidLandingReady = false
        rapidLandingMetadata = false
        rapidLandingMetadataAlpha = 0f
    }

    fun clearContinuousLandingPaint() {
        continuousLandingProgress = 0f
        continuousLandingStartOffsets = emptyMap()
        continuousLandingOffsets = emptyMap()
        resetRapidLandingIntent()
    }

    // A held session keeps the already verified landing behavior. If it
    // interrupts a rapid tap, release that tap's paint offset continuously.
    fun advanceHeldLandingPaint(dt: Float) {
        if (rapidLandingPlan != null || rapidLandingRowKey != null) {
            resetRapidLandingIntent()
            retargetLandingPaint(emptyMap())
        }
        if (continuousLandingStartOffsets.isNotEmpty()) {
            advanceLandingPaint(dt)
            if (continuousLandingProgress >= 1f) clearContinuousLandingPaint()
        }
    }

    /**
     * Called by the same list-scroll frame owner, including its braking tail.
     * A tentative final gap can start opening before the 400ms burst window
     * expires; metadata itself stays hidden until that window is over.
     */
    fun advanceRapidLanding(target: XlSceneSelection, remaining: Float, dt: Float,
        plan: (XlSceneSelection) -> XlLandingPaintPlan): Boolean {
        if (!continuousVerticalNavigation || !continuousGapsCollapsed || continuousHeldScroll) return false
        val selected = selection
        if (selected == null || selected.rowKey != target.rowKey || selected.itemKey != target.itemKey) return false
        if (rapidLandingEpoch != continuousInputEpoch || rapidLandingRowKey != target.rowKey ||
            rapidLandingItemKey != target.itemKey) {
            rapidLandingEpoch = continuousInputEpoch
            rapidLandingRowKey = target.rowKey
            rapidLandingItemKey = target.itemKey
            rapidLandingReleasedSeconds = 0f
            rapidLandingPlan = null
            rapidLandingReady = false
            rapidLandingMetadata = false
            rapidLandingMetadataAlpha = 0f
            retargetLandingPaint(emptyMap())
        }
        if (verticalKeyHeld) rapidLandingReleasedSeconds = 0f
        else rapidLandingReleasedSeconds += dt
        if (!verticalKeyHeld && rapidLandingReleasedSeconds >= 0.07f &&
            kotlin.math.abs(remaining) <= 180f * density && rapidLandingPlan == null) {
            val prepared = plan(target)
            rapidLandingPlan = prepared
            retargetLandingPaint(prepared.offsets)
            XlNavigationTrace.event("RAPID_LANDING_ARM") { "row=${target.rowKey} remaining=$remaining clearance=${prepared.virtualClearancePx}" }
        }
        advanceLandingPaint(dt)
        // A new input invalidates the plan immediately. While a burst is
        // still possible only rows move: no intermediate metadata appears.
        val accepted = !verticalKeyHeld && rapidLandingReleasedSeconds >= XL_CONTINUOUS_NAV_INTERVAL_MS / 1000f
        val prepared = rapidLandingPlan != null && continuousLandingProgress >= 1f
        val aligned = kotlin.math.abs(remaining) <= 1f
        if (accepted && prepared && aligned && !target.isXl) {
            rapidLandingMetadata = true
            rapidLandingMetadataAlpha = (rapidLandingMetadataAlpha + dt / 0.12f).coerceAtMost(1f)
        }
        rapidLandingReady = accepted && prepared && aligned && (target.isXl || rapidLandingMetadataAlpha >= 1f)
        return !rapidLandingReady
    }

    fun retainRapidClearanceAfterMeasure(rows: List<HeroCarouselRow>, selected: XlSceneSelection): Boolean {
        val clearance = rapidLandingPlan?.virtualClearancePx ?: return false
        if (!rapidLandingReady || rapidLandingRowKey != selected.rowKey || rapidLandingItemKey != selected.itemKey) return false
        // The measured spacers have taken over the structural displacement.
        // Keep only the virtual clearance until the ordinary mask commits.
        continuousLandingStartOffsets = emptyMap()
        continuousLandingOffsets = if (clearance > 0f)
            rows.take(selected.rowIndex).associate { it.key to -clearance } else emptyMap()
        continuousLandingProgress = 1f
        return true
    }

    fun continuousLandingGaps(rows: List<HeroCarouselRow>, selected: XlSceneSelection): Map<String, Dp> =
        rows.mapIndexed { index, row ->
            val previous = rows.getOrNull(index - 1)
            val predecessorFocused = previous?.key == selected.rowKey
            val target = when {
                row.isXl && previous != null -> {
                    val compactPrefix = predecessorFocused &&
                        (previous.key == "continue_watching" || !previous.landscapePosters)
                    if (compactPrefix) 0.dp else xlPrefixClearance(row.key, previous.key)
                }
                previous?.isXl == true && index <= selected.rowIndex ->
                    followingHeadroom(previous.key, row.key, density, clearXl = true)
                else -> 0.dp
            }
            row.key to target
        }.toMap()

    fun prepareContinuousLanding(rows: List<HeroCarouselRow>) {
        XlNavigationTrace.event("LAND_PREPARE") { "selected=${selection?.rowKey} collapsed=$continuousGapsCollapsed" }
        if (!continuousVerticalNavigation || !continuousGapsCollapsed) return
        val selected = selection
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            continuousGapsCollapsed = false
            if (selected == null) return@withMutableSnapshot
            openEarlierXlGaps(selected, rows)
            val gaps = continuousLandingGaps(rows, selected)
            rows.forEachIndexed { index, row ->
                val previous = rows.getOrNull(index - 1)
                if (row.isXl && previous != null && previous.key == selected.rowKey && previous.key != "continue_watching")
                    landscapePreXlCompression[row.key] = 1f
                val target = gaps.getValue(row.key)
                headroomValues[row.key] = target
                if (!row.isXl && previous?.isXl == true && index <= selected.rowIndex)
                    heldHeadroomGoals[row.key] = target
            }
            if (!retainRapidClearanceAfterMeasure(rows, selected)) clearContinuousLandingPaint()
        }
    }

    fun finishContinuousNavigation() {
        XlNavigationTrace.event("LAND_FINISH") { "selected=${selection?.rowKey} collapsed=$continuousGapsCollapsed" }
        if (!continuousVerticalNavigation) return
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            clearContinuousLandingPaint()
            continuousGapsCollapsed = false
            continuousVerticalNavigation = false
            continuousHeldScroll = false
            verticalKeyHeld = false
            val key = selection?.let { it.rowKey to it.itemKey }
            if (earlyContinuousBackdrop != key || key == null) pendingContinuousBackdrop = key
            earlyContinuousBackdrop = null
            navigationFrozenBackdrop = null
            exitMetadataAlpha = 1f
            metadataEpoch++
            stationaryMetadata = selection?.takeUnless { it.isXl }?.let {
                XlMetadataSnapshot(it.rowKey, it.preview)
            }
        }
        lastVerticalPressTime = Long.MIN_VALUE
        verticalInputIntent = false
        lastVerticalPressSign = 0
        rapidVerticalStreak = 0
    }
    private var launchedMotionId = -1
    var focusPaintEpoch by mutableIntStateOf(0)
        private set
    // XL previews are already visible and may replay their old display
    // list. Their draw must belong to the newly committed focus composition.
    var compactFocusCompositionEpoch by mutableIntStateOf(0)
        private set
    private var focusPaintSignal: Pair<Int, CompletableDeferred<Unit>>? = null
    var lastFocusPaintedId = 0
        private set

    fun requestFocusPaint(id: Int): CompletableDeferred<Unit> {
        focusPaintSignal?.second?.cancel()
        val signal = CompletableDeferred<Unit>()
        focusPaintSignal = id to signal
        focusPaintEpoch = id
        transition?.let { move ->
            if (move.from.isXl || move.to.isXl)
                compactFocusCompositionEpoch = id
        }
        return signal
    }

    fun recordFocusPaint(id: Int, composedCompactEpoch: Int) {
        if (compactFocusCompositionEpoch == id && composedCompactEpoch != id) return
        focusPaintSignal?.takeIf { it.first == id && verticalMotion?.id == id }?.let {
            lastFocusPaintedId = id
            it.second.complete(Unit)
        }
    }
    val metadataHeights = mutableStateMapOf<String, Float>()
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
    var verticalMotion by mutableStateOf<XlVerticalMotion?>(null)
        internal set
    internal var verticalJob: kotlinx.coroutines.Job? = null
    internal var verticalLauncher: ((XlVerticalMotion) -> Unit)? = null
    var interruptedRelocation by mutableStateOf<XlInterruptedRelocation?>(null)
    internal var rowCount = rows.size
    var compactRowRested by mutableStateOf(false)
    // The following ordinary rows keep their final measured spacer during XL
    // downward exits. This draw-only offset reconstructs the original painted
    // position without remeasuring LazyColumn items on every spring frame.
    var fixedFollowerTranslationPx by mutableFloatStateOf(0f)
        internal set
    // An XL prefix uses a full measured spacer for landscape rows, but a
    // compact resting spacer for portrait rows so the next XL heading peeks.
    // On portrait focus entry, clearance is measured ONCE before the first
    // spring frame. During the spring both modes translate the XL suffix in
    // the draw layer, keeping LazyColumn measurements stable; the fraction
    // survives reversals without restarting its painted position.
    val landscapePreXlCompression = mutableStateMapOf<String, Float>()
    var exitMetadataAlpha by mutableFloatStateOf(0f)
    val heldHeadroomGoals = mutableStateMapOf<String, Dp>()
    var density by mutableFloatStateOf(1f)
    val headroomValues = mutableStateMapOf<String, Dp>()
    val headroomMoving = mutableStateMapOf<String, Boolean>()
    val headerHeights = mutableStateMapOf<String, Float>()
    val rowHeights = mutableStateMapOf<String, Dp>()
    val summaryRowHeights = mutableStateMapOf<String, Dp>()
    // The summary's untrimmed height defines its unchanged resting anchor.
    // Only the measured list footprint is shortened into the trailing gap.
    val summaryTrailingGapReductions = mutableStateMapOf<String, Dp>()
    var screenHeight by mutableStateOf(540.dp)
    var xlContentLift by mutableStateOf(0.dp)
    var fullWidthIcons by mutableStateOf(false)
    var estimatedXlHeight by mutableStateOf(440.dp)
    var ordinaryTop by mutableStateOf(260.dp)
    var metadataRestingTop by mutableStateOf(54.dp)
    var metadataHeightPx by mutableFloatStateOf(0f)
    var metadataLayoutHeightPx = 0f
    var stationaryMetadata by mutableStateOf<XlMetadataSnapshot?>(null)
        private set

    fun xlTop(rowKey: String): Dp = xlBalancedTop(screenHeight.value,
        (summaryRowHeights[rowKey] ?: estimatedXlHeight).value, fullWidthIcons).dp

    fun xlPrefixClearance(rowKey: String, previousKey: String?): Dp =
        (xlTop(rowKey) - 24.dp + 1.dp + (previousKey?.let { summaryTrailingGapReductions[it] } ?: 0.dp)).coerceAtLeast(0.dp)

    fun ordinaryPosterInset(rowKey: String, rowIndex: Int, density: Float = this.density): Float =
        if (rowIndex == 0) minOf(headerHeights[rowKey] ?: 40f * density, 40f * density) else 40f * density

    fun headerPx(rowKey: String, density: Float = this.density): Int =
        (headerHeights[rowKey] ?: 40f * density).roundToInt()

    fun gapPx(rowKey: String, density: Float = this.density): Int =
        ((headroomValues[rowKey]?.value ?: 0f) * density).roundToInt()

    fun posterAnchorPx(selected: XlSceneSelection, density: Float = this.density,
        ordinaryTopPx: Float = ordinaryTop.value * density): Int =
        if (selected.isXl) (xlTop(selected.rowKey).value * density).roundToInt() + headerPx(selected.rowKey, density)
        else (ordinaryTopPx + ordinaryPosterInset(selected.rowKey, selected.rowIndex, density)).roundToInt()

    fun metadataTopFor(rowKey: String?): Dp {
        val height = rowKey?.let { metadataHeights[it] } ?: return metadataRestingTop
        if (metadataLayoutHeightPx <= 0f) return metadataRestingTop
        return metadataRestingTop + ((metadataLayoutHeightPx - height) / density * if (fullWidthIcons) 0.5f else 1f).dp
    }

    /**
     * Draw-only offset for XL and its following rows, preserving the ordinary
     * 24dp gap when focus belongs to the preceding row. Landscape layouts
     * measure full clearance throughout. Portrait layouts measure a compact
     * gap at rest and switch ONCE at focus boundaries. Neither layout changes
     * its measured prefix on individual spring frames.
     */
    fun landscapePreXlDrawOffsetPx(rowIndex: Int, rows: List<HeroCarouselRow>): Float {
        if (continuousGapsCollapsed) return 0f
        var offset = 0f
        for (i in 1..minOf(rowIndex, rows.lastIndex)) {
            val xl = rows[i]
            val preceding = rows[i - 1]
            if (xl.isXl && preceding.key != "continue_watching") {
                // If the scene was created before catalog rows finished
                // loading, the initial map may be empty. A settled landscape
                // focus still needs its normal painted gap.
                val progress = landscapePreXlCompression[xl.key]
                    ?: if (selection?.rowKey == preceding.key && transition == null) 1f else 0f
                if (progress != 0f) {
                    // Same physical spacer used by XlSceneRowHeadroom.
                    val prefix = headroomValues[xl.key] ?: if (
                        !preceding.landscapePosters && selection?.rowKey == preceding.key
                    ) 0.dp else xlPrefixClearance(xl.key, preceding.key)
                    offset -= (prefix.value * density).roundToInt() * progress
                }
            }
        }
        return offset
    }

    private fun landscapePrefixMotion(
        previous: XlSceneSelection, next: XlSceneSelection, rows: List<HeroCarouselRow>
    ): XlLandscapePrefixMotion? {
        val xl = if (previous.isXl) previous else next.takeIf { it.isXl } ?: return null
        val before = rows.getOrNull(xl.rowIndex - 1) ?: return null
        if (before.key == "continue_watching") return null
        val crossingDirectlyAbove = if (previous.isXl) next.rowIndex == xl.rowIndex - 1
            else previous.rowIndex == xl.rowIndex - 1
        if (!crossingDirectlyAbove) return null
        val start = landscapePreXlCompression[xl.rowKey] ?: if (!previous.isXl) 1f else 0f
        return XlLandscapePrefixMotion(xl.rowKey, start, if (next.isXl) 0f else 1f,
            portraitPrefix = !before.landscapePosters)
    }

    fun followingHeadroom(previousKey: String, rowKey: String, density: Float, clearXl: Boolean): Dp {
        val trim = summaryTrailingGapReductions[previousKey] ?: 0.dp
        val bottom = xlTop(previousKey) + (rowHeights[previousKey] ?: estimatedXlHeight - trim)
        val correction = (headerHeights[rowKey] ?: 40f * density) / density - 40f
        val gap = xlFollowingHeadroom(screenHeight.value, bottom.value, ordinaryTop.value,
            metadataTopFor(rowKey).value, correction, clearXl)
        return (if (clearXl) maxOf(gap, ordinaryTop.value - 24f + 1f - correction + trim.value) else gap).dp
    }

    fun cancelVerticalMotion(preserveCompactFollowerPaint: Boolean = false) {
        // An interrupted DOWN->UP compact reversal transfers the paint offset
        // intact to the reverse motion. All other cancellations materialize
        // the current painted gap exactly once before dropping the layer.
        val frozen = verticalMotion
        val handoff = preserveCompactFollowerPaint &&
            frozen?.fixedFollowerMeasurement == true
        if (!handoff) {
            val followerKey = when {
                frozen?.fixedFollowerMeasurement == true -> frozen.rowKey
                frozen?.fixedReturnFollowerMeasurement == true -> frozen.secondaryGap?.rowKey
                else -> null
            }
            androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                followerKey?.let { key ->
                    val fixedGapPx = if (frozen?.fixedReturnFollowerMeasurement == true)
                        gapPx(key) // This is the actual measured follower, not the zero virtual target.
                        else ((frozen?.toGap?.value ?: 0f) * density).roundToInt()
                    val paintedPx = fixedGapPx + fixedFollowerTranslationPx.roundToInt()
                    headroomValues[key] = (paintedPx.coerceAtLeast(0) / density).dp
                }
                fixedFollowerTranslationPx = 0f
            }
        }
        val paint = focusPaintSignal?.second
        focusPaintSignal = null
        verticalMotion?.let { motion ->
            heldHeadroomGoals[motion.rowKey] = headroomValues[motion.rowKey] ?: motion.fromGap
            verticalMotion = null
            headroomMoving[motion.rowKey] = false
            motion.secondaryGap?.let { headroomMoving[it.rowKey] = false }
            verticalJob?.cancel()
            verticalJob = null
        }
        // Publish the released owner before a canceled waiter can resume.
        paint?.cancel()
    }

    fun confirmFocus(rowKey: String, layout: LazyListLayoutInfo? = null, rows: List<HeroCarouselRow> = emptyList()) {
        XlNavigationTrace.event("FOCUS_CONFIRMED") { "row=$rowKey selected=${selection?.rowKey} deferred=$deferredDownwardExit continuous=$continuousVerticalNavigation" }
        boundaryFocusArmed = false
        if (deferredDownwardExit && deferredExitTarget?.rowKey == rowKey) deferredExitConfirmed = true
        if (continuousVerticalNavigation) {
            confirmedRowKey = rowKey
            return
        }
        // A recycled destination can be absent during logical selection and
        // become measurable only when its card receives actual focus.
        if (verticalMotion == null && layout != null) transition?.takeIf {
            it.to.rowKey == rowKey && !it.rowsSettled
        }?.let { prepareVerticalMotion(it, layout, rows, focusConfirmed = false) }
        confirmedRowKey = rowKey
        launchConfirmedMotion()
    }

    fun launchConfirmedMotion() {
        val motion = verticalMotion ?: return
        if (confirmedRowKey != motion.rowKey || launchedMotionId == motion.id || verticalLauncher == null) return
        launchedMotionId = motion.id
        verticalLauncher?.invoke(motion)
    }

    fun managesGap(rowKey: String): Boolean = verticalMotion?.let {
        it.rowKey == rowKey || it.secondaryGap?.rowKey == rowKey ||
            it.landscapePrefix?.xlKey == rowKey
    } == true || previewReturn?.rowKey == rowKey

    fun beginFastReturn(target: XlSceneSelection, layout: LazyListLayoutInfo, rows: List<HeroCarouselRow> = emptyList()) {
        pendingFastReturnXlBackdropKey = null
        clearDeferredExit()
        finishContinuousNavigation()
        pendingContinuousBackdrop = null
        navigationFrozenBackdrop = null
        verticalKeyHeld = false
        clearBoundaryFocusIntent()
        cancelVerticalMotion()
        interruptedRelocation = null
        cancelPreviewReturn()
        transition = null
        stationaryMetadata = null
        fastReturn = XlFastReturn(target, paintedMetadata?.takeIf { paintedAlpha > 0f },
            XlScrollAnchor.fromLayout(layout), paintedTranslation, paintedAlpha, paintedEpoch, paintedClipTop)
        prepareFastReturnGaps(target, rows, layout)
    }

    private fun prepareFastReturnGaps(target: XlSceneSelection, rows: List<HeroCarouselRow>, layout: LazyListLayoutInfo? = null) {
        if (target.isXl) expandedXlRows.remove(target.rowKey)
        val next = rows.getOrNull(target.rowIndex + 1) ?: return
        val closeGap = target.isXl && !next.isXl || target.rowKey == "continue_watching" && next.isXl
        if (closeGap && (layout == null || layout.visibleItemsInfo.none { it.key == next.key })) {
            headroomValues[next.key] = 0.dp
            heldHeadroomGoals.remove(next.key)
            headroomMoving[next.key] = false
        }
    }

    fun finishFastReturn(target: XlSceneSelection, rows: List<HeroCarouselRow> = emptyList()) {
        prepareFastReturnGaps(target, rows)
        fastReturn = null
        transition = null
        selection = target
        confirmedRowKey = null
        paintedAlpha = 0f
        stationaryMetadata = if (target.isXl) null else XlMetadataSnapshot(target.rowKey, target.preview)
        pendingFastReturnXlBackdropKey = target.rowKey.takeIf { target.isXl }
    }

    fun cancelFastReturn() {
        // The Double-Up finally block runs after finishFastReturn, often
        // before the next composition. Do not discard the completed arrival.
        if (fastReturn != null) pendingFastReturnXlBackdropKey = null
        fastReturn = null
    }
    fun fastReturnTranslation(layout: LazyListLayoutInfo): Float = fastReturn?.let {
        it.translation + it.anchor.displacementFromLayout(layout)
    } ?: 0f

    fun resolveStationaryMetadata(preview: HeroPreview?) {
        if (selection?.isXl == false && selection?.preview == preview) stationaryMetadata = null
    }

    init {
        initial?.let { selected ->
            openEarlierXlGaps(selected, rows)
            // Any restored ordinary row above XL starts with the ordinary
            // 24dp painted gap. The portrait version also measures only this
            // resting space so the next XL heading remains in LazyColumn.
            rows.getOrNull(selected.rowIndex + 1)?.takeIf {
                it.isXl && selected.rowKey != "continue_watching"
            }?.let { landscapePreXlCompression[it.key] = 1f }
        }
    }

    private fun openEarlierXlGaps(next: XlSceneSelection, rows: List<HeroCarouselRow>) {
        for (index in 0 until next.rowIndex.coerceIn(0, rows.size)) {
            val row = rows[index]
            if (row.isXl) expandedXlRows[row.key] = true
        }
    }

    fun recordMetadata(snapshot: XlMetadataSnapshot, translation: Float = 0f, alpha: Float = 1f,
        epoch: Int = metadataEpoch, clipTop: Float = 0f) {
        if (navigationPresentationFrozen || epoch != metadataEpoch && transition?.outgoingMetadataEpoch != epoch && fastReturn?.epoch != epoch) return
        paintedMetadata = snapshot
        paintedTranslation = translation
        paintedAlpha = alpha
        paintedEpoch = epoch
        paintedClipTop = clipTop
    }

    fun hideMetadata() { paintedAlpha = 0f }

    fun recordBackdrop(preview: HeroPreview?) { if (!backdropPresentationFrozen) paintedBackdrop = preview }

    fun focus(next: XlSceneSelection, layout: LazyListLayoutInfo, animate: Boolean = true,
        rows: List<HeroCarouselRow> = emptyList(), focusConfirmed: Boolean = true) {
        XlNavigationTrace.event("FOCUS_REQUEST") { "from=${selection?.rowKey}/${selection?.itemKey} next=${next.rowKey}/${next.itemKey} confirmed=$focusConfirmed deferred=$deferredDownwardExit continuous=$continuousVerticalNavigation" }
        if (rows.isNotEmpty()) rowCount = rows.size
        if (fastReturn != null) return
        // The intent gate holds only separation and presentation. Focus and
        // the shared vertical travel start on the first key, not after debounce.
        if (deferredDownwardExit && next.rowKey != deferredExitSource) {
            deferredExitTarget = next
            if (focusConfirmed) deferredExitConfirmed = true
        }
        if (continuousVerticalNavigation) {
            selection = next
            if (focusConfirmed) confirmedRowKey = next.rowKey
            return
        }
        if (pendingContinuousBackdrop != null &&
            pendingContinuousBackdrop != (next.rowKey to next.itemKey)) pendingContinuousBackdrop = null
        val previous = selection
        val previousMove = transition
        val heldMetadata = stationaryMetadata
        var compactPaintHandoff: Float? = null
        val interruptedReturn = verticalMotion != null && previousMove?.downwardExit == true && next.rowKey == previousMove.from.rowKey
        if (previous?.rowKey != next.rowKey) {
            confirmedRowKey = null
            cancelPreviewReturn()
            val interrupted = verticalMotion
            val previousJob = verticalJob
            // Keep the actual painted position of every ordinary row after
            // the XL. Materializing the virtual gap here forces a new LazyList
            // measure before the reverse spring and visibly snaps portrait C.
            compactPaintHandoff = if (interruptedReturn &&
                interrupted?.fixedFollowerMeasurement == true &&
                interrupted.itemAnchor != null) fixedFollowerTranslationPx else null
            cancelVerticalMotion(preserveCompactFollowerPaint = compactPaintHandoff != null)
            interruptedRelocation = interrupted?.let { XlInterruptedRelocation(it.id, next.rowKey, previousJob) }
            if (interrupted != null && previousMove?.downwardExit == true && next.rowKey == previousMove.from.rowKey) {
                // Rebuild the outgoing hero's full clearance while returning
                // from a partly completed compact exit.
                heldHeadroomGoals[interrupted.rowKey] = interrupted.toGap
            }
            compactRowRested = false
            openEarlierXlGaps(next, rows)
        }
        selection = next
        if (previous?.rowKey == next.rowKey) {
            if (focusConfirmed) confirmFocus(next.rowKey, layout, rows)
            return
        }
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
            // Keep XL clearance/fade ownership through a rapid second Down.
            // The held ordinary title still resumes its normal debounce.
            if (previousMove?.downwardExit != true || exitMetadataAlpha >= 1f) transition = null
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
        if (previous.isXl && !next.isXl) metadataHeights[next.rowKey]?.takeIf { it > 0f }?.let { height ->
            if (metadataLayoutHeightPx > 0f) metadataRestingTop +=
                ((metadataLayoutHeightPx - height) / density * if (fullWidthIcons) 0.5f else 1f).dp
            metadataLayoutHeightPx = height
            metadataHeightPx = height
        }
        val move = XlSceneTransition(
            ++generation, previous, next, outgoing, paintedBackdrop ?: previous.preview, XlScrollAnchor.fromLayout(layout),
            if (outgoing == paintedMetadata) paintedTranslation else 0f,
            if (outgoing == paintedMetadata) paintedAlpha else 0f,
            paintedEpoch, sameOwnerReturn, if (outgoing == paintedMetadata) paintedClipTop else 0f,
            outgoingHeadroomPx = outgoing?.let { gapPx(it.rowKey).toFloat() } ?: 0f,
            earlyPreviewReturn = next.isXl && next.rowIndex < previous.rowIndex && !interruptedReturn,
            interruptedFollowerPaintPx = compactPaintHandoff
        )
        transition = move
        exitMetadataAlpha = if (move.downwardExit) 0f else 1f
        if (move.downwardExit) expandedXlRows[previous.rowKey] = true
        if (next.isXl && next.rowIndex < previous.rowIndex &&
            compactPaintHandoff == null) {
            rows.getOrNull(next.rowIndex + 1)?.takeUnless { it.isXl }?.let {
                previewReturn = XlPreviewReturn(move.id, it.key, next.rowKey, next.rowIndex + 1)
                headroomMoving[it.key] = true
            }
        }
        prepareVerticalMotion(move, layout, rows, focusConfirmed)
        // If the return target was recycled before an anchor could be built,
        // fall back to the materialized current gap rather than leaving a
        // paint offset without an owner.
        if (compactPaintHandoff != null && verticalMotion?.fixedReturnFollowerMeasurement != true) {
            androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                val measured = gapPx(previous.rowKey)
                headroomValues[previous.rowKey] =
                    ((measured + fixedFollowerTranslationPx.roundToInt()).coerceAtLeast(0) / density).dp
                fixedFollowerTranslationPx = 0f
            }
            // A delayed focus confirmation must not apply the old painted
            // handoff twice after this one-time fallback materialized it.
            if (transition?.id == move.id)
                transition = move.copy(interruptedFollowerPaintPx = null)
        }
    }

    private fun prepareVerticalMotion(move: XlSceneTransition, layout: LazyListLayoutInfo,
        rows: List<HeroCarouselRow>, focusConfirmed: Boolean) {
        val previous = move.from
        val next = move.to
        if (previous.isXl || next.isXl) {
            val owner = layout.visibleItemsInfo.firstOrNull { it.key == next.rowKey }
            val previousOwner = layout.visibleItemsInfo.firstOrNull { it.key == previous.rowKey }
            val from = (gapPx(next.rowKey) / density).dp
            val ownerTop = owner?.let { (it.offset + layout.beforeContentPadding).toFloat() }
                ?: if (previousOwner != null && next.rowIndex == previous.rowIndex - 1) {
                    rowHeights[next.rowKey]?.let { height -> (previousOwner.offset + layout.beforeContentPadding -
                        (24f * density).roundToInt() - (height.value * density).roundToInt() - gapPx(next.rowKey)).toFloat() }
                } else if (previousOwner != null && next.rowIndex == previous.rowIndex + 1) {
                    (previousOwner.offset + layout.beforeContentPadding + previousOwner.size + (24f * density).roundToInt()).toFloat()
                } else null
            if (ownerTop != null) {
                val to = when {
                    move.downwardExit -> followingHeadroom(previous.rowKey, next.rowKey, density, clearXl = true)
                    next.isXl && next.rowIndex > 0 -> xlPrefixClearance(next.rowKey, rows.getOrNull(next.rowIndex - 1)?.key)
                    else -> from
                }.let { ((it.value * density).roundToInt() / density).dp }
                val fromPx = (from.value * density).roundToInt()
                val toPx = (to.value * density).roundToInt()
                val visibleDistance = ownerTop.roundToInt() + fromPx + headerPx(next.rowKey) - posterAnchorPx(next)
                val scroll = (visibleDistance + toPx - fromPx).toFloat()
                val previousGap = move.interruptedFollowerPaintPx?.let { paint ->
                    ((gapPx(previous.rowKey) + paint.roundToInt()).coerceAtLeast(0) / density).dp
                } ?: (headroomValues[previous.rowKey] ?: 0.dp)
                val restoredGap = heldHeadroomGoals[previous.rowKey]
                val secondary = when {
                    // A compact DOWN->UP reversal must return the follower to
                    // the COMPACT resting gap (zero), not the full offscreen
                    // clearance. Otherwise it first travels down toward the
                    // expanded gap and then snaps back up when that gap closes.
                    // Its physical spacer stays at the expanded height during
                    // the spring; only the follower layer represents this leg.
                    move.interruptedFollowerPaintPx != null ->
                        XlGapMotion(previous.rowKey, previousGap, 0.dp)
                    next.rowKey == "continue_watching" && previous.rowIndex == next.rowIndex + 1 ->
                        XlGapMotion(previous.rowKey, previousGap, 0.dp)
                    // A fast reversal can happen after XL has left the viewport
                    // but before its full clearance gap has finished growing.
                    // Restore that remaining clearance with the return scroll
                    // so the partly visible ordinary hero can leave completely.
                    next.isXl && next.rowIndex < previous.rowIndex && !move.earlyPreviewReturn &&
                        restoredGap != null && restoredGap > previousGap ->
                        XlGapMotion(previous.rowKey, previousGap, restoredGap)
                    else -> null
                }
                if (kotlin.math.abs(scroll) > 0.5f) {
                    heldHeadroomGoals[next.rowKey] = to
                    val landscapePrefix = landscapePrefixMotion(previous, next, rows)
                    val reverseFixedFollower = move.interruptedFollowerPaintPx != null &&
                        secondary?.rowKey == previous.rowKey
                    val fixedPortraitPrefixOnEntry =
                        next.isXl && landscapePrefix?.portraitPrefix == true
                    val followerIndex = if (move.downwardExit) next.rowIndex
                        else if (reverseFixedFollower) previous.rowIndex else -1
                    val itemAnchor = if (move.downwardExit) XlScrollItemAnchor(
                        owner?.index ?: next.rowIndex, layout.beforeContentPadding - ownerTop.roundToInt())
                        else if (reverseFixedFollower) XlScrollItemAnchor(previous.rowIndex, 0) else null
                    val endOfOrdinarySuffix = if (followerIndex >= 0 && rows.isNotEmpty())
                        (followerIndex + 1 until rows.size).firstOrNull { rows[it].isXl } ?: rows.size
                        else Int.MAX_VALUE
                    val motion = XlVerticalMotion(move.id, next.rowKey, from, to, scroll,
                        move.downwardExit, secondary, itemAnchor,
                        fixedFollowerMeasurement = move.downwardExit,
                        followerEndExclusiveIndex = if (move.downwardExit && deferredDownwardExit) Int.MAX_VALUE else endOfOrdinarySuffix,
                        landscapePrefix = landscapePrefix,
                        fixedPrefixMeasurement = fixedPortraitPrefixOnEntry,
                        fixedReturnFollowerMeasurement = reverseFixedFollower,
                        coupledExit = move.downwardExit && deferredDownwardExit)
                    // Establish the final measured row geometry BEFORE the
                    // focus paint gate. Its visual position stays unchanged:
                    // the identical difference is applied to all ordinary
                    // follower content through a graphics-layer translation.
                    if (motion.fixedFollowerMeasurement) {
                        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                            headroomValues[next.rowKey] = to
                            fixedFollowerTranslationPx = (fromPx - toPx).toFloat()
                        }
                    }
                    // Portrait peeks require a zero measured prefix while the
                    // portrait row is focused. Grow it ONCE before the focus
                    // paint gate, with an equal-and-opposite draw translation.
                    // During the spring only the paint offset changes.
                    if (fixedPortraitPrefixOnEntry) {
                        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                            headroomValues[next.rowKey] = to
                            // A mid-spring reversal already has the full
                            // measurement. Preserve its partial painted gap,
                            // rather than restarting from the compressed end.
                            landscapePreXlCompression[next.rowKey] =
                                checkNotNull(landscapePrefix).from
                        }
                    } else landscapePrefix?.let {
                        landscapePreXlCompression[it.xlKey] = it.from
                    }
                    verticalMotion = motion
                    headroomMoving[next.rowKey] = true
                    secondary?.let { headroomMoving[it.rowKey] = true }
                    if (focusConfirmed) confirmFocus(next.rowKey)
                }
            }
        }
    }

    fun rowsSettled(id: Int) {
        transition?.takeIf { it.id == id }?.let {
            if (it.downwardExit) compactRowRested = true
            transition = it.copy(rowsSettled = true)
        }
    }

    fun releasePreviewGap(id: Int) {
        transition?.takeIf { it.id == id && it.to.isXl }?.let {
            transition = it.copy(outgoingMetadataCleared = true)
            expandedXlRows.remove(it.to.rowKey)
        }
    }

    fun startPreviewReturn(id: Int) {
        transition?.takeIf { it.id == id && it.to.isXl }?.let { expandedXlRows.remove(it.to.rowKey) }
    }

    fun complete(id: Int) {
        if (transition?.id != id) return
        verticalInputIntent = false
        val move = transition
        transition = null
        if (move?.from?.isXl == true && selection?.isXl == false) {
            val selected = checkNotNull(selection)
            stationaryMetadata = stationaryMetadata ?: paintedMetadata?.takeIf { paintedAlpha > 0f && it.rowKey == selected.rowKey }
                ?: XlMetadataSnapshot(selected.rowKey, selected.preview)
        }
        // Close the preview gap only after the outgoing ordinary metadata has
        // left the screen. It cannot reappear while the next-row peek returns.
        selection?.takeIf { it.isXl }?.let { expandedXlRows.remove(it.rowKey) }
    }

    fun outgoingTranslation(layout: LazyListLayoutInfo): Float = transition?.let {
        val gap = it.outgoingMetadata?.let { block -> gapPx(block.rowKey).toFloat() } ?: 0f
        val delta = gap - it.outgoingHeadroomPx
        it.initialMetadataTranslation + it.scrollAnchor.displacementFromLayout(layout) +
            if (it.earlyPreviewReturn) delta.coerceAtLeast(0f) else delta
    } ?: 0f

    fun outgoingMetadataOffscreen(layout: LazyListLayoutInfo): Boolean {
        val move = transition ?: return true
        if (move.outgoingMetadata == null || move.outgoingMetadataCleared) return true
        val rest = metadataTopFor(move.outgoingMetadata.rowKey)
        val top = rest.value * density + outgoingTranslation(layout)
        val height = metadataHeights[move.outgoingMetadata.rowKey] ?: metadataHeightPx.takeIf { it > 0f }
            ?: (ordinaryTop - rest).value * density
        return top + move.outgoingMetadataClipTop >= screenHeight.value * density || top + height <= 0f
    }

    fun fixedFollowerOffsetForIndex(index: Int): Float {
        val motion = verticalMotion ?: return 0f
        val first = motion.itemAnchor?.index ?: return 0f
        return if ((motion.fixedFollowerMeasurement || motion.fixedReturnFollowerMeasurement) && index >= first &&
            index < motion.followerEndExclusiveIndex) fixedFollowerTranslationPx else 0f
    }

    fun incomingTranslation(layout: LazyListLayoutInfo, ordinaryTopPx: Float, density: Float): Float? {
        val key = selection?.rowKey ?: return null
        val owner = layout.visibleItemsInfo.firstOrNull { it.key == key } ?: return null
        // This metadata is outside LazyColumn, but its target poster may be
        // paint-translated. Use its PAINTED anchor, not just measured geometry.
        return (owner.offset + layout.beforeContentPadding + gapPx(key, density) + headerPx(key, density) +
            fixedFollowerOffsetForIndex(owner.index) -
            posterAnchorPx(checkNotNull(selection), density, ordinaryTopPx)).toFloat()
    }

    fun outgoingXlBottom(layout: LazyListLayoutInfo): Float? {
        val move = transition?.takeIf { it.downwardExit } ?: return null
        val owner = layout.visibleItemsInfo.firstOrNull { it.key == move.from.rowKey } ?: return 0f
        val actualBottom = owner.offset + layout.beforeContentPadding + owner.size +
            ((summaryTrailingGapReductions[move.from.rowKey]?.value ?: 0f) * density).roundToInt() -
            (xlContentLift.value * density).roundToInt()
        return actualBottom.toFloat().coerceAtLeast(0f)
    }
}

internal fun xlSceneOutgoingMetadataVisible(scene: XlSceneState, layout: LazyListLayoutInfo,
    ordinaryTopPx: Float, viewportHeightPx: Float, density: Float = 1f, reserveSpace: Boolean = false): Boolean {
    val move = scene.transition ?: return false
    if (move.outgoingMetadata == null || move.rowsSettled) return false
    // A short block can leave the top before its reserved hero space does.
    // Keep older posters hidden there after removing the cleared metadata.
    if (move.outgoingMetadataCleared && (!reserveSpace || move.to.rowIndex < move.from.rowIndex)) return false
    val offset = scene.outgoingTranslation(layout)
    val clip = if (move.outgoingMetadataClipTop > 0f)
        scene.metadataTopFor(move.outgoingMetadata?.rowKey).value * density + move.outgoingMetadataClipTop else 0f
    return offset + clip < viewportHeightPx && offset + ordinaryTopPx > 0f && clip < ordinaryTopPx
}

internal fun xlSceneMetadataMask(scene: XlSceneState, layout: LazyListLayoutInfo, enabled: Boolean,
    ordinaryTopPx: Float, density: Float, viewportHeightPx: Float = Float.POSITIVE_INFINITY): Pair<Float, Float>? {
    if (!enabled || scene.navigationPresentationFrozen) return null
    scene.fastReturn?.let { fast ->
        if (fast.metadata == null || fast.alpha <= 0f) return null
        val offset = scene.fastReturnTranslation(layout)
        val top = offset + if (fast.clipTop > 0f) scene.metadataTopFor(fast.metadata?.rowKey).value * density + fast.clipTop else 0f
        val bottom = offset + ordinaryTopPx
        return if (top < bottom && top < viewportHeightPx && bottom > 0f) top to bottom else null
    }
    val move = scene.transition
    val offset = when {
        xlSceneOutgoingMetadataVisible(scene, layout, ordinaryTopPx, viewportHeightPx, density, reserveSpace = true) -> scene.outgoingTranslation(layout)
        scene.selection?.isXl == true -> return null
        move?.downwardExit == true -> if (scene.compactRowRested || (scene.outgoingXlBottom(layout) ?: 0f) <= 0f) 0f else return null
        move?.deferOrdinaryReveal == true -> if (move.rowsSettled) 0f else return null
        move != null -> scene.incomingTranslation(layout, ordinaryTopPx, density) ?: return null
        else -> 0f
    }
    val showingOutgoing = xlSceneOutgoingMetadataVisible(scene, layout, ordinaryTopPx, viewportHeightPx, density, reserveSpace = true)
    val edge = if (showingOutgoing && move != null && move.outgoingMetadataClipTop > 0f)
        offset + scene.metadataTopFor(move.outgoingMetadata?.rowKey).value * density + move.outgoingMetadataClipTop
        else if (showingOutgoing) null else scene.outgoingXlBottom(layout)
    val top = edge?.let { maxOf(offset, it) } ?: offset
    return if (top < offset + ordinaryTopPx) top to offset + ordinaryTopPx else null
}

/** Keep the hero's empty space attached to its row; never uncover an older row behind it. */
@Composable
internal fun Modifier.xlSceneRowsViewport(scene: XlSceneState, list: LazyListState, enabled: Boolean,
    ordinaryTop: Dp): Modifier {
    val mask by rememberHomePosterMask(scene, list, enabled, ordinaryTop)
    // Capture during composition, not during replay of a cached preview draw.
    // Both XL variants require the newly committed focus composition.
    val composedCompactEpoch = scene.compactFocusCompositionEpoch
    return drawWithContent {
        val paintEpoch = scene.focusPaintEpoch
        XlNavigationTrace.event("VIEWPORT_DRAW") { "mask=$mask size=$size focusPaint=$paintEpoch composed=$composedCompactEpoch" }
        clipRect {
            val top = mask?.first?.coerceAtLeast(0f) ?: 0f
            val bottom = mask?.second?.coerceAtMost(size.height) ?: 0f
            if (top < bottom) clipRect(top = top, bottom = bottom, clipOp = ClipOp.Difference) {
                this@drawWithContent.drawContent()
            } else this@drawWithContent.drawContent()
        }
        // The first moving frame is released only after the focus frame has drawn.
        scene.recordFocusPaint(paintEpoch, composedCompactEpoch)
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
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val expanded = previous?.key?.let { scene.expandedXlRows[it] } == true
    val computedTarget = when {
        scene.continuousGapsCollapsed -> 0.dp
        // The gap and prefix together clear the previous row beyond y=0 at
        // XL's measured landing position, including the balanced full-width mode.
        currentXl && previous?.key == "continue_watching" &&
            (scene.selection?.rowKey == previous.key || scene.fastReturn?.target?.rowKey == previous.key) -> 0.dp
        // A focused PORTRAIT row needs to expose XL's next heading at the
        // bottom of the viewport. Measure the compact resting prefix (0dp),
        // but preserve the full 31dp clearance while XL owns the spring.
        currentXl && previous != null && !previous.landscapePosters &&
            scene.selection?.rowKey == previous.key &&
            scene.verticalMotion?.landscapePrefix?.xlKey != rowKey -> 0.dp
        currentXl && previous != null -> scene.xlPrefixClearance(rowKey, previous.key)
        followsXl && expanded ->
            scene.followingHeadroom(checkNotNull(previous).key, rowKey, density, clearXl = expanded)
        else -> 0.dp
    }
    val target = if (!scene.continuousGapsCollapsed && followsXl && expanded) scene.heldHeadroomGoals[rowKey] ?: computedTarget else computedTarget
    val managed = scene.verticalMotion?.takeIf {
        it.rowKey == rowKey || it.secondaryGap?.rowKey == rowKey ||
            it.landscapePrefix?.xlKey == rowKey
    }
    val retainedPreview = scene.previewReturn?.takeIf { it.rowKey == rowKey }
    remember(scene, rowKey) {
        if (rowKey !in scene.headroomValues) scene.headroomValues[rowKey] = target
    }
    LaunchedEffect(scene, rowKey, target, managed?.id, retainedPreview?.id, scene.fastReturn, scene.continuousVerticalNavigation, scene.continuousGapsCollapsed) {
        if (scene.continuousVerticalNavigation) {
            if (scene.continuousGapsCollapsed) scene.headroomValues[rowKey] = 0.dp
            else scene.headroomValues[rowKey] = target
            scene.headroomMoving[rowKey] = false
            return@LaunchedEffect
        }
        if (scene.fastReturn != null) {
            scene.headroomMoving[rowKey] = false
            return@LaunchedEffect
        }
        if (managed != null || retainedPreview != null) return@LaunchedEffect
        val initial = scene.headroomValues[rowKey] ?: target
        scene.headroomMoving[rowKey] = followsXl && initial != target
        try {
            if (followsXl && initial != target) animate(initial.value, target.value, animationSpec = tween(360)) { value, _ ->
                if (scene.fastReturn != null || scene.continuousVerticalNavigation) throw kotlinx.coroutines.CancellationException("Continuous navigation owns row geometry")
                scene.headroomValues[rowKey] = value.dp
            } else scene.headroomValues[rowKey] = target
        } finally {
            if (!scene.managesGap(rowKey)) scene.headroomMoving[rowKey] = false
        }
    }
    DisposableEffect(scene, rowKey) {
        // A recycled row must retain its gap; its preview can return while unmounted.
        onDispose { if (!scene.managesGap(rowKey)) scene.headroomMoving[rowKey] = false }
    }
    // Gap changes invalidate measurement only, not the row's composition.
    // These trace sections are emitted only during explicit Android tracing,
    // so they can be correlated with slow XL frames without Logcat or snapshots.
    Spacer(Modifier.layout { measurable, constraints ->
        val trace = android.os.Build.VERSION.SDK_INT >= 29 && android.os.Trace.isEnabled()
        if (trace) android.os.Trace.beginSection("NuvioXL Headroom measure")
        try {
            val height = (scene.headroomValues[rowKey] ?: target).roundToPx().coerceAtLeast(0)
            val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        } finally {
            if (trace) android.os.Trace.endSection()
        }
    }.testTag("xl-headroom-$rowKey"))
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
internal fun ObserveXlSceneMotion(scene: XlSceneState, list: LazyListState) {
    LocalHomeSceneInspection.current?.let { inspect -> SideEffect { inspect(scene, list) } }
    ObserveXlNavigationTrace(scene, list)
    AnimateXlVerticalMotion(scene, list)
    AnimateXlPreviewReturn(scene, list)
    val move = scene.transition
    val id = move?.id
    LaunchedEffect(scene, list, id) {
        if (move?.to?.isXl != true) return@LaunchedEffect
        // Release the expansion flag early; the scene-owned preview retains
        // its actual gap until the outgoing metadata mask has cleared.
        if (move.earlyPreviewReturn) scene.startPreviewReturn(move.id)
        snapshotFlow { scene.outgoingMetadataOffscreen(list.layoutInfo) }.first { it }
        scene.releasePreviewGap(move.id)
    }
    LaunchedEffect(scene, list, id) {
        if (move?.downwardExit != true) return@LaunchedEffect
        scene.exitMetadataAlpha = 0f
        snapshotFlow {
            (scene.outgoingXlBottom(list.layoutInfo) ?: 0f) <= 0f && (!move.downwardExit || scene.compactRowRested)
        }.first { it }
        animate(0f, 1f, animationSpec = tween(220)) { value, _ ->
            scene.exitMetadataAlpha = value
        }
    }
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
    var imageFadeMillis by mutableIntStateOf(0)
    var directEntry by mutableStateOf(false)
    var directExit by mutableStateOf(false)
    var continuousReveal by mutableStateOf(false)
    private var finishedToken by mutableIntStateOf(-1)
    private var finishedUrl by mutableStateOf<String?>(null)
    private var finishedBlurred by mutableStateOf(false)
    private var renderedToken by mutableIntStateOf(-1)
    private var expectedUrl by mutableStateOf<String?>(null)
    private var readyToken by mutableIntStateOf(-1)
    private var readyUrl by mutableStateOf<String?>(null)
    private var expectedBlurred by mutableStateOf(false)
    private var readyBlurred by mutableStateOf(false)

    fun expectFrame(renderToken: Int, url: String?, blurred: Boolean = this.blurred) {
        if (expectedUrl == url && expectedBlurred == blurred && readyToken == renderedToken && readyUrl == url &&
            readyBlurred == blurred) readyToken = renderToken
        if (expectedUrl == url && expectedBlurred == blurred && finishedToken == renderedToken &&
            finishedUrl == url && finishedBlurred == blurred) finishedToken = renderToken
        renderedToken = renderToken
        expectedUrl = url
        expectedBlurred = blurred
    }

    fun frameReady(renderToken: Int, url: String?, blurred: Boolean = this.blurred) {
        if (renderToken != token) return
        readyToken = renderToken
        readyUrl = url
        readyBlurred = blurred
    }

    fun transitionFinished(renderToken: Int, url: String?, blurred: Boolean) {
        if (renderToken == token && renderedToken == token && expectedUrl == url && expectedBlurred == blurred) {
            finishedToken = renderToken
            finishedUrl = url
            finishedBlurred = blurred
        }
    }

    fun transitionFinished(): Boolean = finishedToken == token && finishedUrl == expectedUrl && finishedBlurred == expectedBlurred

    fun ready(): Boolean = renderedToken == token && readyToken == token && readyUrl == expectedUrl &&
        readyBlurred == expectedBlurred
}

/** Either XL entry crossfades directly; an XL exit reveals its destination during the scroll. */
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
    SideEffect {
        if (XlNavigationTrace.enabled) XlNavigationTrace.backdrop = state
        XlNavigationTrace.event("BACKDROP") { "selected=${selected?.rowKey}/${selected?.itemKey} resolved=${resolvedHero?.title} presented=${state.preview?.title} token=${state.token} owned=${state.owned} blurred=${state.blurred} entering=${state.directEntry} exiting=${state.directExit} reveal=${state.continuousReveal} dim=${state.dim.value} gradient=${state.gradientAlpha.value} ready=${state.ready()} finished=${state.transitionFinished()} fade=${state.imageFadeMillis}" }
    }
    val frozen = enabled && scene.backdropPresentationFrozen
    // The top XL row and its platform icons share exactly one backdrop
    // identity. Focus transferring to those icons is NOT a new image commit.
    val selectionKey = if (frozen) null else selected?.let { it.rowKey to it.itemKey }
    val artworkKey = if (frozen) null else selected?.preview?.let { firstNonBlank(it.backdrop, it.imageUrl) }
    val latestCarouselFocused by rememberUpdatedState(carouselFocused)
    LaunchedEffect(scene, selectionKey, artworkKey, if (frozen) null else darkTarget,
        if (frozen || darkTarget) null else carouselFocused, enabled, frozen) {
        if (frozen) {
            if (!state.owned) state.preview = scene.navigationFrozenBackdrop ?: state.preview ?: latestResolved
            state.owned = true
            return@LaunchedEffect
        }
        val target = selected ?: return@LaunchedEffect
        val boundary = scene.transition
        val boundaryId = boundary?.id
        val dark = darkTarget
        val exitingXl = !dark && (state.blurred || state.directExit || boundary?.from?.isXl == true)
        state.directEntry = false
        state.continuousReveal = false
        if (!enabled) {
            state.directExit = false
            state.owned = false
            state.blurred = false
            state.dim.snapTo(0f)
            state.gradientAlpha.snapTo(1f)
            if (boundaryId != null) scene.complete(boundaryId)
            return@LaunchedEffect
        }
        if (scene.hasContinuousBackdropReveal(target)) {
            // Hold the exact previous presentation until the final artwork is
            // ready. A newly focused XL is not a cold-start backdrop.
            state.owned = true
            // During an early landing the row's artwork pipeline is still
            // intentionally frozen. The backdrop renderer independently
            // prepares and verifies the actual transformed bitmap.
            if (dark && !latestCarouselFocused && !scene.isEarlyContinuousBackdrop(target)) withTimeoutOrNull(2_000L) {
                snapshotFlow { scene.artwork.ready(target.rowKey, target.itemKey,
                    firstNonBlank(target.preview.backdrop, target.preview.imageUrl)) || latestCarouselFocused }.first { it }
            }
            // Keep the landing token pending while artwork is loading. An
            // enrichment recomposition may restart this effect for the same item.
            if (!scene.consumeContinuousBackdropReveal(target)) return@LaunchedEffect
            val needsDissolve = dark || state.blurred || state.directExit
            state.imageFadeMillis = 220
            state.directExit = !dark && needsDissolve
            state.directEntry = dark
            state.continuousReveal = true
            state.revealing = true
            state.token++
            state.preview = target.preview
            state.blurred = dark
            val revealToken = state.token
            try {
                repeat(2) { withFrameNanos { } }
                if (dark && !artworkKey.isNullOrBlank())
                    snapshotFlow { state.ready() }.first { it }
                else withTimeoutOrNull(2_000L) { snapshotFlow { state.ready() }.first { it } }
                kotlinx.coroutines.coroutineScope {
                    launch { state.dim.animateTo(if (dark) XL_BACKDROP_DIM_ALPHA else 0f, tween(220, easing = LinearEasing)) }
                    launch { state.gradientAlpha.animateTo(if (dark) 0f else 1f, tween(220, easing = LinearEasing)) }
                }
                if (needsDissolve) withTimeoutOrNull(2_000L) { snapshotFlow { state.transitionFinished() }.first { it } }
            } finally {
                if (state.token == revealToken && !scene.backdropPresentationFrozen) {
                    state.directEntry = false
                    state.directExit = false
                    state.continuousReveal = false
                }
            }
            if (!dark && latestResolved == target.preview) state.owned = false
            return@LaunchedEffect
        }
        // A vertical boundary must settle before publishing the next backdrop.
        // A later key cancels this wait and freezes the old presentation.
        if (boundaryId != null && !carouselFocused && scene.verticalInputIntent) {
            if (!state.owned) state.preview = boundary?.outgoingBackdrop ?: state.preview ?: latestResolved
            state.owned = true
            snapshotFlow { scene.transition?.let { it.id != boundaryId || it.rowsSettled } ?: true }.first { it }
            if (scene.selection?.rowKey != target.rowKey || scene.navigationPresentationFrozen) return@LaunchedEffect
        }
        if (exitingXl) {
            // The old composite remains visible while the destination loads.
            // Neither the dimmer nor the regular gradient inserts a black beat.
            state.owned = true
            state.revealing = true
            state.directExit = true
            state.imageFadeMillis = XL_BACKDROP_REVEAL_MS
            state.gradientAlpha.snapTo(0f)
            state.token++
            state.preview = scene.stationaryMetadata?.preview ?: target.preview
            state.blurred = false
            repeat(2) { withFrameNanos { } }
            withTimeoutOrNull(2_000) { snapshotFlow { state.ready() }.first { it } }
            state.dim.animateTo(0f, tween(XL_BACKDROP_REVEAL_MS, easing = LinearEasing))
            withTimeoutOrNull(2_000) { snapshotFlow { state.transitionFinished() }.first { it } }
            state.directExit = false
            state.gradientAlpha.snapTo(1f)
            if (boundaryId != null) {
                snapshotFlow { scene.transition?.let { it.id != boundaryId || it.rowsSettled } ?: true }.first { it }
                if (boundary?.downwardExit == true) snapshotFlow {
                    scene.transition?.id != boundaryId || scene.exitMetadataAlpha >= 1f
                }.first { it }
                scene.complete(boundaryId)
            }
            if (latestResolved == target.preview) state.owned = false
            return@LaunchedEffect
        }
        if (!target.isXl && !dark && boundaryId == null && !state.blurred) {
            state.directExit = false
            state.gradientAlpha.snapTo(1f)
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
        if (dark && target.isXl && boundary == null &&
            (!state.blurred || scene.pendingFastReturnXlBackdropKey == target.rowKey)) {
            state.directExit = false
            val doubleUpArrival = scene.consumeFastReturnXlBackdropReveal(target.rowKey)
            // A real first render intentionally initializes without a fade.
            // Double-Up is different: the ordinary backdrop has been visible
            // during the travel. Replacing it with a blurred XL image using
            // a zero-duration transition produces a visible one-frame snap.
            state.imageFadeMillis = if (doubleUpArrival) 220 else 0
            state.token++
            state.preview = target.preview
            state.owned = true
            state.blurred = true
            state.revealing = true
            if (doubleUpArrival) {
                state.directEntry = true
                val revealToken = state.token
                try {
                    repeat(2) { withFrameNanos { } }
                    if (!artworkKey.isNullOrBlank()) snapshotFlow { state.ready() }.first { it }
                    kotlinx.coroutines.coroutineScope {
                        launch { state.dim.animateTo(XL_BACKDROP_DIM_ALPHA, tween(220, easing = LinearEasing)) }
                        launch { state.gradientAlpha.animateTo(0f, tween(220, easing = LinearEasing)) }
                    }
                    withTimeoutOrNull(2_000L) { snapshotFlow { state.transitionFinished() }.first { it } }
                } finally {
                    // A second navigation can cancel this reveal. Do not let
                    // an obsolete job reset a later entry's animation flag.
                    if (state.token == revealToken) state.directEntry = false
                }
            } else {
                state.dim.snapTo(XL_BACKDROP_DIM_ALPHA)
                state.gradientAlpha.snapTo(0f)
            }
            return@LaunchedEffect
        }
        if (dark && target.isXl && boundary != null) {
            state.directExit = false
            if (!state.owned) state.preview = boundary.outgoingBackdrop ?: latestResolved
            state.owned = true
            // Keep the current composite visible while the prepared landscape
            // and blur are pending. Neither entry direction waits for a black beat or row settlement.
            if (!latestCarouselFocused) snapshotFlow { scene.artwork.ready(target.rowKey, target.itemKey,
                firstNonBlank(target.preview.backdrop, target.preview.imageUrl)) || latestCarouselFocused }.first { it }
            state.token++
            state.preview = target.preview
            state.blurred = true
            state.directEntry = true
            state.imageFadeMillis = 220
            state.revealing = true
            state.gradientAlpha.snapTo(0f)
            state.dim.animateTo(XL_BACKDROP_DIM_ALPHA, tween(220, easing = LinearEasing))
            snapshotFlow { scene.transition?.let { it.id != boundary.id || it.rowsSettled } ?: true }.first { it }
            scene.complete(boundary.id)
            state.directEntry = false
            return@LaunchedEffect
        }
        if (dark && state.blurred && boundaryId == null) {
            // Returning from another XL row already has the XL dimmer; the
            // existing 220ms image transition is sufficient.
            scene.consumeFastReturnXlBackdropReveal(target.rowKey)
            state.directExit = false
            // No normal-row debounce or fade-to-black on a horizontal XL move.
            // The row releases this barrier when its matching landscape and
            // blur are prepared; the image crossfade starts on that commit.
            val previousUrl = firstNonBlank(state.preview?.backdrop, state.preview?.imageUrl)
            val nextUrl = firstNonBlank(target.preview.backdrop, target.preview.imageUrl)
            if (previousUrl == nextUrl) {
                state.preview = target.preview
                state.owned = true
                return@LaunchedEffect
            }
            if (!latestCarouselFocused) snapshotFlow { scene.artwork.ready(target.rowKey, target.itemKey,
                firstNonBlank(target.preview.backdrop, target.preview.imageUrl)) || latestCarouselFocused }.first { it }
            state.imageFadeMillis = 220
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
    LaunchedEffect(resolvedHero, state.owned, scene.transition, selected?.isXl, scene.navigationPresentationFrozen, state.continuousReveal) {
        if (scene.navigationPresentationFrozen || state.continuousReveal) return@LaunchedEffect
        scene.resolveStationaryMetadata(resolvedHero)
        if (scene.transition == null && !state.directExit && selected?.isXl != true && state.dim.value == 0f &&
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
    val fast = scene.fastReturn
    val viewportHeight = LocalConfiguration.current.screenHeightDp * density
    val outgoing by remember(scene, list, enabled, ordinaryTopPx, viewportHeight) {
        derivedStateOf {
            val bulk = scene.fastReturn
            if (bulk != null) bulk.metadata != null && bulk.alpha > 0f &&
                scene.metadataTopFor(bulk.metadata?.rowKey).value * density + scene.fastReturnTranslation(list.layoutInfo) + bulk.clipTop < viewportHeight
            else enabled && scene.transition?.outgoingMetadata != null &&
                xlSceneOutgoingMetadataVisible(scene, list.layoutInfo, ordinaryTopPx, viewportHeight, density)
        }
    }
    val snapshot = when {
        enabled && scene.navigationPresentationFrozen -> if (scene.rapidLandingMetadata)
            selected?.takeUnless { it.isXl }?.let { XlMetadataSnapshot(it.rowKey, it.preview) } else null
        fast != null -> if (outgoing) fast.metadata else null
        !enabled -> resolvedHero?.let { XlMetadataSnapshot(selected?.rowKey.orEmpty(), it) }
        outgoing -> move?.outgoingMetadata
        selected?.isXl == true -> null
        move != null -> scene.stationaryMetadata ?: selected?.let { XlMetadataSnapshot(it.rowKey, it.preview) }
        else -> scene.stationaryMetadata ?: (if (backdrop.owned) selected?.preview else resolvedHero)?.let {
            XlMetadataSnapshot(selected?.rowKey.orEmpty(), it)
        }
    }
    if (snapshot == null) {
        SideEffect { scene.hideMetadata() }
    }
    val epoch = if (fast != null) fast.epoch else if (outgoing) move?.outgoingMetadataEpoch ?: scene.metadataEpoch else scene.metadataEpoch
    val metadataLayoutTop = remember(scene) { floatArrayOf(0f) }
    val paintedLayer = remember(scene) { floatArrayOf(0f, 1f) }
    // Keep a stable slot through ordinary row changes. An XL exit promotes the
    // destination slot that was already precomposed with the next epoch.
    val slot = remember(scene, epoch) { XlMetadataSlot(snapshot?.rowKey ?: selected?.rowKey.orEmpty(), epoch) }
    XlMetadataHost(
        activeSlot = slot,
        activeSnapshot = snapshot,
        neighbors = if (enabled && !scene.navigationPresentationFrozen && selected?.isXl == true) adjacentMetadata else emptyList(),
        nextEpoch = scene.metadataEpoch + 1,
        modifier = modifier.zIndex(1f),
        retainPreparedLayout = enabled && (move != null || fast != null)
    ) { active, isActive ->
        HeroTitleBlock(
            preview = active.preview,
            portraitMode = portraitMode,
            selectedPlatformId = selectedPlatformId,
            platformNavDirection = platformNavDirection,
            platformTransitionSnap = platformTransitionSnap,
            fullWidthIconRowEnabled = fullWidthIconRowEnabled,
            heroMetadataLarge = heroMetadataLarge,
            animateTitleLogo = isActive && !scene.rapidLandingMetadata && (!enabled || (move == null && !backdrop.owned)),
            contentModifier = Modifier.onSizeChanged {
                scene.metadataHeights[active.rowKey] = it.height.toFloat()
                if (isActive) scene.metadataHeightPx = it.height.toFloat()
            },
            modifier = if (!isActive) Modifier else Modifier.testTag("ordinary-metadata-${active.rowKey}").graphicsLayer {
                val incomingOffset = if (enabled && move != null && !outgoing && !move.deferOrdinaryReveal && !move.downwardExit && scene.stationaryMetadata == null)
                    scene.incomingTranslation(list.layoutInfo, ordinaryTopPx, density) else 0f
                translationY = when {
                    fast != null -> scene.fastReturnTranslation(list.layoutInfo)
                    outgoing -> scene.outgoingTranslation(list.layoutInfo)
                    enabled && move?.downwardExit == true -> 0f
                    enabled && move != null && !move.deferOrdinaryReveal -> incomingOffset ?: 0f
                    else -> 0f
                }
                alpha = if (enabled && scene.rapidLandingMetadata && scene.continuousVerticalNavigation)
                    scene.rapidLandingMetadataAlpha
                else if (enabled && (scene.navigationPresentationFrozen || (scene.selection?.isXl == true && !outgoing && fast == null))) 0f else if (fast != null) fast.alpha else if (enabled && move?.downwardExit == true && !outgoing) scene.exitMetadataAlpha
                else if (enabled && move?.deferOrdinaryReveal == true && !outgoing) {
                    if (move.rowsSettled) 1f else 0f
                } else if (outgoing) move?.initialMetadataAlpha ?: 0f else if (incomingOffset == null) 0f else 1f
                paintedLayer[0] = translationY
                paintedLayer[1] = alpha
                val edge = if (!outgoing && move?.downwardExit == true) scene.outgoingXlBottom(list.layoutInfo) else null
                val clipTop = if (fast != null) fast.clipTop else if (outgoing) move?.outgoingMetadataClipTop ?: 0f
                    else if (edge != null) (edge - metadataLayoutTop[0] - translationY).coerceAtLeast(0f) else 0f
                val top = metadataLayoutTop[0] + translationY
                val visible = maxOf(top + clipTop, 0f) < minOf(top + size.height, viewportHeight)
                scene.recordMetadata(active, translationY, if (visible) alpha else 0f, epoch, clipTop)
            }.onGloballyPositioned {
                // Subtract the translation actually applied by this layer, not
                // a newly measured row offset from a different layout phase.
                metadataLayoutTop[0] = it.positionInRoot().y - paintedLayer[0]
            }.drawWithContent {
                // Reveal below the departing row's edge. The next hero may be
                // nearer than a full hero-height gap, especially in compact XL.
                // Never cut its artwork or final summary lines with that mask.
                val offset = paintedLayer[0]
                val edge = if (move?.downwardExit == true) scene.outgoingXlBottom(list.layoutInfo) else null
                val top = when {
                    fast != null -> fast.clipTop
                    enabled && outgoing -> move?.outgoingMetadataClipTop ?: 0f
                    enabled && edge != null -> (edge - metadataLayoutTop[0] - offset).coerceAtLeast(0f)
                    else -> 0f
                }
                val screenTop = metadataLayoutTop[0] + offset
                val visible = maxOf(screenTop + top, 0f) < minOf(screenTop + size.height, viewportHeight)
                XlNavigationTrace.event("METADATA_DRAW") { "row=${active.rowKey} title=${active.preview.title} top=$screenTop height=${size.height} clip=$top alpha=${paintedLayer[1]} visible=$visible outgoing=$outgoing epoch=$epoch currentEpoch=${scene.metadataEpoch}" }
                scene.recordMetadata(active, offset, if (visible) paintedLayer[1] else 0f, epoch, top)
                if (top < size.height) {
                    if (top > 0f) clipRect(top = top) { this@drawWithContent.drawContent() } else drawContent()
                }
            }
        )
    }
}
