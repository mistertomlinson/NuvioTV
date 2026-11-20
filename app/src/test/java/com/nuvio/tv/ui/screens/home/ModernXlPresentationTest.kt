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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.positionInRoot
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
    private fun pixels(): android.graphics.Bitmap {
        lateinit var result: android.graphics.Bitmap
        compose.runOnUiThread {
            val view = activity.get().findViewById<android.view.View>(android.R.id.content)
            result = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            result.eraseColor(android.graphics.Color.BLACK)
            view.draw(android.graphics.Canvas(result))
        }
        return result
    }
    private fun snapshot(name: String) = XlMetadataSnapshot(name,
        HeroPreview(name, null, name, "Movie", yearText = null, genres = emptyList(), poster = null, backdrop = null, imageUrl = null))

    private fun colorArtworkUrl(): String {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val image = java.io.File(context.cacheDir, "xl-final-blur.png")
        val source = android.graphics.Bitmap.createBitmap(160, 90, android.graphics.Bitmap.Config.ARGB_8888)
        for (y in 0 until 90) for (x in 0 until 160)
            source.setPixel(x, y, android.graphics.Color.rgb(if (x < 80) 255 else 30, y * 2, if (x < 80) 30 else 255))
        image.outputStream().use { source.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        source.recycle()
        val url = image.toURI().toString()
        return url
    }

    @Composable private fun PreparedArtwork(url: String, body: @Composable () -> Unit) {
        val context = androidx.compose.ui.platform.LocalContext.current
        var ready by remember { mutableStateOf(false) }
        LaunchedEffect(url) {
            val loader = coil.Coil.imageLoader(context)
            listOf(xlBlurredBackdropRequest(context, url),
                coil.request.ImageRequest.Builder(context).data(url).size(576, 324).build()).forEach { request ->
                val result = loader.execute(request)
                assertTrue("Fixture artwork must decode successfully: $result", result is coil.request.SuccessResult)
            }
            ready = true
        }
        if (ready) body()
    }

    private fun awaitImage(ready: () -> Boolean) {
        // Pump Compose's test main dispatcher while Coil uses its real worker lane.
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeBy(16)
            compose.waitForIdle()
            ready()
        }
    }

    @Test fun aLoadedBlurDoesNotChangePixelsOrReloadAfterOrdinaryMeasurementsSettle() {
        val settled = mutableStateOf(false)
        var callbacks = 0
        val url = colorArtworkUrl()
        content {
            PreparedArtwork(url) {
            Box(Modifier.width(960.dp).height(540.dp).background(Color.Black)) {
                ModernHeroMediaLayer(heroBackdrop = url, heroBackdropIsPosterFallback = !settled.value,
                    heroBackdropAlpha = 1f, shouldPlayHeroTrailer = false, heroTrailerUrl = null,
                    heroTrailerAudioUrl = null, heroTrailerAlpha = 0f, muted = true,
                    onTrailerEnded = {}, onFirstFrameRendered = {},
                    modifier = Modifier.align(androidx.compose.ui.Alignment.Center).requiredSize(1056.dp, 594.dp),
                    requestWidthPx = if (settled.value) 1056 else 576,
                    requestHeightPx = if (settled.value) 594 else 324,
                    cinematicMode = true, cinematicScale = if (settled.value) 1f else 1.1f,
                    blurredBackdrop = true, viewportWidth = 960.dp, viewportHeight = 540.dp,
                    onBackdropFrameReady = { _, _ -> callbacks++ })
            }
            }
        }
        awaitImage { callbacks > 0 }
        compose.waitForIdle()
        val first = pixels()
        assertTrue("Fixture must render the real prepared blur", android.graphics.Color.red(first.getPixel(100, 200)) > 100)
        val before = callbacks
        compose.runOnIdle { settled.value = true }
        compose.waitForIdle()
        val final = pixels()
        assertEquals("Late ordinary measurements must not reload the blurred request", before, callbacks)
        assertTrue("The first blurred image must already have its final crop and scale", first.sameAs(final))
        first.recycle(); final.recycle()
    }

    @Test fun changingFromTheLetterboxedContainerKeepsTheOutgoingFrameInTheCrossfade() {
        val blur = mutableStateOf(false)
        var callbacks = 0
        val url = colorArtworkUrl()
        content {
            PreparedArtwork(url) {
            val transition = rememberModernHeroBackdropTransition(url, 1f, false, blur.value,
                blur.value, 576, 324)
            val media: @Composable (Modifier) -> Unit = { modifier ->
                ModernHeroMediaLayer(url, heroBackdropAlpha = 1f, shouldPlayHeroTrailer = false,
                    heroTrailerUrl = null, heroTrailerAudioUrl = null, heroTrailerAlpha = 0f, muted = true,
                    onTrailerEnded = {}, onFirstFrameRendered = {}, modifier = modifier,
                    requestWidthPx = 576, requestHeightPx = 324, cinematicMode = blur.value,
                    blurredBackdrop = blur.value, backdropCrossfadeDuration = 220,
                    backdropTransition = transition, viewportWidth = 960.dp, viewportHeight = 540.dp,
                    onBackdropFrameReady = { _, _ -> callbacks++ })
            }
            Box(Modifier.width(960.dp).height(540.dp).background(Color.Black)) {
                if (blur.value) {
                    Box(Modifier.align(androidx.compose.ui.Alignment.Center).requiredSize(1056.dp, 594.dp)) {
                        media(Modifier.fillMaxSize())
                    }
                } else {
                    Box(Modifier.align(androidx.compose.ui.Alignment.TopEnd).offset(x = 56.dp).width(576.dp).height(324.dp)) {
                        media(Modifier.fillMaxSize())
                    }
                }
            }
            }
        }
        awaitImage { callbacks > 0 }
        compose.waitForIdle()
        val initial = pixels()
        assertEquals(0, android.graphics.Color.red(initial.getPixel(100, 200)))
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { blur.value = true }
        compose.waitForIdle()
        val beginning = pixels()
        assertTrue("Recreating the container must preserve its old image at the start of the fade", initial.sameAs(beginning))
        repeat(7) { compose.mainClock.advanceTimeBy(16); compose.waitForIdle() }
        val middle = pixels()
        repeat(12) { compose.mainClock.advanceTimeBy(16); compose.waitForIdle() }
        val final = pixels()
        val midRed = android.graphics.Color.red(middle.getPixel(100, 200))
        val finalRed = android.graphics.Color.red(final.getPixel(100, 200))
        assertTrue("The incoming blur must fade through an intermediate brightness", midRed in 1 until finalRed)
        assertTrue(finalRed > 100)
        initial.recycle(); beginning.recycle(); middle.recycle(); final.recycle()
        compose.mainClock.autoAdvance = true
    }

    @Test fun ordinaryGradientFadesWithTheNewBlurAndItsAlphaStaysPinnedForThePlatformGhost() {
        val blur = mutableStateOf(false)
        val ghost = mutableStateOf(false)
        val handoff = XlPlatformBackdropHandoff()
        lateinit var backdrop: XlBackdropPresentation
        content {
            backdrop = remember { XlBackdropPresentation(null, false) }
            Box(Modifier.width(960.dp).height(540.dp).background(Color.White)) {
                ModernHeroGradientLayer(Color.Black, false, 0f, Modifier.fillMaxSize(),
                    cinematicMode = true, blurredBackdrop = blur,
                    visibility = rememberXlPlatformGradientAlpha(backdrop, handoff, ghost.value))
            }
        }
        val dark = brightness()
        compose.runOnIdle { blur.value = true }
        compose.waitForIdle()
        assertEquals("Committing the blur must not snap the outgoing gradient away", dark, brightness())
        compose.runOnIdle { kotlinx.coroutines.runBlocking { backdrop.gradientAlpha.snapTo(0.5f) } }
        compose.waitForIdle()
        assertTrue("The ordinary gradient must have an intermediate opacity", brightness() in 110..150)
        compose.runOnIdle { kotlinx.coroutines.runBlocking { backdrop.gradientAlpha.snapTo(0f) } }
        compose.waitForIdle()
        assertEquals(255, brightness())
        compose.runOnIdle {
            handoff.capture(0.12f, true, true, 0f)
            ghost.value = true
            kotlinx.coroutines.runBlocking { backdrop.gradientAlpha.snapTo(1f) }
        }
        compose.waitForIdle()
        assertEquals("An incoming ordinary page must not add its gradient to the XL ghost", 255, brightness())
        compose.runOnIdle { ghost.value = false }
        compose.waitForIdle()
        assertEquals(dark, brightness())
    }

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
        assertTrue(original in 220..230)
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
        assertTrue(brightness() in 220..230)
    }

    @Test fun onlyTheRankPassingTheFeaturedEdgeFadesAndItsLowerInkNeverSlidesUnderXl() {
        val track = XlRankTrack(163f, FloatArray(12) { 32f }, 53.68f)
        val origin = mutableStateOf(track.origin(7))
        val geometry = XlRowGeometry(244f, 434f, 163f, 3)
        content {
            Box(Modifier.width(960.dp).height(540.dp).background(Color.Black)) {
                Box(Modifier.offset(y = 40.dp)) {
                    XlPortraitRank(8, "test", track, origin, geometry, NumberStyle.SOLID, false)
                    XlPortraitRank(9, "test", track, origin, geometry, NumberStyle.SOLID, false)
                }
            }
        }
        fun x(index: Int) = compose.onNodeWithTag("xl-rank-test-$index").fetchSemanticsNode()
            .layoutInfo.coordinates.positionInRoot().x
        fun lowerInk(index: Int): Int {
            val posterX = x(index).toInt()
            var brightest = 0
            compose.runOnUiThread {
                val view = activity.get().findViewById<android.view.View>(android.R.id.content)
                val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.BLACK)
                view.draw(android.graphics.Canvas(bitmap))
                brightest = ((posterX - 32).coerceAtLeast(0)..(posterX - 2).coerceAtLeast(0)).maxOf { px ->
                    (285..291).maxOf { py -> android.graphics.Color.red(bitmap.getPixel(px, py)) }
                }
                bitmap.recycle()
            }
            return brightest
        }
        fun advance(ms: Int) { repeat((ms + 15) / 16) { compose.mainClock.advanceTimeBy(16); compose.waitForIdle() } }
        assertEquals(136, lowerInk(8)); assertEquals(136, lowerInk(9))
        val farX = x(9)
        compose.mainClock.autoAdvance = false
        var passedThroughPartialAlpha = false
        for (distance in 4..52 step 4) {
            compose.runOnIdle { origin.value = track.origin(7) + distance }
            advance(16)
            if (lowerInk(8) in 1..135) passedThroughPartialAlpha = true
            assertEquals("All other ranks must stay opaque through every fade frame", 136, lowerInk(9))
            assertEquals("The passing number must stay attached to its moving poster",
                track.portraitLeftAtOrigin(8, origin.value, geometry.featuredWidth), x(8), 0.6f)
        }
        assertTrue("The passing glyph must actually fade through intermediate opacity", passedThroughPartialAlpha)
        assertEquals("All other ranks must remain fully opaque", 136, lowerInk(9))
        assertEquals("Unaffected ranks keep sliding with their cards", farX - 52f, x(9), 0.1f)
        advance(64)
        compose.runOnIdle { origin.value = track.origin(7) + 180f }
        advance(16)
        assertEquals(0, lowerInk(8))
        for (px in 80..480 step 20) assertEquals("No lower rank ink may slide below the landscape", 0, brightness(px, 287))
        var emergedThroughPartialAlpha = false
        for (distance in 52 downTo 0 step 4) {
            compose.runOnIdle { origin.value = track.origin(7) + distance }
            advance(16)
            if (lowerInk(8) in 1..135) emergedThroughPartialAlpha = true
        }
        assertTrue("A rank emerging from behind XL should fade in", emergedThroughPartialAlpha)
        assertEquals(136, lowerInk(9))
        advance(80)
        assertEquals(136, lowerInk(8))
        compose.mainClock.autoAdvance = true
    }

    @Test fun ordinaryPosterCutoffFadesIntoTheBackdropBeforeTheUnchangedHeadingAnchor() {
        val scene = XlSceneState()
        val list = LazyListState()
        content {
            Box(Modifier.width(960.dp).height(540.dp).background(Color.Blue)) {
                Box(Modifier.offset(y = 40.dp).width(960.dp).height(200.dp)
                    .xlSceneRowsViewport(scene, list, false, 260.dp).background(Color.White))
            }
        }
        assertTrue(brightness(500, 40) < 20)
        assertTrue("The cutoff must contain partially transparent poster pixels", brightness(500, 50) in 100..160)
        assertEquals("The heading's original resting position stays opaque", 255, brightness(500, 60))
        assertEquals(255, brightness(500, 90))
    }

    @Test fun theMovingMetadataMaskHasASoftEdgeButXlArtworkHasNoSuchMask() {
        val scene = XlSceneState()
        val list = LazyListState()
        content {
            Box(Modifier.width(960.dp).height(540.dp).background(Color.Blue)) {
                Box(Modifier.fillMaxSize().xlSceneRowsViewport(scene, list, true, 260.dp).background(Color.White))
            }
        }
        assertEquals(0, brightness(500, 239))
        assertTrue(brightness(500, 250) in 100..160)
        assertEquals(255, brightness(500, 260))
        compose.runOnIdle {
            scene.focus(XlSceneSelection("XL", "item", 0, true, true, snapshot("XL").preview), list.layoutInfo, animate = false)
        }
        compose.waitForIdle()
        assertEquals("An XL row must keep the whole artwork through the old metadata region", 255, brightness(500, 250))
        assertEquals(255, brightness(500, 0))
    }

    @Test fun scaledPosterBottomsDoNotLeaveSliversInsideTheEmptyGapAboveTheHeading() {
        val scene = XlSceneState()
        val list = LazyListState(1)
        content {
            Box(Modifier.width(960.dp).height(540.dp).background(Color.Blue)) {
                LazyColumn(state = list, contentPadding = PaddingValues(top = 260.dp, bottom = 540.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier.fillMaxSize().xlSceneRowsViewport(scene, list, true, 260.dp)) {
                    items((0..2).toList(), key = { it }) { index ->
                        Column {
                            Spacer(Modifier.height(40.dp))
                            Box(Modifier.padding(start = 52.dp).width(200.dp).height(200.dp)
                                .graphicsLayer { scaleY = if (index == 0) 1.2f else 1f }
                                .background(Color.White).testTag("scaled-poster-$index"))
                        }
                    }
                }
            }
        }
        val oldBottom = compose.onNodeWithTag("scaled-poster-0").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("The scaled poster must extend into the fade band to exercise the regression", oldBottom in 245f..259f)
        val actualRowBottom = list.layoutInfo.visibleItemsInfo.first { it.key == 0 }.let {
            it.offset + list.layoutInfo.beforeContentPadding + it.size
        }
        assertTrue(actualRowBottom < 240)
        val bitmap = pixels()
        for (y in 240..259) for (x in 70..220) {
            assertEquals("The gap must contain only the backdrop after the row clears it", 0,
                android.graphics.Color.red(bitmap.getPixel(x, y)))
        }
        bitmap.recycle()
        assertEquals("The next poster remains opaque at its normal position", 255, brightness(100, 320))
    }

    @Test fun anEmergingRankStartsItsFadeBeforeThePortraitClearsXlAndIsReadyWhenItDoes() {
        val track = XlRankTrack(163f, FloatArray(12) { 32f }, 53.68f)
        val origin = mutableStateOf(track.origin(7) + 100f)
        val geometry = XlRowGeometry(244f, 434f, 163f, 3)
        content {
            Box(Modifier.width(960.dp).height(540.dp).background(Color.Black)) {
                Box(Modifier.offset(y = 40.dp)) {
                    XlPortraitRank(8, "early", track, origin, geometry, NumberStyle.SOLID, false)
                }
            }
        }
        fun ink(): Int {
            val x = compose.onNodeWithTag("xl-rank-early-8").fetchSemanticsNode()
                .layoutInfo.coordinates.positionInRoot().x.toInt()
            val bitmap = pixels()
            val brightest = (x - 32..x - 2).maxOf { px -> (285..291).maxOf { py ->
                android.graphics.Color.red(bitmap.getPixel(px, py))
            } }
            for (px in 80..485) assertEquals("The entire lower overhang must disappear softly behind XL", 0,
                android.graphics.Color.red(bitmap.getPixel(px, 287)))
            bitmap.recycle()
            return brightest
        }
        assertEquals(0, ink())
        compose.mainClock.autoAdvance = false
        var beganBeforeClearing = false
        for (distance in 44 downTo 0 step 4) {
            compose.runOnIdle { origin.value = track.origin(7) + distance }
            compose.mainClock.advanceTimeBy(16); compose.waitForIdle()
            val left = track.numberLeft(8, track.portraitLeftAtOrigin(8, origin.value, 434f))
            if (left < 486f && ink() > 0) beganBeforeClearing = true
        }
        assertTrue("The incoming rank must begin fading before the full glyph clears XL", beganBeforeClearing)
        assertEquals(136, ink())
        val bitmap = pixels()
        for (x in 80..485) assertEquals("No early ink may slide below the landscape", 0,
            android.graphics.Color.red(bitmap.getPixel(x, 287)))
        bitmap.recycle()
        compose.runOnIdle { origin.value = track.origin(7) }
        compose.mainClock.advanceTimeBy(16); compose.waitForIdle()
        assertEquals("The revealed portrait must already have its number", 136, ink())
        compose.mainClock.autoAdvance = true
    }

    @Test fun xlColorFieldsKeepTheirRedWithoutOrdinaryGradientsAndPinThatStyleDuringPlatformExit() {
        val xl = mutableStateOf(true)
        val ghost = mutableStateOf(false)
        val black = mutableStateOf(0f)
        val handoff = XlPlatformBackdropHandoff()
        lateinit var backdrop: XlBackdropPresentation
        content {
            backdrop = remember(xl.value) { XlBackdropPresentation(null, xl.value) }
            val blurred = rememberXlPlatformBackdropBlurred(backdrop, handoff, ghost.value)
            Box(Modifier.width(960.dp).height(540.dp).background(Color.Red)) {
                ModernHeroGradientLayer(Color.Black, false, 0f, Modifier.fillMaxSize(), cinematicMode = true,
                    blurredBackdrop = blurred)
                ModernXlBackdropDimmer(rememberXlPlatformBackdropDim(backdrop, handoff, ghost.value))
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = black.value }.background(Color.Black))
            }
        }
        for (x in listOf(50, 500, 900)) assertTrue("The red field should keep most of its original brightness", brightness(x, 150) in 220..230)
        compose.runOnIdle { handoff.capture(backdrop.dim.value, true, backdrop.blurred); ghost.value = true; xl.value = false }
        compose.waitForIdle()
        assertTrue("The incoming ordinary page must not add black gradients to the captured color field", brightness(50, 150) in 220..230)
        compose.runOnIdle { black.value = 1f; ghost.value = false }
        compose.waitForIdle()
        assertEquals(0, brightness(50, 150))
        compose.runOnIdle { black.value = 0f }
        compose.waitForIdle()
        assertTrue("Ordinary rows retain their original hero gradient", brightness(50, 150) < 10)
    }
}
