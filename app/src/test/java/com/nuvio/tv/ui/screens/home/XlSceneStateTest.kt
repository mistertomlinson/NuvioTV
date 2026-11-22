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
        scene.recordFocusPaint(motion.id - 1)
        assertFalse(signal.isCompleted)
        scene.recordFocusPaint(motion.id)
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

}
