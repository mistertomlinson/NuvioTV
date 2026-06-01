package com.nuvio.tv.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Divider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.LocalNoBackdropImage
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.ErrorState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.PosterCardStyle
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.LocalCarouselFocusRequester
import com.nuvio.tv.LocalContentFocusRequester
import kotlin.math.roundToInt

private data class HomePosterOptionsTarget(
    val item: MetaPreview,
    val addonBaseUrl: String,
    val isFromMyList: Boolean = false
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit = { item ->
        onNavigateToDetail(
            when (item) {
                is ContinueWatchingItem.InProgress -> item.progress.contentId
                is ContinueWatchingItem.NextUp -> item.info.contentId
            },
            when (item) {
                is ContinueWatchingItem.InProgress -> item.progress.contentType
                is ContinueWatchingItem.NextUp -> item.info.contentType
            },
            ""
        )
    },
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit = onContinueWatchingClick,
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit = onContinueWatchingClick,
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit = { _, _, _ -> }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Clear stale trailer URLs after 5+ hours in background — YouTube stream
    // URLs expire after 6 hours, causing a frozen first-frame on wake.
    val appInForegroundForTrailer = com.nuvio.tv.LocalAppInForeground.current
    val backgroundedAtMs = com.nuvio.tv.LocalBackgroundedAtMs.current
    androidx.compose.runtime.LaunchedEffect(appInForegroundForTrailer) {
        if (appInForegroundForTrailer) {
            viewModel.clearTrailerUrlCacheIfStale(backgroundedAtMs)
            // Only re-trigger My List on real app resume, not detail page back-navigation
            val backgroundedDurationMs = if (backgroundedAtMs > 0L) System.currentTimeMillis() - backgroundedAtMs else 0L
            if (backgroundedDurationMs > 30_000L) {
                viewModel.observeMyList()
            }
        }
    }
    val effectiveAutoplayEnabled by viewModel.effectiveAutoplayEnabled.collectAsStateWithLifecycle(
        initialValue = false
    )
    val hasCatalogContent = uiState.catalogRows.any { it.items.isNotEmpty() }
    var hasEnteredCatalogContent by rememberSaveable { mutableStateOf(false) }
    var showHomeContentWithAnimation by rememberSaveable { mutableStateOf(false) }
    var posterOptionsTarget by remember { mutableStateOf<HomePosterOptionsTarget?>(null) }

    LaunchedEffect(hasCatalogContent) {
        if (hasCatalogContent) {
            hasEnteredCatalogContent = true
        }
    }

    val posterCardStyle = remember(
        uiState.posterCardWidthDp,
        uiState.posterCardCornerRadiusDp
    ) {
        val computedHeightDp = (uiState.posterCardWidthDp * 1.5f).roundToInt()
        PosterCardStyle(
            width = uiState.posterCardWidthDp.dp,
            height = computedHeightDp.dp,
            cornerRadius = uiState.posterCardCornerRadiusDp.dp,
            focusedBorderWidth = PosterCardDefaults.Style.focusedBorderWidth,
            focusedScale = PosterCardDefaults.Style.focusedScale
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
    ) {
        val hasAnyContent = uiState.catalogRows.isNotEmpty() ||
            uiState.continueWatchingItems.isNotEmpty() ||
            uiState.heroItems.isNotEmpty()

        when {
            uiState.isLoading && !hasAnyContent -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
            }

            uiState.error == "No addons installed" && uiState.catalogRows.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.home_no_addons),
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioColors.TextSecondary
                    )
                }
            }

            uiState.error == "No catalog addons installed" && uiState.catalogRows.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.home_no_catalog_addons),
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioColors.TextSecondary
                    )
                }
            }

            uiState.error != null && uiState.catalogRows.isEmpty() -> {
                ErrorState(
                    message = uiState.error ?: stringResource(R.string.error_generic),
                    onRetry = { viewModel.onEvent(HomeEvent.OnRetry) }
                )
            }

            else -> {
                val shouldShowLoadingGate = !hasEnteredCatalogContent && !hasCatalogContent ||
                    !uiState.layoutPreferencesReady
                LaunchedEffect(shouldShowLoadingGate) {
                    if (shouldShowLoadingGate) {
                        showHomeContentWithAnimation = false
                    } else if (!showHomeContentWithAnimation) {
                        // Flip on the next frame so AnimatedVisibility can run enter transition.
                        kotlinx.coroutines.yield()
                        showHomeContentWithAnimation = true
                    }
                }
                if (shouldShowLoadingGate) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator()
                    }
                } else {
                    AnimatedVisibility(
                        visible = showHomeContentWithAnimation,
                        enter = fadeIn(animationSpec = tween(320)) +
                            slideInVertically(
                                initialOffsetY = { it / 24 },
                                animationSpec = tween(320)
                            )
                    ) {
                        when (uiState.homeLayout) {
                            HomeLayout.CLASSIC -> ClassicHomeRoute(
                                viewModel = viewModel,
                                uiState = uiState,
                                posterCardStyle = posterCardStyle,
                                onNavigateToDetail = onNavigateToDetail,
                                onContinueWatchingClick = onContinueWatchingClick,
                                onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
                                onContinueWatchingPlayManually = onContinueWatchingPlayManually,
                                showContinueWatchingManualPlayOption = effectiveAutoplayEnabled,
                                onNavigateToCatalogSeeAll = onNavigateToCatalogSeeAll,
                                isCatalogItemWatched = { item ->
                                    val key = homeItemStatusKey(item.id, item.apiType)
                                    uiState.movieWatchedStatus[key] == true ||
                                        uiState.seriesWatchedStatus[key] == true
                                },
                                onCatalogItemLongPress = { item, addonBaseUrl ->
                                    val statusKey = homeItemStatusKey(item.id, item.apiType)
                                    posterOptionsTarget = HomePosterOptionsTarget(
                                        item = item,
                                        addonBaseUrl = addonBaseUrl,
                                        isFromMyList = addonBaseUrl.isEmpty() &&
                                            uiState.librarySourceMode == LibrarySourceMode.TRAKT
                                    )
                                }
                            )

                            HomeLayout.GRID -> GridHomeRoute(
                                viewModel = viewModel,
                                uiState = uiState,
                                posterCardStyle = posterCardStyle,
                                onNavigateToDetail = onNavigateToDetail,
                                onContinueWatchingClick = onContinueWatchingClick,
                                onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
                                onContinueWatchingPlayManually = onContinueWatchingPlayManually,
                                showContinueWatchingManualPlayOption = effectiveAutoplayEnabled,
                                onNavigateToCatalogSeeAll = onNavigateToCatalogSeeAll,
                                isCatalogItemWatched = { item ->
                                    val key = homeItemStatusKey(item.id, item.apiType)
                                    uiState.movieWatchedStatus[key] == true ||
                                        uiState.seriesWatchedStatus[key] == true
                                },
                                onCatalogItemLongPress = { item, addonBaseUrl ->
                                    val statusKey = homeItemStatusKey(item.id, item.apiType)
                                    posterOptionsTarget = HomePosterOptionsTarget(
                                        item = item,
                                        addonBaseUrl = addonBaseUrl,
                                        isFromMyList = addonBaseUrl.isEmpty() &&
                                            uiState.librarySourceMode == LibrarySourceMode.TRAKT
                                    )
                                }
                            )

                            HomeLayout.MODERN -> ModernHomeRoute(
                                viewModel = viewModel,
                                uiState = uiState,
                                onNavigateToDetail = onNavigateToDetail,
                                onContinueWatchingClick = onContinueWatchingClick,
                                onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
                                onContinueWatchingPlayManually = onContinueWatchingPlayManually,
                                showContinueWatchingManualPlayOption = effectiveAutoplayEnabled,
                                isCatalogItemWatched = { item ->
                                    val key = homeItemStatusKey(item.id, item.apiType)
                                    uiState.movieWatchedStatus[key] == true ||
                                        uiState.seriesWatchedStatus[key] == true
                                },
                                onCatalogItemLongPress = { item, addonBaseUrl ->
                                    val statusKey = homeItemStatusKey(item.id, item.apiType)
                                    posterOptionsTarget = HomePosterOptionsTarget(
                                        item = item,
                                        addonBaseUrl = addonBaseUrl,
                                        isFromMyList = addonBaseUrl.isEmpty() &&
                                            uiState.librarySourceMode == LibrarySourceMode.TRAKT
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    val selectedPoster = posterOptionsTarget
    if (selectedPoster != null) {
        val item = selectedPoster.item
        val statusKey = homeItemStatusKey(item.id, item.apiType)
        val isMovie = item.apiType.equals("movie", ignoreCase = true)
        HomePosterOptionsDialog(
            title = item.name,
            isInLibrary = selectedPoster.isFromMyList || uiState.posterLibraryMembership[statusKey] == true,
            isLibraryPending = statusKey in uiState.posterLibraryPending,
            showManageLists = uiState.librarySourceMode == LibrarySourceMode.TRAKT &&
                !selectedPoster.isFromMyList,
            isFromMyList = selectedPoster.isFromMyList,
            isMovie = isMovie,
            isWatched = uiState.movieWatchedStatus[statusKey] == true,
            isWatchedPending = statusKey in uiState.movieWatchedPending,
            onDismiss = { posterOptionsTarget = null },
            onDetails = {
                onNavigateToDetail(item.id, item.apiType, selectedPoster.addonBaseUrl)
                posterOptionsTarget = null
            },
            onToggleLibrary = {
                viewModel.togglePosterLibrary(item, selectedPoster.addonBaseUrl)
                posterOptionsTarget = null
            },
            onToggleWatched = {
                viewModel.togglePosterMovieWatched(item)
                posterOptionsTarget = null
            }
        )
    }

    if (uiState.showPosterListPicker) {
        HomeLibraryListPickerDialog(
            title = uiState.posterListPickerTitle ?: stringResource(R.string.detail_lists_fallback),
            tabs = uiState.libraryListTabs,
            membership = uiState.posterListPickerMembership,
            isPending = uiState.posterListPickerPending,
            error = uiState.posterListPickerError,
            onToggle = { key -> viewModel.togglePosterListPickerMembership(key) },
            onSave = { viewModel.savePosterListPickerMembership() },
            onDismiss = { viewModel.dismissPosterListPicker() }
        )
    }
}

@Composable
private fun ClassicHomeRoute(
    viewModel: HomeViewModel,
    uiState: HomeUiState,
    posterCardStyle: PosterCardStyle,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit,
    showContinueWatchingManualPlayOption: Boolean,
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit,
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit
) {
    val focusState by viewModel.focusState.collectAsStateWithLifecycle()
    CompositionLocalProvider(
        LocalNoBackdropImage provides uiState.focusedPosterNoBackdropImage
    ) {
    ClassicHomeContent(
        uiState = uiState,
        posterCardStyle = posterCardStyle,
        focusState = focusState,
        trailerPreviewUrls = viewModel.trailerPreviewUrls,
        trailerPreviewAudioUrls = viewModel.trailerPreviewAudioUrls,
        onNavigateToDetail = onNavigateToDetail,
        onContinueWatchingClick = onContinueWatchingClick,
        onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
        onContinueWatchingPlayManually = onContinueWatchingPlayManually,
        showContinueWatchingManualPlayOption = showContinueWatchingManualPlayOption,
        onNavigateToCatalogSeeAll = onNavigateToCatalogSeeAll,
        onRemoveContinueWatching = { contentId, season, episode, isNextUp ->
            viewModel.onEvent(HomeEvent.OnRemoveContinueWatching(contentId, season, episode, isNextUp))
        },
        isCatalogItemWatched = isCatalogItemWatched,
        onCatalogItemLongPress = onCatalogItemLongPress,
        onRequestTrailerPreview = { item ->
            viewModel.requestTrailerPreview(item)
        },
        onItemFocus = { item ->
            viewModel.onItemFocus(item)
        },
        onSaveFocusState = { vi, vo, ri, ii, m ->
            viewModel.saveFocusState(vi, vo, ri, ii, m)
        }
    )
    }
}

@Composable
private fun GridHomeRoute(
    viewModel: HomeViewModel,
    uiState: HomeUiState,
    posterCardStyle: PosterCardStyle,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit,
    showContinueWatchingManualPlayOption: Boolean,
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit,
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit
) {
    val gridFocusState by viewModel.gridFocusState.collectAsStateWithLifecycle()
    GridHomeContent(
        uiState = uiState,
        posterCardStyle = posterCardStyle,
        gridFocusState = gridFocusState,
        onNavigateToDetail = onNavigateToDetail,
        onContinueWatchingClick = onContinueWatchingClick,
        onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
        onContinueWatchingPlayManually = onContinueWatchingPlayManually,
        showContinueWatchingManualPlayOption = showContinueWatchingManualPlayOption,
        onNavigateToCatalogSeeAll = onNavigateToCatalogSeeAll,
        onRemoveContinueWatching = { contentId, season, episode, isNextUp ->
            viewModel.onEvent(HomeEvent.OnRemoveContinueWatching(contentId, season, episode, isNextUp))
        },
        isCatalogItemWatched = isCatalogItemWatched,
        onCatalogItemLongPress = onCatalogItemLongPress,
        onItemFocus = { item ->
            viewModel.onItemFocus(item)
        },
        onSaveGridFocusState = { vi, vo ->
            viewModel.saveGridFocusState(vi, vo)
        }
    )
}

@Composable
private fun ModernHomeRoute(
    viewModel: HomeViewModel,
    uiState: HomeUiState,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit,
    showContinueWatchingManualPlayOption: Boolean,
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit
) {
    val focusState by viewModel.focusState.collectAsStateWithLifecycle()
    val enrichingItemId by viewModel.enrichingItemId.collectAsStateWithLifecycle()
    val numberedCatalogKeys by viewModel._numberedCatalogKeysSet.collectAsStateWithLifecycle()
    val outlineNumberedCatalogKeys by viewModel._outlineNumberedCatalogKeysSet.collectAsStateWithLifecycle()
    val useThemeColorForNumbers by viewModel._useThemeColorForNumbers.collectAsStateWithLifecycle()
    val requestTrailerPreview = remember(viewModel) {
        { itemId: String, title: String, releaseInfo: String?, apiType: String ->
            viewModel.requestTrailerPreview(itemId, title, releaseInfo, apiType)
        }
    }
    val loadMoreCatalog = remember(viewModel) {
        { catalogId: String, addonId: String, type: String ->
            viewModel.onEvent(HomeEvent.OnLoadMoreCatalog(catalogId, addonId, type))
        }
    }
    val removeContinueWatching = remember(viewModel) {
        { contentId: String, season: Int?, episode: Int?, isNextUp: Boolean ->
            viewModel.onEvent(HomeEvent.OnRemoveContinueWatching(contentId, season, episode, isNextUp))
        }
    }
    val saveModernFocusState = remember(viewModel) {
        { vi: Int, vo: Int, ri: Int, ii: Int, m: Map<String, Int>, rk: String?, pid: String ->
            viewModel.saveFocusState(vi, vo, ri, ii, m, rk, pid)
        }
    }
    val preloadAdjacentItem = remember(viewModel) {
        { item: MetaPreview ->
            viewModel.preloadAdjacentItem(item)
        }
    }
    var selectedPlatformId by remember { mutableStateOf(focusState.selectedPlatformId) }
    var platformNavDirection by remember { mutableStateOf(0) }
    LaunchedEffect(platformNavDirection) {
        if (platformNavDirection != 0) {
            kotlinx.coroutines.delay(100)
            platformNavDirection = 0
        }
    }
    var isCarouselFocused by remember { mutableStateOf(false) }
    val aggregatePlatformsEnabled = uiState.aggregateStreamingPlatformsEnabled
    val fastPlatformScrollEnabled = uiState.fastPlatformScrollEnabled
    val fullWidthIconRowEnabled = uiState.fullWidthIconRowEnabled
    var isAtTop by remember { mutableStateOf(true) }
    val carouselFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    // Load cached platform ids so carousel shows instantly on cold launch
    val cachedPlatformIds by viewModel.getCachedVisiblePlatformIds()
        .collectAsStateWithLifecycle(initialValue = emptySet())
    // Use stable set from ViewModel (set once when loading completes), fall back to cache
    val stablePlatformIds = if (uiState.stableVisiblePlatformIds.isNotEmpty())
        uiState.stableVisiblePlatformIds else cachedPlatformIds
    var carouselReady by rememberSaveable { mutableStateOf(false) }
    var isHeroTrailerPlaying by remember { mutableStateOf(false) }
    // Show carousel as soon as we have any platform ids — from cache or live
    LaunchedEffect(stablePlatformIds) {
        if (stablePlatformIds.isNotEmpty() && !carouselReady) {
            carouselReady = true
        }
    }
    // Save to cache whenever live data is ready
    LaunchedEffect(uiState.stableVisiblePlatformIds) {
        if (uiState.stableVisiblePlatformIds.isNotEmpty()) {
            viewModel.saveCachedVisiblePlatformIds(uiState.stableVisiblePlatformIds)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    val carouselAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (carouselReady && aggregatePlatformsEnabled && !isHeroTrailerPlaying) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(400),
        label = "carouselFade"
    )

    CompositionLocalProvider(
        LocalNoBackdropImage provides uiState.focusedPosterNoBackdropImage,
        LocalCarouselFocusRequester provides carouselFocusRequester
    ) {
    ModernHomeContent(
        uiState = uiState,
        focusState = focusState,
        enrichingItemId = enrichingItemId,
        trailerPreviewUrls = viewModel.trailerPreviewUrls,
        trailerPreviewAudioUrls = viewModel.trailerPreviewAudioUrls,
        onNavigateToDetail = onNavigateToDetail,
        onContinueWatchingClick = onContinueWatchingClick,
        onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
        onContinueWatchingPlayManually = onContinueWatchingPlayManually,
        showContinueWatchingManualPlayOption = showContinueWatchingManualPlayOption,
        onRequestTrailerPreview = requestTrailerPreview,
        onLoadMoreCatalog = loadMoreCatalog,
        onRemoveContinueWatching = removeContinueWatching,
        numberedCatalogKeys = numberedCatalogKeys,
        outlineNumberedCatalogKeys = outlineNumberedCatalogKeys,
        useThemeColorForNumbers = useThemeColorForNumbers,
        isCatalogItemWatched = isCatalogItemWatched,
        onCatalogItemLongPress = onCatalogItemLongPress,
        onItemFocus = { item ->
            viewModel.onItemFocus(item)
        },
        onPreloadAdjacentItem = preloadAdjacentItem,
        onSaveFocusState = saveModernFocusState,
        onAtTopChanged = { isAtTop = it },
        onCarouselOpenRequested = { isCarouselFocused = true },
        isCarouselFocused = isCarouselFocused,
        selectedPlatformId = selectedPlatformId,
        aggregatePlatformsEnabled = aggregatePlatformsEnabled,
        fastPlatformScrollEnabled = fastPlatformScrollEnabled,
        showAllCatalogsOnHome = uiState.showAllCatalogsOnHome,
        fullWidthIconRowEnabled = fullWidthIconRowEnabled,
        carouselGradientAlpha = carouselAlpha,
        onHeroTrailerPlayingChanged = { isHeroTrailerPlaying = it },
        platformNavDirection = platformNavDirection
    )
    }

    // Carousel slides in from top once ready, then stays visible always
    // Streaming platform carousel overlay
    StreamingPlatformCarousel(
        selectedPlatformId = selectedPlatformId,
        visiblePlatformIds = if (aggregatePlatformsEnabled) stablePlatformIds else emptySet(),
        isCarouselFocused = isCarouselFocused,
        onCarouselFocusChanged = { isCarouselFocused = it },
        onPlatformSelected = {
            selectedPlatformId = it
        },
        onNavigationDirection = { dir ->
            platformNavDirection = dir
        },
        focusRequester = carouselFocusRequester,
        fullWidthMode = fullWidthIconRowEnabled,
        dimOnRowExit = uiState.dimIconsOnRowExitEnabled,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .fillMaxWidth(if (fullWidthIconRowEnabled) 1f else 0.55f)
            .padding(top = 8.dp, end = if (fullWidthIconRowEnabled) 0.dp else 20.dp, start = if (fullWidthIconRowEnabled) 20.dp else 0.dp)
            .graphicsLayer { alpha = carouselAlpha }
    )
    } // end Box
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomePosterOptionsDialog(
    title: String,
    isInLibrary: Boolean,
    isLibraryPending: Boolean,
    showManageLists: Boolean,
    isFromMyList: Boolean = false,
    isMovie: Boolean,
    isWatched: Boolean,
    isWatchedPending: Boolean,
    onDismiss: () -> Unit,
    onDetails: () -> Unit,
    onToggleLibrary: () -> Unit,
    onToggleWatched: () -> Unit
) {
    val primaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        primaryFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = stringResource(R.string.home_poster_dialog_subtitle)
    ) {
        Button(
            onClick = onDetails,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(primaryFocusRequester),
            colors = ButtonDefaults.colors(
                containerColor = NuvioColors.BackgroundCard,
                contentColor = NuvioColors.TextPrimary
            )
        ) {
            Text(stringResource(R.string.cw_action_go_to_details))
        }

        Button(
            onClick = onToggleLibrary,
            enabled = !isLibraryPending,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.colors(
                containerColor = NuvioColors.BackgroundCard,
                contentColor = NuvioColors.TextPrimary
            )
        ) {
            Text(
                if (isInLibrary) {
                    stringResource(R.string.hero_remove_from_library)
                } else {
                    stringResource(R.string.hero_add_to_library)
                }
            )
        }

        if (isMovie) {
            Button(
                onClick = onToggleWatched,
                enabled = !isWatchedPending,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    contentColor = NuvioColors.TextPrimary
                )
            ) {
                Text(
                    if (isWatched) {
                        stringResource(R.string.hero_mark_unwatched)
                    } else {
                        stringResource(R.string.hero_mark_watched)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomeLibraryListPickerDialog(
    title: String,
    tabs: List<LibraryListTab>,
    membership: Map<String, Boolean>,
    isPending: Boolean,
    error: String?,
    onToggle: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    val primaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        primaryFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = stringResource(R.string.detail_lists_subtitle),
        width = 500.dp
    ) {
        if (!error.isNullOrBlank()) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFFFB6B6)
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(tabs, key = { it.key }) { tab ->
                val selected = membership[tab.key] == true
                val titleText = if (selected) "\u2713 ${tab.title}" else tab.title
                Button(
                    onClick = { onToggle(tab.key) },
                    enabled = !isPending,
                    modifier = if (tab.key == tabs.firstOrNull()?.key) {
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(primaryFocusRequester)
                    } else {
                        Modifier.fillMaxWidth()
                    },
                    colors = ButtonDefaults.colors(
                        containerColor = if (selected) NuvioColors.FocusBackground else NuvioColors.BackgroundCard,
                        contentColor = NuvioColors.TextPrimary
                    )
                ) {
                    Text(
                        text = titleText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Divider(color = NuvioColors.Border, thickness = 1.dp)

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Button(
                onClick = onSave,
                enabled = !isPending,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    contentColor = NuvioColors.TextPrimary
                )
            ) {
                Text(if (isPending) stringResource(R.string.action_saving) else stringResource(R.string.action_save))
            }
        }
    }
}
