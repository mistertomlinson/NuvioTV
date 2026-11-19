package com.nuvio.tv.ui.screens.home

import android.app.Application
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import kotlin.math.abs

/** Real LazyColumn/focus motion, production metadata ownership, gap and backdrop clocks. */
@OptIn(ExperimentalFoundationApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModernXlSceneTransitionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var scene: XlSceneState
    private lateinit var list: LazyListState
    private lateinit var backdrop: XlBackdropPresentation
    private val frameReady = mutableStateOf(true)
    private val resolved = mutableStateOf<HeroPreview?>(null)
    private val requesters = List(5) { FocusRequester() }
    private lateinit var rows: List<HeroCarouselRow>

    @After fun cleanup() {
        if (::activity.isInitialized) compose.runOnUiThread { activity.pause().stop().destroy() }
    }

    private fun content(start: Int = 1, summary: Boolean = true) {
        rows = listOf(row("Earlier", 0), row("A", 1), row("XL", 2, true, summary), row("C", 3), row("D", 4))
        scene = XlSceneState(rows[start].sceneSelection(0, start), rows)
        list = LazyListState(start)
        resolved.value = scene.selection?.preview
        lateinit var seek: () -> Unit
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity.get().setContent {
                MaterialTheme {
                    backdrop = rememberXlBackdropPresentation(scene, true, resolved.value, false)
                    val visibleBackdrop = if (backdrop.owned) backdrop.preview else scene.transition?.outgoingBackdrop ?: resolved.value
                    val token = backdrop.token
                    SideEffect {
                        scene.recordBackdrop(visibleBackdrop)
                        backdrop.expectFrame(token, visibleBackdrop?.backdrop)
                        if (frameReady.value) backdrop.frameReady(token, visibleBackdrop?.backdrop)
                    }
                    ObserveXlSceneMotion(scene, list)
                    val scope = rememberCoroutineScope()
                    seek = { scope.launch { list.scrollToItem(start, if (rows[start].isXl) 206 else 0) }; Unit }
                    val spec = remember {
                        object : BringIntoViewSpec {
                            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                                offset - (if (scene.selection?.isXl == true) 54f else 260f) - 40f
                        }
                    }
                    Box(Modifier.width(960.dp).height(540.dp).background(Color.Black).testTag("scene")) {
                        CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
                            LazyColumn(
                                state = list,
                                modifier = Modifier.fillMaxSize().xlSceneRowsViewport(scene, list, true, 260.dp),
                                contentPadding = PaddingValues(top = 260.dp, bottom = 540.dp),
                                verticalArrangement = Arrangement.spacedBy(24.dp)
                            ) {
                                itemsIndexed(rows, key = { _, it -> it.key }) { index, row ->
                                    Column(Modifier.testTag("row-${row.key}")) {
                                        XlSceneRowHeadroom(scene, row.key, rows.getOrNull(index - 1), row.isXl, 466.dp)
                                        Text(row.title, Modifier.height(40.dp).padding(start = 52.dp).testTag("heading-${row.key}"))
                                        Box(Modifier.padding(start = 52.dp).width(700.dp)
                                            .height(if (row.isXl) 244.dp else 200.dp)
                                            .background(if (row.isXl) Color.Magenta else Color.Blue)
                                            .focusRequester(requesters[index])
                                            .onFocusChanged {
                                                if (it.isFocused) {
                                                    val selected = checkNotNull(row.sceneSelection(0, index))
                                                    scene.focus(selected, list.layoutInfo, rows = rows)
                                                    resolved.value = selected.preview
                                                }
                                            }.focusable().testTag("poster-${row.key}"))
                                        if (row.isXl) Box(Modifier.padding(start = 52.dp, top = 12.dp).width(430.dp)) {
                                            HeroTitleContent(row.items.first().heroPreview, true,
                                                showTitleLogo = false, showDescription = row.showXlSummary,
                                                useFullSizeDescription = true)
                                        }
                                    }
                                }
                            }
                        }
                        ModernXlAwareHeroTitleBlock(
                            scene, backdrop, true, resolved.value, list, 260f, 1f,
                            true, "home", 0, false, false, true,
                            Modifier.padding(start = 52.dp, top = 24.dp).width(430.dp)
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { seek() }
        compose.waitForIdle()
        compose.runOnIdle { requesters[start].requestFocus() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun press(code: Int) {
        compose.runOnUiThread {
            ShadowSystemClock.advanceBy(Duration.ofMillis(120))
            val time = SystemClock.uptimeMillis()
            activity.get().dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0))
            activity.get().dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, code, 0))
        }
        compose.waitForIdle()
    }
    private fun advance(ms: Int) {
        repeat((ms + 15) / 16) { compose.mainClock.advanceTimeBy(16); compose.waitForIdle() }
    }
    private fun settle() {
        var stableFrames = 0
        repeat(240) {
            advance(16)
            if (scene.transition == null && !list.isScrollInProgress && scene.headroomMoving.values.none { it }) stableFrames++
            else stableFrames = 0
            if (stableFrames >= 4) return
        }
        fail("Scene did not settle: ${scene.transition}, dim=${backdrop.dim.value}, gap=${scene.headroomValues}")
    }
    private fun awaitRows() {
        repeat(240) { advance(16); if (scene.transition?.rowsSettled == true) return }
        fail("Rows did not settle: ${scene.transition}, gap=${scene.headroomValues}")
    }
    private fun y(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
        .fetchSemanticsNode().layoutInfo.coordinates.positionInRoot().y
    private fun pixel(x: Int, y: Int): Int {
        var result = 0
        compose.runOnUiThread {
            val view = activity.get().findViewById<android.view.View>(android.R.id.content)
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            result = bitmap.getPixel(x, y)
            bitmap.recycle()
        }
        return result
    }
    private fun metadataCount() = compose.onAllNodes(hasTestTag("ordinary-metadata-A") or
        hasTestTag("ordinary-metadata-C") or hasTestTag("ordinary-metadata-XL"), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test fun enteringXlKeepsThePaintedOutgoingBlockAndSlidesItWithTheRow() {
        content()
        val oldTop = y("ordinary-metadata-A")
        val destinationTop = y("heading-XL")
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Synopsis A", substring = true).assertExists()
        compose.onNodeWithText("Synopsis Earlier", substring = true).assertDoesNotExist()
        advance(32)
        assertEquals(y("heading-XL") - destinationTop, y("ordinary-metadata-A") - oldTop, 2f)
        settle()
        assertTrue(scene.selection?.isXl == true)
        assertEquals(0, metadataCount())
        compose.onNodeWithText("Synopsis A", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Synopsis XL", substring = true).assertExists()
        assertEquals(XL_BACKDROP_DIM_ALPHA, backdrop.dim.value, 0.001f)
    }

    @Test fun upwardExitBringsOrdinaryMetadataAndItsPostersDownTogether() {
        content(start = 2)
        press(KeyEvent.KEYCODE_DPAD_UP)
        advance(32)
        val header = y("heading-A")
        val meta = y("ordinary-metadata-A")
        advance(80)
        assertEquals(y("heading-A") - header, y("ordinary-metadata-A") - meta, 2f)
        settle()
        assertEquals("A", scene.selection?.rowKey)
        assertEquals(1, metadataCount())
        assertEquals(260f, y("heading-A"), 2f)
    }

    @Test fun compactExitClearsAllXlPixelsBeforeMetadataAndBackdropRevealTogether() {
        content(start = 2, summary = false)
        frameReady.value = false
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitRows()
        assertEquals("C", scene.selection?.rowKey)
        assertEquals(260f, y("heading-C"), 2f)
        assertEquals(1f, backdrop.dim.value, 0.001f)
        assertFalse(backdrop.revealing)
        val outgoing = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "XL" }
        assertTrue("XL must be entirely above the screen", outgoing == null || outgoing.offset + list.layoutInfo.beforeContentPadding + outgoing.size <= 0)
        assertEquals(0f to 260f, xlSceneMetadataMask(scene, list.layoutInfo, true, 260f, 1f))
        frameReady.value = true
        advance(112)
        assertTrue(backdrop.revealing)
        assertTrue(backdrop.dim.value in 0.01f..0.99f)
        settle()
        assertEquals(0f, backdrop.dim.value, 0.001f)
        assertEquals(1, metadataCount())
        assertEquals(0f to 260f, xlSceneMetadataMask(scene, list.layoutInfo, true, 260f, 1f))
    }

    @Test fun returnToCompactXlKeepsOutgoingMetadataUntilItHasMovedOffScreen() {
        content(start = 2, summary = false)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        settle()
        press(KeyEvent.KEYCODE_DPAD_UP)
        // The preceding XL row was correctly virtualized while off screen.
        advance(32)
        val oldTop = y("ordinary-metadata-C")
        val destinationTop = y("heading-XL")
        advance(80)
        assertEquals(y("heading-XL") - destinationTop, y("ordinary-metadata-C") - oldTop, 2f)
        settle()
        assertEquals("XL", scene.selection?.rowKey)
        assertEquals(0, metadataCount())
        assertEquals(0.dp, scene.headroomValues["C"])
    }

    @Test fun enteringXlDoesNotSwapBackdropUntilItIsBlackAndWaitsForTheNewFrame() {
        content()
        frameReady.value = false
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        advance(80)
        assertEquals("Backdrop A", backdrop.preview?.backdrop)
        assertTrue(backdrop.dim.value in 0.01f..0.99f)
        advance(240)
        assertEquals("Backdrop XL", backdrop.preview?.backdrop)
        assertEquals(1f, backdrop.dim.value, 0.001f)
        assertFalse(backdrop.revealing)
        frameReady.value = true
        settle()
        assertEquals(XL_BACKDROP_DIM_ALPHA, backdrop.dim.value, 0.001f)
    }

    @Test fun summaryExitBringsTheIncomingBlockUpWithItsPosters() {
        content(start = 2, summary = true)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        advance(32)
        val header = y("heading-C")
        val meta = y("ordinary-metadata-C")
        advance(80)
        assertEquals(y("heading-C") - header, y("ordinary-metadata-C") - meta, 2f)
        settle()
        assertEquals(260f, y("heading-C"), 2f)
        assertEquals(1, metadataCount())
    }

    @Test fun ordinaryMetadataRemainsStationaryWithXlElsewhereInTheList() {
        content()
        val top = y("ordinary-metadata-A")
        press(KeyEvent.KEYCODE_DPAD_UP)
        advance(96)
        assertNull(scene.transition)
        assertEquals(top, y("ordinary-metadata-Earlier"), 0.1f)
        settle()
    }

    @Test fun reversingBeforeCompactMetadataAppearsCannotFlashItIntoView() {
        content(start = 2, summary = false)
        frameReady.value = false
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        advance(80)
        press(KeyEvent.KEYCODE_DPAD_UP)
        advance(32)
        assertNull(scene.transition?.outgoingMetadata)
        assertEquals(0, metadataCount())
        frameReady.value = true
        settle()
        assertEquals("XL", scene.selection?.rowKey)
        assertEquals(0, metadataCount())
    }

    @Test fun restoredOrdinaryFocusBelowCompactXlCanReturnUpAsOneScene() {
        content(start = 3, summary = false)
        assertEquals(true, scene.expandedCompactRows["XL"])
        assertEquals(260f, y("heading-C"), 2f)
        press(KeyEvent.KEYCODE_DPAD_UP)
        advance(32)
        val meta = y("ordinary-metadata-C")
        val poster = y("heading-XL")
        advance(80)
        assertEquals(y("heading-XL") - poster, y("ordinary-metadata-C") - meta, 2f)
        settle()
        assertEquals("XL", scene.selection?.rowKey)
        assertEquals(0, metadataCount())
    }

    @Test fun enteringXlDoesNotUncoverTheHiddenRowBehindTheOutgoingHero() {
        content()
        compose.onNodeWithTag("poster-Earlier").assertExists()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        advance(32)
        val mask = checkNotNull(xlSceneMetadataMask(scene, list.layoutInfo, true, 260f, 1f))
        val sampleY = ((mask.first.coerceAtLeast(0f) + mask.second.coerceAtMost(540f)) / 2).toInt()
        assertEquals("Old posters must stay hidden behind the travelling hero space",
            android.graphics.Color.BLACK, pixel(700, sampleY))
        settle()
        val previous = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "A" }
        assertTrue(previous == null || previous.offset + list.layoutInfo.beforeContentPadding + previous.size <= 0)
    }

    @Test fun compactExitPosterCanPassThroughTheFormerLogoAreaWithoutClipping() {
        content(start = 2, summary = false)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        advance(64)
        assertNull(xlSceneMetadataMask(scene, list.layoutInfo, true, 260f, 1f))
        val posterTop = y("poster-XL")
        val top = posterTop.coerceAtLeast(0f)
        val bottom = (posterTop + 244f).coerceAtMost(250f)
        assertTrue("The outgoing XL image crosses the ordinary hero region", bottom > top)
        assertEquals(android.graphics.Color.MAGENTA, pixel(700, ((top + bottom) / 2).toInt()))
        settle()
    }

    @Test fun skippingAcrossXlCannotReplaceTheStillVisibleOutgoingBlock() {
        content(summary = false)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        advance(32)
        val oldTop = y("ordinary-metadata-A")
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Synopsis A", substring = true).assertExists()
        assertEquals("A", scene.transition?.outgoingMetadata?.rowKey)
        assertEquals(oldTop, y("ordinary-metadata-A"), 2f)
        settle()
        assertEquals("C", scene.selection?.rowKey)
        assertEquals(1, metadataCount())
        compose.onNodeWithText("Synopsis A", substring = true).assertDoesNotExist()
    }

    companion object {
        private fun row(name: String, index: Int, xl: Boolean = false, summary: Boolean = true): HeroCarouselRow {
            val preview = HeroPreview(name, null, "Synopsis $name", "Movie", yearText = "2026", genres = emptyList(),
                poster = null, backdrop = "Backdrop $name", imageUrl = null)
            return HeroCarouselRow(name, name, index, listOf(ModernCarouselItem(name, name, null, null, preview,
                ModernPayload.Catalog(name, name, "movie", "", name, null, "movie"))), isXl = xl, showXlSummary = summary)
        }
    }
}
