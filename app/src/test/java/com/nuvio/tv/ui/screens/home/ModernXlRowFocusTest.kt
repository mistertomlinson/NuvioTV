package com.nuvio.tv.ui.screens.home

import android.app.Application
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
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

    private fun content(fast: Boolean = false) {
        compose.runOnUiThread {
            activity.get().setContent {
                MaterialTheme {
                    CompositionLocalProvider(
                        LocalViewModelStoreOwner provides models,
                        LocalSidebarOpenRequest provides { sidebarOpens++ },
                        LocalFastHorizontalNavigationEnabled provides fast
                    ) {
                        Column(Modifier.width(960.dp).height(540.dp).onPreviewKeyEvent {
                            events += "key=${it.nativeKeyEvent.keyCode}, repeat=${it.nativeKeyEvent.repeatCount}, time=${SystemClock.uptimeMillis()}, selected=${selected.intValue}"
                            false
                        }) {
                            Button(onClick = {}, modifier = Modifier.focusRequester(platform)
                                .onFocusChanged { platformHasFocus = it.isFocused }.testTag("platform")) {
                                Text("Platform ${selected.intValue}")
                            }
                            ModernXlRowSection(
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
                                doubleLeftRowShortcutEnabled = false, onUpPressed = { platform.requestFocus() }
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

    private fun down(keyCode: Int, repeat: Int = 0) {
        compose.runOnUiThread {
            ShadowSystemClock.advanceBy(Duration.ofMillis(90))
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
