package com.nuvio.tv.ui.screens.home

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
import com.nuvio.tv.ui.components.PulsingLogoIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.HomePopupGlassEnvironment
import com.nuvio.tv.ui.components.LocalHomePopupGlassEnvironment
import com.nuvio.tv.ui.components.glassDialogFocusTransform
import com.nuvio.tv.ui.components.GlassDialogAnimatedPanel
import com.nuvio.tv.ui.components.WatchedRatingOverlay
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.PosterCardStyle
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.LocalCarouselFocusRequester
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.LocalRowFocusRestorer
import kotlin.math.roundToInt
import androidx.compose.animation.fadeOut
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild

private data class HomePosterOptionsTarget(
    val item: MetaPreview,
    val addonBaseUrl: String,
    val isFromMyList: Boolean = false
)

private val HomeDialogGlassRowColor = Color.Transparent
private val HomeDialogGlassRowFocusedColor = Color.White.copy(alpha = 0.16f)
private val HomeDialogGlassBrush = Brush.verticalGradient(
    colors = listOf(
        Color(0xAD2A3038),
        Color(0x9E20252C),
        Color(0xA824292F)
    )
)
private val HomeDialogGlassBorderColor = Color.White.copy(alpha = 0.09f)

private const val HOME_COLD_REVEAL_SENTINEL = "home_cold_reveal_seen"
private var coldHomeRevealClaimedInProcess = false

private fun claimColdHomeReveal(context: android.content.Context): Boolean {
    // Only the first Home instance in this process may claim the cold reveal.
    // This guarantees profile changes in the same process use 450ms.
    if (coldHomeRevealClaimedInProcess) return false
    coldHomeRevealClaimedInProcess = true

    val sentinel = context.cacheDir.resolve(HOME_COLD_REVEAL_SENTINEL)
    val isColdCacheLaunch = !sentinel.exists()

    if (isColdCacheLaunch) {
        runCatching { sentinel.createNewFile() }
    }

    return isColdCacheLaunch
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    skipReturnCurtain: Boolean = false,
    returnFrameSignalActive: Boolean = false,
    onReturnFrameDrawn: () -> Unit = {},
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

    val seasonalSpotlightStageReady by
        viewModel.seasonalSpotlightStageReady
            .collectAsStateWithLifecycle()

    /*
     * Consume only a replacement that was already completely staged when
     * this Home entry began. If staging finishes after Home is visible, keep
     * the current block stable and defer that replacement to the next return.
     */
    val seasonalSpotlightStageReadyAtEntry =
        remember {
            seasonalSpotlightStageReady
        }

    var seasonalSpotlightReturnSwapSettled by
        remember {
            mutableStateOf(
                !seasonalSpotlightStageReadyAtEntry
            )
        }

    androidx.compose.runtime.DisposableEffect(
        viewModel
    ) {
        viewModel.setHomePresentationVisible(
            true
        )

        onDispose {
            /*
             * Home is no longer being navigated. Pending Seasonal Spotlight
             * preparation may now use network/TMDB/image decode resources.
             */
            viewModel.setHomePresentationVisible(
                false
            )
        }
    }

    LaunchedEffect(
        seasonalSpotlightStageReadyAtEntry
    ) {
        if (
            seasonalSpotlightStageReadyAtEntry
        ) {
            viewModel
                .applyStagedSeasonalSpotlightIfReady()

            /*
             * The map/order/UI-state swap happens synchronously above.
             * Keep any existing return cover through one real frame so the
             * replacement Home tree is committed before it can be revealed.
             */
            androidx.compose.runtime.withFrameNanos {
            }

            seasonalSpotlightReturnSwapSettled =
                true
        }
    }

    val playerReturnRequestedGeneration by
        viewModel.playerReturnCwRequestedGenerationState.collectAsStateWithLifecycle()
    val playerReturnSettledGeneration by
        viewModel.playerReturnCwSettledGeneration.collectAsStateWithLifecycle()

    val playerReturnPending =
        playerReturnRequestedGeneration > playerReturnSettledGeneration

    /*
     * Direct Player -> Home returns stay fully opaque until the exact CW
     * generation armed before Player release has survived the complete CW
     * pipeline, including enrichment.
     *
     * Initial alpha/visibility are derived synchronously from pending state so
     * a newly recomposed Home cannot expose one stale frame before LaunchedEffect
     * gets a chance to run.
     */
    val playerReturnCurtainAlpha = remember {
        androidx.compose.animation.core.Animatable(
            if (playerReturnPending) 1f else 0f
        )
    }
    var showPlayerReturnCurtain by remember {
        mutableStateOf(playerReturnPending)
    }

    LaunchedEffect(
        playerReturnPending,
        playerReturnRequestedGeneration,
        seasonalSpotlightReturnSwapSettled
    ) {
        if (playerReturnPending) {
            showPlayerReturnCurtain = true
            playerReturnCurtainAlpha.snapTo(1f)
        } else if (
            showPlayerReturnCurtain &&
            seasonalSpotlightReturnSwapSettled
        ) {
            /*
             * Settlement is published after the CW pipeline's final state update.
             * Keep the curtain through one rendered frame so the final Home
             * projection is actually committed before the dissolve begins.
             */
            androidx.compose.runtime.withFrameNanos { }
            playerReturnCurtainAlpha.animateTo(
                targetValue = 0f,
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = 350
                )
            )
            showPlayerReturnCurtain = false
        }
    }

    val context = LocalContext.current

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
    val platformBackdropsPreloaded by viewModel.platformBackdropsPreloaded.collectAsStateWithLifecycle()
    val heroBackdropWarm by viewModel.heroBackdropWarm.collectAsStateWithLifecycle()
    /*
     * Kick the ViewModel-side warm as soon as layout prefs are known (they pick
     * the rows-viewport fraction, so the hero size depends on them). This also
     * seeds backdropPreloadWidthPx/HeightPx, which unblocks the platform
     * backdrop preload that previously could not run before this screen
     * composed.
     */
    LaunchedEffect(
        uiState.homeLoadSessionId,
        uiState.layoutPreferencesReady,
        uiState.modernLandscapePostersEnabled
    ) {
        if (uiState.layoutPreferencesReady) {
            viewModel.warmFirstHeroBackdrop(uiState.modernLandscapePostersEnabled)
        }
    }
    // No home screen gate on backdrop preload — preloadPlatformBackdrops has its own
    // 3s internal timeout so it always completes. Icons are gated on backdropsPreloaded
    // via stablePlatformIds below and all appear at once when preload finishes.
    // Safety net for the initial-rows-enrichment gate: some items may never receive an
    // ageRating or status (TMDB has no data for them), which would otherwise block the
    // gate forever. Force-release after 6s regardless.
    var initialRowsEnrichmentGateReleased by rememberSaveable(
        uiState.homeLoadSessionId
    ) {
        mutableStateOf(false)
    }
    LaunchedEffect(uiState.homeLoadSessionId) {
        kotlinx.coroutines.delay(10_000L)
        initialRowsEnrichmentGateReleased = true
    }
    var showHomeContentWithAnimation by rememberSaveable(
        uiState.homeLoadSessionId
    ) {
        mutableStateOf(false)
    }
    var posterOptionsTarget by remember(uiState.homeLoadSessionId) {
        mutableStateOf<HomePosterOptionsTarget?>(null)
    }
    val homePopupHazeState = remember { HazeState() }
    val homePopupBlurEnabled =
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    var continueWatchingPopupVisible by remember { mutableStateOf(false) }
    var preserveCatalogTrailerPlayback by remember { mutableStateOf(false) }

    /*
     * Mark Watched is asynchronous. The options Dialog disappears before the
     * ViewModel may publish showWatchedRatingOverlay=true.
     *
     * Keep that interval inside the SAME popup session so an already-playing
     * expanded-card trailer is not collapsed between the two Dialogs.
     */
    var watchedRatingHandoffStatusKey by remember {
        mutableStateOf<String?>(null)
    }
    var watchedRatingHandoffObservedPending by remember {
        mutableStateOf(false)
    }

    val watchedRatingHandoffActive =
        watchedRatingHandoffStatusKey != null

    val homePopupVisible =
        posterOptionsTarget != null ||
            uiState.showWatchedRatingOverlay ||
            watchedRatingHandoffActive ||
            continueWatchingPopupVisible
    val homeContentFocusRequester = LocalContentFocusRequester.current
    val homeRowFocusRestorer = LocalRowFocusRestorer.current
    var homePopupWasVisible by remember { mutableStateOf(false) }

    /*
     * Keeps an already-running catalog trailer protected only while the
     * modal popup is handing focus back to its originating poster.
     */
    var catalogOptionsFocusRestoreActive by remember {
        mutableStateOf(false)
    }

    var homePopupReturnFocusRequester by remember {
        mutableStateOf<androidx.compose.ui.focus.FocusRequester?>(null)
    }

    LaunchedEffect(
        watchedRatingHandoffStatusKey,
        uiState.showWatchedRatingOverlay,
        uiState.movieWatchedPending,
        uiState.movieWatchedStatus
    ) {
        val handoffKey =
            watchedRatingHandoffStatusKey
                ?: return@LaunchedEffect

        /*
         * Rating Dialog has appeared. It now keeps homePopupVisible /
         * catalogOptionsVisible true itself, so the temporary bridge can drop.
         */
        if (uiState.showWatchedRatingOverlay) {
            watchedRatingHandoffStatusKey = null
            watchedRatingHandoffObservedPending = false
            return@LaunchedEffect
        }

        /*
         * Wait until Home has actually observed the ViewModel's watched job.
         * This prevents the bridge from clearing in the tiny interval between
         * arming it locally and StateFlow delivering movieWatchedPending.
         */
        if (handoffKey in uiState.movieWatchedPending) {
            watchedRatingHandoffObservedPending = true
            return@LaunchedEffect
        }

        /*
         * The watched job has finished.
         *
         * If no rating Dialog was produced (no connected rating provider,
         * failure, etc.), end the popup session normally.
         */
        if (
            watchedRatingHandoffObservedPending ||
            uiState.movieWatchedStatus[handoffKey] == true
        ) {
            watchedRatingHandoffStatusKey = null
            watchedRatingHandoffObservedPending = false
        }
    }

    LaunchedEffect(homePopupVisible) {
        if (homePopupVisible) {
            catalogOptionsFocusRestoreActive = false

            if (!homePopupWasVisible) {
                homePopupReturnFocusRequester =
                    homeRowFocusRestorer.value
                        .takeUnless {
                            it == androidx.compose.ui.focus.FocusRequester.Default
                        }
            }
            homePopupWasVisible = true
        } else if (homePopupWasVisible) {
            /*
             * A preserved expanded-card trailer must survive the few frames
             * needed to detach the Dialog focus owner and return exact focus.
             */
            catalogOptionsFocusRestoreActive =
                preserveCatalogTrailerPlayback

            val returnRequester = homePopupReturnFocusRequester

            var restoredExactItem = false

            repeat(3) {
                androidx.compose.runtime.withFrameNanos { }

                if (!restoredExactItem && returnRequester != null) {
                    restoredExactItem =
                        runCatching {
                            returnRequester.requestFocus()
                        }.getOrDefault(false)
                }
            }

            if (!restoredExactItem) {
                runCatching {
                    homeContentFocusRequester.requestFocus()
                }
            }

            homePopupReturnFocusRequester = null
            homePopupWasVisible = false
            catalogOptionsFocusRestoreActive = false

            /*
             * Preservation is per popup SESSION.
             *
             * Without resetting this here, opening options once while a
             * trailer is already playing leaves preserve=true indefinitely,
             * causing future pre-autoplay popups to incorrectly preserve
             * trailers instead of suppressing them.
             *
             * Do this only after the complete popup/rating chain has closed
             * and exact Home focus restoration has finished.
             */
            preserveCatalogTrailerPlayback = false
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

    CompositionLocalProvider(
        LocalHomePopupGlassEnvironment provides HomePopupGlassEnvironment(
            hazeState = homePopupHazeState,
            blurEnabled = homePopupBlurEnabled,
            onPopupVisibilityChanged = { visible ->
                continueWatchingPopupVisible = visible
            },
            // Keep the same trailer policy through the rating prompt
            // launched by Mark Watched.
            catalogOptionsVisible =
                posterOptionsTarget != null ||
                    uiState.showWatchedRatingOverlay ||
                    watchedRatingHandoffActive,
            preserveCatalogTrailerPlayback = preserveCatalogTrailerPlayback,
            catalogOptionsFocusRestoreActive =
                catalogOptionsFocusRestoreActive,
            onCatalogOptionsOpening = { preservePlayback ->
                preserveCatalogTrailerPlayback = preservePlayback
            }
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (homePopupBlurEnabled && homePopupVisible) {
                        Modifier.haze(homePopupHazeState)
                } else {
                    Modifier
                }
            )
    ) {
        val hasAnyContent = uiState.catalogRows.isNotEmpty() ||
            uiState.continueWatchingItems.isNotEmpty() ||
            uiState.heroItems.isNotEmpty()

        when {
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
                // Gate on catalogsReady (all catalog rows finished loading their items —
                // not TMDB enrichment, which runs separately in the background).
                // This is a one-way monotonic flip so it can't regress back to false.
                /*
                 * Hero items arrive in a later emission than skeletonReady, so
                 * releasing on skeleton alone let the backdrop snap in with
                 * metadata still unrendered on cold launch. Profile switches
                 * looked correct only because the VM survived with hero data
                 * already in state. Independent 2.5s timeout: if hero never
                 * arrives we degrade to the old behaviour rather than hang.
                 * Do NOT add platformBackdropsPreloaded here - that flag is
                 * set only after ModernHomeContent reports its render size,
                 * which cannot happen until this gate releases (deadlock).
                 */
                var heroGateTimedOut by remember(
                    uiState.homeLoadSessionId
                ) {
                    mutableStateOf(false)
                }
                LaunchedEffect(uiState.homeLoadSessionId) {
                    kotlinx.coroutines.delay(2_500L)
                    heroGateTimedOut = true
                }
                val heroGateSatisfied = !uiState.heroSectionEnabled ||
                    uiState.heroItems.isNotEmpty() ||
                    heroGateTimedOut
                /*
                 * Release only once the hero backdrop is in Coil and the
                 * platform backdrops are preloaded, so the backdrop fades in
                 * from cache (instead of snapping), hero metadata is already
                 * composed, and the icon row is present at reveal. Both flags
                 * are self-releasing: heroBackdropWarm on a 2.5s bound in the
                 * VM, platformBackdropsPreloaded on the 3s bound in
                 * preloadPlatformBackdrops. The local 6s backstop may relax
                 * those media warmups, but never the platform-row draw gate.
                 */
                var gateBackstopElapsed by remember(
                    uiState.homeLoadSessionId
                ) {
                    mutableStateOf(false)
                }
                LaunchedEffect(uiState.homeLoadSessionId) {
                    kotlinx.coroutines.delay(6_000L)
                    gateBackstopElapsed = true
                }
                var platformChromeCommitted by remember(
                    uiState.homeLoadSessionId
                ) {
                    mutableStateOf(
                        uiState.stableVisiblePlatformIds.isNotEmpty()
                    )
                }
                /*
                 * The platform row lives outside ModernHomeContent, so wait
                 * until it has committed once before releasing the curtain.
                 * If there are truly no platform IDs, catalogsReady plus the
                 * preload flag remains the fallback "nothing to draw" path.
                 */
                val platformChromeReady =
                    !uiState.aggregateStreamingPlatformsEnabled ||
                        uiState.homeLayout != HomeLayout.MODERN ||
                        platformChromeCommitted ||
                        (
                            uiState.catalogsReady &&
                                platformBackdropsPreloaded &&
                                uiState.stableVisiblePlatformIds.isEmpty()
                            )
                val mediaWarmupReady = gateBackstopElapsed ||
                    (heroBackdropWarm && platformBackdropsPreloaded)
                val warmupSatisfied =
                    platformChromeReady && mediaWarmupReady
                val shouldShowLoadingGate = !uiState.skeletonReady ||
                    !uiState.layoutPreferencesReady ||
                    !warmupSatisfied ||
                    !heroGateSatisfied

                // Strict loader sequence with whole-cycle dismissal:
                // LOADING -> (data ready, wait for next sweep boundary) -> FADING
                //         -> (fade-out actually finished) -> REVEAL (home fades in)
                // 0 = LOADING, 1 = FADING, 2 = REVEAL. Monotonic; never regresses.
                // Seed from initial readiness: if data is ALREADY ready at first
                // composition (e.g. back-navigation from details with the VM still
                // alive), skip straight to REVEAL so the loader doesn't replay. The
                // loader only runs when we genuinely start not-ready (cold start /
                // profile switch). remember{} captures the value at first composition.
                val initiallyReady = remember(
                    uiState.homeLoadSessionId
                ) {
                    !shouldShowLoadingGate
                }
                val useColdLaunchReveal = remember(
                    uiState.homeLoadSessionId
                ) {
                    !initiallyReady && claimColdHomeReveal(context)
                }
                val homeRevealDurationMs = if (useColdLaunchReveal) 900 else 450
                var loaderPhase by remember(
                    uiState.homeLoadSessionId
                ) {
                    mutableStateOf(if (initiallyReady) 2 else 0)
                }
                val dataReady = !shouldShowLoadingGate

                var cachedViewportPredecodeStarted by remember(
                    uiState.homeLoadSessionId
                ) {
                    mutableStateOf(false)
                }

                // Overlay visibility as a transition state so we can detect when the
                // fade-out has fully completed before revealing content. Seeded hidden
                // when already ready (no loader on back-nav).
                val overlayState = remember(
                    uiState.homeLoadSessionId
                ) {
                    androidx.compose.animation.core.MutableTransitionState(!initiallyReady)
                }

                // Grace gate: only *commit* to showing the loader if we're still
                // not-ready after a short grace window. This absorbs the 1-2 frame
                // gap on back-navigation (where uiState briefly reads not-ready
                // before the live, already-loaded state propagates), preventing a
                // dots flash. A sustained cold start / profile switch stays not-ready
                // through the grace and shows the loader as intended.
                var graceElapsed by remember(
                    uiState.homeLoadSessionId
                ) {
                    mutableStateOf(initiallyReady)
                }
                LaunchedEffect(uiState.homeLoadSessionId) {
                    if (!initiallyReady) {
                        kotlinx.coroutines.delay(120)
                        graceElapsed = true
                    }
                }
                /*
                 * On a cached process restart, refill Coil's process-local
                 * memory cache while the existing loader remains visible.
                 * Loader timing is intentionally unchanged in this patch.
                 */
                LaunchedEffect(
                    dataReady,
                    graceElapsed,
                    uiState.catalogRows
                ) {
                    if (
                        !initiallyReady &&
                        dataReady &&
                        graceElapsed &&
                        !cachedViewportPredecodeStarted &&
                        uiState.catalogRows.any {
                            it.items.isNotEmpty()
                        }
                    ) {
                        cachedViewportPredecodeStarted = true
                        viewModel.preloadCachedHomeViewport()
                    }
                }

                // If data becomes ready during the grace, skip the loader entirely:
                // hide the overlay (so dots never render) and jump to REVEAL.
                LaunchedEffect(
                    uiState.homeLoadSessionId,
                    dataReady
                ) {
                    if (dataReady && !graceElapsed && loaderPhase == 0) {
                        overlayState.targetState = false
                        loaderPhase = 2
                    }
                }

                // Begin fade-out when we enter FADING.
                LaunchedEffect(
                    uiState.homeLoadSessionId,
                    loaderPhase
                ) {
                    if (loaderPhase >= 1) overlayState.targetState = false
                }
                // When the fade-out animation is fully idle and hidden, reveal content.
                LaunchedEffect(
                    uiState.homeLoadSessionId,
                    overlayState.isIdle,
                    overlayState.currentState
                ) {
                    if (loaderPhase == 1 && overlayState.isIdle && !overlayState.currentState) {
                        loaderPhase = 2
                    }
                }
                // Backstop only: the indicator's atomic loop drives dismissal via
                // onDismissReady at a cycle boundary. This long fallback exists solely
                // in case the indicator never composes, so we can't hang forever.
                LaunchedEffect(
                    uiState.homeLoadSessionId,
                    dataReady,
                    loaderPhase
                ) {
                    if (dataReady && loaderPhase == 0) {
                        kotlinx.coroutines.delay(12000)
                        if (loaderPhase == 0) loaderPhase = 1
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    /*
                     * Content mounts and renders unconditionally now, instead
                     * of waiting for loaderPhase == 2. It lays out - and the
                     * hero backdrop, catalog rows, and platform icons all
                     * start their image requests - while the curtain below
                     * (drawn after this in the same Box, so already on top)
                     * is still opaque. The curtain's fadeOut is the only
                     * reveal animation left: it now dissolves over content
                     * that's already complete, instead of revealing nothing
                     * and having content pop in and fade on its own right
                     * after.
                     */
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
                                        isFromMyList = viewModel.isInWatchlist(item.id, item.apiType)
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
                                        isFromMyList = viewModel.isInWatchlist(item.id, item.apiType)
                                    )
                                }
                            )

                            HomeLayout.MODERN -> ModernHomeRoute(
                                viewModel = viewModel,
                                uiState = uiState,
                                skipReturnCurtain = skipReturnCurtain,
                                returnFrameSignalActive = returnFrameSignalActive,
                                onReturnFrameDrawn = {
                                    if (
                                        seasonalSpotlightReturnSwapSettled
                                    ) {
                                        onReturnFrameDrawn()
                                    }
                                },
                                onPlatformChromeCommitted = {
                                    platformChromeCommitted = true
                                },
                                onNavigateToDetail = onNavigateToDetail,
                                onContinueWatchingClick = onContinueWatchingClick,
                                onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
                                onContinueWatchingPlayManually = onContinueWatchingPlayManually,
                                showContinueWatchingManualPlayOption = effectiveAutoplayEnabled,
                                isCatalogItemWatched = remember(
                                    uiState.movieWatchedStatus,
                                    uiState.seriesWatchedStatus
                                ) {
                                    val movieStatus = uiState.movieWatchedStatus
                                    val seriesStatus = uiState.seriesWatchedStatus
                                    { item ->
                                        val key = homeItemStatusKey(item.id, item.apiType)
                                        movieStatus[key] == true ||
                                            seriesStatus[key] == true
                                    }
                                },
                                onCatalogItemLongPress = { item, addonBaseUrl ->
                                    val statusKey = homeItemStatusKey(item.id, item.apiType)
                                    posterOptionsTarget = HomePosterOptionsTarget(
                                        item = item,
                                        addonBaseUrl = addonBaseUrl,
                                        isFromMyList = viewModel.isInWatchlist(item.id, item.apiType)
                                    )
                                }
                            )
                        }
                // Loader overlay. Driven by a transition state so REVEAL only
                // starts once this fade-out is fully idle. Content is NOT visible
                // during the fade, so loader animation and home never contend.
                // Home is already fully rendered behind this opaque curtain.
                // Android Clear cache removes the sentinel, so the next genuine
                // cold-cache reveal uses 900ms. Warm launches and profile changes
                // use 450ms. No timing or Coil-cache heuristic is involved.
                AnimatedVisibility(
                    visibleState = overlayState,
                    enter = fadeIn(animationSpec = tween(150)),
                    exit = fadeOut(animationSpec = tween(homeRevealDurationMs)),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(NuvioColors.Background),
                        contentAlignment = Alignment.Center
                    ) {
                        PulsingLogoIndicator(
                            active = overlayState.currentState,
                            // Ask the loader to dismiss once data is ready; it keeps
                            // playing whole cycles and only stops at a boundary.
                            dismissRequested = dataReady,
                            onDismissReady = {
                                // Fired at a cycle boundary (all dots at rest) once
                                // dismissal was requested. Now it's safe to fade out.
                                if (loaderPhase == 0) loaderPhase = 1
                            }
                        )
                    }
                }
                }
            }
        }
        }
    }

    val userMessage = uiState.userMessage
    if (userMessage != null) {
        androidx.compose.foundation.layout.Box(
            modifier = androidx.compose.ui.Modifier.fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.BottomCenter
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = androidx.compose.ui.Modifier
                    .padding(bottom = 8.dp)
                    .background(
                        color = if (userMessage.isError) androidx.compose.ui.graphics.Color(0xFF5A1C1C)
                        else com.nuvio.tv.ui.theme.NuvioColors.BackgroundElevated,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                androidx.compose.material3.Text(
                    text = userMessage.message,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = com.nuvio.tv.ui.theme.NuvioColors.TextPrimary
                )
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
            hazeState = homePopupHazeState,
            blurEnabled = homePopupBlurEnabled,
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
                val wasAlreadyWatched =
                    uiState.movieWatchedStatus[statusKey] == true

                if (!wasAlreadyWatched) {
                    /*
                     * Arm this synchronously before removing the options Dialog.
                     * The ViewModel's rating overlay is published later from its
                     * asynchronous watched-status job.
                     */
                    watchedRatingHandoffStatusKey = statusKey
                    watchedRatingHandoffObservedPending = false
                }

                viewModel.togglePosterMovieWatched(item)
                posterOptionsTarget = null
            }
        )
    }

    WatchedRatingOverlay(
        visible = uiState.showWatchedRatingOverlay,
        hazeState = homePopupHazeState,
        blurEnabled = homePopupBlurEnabled,
        onRate = { rating -> viewModel.submitWatchedRating(rating) },
        onDismiss = { viewModel.dismissWatchedRating() }
    )

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

    /*
     * Outermost Home-level Player return curtain.
     *
     * This deliberately lives outside Classic/Grid/Modern route implementations
     * and after Home's normal overlays. It is absent from composition during
     * ordinary Home use, so it cannot add work to poster/D-pad scroll paths.
     */
    if (showPlayerReturnCurtain) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = playerReturnCurtainAlpha.value
                }
                .background(NuvioColors.Background)
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
    skipReturnCurtain: Boolean,
    returnFrameSignalActive: Boolean,
    onReturnFrameDrawn: () -> Unit,
    onPlatformChromeCommitted: () -> Unit,
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
    var selectedPlatformId by remember(
        uiState.homeLoadSessionId
    ) {
        mutableStateOf(focusState.selectedPlatformId)
    }
    var platformNavDirection by remember(
        uiState.homeLoadSessionId
    ) {
        mutableStateOf(0)
    }
    // Physical dpad state on the platform carousel: blocks the transition's
    // quiet gate while a key is held. Timestamp refreshes on every hold move;
    // 800ms staleness fallback means a swallowed KeyUp can never wedge it.
    val platformDpadHeld = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val platformDpadActivityAt = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    LaunchedEffect(platformNavDirection) {
        if (platformNavDirection != 0) {
            kotlinx.coroutines.delay(100)
            platformNavDirection = 0
        }
    }
    var isCarouselFocused by remember { mutableStateOf(false) }
    var keepPlatformRowVisibleForSidebar by remember {
        mutableStateOf(false)
    }
    val aggregatePlatformsEnabled = uiState.aggregateStreamingPlatformsEnabled
    val fullWidthIconRowEnabled = uiState.fullWidthIconRowEnabled
    val heroMetadataLarge = uiState.heroMetadataLarge
    var isAtTop by remember { mutableStateOf(true) }
    val carouselFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    // Load cached platform ids so carousel shows instantly on cold launch
    val cachedPlatformIds by viewModel.getCachedVisiblePlatformIds()
        .collectAsStateWithLifecycle(initialValue = emptySet())
    // Use stable set from ViewModel (set once when loading completes), fall back to cache.
    // On fresh cache clear cachedPlatformIds is empty, so gate on platformBackdropsPreloaded
    // to ensure backdrops are in Coil before icons appear. On normal launch cachedPlatformIds
    // is already populated so icons show immediately without waiting for preload.
    val backdropsPreloaded by viewModel.platformBackdropsPreloaded.collectAsStateWithLifecycle()
    // On warm launch: cachedPlatformIds is populated from DataStore instantly.
    // On cold launch after cache clear: cachedPlatformIds is empty, so wait for
    // backdropsPreloaded (set by triggerPlatformPreloadIfReady when platform catalogs
    // resolve and backdrops are in Coil) then use stableVisiblePlatformIds.
    val stablePlatformIds = when {
        // Always gate on backdropsPreloaded — cachedPlatformIds from DataStore
        // survives cache clears so we can't use it until backdrops are actually ready.
        !backdropsPreloaded -> emptySet()
        uiState.stableVisiblePlatformIds.isNotEmpty() -> uiState.stableVisiblePlatformIds
        cachedPlatformIds.isNotEmpty() -> cachedPlatformIds
        else -> emptySet()
    }
    /*
     * Shared hero-backdrop alpha for the platform chrome.
     *
     * ModernHomeContent owns the authoritative backdrop/trailer crossfade.
     * The icon row reads this state only from its graphics layer, so trailer
     * fades do not require a second independently-timed Compose animation.
     */
    val heroChromeBackdropAlpha = remember(
        uiState.homeLoadSessionId
    ) {
        androidx.compose.runtime.mutableFloatStateOf(1f)
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            viewModel.setHomeHeroTrailerPlaying(false)
        }
    }
    // Save to cache whenever live data is ready
    LaunchedEffect(uiState.stableVisiblePlatformIds) {
        if (uiState.stableVisiblePlatformIds.isNotEmpty()) {
            viewModel.saveCachedVisiblePlatformIds(uiState.stableVisiblePlatformIds)
        }
    }

    val homeReturnContentAlpha = remember(skipReturnCurtain) {
        androidx.compose.animation.core.Animatable(
            1f // frozen Details overlay owns return dissolve
        )
    }

    LaunchedEffect(skipReturnCurtain) {
        if (skipReturnCurtain) {
            homeReturnContentAlpha.animateTo(
                targetValue = 1f,
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = 350
                )
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = homeReturnContentAlpha.value
            }
            .then(
                if (returnFrameSignalActive) {
                    Modifier.drawWithContent {
                        // Draw the entire live Home first.
                        drawContent()

                        // Only then tell the frozen Details overlay that
                        // it is safe to begin its 350ms dissolve.
                        onReturnFrameDrawn()
                    }
                } else {
                    Modifier
                }
            )
    ) {
    val homeReturnCurtainAlpha = remember {
        androidx.compose.animation.core.Animatable(1f)
    }
    var showHomeReturnCurtain by remember {
        mutableStateOf(!skipReturnCurtain)
    }

    LaunchedEffect(Unit) {
        homeReturnCurtainAlpha.animateTo(
            targetValue = 0f,
            animationSpec = androidx.compose.animation.core.tween(
                durationMillis = 350
            )
        )
        showHomeReturnCurtain = false
    }

    /*
     * Initial Home owns the reveal animation. The platform row is either fully
     * rendered beneath that curtain or absent; giving it another 400 ms fade
     * is what allowed it to visibly trail the rest of the screen.
     */
    val carouselAlpha =
        if (stablePlatformIds.isNotEmpty() && aggregatePlatformsEnabled) 1f else 0f

    /*
     * Optional focus-only platform chrome.
     *
     * Hidden state keeps the carousel in layout/focus traversal so D-pad Up can
     * still enter it normally; only its rendered position moves above the
     * screen. The top gradient follows the same reveal target with a slightly
     * slower independent fade.
     *
     * Do not fold heroChromeBackdropAlpha into this animation: Hero Media
     * trailer transitions already own that separate fade.
     */
    val platformRowRevealTarget =
        if (
            !uiState.hidePlatformIconsOnRowExitEnabled ||
            isCarouselFocused ||
            keepPlatformRowVisibleForSidebar
        ) {
            1f
        } else {
            0f
        }

    val platformRowRevealProgress =
        androidx.compose.animation.core.animateFloatAsState(
            targetValue = platformRowRevealTarget,
            animationSpec =
                androidx.compose.animation.core.tween(
                    durationMillis = 240,
                    easing =
                        androidx.compose.animation.core.FastOutSlowInEasing
                ),
            label = "platformRowRevealProgress"
        )

    val platformRowHiddenOffsetPx =
        with(androidx.compose.ui.platform.LocalDensity.current) {
            64.dp.toPx()
        }

    val platformGradientRevealProgress =
        androidx.compose.animation.core.animateFloatAsState(
            targetValue = platformRowRevealTarget,
            animationSpec =
                androidx.compose.animation.core.tween(
                    durationMillis = 400,
                    easing =
                        androidx.compose.animation.core.FastOutSlowInEasing
                ),
            label = "platformGradientRevealProgress"
        )

    /*
     * Keep the platform-row animation read in graphics-layer phase so the
     * icon slide does not trigger unnecessary composition work.
     */
    val platformChromeTranslationY =
        remember(
            platformRowRevealProgress,
            platformRowHiddenOffsetPx
        ) {
            {
                -(1f - platformRowRevealProgress.value) *
                    platformRowHiddenOffsetPx
            }
        }

    val platformGradientRevealAlpha =
        remember(platformGradientRevealProgress) {
            { platformGradientRevealProgress.value }
        }

    val platformChromeDrawReported = remember(
        uiState.homeLoadSessionId
    ) {
        java.util.concurrent.atomic.AtomicBoolean(false)
    }

    CompositionLocalProvider(
        LocalNoBackdropImage provides uiState.focusedPosterNoBackdropImage,
        LocalCarouselFocusRequester provides carouselFocusRequester
    ) {
    ModernHomeContent(
        uiState = uiState,
        focusState = focusState,
        myListHeadResetPending = uiState.myListHeadResetPending,
        onMyListHeadResetConsumed = {
            viewModel.consumeMyListHeadReset()
        },
        enrichingItemId = enrichingItemId,
        trailerPreviewUrls = viewModel.trailerPreviewUrls,
        trailerPreviewAudioUrls = viewModel.trailerPreviewAudioUrls,
        sharedTrailerPlayer = viewModel.homeTrailerPlayerHolder.player,
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
        showAllCatalogsOnHome = uiState.showAllCatalogsOnHome,
        fullWidthIconRowEnabled = fullWidthIconRowEnabled,
        heroMetadataLarge = heroMetadataLarge,
        carouselGradientAlpha = carouselAlpha,
        platformGradientRevealAlpha =
            platformGradientRevealAlpha,
        onHeroTrailerPlayingChanged = { playing ->
            viewModel.setHomeHeroTrailerPlaying(playing)
        },
        onHeroBackdropAlphaChanged = { alpha ->
            heroChromeBackdropAlpha.floatValue = alpha
        },
        platformNavDirection = platformNavDirection,
        isPlatformDpadHeld = {
            platformDpadHeld.get() &&
                android.os.SystemClock.elapsedRealtime() - platformDpadActivityAt.get() < 800L
        },
        isAtTop = isAtTop,
        onBackdropPreloadSizeKnown = { w, h ->
            viewModel.setBackdropPreloadSize(w, h)
        }
    )
    }

    // Carousel slides in from top once ready, then stays visible always
    // Streaming platform carousel overlay
    StreamingPlatformCarousel(
        selectedPlatformId = selectedPlatformId,
        visiblePlatformIds = if (aggregatePlatformsEnabled) stablePlatformIds else emptySet(),
        isCarouselFocused = isCarouselFocused,
        onCarouselFocusChanged = { focused ->
            isCarouselFocused = focused

            /*
             * Once actual focus returns to the carousel, normal focused
             * visibility takes over and the sidebar-only hold can clear.
             */
            if (focused) {
                keepPlatformRowVisibleForSidebar = false
            }
        },
        onKeepVisibleForSidebarChanged = {
            keepPlatformRowVisibleForSidebar = it
        },
        onPlatformSelected = {
            selectedPlatformId = it
        },
        onNavigationDirection = { dir ->
            platformNavDirection = dir
            if (platformDpadHeld.get()) platformDpadActivityAt.set(android.os.SystemClock.elapsedRealtime())
        },
        onDpadHeldChanged = { held ->
            platformDpadHeld.set(held)
            if (held) platformDpadActivityAt.set(android.os.SystemClock.elapsedRealtime())
        },
        focusRequester = carouselFocusRequester,
        fullWidthMode = fullWidthIconRowEnabled,
        dimOnRowExit =
            uiState.dimIconsOnRowExitEnabled &&
                !uiState.hidePlatformIconsOnRowExitEnabled,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .fillMaxWidth(if (fullWidthIconRowEnabled) 1f else 0.55f)
            .padding(top = 8.dp, end = if (fullWidthIconRowEnabled) 0.dp else 20.dp, start = if (fullWidthIconRowEnabled) 20.dp else 0.dp)
            .drawWithContent {
                drawContent()
                if (
                    stablePlatformIds.isNotEmpty() &&
                        platformChromeDrawReported.compareAndSet(false, true)
                ) {
                    onPlatformChromeCommitted()
                }
            }
            .graphicsLayer {
                alpha =
                    carouselAlpha *
                        heroChromeBackdropAlpha.floatValue
                translationY =
                    platformChromeTranslationY()
            }
    )
    if (showHomeReturnCurtain) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = homeReturnCurtainAlpha.value
                }
                .background(NuvioColors.Background)
        )
    }

    } // end Box
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomePosterOptionsDialog(
    title: String,
    hazeState: HazeState,
    blurEnabled: Boolean,
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
    var suppressNextKeyUp by remember { mutableStateOf(true) }
    val appearanceProgress = remember {
        androidx.compose.animation.core.Animatable(0f)
    }

    LaunchedEffect(Unit) {
        runCatching { primaryFocusRequester.requestFocus() }
        appearanceProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 220,
                easing = androidx.compose.animation.core.FastOutSlowInEasing
            )
        )
    }

    /*
     * Use a real Dialog window so TV focus cannot escape back into Home,
     * posters, or the sidebar while the options bubble remains visible.
     *
     * Keep Android's default DIM_BEHIND disabled because the existing
     * haze/glass presentation provides its own background treatment.
     */
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss
    ) {
        val dialogView =
            androidx.compose.ui.platform.LocalView.current

        androidx.compose.runtime.DisposableEffect(dialogView) {
            val window =
                (
                    dialogView.parent as?
                        androidx.compose.ui.window.DialogWindowProvider
                    )?.window

            window?.clearFlags(
                android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND
            )

            onDispose { }
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
        val panelShape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
        val blurModifier = if (blurEnabled) {
            Modifier.hazeChild(
                state = hazeState,
                shape = panelShape,
                tint = Color.Unspecified,
                blurRadius = (1f + (29f * appearanceProgress.value)).dp,
                noiseFactor = 0.025f * appearanceProgress.value
            )
        } else {
            Modifier
        }

        GlassDialogAnimatedPanel(
            width = 520.dp,
            scale =
                0.96f +
                    (0.04f * appearanceProgress.value),
            alpha = appearanceProgress.value,
            shape = panelShape,
            hazeModifier = blurModifier,
            surfaceModifier = Modifier
                .background(HomeDialogGlassBrush, panelShape)
                .border(
                    1.dp,
                    HomeDialogGlassBorderColor,
                    panelShape
                ),
            contentModifier = Modifier
                .padding(24.dp)
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (
                        suppressNextKeyUp &&
                        native.action == AndroidKeyEvent.ACTION_UP &&
                        (
                            native.keyCode ==
                                AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                                native.keyCode ==
                                    AndroidKeyEvent.KEYCODE_ENTER ||
                                native.keyCode ==
                                    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER ||
                                native.keyCode ==
                                    AndroidKeyEvent.KEYCODE_MENU
                        )
                    ) {
                        suppressNextKeyUp = false
                        true
                    } else {
                        false
                    }
                }
        ) {
            androidx.compose.foundation.layout.Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = NuvioColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.home_poster_dialog_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextSecondary
                )

        Button(
            onClick = onDetails,
            modifier = Modifier
                .fillMaxWidth()
                .glassDialogFocusTransform()
                .focusRequester(primaryFocusRequester),
            colors = ButtonDefaults.colors(
                containerColor = HomeDialogGlassRowColor,
                focusedContainerColor = HomeDialogGlassRowFocusedColor,
                contentColor = NuvioColors.TextSecondary,
                focusedContentColor = NuvioColors.TextPrimary
            ),
            border = ButtonDefaults.border(
                border = androidx.tv.material3.Border.None,
                focusedBorder = androidx.tv.material3.Border.None
            ),
            shape = ButtonDefaults.shape(RoundedCornerShape(32.dp)),
            scale = ButtonDefaults.scale(
                focusedScale = 1f,
                pressedScale = 1f
            )
        ) {
            Text(stringResource(R.string.cw_action_go_to_details))
        }

        Button(
            onClick = onToggleLibrary,
            enabled = !isLibraryPending,
            modifier = Modifier
                .fillMaxWidth()
                .glassDialogFocusTransform(),
            colors = ButtonDefaults.colors(
                containerColor = HomeDialogGlassRowColor,
                focusedContainerColor = HomeDialogGlassRowFocusedColor,
                contentColor = NuvioColors.TextSecondary,
                focusedContentColor = NuvioColors.TextPrimary
            ),
            border = ButtonDefaults.border(
                border = androidx.tv.material3.Border.None,
                focusedBorder = androidx.tv.material3.Border.None
            ),
            shape = ButtonDefaults.shape(RoundedCornerShape(32.dp)),
            scale = ButtonDefaults.scale(
                focusedScale = 1f,
                pressedScale = 1f
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
                modifier = Modifier
                .fillMaxWidth()
                .glassDialogFocusTransform(),
                colors = ButtonDefaults.colors(
                    containerColor = HomeDialogGlassRowColor,
                    focusedContainerColor = HomeDialogGlassRowFocusedColor,
                    contentColor = NuvioColors.TextSecondary,
                    focusedContentColor = NuvioColors.TextPrimary
                ),
                border = ButtonDefaults.border(
                    border = androidx.tv.material3.Border.None,
                    focusedBorder = androidx.tv.material3.Border.None
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(32.dp)),
                scale = ButtonDefaults.scale(
                    focusedScale = 1f,
                    pressedScale = 1f
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
        glass = true,
        enhancedGlass = true,
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
                            .glassDialogFocusTransform()
                            .focusRequester(primaryFocusRequester)
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .glassDialogFocusTransform()
                    },
                    colors = ButtonDefaults.colors(
                        containerColor = if (selected) NuvioColors.Secondary.copy(alpha = 0.42f) else Color.Transparent,
                        focusedContainerColor = HomeDialogGlassRowFocusedColor,
                        contentColor =
                            if (selected) {
                                NuvioColors.TextPrimary
                            } else {
                                NuvioColors.TextSecondary
                            },
                        focusedContentColor = NuvioColors.TextPrimary
                    ),
                    border = ButtonDefaults.border(
                        border = androidx.tv.material3.Border.None,
                        focusedBorder = androidx.tv.material3.Border.None
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(32.dp)),
                    scale = ButtonDefaults.scale(
                        focusedScale = 1f,
                        pressedScale = 1f
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

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Button(
                onClick = onSave,
                enabled = !isPending,
                modifier = Modifier.glassDialogFocusTransform(),
                colors = ButtonDefaults.colors(
                    containerColor = Color.Transparent,
                    focusedContainerColor = HomeDialogGlassRowFocusedColor,
                    contentColor = NuvioColors.TextSecondary,
                    focusedContentColor = NuvioColors.TextPrimary
                ),
                border = ButtonDefaults.border(
                    border = androidx.tv.material3.Border.None,
                    focusedBorder = androidx.tv.material3.Border.None
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(32.dp)),
                scale = ButtonDefaults.scale(
                    focusedScale = 1f,
                    pressedScale = 1f
                )
            ) {
                Text(if (isPending) stringResource(R.string.action_saving) else stringResource(R.string.action_save))
            }
        }
    }
}
