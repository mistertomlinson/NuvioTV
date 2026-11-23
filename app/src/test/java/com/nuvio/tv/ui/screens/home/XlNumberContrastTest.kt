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
    @After fun cleanup() {
        if (::activity.isInitialized) compose.runOnUiThread { activity.pause().stop().destroy() }
    }

    private fun content(style: NumberStyle, xl: Boolean, backdrop: Color = Color.White) {
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity.get().setContent {
                MaterialTheme {
                    Box(Modifier.width(960.dp).height(540.dp).background(backdrop)) {
                        Box(Modifier.padding(start = 80.dp, top = 40.dp)) {
                            NumberedCatalogCardWrapper(8, 163.dp, 244.dp,
                                preMeasuredTextWidth = 48.dp, numberStyle = style,
                                compactXl = xl, numberOpacity = opacity) {
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

    private fun verify(style: NumberStyle, xl: Boolean) {
        content(style, xl)
        val image = pixels()
        var gray = 0
        var dark = 0
        for (y in 40..340) for (x in 0..200) {
            val red = android.graphics.Color.red(image.getPixel(x, y))
            if (red in 100..200) gray++
            if (red < 100) dark++
        }
        assertTrue("Original gray glyph remains: $gray", gray > 20)
        if (style == NumberStyle.SOLID) assertEquals("No added solid rank contour", 0, dark)
        else assertEquals("Outlined ranks have no interior fill", 0, dark)
        image.recycle()
    }
    /**
     * Digit 4 contains adjacent/overlapping font primitives whose individual
     * stroke contours must not appear inside the visible filled silhouette.
     * The completed outline must have ink only outside the union of the fill.
     */
    @Test fun outlineFourDoesNotDrawInternalFontSeams() {
        val width = 260
        val height = 250
        val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textSize = 180f
            color = android.graphics.Color.GRAY
            style = android.graphics.Paint.Style.FILL
        }
        val expanded = android.graphics.Paint(fill).apply {
            style = android.graphics.Paint.Style.FILL_AND_STROKE
            strokeWidth = 6f
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        val erase = android.graphics.Paint(fill).apply {
            style = android.graphics.Paint.Style.FILL
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
        }
        val solid = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(solid).drawText("4", 32f, 200f, fill)
        val outline = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outline)
        val layer = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), 255)
        canvas.drawCatalogNumberSilhouetteOutline("4", 32f, 200f, expanded, erase)
        canvas.restoreToCount(layer)

        var verifiedInterior = 0
        var visibleOutline = 0
        for (y in 5 until height - 5) for (x in 5 until width - 5) {
            if (android.graphics.Color.alpha(outline.getPixel(x, y)) > 200) visibleOutline++
            // Any pixel surrounded by solid ink is interior, even if the
            // font uses crossing contours. No outline line belongs there.
            val inside = (-4..4).all { dy ->
                (-4..4).all { dx ->
                    android.graphics.Color.alpha(solid.getPixel(x + dx, y + dy)) >= 250
                }
            }
            if (inside) {
                verifiedInterior++
                assertEquals("No outline stroke inside the filled 4 at ($x,$y)",
                    0, android.graphics.Color.alpha(outline.getPixel(x, y)))
            }
        }
        assertTrue("The test must check real interior pixels", verifiedInterior > 100)
        assertTrue("The test must render a visible 4 outline", visibleOutline > 100)
        solid.recycle()
        outline.recycle()
    }

    @Test fun xlSolidHasNoAddedDarkContour() = verify(NumberStyle.SOLID, true)
    @Test fun xlOutlineHasTransparentInterior() = verify(NumberStyle.OUTLINE, true)
    @Test fun regularSolidHasNoAddedContour() = verify(NumberStyle.SOLID, false)
    @Test fun regularOutlineHasTransparentInterior() = verify(NumberStyle.OUTLINE, false)

    private fun verifyFade(style: NumberStyle) {
        content(style, true)
        val full = pixels()
        val ink = buildList {
            for (y in 40..340) for (x in 0..200)
                if (android.graphics.Color.red(full.getPixel(x, y)) < 140) add(x to y)
        }
        assertTrue(ink.isNotEmpty())
        compose.runOnIdle { opacity.value = 0.5f }
        compose.waitForIdle()
        val half = pixels()
        ink.forEach { (x, y) ->
            assertEquals("All ink shares the number fade", (255.0 + android.graphics.Color.red(full.getPixel(x, y))) / 2,
                android.graphics.Color.red(half.getPixel(x, y)).toDouble(), 3.0)
        }
        compose.runOnIdle { opacity.value = 0f }
        compose.waitForIdle()
        val gone = pixels()
        ink.forEach { (x, y) -> assertEquals(android.graphics.Color.WHITE, gone.getPixel(x, y)) }
        full.recycle(); half.recycle(); gone.recycle()
    }
    @Test fun solidRanksFadeAsOneGlyph() = verifyFade(NumberStyle.SOLID)
    @Test fun outlinedRanksFadeAsOneGlyph() = verifyFade(NumberStyle.OUTLINE)

    private fun verifyHollowOnColor(xl: Boolean) {
        content(NumberStyle.OUTLINE, xl, Color.Magenta)
        val image = pixels()
        var strokePixels = 0
        for (y in 40..340) for (x in 0..200) {
            val pixel = image.getPixel(x, y)
            val r = android.graphics.Color.red(pixel)
            val g = android.graphics.Color.green(pixel)
            val b = android.graphics.Color.blue(pixel)
            assertEquals("The backdrop must show through every interior pixel", r, b)
            assertTrue("Only a gray stroke may blend with magenta; no black/white fill", g <= 138 && r >= 134)
            if (r < 245) strokePixels++
        }
        assertTrue("The real outline remains visible", strokePixels > 20)
        image.recycle()
    }
    @Test fun xlOutlineShowsTheColoredBackdropThroughItsInterior() = verifyHollowOnColor(true)
    @Test fun regularOutlineShowsTheColoredBackdropThroughItsInterior() = verifyHollowOnColor(false)
}
