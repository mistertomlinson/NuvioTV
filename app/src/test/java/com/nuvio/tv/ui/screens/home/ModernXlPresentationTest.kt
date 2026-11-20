package com.nuvio.tv.ui.screens.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
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
class ModernXlPresentationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    @After fun cleanup() {
        if (::activity.isInitialized) compose.runOnUiThread { activity.pause().stop().destroy() }
    }
    private fun content(body: @Composable () -> Unit) {
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity.get().setContent { MaterialTheme { body() } }
        }
        compose.waitForIdle()
    }
    private fun brightness(x: Int = 50, y: Int = 50): Int {
        var red = 0
        compose.runOnUiThread {
            val view = activity.get().findViewById<android.view.View>(android.R.id.content)
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLACK)
            view.draw(android.graphics.Canvas(bitmap))
            red = android.graphics.Color.red(bitmap.getPixel(x, y))
            bitmap.recycle()
        }
        return red
    }
    private fun snapshot(name: String) = XlMetadataSnapshot(name,
        HeroPreview(name, null, name, "Movie", yearText = null, genres = emptyList(), poster = null, backdrop = null, imageUrl = null))

    @Test fun neighborsAreMeasuredButInvisibleAndTheChosenSlotIsReused() {
        val neighbors = listOf(snapshot("Above"), snapshot("Below"), snapshot("Too far"))
        val active = mutableStateOf<XlMetadataSnapshot?>(null)
        val tokens = mutableMapOf<String, Int>()
        val measured = mutableSetOf<String>()
        var sequence = 0
        content {
            Box(Modifier.width(960.dp).height(540.dp).background(Color.Black)) {
                XlMetadataHost(XlMetadataSlot(active.value?.rowKey ?: "XL", 1), active.value,
                    if (active.value == null) neighbors else emptyList(), 1, Modifier.width(430.dp)) { item, visible ->
                    val token = remember { ++sequence }
                    SideEffect { tokens[item.rowKey] = token }
                    Box(Modifier.size(100.dp).onSizeChanged { measured += item.rowKey }
                        .background(if (visible) Color.White else Color.Red)) {
                        Text(item.rowKey, color = Color.Black, modifier = Modifier.testTag("prepared-${item.rowKey}"))
                    }
                }
            }
        }
        assertEquals(setOf("Above", "Below"), measured)
        assertEquals(2, sequence)
        val original = tokens["Above"]
        compose.onNodeWithText("Above").assertDoesNotExist()
        assertEquals("Pre-rendering must draw no hidden metadata pixels", 0, brightness())
        compose.runOnIdle { active.value = neighbors.first() }
        compose.waitForIdle()
        compose.onNodeWithText("Above").assertIsDisplayed()
        assertEquals("Activating the prepared hero must preserve its existing composition", original, tokens["Above"])
        assertEquals(2, sequence)
        assertTrue(brightness(90, 90) > 240)
    }

    @Test fun fadingRanksKeepTheirEntireLowerOverhangUntilTheyAreTransparent() {
        val opacity = mutableStateOf(1f)
        content {
            Box(Modifier.width(960.dp).height(540.dp).background(Color.Black)) {
                Box(Modifier.padding(start = 52.dp, top = 40.dp)) {
                    NumberedCatalogCardWrapper(8, 163.dp, 244.dp, preMeasuredTextWidth = 32.dp,
                        compactXl = true, compactXlFontSize = xlRankFontSize(244f), numberOpacity = opacity) {
                        Spacer(Modifier.width(163.dp).height(244.dp))
                    }
                }
            }
        }
        fun pixels(): android.graphics.Bitmap {
            lateinit var result: android.graphics.Bitmap
            compose.runOnUiThread {
                val view = activity.get().findViewById<android.view.View>(android.R.id.content)
                result = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                result.eraseColor(android.graphics.Color.BLACK)
                view.draw(android.graphics.Canvas(result))
            }
            return result
        }
        val full = pixels()
        val lowerInk = buildList {
            for (y in 285..292) for (x in 0..50)
                if (full.getPixel(x, y) == android.graphics.Color.rgb(136, 136, 136)) add(x to y)
        }
        full.recycle()
        assertTrue("Reference rank should extend below the poster bottom", lowerInk.isNotEmpty())
        compose.runOnIdle { opacity.value = 0.5f }
        compose.waitForIdle()
        val half = pixels()
        lowerInk.forEach { (x, y) ->
            assertEquals("The lower ink must fade rather than be clipped", 68.0, android.graphics.Color.red(half.getPixel(x, y)).toDouble(), 2.0)
        }
        half.recycle()
        compose.runOnIdle { opacity.value = 0f }
        compose.waitForIdle()
        val gone = pixels()
        lowerInk.forEach { (x, y) -> assertEquals(0, android.graphics.Color.red(gone.getPixel(x, y))) }
        gone.recycle()
    }

    @Test fun leavingAnXlFirstPageKeepsItsCapturedBackdropDarkUntilBlack() {
        val xl = mutableStateOf(true)
        val ghost = mutableStateOf(false)
        val black = mutableStateOf(0f)
        val handoff = XlPlatformBackdropHandoff()
        lateinit var backdrop: XlBackdropPresentation
        content {
            backdrop = remember(xl.value) { XlBackdropPresentation(null, xl.value) }
            Box(Modifier.width(960.dp).height(540.dp).background(Color.White)) {
                ModernXlBackdropDimmer(rememberXlPlatformBackdropDim(backdrop, handoff, ghost.value))
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = black.value }.background(Color.Black))
            }
        }
        val original = brightness()
        assertTrue(original in 10..20)
        compose.runOnIdle {
            handoff.capture(backdrop.dim.value, true)
            ghost.value = true
            xl.value = false
        }
        compose.waitForIdle()
        assertEquals("An ordinary incoming scene must not brighten the old XL bitmap", original, brightness())
        compose.runOnIdle { black.value = 1f; ghost.value = false }
        compose.waitForIdle()
        assertEquals(0, brightness())
        compose.runOnIdle { black.value = 0.5f }
        compose.waitForIdle()
        assertTrue(brightness() in 110..140)
        compose.runOnIdle { black.value = 0f }
        compose.waitForIdle()
        assertEquals(255, brightness())
    }

    @Test fun enteringAnXlFirstPageDoesNotSnapTheOutgoingBackdropDarkAtFlip() {
        val xl = mutableStateOf(false)
        val ghost = mutableStateOf(false)
        val black = mutableStateOf(0f)
        val handoff = XlPlatformBackdropHandoff()
        lateinit var backdrop: XlBackdropPresentation
        content {
            backdrop = remember(xl.value) { XlBackdropPresentation(null, xl.value) }
            Box(Modifier.width(960.dp).height(540.dp).background(Color.White)) {
                ModernXlBackdropDimmer(rememberXlPlatformBackdropDim(backdrop, handoff, ghost.value))
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = black.value }.background(Color.Black))
            }
        }
        assertEquals(255, brightness())
        compose.runOnIdle {
            handoff.capture(backdrop.dim.value, false)
            ghost.value = true
            xl.value = true
        }
        compose.waitForIdle()
        assertEquals("Flipping the live page must not dim the outgoing captured page", 255, brightness())
        compose.runOnIdle { black.value = 0.5f }
        compose.waitForIdle()
        assertTrue("The old backdrop should fade through an intermediate brightness", brightness() in 110..140)
        compose.runOnIdle { black.value = 1f; ghost.value = false }
        compose.waitForIdle()
        assertEquals(0, brightness())
        compose.runOnIdle { black.value = 0f }
        compose.waitForIdle()
        assertTrue(brightness() in 10..20)
    }
}
