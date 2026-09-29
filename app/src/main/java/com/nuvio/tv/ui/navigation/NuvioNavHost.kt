package com.nuvio.tv.ui.navigation

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.nuvio.tv.ui.screens.CatalogSeeAllScreen
import com.nuvio.tv.ui.screens.LayoutSelectionScreen
import com.nuvio.tv.ui.screens.detail.MetaDetailsScreen
import com.nuvio.tv.ui.screens.home.HomeScreen
import com.nuvio.tv.ui.screens.addon.AddonManagerScreen
import com.nuvio.tv.ui.screens.home.HomeViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import com.nuvio.tv.ui.screens.addon.CatalogOrderScreen
import com.nuvio.tv.ui.screens.library.LibraryScreen
import com.nuvio.tv.ui.screens.player.PlayerScreen
import com.nuvio.tv.ui.screens.plugin.PluginScreen
import com.nuvio.tv.ui.screens.search.DiscoverScreen
import com.nuvio.tv.ui.screens.search.SearchScreen
import com.nuvio.tv.ui.screens.settings.AboutScreen
import com.nuvio.tv.ui.screens.settings.LayoutSettingsScreen
import com.nuvio.tv.ui.screens.settings.PlaybackSettingsScreen
import com.nuvio.tv.ui.screens.settings.SettingsScreen
import com.nuvio.tv.ui.screens.settings.SettingsGlassScreen
import com.nuvio.tv.ui.screens.settings.SimklScreen
import com.nuvio.tv.ui.screens.settings.SupportersContributorsScreen
import com.nuvio.tv.ui.screens.settings.ThemeSettingsScreen
import com.nuvio.tv.ui.screens.settings.TrackingSettingsScreen
import com.nuvio.tv.ui.screens.settings.TraktScreen
import com.nuvio.tv.ui.screens.settings.TmdbSettingsScreen
import com.nuvio.tv.ui.screens.stream.StreamScreen
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.screens.account.AuthSignInScreen
import com.nuvio.tv.ui.screens.account.AuthQrSignInScreen
import com.nuvio.tv.ui.screens.cast.CastDetailScreen
import com.nuvio.tv.ui.screens.profile.ProfileSelectionMode
import com.nuvio.tv.ui.screens.profile.ProfileSelectionScreen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import kotlinx.coroutines.launch

private fun rootDestinationPopExit(
    destinationName: String,
    targetRoute: String?
): ExitTransition {
    if (targetRoute != Screen.Home.route) {
        return fadeOut(animationSpec = tween(350))
    }

    Log.d("RootDissolve", "$destinationName cross-dissolve -> home")
    return fadeOut(
        targetAlpha = 0f,
        animationSpec = tween(
            durationMillis = 300,
            easing = LinearEasing
        )
    )
}

/*
 * Navigation's own BackHandler is registered after the sidebar scaffold, so a
 * remote Back press on a full-screen sidebar destination otherwise pops Home
 * without first suppressing Home's dark return curtain. Register this inside
 * each destination, before the screen's own handlers: a screen can still
 * consume Back internally, while root-level Back restores Home cleanly.
 */
@Composable
private fun SidebarRootBackToHome(
    onBackToHome: () -> Unit
) {
    BackHandler(onBack = onBackToHome)
}

@Composable
private fun RecordSidebarRootFrame(
    layer: androidx.compose.ui.graphics.layer.GraphicsLayer,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawWithContent {
                layer.record {
                    this@drawWithContent.drawContent()
                }
                drawLayer(layer)
            }
    ) {
        content()
    }
}

@Composable
fun NuvioNavHost(
    navController: NavHostController,
    startDestination: String = Screen.Home.route,
    hideBuiltInHeaders: Boolean = false
) {
    /*
     * Unlike SavedStateHandle, this value invalidates the active NavHost
     * immediately before a root pop. Home therefore receives the suppression
     * on its first returning composition instead of restoring a stale value.
     */
    val suppressHomeCurtainForRootReturn =
        androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(false)
        }

    /*
     * Root destinations use the same retained Compose display-list strategy as
     * Details -> Home below. Navigation Compose can dispose a popped root
     * before its popExit pixels remain visible on some builds; this layer keeps
     * the exact final rendered frame above Home for the dissolve.
     */
    val sidebarRootReturnLayer = rememberGraphicsLayer()
    val sidebarRootReturnOverlayAlpha =
        androidx.compose.runtime.remember {
            androidx.compose.animation.core.Animatable(1f)
        }
    val showSidebarRootReturnOverlay =
        androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(false)
        }
    val sidebarRootReturnRunning =
        androidx.compose.runtime.remember {
            java.util.concurrent.atomic.AtomicBoolean(false)
        }
    val sidebarRootReturnScope =
        androidx.compose.runtime.rememberCoroutineScope()
    val sidebarRootReturnHomeDrawSignal =
        androidx.compose.runtime.remember {
            kotlinx.coroutines.channels.Channel<Unit>(
                capacity = kotlinx.coroutines.channels.Channel.CONFLATED
            )
        }
    val sidebarRootReturnAwaitingHomeDraw =
        androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(false)
        }

    // DETAIL_RETURN_FROZEN_LAYER
    //
    // The Details destination continuously records its current Compose
    // drawing into this NavHost-owned GraphicsLayer. The recorded display
    // list therefore survives the navigation pop.
    val detailReturnLayer = rememberGraphicsLayer()

    val detailReturnOverlayAlpha =
        androidx.compose.runtime.remember {
            androidx.compose.animation.core.Animatable(1f)
        }

    val showDetailReturnOverlay =
        androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(false)
        }

    val detailReturnRunning =
        androidx.compose.runtime.remember {
            java.util.concurrent.atomic.AtomicBoolean(false)
        }

    val detailReturnScope =
        androidx.compose.runtime.rememberCoroutineScope()

    // One-shot signal used only during Details -> Home return.
    //
    // Home sends this only after its outer content has actually
    // completed a draw. The draw hook is then removed immediately,
    // so it does not remain on the normal Home scrolling path.
    val detailReturnHomeDrawSignal =
        androidx.compose.runtime.remember {
            kotlinx.coroutines.channels.Channel<Unit>(
                capacity = kotlinx.coroutines.channels.Channel.CONFLATED
            )
        }

    val detailReturnAwaitingHomeDraw =
        androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(false)
        }

    fun runFrozenDetailReturn(
        navigateHome: () -> Unit
    ) {
        if (!detailReturnRunning.compareAndSet(false, true)) return

        detailReturnScope.launch {
            try {
                detailReturnOverlayAlpha.snapTo(1f)
                showDetailReturnOverlay.value = true

                // Live Details is still on screen here.
                androidx.compose.runtime.withFrameNanos { }

                // Clear any stale one-shot signal from a previous return.
                while (detailReturnHomeDrawSignal.tryReceive().isSuccess) {
                    // Drain only.
                }

                // Arm the Home draw handshake before navigation so the first
                // returning Home draw cannot race past us.
                detailReturnAwaitingHomeDraw.value = true

                // Pop Details. The recorded Details frame remains fully
                // visible above Home while Home restores underneath.
                navigateHome()

                // Wait until Home has actually completed an outer draw.
                //
                // The 250ms timeout is ONLY a failsafe. Normally the signal
                // should arrive much sooner, allowing the same 350ms dissolve
                // to begin immediately instead of waiting a fixed ~100ms.
                kotlinx.coroutines.withTimeoutOrNull(250L) {
                    detailReturnHomeDrawSignal.receive()
                }

                // Remove the temporary draw hook BEFORE the dissolve.
                detailReturnAwaitingHomeDraw.value = false

                // Keep the approved crossfade duration unchanged.
                detailReturnOverlayAlpha.animateTo(
                    targetValue = 0f,
                    animationSpec =
                        androidx.compose.animation.core.tween(
                            durationMillis = 350
                        )
                )
            } finally {
                detailReturnAwaitingHomeDraw.value = false
                showDetailReturnOverlay.value = false
                detailReturnOverlayAlpha.snapTo(1f)
                detailReturnRunning.set(false)
            }
        }
    }

    fun runFrozenSidebarRootReturn(
        navigateHome: () -> Unit
    ) {
        if (!sidebarRootReturnRunning.compareAndSet(false, true)) return

        sidebarRootReturnScope.launch {
            try {
                sidebarRootReturnOverlayAlpha.snapTo(1f)
                showSidebarRootReturnOverlay.value = true

                /* Commit the already-recorded live root frame as an overlay. */
                androidx.compose.runtime.withFrameNanos { }

                while (sidebarRootReturnHomeDrawSignal.tryReceive().isSuccess) {
                    // Drain a stale one-shot signal from an earlier return.
                }
                sidebarRootReturnAwaitingHomeDraw.value = true

                navigateHome()

                /* Begin only when the returning Home has produced real pixels. */
                kotlinx.coroutines.withTimeoutOrNull(250L) {
                    sidebarRootReturnHomeDrawSignal.receive()
                }
                sidebarRootReturnAwaitingHomeDraw.value = false

                sidebarRootReturnOverlayAlpha.animateTo(
                    targetValue = 0f,
                    animationSpec =
                        androidx.compose.animation.core.tween(
                            durationMillis = 300,
                            easing = LinearEasing
                        )
                )
            } finally {
                sidebarRootReturnAwaitingHomeDraw.value = false
                showSidebarRootReturnOverlay.value = false
                sidebarRootReturnOverlayAlpha.snapTo(1f)
                sidebarRootReturnRunning.set(false)
            }
        }
    }

    fun popSidebarRootToHome() {
        suppressHomeCurtainForRootReturn.value = true
        runCatching {
            navController.getBackStackEntry(Screen.Home.route)
        }.getOrNull()
            ?.savedStateHandle
            ?.set("skipHomeReturnCurtainOnce", true)

        runFrozenSidebarRootReturn {
            if (!navController.popBackStack(Screen.Home.route, inclusive = false)) {
                navController.navigate(Screen.Home.route) {
                    popUpTo(navController.graph.startDestinationId) {
                        inclusive = false
                    }
                    launchSingleTop = true
                }
            }
        }
    }

    fun isStreamToPlayer(from: String, to: String): Boolean {
        return from.startsWith("stream/") && to.startsWith("player/")
    }

    fun isPlayerToStream(from: String, to: String): Boolean {
        return from.startsWith("player/") && to.startsWith("stream/")
    }

    fun isSidebarRoot(route: String): Boolean {
        return route == Screen.Home.route ||
            route == Screen.Search.route ||
            route == Screen.Library.route ||
            route == Screen.AddonManager.route ||
            route == Screen.Settings.route
    }

    fun isSidebarRootTransition(from: String, to: String): Boolean {
        return from != to && isSidebarRoot(from) && isSidebarRoot(to)
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            val from = initialState.destination.route.orEmpty()
            val to = targetState.destination.route.orEmpty()
            val isAutoPlayNav = targetState.arguments
                ?.getString("autoPlayNav")
                ?.toBooleanStrictOrNull() == true
            when {
                isStreamToPlayer(from, to) && isAutoPlayNav ->
                    EnterTransition.None

                /*
                 * Sidebar destinations dissolve over the still-opaque screen
                 * beneath them. This gives the glass workspace a deliberate
                 * entrance without exposing the NavHost background.
                 */
                isSidebarRootTransition(from, to) ->
                    when (to) {
                        Screen.Home.route ->
                            EnterTransition.None

                        Screen.Settings.route ->
                            fadeIn(
                                animationSpec = tween(
                                    durationMillis = 180,
                                    easing = FastOutSlowInEasing
                                )
                            )

                        else ->
                            fadeIn(
                                animationSpec = tween(
                                    durationMillis = 300,
                                    easing = LinearEasing
                                )
                            )
                    }

                else -> fadeIn(animationSpec = tween(350))
            }
        },
        exitTransition = {
            val from = initialState.destination.route.orEmpty()
            val to = targetState.destination.route.orEmpty()
            val isAutoPlayNav = targetState.arguments
                ?.getString("autoPlayNav")
                ?.toBooleanStrictOrNull() == true
            when {
                isStreamToPlayer(from, to) && isAutoPlayNav ->
                    ExitTransition.None

                /*
                 * Opening keeps the outgoing root opaque while the glass screen
                 * fades over it. Returning keeps Home opaque underneath and
                 * dissolves the outgoing glass screen away. Fading both layers
                 * at once can expose the NavHost background between them.
                 */
                isSidebarRootTransition(from, to) ->
                    if (to == Screen.Home.route) {
                        fadeOut(
                            animationSpec = tween(
                                durationMillis = 300,
                                easing = LinearEasing
                            )
                        )
                    } else {
                        ExitTransition.None
                    }

                else -> fadeOut(animationSpec = tween(350))
            }
        },
        popEnterTransition = {
            val from = initialState.destination.route.orEmpty()
            val to = targetState.destination.route.orEmpty()
            val isAutoPlayNav = initialState.arguments
                ?.getString("autoPlayNav")
                ?.toBooleanStrictOrNull() == true
            when {
                (isPlayerToStream(from, to) && isAutoPlayNav) ||
                    (from.startsWith("detail/") && to == Screen.Home.route) ->
                    EnterTransition.None

                isSidebarRootTransition(from, to) ->
                    when (to) {
                        Screen.Home.route -> {
                            /* Home's outer layer owns the visible return dissolve. */
                            EnterTransition.None
                        }

                        Screen.Settings.route ->
                            fadeIn(
                                animationSpec = tween(
                                    durationMillis = 180,
                                    easing = FastOutSlowInEasing
                                )
                            )

                        else ->
                            fadeIn(
                                animationSpec = tween(
                                    durationMillis = 300,
                                    easing = LinearEasing
                                )
                            )
                    }

                else -> fadeIn(animationSpec = tween(350))
            }
        },
        popExitTransition = {
            val from = initialState.destination.route.orEmpty()
            val to = targetState.destination.route.orEmpty()
            val isAutoPlayNav = initialState.arguments
                ?.getString("autoPlayNav")
                ?.toBooleanStrictOrNull() == true
            val isRatingExit = initialState.savedStateHandle
                .get<Boolean>("ratingExit") == true
            when {
                isPlayerToStream(from, to) && isAutoPlayNav -> ExitTransition.None
                // Screen already black from rating animation — skip nav fade
                from.startsWith("player/") && isRatingExit -> ExitTransition.None

                // Frozen Details layer owns this visual transition.
                from.startsWith("detail/") &&
                    to == Screen.Home.route -> ExitTransition.None

                // Preserve the same direction-aware layer ownership when state
                // restoration turns sidebar navigation into a pop.
                isSidebarRootTransition(from, to) ->
                    if (to == Screen.Home.route) {
                        fadeOut(
                            animationSpec = tween(
                                durationMillis = 300,
                                easing = LinearEasing
                            )
                        )
                    } else {
                        ExitTransition.None
                    }

                else -> fadeOut(animationSpec = tween(350))
            }
        }
    ) {
        composable(Screen.LayoutSelection.route) {
            LayoutSelectionScreen(
                onContinue = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.LayoutSelection.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Home.route) { backStackEntry ->
            // Scope HomeViewModel explicitly to the Home NavBackStackEntry.
            // HomeScreen previously resolved this same owner implicitly via hiltViewModel().
            // Keeping the owner explicit lets other destinations reference this exact
            // Home instance later without ever creating a second Home pipeline.
            val homeViewModel: HomeViewModel = hiltViewModel(backStackEntry)

            val savedSkipHomeReturnCurtain =
                androidx.compose.runtime.remember(backStackEntry) {
                    backStackEntry.savedStateHandle
                        .get<Boolean>("skipHomeReturnCurtainOnce") == true
                }
            val skipHomeReturnCurtain =
                savedSkipHomeReturnCurtain ||
                    suppressHomeCurtainForRootReturn.value

            androidx.compose.runtime.LaunchedEffect(skipHomeReturnCurtain) {
                if (skipHomeReturnCurtain) {
                    backStackEntry.savedStateHandle["skipHomeReturnCurtainOnce"] = false
                }
            }

            fun createContinueWatchingRoute(
                item: ContinueWatchingItem,
                manualSelection: Boolean = false,
                startFromBeginning: Boolean = false
            ): String {
                return when (item) {
                    is ContinueWatchingItem.InProgress -> Screen.Stream.createRoute(
                        videoId = item.progress.videoId,
                        contentType = item.progress.contentType,
                        title = item.progress.name,
                        poster = item.progress.poster,
                        backdrop = item.progress.backdrop,
                        logo = item.progress.logo,
                        season = item.progress.season,
                        episode = item.progress.episode,
                        episodeName = item.progress.episodeTitle,
                        genres = null,
                        year = null,
                        contentId = item.progress.contentId,
                        contentName = item.progress.name,
                        runtime = null,
                        manualSelection = manualSelection,
                        returnToDetailOnBack = item.progress.contentType.equals("series", ignoreCase = true),
                        returnToHomeOnBack = true,
                        startFromBeginning = startFromBeginning
                    )
                    is ContinueWatchingItem.NextUp -> Screen.Stream.createRoute(
                        videoId = item.info.videoId,
                        contentType = item.info.contentType,
                        title = item.info.name,
                        poster = item.info.poster,
                        backdrop = item.info.backdrop,
                        logo = item.info.logo,
                        season = item.info.season,
                        episode = item.info.episode,
                        episodeName = item.info.episodeTitle,
                        genres = null,
                        year = null,
                        contentId = item.info.contentId,
                        contentName = item.info.name,
                        runtime = null,
                        manualSelection = manualSelection,
                        returnToDetailOnBack = item.info.contentType.equals("series", ignoreCase = true),
                        returnToHomeOnBack = true,
                        startFromBeginning = startFromBeginning
                    )
                }
            }

            val isRootReturnDissolve = suppressHomeCurtainForRootReturn.value

            androidx.compose.runtime.LaunchedEffect(isRootReturnDissolve) {
                if (isRootReturnDissolve) {
                    /*
                     * Home is the fully opaque destination underneath the
                     * retained root frame. The overlay alone dissolves away,
                     * matching the one-sided Home -> root transition.
                     */
                    androidx.compose.runtime.withFrameNanos { }
                    suppressHomeCurtainForRootReturn.value = false
                }
            }

            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                HomeScreen(
                    viewModel = homeViewModel,
                    skipReturnCurtain = skipHomeReturnCurtain,
                    returnFrameSignalActive =
                        detailReturnAwaitingHomeDraw.value ||
                            sidebarRootReturnAwaitingHomeDraw.value,
                    onReturnFrameDrawn = {
                        if (detailReturnAwaitingHomeDraw.value) {
                            detailReturnHomeDrawSignal.trySend(Unit)
                        }
                        if (sidebarRootReturnAwaitingHomeDraw.value) {
                            sidebarRootReturnHomeDrawSignal.trySend(Unit)
                        }
                    },
                    onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                        navController.navigate(Screen.Detail.createRoute(itemId, itemType, addonBaseUrl))
                    },
                    onContinueWatchingClick = { item ->
                        navController.navigate(createContinueWatchingRoute(item))
                    },
                    onContinueWatchingStartFromBeginning = { item ->
                        navController.navigate(
                            createContinueWatchingRoute(item, startFromBeginning = true)
                        )
                    },
                    onContinueWatchingPlayManually = { item ->
                        navController.navigate(
                            createContinueWatchingRoute(item, manualSelection = true)
                        )
                    },
                    onNavigateToCatalogSeeAll = { catalogId, addonId, type ->
                        navController.navigate(Screen.CatalogSeeAll.createRoute(catalogId, addonId, type))
                    }
                )
            }
        }

        composable(
            route = Screen.Detail.route,
            arguments = listOf(
                navArgument("itemId") { type = NavType.StringType },
                navArgument("itemType") { type = NavType.StringType },
                navArgument("addonBaseUrl") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("returnFocusSeason") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("returnFocusEpisode") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("returnToHomeOnBack") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                }
            )
        ) { backStackEntry ->
            val detailArgs = backStackEntry.arguments
            val savedState = backStackEntry.savedStateHandle
            val returnToHomeOnBack = detailArgs
                ?.getString("returnToHomeOnBack")
                ?.toBooleanStrictOrNull() == true

            fun markHomeReturnCurtainBypass() {
                runCatching {
                    navController.getBackStackEntry(Screen.Home.route)
                }.getOrNull()
                    ?.savedStateHandle
                    ?.set("skipHomeReturnCurtainOnce", true)
            }
            val returnFocusSeason by savedState.getStateFlow(
                "returnFocusSeason", detailArgs?.getString("returnFocusSeason")?.toIntOrNull()
            ).collectAsState()
            val returnFocusEpisode by savedState.getStateFlow(
                "returnFocusEpisode", detailArgs?.getString("returnFocusEpisode")?.toIntOrNull()
            ).collectAsState()
            MetaDetailsScreen(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        detailReturnLayer.record {
                            this@drawWithContent.drawContent()
                        }
                        drawLayer(detailReturnLayer)
                    },
                returnFocusSeason = returnFocusSeason,
                returnFocusEpisode = returnFocusEpisode,
                onBackPress = {
                    if (returnToHomeOnBack) {
                        markHomeReturnCurtainBypass()

                        runFrozenDetailReturn {
                            val popped = navController.popBackStack(
                                Screen.Home.route,
                                inclusive = false
                            )

                            if (!popped) {
                                navController.navigate(Screen.Home.route) {
                                    launchSingleTop = true
                                }
                            }
                        }
                    } else {
                        val returningDirectlyToHome =
                            navController.previousBackStackEntry
                                ?.destination
                                ?.route == Screen.Home.route

                        if (returningDirectlyToHome) {
                            markHomeReturnCurtainBypass()

                            runFrozenDetailReturn {
                                navController.popBackStack()
                            }
                        } else {
                            navController.popBackStack()
                        }
                    }
                },
                onNavigateToCastDetail = { personId, personName, preferCrew ->
                    navController.navigate(Screen.CastDetail.createRoute(personId, personName, preferCrew))
                },
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    navController.navigate(Screen.Detail.createRoute(itemId, itemType, addonBaseUrl))
                },
                onPlayClick = { videoId, contentType, contentId, title, poster, backdrop, logo, season, episode, episodeName, genres, year, runtime ->
                    navController.navigate(
                        Screen.Stream.createRoute(
                            videoId = videoId,
                            contentType = contentType,
                            title = title,
                            poster = poster,
                            backdrop = backdrop,
                            logo = logo,
                            season = season,
                            episode = episode,
                            episodeName = episodeName,
                            genres = genres,
                            year = year,
                            contentId = contentId,
                            contentName = title,
                            runtime = runtime,
                            metadataAddonBaseUrl =
                                backStackEntry.arguments
                                    ?.getString("addonBaseUrl")
                                    ?.takeIf { it.isNotBlank() },
                            returnToDetailOnBack = contentType.equals("series", ignoreCase = true)
                        )
                    )
                },
                onPlayManuallyClick = { videoId, contentType, contentId, title, poster, backdrop, logo, season, episode, episodeName, genres, year, runtime ->
                    navController.navigate(
                        Screen.Stream.createRoute(
                            videoId = videoId,
                            contentType = contentType,
                            title = title,
                            poster = poster,
                            backdrop = backdrop,
                            logo = logo,
                            season = season,
                            episode = episode,
                            episodeName = episodeName,
                            genres = genres,
                            year = year,
                            contentId = contentId,
                            contentName = title,
                            runtime = runtime,
                            metadataAddonBaseUrl =
                                backStackEntry.arguments
                                    ?.getString("addonBaseUrl")
                                    ?.takeIf { it.isNotBlank() },
                            manualSelection = true,
                            returnToDetailOnBack = contentType.equals("series", ignoreCase = true)
                        )
                    )
                }
            )
        }

        composable(
            route = Screen.Stream.route,
            arguments = listOf(
                navArgument("videoId") { type = NavType.StringType },
                navArgument("contentType") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType },
                navArgument("poster") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("backdrop") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("logo") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("season") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("episode") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("episodeName") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("genres") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("year") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("contentId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("contentName") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("runtime") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("manualSelection") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                },
                navArgument("returnToDetailOnBack") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                },
                navArgument("returnToHomeOnBack") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                },
                navArgument("startFromBeginning") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                },
                navArgument("metadataAddonBaseUrl") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val streamArgs = backStackEntry.arguments
            val returnToDetailOnBack = streamArgs
                ?.getString("returnToDetailOnBack")
                ?.toBooleanStrictOrNull() == true
            val returnToHomeOnBack = streamArgs
                ?.getString("returnToHomeOnBack")
                ?.toBooleanStrictOrNull() == true
            val startFromBeginning = streamArgs
                ?.getString("startFromBeginning")
                ?.toBooleanStrictOrNull() == true
            StreamScreen(
                onBackPress = {
                    val streamContentType = streamArgs?.getString("contentType").orEmpty()
                    val streamContentId = streamArgs?.getString("contentId").orEmpty()
                    val season = streamArgs?.getString("season")?.toIntOrNull()
                    val episode = streamArgs?.getString("episode")?.toIntOrNull()
                    if (streamContentType.equals("series", ignoreCase = true) && streamContentId.isNotBlank()) {
                        val detailEntry = runCatching { navController.getBackStackEntry(Screen.Detail.route) }.getOrNull()
                        if (detailEntry != null) {
                            detailEntry.savedStateHandle["returnFocusSeason"] = season
                            detailEntry.savedStateHandle["returnFocusEpisode"] = episode
                            navController.popBackStack(Screen.Detail.route, inclusive = false)
                        } else {
                            navController.navigate(
                                Screen.Detail.createRoute(
                                    itemId = streamContentId,
                                    itemType = streamContentType,
                                    addonBaseUrl =
                                        streamArgs
                                            ?.getString("metadataAddonBaseUrl")
                                            ?.takeIf { it.isNotBlank() },
                                    returnFocusSeason = season,
                                    returnFocusEpisode = episode,
                                    returnToHomeOnBack = returnToHomeOnBack
                                )
                            ) {
                                popUpTo(Screen.Stream.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    } else {
                        navController.popBackStack()
                    }
                },
                onStreamSelected = { playbackInfo ->
                    playbackInfo.url?.let { url ->
                        navController.navigate(
                            Screen.Player.createRoute(
                                streamUrl = url,
                                title = playbackInfo.title,
                                streamName = playbackInfo.streamName,
                                year = playbackInfo.year,
                                headers = playbackInfo.headers,
                                contentId = playbackInfo.contentId,
                                contentType = playbackInfo.contentType,
                                contentName = playbackInfo.contentName,
                                metadataAddonBaseUrl =
                                    streamArgs
                                        ?.getString("metadataAddonBaseUrl")
                                        ?.takeIf { it.isNotBlank() },
                                poster = playbackInfo.poster,
                                backdrop = playbackInfo.backdrop,
                                logo = playbackInfo.logo,
                                videoId = playbackInfo.videoId,
                                season = playbackInfo.season,
                                episode = playbackInfo.episode,
                                episodeTitle = playbackInfo.episodeTitle,
                                bingeGroup = playbackInfo.bingeGroup,
                                rememberedAudioLanguage = playbackInfo.rememberedAudioLanguage,
                                rememberedAudioName = playbackInfo.rememberedAudioName,
                                autoPlayNav = false,
                                returnToDetailOnBack = returnToDetailOnBack,
                                returnToHomeOnBack = returnToHomeOnBack,
                                filename = playbackInfo.filename,
                                videoHash = playbackInfo.videoHash,
                                videoSize = playbackInfo.videoSize,
                                infoHash = playbackInfo.infoHash,
                                fileIdx = playbackInfo.fileIdx,
                                sources = playbackInfo.sources,
                                startFromBeginning = startFromBeginning,
                                addonName = playbackInfo.addonName,
                                addonLogo = playbackInfo.addonLogo,
                                streamDescription = playbackInfo.streamDescription,
                                manualSelection = true
                            )
                        )
                    }
                },
                onAutoPlayResolved = { playbackInfo ->
                    playbackInfo.url?.let { url ->
                        navController.navigate(
                            Screen.Player.createRoute(
                                streamUrl = url,
                                title = playbackInfo.title,
                                streamName = playbackInfo.streamName,
                                year = playbackInfo.year,
                                headers = playbackInfo.headers,
                                contentId = playbackInfo.contentId,
                                contentType = playbackInfo.contentType,
                                contentName = playbackInfo.contentName,
                                metadataAddonBaseUrl =
                                    streamArgs
                                        ?.getString("metadataAddonBaseUrl")
                                        ?.takeIf { it.isNotBlank() },
                                poster = playbackInfo.poster,
                                backdrop = playbackInfo.backdrop,
                                logo = playbackInfo.logo,
                                videoId = playbackInfo.videoId,
                                season = playbackInfo.season,
                                episode = playbackInfo.episode,
                                episodeTitle = playbackInfo.episodeTitle,
                                bingeGroup = playbackInfo.bingeGroup,
                                rememberedAudioLanguage = playbackInfo.rememberedAudioLanguage,
                                rememberedAudioName = playbackInfo.rememberedAudioName,
                                autoPlayNav = true,
                                returnToDetailOnBack = returnToDetailOnBack,
                                returnToHomeOnBack = returnToHomeOnBack,
                                filename = playbackInfo.filename,
                                videoHash = playbackInfo.videoHash,
                                videoSize = playbackInfo.videoSize,
                                infoHash = playbackInfo.infoHash,
                                fileIdx = playbackInfo.fileIdx,
                                sources = playbackInfo.sources,
                                startFromBeginning = startFromBeginning,
                                addonName = playbackInfo.addonName,
                                addonLogo = playbackInfo.addonLogo,
                                streamDescription = playbackInfo.streamDescription,
                                manualSelection = false
                            )
                        ) {
                            popUpTo(Screen.Stream.route) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable(
            route = Screen.Player.route,
            arguments = listOf(
                navArgument("streamUrl") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType },
                navArgument("streamName") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("year") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("headers") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("contentId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("contentType") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("contentName") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("poster") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("backdrop") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("logo") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("videoId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("season") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("episode") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("episodeTitle") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("bingeGroup") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("rememberedAudioLanguage") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("rememberedAudioName") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("autoPlayNav") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                },
                navArgument("returnToDetailOnBack") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                },
                navArgument("returnToHomeOnBack") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                },
                navArgument("filename") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("videoHash") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("videoSize") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("startFromBeginning") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                },
                navArgument("addonName") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("addonLogo") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("streamDescription") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("manualSelection") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                },
                navArgument("infoHash") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("fileIdx") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("sources") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("contentLanguage") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("metadataAddonBaseUrl") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val playerReturnToHomeOnBack =
                backStackEntry.arguments
                    ?.getString("returnToHomeOnBack")
                    ?.toBooleanStrictOrNull() == true

            val homeEntryForPlayerReturn =
                runCatching {
                    navController.getBackStackEntry(Screen.Home.route)
                }.getOrNull()

            val homeViewModelForPlayerReturn: HomeViewModel? =
                homeEntryForPlayerReturn?.let { homeEntry ->
                    hiltViewModel(homeEntry)
                }

            PlayerScreen(
                onBeforePostPlayHomeExit = { expectFreshSave ->
                    // Post-play Back always returns directly to Home.
                    homeViewModelForPlayerReturn
                        ?.armPlayerReturnCwTransaction(
                            expectFreshSave = expectFreshSave
                        )
                },
                onBeforeRatingHomeExit = { expectFreshSave ->
                    // Rating only returns directly to Home for CW-origin playback.
                    if (playerReturnToHomeOnBack) {
                        homeViewModelForPlayerReturn
                            ?.armPlayerReturnCwTransaction(
                                expectFreshSave = expectFreshSave
                            )
                    }
                },
                onPostPlayBackPress = {
                    backStackEntry.savedStateHandle["ratingExit"] = true
                    val returnedHome = navController.popBackStack(
                        Screen.Home.route,
                        inclusive = false
                    )
                    if (!returnedHome) {
                        navController.navigate(Screen.Home.route) {
                            launchSingleTop = true
                        }
                    }
                },
                onRatingBackPress = { currentSeason: Int?, currentEpisode: Int?, autoPlayEnabled: Boolean ->
                    backStackEntry.savedStateHandle["ratingExit"] = true
                    // reuse same logic as onBackPress below
                    val args2 = backStackEntry.arguments
                    val returnToHomeOnBack2 = args2?.getString("returnToHomeOnBack")
                        ?.toBooleanStrictOrNull() == true
                    if (returnToHomeOnBack2) {
                        navController.popBackStack(Screen.Home.route, inclusive = false)
                    } else {
                        navController.popBackStack()
                    }
                },
                onBackPress = { currentSeason, currentEpisode, autoPlayEnabled ->
                    val args = backStackEntry.arguments
                    val initialSeason = args?.getString("season")?.toIntOrNull()
                    val initialEpisode = args?.getString("episode")?.toIntOrNull()
                    val episodeChangedInPlace = (currentSeason != null || currentEpisode != null) &&
                        (currentSeason != initialSeason || currentEpisode != initialEpisode)
                    val returnToDetailOnBack = args?.getString("returnToDetailOnBack")
                        ?.toBooleanStrictOrNull() == true
                    val returnToHomeOnBack = args?.getString("returnToHomeOnBack")
                        ?.toBooleanStrictOrNull() == true
                    val contentType = args?.getString("contentType").orEmpty()
                    val contentId = args?.getString("contentId").orEmpty()
                    val focusSeason = currentSeason ?: initialSeason
                    val focusEpisode = currentEpisode ?: initialEpisode

                    // Helper to pop with rating exit flag
                    fun popWithRatingExit() {
                        backStackEntry.savedStateHandle["ratingExit"] = true
                        navController.popBackStack()
                    }

                    when {
                        episodeChangedInPlace && autoPlayEnabled -> {
                            // autoplay moved to next episode — skip Stream, go to detail
                            if (returnToDetailOnBack && contentType.equals("series", ignoreCase = true) && contentId.isNotBlank()) {
                                val detailOnStack = navController.previousBackStackEntry
                                    ?.destination?.route?.startsWith("detail/") == true
                                if (detailOnStack) {
                                    navController.previousBackStackEntry?.savedStateHandle?.set("returnFocusSeason", focusSeason)
                                    navController.previousBackStackEntry?.savedStateHandle?.set("returnFocusEpisode", focusEpisode)
                                    navController.popBackStack()
                                } else {
                                    navController.navigate(
                                        Screen.Detail.createRoute(
                                            itemId = contentId,
                                            itemType = contentType,
                                            addonBaseUrl =
                                                args
                                                    ?.getString("metadataAddonBaseUrl")
                                                    ?.takeIf { it.isNotBlank() },
                                            returnFocusSeason = focusSeason,
                                            returnFocusEpisode = focusEpisode,
                                            returnToHomeOnBack = returnToHomeOnBack
                                        )
                                    ) {
                                        popUpTo(Screen.Player.route) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                }
                            } else {
                                navController.popBackStack()
                            }
                        }
                        episodeChangedInPlace && !autoPlayEnabled -> {
                            // manual stream switch to next episode — go to Stream of current episode
                            val videoId = args?.getString("videoId").orEmpty()
                            if (videoId.isNotBlank() && contentType.isNotBlank()) {
                                navController.navigate(
                                    Screen.Stream.createRoute(
                                        videoId = videoId,
                                        contentType = contentType,
                                        title = args?.getString("title").orEmpty(),
                                        poster = args?.getString("poster"),
                                        backdrop = args?.getString("backdrop"),
                                        logo = args?.getString("logo"),
                                        season = focusSeason,
                                        episode = focusEpisode,
                                        year = args?.getString("year"),
                                        contentId = contentId.takeIf { it.isNotBlank() },
                                        contentName = args?.getString("contentName"),
                                        metadataAddonBaseUrl =
                                            args
                                                ?.getString("metadataAddonBaseUrl")
                                                ?.takeIf { it.isNotBlank() },
                                        returnToDetailOnBack = returnToDetailOnBack,
                                        returnToHomeOnBack = returnToHomeOnBack
                                    )
                                ) {
                                    popUpTo(Screen.Stream.route) { inclusive = true }
                                    launchSingleTop = true
                                }
                            } else {
                                navController.popBackStack()
                            }
                        }
                        else -> {
                            // normal back — try returning to Stream of this episode
                            val returnedToStream = navController.popBackStack(Screen.Stream.route, inclusive = false)
                            if (!returnedToStream) {
                                if (returnToDetailOnBack && contentType.equals("series", ignoreCase = true) && contentId.isNotBlank()) {
                                    val detailOnStack = navController.previousBackStackEntry
                                        ?.destination?.route?.startsWith("detail/") == true
                                    if (detailOnStack) {
                                        navController.previousBackStackEntry?.savedStateHandle?.set("returnFocusSeason", focusSeason)
                                        navController.previousBackStackEntry?.savedStateHandle?.set("returnFocusEpisode", focusEpisode)
                                        navController.popBackStack()
                                    } else {
                                        navController.navigate(
                                            Screen.Detail.createRoute(
                                                itemId = contentId,
                                                itemType = contentType,
                                                addonBaseUrl =
                                                args
                                                    ?.getString("metadataAddonBaseUrl")
                                                    ?.takeIf { it.isNotBlank() },
                                                returnFocusSeason = focusSeason,
                                                returnFocusEpisode = focusEpisode,
                                                returnToHomeOnBack = returnToHomeOnBack
                                            )
                                        ) {
                                            popUpTo(Screen.Player.route) { inclusive = true }
                                            launchSingleTop = true
                                        }
                                    }
                                } else {
                                    navController.popBackStack()
                                }
                            }
                        }
                    }
                },
                onPlaybackEnded = { nextVideoId, nextSeason, nextEpisode ->
                    val args = backStackEntry.arguments
                    val contentType = args?.getString("contentType").orEmpty()
                    val contentId = args?.getString("contentId").orEmpty()
                    val returnToDetailOnBack = args?.getString("returnToDetailOnBack")
                        ?.toBooleanStrictOrNull() == true
                    val returnToHomeOnBack = args?.getString("returnToHomeOnBack")
                        ?.toBooleanStrictOrNull() == true
                    if (nextVideoId != null && nextSeason != null && nextEpisode != null) {
                        val route = Screen.Stream.createRoute(
                            videoId = nextVideoId,
                            contentType = contentType,
                            title = args?.getString("title").orEmpty(),
                            poster = args?.getString("poster"),
                            backdrop = args?.getString("backdrop"),
                            logo = args?.getString("logo"),
                            season = nextSeason,
                            episode = nextEpisode,
                            episodeName = null,
                            genres = null,
                            year = args?.getString("year"),
                            contentId = contentId.takeIf { it.isNotBlank() },
                            contentName = args?.getString("contentName"),
                            runtime = null,
                            returnToDetailOnBack = returnToDetailOnBack,
                            returnToHomeOnBack = returnToHomeOnBack
                        )
                        navController.navigate(route) {
                            popUpTo(Screen.Player.route) { inclusive = true }
                        }
                    } else {
                        // No next episode — pop back to detail (or home if detail not on stack)
                        val poppedToDetail = navController.popBackStack(Screen.Detail.route, inclusive = false)
                        if (!poppedToDetail) {
                            navController.popBackStack(Screen.Stream.route, inclusive = true)
                        }
                    }
                },
                onPostPlayRecommendationSelected = { recommendation, playNow ->
                    val route = if (playNow) {
                        Screen.Stream.createRoute(
                            videoId = recommendation.playbackVideoId ?: recommendation.id,
                            contentType = recommendation.contentType,
                            title = recommendation.title,
                            poster = recommendation.poster,
                            backdrop = recommendation.backdrop,
                            logo = recommendation.logo,
                            contentId = recommendation.id,
                            contentName = recommendation.title,
                            returnToHomeOnBack = true
                        )
                    } else {
                        Screen.Detail.createRoute(
                            itemId = recommendation.id,
                            itemType = recommendation.contentType,
                            heroBackdropUrl = recommendation.backdrop
                        )
                    }
                    navController.navigate(route) {
                        popUpTo(Screen.Home.route) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onPlaybackErrorBack = {
                    val returnedToStream = navController.popBackStack(Screen.Stream.route, inclusive = false)
                    if (!returnedToStream) {
                        val args = backStackEntry.arguments
                        val videoId = args?.getString("videoId").orEmpty()
                        val contentType = args?.getString("contentType").orEmpty()
                        val title = args?.getString("title").orEmpty()

                        if (videoId.isBlank() || contentType.isBlank() || title.isBlank()) {
                            navController.popBackStack()
                        } else {
                            val route = Screen.Stream.createRoute(
                                videoId = videoId,
                                contentType = contentType,
                                title = title,
                                poster = args?.getString("poster"),
                                backdrop = args?.getString("backdrop"),
                                logo = args?.getString("logo"),
                                season = args?.getString("season")?.toIntOrNull(),
                                episode = args?.getString("episode")?.toIntOrNull(),
                                episodeName = args?.getString("episodeTitle"),
                                genres = null,
                                year = args?.getString("year"),
                                contentId = args?.getString("contentId"),
                                contentName = args?.getString("contentName"),
                                runtime = null,
                                manualSelection = true,
                                returnToDetailOnBack = args?.getString("returnToDetailOnBack")
                                    ?.toBooleanStrictOrNull() == true,
                                returnToHomeOnBack = args?.getString("returnToHomeOnBack")
                                    ?.toBooleanStrictOrNull() == true
                            )

                            navController.navigate(route) {
                                popUpTo(Screen.Player.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                }
            )
        }

        composable(
            route = Screen.Search.route,
            popExitTransition = {
                rootDestinationPopExit(
                    destinationName = "search",
                    targetRoute = targetState.destination.route
                )
            }
        ) {
            SidebarRootBackToHome { popSidebarRootToHome() }
            RecordSidebarRootFrame(sidebarRootReturnLayer) {
                SettingsGlassScreen {
                    SearchScreen(
                        onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                            navController.navigate(Screen.Detail.createRoute(itemId, itemType, addonBaseUrl))
                        },
                        onNavigateToSeeAll = { catalogId, addonId, type ->
                            navController.navigate(Screen.CatalogSeeAll.createRoute(catalogId, addonId, type))
                        },
                        onOpenDiscover = { navController.navigate(Screen.Discover.route) }
                    )
                }
            }
        }

        composable(Screen.Discover.route) {
            SettingsGlassScreen {
                DiscoverScreen(
                    onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                        navController.navigate(Screen.Detail.createRoute(itemId, itemType, addonBaseUrl))
                    }
                )
            }
        }

        composable(
            route = Screen.Library.route,
            popExitTransition = {
                rootDestinationPopExit(
                    destinationName = "library",
                    targetRoute = targetState.destination.route
                )
            }
        ) {
            SidebarRootBackToHome { popSidebarRootToHome() }
            RecordSidebarRootFrame(sidebarRootReturnLayer) {
                SettingsGlassScreen {
                    LibraryScreen(
                        showBuiltInHeader = !hideBuiltInHeaders,
                        onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                            navController.navigate(Screen.Detail.createRoute(itemId, itemType, addonBaseUrl))
                        }
                    )
                }
            }
        }

        composable(
            route = Screen.Settings.route,
            popExitTransition = {
                rootDestinationPopExit(
                    destinationName = "settings",
                    targetRoute = targetState.destination.route
                )
            }
        ) { settingsBackStackEntry ->
            val trackingReturnFocusAccount by
                settingsBackStackEntry.savedStateHandle
                    .getStateFlow("settings_tracking_return_focus", "")
                    .collectAsState()

            SidebarRootBackToHome { popSidebarRootToHome() }
            RecordSidebarRootFrame(sidebarRootReturnLayer) {
                SettingsScreen(
                    showBuiltInHeader = !hideBuiltInHeaders,
                    onNavigateToTracking = {
                        navController.navigate(Screen.Tracking.route)
                    },
                    onNavigateToTrakt = {
                        navController.navigate(Screen.Trakt.route)
                    },
                    onNavigateToSimkl = {
                        navController.navigate(Screen.Simkl.route)
                    },
                    trackingReturnFocusAccount =
                        trackingReturnFocusAccount
                            .takeIf { it.isNotBlank() },
                    onTrackingReturnFocusConsumed = {
                        settingsBackStackEntry.savedStateHandle[
                            "settings_tracking_return_focus"
                        ] = ""
                    },
                    onNavigateToAuthQrSignIn = { navController.navigate(Screen.AuthQrSignIn.route) },
                    onNavigateToManageProfiles = { navController.navigate(Screen.ManageProfiles.route) },
                    onNavigateToSupportersContributors = {
                        navController.navigate(Screen.SupportersContributors.route)
                    }
                )
            }
        }

        composable(Screen.ManageProfiles.route) {
            ProfileSelectionScreen(
                onProfileSelected = {},
                screenMode = ProfileSelectionMode.Management,
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.Tracking.route) {
            TrackingSettingsScreen(
                onNavigateToTrakt = {
                    navController.navigate(Screen.Trakt.route)
                },
                onNavigateToSimkl = {
                    navController.navigate(Screen.Simkl.route)
                },
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.Trakt.route) {
            TraktScreen(
                onBackPress = {
                    navController.previousBackStackEntry
                        ?.takeIf {
                            it.destination.route ==
                                Screen.Settings.route
                        }
                        ?.savedStateHandle
                        ?.set(
                            "settings_tracking_return_focus",
                            "trakt"
                        )
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.Simkl.route) {
            SimklScreen(
                onBackPress = {
                    navController.previousBackStackEntry
                        ?.takeIf {
                            it.destination.route ==
                                Screen.Settings.route
                        }
                        ?.savedStateHandle
                        ?.set(
                            "settings_tracking_return_focus",
                            "simkl"
                        )
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.TmdbSettings.route) {
            TmdbSettingsScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.ThemeSettings.route) {
            ThemeSettingsScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.PlaybackSettings.route) {
            PlaybackSettingsScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.About.route) {
            AboutScreen(
                onBackPress = { navController.popBackStack() },
                onNavigateToSupportersContributors = {
                    navController.navigate(Screen.SupportersContributors.route)
                }
            )
        }

        composable(Screen.SupportersContributors.route) {
            SupportersContributorsScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.AddonManager.route,
            popExitTransition = {
                rootDestinationPopExit(
                    destinationName = "addons",
                    targetRoute = targetState.destination.route
                )
            }
        ) {
            SidebarRootBackToHome { popSidebarRootToHome() }
            val homeBackStackEntry = navController.getBackStackEntry(Screen.Home.route)
            val homeViewModel: HomeViewModel = hiltViewModel(homeBackStackEntry)
            RecordSidebarRootFrame(sidebarRootReturnLayer) {
                SettingsGlassScreen {
                    AddonManagerScreen(
                        showBuiltInHeader = !hideBuiltInHeaders,
                        onNavigateToCatalogOrder = { navController.navigate(Screen.CatalogOrder.route) },
                        onRefreshCatalogs = { homeViewModel.forceReloadCatalogs() }
                    )
                }
            }
        }

        composable(Screen.CatalogOrder.route) {
            SettingsGlassScreen {
                CatalogOrderScreen(
                    onBackPress = { navController.popBackStack() }
                )
            }
        }

        composable(Screen.Plugins.route) {
            PluginScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.Account.route) {
            AuthQrSignInScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.AuthSignIn.route) {
            AuthSignInScreen(
                onBackPress = { navController.popBackStack() },
                onNavigateToQrSignIn = { navController.navigate(Screen.AuthQrSignIn.route) },
                onSuccess = { navController.popBackStack() }
            )
        }

        composable(Screen.AuthQrSignIn.route) {
            AuthQrSignInScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.LayoutSettings.route) {
            LayoutSettingsScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.CatalogSeeAll.route,
            arguments = listOf(
                navArgument("catalogId") { type = NavType.StringType },
                navArgument("addonId") { type = NavType.StringType },
                navArgument("type") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val catalogId = backStackEntry.arguments?.getString("catalogId") ?: ""
            val addonId = backStackEntry.arguments?.getString("addonId") ?: ""
            val type = backStackEntry.arguments?.getString("type") ?: ""
            CatalogSeeAllScreen(
                catalogId = catalogId,
                addonId = addonId,
                type = type,
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    navController.navigate(Screen.Detail.createRoute(itemId, itemType, addonBaseUrl))
                },
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.CastDetail.route,
            arguments = listOf(
                navArgument("personId") { type = NavType.StringType },
                navArgument("personName") { type = NavType.StringType },
                navArgument("preferCrew") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) {
            CastDetailScreen(
                onBackPress = { navController.popBackStack() },
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    navController.navigate(Screen.Detail.createRoute(itemId, itemType, addonBaseUrl))
                }
            )
        }
    }

        if (showSidebarRootReturnOverlay.value) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = sidebarRootReturnOverlayAlpha.value
                    }
            ) {
                drawLayer(sidebarRootReturnLayer)
            }
        }

        if (showDetailReturnOverlay.value) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = detailReturnOverlayAlpha.value
                    }
            ) {
                drawLayer(detailReturnLayer)
            }
        }
    }
}
