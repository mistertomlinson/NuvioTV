package com.nuvio.tv.ui.screens.addon

import android.app.Application
import android.os.SystemClock
import android.view.KeyEvent
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.nuvio.tv.domain.model.CatalogPosterMode
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.home.NumberStyle
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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CatalogRowAppearanceControlsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    @After fun cleanup() {
        if (::activity.isInitialized) compose.runOnUiThread { activity.pause().stop().destroy() }
    }

    @Test fun onePosterButtonCyclesPortraitLandscapeAndBothXlVariants() {
        val mode = mutableStateOf(CatalogPosterMode.PORTRAIT)
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity.get().setContent {
                MaterialTheme { CatalogPosterModeControl(mode.value, { mode.value = mode.value.next() }) }
            }
        }
        compose.waitForIdle()
        for (label in listOf("Portrait", "Landscape", "XL with summary", "XL without summary", "Portrait")) {
            compose.onAllNodes(hasClickAction()).assertCountEquals(1)
            compose.onNodeWithTag("catalog-poster-mode").assert(
                SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, label))
            select("catalog-poster-mode")
            settleFrames()
        }
    }

    @Test fun focusedPortraitStaysGreyWhileTheOtherModesRenderWhite() {
        val mode = mutableStateOf(CatalogPosterMode.PORTRAIT)
        val requester = FocusRequester()
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity.get().setContent {
                MaterialTheme {
                    Box(Modifier.background(Color.Black).padding(16.dp)) {
                        CatalogPosterModeControl(mode.value, { mode.value = mode.value.next() }, Modifier.focusRequester(requester))
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { requester.requestFocus() }
        compose.waitForIdle()
        val grey = iconBrightness()
        select("catalog-poster-mode")
        settleFrames()
        val white = iconBrightness()
        assertTrue("Portrait icon should be muted: $grey versus enabled $white", white > grey + 60)
        repeat(2) {
            select("catalog-poster-mode")
            settleFrames()
            assertTrue(iconBrightness() > grey + 60)
        }
        select("catalog-poster-mode")
        settleFrames()
        assertTrue(iconBrightness() < white - 60)
    }

    @Test fun numberStyleUsesDrawableArtworkForBothStylesWithoutFontGlyphClipping() {
        val mode = mutableStateOf(NumberStyle.SOLID)
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity.get().setContent {
                MaterialTheme { CatalogNumberStyleControl(mode.value, { mode.value = NumberStyle.OUTLINE }) }
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithText("#").assertCountEquals(0)
        compose.onNodeWithContentDescription("Solid row numbers. Select for outline numbers.").assertExists()
        select("catalog-number-style")
        settleFrames()
        compose.onNodeWithContentDescription("Outline row numbers. Select to turn off.").assertExists()
        compose.onAllNodesWithText("#").assertCountEquals(0)
    }

    @Test fun solidAndOutlineHaveIdenticalRenderedBoundsAndTheSameOuterShape() {
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            for (size in listOf(18, 36, 72, 192)) {
                fun render(id: Int): Bitmap {
                    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    val drawable = activity.get().resources.getDrawable(id, activity.get().theme).mutate()
                    drawable.setTint(android.graphics.Color.WHITE)
                    drawable.setBounds(0, 0, size, size)
                    drawable.draw(Canvas(bitmap))
                    return bitmap
                }
                val solid = render(R.drawable.ic_catalog_number_solid)
                val outline = render(R.drawable.ic_catalog_number_outline)
                fun ink(bitmap: Bitmap): Set<Pair<Int, Int>> = buildSet {
                    for (y in 0 until size) for (x in 0 until size) {
                        if (android.graphics.Color.alpha(bitmap.getPixel(x, y)) > 0) add(x to y)
                    }
                }
                val solidInk = ink(solid)
                val outlineInk = ink(outline)
                fun bounds(points: Set<Pair<Int, Int>>) = listOf(points.minOf { it.first }, points.minOf { it.second },
                    points.maxOf { it.first }, points.maxOf { it.second })
                assertTrue("Outline cannot extend outside the solid shape at ${size}px", solidInk.containsAll(outlineInk))
                assertEquals("Both variants must keep the same size and position at ${size}px", bounds(solidInk), bounds(outlineInk))
                fun coverage(bitmap: Bitmap): Long = (0 until size).sumOf { y ->
                    (0 until size).sumOf { x -> android.graphics.Color.alpha(bitmap.getPixel(x, y)).toLong() }
                }
                assertTrue("The outline keeps the silhouette while removing its interior fill", coverage(outline) < coverage(solid))
                solid.recycle(); outline.recycle()
            }
        }
    }

    private fun iconBrightness(): Int {
        val bounds = compose.onNodeWithTag("catalog-poster-mode").fetchSemanticsNode().boundsInRoot
        var brightest = 0
        compose.runOnUiThread {
            val view = activity.get().findViewById<View>(android.R.id.content)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLACK)
            view.draw(Canvas(bitmap))
            // Inspect the icon interior, excluding the button's focus border.
            val centerX = bounds.center.x.toInt()
            val centerY = bounds.center.y.toInt()
            for (y in centerY - 9..centerY + 9) for (x in centerX - 9..centerX + 9) {
                val pixel = bitmap.getPixel(x, y)
                brightest = maxOf(brightest, (android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel)) / 3)
            }
            bitmap.recycle()
        }
        return brightest
    }

    private fun select(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        var pressedAt = 0L
        compose.runOnUiThread {
            ShadowSystemClock.advanceBy(Duration.ofMillis(120))
            pressedAt = SystemClock.uptimeMillis()
            activity.get().dispatchKeyEvent(KeyEvent(pressedAt, pressedAt, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        }
        settleFrames()
        compose.runOnUiThread {
            activity.get().dispatchKeyEvent(KeyEvent(pressedAt, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        }
        settleFrames()
    }

    private fun settleFrames() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
    }
}
