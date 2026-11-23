package com.nuvio.tv.ui.screens.home

import androidx.compose.animation.core.VectorConverter
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
import androidx.compose.foundation.lazy.LazyLayoutScrollScope as lazyListScrollScope
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
    private lateinit var ordinaryAnimation: androidx.compose.animation.core.AnimationSpec<Float>
    private val frameReady = mutableStateOf(true)
    private val carouselFocused = mutableStateOf(false)
    private val startsWithXl = mutableStateOf(false)
    private val resolved = mutableStateOf<HeroPreview?>(null)
    private val requesters = List(5) { FocusRequester() }
    private lateinit var rows: List<HeroCarouselRow>

    @After fun cleanup() {
        if (::activity.isInitialized) compose.runOnUiThread { activity.pause().stop().destroy() }
    }

    private fun content(start: Int = 1, summary: Boolean = true, header: Int = 40, fullWidth: Boolean = false, freezeHero: Boolean = false, metadataTop: Int = 24, shortMetadata: Boolean = false, paintFocusedState: Boolean = false) {
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
                    ordinaryAnimation = default.scrollAnimationSpec
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
                                            scene.rowHeights[row.key] = it.height.dp
                                            if (row.isXl) {
                                                // The compact mode uses the same visual anchor as summary mode.
                                                scene.summaryRowHeights[row.key] = it.height.dp + if (summary) 0.dp else 80.dp
                                            }
                                        }) {
                                        Text(row.title, Modifier.onSizeChanged { scene.headerHeights[row.key] = it.height.toFloat() }.height(header.dp).padding(start = 52.dp).testTag("heading-${row.key}"))
                                        val paintedFocus = if (paintFocusedState) remember { mutableStateOf(false) } else null
                                        Box(Modifier.padding(start = 52.dp).width(700.dp)
                                            .height(if (row.isXl) 244.dp else 200.dp)
                                            .background(if (paintedFocus?.value == true) Color.Cyan else if (row.isXl) Color.Magenta else Color.Blue)
                                            .focusRequester(requesters[index])
                                            .onFocusChanged {
                                                paintedFocus?.value = it.isFocused
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
        val motion = scene.verticalMotion
        val trackedKey = if (rows[start].isXl) rows[start].key else target
        fun trackedTop(): Float? = list.layoutInfo.let { info ->
            info.visibleItemsInfo.firstOrNull { it.key == trackedKey }?.let {
                it.offset + info.beforeContentPadding + (scene.headroomValues[trackedKey]?.value ?: 0f) +
                    (scene.headerHeights[trackedKey] ?: 40f)
            }
        }
        var tracked = trackedTop()
        var peakStep = 0f
        var peakAt = -1
        val samples = mutableListOf<String>()
        var firstRest = -1
        var lastY = Float.NaN
        var reversed = 0
        var direction = 0
        repeat(90) { frame ->
            val position = y("poster-$target")
            val current = trackedTop()
            if (current != null && tracked != null) {
                val step = abs(current - tracked!!)
                if (step > peakStep) { peakStep = step; peakAt = frame * 16 }
            }
            tracked = current
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
        if (motion == null) assertTrue("Ordinary timing stays unchanged", firstRest in 0..336)
        else {
            val reference = abs(motion.scrollPx)
            val template = androidx.compose.animation.core.TargetBasedAnimation(ordinaryAnimation,
                Float.VectorConverter, 0f, reference)
            val referenceSteps = (0..120).map { template.getValueFromNanos(it * 16_000_000L) }
            val peakReference = referenceSteps.zipWithNext().maxOf { (a, b) -> abs(b - a) }
            val referenceSettle = referenceSteps.indexOfFirst { abs(reference - it) < 0.5f } * 16
            val expectedSettle = referenceSettle * XL_SPRING_TIME_SCALE
            println("XL_SPRING name=$name distance=$reference scale=$XL_SPRING_TIME_SCALE peak=$peakStep referencePeak=$peakReference settled=$firstRest regularSpringStop=$referenceSettle peakAt=$peakAt")
            assertTrue("Only a modest stretch of the ordinary spring", firstRest in (expectedSettle - 16).toInt()..(expectedSettle + 64).toInt())
            assertTrue("XL must not gain a long poster-size timing multiplier", firstRest <= referenceSettle + 80)
            assertTrue("The same spring keeps its familiar early movement", peakStep <= peakReference * 1.1f + 2f)
            if (start == 2) assertTrue("Regular spring acceleration must peak early, then decelerate", peakAt in 1 until (firstRest * 0.3f).toInt())
        }
        assertEquals("The incoming row must never reverse direction", 0, reversed)
    }

    @Test fun measureOrdinaryDownMotion() = measureVerticalMotion(0, KeyEvent.KEYCODE_DPAD_DOWN, "ordinaryDown")
    @Test fun measureOrdinaryUpMotion() = measureVerticalMotion(1, KeyEvent.KEYCODE_DPAD_UP, "ordinaryUp")
    @Test fun measureXlDownEntryMotion() = measureVerticalMotion(1, KeyEvent.KEYCODE_DPAD_DOWN, "xlDownEntry")
    @Test fun measureXlUpEntryMotion() = measureVerticalMotion(3, KeyEvent.KEYCODE_DPAD_UP, "xlUpEntry")
    @Test fun measureXlUpExitMotion() = measureVerticalMotion(2, KeyEvent.KEYCODE_DPAD_UP, "xlUpExit")
    @Test fun measureXlDownExitMotion() = measureVerticalMotion(2, KeyEvent.KEYCODE_DPAD_DOWN, "xlDownExit")
    @Test fun measureCompactXlDownExitMotion() = measureVerticalMotion(2, KeyEvent.KEYCODE_DPAD_DOWN, "compactXlDownExit", false)

    @Test fun compactIncomingRowDeceleratesOnTheSameSpringWithoutAHardStop() {
        content(start = 2, summary = false)
        val startNext = y("heading-C")
        val startOwner = list.layoutInfo.let { info -> info.visibleItemsInfo.first { it.key == "C" }.offset + info.beforeContentPadding }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        val motion = checkNotNull(scene.verticalMotion)
        val steps = mutableListOf<Float>()
        var previous = startNext
        var springFrames = 0
        repeat(30) {
            advance(16)
            val next = y("heading-C")
            val info = list.layoutInfo
            val owner = info.visibleItemsInfo.first { it.key == "C" }.offset + info.beforeContentPadding
            val fraction = ((startOwner - owner) / motion.scrollPx).coerceIn(0f, 1f)
            val expected = startNext + (260f - startNext) * fraction
            assertEquals("The short incoming leg shares the XL spring progression", expected, next, 1.1f)
            assertTrue("The incoming row never overshoots its anchor", next >= 260f)
            if (fraction in 0.05f..0.99f) springFrames++
            if (!scene.compactRowRested) assertEquals("Prepared metadata waits for the spring landing", 0f, scene.exitMetadataAlpha, 0f)
            if (previous > next) steps += previous - next
            previous = next
        }
        assertTrue("Observe a gradual short-range spring rather than an early clamp", springFrames >= 8)
        assertTrue("The short row decelerates over multiple visible steps", steps.size >= 8 && steps.last() < steps.max() / 4f)
        assertEquals(260f, y("heading-C"), 0f)
        settle()
        assertEquals("No correction after the spring releases ownership", 260f, y("heading-C"), 0f)
        assertTrue("Metadata fades in after landing", scene.exitMetadataAlpha > 0.99f)
    }

    @Test fun compactFramePublishesItsNewSpacerMeasurementWithItsScrollPosition() {
        content(start = 2, summary = false)
        compose.runOnUiThread { scene.verticalLauncher = {} }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        advance(32)
        val motion = checkNotNull(scene.verticalMotion)
        val anchor = checkNotNull(motion.itemAnchor)
        val before = list.layoutInfo.visibleItemsInfo.first { it.key == "C" }
        val oldSize = before.size
        val oldOffset = before.offset
        val oldGap = scene.gapPx("C")
        val travel = 20
        val targetGap = (motion.toGap.value * scene.density).toInt()
        val gap = oldGap + ((targetGap - oldGap) * travel / motion.scrollPx).toInt()
        assertTrue("Exercise the first growing spacer frame", gap > oldGap)
        var measuredSize = 0
        var measuredOffset = 0
        val measureGetter = LazyListState::class.java.methods.first { it.name.startsWith("getNumMeasurePasses") }
        fun measureCount() = measureGetter.invoke(list) as Int
        val measuresBefore = measureCount()
        var measuresAfterFrame = 0
        compose.runOnUiThread {
            kotlinx.coroutines.runBlocking {
                list.scroll(androidx.compose.foundation.MutatePriority.PreventUserInput) {
                    lazyListScrollScope(list, this).publishCompactXlFrame(scene, motion, gap, travel, 0f)
                    val current = list.layoutInfo.visibleItemsInfo.first { it.key == "C" }
                    measuredSize = current.size
                    measuredOffset = current.offset
                    measuresAfterFrame = measureCount()
                    assertTrue("The original scroll owner remains held", list.isScrollInProgress)
                }
            }
        }
        assertEquals("The compact frame must not publish a new offset with the previous spacer measurement",
            oldSize + gap - oldGap, measuredSize)
        assertEquals("Both measurements describe the same spring frame", oldOffset - travel, measuredOffset)
        assertEquals(3, anchor.index)
        assertEquals("One measured pass publishes the compact frame", 1, measuresAfterFrame - measuresBefore)
        compose.waitForIdle()
        println("COMPACT_LAYOUT_FRAME gap=$gap size=$measuredSize expected=${oldSize + gap - oldGap} synchronousPasses=${measuresAfterFrame - measuresBefore} followupPasses=${measureCount() - measuresAfterFrame}")
        assertEquals("No corrective layout pass is left for the next frame", measuresAfterFrame, measureCount())
        compose.runOnUiThread { scene.cancelVerticalMotion() }
    }

    private fun verifyFocusPaintPrecedesMotion(start: Int, key: Int) {
        content(start = start, summary = false)
        val firstIndex = list.firstVisibleItemIndex
        val firstOffset = list.firstVisibleItemScrollOffset
        press(key)
        val motion = checkNotNull(scene.verticalMotion)
        assertEquals("Acquiring focus cannot move the rows before their focus frame", firstIndex, list.firstVisibleItemIndex)
        assertEquals(firstOffset, list.firstVisibleItemScrollOffset)
        pixel(700, 400) // A cached viewport draw can precede focus composition.
        advance(16) // Commit the focused composition before accepting its draw.
        assertEquals("The rows remain stationary through focus composition", firstIndex, list.firstVisibleItemIndex)
        assertEquals(firstOffset, list.firstVisibleItemScrollOffset)
        pixel(700, 400)
        assertEquals("The rows acknowledge the committed focused frame", motion.id, scene.lastFocusPaintedId)
        var moved = false
        repeat(8) {
            advance(16)
            if (firstIndex != list.firstVisibleItemIndex || firstOffset != list.firstVisibleItemScrollOffset) moved = true
        }
        assertTrue("The focus draw releases the spring", moved)
        settle()
    }

    @Test fun compactCachedPreviewCannotReleaseMotionBeforeItsFocusCompositionCommits() {
        content(start = 2, summary = false, paintFocusedState = true)
        val sampleY = (y("poster-C") + 4f).toInt()
        assertTrue("The incoming ordinary preview is already on screen", sampleY in 0..539)
        var cachedPixel = 0
        lateinit var signal: kotlinx.coroutines.CompletableDeferred<Unit>
        val launcher = scene.verticalLauncher
        compose.runOnUiThread {
            // Hold the spring while reproducing a cached preview draw in the
            // same input event, before the recomposer commits focused styling.
            scene.verticalLauncher = { active -> signal = scene.requestFocusPaint(active.id) }
            requesters[3].requestFocus()
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            val view = activity.get().findViewById<android.view.View>(android.R.id.content)
            val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(image))
            cachedPixel = image.getPixel(700, sampleY)
            image.recycle()
        }
        assertEquals("This is still the cached unfocused preview", android.graphics.Color.BLUE, cachedPixel)
        assertFalse("A cached compact preview cannot acknowledge the new focused frame", signal.isCompleted)
        advance(16)
        assertEquals("The new focus style has now composed and drawn", android.graphics.Color.CYAN, pixel(700, sampleY))
        assertTrue("Only the committed focus draw releases the spring", signal.isCompleted)
        compose.runOnUiThread { scene.cancelVerticalMotion(); scene.verticalLauncher = launcher }
    }

    @Test fun xlExitDrawsTheNewFocusBeforeStartingItsSpring() = verifyFocusPaintPrecedesMotion(2, KeyEvent.KEYCODE_DPAD_DOWN)
    @Test fun xlEntryDrawsTheNewFocusBeforeStartingItsSpring() = verifyFocusPaintPrecedesMotion(3, KeyEvent.KEYCODE_DPAD_UP)

    @Test fun upwardCompactPreviewKeepsItsTravelUntilTheMetadataMaskClears() {
        content(start = 3, summary = false)
        frameReady.value = false
        val originalGap = checkNotNull(scene.headroomValues["C"])
        press(KeyEvent.KEYCODE_DPAD_UP)
        advance(64)
        assertEquals("Do not consume the preview return behind its outgoing metadata mask", originalGap, scene.headroomValues["C"])
        assertFalse(checkNotNull(scene.transition).outgoingMetadataCleared)
        var began = false
        repeat(60) {
            advance(16)
            if (!began && checkNotNull(scene.headroomValues["C"]) < originalGap) {
                began = true
                assertTrue(checkNotNull(scene.transition).outgoingMetadataCleared)
                assertTrue("The return starts before XL finishes its slide", list.isScrollInProgress)
            }
        }
        assertTrue("Exercise a visible return after the travelling mask clears", began)
        frameReady.value = true
        settle()
        assertEquals(0.dp, scene.headroomValues["C"])
        assertEquals(0, metadataCount())
    }

    private fun verifyDownwardMetadataWaitsForXlClearance(summary: Boolean) {
        content(start = 2, summary = summary)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        var sawXl = false
        var sawFade = false
        repeat(40) {
            advance(16)
            val bottom = scene.outgoingXlBottom(list.layoutInfo) ?: 0f
            if (bottom > 0f) {
                sawXl = true
                assertEquals("Metadata must stay invisible while any XL row remains: bottom=$bottom", 0f, scene.exitMetadataAlpha, 0f)
            } else if (scene.exitMetadataAlpha > 0f && scene.exitMetadataAlpha < 1f) sawFade = true
        }
        assertTrue(sawXl)
        assertTrue("Metadata fades after XL has cleared", sawFade)
        settle()
        assertEquals(1f, scene.exitMetadataAlpha, 0f)
    }

    @Test fun rapidDownAcrossCompactXlKeepsOutgoingMetadataButClipsAtPoster() {
        content(start = 1, summary = false)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        val entering = checkNotNull(scene.transition)
        assertEquals("A", entering.from.rowKey)
        assertEquals("XL", entering.to.rowKey)
        assertEquals("A", entering.outgoingMetadata?.rowKey)
        advance(32)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        val exiting = checkNotNull(scene.transition)
        assertEquals("XL", exiting.from.rowKey)
        assertEquals("C", exiting.to.rowKey)
        assertTrue(exiting.compactExit)
        assertEquals("A", exiting.outgoingMetadata?.rowKey)
        val xl = list.layoutInfo.visibleItemsInfo.first { it.key == "XL" }
        val posterTop = xl.offset + list.layoutInfo.beforeContentPadding +
            scene.gapPx("XL") + scene.headerPx("XL")
        val clipBottom = scene.carriedMetadataBottomBeforeXlPoster(list.layoutInfo, 0f)
        assertEquals("The inherited metadata is bounded by the XL poster, not discarded",
            posterTop.toFloat().coerceAtLeast(0f), checkNotNull(clipBottom), 0f)
        assertEquals(0f, scene.exitMetadataAlpha, 0f)
        settle()
    }

    @Test fun compactMetadataWaitsUntilXlIsCompletelyOffScreen() = verifyDownwardMetadataWaitsForXlClearance(false)
    @Test fun compactMetadataRetainsItsOriginal220msFadeAfterCompleteClearance() {
        content(start = 2, summary = false)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        var firstFade = -1
        var fullAlpha = -1
        var landed = -1
        var cleared = -1
        var previousAlpha = 0f
        var intermediate = 0
        repeat(90) { frame ->
            advance(16)
            val time = (frame + 1) * 16
            val alpha = scene.exitMetadataAlpha
            if (scene.compactRowRested && landed < 0) landed = time
            if ((scene.outgoingXlBottom(list.layoutInfo) ?: 0f) <= 0f && cleared < 0) cleared = time
            if (alpha > 0f && firstFade < 0) {
                firstFade = time
                assertTrue(scene.compactRowRested)
                assertTrue((scene.outgoingXlBottom(list.layoutInfo) ?: 0f) <= 0f)
            }
            if (alpha in 0.001f..0.999f) intermediate++
            if (alpha >= 1f && fullAlpha < 0) fullAlpha = time
            assertTrue("The prepared block must retain a gradual fade, not jump to full alpha", alpha - previousAlpha < 0.2f)
            previousAlpha = alpha
        }
        assertTrue("Keep metadata hidden until both landing and complete XL clearance", firstFade >= maxOf(landed, cleared))
        assertTrue("Do not add a new fade debounce after clearance", firstFade - maxOf(landed, cleared) in 0..48)
        println("COMPACT_FADE landed=$landed cleared=$cleared first=$firstFade opaque=$fullAlpha partialFrames=$intermediate")
        assertTrue("Retain the 220ms fade with normal frame quantization", fullAlpha - firstFade in 176..256)
        assertTrue("The native clock observes the fade over several frames", intermediate >= 10)
        settle()
    }

    @Test fun summaryMetadataIsPreparedOpaqueAndSlidesInWithItsRow() {
        content(start = 2, summary = true)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        var enteredBeforeLanding = false
        repeat(30) {
            advance(16)
            assertEquals(1f, scene.exitMetadataAlpha, 0f)
            val bounds = compose.onNodeWithTag("ordinary-metadata-C", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            if (bounds.bottom > 0 && bounds.top < 540 && list.isScrollInProgress) enteredBeforeLanding = true
        }
        assertTrue("Prepared summary-exit metadata enters during the row slide", enteredBeforeLanding)
        settle()
    }

    @Test fun aRapidSecondDownKeepsXlClearanceAndTheOrdinaryMetadataDebounce() {
        content(start = 2, summary = false, freezeHero = true)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        advance(32)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("D", scene.selection?.rowKey)
        assertEquals("C", scene.stationaryMetadata?.rowKey)
        repeat(24) {
            advance(16)
            if ((scene.outgoingXlBottom(list.layoutInfo) ?: 0f) > 0f) {
                assertEquals("A second key cannot reveal metadata over XL", 0f, scene.exitMetadataAlpha, 0f)
            }
            compose.onNodeWithTag("ordinary-metadata-D", useUnmergedTree = true).assertDoesNotExist()
        }
        settle()
        compose.onNodeWithTag("ordinary-metadata-C", useUnmergedTree = true).assertExists()
        compose.runOnIdle { resolved.value = rows[4].items.first().heroPreview }
        advance(64)
        compose.onNodeWithTag("ordinary-metadata-D", useUnmergedTree = true).assertExists()
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
        advance(272)
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
        val outgoingRowTop = y("heading-C")
        advance(80)
        assertEquals(y("heading-C") - outgoingRowTop, y("ordinary-metadata-C") - oldTop, 2f)
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
        var brightest = 0
        repeat(18) {
            advance(16)
            assertEquals("Prepared summary metadata has no alpha ramp", 1f, scene.exitMetadataAlpha, 0f)
            if (list.isScrollInProgress) {
                val bounds = compose.onNodeWithTag("ordinary-metadata-C", true).fetchSemanticsNode().boundsInRoot
                compose.runOnUiThread {
                    val view = activity.get().findViewById<android.view.View>(android.R.id.content)
                    val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                    view.draw(android.graphics.Canvas(image))
                    for (y in bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(image.height))
                        for (x in bounds.left.toInt().coerceAtLeast(0) until bounds.right.toInt().coerceAtMost(image.width))
                            brightest = maxOf(brightest, android.graphics.Color.red(image.getPixel(x, y)))
                    image.recycle()
                }
            }
        }
        assertTrue("Already prepared metadata enters fully bright during the longer row slide", brightest >= 245)
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
        val outgoingRowTop = y("heading-C")
        advance(80)
        assertEquals(y("heading-C") - outgoingRowTop, y("ordinary-metadata-C") - meta, 2f)
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

    private fun verifyNoExitStall(summary: Boolean) {
        content(start = 2, summary = summary)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        fun paintedBottom(): Int {
            var bottom = -1
            compose.runOnUiThread {
                val view = activity.get().findViewById<android.view.View>(android.R.id.content)
                val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(image))
                for (y in 0 until image.height) if (image.getPixel(700, y) == android.graphics.Color.MAGENTA) bottom = y
                image.recycle()
            }
            return bottom
        }
        var previous = checkNotNull(scene.outgoingXlBottom(list.layoutInfo))
        var painted = paintedBottom()
        var paintStarted = false
        var paintedFrames = 0
        var started = false
        var checked = 0
        repeat(30) {
            advance(16)
            val edge = scene.outgoingXlBottom(list.layoutInfo) ?: 0f
            val nextPainted = paintedBottom()
            if (nextPainted < painted) paintStarted = true
            if (paintStarted && nextPainted > 40) {
                assertTrue("The actual painted XL image cannot pause at focus handoff: edge=$nextPainted previous=$painted", nextPainted < painted)
                paintedFrames++
            }
            painted = nextPainted
            if (previous - edge > 0f) started = true
            if (started && edge > 40f) {
                assertTrue("After motion begins it cannot pause for focus: edge=$edge previous=$previous", edge < previous)
                checked++
            }
            previous = edge
        }
        assertTrue("Exercise multiple moving frames before XL clears", checked >= 3)
        assertTrue("Exercise the actual painted landscape edge", paintedFrames >= 3)
        settle()
    }
    @Test fun compactExitHasNoFocusHandoffStallAfterMovementBegins() = verifyNoExitStall(false)
    @Test fun summaryExitHasNoFocusHandoffStallAfterMovementBegins() = verifyNoExitStall(true)
}
