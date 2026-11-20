package com.nuvio.tv.ui.screens.home

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
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
class XlNumberContrastTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val opacity = mutableStateOf(1f)
    private val contour = mutableStateOf(1.dp)
    @After fun cleanup() {
        if (::activity.isInitialized) compose.runOnUiThread { activity.pause().stop().destroy() }
    }

    private fun content(style: NumberStyle, xl: Boolean) {
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity.get().setContent {
                MaterialTheme {
                    Box(Modifier.width(960.dp).height(540.dp).background(Color.White)) {
                        Box(Modifier.padding(start = 80.dp, top = 40.dp)) {
                            NumberedCatalogCardWrapper(8, 163.dp, 244.dp,
                                preMeasuredTextWidth = 48.dp, numberStyle = style,
                                compactXl = xl, xlContrastStrokeWidth = contour.value, numberOpacity = opacity) {
                                Spacer(Modifier.width(163.dp).height(244.dp))
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun pixels(): Bitmap {
        lateinit var bitmap: Bitmap
        compose.runOnUiThread {
            val view = activity.get().findViewById<android.view.View>(android.R.id.content)
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        return bitmap
    }

    private fun exterior(actual: Bitmap, reference: Bitmap): List<Pair<Int, Int>> = buildList {
        for (y in 40..340) for (x in 0..200) {
            if (reference.getPixel(x, y) == android.graphics.Color.WHITE &&
                android.graphics.Color.red(actual.getPixel(x, y)) < 200) add(x to y)
        }
    }

    private fun reference(): Bitmap {
        compose.runOnIdle { contour.value = 0.dp }
        compose.waitForIdle()
        return pixels()
    }

    private fun verify(style: NumberStyle, xl: Boolean) {
        content(style, xl)
        val image = pixels()
        val reference = reference()
        val edge = exterior(image, reference)
        if (xl) assertTrue("XL needs a dark contour beyond the original glyph", edge.size >= 4)
        else assertTrue("Regular numbers must be pixel-identical", image.sameAs(reference))
        image.recycle(); reference.recycle()
    }

    @Test fun xlSolidHasAThinDarkExterior() = verify(NumberStyle.SOLID, true)
    @Test fun xlOutlineHasAThinDarkExterior() = verify(NumberStyle.OUTLINE, true)
    @Test fun regularSolidHasNoAddedContour() = verify(NumberStyle.SOLID, false)
    @Test fun regularOutlineHasNoAddedContour() = verify(NumberStyle.OUTLINE, false)

    @Test fun theContrastContourFadesTogetherWithTheNumber() {
        content(NumberStyle.SOLID, true)
        val full = pixels()
        val reference = reference()
        val edge = exterior(full, reference)
        assertTrue(edge.isNotEmpty())
        compose.runOnIdle { contour.value = 1.dp; opacity.value = 0.5f }
        compose.waitForIdle()
        val half = pixels()
        edge.forEach { (x, y) ->
            assertEquals("Dark ink must use the same fade as the glyph", (255.0 + android.graphics.Color.red(full.getPixel(x, y))) / 2,
                android.graphics.Color.red(half.getPixel(x, y)).toDouble(), 3.0)
        }
        compose.runOnIdle { opacity.value = 0f }
        compose.waitForIdle()
        val gone = pixels()
        edge.forEach { (x, y) -> assertEquals(android.graphics.Color.WHITE, gone.getPixel(x, y)) }
        full.recycle(); reference.recycle(); half.recycle(); gone.recycle()
    }
}
