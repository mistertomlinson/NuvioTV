package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.unit.dp
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class XlSceneStateTest {
    private fun selection(key: String, index: Int, xl: Boolean = false, summary: Boolean = true) =
        XlSceneSelection(key, key, index, xl, summary,
            HeroPreview(key, null, key, "Movie", yearText = null, genres = emptyList(), poster = null, backdrop = key, imageUrl = null))
    private fun layout() = mockk<LazyListLayoutInfo> { every { visibleItemsInfo } returns emptyList() }

    @Test fun `rapid ordinary navigation holds the last painted XL exit until the latest resolved hero`() {
        val scene = XlSceneState(selection("XL", 2, true))
        val first = selection("C", 3)
        val painted = first.preview.copy(description = "Enriched visible metadata")
        scene.focus(first, layout())
        val exit = checkNotNull(scene.transition).id
        scene.exitMetadataAlpha = 0.25f
        scene.recordMetadata(XlMetadataSnapshot("C", painted), alpha = 0.25f)
        scene.focus(selection("D", 4), layout())
        scene.focus(selection("E", 5), layout())
        assertEquals("Keep clearance ownership through rapid navigation", exit, scene.transition?.id)
        assertEquals("Further keys cannot restart a running fade", 0.25f, scene.exitMetadataAlpha, 0f)
        assertEquals(XlMetadataSnapshot("C", painted), scene.stationaryMetadata)
        scene.exitMetadataAlpha = 1f
        scene.rowsSettled(exit); scene.complete(exit)
        assertNull(scene.transition)
        assertEquals(XlMetadataSnapshot("C", painted), scene.stationaryMetadata)
        scene.resolveStationaryMetadata(first.preview)
        assertEquals("C", scene.stationaryMetadata?.rowKey)
        scene.resolveStationaryMetadata(scene.selection?.preview)
        assertNull(scene.stationaryMetadata)
    }

    @Test fun `a completed immediate exit does not keep bypassing the ordinary debounce`() {
        val scene = XlSceneState(selection("XL", 2, true))
        val first = selection("C", 3)
        scene.focus(first, layout())
        scene.recordMetadata(XlMetadataSnapshot("C", first.preview))
        scene.complete(checkNotNull(scene.transition).id)
        assertEquals("C", scene.stationaryMetadata?.rowKey)
        scene.focus(selection("D", 4), layout())
        assertNull(scene.transition)
        assertEquals("C", scene.stationaryMetadata?.rowKey)
    }

    @Test fun `returning into XL carries the actually visible held hero`() {
        val xl = selection("XL", 2, true)
        val scene = XlSceneState(xl)
        val first = selection("C", 3)
        scene.focus(first, layout())
        scene.recordMetadata(XlMetadataSnapshot("C", first.preview))
        scene.focus(selection("D", 4), layout())
        scene.focus(xl, layout())
        assertEquals("C", scene.transition?.outgoingMetadata?.rowKey)
        assertNull(scene.stationaryMetadata)
    }

    @Test fun `return restoration chooses the saved XL title before the default first row`() {
        val saved = selection("XL", 1, true)
        fun row(selection: XlSceneSelection) = HeroCarouselRow(selection.rowKey, selection.rowKey,
            selection.rowIndex, listOf(ModernCarouselItem(selection.itemKey, selection.itemKey, null, null,
                selection.preview, ModernPayload.Catalog(selection.itemKey, selection.itemKey, "movie", "", selection.itemKey, null, "movie"))),
            isXl = selection.isXl)
        val rows = listOf(row(selection("A", 0)), row(saved))
        assertEquals(saved, restoredHomeSceneSelection(rows, "XL", 0))
        assertEquals(saved, restoredHomeSceneSelection(rows, "XL", 999))
        assertNull(restoredHomeSceneSelection(rows, "gone", 0))
        assertNull(restoredHomeSceneSelection(emptyList(), "XL", 0))
    }

    @Test fun `boundary preserves the painted ordinary preview rather than substituting an adjacent row`() {
        val ordinary = selection("A", 1)
        val scene = XlSceneState(ordinary)
        val painted = ordinary.preview.copy(description = "Visible enriched metadata")
        scene.recordMetadata(XlMetadataSnapshot("A", painted))
        scene.recordBackdrop(painted)
        scene.focus(selection("XL", 2, true), layout())
        assertEquals(painted, scene.transition?.outgoingMetadata?.preview)
        assertEquals(painted, scene.transition?.outgoingBackdrop)
        val id = checkNotNull(scene.transition).id
        scene.rowsSettled(id); scene.complete(id)
        assertNull(scene.transition)
        assertTrue(scene.selection?.isXl == true)
    }

    @Test fun `ordinary navigation never starts a moving metadata boundary`() {
        val scene = XlSceneState(selection("A", 1))
        scene.focus(selection("B", 2), layout())
        assertNull(scene.transition)
        assertEquals(0f to 260f, xlSceneMetadataMask(scene, layout(), true, 260f, 1f))
    }

    @Test fun `scroll displacement continues after the original owner leaves the composed window`() {
        val anchor = XlScrollAnchor(mapOf("A" to 260, "XL" to 510))
        assertEquals(-100f, anchor.displacement(mapOf("A" to 160, "XL" to 410)), 0f)
        assertEquals(-350f, anchor.displacement(mapOf("XL" to 160, "C" to 610)), 0f)
        assertEquals(-450f, anchor.displacement(mapOf("XL" to 60, "C" to 510)), 0f)
        assertEquals(-450f, anchor.displacement(mapOf("XL" to 60, "C" to 510)), 0f)
    }

    @Test fun `layout scroll anchor matches map tracking during row recycling`() {
        val anchor = XlScrollAnchor.fromLayout(positionedLayout("A" to 260, "XL" to 510))
        assertEquals(-100f, anchor.displacementFromLayout(positionedLayout("A" to 160, "XL" to 410)), 0f)
        assertEquals(-350f, anchor.displacementFromLayout(positionedLayout("XL" to 160, "C" to 610)), 0f)
        assertEquals(-450f, anchor.displacementFromLayout(positionedLayout("XL" to 60, "C" to 510)), 0f)
        assertEquals("Repeated draws cannot advance the anchor",
            -450f, anchor.displacementFromLayout(positionedLayout("XL" to 60, "C" to 510)), 0f)
        assertEquals("A frame with no visible rows preserves accumulated distance",
            -450f, anchor.displacementFromLayout(layout()), 0f)
        assertEquals("First row after a fully recycled window establishes a new anchor",
            -450f, anchor.displacementFromLayout(positionedLayout("C" to 480)), 0f)
        assertEquals(-480f, anchor.displacementFromLayout(positionedLayout("C" to 450)), 0f)
    }

    @Test fun `layout anchor includes padding and handles visible row buffer growth`() {
        fun frame(padding: Int, vararg positions: Pair<String, Int>): LazyListLayoutInfo = mockk {
            every { beforeContentPadding } returns padding
            every { visibleItemsInfo } returns positions.mapIndexed { i, (rowKey, y) ->
                mockk<androidx.compose.foundation.lazy.LazyListItemInfo> {
                    every { key } returns rowKey
                    every { index } returns i
                    every { offset } returns y
                }
            }
        }
        val anchor = XlScrollAnchor.fromLayout(frame(20, "A" to 260))
        assertEquals("A padding change is real coordinate movement",
            10f, anchor.displacementFromLayout(frame(50, "A" to 240)), 0f)
        val wide = (0 until 16).map { "row-$it" to (500 + it * 80) }.toTypedArray()
        assertEquals("No shared row means no spurious jump",
            10f, anchor.displacementFromLayout(frame(50, *wide)), 0f)
        val shifted = (0 until 16).map { "row-$it" to (495 + it * 80) }.toTypedArray()
        assertEquals("The expanded buffer follows the first shared key",
            5f, anchor.displacementFromLayout(frame(50, *shifted)), 0f)
    }

    @Test fun `compact exit retains its opened gap until return to that XL has finished`() {
        val xl = selection("XL", 2, true, false)
        val scene = XlSceneState(xl)
        scene.focus(selection("C", 3), layout())
        assertTrue(scene.transition?.compactExit == true)
        assertEquals(true, scene.expandedXlRows["XL"])
        // No XL item remains in this layout; normal poster exclusion can
        // resume while the prepared metadata is still waiting to fade.
        assertEquals(0f to 260f, xlSceneMetadataMask(scene, layout(), true, 260f, 1f))
        assertEquals(0f, scene.exitMetadataAlpha, 0f)
        assertFalse(checkNotNull(scene.transition).deferOrdinaryReveal)
        val exit = checkNotNull(scene.transition).id
        scene.exitMetadataAlpha = 1f
        scene.rowsSettled(exit); scene.complete(exit)
        assertEquals(true, scene.expandedXlRows["XL"])
        scene.focus(selection("D", 4), layout())
        assertEquals(true, scene.expandedXlRows["XL"])
        scene.focus(xl, layout())
        assertEquals(true, scene.expandedXlRows["XL"])
        val enter = checkNotNull(scene.transition).id
        scene.rowsSettled(enter); scene.complete(enter)
        assertNull(scene.expandedXlRows["XL"])
    }

    @Test fun `an interrupted transition cannot complete a newer one`() {
        val scene = XlSceneState(selection("A", 1))
        scene.focus(selection("XL", 2, true), layout())
        val first = checkNotNull(scene.transition).id
        scene.focus(selection("A", 1), layout())
        val second = checkNotNull(scene.transition).id
        scene.rowsSettled(first); scene.complete(first)
        assertEquals(second, scene.transition?.id)
        assertFalse(checkNotNull(scene.transition).rowsSettled)
        assertEquals(1, scene.metadataEpoch)
    }

    @Test fun `only the committed backdrop frame can release the black handoff`() {
        val state = XlBackdropPresentation(null, false)
        state.token = 7
        state.expectFrame(7, "B")
        state.frameReady(6, "B"); assertFalse(state.ready())
        state.frameReady(7, "A"); assertFalse(state.ready())
        state.frameReady(7, "B"); assertTrue(state.ready())
        state.token = 8
        state.expectFrame(8, "B")
        assertTrue("An unchanged already-rendered bitmap needs no second load callback", state.ready())
    }

    @Test fun `a sharp callback cannot release a blurred frame with the same URL`() {
        val state = XlBackdropPresentation(null, false)
        state.token = 1
        state.expectFrame(1, "same", false)
        state.frameReady(1, "same", false)
        assertTrue(state.ready())
        state.token = 2
        state.expectFrame(2, "same", true)
        assertFalse(state.ready())
        state.frameReady(2, "same", false)
        assertFalse("The old variant must not reveal an unprepared blur", state.ready())
        state.frameReady(2, "same", true)
        assertTrue(state.ready())
        state.token = 3
        state.expectFrame(3, "same", false)
        assertFalse("An XL exit must wait for the sharp variant", state.ready())
    }

    @Test fun `reversing a compact exit cannot invent metadata that was still transparent`() {
        val xl = selection("XL", 2, true, false)
        val normal = selection("C", 3)
        val scene = XlSceneState(xl)
        scene.focus(normal, layout())
        scene.recordMetadata(XlMetadataSnapshot("C", normal.preview), alpha = 0f)
        scene.focus(xl, layout())
        assertNull(scene.transition?.outgoingMetadata)
    }

    @Test fun `a second down keeps the original visible outgoing block until it leaves the screen`() {
        val ordinary = selection("A", 1)
        val scene = XlSceneState(ordinary)
        scene.recordMetadata(XlMetadataSnapshot("A", ordinary.preview))
        scene.focus(selection("XL", 2, true, false), layout())
        scene.recordMetadata(XlMetadataSnapshot("A", ordinary.preview), -40f)
        scene.focus(selection("C", 3), layout())
        assertEquals("A", scene.transition?.outgoingMetadata?.rowKey)
        assertEquals(-40f, checkNotNull(scene.transition).initialMetadataTranslation, 0f)
        assertTrue(checkNotNull(scene.transition).deferOrdinaryReveal)
    }

    @Test fun `reversing back to the visible owner preserves its metadata composition`() {
        val ordinary = selection("A", 1)
        val scene = XlSceneState(ordinary)
        scene.recordMetadata(XlMetadataSnapshot("A", ordinary.preview))
        scene.focus(selection("XL", 2, true), layout())
        scene.focus(ordinary, layout())
        assertEquals(0, scene.metadataEpoch)
        assertTrue(checkNotNull(scene.transition).keepMetadataVisible)
        assertFalse(checkNotNull(scene.transition).deferOrdinaryReveal)
    }
    @Test fun `exiting XL cannot revive its date in the ordinary badge from a frozen hero`() {
        val xl = selection("XL", 1, true).let { it.copy(preview = it.preview.copy(comingSoonText = "NOVEMBER 30")) }
        val normal = selection("C", 2)
        val scene = XlSceneState(xl)
        val backdrop = XlBackdropPresentation(xl.preview, true)
        scene.focus(normal, layout())
        assertNull(ordinaryComingSoonText(scene, backdrop, xl.preview, false))
        val id = checkNotNull(scene.transition).id
        scene.rowsSettled(id)
        assertNull(ordinaryComingSoonText(scene, backdrop, xl.preview, false))
        scene.complete(id)
        assertNull(ordinaryComingSoonText(scene, backdrop, xl.preview, false))
    }

    @Test fun `a following ordinary coming soon badge uses its own date only after landing`() {
        val xl = selection("XL", 1, true).let { it.copy(preview = it.preview.copy(comingSoonText = "NOVEMBER 30")) }
        val next = selection("C", 2).let { it.copy(preview = it.preview.copy(comingSoonText = "DECEMBER 4")) }
        val scene = XlSceneState(xl)
        val backdrop = XlBackdropPresentation(xl.preview, true)
        scene.focus(next, layout())
        assertNull(ordinaryComingSoonText(scene, backdrop, xl.preview, false))
        scene.rowsSettled(checkNotNull(scene.transition).id)
        assertEquals("DECEMBER 4", ordinaryComingSoonText(scene, backdrop, xl.preview, false))
        assertNull(ordinaryComingSoonText(scene, backdrop, xl.preview, true))
    }

    @Test fun `a reversal retains the visible portion of a clipped incoming metadata block`() {
        val xl = selection("XL", 1, true, false)
        val normal = selection("C", 2)
        val scene = XlSceneState(xl)
        scene.focus(normal, layout())
        scene.recordMetadata(XlMetadataSnapshot("C", normal.preview), 130f, 1f, clipTop = 180f)
        scene.focus(xl, layout())
        assertEquals("C", scene.transition?.outgoingMetadata?.rowKey)
        assertEquals(180f, checkNotNull(scene.transition).outgoingMetadataClipTop, 0f)
    }


    private fun positionedLayout(vararg positions: Pair<String, Int>): LazyListLayoutInfo = mockk {
        every { beforeContentPadding } returns 0
        every { visibleItemsInfo } returns positions.mapIndexed { i, (rowKey, top) ->
            mockk<androidx.compose.foundation.lazy.LazyListItemInfo> {
                every { key } returns rowKey
                every { index } returns i
                every { offset } returns top
                every { size } returns 280
            }
        }
    }

    @Test fun `logical destination cannot start the scroll before real focus arrives`() {
        val scene = XlSceneState(selection("XL", 1, true, false))
        val launched = mutableListOf<XlVerticalMotion>()
        scene.verticalLauncher = { launched += it }
        val next = selection("C", 2)
        val layout = positionedLayout("XL" to 54, "C" to 500)
        scene.armBoundaryFocus()
        scene.focus(next, layout, focusConfirmed = false)
        assertTrue(scene.boundaryFocusArmed)
        assertNotNull(scene.verticalMotion)
        assertTrue(launched.isEmpty())
        scene.confirmFocus("another-row")
        assertTrue(launched.isEmpty())
        scene.confirmFocus("C")
        assertFalse(scene.boundaryFocusArmed)
        assertEquals(1, launched.size)
        scene.focus(next, layout, focusConfirmed = false)
        scene.confirmFocus("C")
        assertEquals("Focus callbacks cannot restart the same movement", 1, launched.size)
    }

    @Test fun `CW exit uses its measured short row and returns the XL prefix on the same clock`() {
        val scene = XlSceneState(selection("XL", 1, true))
        scene.rowHeights["continue_watching"] = 180.dp
        scene.headroomValues["XL"] = 45.dp
        scene.metadataLayoutHeightPx = 220f
        scene.metadataHeights["continue_watching"] = 140f
        val layout = positionedLayout("XL" to 20)
        scene.focus(selection("continue_watching", 0), layout, focusConfirmed = false)
        val motion = checkNotNull(scene.verticalMotion)
        assertTrue("Use the offscreen CW row's real height", motion.scrollPx < 0f)
        assertEquals(XlGapMotion("XL", 45.dp, 0.dp), motion.secondaryGap)
        assertEquals(0.dp, motion.toGap)
        assertEquals("Prepare the actual shorter metadata position before moving", 134.dp, scene.metadataRestingTop)
    }

    @Test fun `double Up carries old metadata off screen and does not mask the first XL card`() {
        val old = selection("D", 6)
        val first = selection("XL", 0, true)
        val scene = XlSceneState(old)
        scene.recordMetadata(XlMetadataSnapshot("D", old.preview))
        scene.beginFastReturn(first, positionedLayout("D" to 260))
        assertEquals(0f to 260f, xlSceneMetadataMask(scene, positionedLayout("D" to 260), true, 260f, 1f, 540f))
        assertNull("The old metadata band has slid below the viewport",
            xlSceneMetadataMask(scene, positionedLayout("D" to 860), true, 260f, 1f, 540f))
        scene.finishFastReturn(first)
        assertEquals(first, scene.selection)
        assertNull(scene.transition)
        assertNull(scene.fastReturn)
        assertNull(xlSceneMetadataMask(scene, positionedLayout("XL" to 54), true, 260f, 1f, 540f))
    }

    @Test fun `a first ordinary row uses its reachable natural header instead of an impossible extra six pixels`() {
        val scene = XlSceneState(selection("continue_watching", 0))
        scene.headerHeights["continue_watching"] = 34f
        val layout = positionedLayout("continue_watching" to 0)
        every { layout.beforeContentPadding } returns 260
        assertEquals(34f, scene.ordinaryPosterInset("continue_watching", 0), 0f)
        assertEquals(0f, checkNotNull(scene.incomingTranslation(layout, 260f, 1f)), 0f)
        assertEquals("Other ordinary rows keep their existing anchor", 40f, scene.ordinaryPosterInset("C", 2), 0f)
    }

    @Test fun `prepared metadata reserves the same gap before and after XL exit focus`() {
        val scene = XlSceneState(selection("XL", 1, true))
        scene.metadataRestingTop = 54.dp
        scene.metadataLayoutHeightPx = 220f
        scene.metadataHeights["C"] = 140f
        val before = scene.followingHeadroom("XL", "C", 1f, true)
        scene.focus(selection("C", 2), layout())
        assertEquals(before, scene.followingHeadroom("XL", "C", 1f, true))
    }
    @Test fun `actual focus rebuilds motion when logical selection preceded row measurement`() {
        val scene = XlSceneState(selection("A", 1))
        val xl = selection("XL", 2, true, false)
        val launched = mutableListOf<XlVerticalMotion>()
        scene.verticalLauncher = { launched += it }
        scene.rowHeights["A"] = 240.dp
        scene.focus(xl, layout(), focusConfirmed = false)
        assertNotNull(scene.transition)
        assertNull("The recycled row is not measured yet", scene.verticalMotion)
        scene.focus(xl, positionedLayout("A" to 0, "XL" to 500), focusConfirmed = true)
        assertNotNull("Actual focus must retry with the newly measured destination", scene.verticalMotion)
        assertEquals(1, launched.size)
        scene.confirmFocus("XL", positionedLayout("A" to 0, "XL" to 500))
        assertEquals("The late-measurement retry cannot start another owner", 1, launched.size)
    }

    @Test fun `compact landing uses one physical pixel anchor at fractional densities`() {
        for (scale in listOf(1.25f, 1.5f, 2.625f, 3f)) {
            val scene = XlSceneState(selection("XL", 1, true, false)).apply {
                density = scale
                ordinaryTop = 259.2f.dp
                headerHeights["C"] = (39.7f * scale).toInt().toFloat()
                headroomValues["C"] = 7.3f.dp
            }
            val next = selection("C", 2)
            val startTop = (500 * scale).toInt()
            scene.focus(next, positionedLayout("XL" to 40, "C" to startTop), focusConfirmed = false)
            val motion = checkNotNull(scene.verticalMotion)
            assertEquals("The list receives a whole physical-pixel distance", motion.scrollPx.toInt().toFloat(), motion.scrollPx, 0f)
            scene.headroomValues["C"] = motion.toGap
            val landed = positionedLayout("C" to startTop - motion.scrollPx.toInt())
            assertEquals("No residual relocation after releasing the owned spring at density $scale", 0f,
                checkNotNull(scene.incomingTranslation(landed, scene.ordinaryTop.value * scale, scale)), 0f)
        }
    }

    @Test fun `stale focus draws cannot release a newer motion and cancellation releases its waiter`() {
        val scene = XlSceneState(selection("XL", 1, true, false))
        scene.focus(selection("C", 2), positionedLayout("XL" to 40, "C" to 500), focusConfirmed = false)
        val motion = checkNotNull(scene.verticalMotion)
        val signal = scene.requestFocusPaint(motion.id)
        scene.recordFocusPaint(motion.id - 1, motion.id)
        assertFalse(signal.isCompleted)
        scene.recordFocusPaint(motion.id, motion.id)
        assertTrue(signal.isCompleted)
        val waiting = scene.requestFocusPaint(motion.id)
        scene.cancelVerticalMotion()
        assertTrue("An interrupted or detached scene cannot leave a suspended scroll owner", waiting.isCancelled)
        assertNull(scene.verticalMotion)
    }

    @Test fun `canceling focus preparation releases ownership before resuming its waiter`() = kotlinx.coroutines.runBlocking {
        val scene = XlSceneState(selection("XL", 1, true, false))
        scene.focus(selection("C", 2), positionedLayout("XL" to 40, "C" to 500), focusConfirmed = false)
        val motion = checkNotNull(scene.verticalMotion)
        val signal = scene.requestFocusPaint(motion.id)
        var ownerWhenReleased: XlVerticalMotion? = motion
        val waiter = launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            try { signal.await() } finally { ownerWhenReleased = scene.verticalMotion }
        }
        scene.cancelVerticalMotion()
        waiter.join()
        assertNull("A canceled waiter cannot clear a still-published old owner", ownerWhenReleased)
        assertEquals("Preserve the gap before cancellation resumes work", motion.fromGap, scene.heldHeadroomGoals["C"])
    }

    @Test fun `compact entry and exit require their own committed focus composition`() {
        for (entering in listOf(false, true)) {
            val xl = selection("XL", 1, true, false)
            val ordinary = selection("C", 2)
            val scene = XlSceneState(if (entering) ordinary else xl)
            val oldComposition = scene.compactFocusCompositionEpoch
            scene.focus(if (entering) xl else ordinary, positionedLayout("XL" to 54, "C" to 500), focusConfirmed = false)
            val motion = checkNotNull(scene.verticalMotion)
            val signal = scene.requestFocusPaint(motion.id)
            assertEquals(motion.id, scene.compactFocusCompositionEpoch)
            scene.recordFocusPaint(motion.id, oldComposition)
            assertFalse("An old cached composition cannot acknowledge new focus", signal.isCompleted)
            scene.recordFocusPaint(motion.id, scene.compactFocusCompositionEpoch)
            assertTrue(signal.isCompleted)
        }
    }

    @Test fun `summary entry and exit require the newly committed focus composition`() {
        for (entering in listOf(false, true)) {
            val xl = selection("XL", 1, true, true)
            val ordinary = selection("C", 2)
            val scene = XlSceneState(if (entering) ordinary else xl)
            val oldComposition = scene.compactFocusCompositionEpoch
            scene.focus(if (entering) xl else ordinary, positionedLayout("XL" to 54, "C" to 500), focusConfirmed = false)
            val motion = checkNotNull(scene.verticalMotion)
            val signal = scene.requestFocusPaint(motion.id)
            assertEquals(motion.id, scene.compactFocusCompositionEpoch)
            scene.recordFocusPaint(motion.id, oldComposition)
            assertFalse("An old summary preview draw cannot acknowledge new focus", signal.isCompleted)
            scene.recordFocusPaint(motion.id, scene.compactFocusCompositionEpoch)
            assertTrue(signal.isCompleted)
        }
    }

    @Test fun `compact frame anchor uses actual lazy index and excludes content padding`() {
        val scene = XlSceneState(selection("XL", 1, true, false))
        val layout = positionedLayout("XL" to 54, "C" to 500)
        every { layout.beforeContentPadding } returns 37
        scene.focus(selection("C", 2), layout, focusConfirmed = false)
        val anchor = checkNotNull(scene.verticalMotion?.itemAnchor)
        assertEquals("Use the actual item index, not a stale logical row index", 1, anchor.index)
        assertEquals("Offsets are relative to the content-padding origin", -500, anchor.scrollOffset)
    }

    @Test fun `rapid same direction taps suppress geometry and metadata until the final landing`() {
        for (summary in listOf(false, true)) {
            val xl = selection("XL", 0, true, summary)
            val c = selection("C", 1)
            val d = selection("D", 2)
            fun row(item: XlSceneSelection) = HeroCarouselRow(item.rowKey, item.rowKey,
                item.rowIndex, listOf(ModernCarouselItem(item.itemKey, item.itemKey, null, null,
                    item.preview, ModernPayload.Catalog(item.itemKey, item.itemKey, "movie", "", item.itemKey, null, "movie"))),
                isXl = item.isXl, showXlSummary = item.summary)
            val rows = listOf(row(xl), row(c), row(d))
            val scene = XlSceneState(xl, rows)
            scene.noteVerticalPress(1, 1000L, false)
            assertFalse(scene.continuousVerticalNavigation)
            scene.focus(c, positionedLayout("XL" to 54, "C" to 500), rows = rows, focusConfirmed = false)
            assertNotNull(scene.verticalMotion)
            scene.noteVerticalPress(1, 1100L, false)
            assertTrue(scene.continuousVerticalNavigation)
            assertTrue(scene.continuousGapsCollapsed)
            assertNull(scene.verticalMotion)
            assertNull(scene.transition)
            assertEquals(0, scene.gapPx("C"))
            assertNull(xlSceneMetadataMask(scene, layout(), true, 260f, 1f))
            scene.focus(d, layout(), rows = rows)
            scene.confirmFocus("D", layout(), rows)
            scene.recordMetadata(XlMetadataSnapshot("C", c.preview))
            assertNull(scene.stationaryMetadata)
            assertNull(scene.verticalMotion)
            scene.prepareContinuousLanding(rows)
            assertTrue(scene.continuousVerticalNavigation)
            assertFalse(scene.continuousGapsCollapsed)
            assertNull(xlSceneMetadataMask(scene, layout(), true, 260f, 1f))
            scene.finishContinuousNavigation()
            assertFalse(scene.continuousVerticalNavigation)
            assertEquals(XlMetadataSnapshot("D", d.preview), scene.stationaryMetadata)
            assertEquals(0f to 260f, xlSceneMetadataMask(scene, layout(), true, 260f, 1f))
        }
    }

    @Test fun `a held key collapses gaps and a new press cancels pending restoration`() {
        val scene = XlSceneState(selection("XL", 0, true))
        scene.headroomValues["C"] = 140.dp
        scene.noteVerticalPress(1, 1000L, true)
        assertTrue(scene.continuousGapsCollapsed)
        assertEquals(0, scene.gapPx("C"))
        scene.prepareContinuousLanding(emptyList())
        assertFalse(scene.continuousGapsCollapsed)
        scene.headroomValues["C"] = 140.dp
        scene.noteVerticalPress(-1, 1050L, false)
        assertTrue(scene.continuousGapsCollapsed)
        assertEquals(0, scene.gapPx("C"))
        assertNull(scene.transition)
    }

    @Test fun `rapid alternating directions share the compact navigation owner`() {
        val scene = XlSceneState(selection("XL", 0, true))
        scene.noteVerticalPress(1, 1000L, false)
        scene.noteVerticalRelease()
        scene.noteVerticalPress(-1, 1375L, false)
        assertTrue(scene.continuousVerticalNavigation)
        assertTrue(scene.continuousGapsCollapsed)
        scene.finishContinuousNavigation()
        scene.noteVerticalPress(-1, 1850L, false)
        assertFalse(scene.continuousVerticalNavigation)
    }

    @Test fun `single XL tap defers the opening until intent is resolved`() {
        for (summary in listOf(false, true)) {
            val xl = selection("XL", 0, true, summary)
            val normal = selection("C", 1)
            val scene = XlSceneState(xl)
            val positions = positionedLayout("XL" to 54, "C" to 500)
            scene.noteVerticalPress(1, 1000L, false)
            scene.armDeferredDownwardExit("XL")
            scene.focus(normal, positions, focusConfirmed = true)
            assertTrue(scene.deferredDownwardExit)
            assertEquals(normal, scene.selection)
            val motion = checkNotNull(scene.verticalMotion)
            assertTrue(motion.coupledExit)
            assertEquals(0f, scene.gapPx("C") + scene.fixedFollowerTranslationPx, 0.01f)
            assertTrue(checkNotNull(scene.transition).downwardExit)
            assertNull(xlSceneMetadataMask(scene, positions, true, 260f, 1f))
            scene.noteVerticalRelease()
            scene.resolveDeferredDownwardExit(positions, emptyList())
            assertFalse(scene.deferredDownwardExit)
            assertFalse(scene.continuousVerticalNavigation)
            assertEquals(normal, scene.selection)
            assertTrue(checkNotNull(scene.transition).downwardExit)
            assertNotNull(scene.verticalMotion)
        }
    }

    @Test fun `held XL exit never expands the first follower gap`() {
        val xl = selection("XL", 0, true)
        val normal = selection("C", 1)
        val scene = XlSceneState(xl)
        val positions = positionedLayout("XL" to 54, "C" to 500)
        scene.recordBackdrop(xl.preview)
        scene.noteVerticalPress(1, 1000L, false)
        scene.armDeferredDownwardExit("XL")
        scene.focus(normal, positions)
        scene.resolveDeferredDownwardExit(positions, emptyList())
        assertTrue(scene.continuousVerticalNavigation)
        assertTrue(scene.continuousGapsCollapsed)
        assertEquals(normal, scene.selection)
        assertEquals(xl.preview, scene.navigationFrozenBackdrop)
        assertNull(scene.transition)
        assertEquals(0, scene.gapPx("C"))
        assertTrue(scene.verticalKeyHeld)
    }

    @Test fun `rapid taps promote deferred XL exit before gap expansion`() {
        val xl = selection("XL", 0, true)
        val normal = selection("C", 1)
        val scene = XlSceneState(xl)
        val positions = positionedLayout("XL" to 54, "C" to 500)
        scene.noteVerticalPress(1, 1000L, false)
        scene.armDeferredDownwardExit("XL")
        scene.focus(normal, positions)
        scene.noteVerticalRelease()
        scene.noteVerticalPress(1, 1225L, false)
        assertFalse(scene.deferredDownwardExit)
        assertTrue(scene.continuousGapsCollapsed)
        assertNull(scene.verticalMotion)
        assertEquals(0, scene.gapPx("C"))
        scene.focus(xl, positions)
        scene.prepareContinuousLanding(emptyList())
        scene.finishContinuousNavigation()
        assertTrue(scene.consumeContinuousBackdropReveal(xl))
        assertFalse(scene.consumeContinuousBackdropReveal(xl))
        assertNull(scene.stationaryMetadata)
    }

    @Test fun `obsolete backdrop ready callback cannot overwrite matching final token`() {
        val state = XlBackdropPresentation(null, false)
        state.token = 2
        state.expectFrame(2, "final", true)
        state.frameReady(2, "final", true)
        state.frameReady(1, "obsolete", false)
        assertTrue(state.ready())
    }

    @Test fun `summary gap trim leaves the anchor and portrait clearance unchanged`() {
        val height = 426f
        val anchor = xlBalancedTop(540f, height, true)
        val trim = xlSummaryTrailingGapReduction(540f, anchor, height, 24f, 8f)
        assertTrue(trim > 0f)
        assertTrue(trim <= 20f)
        assertEquals(anchor, xlBalancedTop(540f, height, true), 0f)
        assertEquals(8f, 540f - (anchor + height + 24f - trim + 24f), 0.01f)
    }

    @Test fun `ordinary landing clears a visible preceding row even when no structural spacer changes`() {
        val plan = xlLandingPaintPlan(listOf("XL", "A", "B", "C"),
            listOf(0f, 116f, 0f, 0f), 2, null, true, 490f, 48f)
        assertEquals(538f, plan.virtualClearancePx, 0.01f)
        assertEquals(-538f, plan.offsets["A"] ?: 0f, 0.01f)
        assertEquals(0f, plan.offsets["B"] ?: 0f, 0.01f)
        assertEquals(0f, plan.offsets["C"] ?: 0f, 0.01f)
        assertTrue(490f + plan.offsets.getValue("A") < 0f)
    }

    @Test fun `XL landing keeps only the actual structural spacing correction`() {
        val plan = xlLandingPaintPlan(listOf("A", "XL", "B", "C"),
            listOf(0f, 116f, 0f, 0f), 1, null, false, 490f, 48f)
        assertEquals(0f, plan.virtualClearancePx, 0f)
        assertEquals(-116f, plan.offsets.getValue("A"), 0f)
        assertFalse(plan.offsets.containsKey("XL"))
        assertFalse(plan.offsets.containsKey("B"))
    }

    @Test fun `rapid separation begins during travel and metadata waits for clearance and alignment`() {
        val target = selection("B", 2)
        val scene = XlSceneState(target)
        val plan = XlLandingPaintPlan(mapOf("A" to -538f), 538f)
        scene.beginContinuousNavigation()
        repeat(12) { scene.advanceRapidLanding(target, 80f, 0.016f) { plan } }
        assertTrue("The preceding row moves before the selected row reaches its anchor",
            scene.continuousLandingOffsetPx("A") < -1f)
        assertEquals(0f, scene.continuousLandingOffsetPx("B"), 0f)
        assertFalse(scene.rapidLandingMetadata)
        repeat(24) { scene.advanceRapidLanding(target, 16f, 0.016f) { plan } }
        assertEquals(-538f, scene.continuousLandingOffsetPx("A"), 0.01f)
        assertFalse("Metadata cannot appear while the selected row is still traveling", scene.rapidLandingMetadata)
        repeat(12) { scene.advanceRapidLanding(target, 0f, 0.016f) { plan } }
        assertTrue(scene.rapidLandingReady)
        assertTrue(scene.rapidLandingMetadata)
        assertEquals(1f, scene.rapidLandingMetadataAlpha, 0f)
        assertTrue(scene.continuousGapsCollapsed)
        assertNull(xlSceneMetadataMask(scene, layout(), true, 260f, 1f))
        val rows = listOf(selection("XL", 0, true), selection("A", 1), target).map { item ->
            HeroCarouselRow(item.rowKey, item.rowKey, item.rowIndex,
                listOf(ModernCarouselItem(item.itemKey, item.itemKey, null, null, item.preview,
                    ModernPayload.Catalog(item.itemKey, item.itemKey, "movie", "", item.itemKey, null, "movie"))),
                isXl = item.isXl)
        }
        scene.prepareContinuousLanding(rows)
        assertFalse(scene.continuousGapsCollapsed)
        assertEquals("No frame may expose the preceding row before the final mask commits",
            -538f, scene.continuousLandingOffsetPx("A"), 0.01f)
        assertTrue(scene.rapidLandingMetadata)
        assertNull(xlSceneMetadataMask(scene, layout(), true, 260f, 1f))
        scene.finishContinuousNavigation()
        assertEquals(0f, scene.continuousLandingOffsetPx("A"), 0f)
        assertEquals(0f to 260f, xlSceneMetadataMask(scene, layout(), true, 260f, 1f))
    }

    @Test fun `another press retargets partial clearance without resetting the painted row`() {
        val first = selection("B", 2)
        val next = selection("C", 3)
        val scene = XlSceneState(first)
        scene.beginContinuousNavigation()
        repeat(12) {
            scene.advanceRapidLanding(first, 50f, 0.016f) {
                XlLandingPaintPlan(mapOf("A" to -538f), 538f)
            }
        }
        val before = scene.continuousLandingOffsetPx("A")
        assertTrue(before < -1f && before > -538f)
        scene.noteVerticalPress(1, 1000L, false)
        scene.focus(next, layout(), focusConfirmed = true)
        assertEquals("Input must retain the current painted position", before, scene.continuousLandingOffsetPx("A"), 0f)
        scene.advanceRapidLanding(next, 300f, 0.016f) { error("Distant target must not open a gap") }
        val after = scene.continuousLandingOffsetPx("A")
        assertTrue("The old gap reverses gradually", after > before && after < 0f)
        assertFalse(scene.rapidLandingMetadata)
        assertFalse(scene.rapidLandingReady)
    }

    @Test fun `rapid downward travel retains and moves the outgoing metadata mask`() {
        val scene = XlSceneState(selection("B", 1))
        scene.captureBurstOutgoingMask(0f to 260f)
        scene.beginContinuousNavigation()
        scene.focus(selection("XL", 2, true), layout())
        assertEquals(0f to 260f, xlSceneMetadataMask(scene, layout(), true, 260f, 1f))
        scene.advanceBurstOutgoingMask(72f)
        assertEquals(-72f to 188f, xlSceneMetadataMask(scene, layout(), true, 260f, 1f))
        scene.advanceBurstOutgoingMask(188f)
        assertEquals(-260f to 0f, scene.retainedBurstOutgoingMask())
        scene.advanceBurstOutgoingMask(32f)
        assertNull(xlSceneMetadataMask(scene, layout(), true, 260f, 1f))
        scene.advanceBurstOutgoingMask(-20f)
        assertNull("A retired mask must not reappear on reversal", scene.retainedBurstOutgoingMask())
    }

    @Test fun `held scroll and reversal preserve the same painted metadata and mask until offscreen`() {
        val first = selection("A", 1)
        val scene = XlSceneState(first).apply { screenHeight = 540.dp; density = 1f }
        val painted = XlMetadataSnapshot(first.rowKey, first.preview)
        scene.recordMetadata(painted, translation = 12f, alpha = 0.65f)
        scene.captureBurstOutgoingMask(12f to 272f)
        scene.beginContinuousNavigation()
        scene.advanceBurstOutgoingMask(72f)
        assertEquals(-60f to 200f, scene.retainedBurstOutgoingMask())
        assertEquals(-60f, scene.retainedBurstPresentation!!.translation, 0f)
        scene.noteVerticalPress(-1, 1000L, false)
        scene.focus(selection("XL", 0, true), layout())
        scene.startHeldVerticalScroll()
        assertEquals(painted, scene.retainedBurstPresentation!!.metadata)
        assertEquals(0.65f, scene.retainedBurstPresentation!!.alpha, 0f)
        scene.advanceBurstOutgoingMask(-40f)
        assertEquals(-20f to 240f, scene.retainedBurstOutgoingMask())
        assertEquals(-20f, scene.retainedBurstPresentation!!.translation, 0f)
        scene.advanceBurstOutgoingMask(-560f)
        assertNull(scene.retainedBurstPresentation)
        scene.advanceBurstOutgoingMask(20f)
        assertNull("An offscreen retired block cannot resurrect", scene.retainedBurstPresentation)
    }

    @Test fun `ordinary rapid landing grows its mask before metadata and keeps row geometry`() {
        val target = selection("B", 2)
        val scene = XlSceneState(target)
        scene.beginContinuousNavigation()
        val plan = XlLandingPaintPlan(emptyMap(), 0f)
        repeat(10) { scene.advanceRapidLanding(target, 80f, 0.016f) { plan } }
        val partial = checkNotNull(scene.continuousPresentationMask(260f))
        assertTrue(partial.second in 0.01f..259.99f)
        assertFalse(scene.rapidLandingMetadata)
        assertEquals(0f, scene.continuousLandingOffsetPx("A"), 0f)
        repeat(32) { scene.advanceRapidLanding(target, 0f, 0.016f) { plan } }
        assertEquals(0f to 260f, scene.continuousPresentationMask(260f))
        assertTrue(scene.rapidLandingReady)
        assertEquals(1f, scene.rapidLandingMetadataAlpha, 0f)
    }

    @Test fun `compact takeover starts at the previous painted row offset`() {
        val target = selection("B", 2)
        val scene = XlSceneState(target)
        scene.beginContinuousNavigation()
        scene.seedCompactHandoff(mapOf("A" to -82f, "C" to 120f))
        assertEquals(-82f, scene.continuousLandingOffsetPx("A"), 0f)
        assertEquals(120f, scene.continuousLandingOffsetPx("C"), 0f)
        scene.advanceRapidLanding(target, 300f, 0.016f) { error("No distant landing") }
        assertTrue(scene.continuousLandingOffsetPx("A") > -82f)
        assertTrue(scene.continuousLandingOffsetPx("C") < 120f)
        assertFalse(scene.rapidLandingMetadata)
    }

    @Test fun `a logical boundary and an unmeasured target cannot count as a completed landing`() {
        assertFalse(xlLandingAligned(null))
        assertFalse(xlLandingAligned(Float.NEGATIVE_INFINITY))
        assertFalse(xlLandingAligned(Float.POSITIVE_INFINITY))
        assertFalse(xlLandingAligned(Float.NaN))
        assertFalse(xlLandingAligned(-213f))
        assertFalse(xlLandingAligned(213f))
        assertFalse(xlLandingAligned(1.01f))
        assertTrue(xlLandingAligned(-1f))
        assertTrue(xlLandingAligned(0f))
        assertTrue(xlLandingAligned(1f))
    }

    @Test fun `an obsolete first Up timeout cannot release a newer backdrop hold`() {
        val original = selection("XL", 3, true)
        val scene = XlSceneState(original)
        scene.recordBackdrop(original.preview)
        val first = scene.armDoubleUpBackdrop()
        assertTrue(scene.backdropPresentationFrozen)
        scene.focus(selection("C", 2), layout())
        scene.recordBackdrop(scene.selection?.preview)
        assertEquals(original.preview, scene.navigationFrozenBackdrop)
        val second = scene.armDoubleUpBackdrop()
        scene.releaseDoubleUpBackdrop(first)
        assertTrue(scene.backdropPresentationFrozen)
        scene.releaseDoubleUpBackdrop(second)
        assertFalse(scene.backdropPresentationFrozen)
        assertNull(scene.navigationFrozenBackdrop)
    }

    @Test fun `new vertical press invalidates an earlier metadata reveal`() {
        val scene = XlSceneState(selection("A", 1))
        scene.noteVerticalPress(1, 1000L, false)
        val obsolete = scene.verticalMetadataEpoch
        assertEquals(0f, scene.verticalMetadataAlpha, 0f)
        scene.advanceVerticalMetadataAlpha(obsolete, 1f)
        assertEquals("Held key cannot reveal incoming metadata", 0f, scene.verticalMetadataAlpha, 0f)
        scene.noteVerticalRelease()
        scene.advanceVerticalMetadataAlpha(obsolete, 0.4f)
        assertEquals(0.4f, scene.verticalMetadataAlpha, 0f)
        scene.noteVerticalPress(-1, 1200L, false)
        scene.noteVerticalRelease()
        scene.advanceVerticalMetadataAlpha(obsolete, 1f)
        assertEquals("Obsolete reveal cannot set the new epoch's opacity", 0f, scene.verticalMetadataAlpha, 0f)
        scene.advanceVerticalMetadataAlpha(scene.verticalMetadataEpoch, 1f)
        assertEquals(1f, scene.verticalMetadataAlpha, 0f)
    }

    @Test fun `first Up retains the visible outgoing ordinary block until the XL focus commits`() {
        val ordinary = selection("A", 2)
        val scene = XlSceneState(ordinary)
        scene.recordMetadata(XlMetadataSnapshot(ordinary.rowKey, ordinary.preview))
        scene.noteVerticalPress(-1, 1000L, false)
        assertEquals("The destination gate is closed", 0f, scene.verticalMetadataAlpha, 0f)
        assertEquals("Do not erase the old block before the focus handoff",
            1f, scene.verticalMetadataGateFor(ordinary.rowKey), 0f)
        scene.recordMetadata(XlMetadataSnapshot(ordinary.rowKey, ordinary.preview),
            alpha = scene.verticalMetadataGateFor(ordinary.rowKey))
        scene.focus(selection("XL", 1, true), layout())
        assertEquals(ordinary.rowKey, scene.transition?.outgoingMetadata?.rowKey)
        assertEquals(0f, scene.verticalMetadataGateFor("XL"), 0f)
    }

    @Test fun `aligned upward XL exit becomes ordinary stationary metadata even before the backdrop finishes`() {
        val scene = XlSceneState(selection("XL", 2, true))
        val ordinary = selection("A", 1)
        scene.noteVerticalPress(-1, 1000L, false)
        scene.focus(ordinary, layout(), focusConfirmed = false)
        scene.noteVerticalRelease()
        assertTrue(checkNotNull(scene.transition).upwardExit)
        assertEquals("Prepared upward metadata is not faded in", 1f, scene.verticalMetadataAlpha, 0f)
        val anchor = scene.posterAnchorPx(ordinary) - scene.headerPx(ordinary.rowKey) - scene.gapPx(ordinary.rowKey)
        val landed = positionedLayout(ordinary.rowKey to anchor)
        assertFalse(scene.promoteAlignedUpwardExit(landed, scrolling = true))
        assertTrue(scene.promoteAlignedUpwardExit(landed, scrolling = false))
        assertNull(scene.transition)
        scene.noteVerticalPress(-1, 1320L, false, ordinaryOnly = true)
        scene.focus(selection("Earlier", 0), landed)
        assertFalse(scene.continuousVerticalNavigation)
        assertEquals(ordinary.preview, scene.ordinaryVerticalHold)
        assertEquals(1f, scene.verticalMetadataAlpha, 0f)
        assertNotNull(xlSceneMetadataMask(scene, landed, true, scene.ordinaryTop.value, 1f))
    }

    @Test fun `interrupted upward exit preserves the first ordinary block until the latest resolved destination`() {
        val scene = XlSceneState(selection("XL", 3, true))
        val first = selection("A", 2)
        val second = selection("B", 1)
        val last = selection("C", 0)
        scene.noteVerticalPress(-1, 1000L, false)
        scene.focus(first, layout(), focusConfirmed = false)
        scene.noteVerticalRelease()
        assertTrue(checkNotNull(scene.transition).upwardExit)
        scene.recordMetadata(XlMetadataSnapshot(first.rowKey, first.preview), translation = -84f)
        assertTrue(scene.retainXlExitForOrdinaryNavigation(layout()))
        scene.noteVerticalPress(-1, 1180L, false, ordinaryOnly = true)
        scene.focus(second, layout())
        scene.noteVerticalRelease()
        assertFalse(scene.continuousVerticalNavigation)
        assertNull(scene.transition)
        assertEquals(first.preview, scene.ordinaryVerticalHold)
        assertEquals(-84f, scene.ordinaryVerticalHoldOffset, 0f)
        assertEquals(-84f to 176f, xlSceneMetadataMask(scene, layout(), true, 260f, 1f))
        val earlierEpoch = scene.verticalMetadataEpoch
        scene.advanceOrdinaryHoldOffset(earlierEpoch, -24f)
        scene.noteVerticalPress(-1, 1320L, false, ordinaryOnly = true)
        scene.focus(last, layout())
        scene.noteVerticalRelease()
        assertEquals(-24f, scene.ordinaryVerticalHoldOffset, 0f)
        scene.markOrdinaryHoldSettled(earlierEpoch)
        scene.commitOrdinaryHoldIfResolved(last.preview)
        assertEquals("Obsolete settlement cannot release the retained block", first.preview, scene.ordinaryVerticalHold)
        val epoch = scene.verticalMetadataEpoch
        scene.advanceOrdinaryHoldOffset(epoch, 0f)
        scene.markOrdinaryHoldSettled(epoch)
        scene.commitOrdinaryHoldIfResolved(second.preview)
        assertEquals("An intermediate resolved hero cannot replace the retained block", first.preview, scene.ordinaryVerticalHold)
        scene.commitOrdinaryHoldIfResolved(last.preview)
        assertNull(scene.ordinaryVerticalHold)
        assertEquals(0f to 260f, xlSceneMetadataMask(scene, layout(), true, 260f, 1f))
    }

    @Test fun `rapid continuation into XL retires the ordinary mask and metadata before settling`() {
        val scene = XlSceneState(selection("BottomXL", 3, true)).apply {
            screenHeight = 540.dp
            density = 1f
        }
        val first = selection("A", 2)
        val second = selection("B", 1)
        val xl = selection("TopXL", 0, true)
        scene.noteVerticalPress(-1, 1000L, false)
        scene.focus(first, layout(), focusConfirmed = false)
        scene.noteVerticalRelease()
        scene.recordMetadata(XlMetadataSnapshot(first.rowKey, first.preview), translation = -48f)
        assertTrue(scene.retainXlExitForOrdinaryNavigation(layout()))
        scene.noteVerticalPress(-1, 1180L, false, ordinaryOnly = true)
        scene.focus(second, layout())
        scene.noteVerticalRelease()
        scene.captureBurstOutgoingMask(xlSceneMetadataMask(scene, layout(), true, 260f, 1f))
        scene.noteVerticalPress(-1, 1320L, false)
        scene.focus(xl, layout())
        scene.noteVerticalRelease()
        assertNull(scene.ordinaryVerticalHold)
        assertEquals(first.preview, scene.retainedBurstPresentation?.metadata?.preview)
        assertEquals(-48f, scene.retainedBurstPresentation!!.translation, 0f)
        scene.advanceBurstOutgoingMask(-320f)
        val plan = XlLandingPaintPlan(emptyMap(), 0f)
        repeat(10) { scene.advanceRapidLanding(xl, -80f, 0.016f) { plan } }
        assertNull("Retire the block during XL approach, without waiting for metadata debounce",
            scene.retainedBurstPresentation)
        assertNull(xlSceneMetadataMask(scene, layout(), true, 260f, 1f))
        repeat(40) { scene.advanceRapidLanding(xl, 0f, 0.016f) { plan } }
        assertTrue(scene.rapidLandingReady)
        assertFalse(scene.rapidLandingMetadata)
        scene.prepareContinuousLanding(emptyList())
        scene.finishContinuousNavigation()
        assertNull(scene.retainedBurstPresentation)
        assertNull(xlSceneMetadataMask(scene, layout(), true, 260f, 1f))
    }

    @Test fun `partly faded downward exit remains visible through later ordinary presses`() {
        val scene = XlSceneState(selection("XL", 0, true))
        val first = selection("A", 1)
        val second = selection("B", 2)
        val last = selection("C", 3)
        scene.noteVerticalPress(1, 1000L, false)
        scene.focus(first, layout(), focusConfirmed = false)
        scene.noteVerticalRelease()
        assertTrue(checkNotNull(scene.transition).downwardExit)
        assertFalse("An invisible intermediate downward destination may be skipped",
            scene.retainXlExitForOrdinaryNavigation(layout()))
        scene.exitMetadataAlpha = 0.5f
        scene.advanceVerticalMetadataAlpha(scene.verticalMetadataEpoch, 0.6f)
        scene.recordMetadata(XlMetadataSnapshot(first.rowKey, first.preview), alpha = 0.3f)
        assertTrue(scene.retainXlExitForOrdinaryNavigation(layout()))
        assertEquals(0.3f, scene.ordinaryVerticalHoldAlpha, 0f)
        scene.noteVerticalPress(1, 1180L, false, ordinaryOnly = true)
        scene.focus(second, layout())
        scene.noteVerticalRelease()
        assertFalse(scene.continuousVerticalNavigation)
        assertNull(scene.transition)
        assertEquals(first.preview, scene.ordinaryVerticalHold)
        assertEquals(0.3f, scene.ordinaryVerticalHoldAlpha, 0f)
        assertEquals(0f to 260f, xlSceneMetadataMask(scene, layout(), true, 260f, 1f))
        val previousEpoch = scene.verticalMetadataEpoch
        scene.advanceOrdinaryHoldAlpha(previousEpoch, 0.55f)
        scene.noteVerticalPress(1, 1320L, false, ordinaryOnly = true)
        scene.focus(last, layout())
        scene.noteVerticalRelease()
        assertEquals(0.55f, scene.ordinaryVerticalHoldAlpha, 0f)
        scene.advanceOrdinaryHoldAlpha(previousEpoch, 1f)
        assertEquals("A canceled reveal cannot overwrite the current frame", 0.55f, scene.ordinaryVerticalHoldAlpha, 0f)
        val epoch = scene.verticalMetadataEpoch
        scene.advanceOrdinaryHoldAlpha(epoch, 0f)
        assertEquals("An existing visible block never fades back to zero", 0.55f, scene.ordinaryVerticalHoldAlpha, 0f)
        scene.advanceOrdinaryHoldAlpha(epoch, 1f)
        scene.markOrdinaryHoldSettled(epoch)
        scene.commitOrdinaryHoldIfResolved(second.preview)
        assertEquals(first.preview, scene.ordinaryVerticalHold)
        scene.commitOrdinaryHoldIfResolved(last.preview)
        assertNull(scene.ordinaryVerticalHold)
    }

}
