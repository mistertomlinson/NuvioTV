package com.nuvio.tv.ui.screens.home

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.LocalSidebarOpenRequest
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.util.dpadRepeatThrottle
import kotlinx.coroutines.flow.collectLatest

/**
 * One permanent featured slot and a small moving portrait runway. The actual
 * card nodes keep their item keys/requesters across a step; only presentation
 * changes. There is no second player or invisible duplicate focus target.
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
    val geometry = remember(configuration.screenWidthDp, configuration.screenHeightDp, heroMetadataLarge) {
        xlRowGeometry(configuration.screenWidthDp.toFloat(), configuration.screenHeightDp.toFloat(), heroMetadataLarge)
    }
    val initialIndex = (uiCaches.focusedItemByRow[row.key] ?: savedRowIndices[row.key] ?: 0)
        .coerceIn(0, row.items.lastIndex)
    var selectedKey by rememberSaveable(row.key, if (row.key == HomeViewModel.MY_LIST_CATALOG_KEY) slotGeneration else 0) { mutableStateOf(row.items[initialIndex].key) }
    val selected = row.items.indexOfFirst { it.key == selectedKey }.takeIf { it >= 0 } ?: initialIndex
    var requestedIndex by remember(row.key) { mutableStateOf<Int?>(null) }
    var requestNonce by remember(row.key) { mutableIntStateOf(0) }
    val currentRow by rememberUpdatedState(row)
    val sidebarOpen = LocalSidebarOpenRequest.current
    val leftShortcut = remember(row.key) { XlRowLeftShortcut() }
    var rowHasFocus by remember(row.key) { mutableStateOf(false) }
    LaunchedEffect(rowHasFocus, selected, row.items[selected].key) {
        if (!rowHasFocus) {
            leftShortcut.reset()
        } else if (uiCaches.focusedItemByRow[row.key] != selected) {
            // An add-on refresh can move the focused title without changing its
            // stable node. Keep the parent selection in sync with its new index.
            onRowItemFocused(row.key, selected, false, true)
            (row.items[selected].payload as? ModernPayload.Catalog)?.let { payload ->
                onCatalogSelectionFocused(FocusedCatalogSelection(payload.focusKey, payload))
            }
        }
    }

    fun requestItem(index: Int) {
        val target = index.coerceIn(0, row.items.lastIndex)
        // Neighbor requesters are already attached; retain native focus behavior.
        val focused = runCatching { uiCaches.requesterFor(row.key, row.items[target].key).requestFocus() }.getOrDefault(false)
        if (!focused) {
            selectedKey = row.items[target].key
            requestedIndex = target
            requestNonce++
        }
    }

    LaunchedEffect(requestNonce) {
        val target = requestedIndex ?: return@LaunchedEffect
        repeat(20) {
            withFrameNanos { }
            val item = currentRow.items.getOrNull(target) ?: return@LaunchedEffect
            if (runCatching { uiCaches.requesterFor(row.key, item.key).requestFocus() }.getOrDefault(false)) {
                requestedIndex = null
                return@LaunchedEffect
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
    // Preserve pagination without polling or work in the D-pad callback.
    LaunchedEffect(row.key, selected, row.items.size, row.hasMore, row.isLoading) {
        val nearEnd = selected + geometry.visiblePortraitCount >= row.items.lastIndex
        if (!nearEnd) uiCaches.loadMoreRequestedTotals.remove(row.key)
        if (nearEnd && row.hasMore && !row.isLoading) {
            val id = row.catalogId
            val addon = row.addonId
            val type = row.apiType
            if (id != null && addon != null && type != null && uiCaches.loadMoreRequestedTotals[row.key] != row.items.size) {
                uiCaches.loadMoreRequestedTotals[row.key] = row.items.size
                // The existing row callback owns the repository/network work.
                onLoadMoreCatalog(id, addon, type)
            }
        }
    }

    Column(
        modifier = Modifier
            .focusRestorer { uiCaches.requesterFor(row.key, row.items[selected].key) }
            .onFocusChanged { rowHasFocus = it.hasFocus }
            .focusGroup()
            .dpadRepeatThrottle(horizontalGateMs = 80L, verticalGateMs = 100L)
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
                val doubleLeft = left && leftShortcut.onDown(selected, native.repeatCount, doubleLeftRowShortcutEnabled, now)
                when {
                    doubleLeft -> {
                        requestItem(0)
                    }
                    left && selected == 0 -> { if (native.repeatCount == 0) sidebarOpen() }
                    left -> requestItem(selected - 1)
                    right && selected < row.items.lastIndex -> requestItem(selected + 1)
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
                .drawWithContent { clipRect(left = leadingPx - 3.dp.toPx(), top = -3.dp.toPx(), bottom = size.height + 3.dp.toPx()) { this@drawWithContent.drawContent() } }
        ) {
            for (index in xlVisibleIndices(selected, row.items.size, geometry.visiblePortraitCount)) {
                val item = row.items[index]
                val payload = item.payload as? ModernPayload.Catalog ?: continue
                key(item.key) {
                    val featured = index == selected
                    val target = xlCardOffset(index, selected, geometry.featuredWidth, geometry.portraitWidth).dp
                    val animatedX by animateDpAsState(target, tween(220), label = "xlPortraitSlide")
                    val image = if (featured) {
                        firstNonBlank(item.metaPreview?.landscapePoster, item.heroPreview.backdrop, item.heroPreview.imageUrl, item.heroPreview.poster)
                    } else firstNonBlank(item.heroPreview.poster, item.imageUrl)
                    val cardItem = remember(item, image) { item.copy(imageUrl = image) }
                    Box(
                        modifier = Modifier
                            .absoluteOffset { IntOffset(with(density) { (52.dp + if (featured) 0.dp else animatedX).roundToPx() }, 0) }
                            .width((if (featured) geometry.featuredWidth else geometry.portraitWidth).dp)
                            .zIndex(if (featured) 1f else 0f)
                    ) {
                        ModernCatalogRowItem(
                            item = cardItem,
                            payload = payload,
                            requester = uiCaches.requesterFor(row.key, item.key),
                            useLandscapePosters = featured,
                            perCatalogLandscape = featured,
                            heroMetadataLarge = heroMetadataLarge,
                            showLabels = false,
                            posterCardCornerRadius = cornerRadius,
                            shimmerTranslateState = null,
                            modernCatalogCardWidth = (if (featured) geometry.featuredWidth else geometry.portraitWidth).dp,
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
                                selectedKey = item.key
                                onRowItemFocused(row.key, index, false, true)
                            },
                            onItemFocus = onItemFocus,
                            onPreloadAdjacentItem = { row.items.getOrNull(index + 1)?.metaPreview?.let(onPreloadAdjacentItem) },
                            onCatalogSelectionFocused = onCatalogSelectionFocused,
                            onNavigateToDetail = onNavigateToDetail,
                            onLongPress = { item.metaPreview?.let { onCatalogItemLongPress(it, payload.addonBaseUrl) } },
                            onBackdropInteraction = onBackdropInteraction,
                            onExpandedCatalogFocusKeyChange = onExpandedCatalogFocusKeyChange,
                            fixedXlGeometry = true,
                            onUpPressed = onUpPressed
                        )
                        if (row.numberStyle != NumberStyle.OFF) {
                            Text(
                                text = (index + 1).toString(),
                                style = TextStyle(
                                    fontFamily = FontFamily(Font(R.font.jost_variable)),
                                    fontWeight = FontWeight.W500,
                                    fontSize = 26.sp,
                                    drawStyle = if (row.numberStyle == NumberStyle.OUTLINE) {
                                        Stroke(width = with(density) { 1.dp.toPx() })
                                    } else Fill
                                ),
                                color = if (useThemeColorForNumbers) NuvioColors.Secondary else Color.White,
                                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(minOf(cornerRadius, 6.dp)))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
        Box(
            modifier = Modifier.padding(start = 52.dp, top = 12.dp).width(geometry.featuredWidth.dp)
        ) {
            HeroTitleContent(
                preview = row.items[selected].heroPreview,
                portraitMode = portraitMetadata,
                heroMetadataLarge = heroMetadataLarge,
                showTitleLogo = false
            )
        }
    }
}
