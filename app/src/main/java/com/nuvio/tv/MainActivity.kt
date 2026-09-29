package com.nuvio.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Context
import android.content.res.Configuration
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.view.View
import android.view.PixelCopy
import android.view.Window
import androidx.core.os.ConfigurationCompat
import android.util.Log
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import java.util.Locale
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.data.local.AppOnboardingDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.ThemeDataStore
import com.nuvio.tv.core.tracking.TrackingProgressRefreshCoordinator
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.domain.model.AppFont
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.core.sync.ProfileSyncService
import com.nuvio.tv.core.sync.StartupSyncService
import com.nuvio.tv.data.remote.supabase.AvatarRepository
import com.nuvio.tv.core.homechannel.DeepLinkHandler
import com.nuvio.tv.ui.navigation.NuvioNavHost
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.components.NuvioScrollDefaults
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.screens.account.AuthQrSignInScreen
import com.nuvio.tv.ui.screens.profile.ProfileSelectionScreen
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.updater.UpdateViewModel
import com.nuvio.tv.updater.ui.UpdatePromptDialog
import dagger.hilt.android.AndroidEntryPoint
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import coil.compose.rememberAsyncImagePainter
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CardDepthStyle
import com.nuvio.tv.ui.components.LocalCardDepthStyle

val LocalIsScrolling = compositionLocalOf { false }
val LocalBackgroundedAtMs = compositionLocalOf { 0L }
val LocalSidebarExpanded = compositionLocalOf { false }
val LocalAppInForeground = compositionLocalOf { true }
val LocalNoBackdropImage = compositionLocalOf { false }
val LocalContentFocusRequester = compositionLocalOf { FocusRequester.Default }
val LocalCarouselFocusRequester = compositionLocalOf { FocusRequester.Default }
val LocalSidebarOpenRequest = compositionLocalOf<() -> Unit> { {} }
val LocalHomeHeroTrailerPlaying =
    compositionLocalOf<androidx.compose.runtime.MutableState<Boolean>> {
        androidx.compose.runtime.mutableStateOf(false)
    }
val LocalPreserveSidebarTrailerPlayback = compositionLocalOf { false }

/*
 * Visual-only Home focus retention while the LEGACY sidebar is handing
 * actual focus back to the exact poster/CW card.
 */
val LocalSidebarFocusRestoreActive = compositionLocalOf { false }
val LocalRowFocusRestorer = compositionLocalOf<androidx.compose.runtime.MutableState<FocusRequester>> { androidx.compose.runtime.mutableStateOf(FocusRequester.Default) }
val LocalSettingsBackdropBitmap = compositionLocalOf<Bitmap?> { null }

data class DrawerItem(
    val route: String,
    val label: String,
    val iconRes: Int? = null,
    val icon: ImageVector? = null
)

private fun captureSettingsBackdrop(
    window: Window?,
    view: View,
    onCaptured: (Bitmap?) -> Unit
) {
    if (view.width <= 0 || view.height <= 0) {
        onCaptured(null)
        return
    }

    val bitmap = Bitmap.createBitmap(
        view.width,
        view.height,
        Bitmap.Config.ARGB_8888
    )

    if (
        window != null &&
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
    ) {
        PixelCopy.request(
            window,
            bitmap,
            { result ->
                if (result == PixelCopy.SUCCESS) {
                    onCaptured(bitmap)
                } else {
                    Log.w(
                        "SettingsGlass",
                        "PixelCopy failed ($result); using View.draw fallback"
                    )
                    val fallback = runCatching {
                        view.draw(AndroidCanvas(bitmap))
                        bitmap
                    }.getOrNull()
                    if (fallback == null) bitmap.recycle()
                    onCaptured(fallback)
                }
            },
            Handler(Looper.getMainLooper())
        )
    } else {
        val captured = runCatching {
            view.draw(AndroidCanvas(bitmap))
            bitmap
        }.getOrNull()
        if (captured == null) bitmap.recycle()
        onCaptured(captured)
    }
}

private data class MainUiPrefs(
    val theme: AppTheme = AppTheme.WHITE,
    val font: AppFont = AppFont.INTER,
    val hasChosenLayout: Boolean? = null,
    val cardDepthStyle: CardDepthStyle = CardDepthStyle()
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var themeDataStore: ThemeDataStore

    @Inject
    lateinit var layoutPreferenceDataStore: LayoutPreferenceDataStore

    @Inject
    lateinit var trackingProgressRefreshCoordinator: TrackingProgressRefreshCoordinator

    @Inject
    lateinit var startupSyncService: StartupSyncService

    @Inject
    lateinit var profileSyncService: ProfileSyncService

    @Inject
    lateinit var profileManager: ProfileManager

    @Inject
    lateinit var authManager: AuthManager

    @Inject
    lateinit var appOnboardingDataStore: AppOnboardingDataStore

    @Inject
    lateinit var avatarRepository: AvatarRepository

    private lateinit var jankStats: JankStats

    @OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
    override fun attachBaseContext(newBase: Context) {
        val tag = newBase.getSharedPreferences("app_locale", Context.MODE_PRIVATE)
            .getString("locale_tag", null)
        if (!tag.isNullOrEmpty()) {
            val locale = Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
            val config = Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            val systemLocale = ConfigurationCompat.getLocales(newBase.resources.configuration)[0]
                ?: Locale.getDefault(Locale.Category.DISPLAY)
            Locale.setDefault(systemLocale)
            super.attachBaseContext(newBase)
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        if (intent?.data?.scheme == "nuvio") {
            splashScreen.setKeepOnScreenCondition { false }
            val profileId = DeepLinkHandler.extractProfileId(intent)
            _incomingProfileId.value = profileId
            if (profileId != null) _deepLinkSkipProfilePicker.value = true
        }
        val effectiveBundle = if (intent?.data?.scheme == "nuvio") null else savedInstanceState
        super.onCreate(effectiveBundle)

        // Prefetch avatar images early so they are cached before profile picker shows.
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val catalog = avatarRepository.getAvatarCatalog()
                val imageLoader = coil.Coil.imageLoader(applicationContext)
                val urlsToPrefetch = buildList {
                    catalog.filter { !it.imageUrl.startsWith("res://") }
                        .forEach { add(it.imageUrl) }
                    profileManager.profiles.value
                        .mapNotNull { p -> p.avatarUrl?.takeIf { it.isNotBlank() } }
                        .forEach { add(it) }
                }.distinct()
                urlsToPrefetch.forEach { url ->
                    imageLoader.enqueue(
                        coil.request.ImageRequest.Builder(applicationContext)
                            .data(url)
                            .build()
                    )
                }
            }
        }

        // Overdraw fix: the theme windowBackground (splash drawable) stays attached as a
        // full-screen layer under the opaque Compose Surface on every frame. Drop it after
        // the first frame renders so the splash still covers app startup.
        window.decorView.post {
            window.setBackgroundDrawable(null)
        }
        setContent {
            // If launched from a home screen channel deep link with a profileId,
            // pre-select that profile and skip the profile picker entirely.
            val deepLinkProfileId by _incomingProfileId.collectAsState(initial = _incomingProfileId.value)
            val skipPickerForDeepLink by _deepLinkSkipProfilePicker.collectAsState(initial = _deepLinkSkipProfilePicker.value)
            var hasSelectedProfileThisSession by remember {
                mutableStateOf(false)
            }
            var onboardingCompletedThisSession by remember { mutableStateOf(false) }
            var onboardingProfileSyncInProgress by remember { mutableStateOf(false) }
            val hasSeenAuthQrOnFirstLaunch by appOnboardingDataStore
                .hasSeenAuthQrOnFirstLaunch
                .map<Boolean, Boolean?> { it }
                .collectAsState(initial = null)
            val authState by authManager.authState.collectAsState()

            LaunchedEffect(hasSeenAuthQrOnFirstLaunch, authState) {
                if (hasSeenAuthQrOnFirstLaunch == false && authState is AuthState.FullAccount) {
                    appOnboardingDataStore.setHasSeenAuthQrOnFirstLaunch(true)
                    onboardingCompletedThisSession = true
                }
            }

            val activeProfileId by profileManager.activeProfileId.collectAsState()
            val profiles by profileManager.profiles.collectAsState()
            val activeProfile = remember(activeProfileId, profiles) {
                profiles.firstOrNull { it.id == activeProfileId }
            }
            var avatarCatalog by remember { mutableStateOf(emptyList<com.nuvio.tv.data.remote.supabase.AvatarCatalogItem>()) }

            LaunchedEffect(Unit) {
                avatarCatalog = runCatching { avatarRepository.getAvatarCatalog() }
                    .getOrDefault(emptyList())
                // Prefetch all remote avatar images into Coil's disk cache so
                // they appear instantly when the profile picker opens.

            }

            val activeProfileAvatarImageUrl = remember(activeProfile, avatarCatalog) {
                activeProfile?.avatarId?.let { avatarRepository.getAvatarImageUrl(it, avatarCatalog) }
            }

            // Prefetch avatar URLs for all profiles whenever profiles list changes
            LaunchedEffect(profiles) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val imageLoader = coil.Coil.imageLoader(applicationContext)
                        profiles.mapNotNull { p -> p.avatarUrl?.takeIf { it.isNotBlank() } }
                            .distinct()
                            .forEach { url ->
                                imageLoader.enqueue(
                                    coil.request.ImageRequest.Builder(applicationContext)
                                        .data(url)
                                        .build()
                                )
                            }
                    }
                }
            }

            val mainUiPrefsFlow = remember(themeDataStore, layoutPreferenceDataStore) {
                combine(
                    themeDataStore.selectedTheme,
                    themeDataStore.selectedFont,
                    layoutPreferenceDataStore.hasChosenLayout,
                ) { theme, font, hasChosenLayout ->
                    MainUiPrefs(
                        theme = theme,
                        font = font,
                        hasChosenLayout = hasChosenLayout,
                    )
                }.combine(layoutPreferenceDataStore.cardDepthStyle) { prefs, cardDepthStyle ->
                    prefs.copy(cardDepthStyle = cardDepthStyle)
                }
            }
            val mainUiPrefs by mainUiPrefsFlow.collectAsState(initial = MainUiPrefs(hasChosenLayout = null))

            NuvioTheme(appTheme = mainUiPrefs.theme, appFont = mainUiPrefs.font) {
                CompositionLocalProvider(
                    LocalBringIntoViewSpec provides NuvioScrollDefaults.smoothScrollSpec,
                      LocalCardDepthStyle provides mainUiPrefs.cardDepthStyle
                ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape,
                    colors = SurfaceDefaults.colors(
                        containerColor = NuvioColors.Background
                    )
                ) {
                    if (hasSeenAuthQrOnFirstLaunch == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(NuvioColors.Background)
                        )
                        return@Surface
                    }

                    if (
                        hasSeenAuthQrOnFirstLaunch == false &&
                        authState !is AuthState.FullAccount &&
                        !onboardingCompletedThisSession
                    ) {
                        AuthQrSignInScreen(
                            onBackPress = {},
                            onContinue = {
                                lifecycleScope.launch {
                                    val shouldRunRemoteOnboardingSync =
                                        authManager.authState.value is AuthState.FullAccount

                                    if (shouldRunRemoteOnboardingSync) {
                                        if (onboardingProfileSyncInProgress) return@launch
                                        onboardingProfileSyncInProgress = true
                                        val maxAttempts = 3
                                        var synced = false
                                        for (attempt in 0 until maxAttempts) {
                                            val result = profileSyncService.pullFromRemote()
                                            if (result.isSuccess) {
                                                synced = true
                                                break
                                            }
                                            if (attempt < maxAttempts - 1) {
                                                delay(1_000)
                                            }
                                        }
                                        if (!synced) {
                                            android.util.Log.w(
                                                "MainActivity",
                                                "Onboarding profile sync failed after retries; continuing"
                                            )
                                        }
                                    }
                                    appOnboardingDataStore.setHasSeenAuthQrOnFirstLaunch(true)
                                    onboardingCompletedThisSession = true
                                    onboardingProfileSyncInProgress = false
                                }
                                if (authManager.authState.value is AuthState.FullAccount) {
                                    startupSyncService.requestSyncNow()
                                }
                            }
                        )
                        return@Surface
                    }

                    // Auto-select profile from deep link on cold start.
                    // Runs after profiles are loaded from DataStore so the profile
                    // is guaranteed to exist before we call setActiveProfile.
                    // Handles both cold-start and already-running (onNewIntent) cases.
                    // deepLinkProfileId is backed by a StateFlow so it updates on onNewIntent.
                    LaunchedEffect(deepLinkProfileId, profiles) {
                        val profileId = deepLinkProfileId ?: return@LaunchedEffect
                        if (profiles.any { it.id == profileId }) {
                            profileManager.setActiveProfile(profileId)
                            hasSelectedProfileThisSession = true
                            if (authManager.authState.value is AuthState.FullAccount) {
                                startupSyncService.requestSyncNow()
                            }
                            // Reset after handling so the picker works normally next time
                            _deepLinkSkipProfilePicker.value = false
                            _incomingProfileId.value = null
                        }
                    }

                    // Compute startDestination and create navController BEFORE the profile
                    // picker check so they survive picker show/hide recompositions.
                    // Using remember ensures intentDeepLinkRoute is only computed once
                    // from the original launch intent, not re-evaluated after profile switch.
                    val intentDeepLinkRoute = remember {
                        val uri = intent?.data ?: return@remember null
                        if (uri.scheme != "nuvio") return@remember null
                        val host = uri.host ?: return@remember null
                        val path = uri.path?.trimStart('/') ?: ""
                        val fullPath = if (path.isBlank()) host else "$host/$path"
                        if (fullPath.startsWith("detail/")) {
                            val parts = fullPath.removePrefix("detail/").split("/")
                            if (parts.size >= 2) {
                                val contentId = parts[0]
                                val contentType = parts[1]
                                val addonBaseUrl = uri.getQueryParameter("addonBaseUrl") ?: ""
                                Screen.Detail.createRoute(contentId, contentType, addonBaseUrl, returnToHomeOnBack = true)
                            } else null
                        } else null
                    }
                    val navController = rememberNavController()
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = navBackStackEntry?.destination?.route

                    val shouldShowProfileSelection =
                        !hasSelectedProfileThisSession && profiles.size > 1 &&
                        !skipPickerForDeepLink

                    if (shouldShowProfileSelection) {
                        ProfileSelectionScreen(
                            onProfileSelected = {
                                hasSelectedProfileThisSession = true
                                if (authManager.authState.value is AuthState.FullAccount) {
                                    startupSyncService.requestSyncNow()
                                }
                            }
                        )
                        return@Surface
                    }

                    val layoutChosen = mainUiPrefs.hasChosenLayout
                    if (layoutChosen == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(NuvioColors.Background)
                        )
                        return@Surface
                    }
                    // Nuvio Enhanced uses one supported sidebar configuration:
                    // the polished collapsed legacy sidebar.
                    val sidebarCollapsed = true
                    val modernSidebarEnabled = false
                    val modernSidebarBlurEnabled = false
                    val hideBuiltInHeadersForFloatingPill = false

                    val startDestination = intentDeepLinkRoute ?: if (layoutChosen) Screen.Home.route else Screen.LayoutSelection.route

                    val updateViewModel: UpdateViewModel = hiltViewModel(this@MainActivity)
                    val updateState by updateViewModel.uiState.collectAsState()

                    val pendingDeepLinkIntentState = remember { androidx.compose.runtime.mutableStateOf<android.content.Intent?>(null) }
                    var pendingDeepLinkIntent by pendingDeepLinkIntentState
                    _pendingDeepLinkIntent = pendingDeepLinkIntentState
                    LaunchedEffect(pendingDeepLinkIntent, currentRoute) {
                        val pending = pendingDeepLinkIntent ?: return@LaunchedEffect
                        if (pending.data?.scheme == "nuvio" && currentRoute != null) {
                            DeepLinkHandler.handle(pending, navController)
                            pendingDeepLinkIntent = null
                        }
                    }



                    val view = LocalView.current
                    LaunchedEffect(currentRoute) {
                        val holder = PerformanceMetricsState.getHolderForHierarchy(view)
                        if (currentRoute != null) {
                            holder.state?.putState("Screen", currentRoute)
                        }
                    }

                    val rootRoutes = remember {
                        setOf(
                            Screen.Home.route,
                            Screen.Search.route,
                            Screen.Library.route,
                            Screen.Settings.route,
                            Screen.AddonManager.route
                        )
                    }

                    val strNavHome = stringResource(R.string.nav_home)
                    val strNavSearch = stringResource(R.string.nav_search)
                    val strNavLibrary = stringResource(R.string.nav_library)
                    val strNavAddons = stringResource(R.string.nav_addons)
                    val strNavSettings = stringResource(R.string.nav_settings)
                    val drawerItems = remember(
                        strNavHome,
                        strNavSearch,
                        strNavLibrary,
                        strNavAddons,
                        strNavSettings
                    ) {
                        listOf(
                            DrawerItem(
                                route = Screen.Home.route,
                                label = strNavHome,
                                icon = Icons.Default.Home
                            ),
                            DrawerItem(
                                route = Screen.Search.route,
                                label = strNavSearch,
                                iconRes = R.raw.sidebar_search
                            ),
                            DrawerItem(
                                route = Screen.Library.route,
                                label = strNavLibrary,
                                iconRes = R.raw.sidebar_library
                            ),
                            DrawerItem(
                                route = Screen.AddonManager.route,
                                label = strNavAddons,
                                iconRes = R.raw.sidebar_plugin
                            ),
                            DrawerItem(
                                route = Screen.Settings.route,
                                label = strNavSettings,
                                iconRes = R.raw.sidebar_settings
                            )
                        )
                    }
                    val selectedDrawerRoute = drawerItems.firstOrNull { item ->
                        currentRoute == item.route || currentRoute?.startsWith("${item.route}/") == true
                    }?.route
                    val selectedDrawerItem = drawerItems.firstOrNull { it.route == selectedDrawerRoute } ?: drawerItems.first()

                    val appInForeground by _appInForeground

                    if (modernSidebarEnabled) {
                        ModernSidebarScaffold(
                            appInForeground = appInForeground,
                            backgroundedAtMs = _backgroundedAtMs,
                            navController = navController,
                            startDestination = startDestination,
                            currentRoute = currentRoute,
                            rootRoutes = rootRoutes,
                            drawerItems = drawerItems,
                            selectedDrawerRoute = selectedDrawerRoute,
                            selectedDrawerItem = selectedDrawerItem,
                            sidebarCollapsed = sidebarCollapsed,
                            modernSidebarBlurEnabled = modernSidebarBlurEnabled,
                            hideBuiltInHeaders = hideBuiltInHeadersForFloatingPill,
                            activeProfileName = activeProfile?.name ?: "",
                            activeProfileColorHex = activeProfile?.avatarColorHex ?: "#1E88E5",
                            activeProfileAvatarImageUrl = activeProfileAvatarImageUrl,
                            showProfileSelector = profiles.size > 1,
                            onSwitchProfile = { hasSelectedProfileThisSession = false },
                            onExitApp = {
                                finishAffinity()
                                finishAndRemoveTask()
                            }
                        )
                    } else {
                        LegacySidebarScaffold(
                            appInForeground = appInForeground,
                            backgroundedAtMs = _backgroundedAtMs,
                            navController = navController,
                            startDestination = startDestination,
                            currentRoute = currentRoute,
                            rootRoutes = rootRoutes,
                            drawerItems = drawerItems,
                            selectedDrawerRoute = selectedDrawerRoute,
                            sidebarCollapsed = sidebarCollapsed,
                            hideBuiltInHeaders = false,
                            activeProfileName = activeProfile?.name ?: "",
                            activeProfileColorHex = activeProfile?.avatarColorHex ?: "#1E88E5",
                            activeProfileAvatarImageUrl = activeProfileAvatarImageUrl,
                            showProfileSelector = profiles.size > 1,
                            onSwitchProfile = { hasSelectedProfileThisSession = false },
                            onExitApp = {
                                finishAffinity()
                                finishAndRemoveTask()
                            }
                        )
                    }

                    if (!com.nuvio.tv.BuildConfig.IS_DEBUG_BUILD) UpdatePromptDialog(
                        state = updateState,
                        onDismiss = { updateViewModel.dismissDialog() },
                        onDownload = { updateViewModel.downloadUpdate() },
                        onInstall = { updateViewModel.installUpdateOrRequestPermission() },
                        onIgnore = { updateViewModel.ignoreThisVersion() },
                        onOpenUnknownSources = { updateViewModel.openUnknownSourcesSettings() }
                    )
                }
            }
            }
        }

        jankStats = JankStats.createAndTrack(window) { frameData ->
            if (frameData.isJank) {
                Log.w(
                    "JankStats",
                    "JANK: ${frameData.frameDurationUiNanos / 1_000_000}ms | states: ${frameData.states}"
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        _appInForeground.value = true
        if (::jankStats.isInitialized) jankStats.isTrackingEnabled = true
        startupSyncService.requestSyncNow()
        lifecycleScope.launch {
            trackingProgressRefreshCoordinator.refreshSelected(
                TrackingRefreshIntent.FOREGROUND
            )
        }
        // If resumed without a deep link intent, reset the skip-picker flag
        // so the profile picker works normally on next manual open.
        if (intent?.data?.scheme != "nuvio") {
            _deepLinkSkipProfilePicker.value = false
            _incomingProfileId.value = null
        }
    }

    override fun onPause() {
        super.onPause()
        _appInForeground.value = false
        _backgroundedAtMs = System.currentTimeMillis()
        if (::jankStats.isInitialized) jankStats.isTrackingEnabled = false
    }

    override fun onStart() {
        super.onStart()
    }

    private val _appInForeground = androidx.compose.runtime.mutableStateOf(true)
    private var _backgroundedAtMs = 0L
    private var _pendingDeepLinkIntent: androidx.compose.runtime.MutableState<android.content.Intent?>? = null
    private val _incomingProfileId = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    // True when app was launched or resumed via a channel deep link with a profileId.
    // Set before setContent runs so first composition already sees the correct value.
    private val _deepLinkSkipProfilePicker = kotlinx.coroutines.flow.MutableStateFlow(false)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        _pendingDeepLinkIntent?.value = intent
        val profileId = DeepLinkHandler.extractProfileId(intent)
        _incomingProfileId.value = profileId
        if (profileId != null) _deepLinkSkipProfilePicker.value = true
    }

}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LegacySidebarScaffold(
    appInForeground: Boolean,
    backgroundedAtMs: Long,
    navController: NavHostController,
    startDestination: String,
    currentRoute: String?,
    rootRoutes: Set<String>,
    drawerItems: List<DrawerItem>,
    selectedDrawerRoute: String?,
    sidebarCollapsed: Boolean,
    hideBuiltInHeaders: Boolean,
    activeProfileName: String,
    activeProfileColorHex: String,
    activeProfileAvatarImageUrl: String?,
    showProfileSelector: Boolean,
    onSwitchProfile: () -> Unit,
    onExitApp: () -> Unit
) {
    var isLegacySidebarOpen by remember { mutableStateOf(false) }
    val legacyDrawerProgress = remember { Animatable(0f) }
    val drawerItemFocusRequesters = remember(drawerItems) {
        drawerItems.associate { item -> item.route to FocusRequester() }
    }
    val showSidebar = currentRoute in rootRoutes

    val closedDrawerWidth = if (sidebarCollapsed) 0.dp else 72.dp
    val openDrawerWidth = 202.dp

    val focusManager = LocalFocusManager.current
    val contentFocusRequester = remember { FocusRequester() }
    val rowFocusRestorer = remember { androidx.compose.runtime.mutableStateOf(FocusRequester.Default) }
    val homeHeroTrailerPlaying = remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    var preserveSidebarTrailerPlayback by remember {
        mutableStateOf(false)
    }
    var pendingContentFocusTransfer by remember { mutableStateOf(false) }
    var pendingSidebarFocusRequest by remember { mutableStateOf(false) }
    var legacyLeftAtEdge by remember { mutableStateOf(false) }
    var legacyLeftReleasedSinceEdge by remember { mutableStateOf(false) }
    val legacyRootTransitionScope = rememberCoroutineScope()
    val legacyRootTransitionAlpha = remember { Animatable(0f) }
    var legacyRootTransitionBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var legacyRootTransitionTarget by remember { mutableStateOf<String?>(null) }
    val legacyHostView = LocalView.current
    val legacyActivity = LocalContext.current as? Activity
    var settingsBackdropBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var settingsCaptureInProgress by remember { mutableStateOf(false) }
    fun openLegacySidebar() {
        if (settingsCaptureInProgress) return

        /*
         * Snapshot Home's playing state before opening the sidebar steals
         * focus. This is intentionally decided at the open request rather
         * than after isLegacySidebarOpen changes.
         */
        preserveSidebarTrailerPlayback =
            homeHeroTrailerPlaying.value
        if (currentRoute == Screen.Settings.route) {
            pendingSidebarFocusRequest = true
            isLegacySidebarOpen = true
            return
        }
        settingsCaptureInProgress = true
        captureSettingsBackdrop(
            window = legacyActivity?.window,
            view = legacyHostView
        ) { captured ->
            settingsCaptureInProgress = false
            if (captured != null) {
                settingsBackdropBitmap?.takeIf { it !== captured }?.recycle()
                settingsBackdropBitmap = captured
            }
            pendingSidebarFocusRequest = true
            isLegacySidebarOpen = true
        }
    }
    LaunchedEffect(
        currentRoute,
        legacyRootTransitionTarget,
        legacyRootTransitionBitmap
    ) {
        val targetRoute = legacyRootTransitionTarget
        val frozenBitmap = legacyRootTransitionBitmap
        if (
            targetRoute != null &&
            frozenBitmap != null &&
            currentRoute == targetRoute
        ) {
            /*
             * Let the incoming SettingsGlassScreen-backed destination finish
             * establishing its blurred backdrop and haze environment before
             * revealing it.
             */
            repeat(3) { withFrameNanos { } }
            legacyRootTransitionAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = 260,
                    easing = LinearEasing
                )
            )

            legacyRootTransitionBitmap = null
            legacyRootTransitionTarget = null
            legacyRootTransitionAlpha.snapTo(0f)

            if (
                frozenBitmap !== settingsBackdropBitmap &&
                !frozenBitmap.isRecycled
            ) {
                frozenBitmap.recycle()
            }
        }
    }

    val legacySidebarHazeState = remember { HazeState() }
    val legacySidebarBlurEnabled =
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    val legacyDrawerShape = RoundedCornerShape(24.dp)
    val legacyDrawerBackgroundBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xAD2A3038),
                Color(0x9E20252C),
                Color(0xA824292F)
            )
        )
    }
    val legacyDrawerBorderColor = Color.White.copy(alpha = 0.09f)
    val legacyDrawerVisible by remember {
        derivedStateOf {
            isLegacySidebarOpen || legacyDrawerProgress.value > 0.001f
        }
    }
    val legacySidebarHazeCaptureActive by remember {
        derivedStateOf {
            legacySidebarBlurEnabled &&
                legacyDrawerVisible
        }
    }

    LaunchedEffect(isLegacySidebarOpen) {
        if (isLegacySidebarOpen) {
            legacyDrawerProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 520,
                    easing = LinearOutSlowInEasing
                )
            )
        } else {
            legacyDrawerProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = 260,
                    easing = FastOutSlowInEasing
                )
            )
        }
    }

    BackHandler(enabled = currentRoute in rootRoutes && !isLegacySidebarOpen) {
        openLegacySidebar()
    }

    BackHandler(enabled = currentRoute in rootRoutes && isLegacySidebarOpen) {
        onExitApp()
    }

    LaunchedEffect(isLegacySidebarOpen, pendingContentFocusTransfer) {
        if (!pendingContentFocusTransfer || isLegacySidebarOpen) {
            return@LaunchedEffect
        }
        delay(210L)
        if (isLegacySidebarOpen) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        val restorer = rowFocusRestorer.value
        val restoredExactItem =
            restorer != androidx.compose.ui.focus.FocusRequester.Default &&
                runCatching { restorer.requestFocus() }.getOrDefault(false)
        if (!restoredExactItem) {
            runCatching { contentFocusRequester.requestFocus() }
        }

        /*
         * requestFocus() completes before LazyRow/LazyColumn bring-into-view
         * settlement necessarily finishes. Keep the existing restore-active
         * handoff alive across the following layout frames so Home can suppress
         * restore-induced scrolling without affecting normal navigation.
         */
        repeat(2) { withFrameNanos { } }

        preserveSidebarTrailerPlayback = false
        pendingContentFocusTransfer = false
    }

    LaunchedEffect(isLegacySidebarOpen, selectedDrawerRoute, showSidebar, pendingSidebarFocusRequest) {
        if (!showSidebar || !pendingSidebarFocusRequest || !isLegacySidebarOpen) {
            return@LaunchedEffect
        }
        withFrameNanos { }
        if (!isLegacySidebarOpen) return@LaunchedEffect
        val targetRoute = selectedDrawerRoute ?: run {
            pendingSidebarFocusRequest = false
            return@LaunchedEffect
        }
        val requester = drawerItemFocusRequesters[targetRoute] ?: run {
            pendingSidebarFocusRequest = false
            return@LaunchedEffect
        }
        runCatching { requester.requestFocus() }
        pendingSidebarFocusRequest = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
            if (showSidebar && legacyDrawerVisible) {
                val isExpanded = legacyDrawerVisible
                val progress = legacyDrawerProgress.value.coerceIn(0f, 1f)
                val widthPhase =
                    FastOutSlowInEasing.transform(
                        (progress / 0.90f).coerceIn(0f, 1f)
                    )
                val heightPhase =
                    LinearOutSlowInEasing.transform(
                        ((progress - 0.04f) / 0.96f).coerceIn(0f, 1f)
                    )
                val drawerWidth =
                    22.dp +
                        (
                            (openDrawerWidth - 22.dp) *
                                (0.42f + (0.58f * widthPhase))
                            )
                val drawerHeightFraction =
                    0.065f + (0.935f * heightPhase)
                val drawerSurfaceAlpha =
                    LinearOutSlowInEasing.transform(
                        (progress / 0.92f).coerceIn(0f, 1f)
                    )
                val drawerContentProgress =
                    FastOutSlowInEasing.transform(
                        ((progress - 0.28f) / 0.62f)
                            .coerceIn(0f, 1f)
                    )
                val drawerBlurModifier = if (legacySidebarHazeCaptureActive && isExpanded) {
                    Modifier.hazeChild(
                        state = legacySidebarHazeState,
                        shape = legacyDrawerShape,
                        tint = Color.Unspecified,
                        blurRadius = (1f + (29f * progress)).dp,
                        noiseFactor = 0.025f * progress
                    )
                } else {
                    Modifier
                }
                val drawerSurfaceModifier = if (isExpanded) {
                    Modifier
                        .padding(start = 14.dp, top = 16.dp, end = 8.dp, bottom = 16.dp)
                        .then(drawerBlurModifier)
                        .graphicsLayer {
                            shape = legacyDrawerShape
                            clip = true
                        }
                        .clip(legacyDrawerShape)
                        .background(
                            brush = legacyDrawerBackgroundBrush,
                            shape = legacyDrawerShape
                        )
                        .border(
                            width = 1.dp,
                            color = legacyDrawerBorderColor,
                            shape = legacyDrawerShape
                        )
                } else {
                    Modifier.background(NuvioColors.Background)
                }
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(openDrawerWidth)
                        .zIndex(1f)
                ) {
                    /*
                     * Animate real layout bounds instead of drawing a
                     * full-size Haze child behind a visually scaled panel.
                     * The blur and tinted surface now share one size at every
                     * frame, so there is never a second window underneath.
                     */
                    Box(
                        modifier = Modifier
                            .width(drawerWidth)
                            .fillMaxHeight(drawerHeightFraction)
                            .graphicsLayer {
                                alpha = drawerSurfaceAlpha
                            }
                            .then(drawerSurfaceModifier)
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(openDrawerWidth)
                            .padding(
                                start = 14.dp,
                                top = 16.dp,
                                end = 8.dp,
                                bottom = 16.dp
                            )
                            .padding(12.dp)
                            .graphicsLayer {
                                alpha = drawerContentProgress
                            }
                            .selectableGroup()
                            .onPreviewKeyEvent { keyEvent ->
                            if (
                                isLegacySidebarOpen &&
                                keyEvent.key == Key.DirectionRight &&
                                keyEvent.type == KeyEventType.KeyDown
                            ) {
                                isLegacySidebarOpen = false
                                // Keep focus on the drawer item until the exit is
                                // fully offscreen. The delayed transfer above then
                                // restores the exact content item when possible.
                                pendingContentFocusTransfer = true
                                true
                            } else {
                                false
                            }
                        }
                    ) {
                    val itemWidth = if (isExpanded) 156.dp else 48.dp
                    /*
                     * The visual focus bubble follows the same continuous
                     * progress as the panel. Actual Compose focus is still
                     * managed independently, so D-pad input cannot enter the
                     * menu before the drawer is ready.
                     */
                    val focusPresentationProgress =
                        FastOutSlowInEasing.transform(
                            (legacyDrawerProgress.value / 0.94f)
                                .coerceIn(0f, 1f)
                        )
                    val focusScaleReady = legacyDrawerProgress.value >= 0.985f

                    if (isExpanded) {
                        Spacer(modifier = Modifier.height(30.dp))
                        if (showProfileSelector && activeProfileName.isNotEmpty()) {
                            var isProfileFocused by remember { mutableStateOf(false) }
                            val profileFocusScale by animateFloatAsState(
                                targetValue =
                                    if (isProfileFocused && focusScaleReady) 1.04f else 1f,
                                animationSpec = tween(
                                    durationMillis = 160,
                                    easing = FastOutSlowInEasing
                                ),
                                label = "legacyProfileFocusScale"
                            )
                            val profileItemShape = RoundedCornerShape(32.dp)
                            val profileLeadingInset = 18.dp
                            val profileAvatarSize = 34.dp
                            val profileLabelStart = 60.dp
                            val profileGapAfterAvatar =
                                (profileLabelStart - profileLeadingInset - profileAvatarSize).coerceAtLeast(0.dp)
                            val profileBgColor by animateColorAsState(
                                targetValue =
                                    if (isProfileFocused) {
                                        Color.White.copy(
                                            alpha =
                                                0.16f *
                                                    focusPresentationProgress
                                        )
                                    } else {
                                        Color.Transparent
                                    },
                                label = "legacyProfileItemBg"
                            )
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    modifier = Modifier
                                        .width(itemWidth)
                                        .height(52.dp)
                                        .clip(profileItemShape)
                                        .background(color = profileBgColor, shape = profileItemShape)
                                        .graphicsLayer {
                                            scaleX = profileFocusScale
                                            scaleY = profileFocusScale
                                        }
                                        .focusProperties { canFocus = legacyDrawerVisible }
                                        .onFocusChanged { isProfileFocused = it.isFocused }
                                        .onPreviewKeyEvent { event ->
                                            event.key == Key.DirectionUp
                                        }
                                        .clickable {
                                            onSwitchProfile()
                                            isLegacySidebarOpen = false
                                        },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Spacer(modifier = Modifier.width(profileLeadingInset))
                                    ProfileAvatarCircle(
                                        name = activeProfileName,
                                        colorHex = activeProfileColorHex,
                                        size = profileAvatarSize,
                                        avatarImageUrl = activeProfileAvatarImageUrl
                                    )
                                    Spacer(modifier = Modifier.width(profileGapAfterAvatar))
                                    Text(
                                        text = activeProfileName,
                                        color =
                                            if (
                                                isProfileFocused &&
                                                    focusPresentationProgress > 0.01f
                                            ) {
                                                NuvioColors.TextPrimary
                                            } else {
                                                NuvioColors.TextSecondary
                                            },
                                        fontSize = 15.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Start,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        } else {
                            Image(
                                painter = painterResource(id = R.drawable.app_logo_wordmark),
                                contentDescription = "NuvioTV",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    Spacer(modifier = Modifier.weight(1f))
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        drawerItems.forEach { item ->
                            LegacySidebarButton(
                                label = item.label,
                                iconRes = item.iconRes,
                                icon = item.icon,
                                selected = selectedDrawerRoute == item.route,
                                expanded = isExpanded,
                                focusEnabled = legacyDrawerVisible,
                                focusPresentationProgress =
                                    focusPresentationProgress,
                                focusScaleReady = focusScaleReady,
                                onClick = {
                                    val targetRoute = item.route
                                    val stayingOnCurrentRoute =
                                        currentRoute == targetRoute

                                    isLegacySidebarOpen = false
                                    pendingContentFocusTransfer =
                                        stayingOnCurrentRoute

                                    when {
                                        stayingOnCurrentRoute -> {
                                            navigateToDrawerRoute(
                                                navController = navController,
                                                currentRoute = currentRoute,
                                                targetRoute = targetRoute
                                            )
                                        }

                                        targetRoute == Screen.Home.route -> {
                                            navigateToDrawerRoute(
                                                navController = navController,
                                                currentRoute = currentRoute,
                                                targetRoute = targetRoute
                                            )
                                        }

                                        else -> {
                                            legacyRootTransitionTarget =
                                                targetRoute

                                            legacyRootTransitionScope.launch {
                                                /*
                                                 * Legacy drawer takes 260ms to close.
                                                 * Capture only after it and its haze are
                                                 * fully gone so the frozen frame contains
                                                 * the clean outgoing destination.
                                                 */
                                                delay(220L)
                                                repeat(2) {
                                                    withFrameNanos { }
                                                }

                                                captureSettingsBackdrop(
                                                    window =
                                                        legacyActivity?.window,
                                                    view = legacyHostView
                                                ) { captured ->
                                                    if (captured == null) {
                                                        legacyRootTransitionTarget =
                                                            null
                                                        navigateToDrawerRoute(
                                                            navController =
                                                                navController,
                                                            currentRoute =
                                                                currentRoute,
                                                            targetRoute =
                                                                targetRoute
                                                        )
                                                    } else {
                                                        legacyRootTransitionBitmap =
                                                            captured

                                                        legacyRootTransitionScope.launch {
                                                            legacyRootTransitionAlpha
                                                                .snapTo(1f)

                                                            /*
                                                             * Ensure the frozen frame has
                                                             * actually rendered before the
                                                             * route changes underneath it.
                                                             */
                                                            withFrameNanos { }

                                                            val previousBackdrop =
                                                                settingsBackdropBitmap
                                                            settingsBackdropBitmap =
                                                                captured

                                                            if (
                                                                previousBackdrop != null &&
                                                                previousBackdrop !==
                                                                    captured &&
                                                                previousBackdrop !==
                                                                    legacyRootTransitionBitmap &&
                                                                !previousBackdrop.isRecycled
                                                            ) {
                                                                previousBackdrop.recycle()
                                                            }

                                                            navigateToDrawerRoute(
                                                                navController =
                                                                    navController,
                                                                currentRoute =
                                                                    currentRoute,
                                                                targetRoute =
                                                                    targetRoute
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.focusRequester(
                                    drawerItemFocusRequesters.getValue(item.route)
                                ).width(itemWidth)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        val contentStartPadding by animateDpAsState(
            targetValue = if (showSidebar) closedDrawerWidth else 0.dp,
            animationSpec = tween(350),
            label = "contentStartPadding"
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = contentStartPadding)
                .then(
                    if (legacySidebarHazeCaptureActive) {
                        Modifier.haze(legacySidebarHazeState)
                    } else {
                        Modifier
                    }
                )
                .onPreviewKeyEvent { keyEvent ->
                    isLegacySidebarOpen &&
                        keyEvent.type == KeyEventType.KeyDown &&
                        isBlockedContentKey(keyEvent.key)
                }
                .onKeyEvent { keyEvent ->
                    if (showSidebar && !isLegacySidebarOpen) {
                        if (keyEvent.key == Key.DirectionLeft && keyEvent.type == KeyEventType.KeyUp) {
                            if (legacyLeftAtEdge) legacyLeftReleasedSinceEdge = true
                        }
                        if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.DirectionLeft) {
                            if (focusManager.moveFocus(FocusDirection.Left)) {
                                legacyLeftAtEdge = false
                                legacyLeftReleasedSinceEdge = false
                                true
                            } else {
                                if (legacyLeftAtEdge && legacyLeftReleasedSinceEdge) {
                                    openLegacySidebar()
                                }
                                legacyLeftAtEdge = true
                                true
                            }
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }
        ) {
            CompositionLocalProvider(
                LocalAppInForeground provides appInForeground,
                LocalBackgroundedAtMs provides backgroundedAtMs,
                LocalSidebarExpanded provides isLegacySidebarOpen,
                LocalContentFocusRequester provides contentFocusRequester,
                LocalSidebarOpenRequest provides {
                    openLegacySidebar()
                },
                LocalHomeHeroTrailerPlaying provides homeHeroTrailerPlaying,
                LocalPreserveSidebarTrailerPlayback provides
                    preserveSidebarTrailerPlayback,
                LocalSidebarFocusRestoreActive provides
                    (
                        legacyDrawerVisible ||
                            pendingContentFocusTransfer
                    ),
                LocalRowFocusRestorer provides rowFocusRestorer,
                LocalSettingsBackdropBitmap provides settingsBackdropBitmap
            ) {
                NuvioNavHost(
                    navController = navController,
                    startDestination = startDestination,
                    hideBuiltInHeaders = hideBuiltInHeaders
                )
            }
        }

        val frozenLegacyRootBitmap = legacyRootTransitionBitmap
        if (
            frozenLegacyRootBitmap != null &&
            !frozenLegacyRootBitmap.isRecycled
        ) {
            val frozenLegacyRootImage =
                remember(frozenLegacyRootBitmap) {
                    frozenLegacyRootBitmap.asImageBitmap()
                }

            Image(
                bitmap = frozenLegacyRootImage,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(2f)
                    .graphicsLayer {
                        alpha = legacyRootTransitionAlpha.value
                    }
            )
        }
    }
}

@Composable
private fun LegacySidebarButton(
    label: String,
    iconRes: Int?,
    icon: ImageVector?,
    selected: Boolean,
    expanded: Boolean,
    focusEnabled: Boolean,
    focusPresentationProgress: Float,
    focusScaleReady: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember {
        androidx.compose.foundation.interaction.MutableInteractionSource()
    }
    val itemShape = RoundedCornerShape(32.dp)
    val bubbleColor by animateColorAsState(
        targetValue = when {
            isFocused -> Color.White.copy(alpha = 0.16f)
            expanded && selected ->
                NuvioColors.Secondary.copy(alpha = 0.42f)
            else -> Color.Transparent
        },
        label = "legacySidebarItemBackground"
    )
    val bubbleProgress =
        if (expanded && (isFocused || selected)) {
            focusPresentationProgress.coerceIn(0f, 1f)
        } else {
            0f
        }
    val contentColor by animateColorAsState(
        targetValue = when {
            isFocused && focusPresentationProgress > 0.01f -> NuvioColors.TextPrimary
            expanded && selected -> NuvioColors.TextPrimary
            else -> NuvioColors.TextSecondary
        },
        label = "legacySidebarItemContent"
    )
    val iconTint by animateColorAsState(
        targetValue = when {
            isFocused && focusPresentationProgress > 0.01f -> NuvioColors.Secondary
            expanded && selected -> NuvioColors.Secondary
            selected -> NuvioColors.Secondary
            !expanded -> NuvioColors.TextTertiary
            else -> NuvioColors.TextSecondary
        },
        label = "legacySidebarItemIconTint"
    )
    val focusScale by animateFloatAsState(
        targetValue = if (isFocused && focusScaleReady) 1.045f else 1f,
        animationSpec = tween(
            durationMillis = 160,
            easing = FastOutSlowInEasing
        ),
        label = "legacySidebarItemScale"
    )

    Box(
        modifier = modifier
            .height(52.dp)
            .graphicsLayer {
                scaleX = focusScale
                scaleY = focusScale
            }
            .focusProperties { canFocus = focusEnabled }
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
    ) {
        /*
         * Draw the bubble independently from the clickable/content layer. It
         * can grow from an icon-sized pill to the complete row without the
         * row's own clip cutting off its horizontal scale.
         */
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(
                    52.dp +
                        (104.dp * bubbleProgress)
                )
                .height(52.dp)
                .graphicsLayer {
                    alpha = bubbleProgress
                }
                .clip(itemShape)
                .background(
                    color = bubbleColor,
                    shape = itemShape
                )
        )
        DrawerItemIcon(
            iconRes = iconRes,
            icon = icon,
            tint = iconTint,
            modifier = if (expanded) {
                Modifier
                    .size(22.dp)
                    .align(Alignment.CenterStart)
                    .offset(x = 18.dp)
            } else {
                Modifier
                    .size(22.dp)
                    .align(Alignment.Center)
            }
        )
        if (expanded) {
            Text(
                text = label,
                color = contentColor,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .padding(start = 54.dp, end = 14.dp)
            )
        }
    }
}

@Composable
private fun ModernSidebarScaffold(
    appInForeground: Boolean,
    backgroundedAtMs: Long,
    navController: NavHostController,
    startDestination: String,
    currentRoute: String?,
    rootRoutes: Set<String>,
    drawerItems: List<DrawerItem>,
    selectedDrawerRoute: String?,
    selectedDrawerItem: DrawerItem,
    sidebarCollapsed: Boolean,
    modernSidebarBlurEnabled: Boolean,
    hideBuiltInHeaders: Boolean,
    activeProfileName: String,
    activeProfileColorHex: String,
    activeProfileAvatarImageUrl: String?,
    showProfileSelector: Boolean,
    onSwitchProfile: () -> Unit,
    onExitApp: () -> Unit
) {
    val showSidebar = currentRoute in rootRoutes
    val collapsedSidebarWidth = if (sidebarCollapsed) 0.dp else 184.dp
    val openSidebarWidth = 262.dp

    val focusManager = LocalFocusManager.current
    val contentFocusRequester = remember { FocusRequester() }
    val drawerItemFocusRequesters = remember(drawerItems) {
        drawerItems.associate { item -> item.route to FocusRequester() }
    }

    var isSidebarExpanded by remember { mutableStateOf(false) }
    var sidebarCollapsePending by remember { mutableStateOf(false) }
    var pendingContentFocusTransfer by remember { mutableStateOf(false) }
    var pendingSidebarFocusRequest by remember { mutableStateOf(false) }
    var focusedDrawerIndex by remember { mutableStateOf(-1) }
    var leftAtEdge by remember { mutableStateOf(false) }
    var leftReleasedSinceEdge by remember { mutableStateOf(false) }
    var isFloatingPillIconOnly by remember { mutableStateOf(false) }
    var sidebarRootNavigationInProgress by remember { mutableStateOf(false) }
    val sidebarRootTransitionScope = rememberCoroutineScope()
    val sidebarRootTransitionAlpha = remember { Animatable(0f) }
    var sidebarRootTransitionBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var sidebarRootTransitionTarget by remember { mutableStateOf<String?>(null) }
    val modernHostView = LocalView.current
    val modernActivity = LocalContext.current as? Activity
    var settingsBackdropBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var settingsCaptureInProgress by remember { mutableStateOf(false) }
    fun openModernSidebar() {
        if (settingsCaptureInProgress) return
        if (currentRoute == Screen.Settings.route) {
            isSidebarExpanded = true
            sidebarCollapsePending = false
            pendingSidebarFocusRequest = true
            return
        }
        settingsCaptureInProgress = true
        captureSettingsBackdrop(
            window = modernActivity?.window,
            view = modernHostView
        ) { captured ->
            settingsCaptureInProgress = false
            if (captured != null) {
                settingsBackdropBitmap?.takeIf { it !== captured }?.recycle()
                settingsBackdropBitmap = captured
            }
            isSidebarExpanded = true
            sidebarCollapsePending = false
            pendingSidebarFocusRequest = true
        }
    }
    val keepFloatingPillExpanded = selectedDrawerRoute == Screen.Settings.route
    val keepSidebarFocusDuringCollapse =
        isSidebarExpanded || sidebarCollapsePending || pendingContentFocusTransfer
    val hasSidebarProfileItem = showProfileSelector && activeProfileName.isNotEmpty()
    val sidebarTopBoundaryIndex = if (hasSidebarProfileItem) drawerItems.size else 0

    LaunchedEffect(showSidebar) {
        if (!showSidebar) {
            isSidebarExpanded = false
            sidebarCollapsePending = false
            pendingContentFocusTransfer = false
            pendingSidebarFocusRequest = false
            isFloatingPillIconOnly = false
        }
    }

    LaunchedEffect(currentRoute) {
        if (sidebarRootNavigationInProgress) {
            /*
             * Keep sidebar rendering suppressed until the frozen-frame
             * dissolve has completed.
             */
            delay(400L)
            sidebarRootNavigationInProgress = false
        }
    }

    LaunchedEffect(
        currentRoute,
        sidebarRootTransitionTarget,
        sidebarRootTransitionBitmap
    ) {
        val targetRoute = sidebarRootTransitionTarget
        val frozenBitmap = sidebarRootTransitionBitmap
        if (
            targetRoute != null &&
            frozenBitmap != null &&
            currentRoute == targetRoute
        ) {
            /*
             * Keep the outgoing frame fully opaque until the incoming root,
             * including its blur/haze layers, has produced a few real frames.
             * The fade then reveals a finished destination instead of exposing
             * blur initialization as a visual snap.
             */
            repeat(3) { withFrameNanos { } }
            sidebarRootTransitionAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = 300,
                    easing = LinearEasing
                )
            )

            sidebarRootTransitionBitmap = null
            sidebarRootTransitionTarget = null
            sidebarRootTransitionAlpha.snapTo(0f)

            if (
                frozenBitmap !== settingsBackdropBitmap &&
                !frozenBitmap.isRecycled
            ) {
                frozenBitmap.recycle()
            }
        }
    }

    LaunchedEffect(keepFloatingPillExpanded, showSidebar) {
        if (!showSidebar || keepFloatingPillExpanded) {
            isFloatingPillIconOnly = false
        }
    }

    BackHandler(enabled = currentRoute in rootRoutes && !isSidebarExpanded && !sidebarCollapsePending) {
        openModernSidebar()
    }

    BackHandler(enabled = currentRoute in rootRoutes && isSidebarExpanded && !sidebarCollapsePending) {
        onExitApp()
    }

    LaunchedEffect(sidebarCollapsePending, isSidebarExpanded, showSidebar) {
        if (!showSidebar || !sidebarCollapsePending) {
            return@LaunchedEffect
        }
        if (!isSidebarExpanded) {
            sidebarCollapsePending = false
            return@LaunchedEffect
        }
        delay(95L)
        isSidebarExpanded = false
        sidebarCollapsePending = false
        pendingContentFocusTransfer = true
    }

    val sidebarVisible = showSidebar && (isSidebarExpanded || !sidebarCollapsed)
    val sidebarHazeState = remember { HazeState() }
    val targetSidebarWidth = when {
        !sidebarVisible -> 0.dp
        isSidebarExpanded -> openSidebarWidth
        else -> collapsedSidebarWidth
    }
    val sidebarWidth by animateDpAsState(
        targetValue = targetSidebarWidth,
        animationSpec = if (isSidebarExpanded) {
            keyframes {
                durationMillis = 365
                (openSidebarWidth + 12.dp) at 175
            }
        } else {
            tween(durationMillis = 385, easing = LinearOutSlowInEasing)
        },
        label = "sidebarWidth"
    )
    val sidebarSlideX by animateDpAsState(
        targetValue = if (sidebarVisible) 0.dp else (-24).dp,
        animationSpec = tween(durationMillis = 205, easing = FastOutSlowInEasing),
        label = "sidebarSlideX"
    )
    val sidebarSurfaceAlpha by animateFloatAsState(
        targetValue = if (sidebarVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 135, easing = FastOutSlowInEasing),
        label = "sidebarSurfaceAlpha"
    )
    val shouldApplySidebarHaze = showSidebar && modernSidebarBlurEnabled && (
        isSidebarExpanded || sidebarCollapsePending
        )
    val sidebarTransition = updateTransition(
        targetState = isSidebarExpanded,
        label = "sidebarTransition"
    )
    val sidebarLabelAlpha by sidebarTransition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(durationMillis = 125, easing = FastOutSlowInEasing)
            } else {
                tween(durationMillis = 145, easing = LinearOutSlowInEasing)
            }
        },
        label = "sidebarLabelAlpha"
    ) { expanded ->
        if (expanded) 1f else 0f
    }
    val sidebarExpandProgress by sidebarTransition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(durationMillis = 345, easing = FastOutSlowInEasing)
            } else {
                tween(durationMillis = 385, easing = LinearOutSlowInEasing)
            }
        },
        label = "sidebarExpandProgress"
    ) { expanded ->
        if (expanded) 1f else 0f
    }

    // derivedStateOf prevents per-frame recomposition — only triggers when the boolean crosses the threshold
    val sidebarBlocksContentKeys by remember { derivedStateOf { sidebarExpandProgress > 0.2f } }
    val sidebarShowExpandedPanel by remember { derivedStateOf { sidebarExpandProgress > 0.01f } }
    val sidebarShowCollapsedPill by remember { derivedStateOf { sidebarExpandProgress < 0.98f } }

    val sidebarIconScale by sidebarTransition.animateFloat(
        transitionSpec = { tween(durationMillis = 145, easing = FastOutSlowInEasing) },
        label = "sidebarIconScale"
    ) { expanded ->
        if (expanded) 1f else 0.92f
    }
    val sidebarBloomScale by sidebarTransition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(durationMillis = 345, easing = FastOutSlowInEasing)
            } else {
                tween(durationMillis = 395, easing = LinearOutSlowInEasing)
            }
        },
        label = "sidebarBloomScale"
    ) { expanded ->
        if (expanded) 1f else 0.9f
    }
    val sidebarDeflateOffsetX by sidebarTransition.animateDp(
        transitionSpec = {
            if (targetState) {
                tween(durationMillis = 345, easing = FastOutSlowInEasing)
            } else {
                tween(durationMillis = 395, easing = LinearOutSlowInEasing)
            }
        },
        label = "sidebarDeflateOffsetX"
    ) { expanded ->
        if (expanded) 0.dp else (-10).dp
    }
    val sidebarDeflateOffsetY by sidebarTransition.animateDp(
        transitionSpec = {
            if (targetState) {
                tween(durationMillis = 345, easing = FastOutSlowInEasing)
            } else {
                tween(durationMillis = 395, easing = LinearOutSlowInEasing)
            }
        },
        label = "sidebarDeflateOffsetY"
    ) { expanded ->
        if (expanded) 0.dp else (-8).dp
    }

    LaunchedEffect(isSidebarExpanded, sidebarCollapsePending, pendingContentFocusTransfer, showSidebar) {
        if (!showSidebar || !pendingContentFocusTransfer || isSidebarExpanded || sidebarCollapsePending) {
            return@LaunchedEffect
        }
        repeat(2) { withFrameNanos { } }
        runCatching { contentFocusRequester.requestFocus() }
        pendingContentFocusTransfer = false
    }

    LaunchedEffect(isSidebarExpanded, pendingSidebarFocusRequest, showSidebar, selectedDrawerRoute) {
        if (!showSidebar || !pendingSidebarFocusRequest || !isSidebarExpanded) {
            return@LaunchedEffect
        }
        val targetRoute = selectedDrawerRoute ?: run {
            pendingSidebarFocusRequest = false
            return@LaunchedEffect
        }
        val requester = drawerItemFocusRequesters[targetRoute] ?: run {
            pendingSidebarFocusRequest = false
            return@LaunchedEffect
        }
        repeat(2) { withFrameNanos { } }
        runCatching { requester.requestFocus() }
        pendingSidebarFocusRequest = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (shouldApplySidebarHaze) {
                        Modifier.haze(sidebarHazeState)
                    } else {
                        Modifier
                    }
                )
                .onPreviewKeyEvent { keyEvent ->
                    if (
                        isSidebarExpanded &&
                        !sidebarCollapsePending &&
                        sidebarBlocksContentKeys &&
                        keyEvent.type == KeyEventType.KeyDown &&
                        isBlockedContentKey(keyEvent.key)
                    ) {
                        true
                    } else {
                        false
                    }
                }
                .onKeyEvent { keyEvent ->
                    if (showSidebar && !isSidebarExpanded) {
                        if (keyEvent.key == Key.DirectionLeft && keyEvent.type == KeyEventType.KeyUp) {
                            if (leftAtEdge) leftReleasedSinceEdge = true
                        }
                        if (keyEvent.type == KeyEventType.KeyDown) {
                            if (!keepFloatingPillExpanded) {
                                when (keyEvent.key) {
                                    Key.DirectionDown -> isFloatingPillIconOnly = true
                                    Key.DirectionUp -> isFloatingPillIconOnly = false
                                    else -> Unit
                                }
                            }
                            if (keyEvent.key == Key.DirectionLeft) {
                                if (focusManager.moveFocus(FocusDirection.Left)) {
                                    leftAtEdge = false
                                    leftReleasedSinceEdge = false
                                    true
                                } else {
                                    if (leftAtEdge && leftReleasedSinceEdge) {
                                        openModernSidebar()
                                    }
                                    leftAtEdge = true
                                    true
                                }
                            } else {
                                leftAtEdge = false
                                leftReleasedSinceEdge = false
                                false
                            }
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }
        ) {
            CompositionLocalProvider(
                LocalAppInForeground provides appInForeground,
                LocalBackgroundedAtMs provides backgroundedAtMs,
                LocalSidebarExpanded provides isSidebarExpanded,
                LocalContentFocusRequester provides contentFocusRequester,
                LocalSidebarOpenRequest provides { openModernSidebar() },
                LocalSettingsBackdropBitmap provides settingsBackdropBitmap
            ) {
                NuvioNavHost(
                    navController = navController,
                    startDestination = startDestination,
                    hideBuiltInHeaders = hideBuiltInHeaders
                )
            }
        }

        val frozenRootBitmap = sidebarRootTransitionBitmap
        if (frozenRootBitmap != null && !frozenRootBitmap.isRecycled) {
            val frozenRootImage = remember(frozenRootBitmap) {
                frozenRootBitmap.asImageBitmap()
            }
            Image(
                bitmap = frozenRootImage,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = sidebarRootTransitionAlpha.value
                    }
            )
        }

        if (
            showSidebar &&
            !sidebarRootNavigationInProgress &&
            (sidebarVisible || sidebarWidth > 0.dp)
        ) {
            val panelShape = RoundedCornerShape(30.dp)
            val showExpandedPanel = isSidebarExpanded || sidebarShowExpandedPanel

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .width(sidebarWidth)
                    .padding(start = 14.dp, top = 16.dp, bottom = 12.dp, end = 8.dp)
                    .offset {
                        IntOffset(
                            (sidebarSlideX + sidebarDeflateOffsetX).roundToPx(),
                            sidebarDeflateOffsetY.roundToPx()
                        )
                    }
                    .graphicsLayer {
                        alpha = sidebarSurfaceAlpha
                        scaleX = sidebarBloomScale
                        scaleY = sidebarBloomScale
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                    .selectableGroup()
                    .onPreviewKeyEvent { keyEvent ->
                        if (!isSidebarExpanded || keyEvent.type != KeyEventType.KeyDown) {
                            return@onPreviewKeyEvent false
                        }
                        when (keyEvent.key) {
                            Key.DirectionUp -> {
                                focusedDrawerIndex == sidebarTopBoundaryIndex
                            }

                            Key.DirectionDown -> {
                                focusedDrawerIndex == drawerItems.lastIndex
                            }

                            Key.DirectionRight -> {
                                pendingContentFocusTransfer = false
                                sidebarCollapsePending = true
                                true
                            }

                            else -> false
                        }
                    }
            ) {
                if (showExpandedPanel) {
                    ModernSidebarBlurPanel(
                        drawerItems = drawerItems,
                        selectedDrawerRoute = selectedDrawerRoute,
                        keepSidebarFocusDuringCollapse = keepSidebarFocusDuringCollapse,
                        sidebarLabelAlpha = sidebarLabelAlpha,
                        sidebarIconScale = sidebarIconScale,
                        sidebarExpandProgress = sidebarExpandProgress,
                        isSidebarExpanded = isSidebarExpanded,
                        sidebarCollapsePending = sidebarCollapsePending,
                        blurEnabled = modernSidebarBlurEnabled,
                        sidebarHazeState = sidebarHazeState,
                        panelShape = panelShape,
                        drawerItemFocusRequesters = drawerItemFocusRequesters,
                        onDrawerItemFocused = { focusedDrawerIndex = it },
                        onDrawerItemClick = { targetRoute ->
                            val stayingOnCurrentRoute = currentRoute == targetRoute
                            pendingSidebarFocusRequest = false
                            isSidebarExpanded = false
                            sidebarCollapsePending = false
                            pendingContentFocusTransfer = stayingOnCurrentRoute

                            when {
                                stayingOnCurrentRoute -> {
                                    navigateToDrawerRoute(
                                        navController = navController,
                                        currentRoute = currentRoute,
                                        targetRoute = targetRoute
                                    )
                                }

                                targetRoute == Screen.Home.route -> {
                                    sidebarRootNavigationInProgress = true
                                    navigateToDrawerRoute(
                                        navController = navController,
                                        currentRoute = currentRoute,
                                        targetRoute = targetRoute
                                    )
                                }

                                else -> {
                                    sidebarRootNavigationInProgress = true
                                    sidebarRootTransitionTarget = targetRoute

                                    /*
                                     * Remove the sidebar first, then capture the exact
                                     * outgoing root with no drawer pixels in the frame.
                                     */
                                    sidebarRootTransitionScope.launch {
                                        repeat(2) { withFrameNanos { } }

                                        captureSettingsBackdrop(
                                            window = modernActivity?.window,
                                            view = modernHostView
                                        ) { captured ->
                                            if (captured == null) {
                                                sidebarRootTransitionTarget = null
                                                navigateToDrawerRoute(
                                                    navController = navController,
                                                    currentRoute = currentRoute,
                                                    targetRoute = targetRoute
                                                )
                                            } else {
                                                sidebarRootTransitionBitmap = captured

                                                sidebarRootTransitionScope.launch {
                                                    /*
                                                     * Put the frozen frame on screen before
                                                     * changing the backdrop source or route.
                                                     */
                                                    sidebarRootTransitionAlpha.snapTo(1f)
                                                    withFrameNanos { }

                                                    val previousBackdrop =
                                                        settingsBackdropBitmap
                                                    settingsBackdropBitmap = captured

                                                    if (
                                                        previousBackdrop != null &&
                                                        previousBackdrop !== captured &&
                                                        previousBackdrop !==
                                                            sidebarRootTransitionBitmap &&
                                                        !previousBackdrop.isRecycled
                                                    ) {
                                                        previousBackdrop.recycle()
                                                    }

                                                    navigateToDrawerRoute(
                                                        navController = navController,
                                                        currentRoute = currentRoute,
                                                        targetRoute = targetRoute
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        activeProfileName = activeProfileName,
                        activeProfileColorHex = activeProfileColorHex,
                        activeProfileAvatarImageUrl = activeProfileAvatarImageUrl,
                        showProfileSelector = showProfileSelector,
                        onSwitchProfile = onSwitchProfile
                    )
                }
            }

            if (
                !sidebarCollapsed &&
                sidebarShowCollapsedPill &&
                selectedDrawerRoute != Screen.Search.route
            ) {
                CollapsedSidebarPill(
                    label = selectedDrawerItem.label,
                    iconRes = selectedDrawerItem.iconRes,
                    icon = selectedDrawerItem.icon,
                    iconOnly = isFloatingPillIconOnly && !keepFloatingPillExpanded,
                    blurEnabled = modernSidebarBlurEnabled,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset {
                            IntOffset(
                                14.dp.roundToPx(),
                                (16.dp + sidebarDeflateOffsetY).roundToPx()
                            )
                        }
                        .graphicsLayer {
                            val progress = sidebarExpandProgress
                            alpha = 1f - progress
                            val s = 0.9f + (0.1f * (1f - progress))
                            scaleX = s
                            scaleY = s
                            transformOrigin = TransformOrigin(0f, 0f)
                        },
                    onExpand = {
                        openModernSidebar()
                    }
                )
            }
        }
    }
}

@Composable
private fun CollapsedSidebarPill(
    label: String,
    iconRes: Int?,
    icon: ImageVector?,
    iconOnly: Boolean,
    blurEnabled: Boolean,
    modifier: Modifier = Modifier,
    onExpand: () -> Unit
) {
    val pillShape = RoundedCornerShape(999.dp)
    val bgElevated = NuvioColors.BackgroundElevated
    val bgCard = NuvioColors.BackgroundCard
    val borderBase = NuvioColors.Border
    val pillBackgroundBrush = remember(blurEnabled, bgElevated, bgCard) {
        if (blurEnabled) {
            Brush.verticalGradient(listOf(Color(0xD1424851), Color(0xC73B4149)))
        } else {
            Brush.verticalGradient(listOf(bgElevated, bgCard))
        }
    }
    val pillBorderColor = remember(blurEnabled, borderBase) {
        if (blurEnabled) Color.White.copy(alpha = 0.14f) else borderBase.copy(alpha = 0.9f)
    }

    Row(
        modifier = modifier
            .focusProperties { canFocus = false }
            .animateContentSize()
            .clickable(onClick = onExpand)
            .padding(horizontal = 1.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.25.dp)
    ) {
        if (!iconOnly) {
            Image(
                painter = painterResource(id = R.drawable.ic_chevron_compact_left),
                contentDescription = stringResource(R.string.cd_expand_sidebar),
                modifier = Modifier
                    .width(8.5.dp)
                    .height(16.dp)
                    .offset(y = (-0.5).dp)
            )
        }

        Box(
            modifier = Modifier
                .height(44.dp)
                .graphicsLayer {
                    shape = pillShape
                    clip = true
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .clip(pillShape)
                .background(brush = pillBackgroundBrush, shape = pillShape)
                .border(width = 1.dp, color = pillBorderColor, shape = pillShape)
        ) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .padding(start = 5.dp, end = if (iconOnly) 5.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (iconOnly) 0.dp else 9.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF4F555E)),
                    contentAlignment = Alignment.Center
                ) {
                    DrawerItemIcon(
                        iconRes = iconRes,
                        icon = icon,
                        tint = Color.White,
                        modifier = Modifier
                            .size(22.dp)
                            .offset(y = (-0.5).dp)
                    )
                }

                if (!iconOnly) {
                    Text(
                        text = label,
                        color = Color.White,
                        style = androidx.tv.material3.MaterialTheme.typography.titleLarge.copy(
                            lineHeight = 30.sp
                        ),
                        modifier = Modifier.offset(y = (-0.5).dp),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

private fun navigateToDrawerRoute(
    navController: NavHostController,
    currentRoute: String?,
    targetRoute: String
) {
    if (currentRoute == targetRoute) {
        return
    }

    if (targetRoute == Screen.Home.route) {
        /*
         * Root navigation already keeps the outgoing destination visible until
         * Home has drawn underneath it. Do not stack Home's separate dark
         * return curtain on top of that dissolve.
         */
        runCatching {
            navController.getBackStackEntry(Screen.Home.route)
        }.getOrNull()
            ?.savedStateHandle
            ?.set("skipHomeReturnCurtainOnce", true)

        /*
         * Home is the root entry already sitting underneath every sidebar
         * destination. Restore it with a real pop so Navigation Compose runs
         * the root pop transition instead of replacing both destinations as
         * part of a navigate + popUpTo transaction.
         */
        if (navController.popBackStack(Screen.Home.route, inclusive = false)) {
            return
        }
    }

    navController.navigate(targetRoute) {
        popUpTo(navController.graph.startDestinationId) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

private fun isBlockedContentKey(key: Key): Boolean {
    return key == Key.DirectionUp ||
        key == Key.DirectionDown ||
        key == Key.DirectionLeft ||
        key == Key.DirectionRight ||
        key == Key.DirectionCenter ||
        key == Key.Enter
}

@Composable
private fun DrawerItemIcon(
    iconRes: Int?,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    tint: Color = androidx.tv.material3.LocalContentColor.current
) {
    when {
        icon != null -> Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = modifier
        )

        iconRes != null -> Icon(
            painter = rememberRawSvgPainter(iconRes),
            contentDescription = null,
            tint = tint,
            modifier = modifier
        )
    }
}

@Composable
private fun rememberRawSvgPainter(rawIconRes: Int): Painter = rememberAsyncImagePainter(
    model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
        .data(rawIconRes)
        .decoderFactory(SvgDecoder.Factory())
        .build()
)
