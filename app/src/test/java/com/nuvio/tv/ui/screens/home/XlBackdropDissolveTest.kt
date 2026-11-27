package com.nuvio.tv.ui.screens.home

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
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
class XlBackdropDissolveTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val target = mutableStateOf("A")
    private val frozen = mutableStateOf(false)
    private val duration = mutableIntStateOf(180)
    private val readyCallbacks = mutableMapOf<String, () -> Unit>()
    private val finished = mutableListOf<String?>()
    private val activeFrames = mutableSetOf<ModernHeroBackdropFrame>()
    @After fun cleanup() {
        if (::activity.isInitialized) compose.runOnUiThread { activity.pause().stop().destroy() }
    }
    private fun content(mixed: Boolean = false) {
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity.get().setContent {
                val transition = rememberModernHeroBackdropTransition(target.value, 1f, false, !mixed || target.value != "A", true, 160, 90)
                Box(Modifier.width(960.dp).height(540.dp).background(Color.Black)) {
                    XlBackdropDissolve(transition, duration.intValue, frozen = frozen.value, onFinished = { finished += it.url }) { frame, ready ->
                        val color = when (frame.url) { "A" -> android.graphics.Color.RED; "B" -> android.graphics.Color.BLUE; else -> android.graphics.Color.GREEN }
                        val image = frame.compositedBitmap ?: remember(frame) {
                            Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                        }
                        SideEffect { frame.blurredBitmap = image }
                        DisposableEffect(frame) {
                            activeFrames += frame
                            onDispose { activeFrames -= frame }
                        }
                        SideEffect { readyCallbacks[checkNotNull(frame.url)] = ready }
                        Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        if (frame.compositedBitmap != null) SideEffect { ready() }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }
    private fun advance(ms: Int) = repeat((ms + 15) / 16) {
        compose.mainClock.advanceTimeBy(16)
        compose.waitForIdle()
    }
    private fun pixel(): Int {
        var result = 0
        compose.runOnUiThread {
            val view = activity.get().findViewById<android.view.View>(android.R.id.content)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap)); result = bitmap.getPixel(200, 200); bitmap.recycle()
        }
        return result
    }

    @Test fun aColdIncomingImageHoldsTheOldCompositeUntilItsRendererIsReady() {
        content()
        compose.runOnIdle { target.value = "B" }
        advance(96)
        assertEquals(android.graphics.Color.RED, pixel())
        assertTrue(finished.isEmpty())
        compose.runOnIdle { checkNotNull(readyCallbacks["B"])() }
        advance(80)
        val middle = pixel()
        assertTrue(android.graphics.Color.red(middle) in 1..254)
        assertTrue(android.graphics.Color.blue(middle) in 1..254)
        assertEquals(255.0, (android.graphics.Color.red(middle) + android.graphics.Color.blue(middle)).toDouble(), 2.0)
        advance(160)
        assertEquals(android.graphics.Color.BLUE, pixel())
        assertEquals(listOf("B"), finished)
    }

    @Test fun unreadyBlurCannotRevealEvenAfterTheFormerTimeout() {
        content()
        compose.runOnIdle { target.value = "B" }
        advance(2300)
        assertEquals("Keep the old image rather than expose an unready blur", android.graphics.Color.RED, pixel())
        assertTrue(finished.isEmpty())
        compose.runOnIdle { checkNotNull(readyCallbacks["B"])() }
        advance(240)
        assertEquals(android.graphics.Color.BLUE, pixel())
        assertEquals(listOf("B"), finished)
    }

    @Test fun mixedModeDissolveRetainsTheOutgoingImageThroughPreparationFrames() {
        content(mixed = true)
        compose.runOnIdle { target.value = "B" }
        advance(96)
        assertEquals(android.graphics.Color.RED, pixel())
        compose.runOnIdle { checkNotNull(readyCallbacks["B"])() }
        advance(16)
        assertEquals("Warm the prepared image without exposing it", android.graphics.Color.RED, pixel())
        advance(112)
        assertTrue(android.graphics.Color.red(pixel()) in 1..254)
        assertTrue(android.graphics.Color.blue(pixel()) in 1..254)
        advance(240)
        assertEquals(android.graphics.Color.BLUE, pixel())
        assertEquals(listOf("B"), finished)
    }

    @Test fun aLateReadyCallbackCannotReviveAnObsoleteTarget() {
        content()
        compose.runOnIdle { target.value = "B" }
        advance(64)
        val late = checkNotNull(readyCallbacks["B"])
        compose.runOnIdle { target.value = "C" }
        advance(48)
        compose.runOnIdle { checkNotNull(readyCallbacks["C"])() }
        advance(240)
        assertEquals(android.graphics.Color.GREEN, pixel())
        compose.runOnIdle { late() }
        advance(128)
        assertEquals(android.graphics.Color.GREEN, pixel())
        assertEquals(listOf("C"), finished)
    }

    @Test fun rapidPreparedBlursRetainOneBackgroundAndOneIncomingLayer() {
        content()
        var merges = 0
        for (name in listOf("B", "C", "A", "B", "C", "A")) {
            val before = pixel()
            compose.runOnIdle { target.value = name }
            compose.waitUntil(10_000) {
                compose.mainClock.advanceTimeBy(16); compose.waitForIdle()
                activeFrames.any { it.url == name }
            }
            val retained = pixel()
            // The pending target takes effect on the next animation frame.
            // Permit that one frame of the old fade, never an extra dark beat.
            for (shift in listOf(0, 8, 16)) assertEquals("Retargeting $name keeps the visible blend",
                ((before shr shift) and 255).toDouble(), ((retained shr shift) and 255).toDouble(), 26.0)
            assertEquals("Opaque color fields never reveal black", 255.0,
                (android.graphics.Color.red(retained) + android.graphics.Color.green(retained) + android.graphics.Color.blue(retained)).toDouble(), 3.0)
            assertTrue("Prepared blur layers must not accumulate: ${activeFrames.map { it.url }}", activeFrames.size <= 2)
            if (activeFrames.any { it.compositedBitmap != null }) merges++
            compose.runOnIdle { checkNotNull(readyCallbacks[name])() }
            advance(64)
        }
        assertTrue("Exercise interrupted partial blends", merges >= 4)
        advance(240)
        assertEquals(1, activeFrames.size)
        assertEquals(android.graphics.Color.RED, pixel())
    }

    @Test fun cachingTheCompleteXlFramePreservesItsLighterGradientThroughPlatformTransforms() {
        val opacity = mutableFloatStateOf(1f)
        val travel = mutableFloatStateOf(0f)
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity.get().setContent {
                val transition = rememberModernHeroBackdropTransition("tint", 1f, false, true, true, 160, 90)
                val artwork: @Composable () -> Unit = {
                    Box(Modifier.fillMaxSize().background(Color(0.85f, 0.35f, 0.15f)))
                    ModernHeroGradientLayer(Color.Black, false, 0f, Modifier.fillMaxSize(),
                        cinematicMode = true, opacity = XL_BLUR_GRADIENT_OPACITY)
                }
                Box(Modifier.width(960.dp).height(540.dp).background(Color.White)) {
                    for (cached in listOf(false, true)) {
                        Box(Modifier.offset(x = if (cached) 480.dp else 0.dp).width(440.dp).height(270.dp)
                            .clipToBounds().graphicsLayer {
                                alpha = opacity.floatValue
                                translationX = travel.floatValue
                            }) {
                            if (cached) XlBackdropDissolve(transition, 220) { _, _ -> artwork() }
                            else artwork()
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        for ((shift, alpha) in listOf(0f to 1f, -12f to 0.65f, 12f to 1f, 0f to 0.25f, 0f to 1f)) {
            compose.runOnIdle { travel.floatValue = shift; opacity.floatValue = alpha }
            compose.waitForIdle()
            compose.runOnUiThread {
                val view = activity.get().findViewById<android.view.View>(android.R.id.content)
                val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(image))
                for (y in 16..254 step 17) for (x in 24..412 step 19) for (channel in listOf(0, 8, 16)) {
                    val reference = image.getPixel(x, y) ushr channel and 255
                    val cached = image.getPixel(x + 480, y) ushr channel and 255
                    assertEquals("Cache must preserve the per-draw tint at $x,$y shift=$shift alpha=$alpha",
                        reference.toDouble(), cached.toDouble(), 2.0)
                }
                assertTrue("The reference includes visible artwork as well as overlapping gradients",
                    android.graphics.Color.red(image.getPixel(400, 40)) > 50)
                image.recycle()
            }
        }
    }
    @Test fun selectionFreezeAndDurationChangesCannotRestartAnAdmittedMixedFade() {
        duration.intValue = 300
        content(mixed = true)
        compose.runOnIdle { target.value = "B" }
        advance(96)
        compose.runOnIdle { checkNotNull(readyCallbacks["B"])() }
        advance(144)
        val middle = android.graphics.Color.blue(pixel())
        assertTrue(middle in 1..254)
        compose.runOnIdle { frozen.value = true; duration.intValue = 180; target.value = "C" }
        advance(64)
        assertTrue("The active fade continues even during the selection freeze",
            android.graphics.Color.blue(pixel()) > middle)
        assertFalse("No intermediate image is admitted while frozen",
            activeFrames.any { it.url == "C" })
        advance(240)
        assertEquals(android.graphics.Color.BLUE, pixel())
        assertEquals(listOf("B"), finished)
        compose.runOnIdle { target.value = "B"; frozen.value = false }
        advance(320)
        assertEquals("Returning to the same committed image does not start another fade", listOf("B"), finished)
        assertEquals(android.graphics.Color.BLUE, pixel())
    }

    @Test fun internalDissolveGradientCannotBeStackedWithTheExternalGradient() {
        for (alpha in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            assertEquals(0f, xlExternalBackdropGradientAlpha(alpha, true, false), 0f)
            assertEquals(alpha, xlExternalBackdropGradientAlpha(alpha, false, false), 0f)
            assertEquals(alpha, xlExternalBackdropGradientAlpha(alpha, true, true), 0f)
        }
    }

}
