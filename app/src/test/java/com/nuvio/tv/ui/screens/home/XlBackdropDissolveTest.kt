package com.nuvio.tv.ui.screens.home

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    private val readyCallbacks = mutableMapOf<String, () -> Unit>()
    private val finished = mutableListOf<String?>()
    @After fun cleanup() {
        if (::activity.isInitialized) compose.runOnUiThread { activity.pause().stop().destroy() }
    }
    private fun content() {
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity.get().setContent {
                val transition = rememberModernHeroBackdropTransition(target.value, 1f, false, true, true, 160, 90)
                Box(Modifier.width(960.dp).height(540.dp).background(Color.Black)) {
                    XlBackdropDissolve(transition, 180, onFinished = { finished += it.url }) { frame, ready ->
                        SideEffect { readyCallbacks[checkNotNull(frame.url)] = ready }
                        Box(Modifier.fillMaxSize().background(when (frame.url) {
                            "A" -> Color.Red
                            "B" -> Color.Blue
                            else -> Color.Green
                        }))
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
}
