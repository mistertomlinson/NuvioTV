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
import androidx.compose.ui.layout.onSizeChanged
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
    private val carouselFocused = mutableStateOf(false)
    private val startsWithXl = mutableStateOf(false)
    private val resolved = mutableStateOf<HeroPreview?>(null)
    private val requesters = List(5) { FocusRequester() }
    private lateinit var rows: List<HeroCarouselRow>

    @After fun cleanup() {
        if (::activity.isInitialized) compose.runOnUiThread { activity.pause().stop().destroy() }
    }

    private fun content(start: Int = 1, summary: Boolean = true, header: Int = 40, fullWidth: Boolean = false, freezeHero: Boolean = false, metadataTop: Int = 24, shortMetadata: Boolean = false) {
        rows = listOf(row("Earlier", 0), row("A", 1, description = if (shortMetadata) "" else "Synopsis A"), row("XL", 2, true, summary), row("C", 3), row("D", 4))
        scene = XlSceneState(rows[start].sceneSelection(0, start), rows).apply {
            fullWidthIcons = fullWidth
            metadataRestingTop = metadataTop.dp
        }
        list = LazyListState(start)
        resolved.value = scene.selection?.preview
        lateinit var seek: () -> Unit
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity.get().setContent {
                MaterialTheme {
                    backdrop = rememberXlBackdropPresentation(scene, true, resolved.value, carouselFocused.value, startsWithXl.value)
                    val visibleBackdrop = if (backdrop.owned) backdrop.preview else scene.transition?.outgoingBackdrop ?: resolved.value
                    val token = backdrop.token
                    SideEffect {
                        scene.recordBackdrop(visibleBackdrop)
                        backdrop.expectFrame(token, visibleBackdrop?.backdrop)
                        if (frameReady.value) backdrop.frameReady(token, visibleBackdrop?.backdrop)
                    }
                    LaunchedEffect(token, backdrop.directExit, frameReady.value) {
                        if (backdrop.directExit && frameReady.value) {
                            kotlinx.coroutines.delay(XL_BACKDROP_REVEAL_MS.toLong())
                            backdrop.transitionFinished(token, visibleBackdrop?.backdrop, false)
                        }
                    }
                    ObserveXlSceneMotion(scene, list)
                    val scope = rememberCoroutineScope()
                    seek = { scope.launch { list.scrollToItem(start, if (rows[start].isXl) 206 else 0) }; Unit }
                    val default = LocalBringIntoViewSpec.current
                    val spec = rememberHomeVerticalScrollSpec(scene, true, 260.dp,
                        androidx.compose.ui.platform.LocalDensity.current, default) { false }
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
                                        XlSceneRowHeadroom(scene, row.key, rows.getOrNull(index - 1), row.isXl)
                                        Column(Modifier.onSizeChanged {
                                            if (row.isXl) {
                                                scene.rowHeights[row.key] = it.height.dp
                                                // The compact mode uses the same visual anchor as summary mode.
                                                scene.summaryRowHeights[row.key] = it.height.dp + if (summary) 0.dp else 80.dp
                                            }
                                        }) {
                                        Text(row.title, Modifier.onSizeChanged { scene.headerHeights[row.key] = it.height.toFloat() }.height(header.dp).padding(start = 52.dp).testTag("heading-${row.key}"))
                                        Box(Modifier.padding(start = 52.dp).width(700.dp)
                                            .height(if (row.isXl) 244.dp else 200.dp)
                                            .homePosterFade(if (row.isXl) null else HomePosterFadeViewport(scene, list, true, 260.dp), row.key)
                                            .background(if (row.isXl) Color.Magenta else Color.Blue)
                                            .focusRequester(requesters[index])
                                            .onFocusChanged {
                                                if (it.isFocused) {
                                                    val selected = checkNotNull(row.sceneSelection(0, index))
                                                    scene.focus(selected, list.layoutInfo, rows = rows)
                                                    if (!freezeHero) resolved.value = selected.preview
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
                        }
                        ModernXlAwareHeroTitleBlock(
                            scene, backdrop, true, resolved.value, list, 260f, 1f,
                            true, "home", 0, false, fullWidth, !fullWidth,
                            Modifier.padding(start = 52.dp, top = metadataTop.dp).width(430.dp),
                            adjacentMetadata = if (scene.selection?.isXl == true) listOf(rows[1], rows[3]).map {
                                XlMetadataSnapshot(it.key, it.items.first().heroPreview)
                            } else emptyList()
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
        repeat(240) {
            advance(16)
            if (scene.transition?.rowsSettled == true || scene.transition == null && !list.isScrollInProgress &&
                scene.headroomMoving.values.none { it }) return
        }
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

    private fun measureVerticalMotion(start: Int, key: Int, name: String, summary: Boolean = true) {
        content(start = start, summary = summary)
        press(key)
        val target = checkNotNull(scene.selection).rowKey
        val samples = mutableListOf<String>()
        var firstRest = -1
        var lastY = Float.NaN
        var reversed = 0
        var direction = 0
        repeat(90) { frame ->
            val position = y("poster-$target")
            if (!lastY.isNaN() && abs(position - lastY) > 0.5f) {
                val nextDirection = if (position > lastY) 1 else -1
                if (direction != 0 && direction != nextDirection) reversed++
                direction = nextDirection
            }
            lastY = position
            val moving = list.isScrollInProgress || scene.headroomMoving.values.any { it }
            if (!moving && firstRest < 0) firstRest = frame * 16
            samples += "${frame * 16}:${position.toInt()}:${scene.headroomValues[target]?.value?.toInt() ?: 0}:$moving"
            advance(16)
        }
        println("VERTICAL_MOTION name=$name firstRestMs=$firstRest reversals=$reversed samples=${samples.joinToString(",")}")
        assertTrue("Motion must settle", firstRest in 0..336)
        assertEquals("The incoming row must never reverse direction", 0, reversed)
    }

    @Test fun measureOrdinaryDownMotion() = measureVerticalMotion(0, KeyEvent.KEYCODE_DPAD_DOWN, "ordinaryDown")
    @Test fun measureOrdinaryUpMotion() = measureVerticalMotion(1, KeyEvent.KEYCODE_DPAD_UP, "ordinaryUp")
    @Test fun measureXlDownEntryMotion() = measureVerticalMotion(1, KeyEvent.KEYCODE_DPAD_DOWN, "xlDownEntry")
    @Test fun measureXlUpEntryMotion() = measureVerticalMotion(3, KeyEvent.KEYCODE_DPAD_UP, "xlUpEntry")
    @Test fun measureXlUpExitMotion() = measureVerticalMotion(2, KeyEvent.KEYCODE_DPAD_UP, "xlUpExit")
    @Test fun measureXlDownExitMotion() = measureVerticalMotion(2, KeyEvent.KEYCODE_DPAD_DOWN, "xlDownExit")
    @Test fun measureCompactXlDownExitMotion() = measureVerticalMotion(2, KeyEvent.KEYCODE_DPAD_DOWN, "compactXlDownExit", false)

    @Test fun compactExitTravelsTogetherThenParksTheIncomingRowBeforeRevealingMetadata() {
        content(start = 2, summary = false)
        val startNext = y("heading-C")
        val startXl = y("heading-XL")
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        var sharedFrames = 0
        var parkedWhileXlMoves = false
        var previousEdge = Float.POSITIVE_INFINITY
        repeat(24) {
            advance(16)
            val next = y("heading-C")
            if (!scene.compactRowRested) {
                assertEquals("Both rows must travel at the same speed", next - startNext, y("heading-XL") - startXl, 1.1f)
                assertEquals("The prepared hero remains invisible before landing", 0f, scene.compactMetadataAlpha, 0f)
                sharedFrames++
            } else {
                assertEquals("The incoming row remains at rest", 260f, next, 1.1f)
                val edge = scene.outgoingXlBottom(list.layoutInfo) ?: 0f
                if (scene.verticalMotion != null && edge < previousEdge && edge > 0f) parkedWhileXlMoves = true
                previousEdge = edge
            }
        }
        assertTrue("Observe the initial shared travel", sharedFrames > 0)
        assertTrue("XL must continue after the incoming row lands", parkedWhileXlMoves)
        assertTrue("Metadata fades in after landing", scene.compactMetadataAlpha > 0.99f)
        settle()
    }

    @Test fun theNextRowPreviewStartsReturningAsSoonAsTheOutgoingMetadataClears() {
        content(start = 3, summary = false)
        frameReady.value = false
        press(KeyEvent.KEYCODE_DPAD_UP)
        var releasedBeforeLanding = false
        var released = false
        repeat(24) {
            advance(16)
            val move = scene.transition
            if (move != null && scene.outgoingMetadataOffscreen(list.layoutInfo)) {
                assertFalse("Preview must not wait for the backdrop", scene.expandedXlRows["XL"] == true)
                if (!released) {
                    val owner = checkNotNull(list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "C" })
                    val heroTop = owner.offset + list.layoutInfo.beforeContentPadding +
                        checkNotNull(scene.headroomValues["C"]).value - 260f + 24f
                    assertTrue("The outgoing content must already be below the screen: $heroTop", heroTop >= 540f)
                    released = true
                }
                if (!move.rowsSettled) releasedBeforeLanding = true
            }
        }
        assertTrue(releasedBeforeLanding)
        frameReady.value = true
        settle()
        assertEquals(0.dp, scene.headroomValues["C"])
    }

    @Test fun measuredHeadingHasNoCorrectionJumpAtTheEndOfAnXlExit() {
        content(start = 2, header = 46)
        press(KeyEvent.KEYCODE_DPAD_UP)
        awaitRows()
        val landing = y("ordinary-metadata-A")
        settle()
        assertEquals("The travelling block must land at its exact resting position", landing, y("ordinary-metadata-A"), 0.6f)
        assertEquals(24f, landing, 0.6f)
    }

    @Test fun xlExitRevealsTheReadyDestinationBeforeTheRowsSettle() {
        content(start = 2)
        press(KeyEvent.KEYCODE_DPAD_UP)
        advance(112)
        assertEquals("Backdrop A", backdrop.preview?.backdrop)
        assertTrue("The image must reveal during the scroll", backdrop.revealing)
        assertTrue(backdrop.dim.value < 0.95f)
        assertFalse(checkNotNull(scene.transition).rowsSettled)
        settle()
    }

    @Test fun theXlExitBackdropHasAVisibleFadeAndFinishesBeforeTheOldFourHundredMillisecondReveal() {
        content(start = 2)
        press(KeyEvent.KEYCODE_DPAD_UP)
        advance(112)
        assertTrue("Exit should blend out the light XL tint without inserting black", backdrop.dim.value in 0f..0.11f)
        advance(80)
        assertTrue(backdrop.dim.value in 0f..0.06f)
        advance(96)
        assertEquals("A ready backdrop must not retain v7's 400ms reveal", 0f, backdrop.dim.value, 0.001f)
        settle()
        assertEquals(0f, backdrop.dim.value, 0.001f)
    }

    @Test fun xlUsesTheBlurVariantAndExitingRestoresTheSharpVariantBeforeReveal() {
        content(start = 2)
        assertTrue(backdrop.blurred)
        press(KeyEvent.KEYCODE_DPAD_UP)
        advance(96)
        assertEquals("Backdrop A", backdrop.preview?.backdrop)
        assertFalse("Ordinary backdrop must be sharp while it is fading in", backdrop.blurred)
        settle()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        settle()
        assertTrue(backdrop.blurred)
        assertEquals(XL_BACKDROP_DIM_ALPHA, backdrop.dim.value, 0.001f)
    }

    @Test fun horizontalXlBackgroundChangesOnTheLandscapeCommitWithoutAnIdleDebounce() {
        content(start = 2)
        val initial = checkNotNull(scene.selection)
        val next = initial.copy(itemKey = "next", preview = initial.preview.copy(backdrop = "Next background"))
        compose.runOnIdle {
            scene.artwork.renderers["XL"] = true
            scene.artwork.frame = XlArtworkFrame("XL", initial.itemKey, initial.preview.backdrop)
            scene.focus(next, list.layoutInfo)
        }
        advance(112)
        assertEquals("Keep the old blur until its landscape partner is prepared", initial.preview, backdrop.preview)
        assertEquals(XL_BACKDROP_DIM_ALPHA, backdrop.dim.value, 0.001f)
        compose.runOnIdle { scene.artwork.frame = XlArtworkFrame("XL", next.itemKey, next.preview.backdrop) }
        advance(32)
        assertEquals("Commit on the next frame, without the ordinary-row debounce", next.preview, backdrop.preview)
        assertEquals("A horizontal handoff must not insert a black beat", XL_BACKDROP_DIM_ALPHA, backdrop.dim.value, 0.001f)
        assertTrue(backdrop.blurred)
    }

    @Test fun incomingMetadataIsOpaqueWhileTheOutgoingBlurIsHeldForItsUnreadyBackdrop() {
        content(start = 2)
        frameReady.value = false
        press(KeyEvent.KEYCODE_DPAD_UP)
        advance(144)
        assertEquals(XL_BACKDROP_DIM_ALPHA, backdrop.dim.value, 0.001f)
        val bounds = compose.onNodeWithText("Synopsis A", substring = true).fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.bottom > 0f && bounds.top < 540f)
        var brightest = 0
        compose.runOnUiThread {
            val view = activity.get().findViewById<android.view.View>(android.R.id.content)
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            for (py in bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(bitmap.height))
                for (px in bounds.left.toInt().coerceAtLeast(0) until bounds.right.toInt().coerceAtMost(bitmap.width))
                    brightest = maxOf(brightest, android.graphics.Color.red(bitmap.getPixel(px, py)))
            bitmap.recycle()
        }
        assertEquals("Metadata must arrive at its normal text brightness", 255.0, brightest.toDouble(), 3.0)
        frameReady.value = true
        settle()
    }

    @Test fun aSecondUpResumesAStationaryOrdinaryHero() {
        content(start = 2, freezeHero = true)
        press(KeyEvent.KEYCODE_DPAD_UP)
        advance(80)
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("Earlier", scene.selection?.rowKey)
        assertNull(scene.transition)
        advance(32)
        val top = y("ordinary-metadata-A")
        compose.onNodeWithTag("ordinary-metadata-Earlier", useUnmergedTree = true).assertDoesNotExist()
        advance(112)
        assertEquals(top, y("ordinary-metadata-A"), 0.1f)
        compose.runOnIdle { resolved.value = scene.selection?.preview }
        advance(32)
        assertEquals(top, y("ordinary-metadata-Earlier"), 0.1f)
        advance(700)
        assertFalse(backdrop.owned)
        assertNull(scene.stationaryMetadata)
        settle()
    }

    @Test fun ordinaryNavigationAfterACompletedXlExitStillWaitsForItsResolvedHero() {
        content(start = 2, freezeHero = true)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        settle()
        val top = y("ordinary-metadata-C")
        assertEquals("C", scene.stationaryMetadata?.rowKey)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        advance(96)
        assertEquals("D", scene.selection?.rowKey)
        assertNull(scene.transition)
        assertEquals(top, y("ordinary-metadata-C"), 0.1f)
        compose.onNodeWithTag("ordinary-metadata-D", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { resolved.value = rows[3].items.first().heroPreview }
        advance(32)
        assertEquals("An older resolved result must not release a newer pending row", "C", scene.stationaryMetadata?.rowKey)
        compose.runOnIdle { resolved.value = scene.selection?.preview }
        advance(32)
        assertEquals(top, y("ordinary-metadata-D"), 0.1f)
        assertNull(scene.stationaryMetadata)
        settle()
    }

    @Test fun upwardXlEntryCrossfadesImmediatelyWithoutBlackOrWaitingForRows() {
        content(start = 3)
        press(KeyEvent.KEYCODE_DPAD_UP)
        advance(32)
        assertEquals("XL", backdrop.preview?.title)
        assertTrue(backdrop.blurred)
        assertTrue(backdrop.directEntry)
        assertTrue(backdrop.revealing)
        assertFalse(checkNotNull(scene.transition).rowsSettled)
        var sawGradientFade = false
        repeat(16) {
            assertTrue("Upward entry must never dim through black", backdrop.dim.value in 0f..XL_BACKDROP_DIM_ALPHA + 0.001f)
            if (backdrop.gradientAlpha.value > 0f && backdrop.gradientAlpha.value < 1f) sawGradientFade = true
            advance(16)
        }
        assertEquals("Ordinary shading is now included inside the dissolving image", 0f, backdrop.gradientAlpha.value, 0.001f)
        assertEquals(0f, backdrop.gradientAlpha.value, 0.001f)
        settle()
    }

    @Test fun upwardXlEntryKeepsTheOldFrameUntilTheLandscapeAndBlurAreReady() {
        content(start = 3)
        compose.runOnIdle { scene.artwork.renderers["XL"] = true }
        press(KeyEvent.KEYCODE_DPAD_UP)
        advance(80)
        assertEquals("C", backdrop.preview?.title)
        assertFalse(backdrop.blurred)
        assertEquals(0f, backdrop.dim.value, 0.001f)
        assertEquals(1f, backdrop.gradientAlpha.value, 0.001f)
        compose.runOnIdle {
            val target = checkNotNull(scene.selection)
            scene.artwork.frame = XlArtworkFrame(target.rowKey, target.itemKey, target.preview.backdrop)
        }
        advance(32)
        assertEquals("XL", backdrop.preview?.title)
        assertTrue(backdrop.directEntry)
        assertFalse(checkNotNull(scene.transition).rowsSettled)
        assertTrue(backdrop.dim.value <= XL_BACKDROP_DIM_ALPHA)
        settle()
    }

    @Test fun fullWidthXlBalancesItsSummaryAndClearsThePreviousRow() {
        content(start = 2, fullWidth = true, metadataTop = 96)
        settle()
        val xlTop = y("heading-XL")
        val xlBodyHeight = checkNotNull(scene.rowHeights["XL"]).value
        assertEquals(xlTop - HOME_PLATFORM_SELECTOR_BOTTOM_DP, 540f - xlTop - xlBodyHeight, 2f)
        val previous = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "A" }
        assertTrue(previous == null || previous.offset + list.layoutInfo.beforeContentPadding + previous.size <= 0)
        // The next ordinary hero begins only a few pixels below the viewport.
        val next = checkNotNull(list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "C" })
        val nextMeta = next.offset + list.layoutInfo.beforeContentPadding + checkNotNull(scene.headroomValues["C"]).value - 260f + 96f
        assertEquals("Incoming hero should start just off screen", 548f, nextMeta, 1f)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        advance(112)
        val edge = checkNotNull(scene.outgoingXlBottom(list.layoutInfo))
        val mask = xlSceneMetadataMask(scene, list.layoutInfo, true, 260f, 1f)
        assertTrue("The mask must preserve the outgoing summary", mask == null || mask.first >= edge)
        settle()
        val outgoing = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "XL" }
        assertTrue(outgoing == null || outgoing.offset + list.layoutInfo.beforeContentPadding + outgoing.size <= 0)
    }

    @Test fun anXlFirstPlatformPageStaysDarkOnThePlatformIcons() {
        startsWithXl.value = true
        carouselFocused.value = true
        content(start = 2)
        settle()
        assertEquals(XL_BACKDROP_DIM_ALPHA, backdrop.dim.value, 0.001f)
        carouselFocused.value = false
        advance(64)
        assertEquals("Down must not cause a second darkening", XL_BACKDROP_DIM_ALPHA, backdrop.dim.value, 0.001f)
        settle()
        carouselFocused.value = true
        startsWithXl.value = false
        advance(160)
        assertTrue("Ordinary-first pages return to the visible backdrop", backdrop.dim.value < 0.9f)
        settle()
    }

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

    @Test fun compactExitRetainsTheColorFieldWhileTheRegularBackdropIsCold() {
        content(start = 2, summary = false)
        frameReady.value = false
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitRows()
        assertEquals("C", scene.selection?.rowKey)
        assertEquals(260f, y("heading-C"), 2f)
        assertEquals(XL_BACKDROP_DIM_ALPHA, backdrop.dim.value, 0.001f)
        assertTrue(backdrop.revealing)
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

    @Test fun downwardXlEntryStartsDuringMotionWithoutADarkStage() {
        content()
        frameReady.value = false
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        advance(32)
        assertEquals("Backdrop XL", backdrop.preview?.backdrop)
        assertTrue(backdrop.revealing)
        assertTrue(backdrop.directEntry)
        assertEquals(220, backdrop.imageFadeMillis)
        assertFalse(checkNotNull(scene.transition).rowsSettled)
        repeat(16) {
            assertTrue("Downward entry must never fade through black", backdrop.dim.value <= XL_BACKDROP_DIM_ALPHA + 0.001f)
            advance(16)
        }
        frameReady.value = true
        settle()
        assertEquals(XL_BACKDROP_DIM_ALPHA, backdrop.dim.value, 0.001f)
    }

    @Test fun downwardXlEntryKeepsTheOldBackdropWhileItsArtworkPairIsCold() {
        content()
        compose.runOnIdle { scene.artwork.renderers["XL"] = true }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        advance(80)
        assertEquals("A", backdrop.preview?.title)
        assertEquals(0f, backdrop.dim.value, 0.001f)
        compose.runOnIdle {
            val target = checkNotNull(scene.selection)
            scene.artwork.frame = XlArtworkFrame(target.rowKey, target.itemKey, target.preview.backdrop)
        }
        advance(32)
        assertEquals("XL", backdrop.preview?.title)
        assertTrue(backdrop.revealing)
        assertTrue(backdrop.dim.value <= XL_BACKDROP_DIM_ALPHA)
        settle()
    }

    @Test fun restoredXlFocusAlreadyHasItsBackdropWithoutReplayingAnEntryFade() {
        content(start = 2, freezeHero = true)
        assertEquals("XL", backdrop.preview?.title)
        assertTrue(backdrop.blurred)
        assertEquals(0, backdrop.imageFadeMillis)
        repeat(16) {
            assertEquals(XL_BACKDROP_DIM_ALPHA, backdrop.dim.value, 0.001f)
            assertEquals(0f, backdrop.gradientAlpha.value, 0.001f)
            advance(16)
        }
        assertNull(scene.transition)
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

    private fun verifyCompactReversal(exitMillis: Int) {
        content(start = 2, summary = false)
        frameReady.value = false
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        advance(exitMillis)
        press(KeyEvent.KEYCODE_DPAD_UP)
        advance(80)
        val move = checkNotNull(scene.transition)
        assertEquals("The reverse press must return to XL", "XL", scene.selection?.rowKey)
        if (move.outgoingMetadata != null) assertTrue(move.initialMetadataAlpha > 0f)
        val posterTop = y("poster-XL")
        val top = (posterTop + 3f).coerceAtLeast(0f).toInt()
        val bottom = (posterTop + 241f).coerceAtMost(540f).toInt()
        assertTrue("The reverse scroll must start promptly: top=$posterTop, motion=${scene.verticalMotion}, scrolling=${list.isScrollInProgress}, gap=${scene.headroomValues}", bottom > top)
        compose.runOnUiThread {
            val view = activity.get().findViewById<android.view.View>(android.R.id.content)
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            var altered = 0
            for (py in top until bottom) for (px in 60 until 440)
                if (bitmap.getPixel(px, py) != android.graphics.Color.MAGENTA) altered++
            bitmap.recycle()
            assertEquals("A reversed hero must not flash its clipped logo over XL", 0, altered)
        }
        frameReady.value = true
        settle()
        assertEquals("XL", scene.selection?.rowKey)
        assertEquals(0, metadataCount())
    }

    @Test fun reversingACompactExitCannotRevealTheHiddenPartOfItsPreparedHero() = verifyCompactReversal(80)

    @Test fun reversingACompactExitCanRecoverAnXlPosterThatAlreadyLeftTheViewport() = verifyCompactReversal(160)

    @Test fun clearingShortMetadataEarlyCannotUncoverAnOlderPosterBehindItsReservedSpace() {
        content(shortMetadata = true, fullWidth = true)
        assertTrue("Fixture needs a short hero", scene.metadataHeightPx < 140f)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        var checked = false
        repeat(12) {
            advance(16)
            if (!checked && scene.transition?.outgoingMetadataCleared == true) {
                val bottom = y("poster-Earlier") + 200f
                assertTrue("Fixture must still have a hidden older poster on screen", bottom > 4f)
                val sampleY = (bottom / 2).toInt()
                assertEquals("Clearing metadata must preserve the remaining poster exclusion", android.graphics.Color.BLACK, pixel(700, sampleY))
                checked = true
            }
        }
        assertTrue(checked)
        settle()
    }

    @Test fun restoredOrdinaryFocusBelowCompactXlCanReturnUpAsOneScene() {
        content(start = 3, summary = false)
        assertEquals(true, scene.expandedXlRows["XL"])
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
        val mask = xlSceneMetadataMask(scene, list.layoutInfo, true, 260f, 1f)
        val outgoingBottom = checkNotNull(scene.outgoingXlBottom(list.layoutInfo))
        assertTrue(mask == null || mask.first >= outgoingBottom)
        val posterTop = y("poster-XL")
        val top = posterTop.coerceAtLeast(0f)
        val bottom = (posterTop + 244f).coerceAtMost(250f)
        assertTrue("The outgoing XL image crosses the ordinary hero region", bottom > top)
        assertEquals(android.graphics.Color.MAGENTA, pixel(700, ((top + bottom) / 2).toInt()))
        settle()
    }

    @Test fun compactExitRevealsEarlyWithoutPaintingMetadataOverTheDepartingPoster() {
        content(start = 2, summary = false)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        advance(64)
        assertTrue(backdrop.revealing)
        assertFalse(checkNotNull(scene.transition).rowsSettled)
        val posterTop = y("poster-XL")
        val top = (posterTop + 3f).coerceAtLeast(0f).toInt()
        val bottom = (posterTop + 241f).coerceAtMost(250f).toInt()
        assertTrue(bottom > top)
        compose.runOnUiThread {
            val view = activity.get().findViewById<android.view.View>(android.R.id.content)
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            var altered = 0
            for (py in top until bottom) for (px in 60 until 440)
                if (bitmap.getPixel(px, py) != android.graphics.Color.MAGENTA) altered++
            bitmap.recycle()
            assertEquals("The whole departing poster must stay intact during early reveal", 0, altered)
        }
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
        private fun row(name: String, index: Int, xl: Boolean = false, summary: Boolean = true, description: String = "Synopsis $name"): HeroCarouselRow {
            val preview = HeroPreview(name, null, description, "Movie", yearText = "2026", genres = emptyList(),
                poster = null, backdrop = "Backdrop $name", imageUrl = null)
            return HeroCarouselRow(name, name, index, listOf(ModernCarouselItem(name, name, null, null, preview,
                ModernPayload.Catalog(name, name, "movie", "", name, null, "movie"))), isXl = xl, showXlSummary = summary)
        }
    }
}
