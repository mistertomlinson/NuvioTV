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
    private fun press(code: Int) {
        compose.runOnUiThread {
            ShadowSystemClock.advanceBy(Duration.ofMillis(150))
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
        repeat(110) {
            advance(16)
            val bottom = scene.outgoingXlBottom(list.layoutInfo)
            if (bottom != null && bottom > 0f) assertEquals("Metadata waits until all XL pixels clear", 0f, scene.exitMetadataAlpha, 0f)
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
            repeat(38) { frame ->
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
    @Test fun ordinaryPageWithXlDisabledRetainsItsMetadata() = verifyExit(false, true, allRegular = true)
    @Test fun ordinaryNumberedPageWithXlDisabledRetainsItsMetadata() = verifyExit(true, true, allRegular = true)
    @Test fun ordinaryHalfWidthPageRetainsOriginalGeometry() = verifyExit(false, false, allRegular = true)
    @Test fun numberedHalfWidthPageRetainsOriginalGeometry() = verifyExit(true, false, allRegular = true)
    @Test fun landscapePageRetainsOriginalGeometry() = verifyExit(false, true, allRegular = true, globalLandscape = true)
    @Test fun numberedLandscapePageRetainsOriginalGeometry() = verifyExit(true, true, allRegular = true, globalLandscape = true)
}
