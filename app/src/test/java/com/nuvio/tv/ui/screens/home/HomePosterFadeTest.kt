package com.nuvio.tv.ui.screens.home

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
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

/** Short real headings can leave a bottom inside, or just below, the old fade band. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomePosterFadeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val drawing = mutableMapOf<String, Int>()
    private var lastFrameDraws = emptyMap<String, Int>()
    private val totalBodyRecordings = mutableMapOf<String, Int>()
    private lateinit var seek: (Int) -> Unit
    private val fadeBodies = mutableStateOf(false)

    @After fun cleanup() {
        if (::activity.isInitialized) compose.runOnUiThread { activity.pause().stop().destroy() }
    }

    private fun content(offset: Int, xlPage: Boolean = true) {
        val scene = XlSceneState()
        val list = LazyListState()
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity.get().setContent {
                val scope = rememberCoroutineScope()
                seek = { next -> scope.launch { list.scrollToItem(1, next) }; Unit }
                val viewport = remember { HomePosterFadeViewport(scene, list, xlPage, 260.dp) }
                Box(Modifier.width(960.dp).height(540.dp).drawWithContent {
                    drawing.clear()
                    drawContent()
                    lastFrameDraws = drawing.toMap()
                }.background(Color.Blue)) {
                    LazyColumn(state = list,
                        modifier = (if (xlPage) Modifier.fillMaxSize() else Modifier.padding(top = 260.dp).fillMaxSize())
                            .xlSceneRowsViewport(scene, list, xlPage, 260.dp).drawWithContent {
                                drawing["list"] = (drawing["list"] ?: 0) + 1
                                drawContent()
                            },
                        contentPadding = PaddingValues(top = if (xlPage) 260.dp else 0.dp, bottom = 540.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        items(4, key = { it.toString() }) { index ->
                            Column(Modifier.padding(start = 52.dp)) {
                                Box(Modifier.width(700.dp).height(28.dp).onSizeChanged { scene.headerHeights[index.toString()] = it.height.toFloat() }
                                    .background(Color.White).testTag("heading-$index"))
                                Box(Modifier.width(700.dp).height(200.dp)
                                    .homePosterFade(if (fadeBodies.value) viewport else null, index.toString())
                                    .drawWithContent {
                                        drawing[index.toString()] = (drawing[index.toString()] ?: 0) + 1
                                        totalBodyRecordings[index.toString()] = (totalBodyRecordings[index.toString()] ?: 0) + 1
                                        drawContent()
                                    }.background(Color.White).testTag("poster-$index"))
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { seek(offset) }
        compose.waitForIdle()
    }

    private fun pixels(): Bitmap {
        lateinit var result: Bitmap
        compose.runOnUiThread {
            val view = activity.get().findViewById<android.view.View>(android.R.id.content)
            result = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(result))
        }
        return result
    }

    private fun recordHardwareDisplayList() {
        compose.runOnUiThread {
            val view = activity.get().findViewById<android.view.View>(android.R.id.content)
            val node = android.graphics.RenderNode("poster-cache-test")
            val canvas = node.beginRecording(view.width, view.height)
            assertTrue("Exercise RenderNode reuse, not a software bitmap capture", canvas.isHardwareAccelerated)
            view.draw(canvas)
            node.endRecording()
            node.discardDisplayList()
        }
    }

    private fun verifySliverIsGone(offset: Int, expectedBottom: Float, xlPage: Boolean = true) {
        content(offset, xlPage)
        val prior = compose.onNodeWithTag("poster-0").fetchSemanticsNode().boundsInRoot
        assertEquals(expectedBottom, prior.bottom, 0.1f)
        val old = pixels()
        val y = expectedBottom.toInt() - 2
        if (expectedBottom > 260f) assertTrue("Fixture must reproduce the unmasked bottom sliver",
            android.graphics.Color.red(old.getPixel(80, y)) > 0)
        old.recycle()
        compose.runOnIdle { fadeBodies.value = true }
        compose.waitForIdle()
        val fixed = pixels()
        for (py in 240..271) assertEquals("Poster pixels must be completely gone at y=$py", 0,
            android.graphics.Color.red(fixed.getPixel(80, py)))
        val heading = compose.onNodeWithTag("heading-1").fetchSemanticsNode().boundsInRoot
        assertEquals("Heading positions must not change", 260f - offset, heading.top, 0.1f)
        assertEquals("Current heading stays fully visible", 255,
            android.graphics.Color.red(fixed.getPixel(80, heading.top.toInt() + 4)))
        assertEquals("Current poster stays fully visible", 255,
            android.graphics.Color.red(fixed.getPixel(80, heading.bottom.toInt() + 24)))
        fixed.recycle()
        assertEquals("The viewport must record its children only once per frame", 1, lastFrameDraws["list"])
    }

    @Test fun aShortHeadingCannotLeavePosterBottomsInsideTheOldFeather() = verifySliverIsGone(-12, 248f)
    @Test fun posterBottomsJustBelowTheOldCutoffAlsoDisappear() = verifySliverIsGone(-30, 266f)
    @Test fun ordinaryPagesUseTheSameBodyFadeWithoutMovingTheirViewport() = verifySliverIsGone(-30, 266f, false)

    @Test fun aCrossingBodyRecordsItsPostersOnlyOnceWhileApplyingTheSoftMask() {
        content(-42)
        compose.runOnIdle { fadeBodies.value = true }
        compose.waitForIdle()
        recordHardwareDisplayList()
        println("POSTER_DRAW_COUNTS=$lastFrameDraws")
        assertTrue("The soft edge must draw card content only once", (lastFrameDraws["0"] ?: 0) <= 1)
    }

    @Test fun theNewBodyEdgeRemainsASoftFade() {
        content(-42)
        compose.runOnIdle { fadeBodies.value = true }
        compose.waitForIdle()
        val image = pixels()
        assertEquals(0, android.graphics.Color.red(image.getPixel(80, 271)))
        assertTrue("Moving artwork should fade before it reaches the clear region",
            android.graphics.Color.red(image.getPixel(80, 275)) in 1..100)
        assertEquals("The empty gap must stay clear", 0, android.graphics.Color.red(image.getPixel(80, 285)))
        image.recycle()
    }

    @Test fun movingTheFadeReusesTheRecordedPosterBodies() {
        content(-44)
        compose.runOnIdle { fadeBodies.value = true }
        compose.waitForIdle()
        recordHardwareDisplayList()
        val before = totalBodyRecordings.toMap()
        assertTrue("The crossing poster must actually have a recorded display list", (before["0"] ?: 0) > 0)
        assertTrue("The next row must actually have been recorded", (before["1"] ?: 0) > 0)
        for (offset in listOf(-42, -40, -38, -36)) {
            compose.runOnIdle { seek(offset) }
            compose.waitForIdle()
            recordHardwareDisplayList()
        }
        assertEquals("Changing the mask must reuse the crossing poster's display list", before["0"], totalBodyRecordings["0"])
        assertEquals("Rows below the fade must keep their cached poster bodies", before["1"], totalBodyRecordings["1"])
    }

    @Test fun theEdgeIsASpatialGradientRatherThanUniformPosterOpacity() {
        content(-62, xlPage = false)
        compose.runOnIdle { fadeBodies.value = true }
        compose.waitForIdle()
        val image = pixels()
        val samples = (273..291 step 3).map { android.graphics.Color.red(image.getPixel(80, it)) }
        assertTrue("The same poster must have several increasing opacity levels: $samples", samples.distinct().size >= 5)
        assertTrue(samples.zipWithNext().all { (a, b) -> b > a })
        for (y in 240..271) assertEquals("No lower sliver behind the hero", 0, android.graphics.Color.red(image.getPixel(80, y)))
        image.recycle()
    }
}
