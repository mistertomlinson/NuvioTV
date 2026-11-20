package com.nuvio.tv.ui.screens.home

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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
    onUpPressed: (() -> Unit)?,
    artworkPresentation: XlArtworkPresentation? = null,
    onHeaderMeasured: (Float) -> Unit = {},
    onRowMeasured: (Dp, Dp) -> Unit = { _, _ -> }
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
    val scope = rememberCoroutineScope()
    val jumpOrigin = remember(row.key) { Animatable(0f) }
    var jumping by remember(row.key) { mutableStateOf(false) }
    val jumpJob = remember(row.key) { arrayOfNulls<Job>(1) }
    val published = remember(row.key) { XlPublishedSelection() }
    var rowHasFocus by remember(row.key) { mutableStateOf(false) }
    val selectedItem = row.items[selected]
    val imageLoader = remember(context) { coil.Coil.imageLoader(context) }
    var presentedKey by remember(row.key) { mutableStateOf(selectedItem.key) }
    val presentedIndex = if (artworkPresentation == null) selected else
        row.items.indexOfFirst { it.key == presentedKey }.takeIf { it >= 0 } ?: selected
    val presentedItem = row.items[presentedIndex]
    DisposableEffect(artworkPresentation, row.key) {
        artworkPresentation?.renderers?.set(row.key, true)
        onDispose { artworkPresentation?.renderers?.remove(row.key) }
    }
    LaunchedEffect(rowHasFocus, jumping, selectedItem, artworkPresentation, geometry, density) {
        if (!rowHasFocus || jumping || artworkPresentation == null) return@LaunchedEffect
        val blurUrl = firstNonBlank(selectedItem.heroPreview.backdrop, selectedItem.heroPreview.imageUrl)
        val requests = buildList {
            selectedItem.xlLandscapeImageUrl()?.let { add(homePosterImageRequest(context, it,
                with(density) { geometry.featuredWidth.dp.roundToPx() }, with(density) { geometry.cardHeight.dp.roundToPx() })) }
            if (blurUrl != null) add(xlBlurredBackdropRequest(context, blurUrl))
        }
        prepareXlArtworkPair(imageLoader, requests)
        // Both cache entries are ready (or have failed). One composition commit
        // swaps the permanent card's artwork and releases the background fade.
        presentedKey = selectedItem.key
        artworkPresentation.frame = XlArtworkFrame(row.key, selectedItem.key, blurUrl)
    }
    val nextLandscape = remember(row.items, selected, geometry, density) {
        xlNextLandscapeRequest(context, row.items, selected,
            with(density) { geometry.featuredWidth.dp.roundToPx() },
            with(density) { geometry.cardHeight.dp.roundToPx() })
    }
    val nextBlurUrl = row.items.getOrNull(selected + 1)?.heroPreview?.let { firstNonBlank(it.backdrop, it.imageUrl) }
    LaunchedEffect(rowHasFocus, jumping, nextLandscape, nextBlurUrl) {
        if (!rowHasFocus || jumping || nextLandscape == null) return@LaunchedEffect
        // Conflate held D-pad presses; decode only one likely destination, on
        // Coil's background path. A changed selection/focus cancels this work.
        delay(80)
        withContext(Dispatchers.IO) {
            try { imageLoader.execute(nextLandscape) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { /* Best-effort lookahead never blocks Home. */ }
        }
        if (nextBlurUrl != null) {
            delay(100)
            try { imageLoader.execute(xlBlurredBackdropRequest(context, nextBlurUrl)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { /* The dark background can fall back to black. */ }
        }
    }

    LaunchedEffect(rowHasFocus, selected, selectedItem.key) {
        if (!rowHasFocus) {
            leftShortcut.reset()
            jumpJob[0]?.cancel()
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
                jumpJob[0]?.cancel()
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
        val maximumWidth = row.items.indices.maxOfOrNull { paint.measureText((it + 1).toString()) / density.density } ?: 0f
        // Keep even three-digit ranks inside the existing 52dp left gutter.
        val gutterTextWidth = 50f + XL_RANK_OVERLAP_DP // leave room for the outline stroke
        if (maximumWidth > gutterTextWidth) paint.textSize *= gutterTextWidth / maximumWidth
        val widths = FloatArray(row.items.size) { index ->
            if (numbered) paint.measureText((index + 1).toString()) / density.density else 0f
        }
        XlRankTrack(geometry.portraitWidth, widths, paint.textSize / density.density)
    }
    val jumpFirstPortrait by remember(rankTrack) { derivedStateOf { rankTrack.firstPortrait(jumpOrigin.value) } }
    val latestSelected by rememberUpdatedState(selected)
    val animatedOrigin = remember(rankTrack) { Animatable(rankTrack.origin(selected)) }
    val runwayWasJumping = remember(rankTrack) { booleanArrayOf(false) }
    LaunchedEffect(selected, rankTrack, jumping) {
        if (jumping) runwayWasJumping[0] = true
        else if (runwayWasJumping[0]) {
            animatedOrigin.snapTo(rankTrack.origin(selected))
            runwayWasJumping[0] = false
        } else animatedOrigin.animateTo(rankTrack.origin(selected), tween(220))
    }
    // Posters and ranks read this shared track during placement. Unaffected
    // numbers stay opaque and move by exactly the same amount as their cards.
    val runwayOrigin = remember(rankTrack) { derivedStateOf {
        if (jumping) jumpOrigin.value else if (runwayWasJumping[0]) rankTrack.origin(latestSelected) else animatedOrigin.value
    } }
    val runwayFirst by remember(rankTrack) { derivedStateOf { rankTrack.firstPortrait(runwayOrigin.value) } }
    val descriptionLineHeight = MaterialTheme.typography.bodyMedium.lineHeight
    val summaryExtra = with(density) {
        (descriptionLineHeight * (if (portraitMetadata) 0.90f else 1f) * (if (portraitMetadata) 4 else 5)).toDp()
    } + 8.dp * (if (portraitMetadata) 0.92f else 1f) * (if (heroMetadataLarge) 1f else 0.7f)
    var primaryMetadataHeight by remember(row.key, heroMetadataLarge) {
        mutableStateOf(if (heroMetadataLarge) 30.dp else 25.5.dp)
    }

    val renderCard: @Composable (Int, Boolean) -> Unit = { index, featured ->
        val item = row.items[index]
        val payload = item.payload as? ModernPayload.Catalog
        if (payload != null) {
            val artworkItem = if (!featured) item else if (jumping) row.items[jumpFirstPortrait.coerceIn(0, row.items.lastIndex)] else presentedItem
            val image = if (featured) {
                artworkItem.xlLandscapeImageUrl()
            } else firstNonBlank(item.heroPreview.poster, item.imageUrl)
            val cardItem = remember(item, artworkItem, image) {
                item.copy(imageUrl = image, title = artworkItem.title, heroPreview = artworkItem.heroPreview)
            }
            val cardWidth = (if (featured) geometry.featuredWidth else geometry.portraitWidth).dp
            val card: @Composable () -> Unit = {
                ModernCatalogRowItem(
                    modifier = if (featured) Modifier else Modifier.focusProperties { canFocus = false },
                    item = cardItem,
                    payload = payload,
                    requester = if (featured) uiCaches.requesterFor(row.key, item.key) else remember { FocusRequester() },
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
                    effectiveExpandEnabled = featured && autoplayEnabled && !jumping && presentedIndex == selected,
                    effectiveAutoplayEnabled = featured && autoplayEnabled && !jumping && presentedIndex == selected,
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
            if (featured) Box(Modifier.testTag("xl-artwork-${row.key}-${if (jumping) jumpFirstPortrait.coerceIn(0, row.items.lastIndex) else presentedIndex}")) {
                card()
            } else card()
        }
    }

    Column(
        modifier = Modifier
            .testTag("xl-row-${row.key}")
            .onSizeChanged {
                val height = with(density) { it.height.toDp() }
                onRowMeasured(height, height + if (row.showXlSummary) 0.dp else summaryExtra)
            }
            .focusRestorer { uiCaches.requesterFor(row.key, row.items[selected].key) }
            .onFocusChanged { rowHasFocus = it.hasFocus }
            .focusGroup()
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                val left = native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT
                val right = native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT
                if (!left && !right) {
                    if (native.action == AndroidKeyEvent.ACTION_DOWN) {
                        leftShortcut.reset()
                        if ((jumping || presentedIndex != selected) && (native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                                native.keyCode == AndroidKeyEvent.KEYCODE_ENTER || native.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER)) {
                            // Commit the landscape title currently being shown
                            // before the permanent card handles Select/long press.
                            val shown = if (jumping) jumpFirstPortrait.coerceIn(0, currentRow.items.lastIndex) else presentedIndex
                            selectedKey = currentRow.items[shown].key
                            presentedKey = currentRow.items[shown].key
                            jumpJob[0]?.cancel()
                        }
                        if (native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP || native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN)
                            jumpJob[0]?.cancel()
                    }
                    return@onPreviewKeyEvent false
                }
                if (right && native.action == AndroidKeyEvent.ACTION_DOWN) leftShortcut.reset()
                if (native.action == AndroidKeyEvent.ACTION_UP) {
                    if (left) leftShortcut.onUp(native.eventTime)
                    return@onPreviewKeyEvent true
                }
                if (native.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent true
                if (jumping) {
                    leftShortcut.reset()
                    return@onPreviewKeyEvent true
                }
                val now = native.eventTime
                if (left && native.repeatCount > 0) leftShortcut.reset()
                if (!repeatGate.accept(native.repeatCount, now, fastNavigation)) return@onPreviewKeyEvent true
                val doubleLeft = left && leftShortcut.onDown(selected, native.repeatCount, doubleLeftRowShortcutEnabled, now)
                when {
                    doubleLeft -> {
                        // Shuffle landscape artwork with the bounded portrait
                        // runway; the permanent focus node never moves.
                        jumping = true
                        jumpJob[0] = scope.launch {
                            try {
                                jumpOrigin.snapTo(rankTrack.origin(selected))
                                jumpOrigin.animateTo(rankTrack.origin(0), tween(
                                    ((selected.coerceAtMost(24) / if (numbered) 20f else 32f) * 1000).toInt().coerceAtLeast(220),
                                    easing = LinearEasing
                                ))
                                // Rank, artwork and logical selection finish as
                                // one title, even if the final blur is still cold.
                                presentedKey = currentRow.items.first().key
                                selectedKey = currentRow.items.first().key
                            } finally {
                                jumping = false
                            }
                        }
                    }
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
            modifier = Modifier.onSizeChanged { onHeaderMeasured(it.height.toFloat()) }
                .padding(start = 52.dp, bottom = rowTitleBottom)
        )
        Box(
            modifier = Modifier.fillMaxWidth().height(geometry.cardHeight.dp)
                .drawWithContent {
                    val rankDrop = if (numbered) xlRankBaselineDrop(rankTrack.fontSize).dp.toPx() else 0f
                    clipRect(left = 0f, top = -3.dp.toPx(), bottom = size.height + rankDrop + 3.dp.toPx()) {
                        this@drawWithContent.drawContent()
                    }
                }
        ) {
            if (numbered) {
                Box(Modifier.fillMaxWidth().height(geometry.cardHeight.dp).zIndex(-1f)
                    .testTag("xl-ranks-${row.key}")) {
                    val featuredRank = if (jumping) jumpFirstPortrait.coerceIn(0, row.items.lastIndex) else presentedIndex
                    Box(Modifier.absoluteOffset(x = rankTrack.featuredLeft.dp)
                        .testTag("xl-featured-rank-${row.key}-$featuredRank")) {
                        NumberedCatalogCardWrapper(featuredRank + 1, geometry.featuredWidth.dp, geometry.cardHeight.dp,
                            preMeasuredTextWidth = rankTrack.textWidths[featuredRank].dp, numberStyle = row.numberStyle,
                            useThemeColorForNumbers = useThemeColorForNumbers, compactXl = true,
                            compactXlFontSize = rankTrack.fontSize) {
                            Spacer(Modifier.width(geometry.featuredWidth.dp).height(geometry.cardHeight.dp))
                        }
                    }
                    val firstRank = (runwayFirst - 1).coerceAtLeast(0)
                    val lastRank = minOf(row.items.lastIndex, runwayFirst + geometry.visiblePortraitCount + 1)
                    for (index in firstRank..lastRank) key(row.items[index].key) {
                        XlPortraitRank(index, row.key, rankTrack, runwayOrigin, geometry, row.numberStyle, useThemeColorForNumbers)
                    }
                }
            }
            val firstPortrait = if (jumping) jumpFirstPortrait else selected + 1
            val lastPortrait = minOf(row.items.lastIndex, firstPortrait + geometry.visiblePortraitCount)
            for (index in firstPortrait..lastPortrait) {
                key(row.items[index].key) {
                    Box(Modifier.absoluteOffset {
                        val x = rankTrack.portraitLeftAtOrigin(index, runwayOrigin.value, geometry.featuredWidth)
                        IntOffset(with(density) { x.dp.roundToPx() }, 0)
                    }.width(geometry.portraitWidth.dp).testTag("xl-portrait-${row.key}-$index")) {
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
        // The platform parallax canvas is wider than the physical screen. Keep
        // this overlay on the real viewport so its right inset mirrors x=52.
        Box(Modifier.width(configuration.screenWidthDp.dp).padding(horizontal = rankTrack.featuredLeft.dp)
            .padding(top = 12.dp)) {
            HeroTitleContent(
                preview = presentedItem.heroPreview,
                portraitMode = portraitMetadata,
                heroMetadataLarge = heroMetadataLarge,
                showTitleLogo = false,
                showDescription = row.showXlSummary,
                useFullSizeDescription = true,
                modifier = Modifier.width(geometry.featuredWidth.dp),
                primaryMetadataModifier = Modifier.testTag("xl-primary-metadata-${row.key}").onSizeChanged {
                    primaryMetadataHeight = with(density) { it.height.toDp() }
                }
            )
            // matchParentSize excludes the badge from row height measurement.
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.TopEnd) {
                ModernXlComingSoonBadge(
                    text = presentedItem.heroPreview.comingSoonText?.takeIf { it.isNotBlank() },
                    visible = rowHasFocus,
                    modifier = Modifier.offset(y = (primaryMetadataHeight - COMING_SOON_PILL_HEIGHT) / 2f)
                        .testTag("xl-release-badge-${row.key}")
                )
            }
        }
    }
}

@Composable
internal fun ModernXlComingSoonBadge(text: String?, visible: Boolean, modifier: Modifier = Modifier) {
    var retainedText by remember { mutableStateOf(text) }
    SideEffect { if (text != null) retainedText = text }
    val active = visible && text != null
    val opacity = animateFloatAsState(if (active) 1f else 0f,
        tween(if (active) 160 else 150, easing = LinearEasing), label = "xlReleaseBadgeOpacity")
    if (retainedText != null && (active || opacity.value > 0f)) {
        // XL's near-black backdrop needs no separate blur source. Reuse the
        // ordinary badge's exact glass, border, typography and text animation.
        ComingSoonGlassPill(retainedText!!, modifier.graphicsLayer { alpha = opacity.value })
    }
}
