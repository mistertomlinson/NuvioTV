package com.nuvio.tv.ui.screens.home

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.tv.material3.MaterialTheme
import com.nuvio.tv.data.local.SeasonalEffectsDataStore
import com.nuvio.tv.ui.screens.settings.SeasonalEffectsViewModel
import com.nuvio.tv.domain.model.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** Actual Home, catalog cards and focus callbacks, rather than substitute row boxes. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModernHomeIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val models = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private val focused = mutableListOf<String>()
    private lateinit var scene: XlSceneState
    private lateinit var list: androidx.compose.foundation.lazy.LazyListState

    @Before fun setup() { compose.runOnUiThread {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val preferences = mockk<SeasonalEffectsDataStore>()
        every { preferences.config } returns MutableStateFlow(SeasonalEffectsConfig(holidayMode = HolidayMode.OFF))
        ViewModelProvider(models, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SeasonalEffectsViewModel(preferences) as T
        })[SeasonalEffectsViewModel::class.java]
    } }
    @After fun cleanup() = compose.runOnUiThread { activity.pause().stop().destroy(); models.viewModelStore.clear() }
    private fun artwork(name: String, color: Int): String {
        val file = java.io.File(org.robolectric.RuntimeEnvironment.getApplication().cacheDir, "home-integration-$name.png")
        Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
            file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        return file.toURI().toString()
    }
    private fun advance(ms: Int) = repeat((ms + 15) / 16) { compose.mainClock.advanceTimeBy(16); compose.waitForIdle() }
    private fun press(code: Int, gapMs: Long = 150) {
        compose.runOnUiThread {
            ShadowSystemClock.advanceBy(Duration.ofMillis(gapMs))
            val time = SystemClock.uptimeMillis()
            activity.get().dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0))
            activity.get().dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, code, 0))
        }
        compose.waitForIdle()
    }
    private fun bitmap(): Bitmap {
        lateinit var image: Bitmap
        compose.runOnUiThread {
            val view = activity.get().findViewById<android.view.View>(android.R.id.content)
            image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
        }
        return image
    }
    private fun verifyExit(numbered: Boolean, fullWidth: Boolean, summary: Boolean = false,
        landscape: Boolean = false, middle: Boolean = false, returnUp: Boolean = false, allRegular: Boolean = false, globalLandscape: Boolean = false) {
        val xlIndex = if (middle) 1 else 0
        val nextIndex = xlIndex + 1
        val black = artwork("black", android.graphics.Color.BLACK)
        val white = artwork("logo", android.graphics.Color.WHITE)
        val rows = (0..3).map { row ->
            CatalogRow("fixture", "Fixture", "https://fixture.invalid", "$row", "Row $row", ContentType.MOVIE,
                items = (0..3).map { item -> MetaPreview("$row-$item", ContentType.MOVIE, name = "Title $row $item",
                    poster = black, posterShape = PosterShape.POSTER, background = black, logo = white,
                    description = "Synopsis for row $row and item $item", releaseInfo = "2026", imdbRating = null,
                    genres = listOf("Drama"), landscapePoster = black) }, hasMore = false)
        }
        val keys = rows.map(::catalogRowKey).toSet()
        val state = HomeUiState(catalogRows = rows, isLoading = false, catalogsReady = true, skeletonReady = true,
            layoutPreferencesReady = true, enrichmentReadyRowKeys = keys,
            xlCatalogKeys = if (allRegular) emptySet() else setOf(catalogRowKey(rows[xlIndex])),
            xlNoSummaryCatalogKeys = if (summary || allRegular) emptySet() else setOf(catalogRowKey(rows[xlIndex])),
            landscapeCatalogKeys = if (landscape) setOf(catalogRowKey(rows[nextIndex])) else emptySet(),
            posterLabelsEnabled = false, modernLandscapePostersEnabled = globalLandscape, fullWidthIconRowEnabled = fullWidth,
            aggregateStreamingPlatformsEnabled = fullWidth, showAllCatalogsOnHome = fullWidth)
        compose.runOnUiThread {
            activity.get().setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides models,
                        LocalHomeSceneInspection provides { observed, observedList -> scene = observed; list = observedList }) {
                        Box(Modifier.width(960.dp).height(540.dp).background(Color.Black)) {
                            ModernHomeContent(uiState = state, aggregatePlatformsEnabled = fullWidth, showAllCatalogsOnHome = fullWidth,
                                fullWidthIconRowEnabled = fullWidth, focusState = HomeScreenFocusState(),
                                trailerPreviewUrls = emptyMap(), trailerPreviewAudioUrls = emptyMap(),
                                numberedCatalogKeys = if (numbered) keys else emptySet(),
                                onNavigateToDetail = { _, _, _ -> }, onContinueWatchingClick = {},
                                onRequestTrailerPreview = { _, _, _, _ -> }, onLoadMoreCatalog = { _, _, _ -> },
                                onRemoveContinueWatching = { _, _, _, _ -> }, onItemFocus = { focused += it.id },
                                onSaveFocusState = { _, _, _, _, _, _, _ -> })
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        advance(800)
        if (middle) { press(KeyEvent.KEYCODE_DPAD_DOWN); advance(800) }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        var previousEdge = scene.outgoingXlBottom(list.layoutInfo)
        var slideStarted = false
        repeat(110) {
            advance(16)
            val bottom = scene.outgoingXlBottom(list.layoutInfo)
            if (bottom != null && previousEdge != null) {
                if (bottom < previousEdge!!) slideStarted = true
                if (slideStarted && bottom > 40f)
                    assertTrue("Actual Home must not pause its visible XL slide at focus handoff", bottom < previousEdge!!)
            }
            previousEdge = bottom
            if (!summary && bottom != null && bottom > 0f) assertEquals("Compact metadata waits until all XL pixels clear", 0f, scene.exitMetadataAlpha, 0f)
            if (summary) assertEquals("Summary metadata stays opaque while sliding", 1f, scene.exitMetadataAlpha, 0f)
        }
        assertEquals("$nextIndex-0", focused.lastOrNull())
        val nodes = compose.onAllNodesWithTag("ordinary-metadata-${catalogRowKey(rows[nextIndex])}", useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("One ordinary metadata block", 1, nodes.size)
        val bounds = nodes.single().boundsInRoot
        val image = bitmap()
        var bright = 0
        for (y in bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(image.height))
            for (x in bounds.left.toInt().coerceAtLeast(0) until bounds.right.toInt().coerceAtMost(image.width)) {
                val pixel = image.getPixel(x, y)
                if (android.graphics.Color.red(pixel) > 180 && android.graphics.Color.green(pixel) > 180 && android.graphics.Color.blue(pixel) > 180) bright++
            }
        image.recycle()
        assertTrue("Actual metadata must render, numbered=$numbered summary=$summary pixels=$bright", bright > 200)
        if (allRegular) {
            assertEquals("Ordinary rows reserve no added fade padding", 0, list.layoutInfo.beforeContentPadding)
            assertEquals("The viewport has its original screen fraction", if (globalLandscape) 265 else 281, list.layoutInfo.viewportSize.height)
            assertNull("Ordinary row focus cannot start XL scene motion", scene.transition)
            assertTrue("XL-disabled pages mount no XL artwork renderer or its preload jobs", scene.artwork.renderers.isEmpty())
        }
        if (returnUp) {
            press(KeyEvent.KEYCODE_DPAD_UP)
            val positions = mutableListOf<String>()
            val tops = mutableListOf<Float?>()
            repeat(90) { frame ->
                advance(16)
                val info = list.layoutInfo
                val key = catalogRowKey(rows[nextIndex])
                val owner = info.visibleItemsInfo.firstOrNull { it.key == key }
                val gap = scene.headroomValues[key]?.value ?: 0f
                val top = owner?.let { it.offset + info.beforeContentPadding + gap }
                tops += top
                positions += "${frame * 16}:$top:$gap:${scene.transition?.outgoingMetadataCleared}:${list.isScrollInProgress}"
            }
            println("REAL_HOME returnUp numbered=$numbered positions=${positions.joinToString(",")}")
            assertEquals("$xlIndex-0", focused.last())
            val peak = tops.indices.maxBy { tops[it] ?: 0f }
            val finalTop = checkNotNull(tops.last())
            val firstVisible = (peak until tops.size).first { (tops[it] ?: 1000f) < 540f }
            val rested = (firstVisible until tops.size).first { kotlin.math.abs((tops[it] ?: 1000f) - finalTop) < 1f }
            assertTrue("Visible preview travel must not be consumed below the screen: ${(rested - firstVisible) * 16}ms",
                (rested - firstVisible) * 16 >= 240)
        }
    }
    @Test fun plainCompactExit() = verifyExit(false, false)
    @Test fun numberedCompactExit() = verifyExit(true, false)
    @Test fun plainFullWidthCompactExit() = verifyExit(false, true)
    @Test fun numberedFullWidthCompactExit() = verifyExit(true, true)
    @Test fun plainSummaryFullWidthExit() = verifyExit(false, true, summary = true)
    @Test fun numberedSummaryFullWidthExit() = verifyExit(true, true, summary = true)
    @Test fun plainSummaryLandscapeExit() = verifyExit(false, true, summary = true, landscape = true)
    @Test fun numberedSummaryLandscapeExit() = verifyExit(true, true, summary = true, landscape = true)
    @Test fun plainMiddleSummaryExit() = verifyExit(false, true, summary = true, middle = true)
    @Test fun numberedMiddleSummaryExit() = verifyExit(true, true, summary = true, middle = true)
    @Test fun plainCompactReturnUp() = verifyExit(false, true, returnUp = true)
    @Test fun numberedCompactReturnUp() = verifyExit(true, true, returnUp = true)
    @Test fun plainLandscapeCompactReturnUp() = verifyExit(false, true, returnUp = true, landscape = true)
    @Test fun numberedLandscapeCompactReturnUp() = verifyExit(true, true, returnUp = true, landscape = true)
    @Test fun ordinaryPageWithXlDisabledRetainsItsMetadata() = verifyExit(false, true, allRegular = true)
    @Test fun ordinaryNumberedPageWithXlDisabledRetainsItsMetadata() = verifyExit(true, true, allRegular = true)
    @Test fun ordinaryHalfWidthPageRetainsOriginalGeometry() = verifyExit(false, false, allRegular = true)
    @Test fun numberedHalfWidthPageRetainsOriginalGeometry() = verifyExit(true, false, allRegular = true)
    @Test fun landscapePageRetainsOriginalGeometry() = verifyExit(false, true, allRegular = true, globalLandscape = true)
    @Test fun numberedLandscapePageRetainsOriginalGeometry() = verifyExit(true, true, allRegular = true, globalLandscape = true)

    private fun mountScenario(state: HomeUiState, numbered: Set<String> = emptySet()) {
        compose.runOnUiThread {
            activity.get().setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides models,
                        LocalHomeSceneInspection provides { observed, observedList -> scene = observed; list = observedList }) {
                        Box(Modifier.width(960.dp).height(540.dp).background(Color.Black)) {
                            ModernHomeContent(uiState = state, aggregatePlatformsEnabled = state.aggregateStreamingPlatformsEnabled,
                                showAllCatalogsOnHome = state.showAllCatalogsOnHome, fullWidthIconRowEnabled = state.fullWidthIconRowEnabled,
                                focusState = HomeScreenFocusState(), trailerPreviewUrls = emptyMap(), trailerPreviewAudioUrls = emptyMap(),
                                numberedCatalogKeys = numbered,
                                onNavigateToDetail = { _, _, _ -> }, onContinueWatchingClick = {}, onRequestTrailerPreview = { _, _, _, _ -> },
                                onLoadMoreCatalog = { _, _, _ -> }, onRemoveContinueWatching = { _, _, _, _ -> },
                                onItemFocus = { focused += it.id }, onSaveFocusState = { _, _, _, _, _, _, _ -> })
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        advance(960)
    }

    private fun scenarioRows(count: Int, redFirst: Boolean = false): List<CatalogRow> {
        val black = artwork("motion-black", android.graphics.Color.BLACK)
        val red = artwork("motion-red", android.graphics.Color.RED)
        val white = artwork("motion-logo", android.graphics.Color.WHITE)
        return (0 until count).map { index ->
            val image = if (index == 0 && redFirst) red else black
            CatalogRow("motion", "Motion", "https://fixture.invalid", "$index", "Row $index", ContentType.MOVIE,
                items = listOf(MetaPreview("motion-$index", ContentType.MOVIE, name = "Title $index", poster = image,
                    posterShape = PosterShape.POSTER, background = image, logo = white, description = "Synopsis $index",
                    releaseInfo = "2026", imdbRating = null, genres = listOf("Drama"), landscapePoster = image)), hasMore = false)
        }
    }

    private fun verifyContinueWatching(style: ContinueWatchingCardStyle, summary: Boolean, fullWidth: Boolean) {
        val rows = scenarioRows(3)
        val xlKey = catalogRowKey(rows.first())
        val image = artwork("cw-motion-image", android.graphics.Color.BLUE)
        val logo = artwork("cw-motion-logo", android.graphics.Color.WHITE)
        val progress = WatchProgress("cw-motion", "movie", "Continue Watching Fixture", image, image, logo,
            "cw-motion", null, null, null, 20_000, 100_000, 10_000)
        mountScenario(HomeUiState(catalogRows = rows, continueWatchingItems = listOf(ContinueWatchingItem.InProgress(progress)),
            continueWatchingCardStyle = style, continueWatchingEnrichmentReady = true, isLoading = false,
            catalogsReady = true, skeletonReady = true, layoutPreferencesReady = true,
            enrichmentReadyRowKeys = rows.map(::catalogRowKey).toSet(), xlCatalogKeys = setOf(xlKey),
            xlNoSummaryCatalogKeys = if (summary) emptySet() else setOf(xlKey),
            fullWidthIconRowEnabled = fullWidth, aggregateStreamingPlatformsEnabled = fullWidth,
            showAllCatalogsOnHome = fullWidth, posterLabelsEnabled = false))
        assertEquals("continue_watching", scene.selection?.rowKey)
        assertEquals("CW keeps its ordinary following margin", 0.dp, scene.headroomValues[xlKey])
        val cw = list.layoutInfo.visibleItemsInfo.first { it.key == "continue_watching" }
        val cwHeight = checkNotNull(scene.rowHeights["continue_watching"])
        println("CW_GEOMETRY style=$style summary=$summary height=$cwHeight prefix=${scene.headroomValues[xlKey]} size=${cw.size}")
        assertEquals("Measure the actual CW style, including its footer", cw.size.toFloat(), cwHeight.value, 1f)
        press(KeyEvent.KEYCODE_DPAD_DOWN); advance(960)
        assertEquals(xlKey, scene.selection?.rowKey)
        val previous = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "continue_watching" }
        assertTrue("CW is fully above the screen on XL", previous == null || previous.offset + list.layoutInfo.beforeContentPadding + previous.size <= 0)
        assertTrue("CW metadata is already measured before Up", (scene.metadataHeights["continue_watching"] ?: 0f) > 0f)
        press(KeyEvent.KEYCODE_DPAD_UP)
        val resting = mutableListOf<Float>()
        val samples = mutableListOf<String>()
        repeat(64) { frame ->
            advance(16)
            val meta = compose.onAllNodesWithTag("ordinary-metadata-continue_watching", true).fetchSemanticsNodes().singleOrNull()
            val top = meta?.boundsInRoot?.top
            samples += "${frame * 16}:$top:${list.isScrollInProgress}:${scene.verticalMotion != null}:${scene.headroomValues[xlKey]}"
            if (!list.isScrollInProgress && scene.verticalMotion == null && top != null) resting += top
        }
        println("CW_RETURN style=$style summary=$summary positions=${samples.joinToString(",")}")
        assertEquals("continue_watching", scene.selection?.rowKey)
        assertEquals("No portrait-sized gap returns beneath landscape CW", 0.dp, scene.headroomValues[xlKey])
        assertTrue(resting.size > 10)
        assertEquals("The metadata cannot correct its position after landing", resting.last(), resting.first(), 1f)
    }

    @Test fun landscapeCwAndCompactXlShareAStableGapAndMetadataAnchor() = verifyContinueWatching(ContinueWatchingCardStyle.CARD, false, false)
    @Test fun landscapeCwAndSummaryXlShareAStableGapAndMetadataAnchor() = verifyContinueWatching(ContinueWatchingCardStyle.CARD, true, true)
    @Test fun wideCwAndCompactXlUseTheirMeasuredGeometry() = verifyContinueWatching(ContinueWatchingCardStyle.WIDE, false, true)
    @Test fun portraitCwAndSummaryXlKeepTheirMeasuredGeometry() = verifyContinueWatching(ContinueWatchingCardStyle.POSTER, true, false)

    @Test fun doubleUpToAnXlFirstRowNeverLeavesTheOldMetadataOverItsUpperHalf() {
        val rows = scenarioRows(12, redFirst = true)
        val key = catalogRowKey(rows.first())
        mountScenario(HomeUiState(catalogRows = rows, isLoading = false, catalogsReady = true, skeletonReady = true,
            layoutPreferencesReady = true, enrichmentReadyRowKeys = rows.map(::catalogRowKey).toSet(),
            xlCatalogKeys = setOf(key), xlNoSummaryCatalogKeys = setOf(key), fullWidthIconRowEnabled = true,
            aggregateStreamingPlatformsEnabled = true, showAllCatalogsOnHome = true,
            doubleUpPlatformShortcutEnabled = true, posterLabelsEnabled = false))
        repeat(9) { press(KeyEvent.KEYCODE_DPAD_DOWN); advance(800) }
        assertEquals(catalogRowKey(rows[9]), scene.selection?.rowKey)
        press(KeyEvent.KEYCODE_DPAD_UP, 25)
        press(KeyEvent.KEYCODE_DPAD_UP, 25)
        var sawBulk = false
        var checkedUpperCard = false
        repeat(160) { frame ->
            advance(16)
            if (scene.fastReturn != null) sawBulk = true
            val node = compose.onAllNodesWithTag("xl-artwork-$key-0", true).fetchSemanticsNodes().singleOrNull()
            val bounds = node?.boundsInRoot
            if (bounds != null && bounds.top >= 0 && bounds.top < 240 && bounds.width > 100 && bounds.height > 80) {
                val image = bitmap()
                val x = (bounds.left + bounds.width / 2).toInt().coerceIn(0, image.width - 1)
                val y = (bounds.top + 20).toInt().coerceIn(0, image.height - 1)
                val pixel = image.getPixel(x, y)
                val red = android.graphics.Color.red(pixel)
                val green = android.graphics.Color.green(pixel)
                if (red <= 150 || green >= 70) {
                    val file = java.io.File("/workspace/scratch/51fe5365d02b/xl-double-up-failure.png")
                    file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    println("DOUBLE_UP_FRAME frame=$frame bounds=$bounds pixel=$red,$green fast=${scene.fastReturn} selection=${scene.selection} transition=${scene.transition} rows=${list.layoutInfo.visibleItemsInfo.map { it.key to it.offset }} mask=${xlSceneMetadataMask(scene, list.layoutInfo, true, scene.ordinaryTop.value, 1f, 540f)}")
                }
                assertTrue("The upper XL artwork must not be clipped by a stale metadata band: red=$red green=$green frame=$frame", red > 150 && green < 70)
                image.recycle(); checkedUpperCard = true
            }
        }
        assertTrue("Exercise the actual Double-Up shortcut", sawBulk)
        assertTrue("Inspect the top half as XL arrives", checkedUpperCard)
        assertEquals(key, scene.selection?.rowKey)
        assertNull(scene.fastReturn)
        assertNull(scene.transition)
        assertFalse("Double-Up cannot retain the expanded gap from the previous XL exit", scene.expandedXlRows[key] == true)
        val following = list.layoutInfo.visibleItemsInfo.first { it.key == catalogRowKey(rows[1]) }
        val followingTitleTop = following.offset + list.layoutInfo.beforeContentPadding + (scene.headroomValues[following.key]?.value ?: 0f)
        assertTrue("The compact row below must already be visible at landing", followingTitleTop < 540f)
        assertTrue(compose.onAllNodes(hasTestTag("ordinary-metadata-$key"), true).fetchSemanticsNodes().isEmpty())
    }
    private fun verifyCompactLandingAtFractionalDensity(numbered: Boolean) {
        val rows = scenarioRows(3)
        val xlKey = catalogRowKey(rows[0])
        val incomingKey = catalogRowKey(rows[1])
        mountScenario(HomeUiState(catalogRows = rows, isLoading = false, catalogsReady = true,
            skeletonReady = true, layoutPreferencesReady = true,
            enrichmentReadyRowKeys = rows.map(::catalogRowKey).toSet(), xlCatalogKeys = setOf(xlKey),
            xlNoSummaryCatalogKeys = setOf(xlKey),
            fullWidthIconRowEnabled = true, aggregateStreamingPlatformsEnabled = true,
            showAllCatalogsOnHome = true, posterLabelsEnabled = false),
            numbered = if (numbered) rows.map(::catalogRowKey).toSet() else emptySet())
        assertEquals(1.5f, scene.density, 0f)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        val restingTops = mutableListOf<Float>()
        repeat(64) {
            advance(16)
            val node = compose.onAllNodesWithTag("xl-headroom-$incomingKey", true).fetchSemanticsNodes().singleOrNull()
            if (node != null && scene.verticalMotion == null && !list.isScrollInProgress) {
                val top = node.layoutInfo.coordinates.positionInRoot().y + node.layoutInfo.height + scene.headerPx(incomingKey)
                restingTops += top
                assertEquals("The first resting frame already matches the final poster anchor",
                    scene.posterAnchorPx(checkNotNull(scene.selection)).toFloat(), top, 0f)
            }
        }
        assertEquals(incomingKey, scene.selection?.rowKey)
        assertTrue("Follow the real cards through metadata reveal and focus relocation", restingTops.size > 20)
        assertEquals("The incoming row cannot correct by a pixel after landing", restingTops.first(), restingTops.last(), 0f)
        assertEquals(1f, scene.exitMetadataAlpha, 0f)
    }

    @Test @Config(qualifiers = "w960dp-h540dp-land-hdpi")
    fun plainCompactLandingHasNoPhysicalPixelCorrection() = verifyCompactLandingAtFractionalDensity(false)

    @Test @Config(qualifiers = "w960dp-h540dp-land-hdpi")
    fun numberedCompactLandingHasNoPhysicalPixelCorrection() = verifyCompactLandingAtFractionalDensity(true)

}
