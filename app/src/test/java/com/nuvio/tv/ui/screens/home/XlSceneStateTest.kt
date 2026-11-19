package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.unit.dp
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class XlSceneStateTest {
    private fun selection(key: String, index: Int, xl: Boolean = false, summary: Boolean = true) =
        XlSceneSelection(key, key, index, xl, summary,
            HeroPreview(key, null, key, "Movie", yearText = null, genres = emptyList(), poster = null, backdrop = key, imageUrl = null))
    private fun layout() = mockk<LazyListLayoutInfo> { every { visibleItemsInfo } returns emptyList() }

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
        assertEquals(true, scene.expandedCompactRows["XL"])
        assertNull(xlSceneMetadataMask(scene, layout(), true, 260f, 1f))
        val exit = checkNotNull(scene.transition).id
        scene.rowsSettled(exit); scene.complete(exit)
        assertEquals(true, scene.expandedCompactRows["XL"])
        scene.focus(selection("D", 4), layout())
        assertEquals(true, scene.expandedCompactRows["XL"])
        scene.focus(xl, layout())
        assertEquals(true, scene.expandedCompactRows["XL"])
        val enter = checkNotNull(scene.transition).id
        scene.rowsSettled(enter); scene.complete(enter)
        assertNull(scene.expandedCompactRows["XL"])
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
}
