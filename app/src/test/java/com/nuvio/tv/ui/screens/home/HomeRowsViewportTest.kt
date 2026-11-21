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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** The diagnostic uses the original hard viewport, without a feather or a second content pass. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeRowsViewportTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>

    @After fun cleanup() {
        if (::activity.isInitialized) compose.runOnUiThread { activity.pause().stop().destroy() }
    }

    private fun verifyViewport(xlPage: Boolean) {
        val scene = XlSceneState()
        val list = LazyListState()
        var contentDraws = 0
        var lastFrameDraws = 0
        lateinit var seek: (Int) -> Unit
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity.get().setContent {
                val scope = rememberCoroutineScope()
                seek = { offset -> scope.launch { list.scrollToItem(1, offset) }; Unit }
                Box(Modifier.width(960.dp).height(540.dp).drawWithContent {
                    contentDraws = 0
                    drawContent()
                    lastFrameDraws = contentDraws
                }.background(Color.Blue)) {
                    LazyColumn(state = list,
                        modifier = Modifier.offset(y = if (xlPage) 0.dp else 40.dp)
                            .width(960.dp).height(if (xlPage) 540.dp else 200.dp)
                            .xlSceneRowsViewport(scene, list, xlPage, 260.dp)
                            .drawWithContent { contentDraws++; drawContent() }) {
                        items(8) { Box(Modifier.fillMaxWidth().height(220.dp).background(Color.White)) }
                    }
                }
            }
        }
        compose.waitForIdle()
        for (offset in listOf(0, 19, 35, 155)) {
            compose.runOnIdle { seek(offset) }
            compose.waitForIdle()
            compose.runOnUiThread {
                val view = activity.get().findViewById<android.view.View>(android.R.id.content)
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                assertEquals("The scrolling viewport draws its content once, offset=$offset", 1, lastFrameDraws)
                val edge = if (xlPage) 260 else 40
                assertEquals("Pixels above the hard boundary stay excluded", 0,
                    android.graphics.Color.red(bitmap.getPixel(500, edge - 1)))
                for (y in edge..edge + 20) {
                    assertEquals("No partial-alpha strip at y=$y offset=$offset", 255,
                        android.graphics.Color.red(bitmap.getPixel(500, y)))
                }
                if (!xlPage) assertEquals("The original viewport height is retained", 0,
                    android.graphics.Color.red(bitmap.getPixel(500, 240)))
                bitmap.recycle()
            }
        }
    }

    @Test fun ordinaryScrollingRetainsTheOriginalHardViewportInOnePass() = verifyViewport(false)
    @Test fun mixedPageScrollingRetainsItsHardMetadataExclusionInOnePass() = verifyViewport(true)
}
