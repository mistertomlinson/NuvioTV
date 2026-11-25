package com.nuvio.tv.ui.screens.home

import android.app.Application
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.nuvio.tv.data.local.SeasonalEffectsDataStore
import com.nuvio.tv.domain.model.SeasonalEffectsConfig
import com.nuvio.tv.domain.model.HolidayMode
import com.nuvio.tv.ui.screens.settings.SeasonalEffectsViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.LocalSidebarOpenRequest
import com.nuvio.tv.ui.util.LocalFastHorizontalNavigationEnabled
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModernXlRowFocusTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val caches = ModernHomeUiCaches()
    private val models = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private val platform = FocusRequester()
    private val below = FocusRequester()
    private var platformHasFocus = false
    private var sidebarOpens = 0
    private var detailsId = ""
    private val events = mutableListOf<String>()
    private val selected = mutableIntStateOf(-1)
    private val selectedId = mutableStateOf("")
    private val rowState = mutableStateOf(row())
    private val artwork = XlArtworkPresentation()

    @Before fun setup() {
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            val preferences = mockk<SeasonalEffectsDataStore>()
            every { preferences.config } returns MutableStateFlow(SeasonalEffectsConfig(holidayMode = HolidayMode.OFF))
            ViewModelProvider(models, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = SeasonalEffectsViewModel(preferences) as T
            })[SeasonalEffectsViewModel::class.java]
        }
    }
    @After fun teardown() {
        compose.runOnUiThread { activity.pause().stop().destroy(); models.viewModelStore.clear() }
    }

    private fun content(fast: Boolean = false, doubleLeft: Boolean = false, wideCanvas: Boolean = false) {
        compose.runOnUiThread {
            activity.get().setContent {
                MaterialTheme {
                    CompositionLocalProvider(
                        LocalViewModelStoreOwner provides models,
                        LocalSidebarOpenRequest provides { sidebarOpens++ },
                        LocalFastHorizontalNavigationEnabled provides fast
                    ) {
                        Column(Modifier.width(960.dp).height(540.dp).layout { measurable, constraints ->
                            val child = measurable.measure(if (wideCanvas) constraints.copy(maxWidth = constraints.maxWidth + 144) else constraints)
                            layout(constraints.maxWidth, child.height) { child.place(0, 0) }
                        }.onPreviewKeyEvent {
                            events += "key=${it.nativeKeyEvent.keyCode}, repeat=${it.nativeKeyEvent.repeatCount}, time=${SystemClock.uptimeMillis()}, selected=${selected.intValue}"
                            false
                        }) {
                            Button(onClick = {}, modifier = Modifier.focusRequester(platform)
                                .onFocusChanged { platformHasFocus = it.isFocused }.testTag("platform")) {
                                Text("Platform ${selected.intValue}")
                            }
                            ModernXlRowSection(
                                artworkPresentation = artwork,
                                row = rowState.value, slotGeneration = 0, uiCaches = caches,
                                pendingRowFocus = PendingRowFocusHolder(), savedRowIndices = emptyMap(),
                                onPendingRowFocusCleared = {},
                                onRowItemFocused = { _, index, _, _ -> selected.intValue = index; events += "selected=$index" },
                                rowTitleBottom = 14.dp, heroMetadataLarge = false, portraitMetadata = true,
                                cornerRadius = 12.dp, autoplayEnabled = false, muted = true,
                                expandedFocusKey = null, trailerUrl = null, trailerAudioUrl = null,
                                heavyOverlayAlpha = mutableStateOf(1f), cardDepthAlpha = mutableStateOf(1f),
                                hideNewSeasonBadge = false, onItemFocus = {}, onPreloadAdjacentItem = {},
                                onCatalogSelectionFocused = { selectedId.value = it.payload.itemId },
                                onNavigateToDetail = { id, _, _ -> detailsId = id }, onLoadMoreCatalog = { _, _, _ -> },
                                onCatalogItemLongPress = { _, _ -> }, onBackdropInteraction = {},
                                onExpandedCatalogFocusKeyChange = {}, useThemeColorForNumbers = false,
                                doubleLeftRowShortcutEnabled = doubleLeft, onUpPressed = { platform.requestFocus() }
                            )
                            Button(onClick = {}, modifier = Modifier.focusRequester(below).testTag("below")) { Text("Next row") }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { caches.requesterFor("test-xl", "item-0").requestFocus() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, selected.intValue); assertFalse(platformHasFocus) }
    }

    private fun down(keyCode: Int, repeat: Int = 0, gapMs: Long = 90) {
        compose.runOnUiThread {
            ShadowSystemClock.advanceBy(Duration.ofMillis(gapMs))
            val now = SystemClock.uptimeMillis()
            activity.get().dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, repeat))
        }
        compose.waitForIdle()
        if (!compose.mainClock.autoAdvance) compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
    }
    private fun up(keyCode: Int) {
        compose.runOnUiThread {
            val now = SystemClock.uptimeMillis()
            activity.get().dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        }
        compose.waitForIdle()
    }
    private fun tap(keyCode: Int) { down(keyCode); up(keyCode) }
    private fun assertXlFocus(index: Int) {
        compose.runOnIdle { assertEquals(events.joinToString("\n"), index, selected.intValue); assertEquals("id-$index", selectedId.value); assertFalse(platformHasFocus) }
        compose.onNodeWithTag("xl-featured-test-xl").assert(hasAnyDescendant(isFocused()))
    }

    @Test fun rightAndLeftKeepTheSameFocusNodeAndOpenTheSelectedTitle() {
        rowState.value = row().copy(numberStyle = NumberStyle.SOLID)
        content()
        val firstNode = compose.onNode(isFocused()).fetchSemanticsNode().id
        repeat(5) { i -> tap(KeyEvent.KEYCODE_DPAD_RIGHT); assertXlFocus(i + 1) }
        repeat(5) { i -> tap(KeyEvent.KEYCODE_DPAD_LEFT); assertXlFocus(4 - i) }
        assertEquals(firstNode, compose.onNode(isFocused()).fetchSemanticsNode().id)
        tap(KeyEvent.KEYCODE_DPAD_RIGHT)
        tap(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertEquals("id-1", detailsId); assertEquals(0, sidebarOpens) }
    }

    @Test fun heldHorizontalKeysStayInXlDuringPortraitAnimation() {
        rowState.value = row().copy(numberStyle = NumberStyle.OUTLINE)
        content(fast = true)
        compose.mainClock.autoAdvance = false
        down(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertXlFocus(1)
        repeat(5) { down(KeyEvent.KEYCODE_DPAD_RIGHT, it + 1); assertXlFocus(it + 2) }
        up(KeyEvent.KEYCODE_DPAD_RIGHT)
        down(KeyEvent.KEYCODE_DPAD_LEFT)
        repeat(5) { down(KeyEvent.KEYCODE_DPAD_LEFT, it + 1); assertXlFocus(4 - it) }
        // Holding Left at item zero does not open the sidebar.
        down(KeyEvent.KEYCODE_DPAD_LEFT, 6)
        assertXlFocus(0)
        up(KeyEvent.KEYCODE_DPAD_LEFT)
        compose.runOnIdle { assertEquals(0, sidebarOpens) }
        compose.mainClock.autoAdvance = true
    }

    @Test fun upCanStillExitAndTheRememberedTitleRegainsFocus() {
        content()
        repeat(3) { tap(KeyEvent.KEYCODE_DPAD_RIGHT) }
        tap(KeyEvent.KEYCODE_DPAD_UP)
        compose.runOnIdle { assertTrue(platformHasFocus) }
        tap(KeyEvent.KEYCODE_DPAD_DOWN)
        assertXlFocus(3)
    }

    @Test fun doubleLeftRequiresTheOriginalRapidTiming() {
        content(doubleLeft = true)
        val focusNode = compose.onNode(isFocused()).fetchSemanticsNode().id
        repeat(8) { tap(KeyEvent.KEYCODE_DPAD_RIGHT) }
        compose.mainClock.autoAdvance = false
        down(KeyEvent.KEYCODE_DPAD_LEFT); up(KeyEvent.KEYCODE_DPAD_LEFT)
        assertXlFocus(7)
        down(KeyEvent.KEYCODE_DPAD_LEFT, gapMs = 120); up(KeyEvent.KEYCODE_DPAD_LEFT)
        assertXlFocus(6)
        down(KeyEvent.KEYCODE_DPAD_LEFT, gapMs = 20); up(KeyEvent.KEYCODE_DPAD_LEFT)
        assertXlFocus(6)
        val portraits = compose.onAllNodes(hasTestTag("xl-portrait-test-xl-6"), useUnmergedTree = true)
        portraits.assertCountEquals(1)
        val before = portraits.fetchSemanticsNodes().single().boundsInRoot.left
        compose.mainClock.advanceTimeBy(32); compose.waitForIdle()
        val after = portraits.fetchSemanticsNodes().single().boundsInRoot.left
        assertTrue("The portrait runway must scroll, before=$before after=$after", after > before)
        assertXlFocus(6)
        val firstArtwork = artworkIndex()
        assertTrue("Landscape artwork should shuffle during the jump", firstArtwork < 6)
        compose.mainClock.advanceTimeBy(32); compose.waitForIdle()
        assertTrue(artworkIndex() < firstArtwork)
        compose.mainClock.advanceTimeBy(400); compose.waitForIdle()
        assertXlFocus(0)
        assertEquals(focusNode, compose.onNode(isFocused()).fetchSemanticsNode().id)
        compose.mainClock.autoAdvance = true
    }

    @Test fun numberedFeaturedPosterKeepsTheOrdinaryHeadingAndMetadataAnchor() {
        rowState.value = row().copy(numberStyle = NumberStyle.OUTLINE)
        content()
        val poster = compose.onNodeWithTag("xl-featured-test-xl").fetchSemanticsNode().boundsInRoot.left
        assertEquals(52f, poster, 0.01f)
        assertEquals(poster, compose.onNodeWithText("Movie").fetchSemanticsNode().boundsInRoot.left, 0.01f)
        repeat(9) { tap(KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertEquals(poster, compose.onNodeWithTag("xl-featured-test-xl").fetchSemanticsNode().boundsInRoot.left, 0.01f)
    }

    @Test fun evenAThrottledHeldRepeatCancelsTheDoubleLeftGesture() {
        content(doubleLeft = true)
        repeat(8) { tap(KeyEvent.KEYCODE_DPAD_RIGHT) }
        compose.mainClock.autoAdvance = false
        down(KeyEvent.KEYCODE_DPAD_LEFT)
        assertXlFocus(7)
        down(KeyEvent.KEYCODE_DPAD_LEFT, repeat = 1, gapMs = 0)
        assertXlFocus(7)
        up(KeyEvent.KEYCODE_DPAD_LEFT)
        down(KeyEvent.KEYCODE_DPAD_LEFT, gapMs = 20); up(KeyEvent.KEYCODE_DPAD_LEFT)
        assertXlFocus(6)
        compose.mainClock.autoAdvance = true
    }

    private fun artworkIndex(): Int {
        val matcher = SemanticsMatcher("featured artwork") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("xl-artwork-test-xl-") == true
        }
        return compose.onNode(matcher, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsProperties.TestTag].substringAfterLast('-').toInt()
    }

    @Test fun selectDuringJumpOpensTheLandscapeTitleCurrentlyShown() {
        content(doubleLeft = true)
        repeat(8) { tap(KeyEvent.KEYCODE_DPAD_RIGHT) }
        compose.mainClock.autoAdvance = false
        down(KeyEvent.KEYCODE_DPAD_LEFT); up(KeyEvent.KEYCODE_DPAD_LEFT)
        down(KeyEvent.KEYCODE_DPAD_LEFT, gapMs = 20); up(KeyEvent.KEYCODE_DPAD_LEFT)
        val shown = artworkIndex()
        assertTrue("The jump should already be shuffling artwork, shown=$shown", shown < 7)
        tap(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertEquals("id-$shown", detailsId) }
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        assertXlFocus(shown)
        compose.mainClock.autoAdvance = true
    }

    @Test fun comingSoonBadgeMirrorsTheMetadataInsetOnThePhysicalViewport() {
        rowState.value = row().let { r -> r.copy(items = r.items.mapIndexed { i, item ->
            item.copy(heroPreview = item.heroPreview.copy(comingSoonText = if (i == 0) "NOVEMBER 30" else "DECEMBER 1"))
        }) }
        content(wideCanvas = true)
        val badge = compose.onNodeWithTag("xl-release-badge-test-xl").fetchSemanticsNode().boundsInRoot
        val meta = compose.onNodeWithTag("xl-primary-metadata-test-xl").fetchSemanticsNode().boundsInRoot
        val poster = compose.onNodeWithTag("xl-featured-test-xl").fetchSemanticsNode().boundsInRoot
        assertEquals(52f, meta.left, 0.5f)
        assertEquals(meta.left, 960f - badge.right, 0.5f)
        assertEquals(meta.center.y, badge.center.y, 0.6f)
        assertTrue(badge.top > poster.bottom)
        compose.mainClock.autoAdvance = false
        tap(KeyEvent.KEYCODE_DPAD_UP)
        compose.mainClock.advanceTimeBy(200); compose.waitForIdle()
        compose.onNodeWithTag("xl-release-badge-test-xl").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
        tap(KeyEvent.KEYCODE_DPAD_DOWN)
        tap(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText("DECEMBER 1").assertExists()
        assertXlFocus(1)
    }

    @Test fun summaryFreeBadgeAddsNoHeightToTheNextRowPeek() {
        rowState.value = row().copy(showXlSummary = false)
        content()
        fun nextTop() = compose.onNodeWithTag("below").fetchSemanticsNode().boundsInRoot.top
        val oldTop = nextTop()
        compose.runOnIdle {
            rowState.value = rowState.value.let { r -> r.copy(items = r.items.map { item ->
                item.copy(heroPreview = item.heroPreview.copy(comingSoonText = "COMING 2027"))
            }) }
        }
        compose.waitForIdle()
        assertEquals(oldTop, nextTop(), 0.1f)
        compose.onNodeWithTag("xl-release-badge-test-xl").assertExists()
        compose.onNodeWithText("Next row").assertIsDisplayed()
    }

    @Test fun unaffectedXlNumbersSlideWithTheirPortraitsDuringNavigation() {
        rowState.value = row().copy(numberStyle = NumberStyle.SOLID)
        content()
        repeat(7) { tap(KeyEvent.KEYCODE_DPAD_RIGHT) }
        fun rankX() = compose.onNodeWithTag("xl-rank-test-xl-9").fetchSemanticsNode()
            .layoutInfo.coordinates.positionInRoot().x
        val oldX = rankX()
        compose.mainClock.autoAdvance = false
        down(KeyEvent.KEYCODE_DPAD_RIGHT); up(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("An unaffected rank must slide immediately rather than freeze and disappear", rankX() < oldX)
        val posterX = compose.onNodeWithTag("xl-portrait-test-xl-9").fetchSemanticsNode()
            .layoutInfo.coordinates.positionInRoot().x
        assertEquals("The number and portrait share exactly the same runway", posterX, rankX(), 0.1f)
        compose.mainClock.advanceTimeBy(400); compose.waitForIdle()
        assertXlFocus(8)
        compose.mainClock.autoAdvance = true
    }

    @Test fun doubleLeftFinishesWithTheOriginalFirstGlyphAndMatchingPortraitRanksInBothStyles() {
        fun gutterPixels(): IntArray {
            val node = compose.onNodeWithTag("xl-featured-test-xl").fetchSemanticsNode()
            val top = (node.layoutInfo.coordinates.positionInRoot().y + node.layoutInfo.coordinates.size.height - 70).toInt()
            val result = IntArray(49 * 80)
            compose.runOnUiThread {
                val view = activity.get().findViewById<android.view.View>(android.R.id.content)
                val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.BLACK)
                view.draw(android.graphics.Canvas(bitmap))
                bitmap.getPixels(result, 0, 49, 0, top, 49, 80)
                bitmap.recycle()
            }
            return result
        }
        rowState.value = row().copy(numberStyle = NumberStyle.SOLID)
        content(doubleLeft = true)
        for (style in listOf(NumberStyle.SOLID, NumberStyle.OUTLINE)) {
            compose.runOnIdle { rowState.value = rowState.value.copy(numberStyle = style) }
            compose.waitForIdle()
            val originalOne = gutterPixels()
            assertTrue("The reference rank must contain visible ink", originalOne.any { android.graphics.Color.red(it) > 8 })
            compose.mainClock.autoAdvance = false
            repeat(8) { tap(KeyEvent.KEYCODE_DPAD_RIGHT) }
            down(KeyEvent.KEYCODE_DPAD_LEFT); up(KeyEvent.KEYCODE_DPAD_LEFT)
            down(KeyEvent.KEYCODE_DPAD_LEFT, gapMs = 20); up(KeyEvent.KEYCODE_DPAD_LEFT)
            var landed = false
            repeat(45) {
                compose.mainClock.advanceTimeBy(16); compose.waitForIdle()
                if (selected.intValue == 0) {
                    landed = true
                    compose.onNodeWithTag("xl-featured-rank-test-xl-0").assertExists()
                    assertEquals("The artwork must finish on the same first item as its rank", 0, artworkIndex())
                }
            }
            assertTrue("The shortcut must finish", landed)
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            assertXlFocus(0)
            assertArrayEquals("Returning to item 1 must reproduce its original $style glyph", originalOne, gutterPixels())
            for (index in 1..3) {
                val rankX = compose.onNodeWithTag("xl-rank-test-xl-$index").fetchSemanticsNode().layoutInfo.coordinates.positionInRoot().x
                val posterX = compose.onNodeWithTag("xl-portrait-test-xl-$index").fetchSemanticsNode().layoutInfo.coordinates.positionInRoot().x
                assertEquals("Every portrait must retain its matching rank", posterX, rankX, 0.1f)
            }
        }
    }

    @Test fun rightMoveKeepsThePortraitAndItsNumberTogetherUntilTheyPassBehindXl() {
        rowState.value = row().copy(numberStyle = NumberStyle.SOLID)
        content()
        fun x(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().layoutInfo.coordinates.positionInRoot().x
        val slot = compose.onNodeWithTag("xl-featured-test-xl").fetchSemanticsNode().layoutInfo.coordinates
        val featuredRight = slot.positionInRoot().x + slot.size.width
        compose.mainClock.autoAdvance = false
        down(KeyEvent.KEYCODE_DPAD_RIGHT); up(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertXlFocus(1)
        compose.onNodeWithTag("xl-portrait-test-xl-1").assertExists()
        var movingFrames = 0
        repeat(24) {
            val nodes = compose.onAllNodes(hasTestTag("xl-portrait-test-xl-1"), useUnmergedTree = true).fetchSemanticsNodes()
            if (nodes.isNotEmpty()) {
                val poster = nodes.single().layoutInfo.coordinates
                assertEquals("The rank must travel with its own portrait without a stationary hold",
                    poster.positionInRoot().x, x("xl-rank-test-xl-1"), 0.1f)
                assertTrue("The portrait must only unmount after the XL card covers it",
                    poster.positionInRoot().x + poster.size.width > featuredRight - 0.6f)
                movingFrames++
            }
            compose.mainClock.advanceTimeBy(16); compose.waitForIdle()
        }
        assertTrue(movingFrames > 0)
        compose.onNodeWithTag("xl-portrait-test-xl-1").assertDoesNotExist()
        assertXlFocus(1)
        compose.mainClock.autoAdvance = true
    }

    @Test fun xlRankBottomMatchesTheOrdinaryNumberedPosterGeometry() {
        compose.runOnUiThread {
            activity.get().setContent {
                MaterialTheme {
                    Column(Modifier.width(960.dp).height(540.dp).background(Color.Black)) {
                        Box(Modifier.padding(start = 52.dp).testTag("regular-number")) {
                            NumberedCatalogCardWrapper(8, 140.dp, 200.dp) {
                                Box(Modifier.width(140.dp).height(200.dp).background(Color.Cyan))
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                        Box(Modifier.padding(start = 52.dp).testTag("xl-number")) {
                            NumberedCatalogCardWrapper(8, 163.dp, 244.dp, preMeasuredTextWidth = 32.dp,
                                compactXl = true, compactXlFontSize = xlRankFontSize(244f)) {
                                Box(Modifier.width(163.dp).height(244.dp).background(Color.Cyan))
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        fun bottom(tag: String, height: Float) = compose.onNodeWithTag(tag).fetchSemanticsNode()
            .layoutInfo.coordinates.positionInRoot().y + height
        val ordinaryBottom = bottom("regular-number", 200f)
        val xlBottom = bottom("xl-number", 244f)
        val inkBottoms = mutableListOf<Int>()
        compose.runOnUiThread {
            val view = activity.get().findViewById<android.view.View>(android.R.id.content)
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLACK)
            view.draw(android.graphics.Canvas(bitmap))
            for (posterBottom in listOf(ordinaryBottom, xlBottom)) {
                val lastInk = ((posterBottom - 35).toInt()..(posterBottom + 14).toInt()).last { y ->
                    (0..50).any { x -> bitmap.getPixel(x, y) == android.graphics.Color.rgb(136, 136, 136) }
                }
                inkBottoms += lastInk
            }
            bitmap.recycle()
        }
        val ordinaryDrop = inkBottoms[0] - ordinaryBottom
        val xlDrop = inkBottoms[1] - xlBottom
        assertTrue("XL rank ink should reach below its poster, drop=$xlDrop", xlDrop > 0f)
        assertEquals("XL and ordinary ranks should sit at the same lower edge", ordinaryDrop, xlDrop, 3f)
    }

    @Test fun leavingDuringAnAnimatedJumpCannotChangeTheRememberedTitleLater() {
        content(doubleLeft = true)
        repeat(8) { tap(KeyEvent.KEYCODE_DPAD_RIGHT) }
        compose.mainClock.autoAdvance = false
        down(KeyEvent.KEYCODE_DPAD_LEFT); up(KeyEvent.KEYCODE_DPAD_LEFT)
        down(KeyEvent.KEYCODE_DPAD_LEFT, gapMs = 20); up(KeyEvent.KEYCODE_DPAD_LEFT)
        assertXlFocus(7)
        down(KeyEvent.KEYCODE_DPAD_UP); up(KeyEvent.KEYCODE_DPAD_UP)
        compose.runOnIdle { assertTrue(platformHasFocus) }
        compose.mainClock.advanceTimeBy(700); compose.waitForIdle()
        compose.runOnIdle { assertTrue(platformHasFocus); assertEquals(7, selected.intValue) }
        compose.mainClock.autoAdvance = true
        tap(KeyEvent.KEYCODE_DPAD_DOWN)
        assertXlFocus(7)
    }

    @Test fun xlSummaryUsesExactlyThreeDescriptionLinesWithoutAHiddenFourthLine() {
        val source = row()
        val longSynopsis = List(36) { "Detailed synopsis" }.joinToString(" ")
        val first = source.items.first()
        rowState.value = source.copy(items = source.items.toMutableList().apply {
            this[0] = first.copy(heroPreview = first.heroPreview.copy(description = longSynopsis))
        })
        content()
        val layoutResults = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(longSynopsis).performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult
        ) { it(layoutResults) }
        assertEquals("XL summary must reserve and render only three lines",
            3, layoutResults.single().lineCount)
    }

    @Test fun summaryOffRemovesItsSpaceAndShowsTheFollowingRow() {
        rowState.value = row().copy(showXlSummary = false)
        content()
        compose.onAllNodes(hasText("Synopsis", substring = true)).assertCountEquals(0)
        compose.onNodeWithText("Next row").assertIsDisplayed()
        compose.onNodeWithText("2026", substring = true).assertExists()
    }

    @Test fun xlSynopsisMatchesTheFullSizeHalfWidthSynopsis() {
        val preview = row().items.first().heroPreview
        compose.runOnUiThread {
            activity.get().setContent {
                MaterialTheme {
                    Column(Modifier.width(430.dp)) {
                        HeroTitleContent(preview.copy(description = "Half-width synopsis"), true,
                            heroMetadataLarge = true, showTitleLogo = false)
                        HeroTitleContent(preview.copy(description = "XL synopsis"), true,
                            heroMetadataLarge = false, showTitleLogo = false, useFullSizeDescription = true)
                        HeroTitleContent(preview.copy(description = "Small synopsis"), true,
                            heroMetadataLarge = false, showTitleLogo = false)
                    }
                }
            }
        }
        compose.waitForIdle()
        fun font(text: String): Float {
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText(text).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {
                it(results)
            }
            return results.single().layoutInput.style.fontSize.value
        }
        assertEquals(font("Half-width synopsis"), font("XL synopsis"), 0.001f)
        assertTrue(font("XL synopsis") > font("Small synopsis"))
    }

    companion object {
        private fun row() = HeroCarouselRow(
            key = "test-xl", title = "XL catalog", globalRowIndex = 0, isXl = true,
            items = (0..9).map { i ->
                val title = "Title $i"
                ModernCarouselItem(
                    key = "item-$i", title = title, subtitle = null, imageUrl = null,
                    heroPreview = HeroPreview(title, null, "Synopsis for $title. A longer summary for testing layout.",
                        "Movie", yearText = "2026", genres = emptyList(), poster = null, backdrop = null, imageUrl = null),
                    payload = ModernPayload.Catalog("test-xl::$i", "id-$i", "movie", "", title, null, "movie")
                )
            }
        )
    }
}
