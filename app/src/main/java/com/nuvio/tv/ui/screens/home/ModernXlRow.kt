package com.nuvio.tv.ui.screens.home

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.res.ResourcesCompat
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.LocalSidebarOpenRequest
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.util.LocalFastHorizontalNavigationEnabled
import kotlinx.coroutines.flow.collectLatest

private class XlPublishedSelection {
    var key: String? = null
    var index: Int = -1
}

/**
 * Physical focus belongs to the permanent featured slot. Horizontal navigation
 * changes its title, not its focus node. Portrait previews never receive focus.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ModernXlRowSection(
    row: HeroCarouselRow,
    slotGeneration: Int,
    uiCaches: ModernHomeUiCaches,
    pendingRowFocus: PendingRowFocusHolder,
    savedRowIndices: Map<String, Int>,
    onPendingRowFocusCleared: () -> Unit,
    onRowItemFocused: (String, Int, Boolean, Boolean) -> Unit,
    rowTitleBottom: Dp,
    heroMetadataLarge: Boolean,
    portraitMetadata: Boolean,
    cornerRadius: Dp,
    autoplayEnabled: Boolean,
    muted: Boolean,
    expandedFocusKey: String?,
    trailerUrl: String?,
    trailerAudioUrl: String?,
    heavyOverlayAlpha: State<Float>,
    cardDepthAlpha: State<Float>,
    hideNewSeasonBadge: Boolean,
    onItemFocus: (MetaPreview) -> Unit,
    onPreloadAdjacentItem: (MetaPreview) -> Unit,
    onCatalogSelectionFocused: (FocusedCatalogSelection) -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    onLoadMoreCatalog: (String, String, String) -> Unit,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit,
    onBackdropInteraction: () -> Unit,
    onExpandedCatalogFocusKeyChange: (String?) -> Unit,
    useThemeColorForNumbers: Boolean,
    doubleLeftRowShortcutEnabled: Boolean,
    onUpPressed: (() -> Unit)?
) {
    if (row.items.isEmpty()) return
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val context = LocalContext.current
    val geometry = remember(configuration.screenWidthDp, configuration.screenHeightDp, heroMetadataLarge) {
        xlRowGeometry(configuration.screenWidthDp.toFloat(), configuration.screenHeightDp.toFloat(), heroMetadataLarge)
    }
    val initialIndex = (uiCaches.focusedItemByRow[row.key] ?: savedRowIndices[row.key] ?: 0).coerceIn(0, row.items.lastIndex)
    var selectedKey by rememberSaveable(row.key, if (row.key == HomeViewModel.MY_LIST_CATALOG_KEY) slotGeneration else 0) {
        mutableStateOf(row.items[initialIndex].key)
    }
    val selected = row.items.indexOfFirst { it.key == selectedKey }.takeIf { it >= 0 } ?: initialIndex
    val currentRow by rememberUpdatedState(row)
    val sidebarOpen = LocalSidebarOpenRequest.current
    val fastNavigation = LocalFastHorizontalNavigationEnabled.current
    val leftShortcut = remember(row.key) { XlRowLeftShortcut() }
    val repeatGate = remember(row.key) { XlHorizontalRepeatGate() }
    val published = remember(row.key) { XlPublishedSelection() }
    var rowHasFocus by remember(row.key) { mutableStateOf(false) }
    val selectedItem = row.items[selected]

    LaunchedEffect(rowHasFocus, selected, selectedItem.key) {
        if (!rowHasFocus) {
            leftShortcut.reset()
        } else if (published.key != selectedItem.key || published.index != selected) {
            // The existing focus target remains focused during logical moves.
            published.key = selectedItem.key
            published.index = selected
            onRowItemFocused(row.key, selected, false, true)
            selectedItem.metaPreview?.let(onItemFocus)
            row.items.getOrNull(selected + 1)?.metaPreview?.let(onPreloadAdjacentItem)
            (selectedItem.payload as? ModernPayload.Catalog)?.let { payload ->
                onCatalogSelectionFocused(FocusedCatalogSelection(payload.focusKey, payload))
            }
        }
    }
    LaunchedEffect(row.key) {
        snapshotFlow { Triple(pendingRowFocus.nonce, pendingRowFocus.key, pendingRowFocus.index) }
            .collectLatest { (_, rowKey, index) ->
                if (rowKey != row.key || currentRow.items.isEmpty()) return@collectLatest
                val target = (index ?: 0).coerceIn(0, currentRow.items.lastIndex)
                selectedKey = currentRow.items[target].key
                repeat(20) {
                    withFrameNanos { }
                    val item = currentRow.items.getOrNull(target) ?: return@collectLatest
                    if (runCatching { uiCaches.requesterFor(row.key, item.key).requestFocus() }.getOrDefault(false)) {
                        onPendingRowFocusCleared()
                        return@collectLatest
                    }
                }
            }
    }
    LaunchedEffect(row.key, selected, row.items.size, row.hasMore, row.isLoading) {
        val nearEnd = selected + geometry.visiblePortraitCount >= row.items.lastIndex
        if (!nearEnd) uiCaches.loadMoreRequestedTotals.remove(row.key)
        if (nearEnd && row.hasMore && !row.isLoading) {
            val id = row.catalogId
            val addon = row.addonId
            val type = row.apiType
            if (id != null && addon != null && type != null && uiCaches.loadMoreRequestedTotals[row.key] != row.items.size) {
                uiCaches.loadMoreRequestedTotals[row.key] = row.items.size
                onLoadMoreCatalog(id, addon, type)
            }
        }
    }

    val numbered = row.numberStyle != NumberStyle.OFF
    val rankTypeface = remember(context) {
        ResourcesCompat.getFont(context, R.font.jost_variable) ?: android.graphics.Typeface.DEFAULT_BOLD
    }
    val rankTrack = remember(numbered, rankTypeface, geometry, row.items.size, density) {
        val paint = android.graphics.Paint().apply {
            typeface = rankTypeface
            textSize = xlRankFontSize(geometry.cardHeight) * density.density
        }
        val widths = FloatArray(row.items.size) { index ->
            if (numbered) paint.measureText((index + 1).toString()) / density.density else 0f
        }
        XlRankTrack(geometry.portraitWidth, widths)
    }
    val lastSelected = remember(row.key) { intArrayOf(selected) }
    val previousIndex = lastSelected[0]
    SideEffect { lastSelected[0] = selected }

    val renderCard: @Composable (Int, Boolean) -> Unit = { index, featured ->
        val item = row.items[index]
        val payload = item.payload as? ModernPayload.Catalog
        if (payload != null) {
            val image = if (featured) {
                firstNonBlank(item.metaPreview?.landscapePoster, item.heroPreview.backdrop, item.heroPreview.imageUrl, item.heroPreview.poster)
            } else firstNonBlank(item.heroPreview.poster, item.imageUrl)
            val cardItem = remember(item, image) { item.copy(imageUrl = image) }
            val cardWidth = (if (featured) geometry.featuredWidth else geometry.portraitWidth).dp
            val card: @Composable () -> Unit = {
                ModernCatalogRowItem(
                    modifier = if (featured) Modifier else Modifier.focusProperties { canFocus = false },
                    item = cardItem,
                    payload = payload,
                    requester = uiCaches.requesterFor(row.key, item.key),
                    useLandscapePosters = featured,
                    perCatalogLandscape = featured,
                    heroMetadataLarge = heroMetadataLarge,
                    showLabels = false,
                    posterCardCornerRadius = cornerRadius,
                    shimmerTranslateState = null,
                    modernCatalogCardWidth = cardWidth,
                    modernCatalogCardHeight = geometry.cardHeight.dp,
                    landscapeTrailerExpandedHeight = geometry.cardHeight.dp,
                    focusedPosterBackdropTrailerMuted = muted,
                    effectiveExpandEnabled = featured && autoplayEnabled,
                    effectiveAutoplayEnabled = featured && autoplayEnabled,
                    expandLandscapePostersEnabled = false,
                    trailerPlaybackTarget = FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD,
                    expandedCatalogFocusKey = expandedFocusKey,
                    expandedTrailerPreviewUrl = if (featured) trailerUrl else null,
                    expandedTrailerPreviewAudioUrl = if (featured) trailerAudioUrl else null,
                    isWatched = false,
                    hideNewSeasonBadge = hideNewSeasonBadge,
                    showHeavyOverlays = true,
                    heavyOverlayAlpha = heavyOverlayAlpha,
                    cardDepthAlpha = cardDepthAlpha,
                    onFocused = {
                        if (featured) {
                            published.key = item.key
                            published.index = index
                            onRowItemFocused(row.key, index, false, true)
                        }
                    },
                    onItemFocus = onItemFocus,
                    onPreloadAdjacentItem = { row.items.getOrNull(index + 1)?.metaPreview?.let(onPreloadAdjacentItem) },
                    onCatalogSelectionFocused = onCatalogSelectionFocused,
                    onNavigateToDetail = onNavigateToDetail,
                    onLongPress = { item.metaPreview?.let { onCatalogItemLongPress(it, payload.addonBaseUrl) } },
                    onBackdropInteraction = onBackdropInteraction,
                    onExpandedCatalogFocusKeyChange = onExpandedCatalogFocusKeyChange,
                    fixedXlGeometry = true,
                    onUpPressed = if (featured) onUpPressed else null
                )
            }
            if (numbered) {
                NumberedCatalogCardWrapper(
                    number = index + 1,
                    cardWidth = cardWidth,
                    cardHeight = geometry.cardHeight.dp,
                    preMeasuredTextWidth = rankTrack.textWidths[index].dp,
                    numberStyle = row.numberStyle,
                    useThemeColorForNumbers = useThemeColorForNumbers,
                    compactXl = true,
                    content = card
                )
            } else card()
        }
    }

    Column(
        modifier = Modifier
            .testTag("xl-row-${row.key}")
            .focusRestorer { uiCaches.requesterFor(row.key, row.items[selected].key) }
            .onFocusChanged { rowHasFocus = it.hasFocus }
            .focusGroup()
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                val left = native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT
                val right = native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT
                if (!left && !right) return@onPreviewKeyEvent false
                if (right && native.action == AndroidKeyEvent.ACTION_DOWN) leftShortcut.reset()
                if (native.action == AndroidKeyEvent.ACTION_UP) {
                    if (left) leftShortcut.onUp(android.os.SystemClock.uptimeMillis())
                    return@onPreviewKeyEvent true
                }
                if (native.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent true
                val now = android.os.SystemClock.uptimeMillis()
                if (!repeatGate.accept(native.repeatCount, now, fastNavigation)) return@onPreviewKeyEvent true
                val doubleLeft = left && leftShortcut.onDown(selected, native.repeatCount, doubleLeftRowShortcutEnabled, now)
                when {
                    doubleLeft -> selectedKey = row.items.first().key
                    left && selected == 0 -> if (native.repeatCount == 0) sidebarOpen()
                    left -> selectedKey = row.items[selected - 1].key
                    right && selected < row.items.lastIndex -> selectedKey = row.items[selected + 1].key
                }
                true
            }
    ) {
        Text(
            text = row.title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = NuvioColors.TextPrimary,
            modifier = Modifier.padding(start = 52.dp, bottom = rowTitleBottom)
        )
        val leadingPx = with(density) { 52.dp.toPx() }
        Box(
            modifier = Modifier.fillMaxWidth().height(geometry.cardHeight.dp)
                .drawWithContent {
                    clipRect(left = leadingPx - 3.dp.toPx(), top = -3.dp.toPx(), bottom = size.height + 3.dp.toPx()) {
                        this@drawWithContent.drawContent()
                    }
                }
        ) {
            for (index in (selected + 1)..minOf(row.items.lastIndex, selected + geometry.visiblePortraitCount)) {
                key(row.items[index].key) {
                    val targetX = rankTrack.portraitLeft(index, selected, geometry.featuredWidth)
                    val animatedX = remember {
                        Animatable(if (selected < previousIndex && index == previousIndex) rankTrack.featuredLeft else targetX)
                    }
                    LaunchedEffect(targetX) { animatedX.animateTo(targetX, tween(220)) }
                    Box(Modifier.absoluteOffset { IntOffset(with(density) { animatedX.value.dp.roundToPx() }, 0) }
                        .width(geometry.portraitWidth.dp)) {
                        renderCard(index, false)
                    }
                }
            }
            // Its composition position, focus target, and geometry remain stable.
            Box(Modifier.absoluteOffset(x = rankTrack.featuredLeft.dp).width(geometry.featuredWidth.dp)
                .zIndex(1f).testTag("xl-featured-${row.key}")) {
                renderCard(selected, true)
            }
        }
        Box(Modifier.padding(start = rankTrack.featuredLeft.dp, top = 12.dp).width(geometry.featuredWidth.dp)) {
            HeroTitleContent(
                preview = row.items[selected].heroPreview,
                portraitMode = portraitMetadata,
                heroMetadataLarge = heroMetadataLarge,
                showTitleLogo = false,
                showDescription = row.showXlSummary,
                useFullSizeDescription = true
            )
        }
    }
}
