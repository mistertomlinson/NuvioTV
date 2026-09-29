@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.annotation.RawRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.HomePopupGlassEnvironment
import com.nuvio.tv.ui.components.LocalHomePopupGlassEnvironment
import com.nuvio.tv.ui.screens.plugin.PluginScreenContent
import com.nuvio.tv.ui.theme.NuvioColors
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class SettingsCategory {
    ACCOUNT,
    PROFILES,
    APPEARANCE,
    LAYOUT,
    PLUGINS,
    INTEGRATION,
    PLAYBACK,
    ADVANCED,
    TRACKING,
    ABOUT,
    DEBUG
}

private enum class IntegrationSettingsSection {
    Hub,
    Debrid,
    Tmdb,
    MdbList,
    AnimeSkip
}

internal enum class SettingsSectionDestination {
    Inline,
    External
}

internal data class SettingsSectionSpec(
    val category: SettingsCategory,
    val title: String,
    val icon: ImageVector? = null,
    @param:RawRes val rawIconRes: Int? = null,
    val subtitle: String,
    val destination: SettingsSectionDestination
)

private const val SETTINGS_DETAIL_FOCUS_DELAY_MS = 120L
private const val SETTINGS_DETAIL_ANIM_IN_DURATION_MS = 200
private const val SETTINGS_DETAIL_ANIM_OUT_DURATION_MS = 180

@Composable
private fun rememberSettingsSectionSpecs() = listOf(
    SettingsSectionSpec(
        category = SettingsCategory.LAYOUT,
        title = stringResource(R.string.settings_layout),
        icon = Icons.Default.GridView,
        subtitle = stringResource(R.string.settings_layout_subtitle),
        destination = SettingsSectionDestination.Inline
    ),
    SettingsSectionSpec(
        category = SettingsCategory.APPEARANCE,
        title = stringResource(R.string.appearance_title),
        icon = Icons.Default.Palette,
        subtitle = stringResource(R.string.appearance_subtitle),
        destination = SettingsSectionDestination.Inline
    ),
    SettingsSectionSpec(
        category = SettingsCategory.PLAYBACK,
        title = stringResource(R.string.settings_playback),
        icon = Icons.Default.Settings,
        subtitle = stringResource(R.string.settings_playback_subtitle),
        destination = SettingsSectionDestination.Inline
    ),
    SettingsSectionSpec(
        category = SettingsCategory.PLUGINS,
        title = stringResource(R.string.settings_plugins),
        icon = Icons.Default.Build,
        subtitle = stringResource(R.string.settings_plugins_subtitle),
        destination = SettingsSectionDestination.Inline
    ),
    SettingsSectionSpec(
        category = SettingsCategory.INTEGRATION,
        title = stringResource(R.string.settings_integration),
        icon = Icons.Default.Link,
        subtitle = "",
        destination = SettingsSectionDestination.Inline
    ),
    SettingsSectionSpec(
        category = SettingsCategory.TRACKING,
        title = stringResource(R.string.settings_tracking_title),
        icon = Icons.Default.Link,
        subtitle = stringResource(
            R.string.settings_tracking_description_compact
        ),
        destination = SettingsSectionDestination.Inline
    ),
    SettingsSectionSpec(
        category = SettingsCategory.PROFILES,
        title = stringResource(R.string.settings_profiles),
        icon = Icons.Default.People,
        subtitle = stringResource(R.string.settings_profiles_subtitle),
        destination = SettingsSectionDestination.Inline
    ),
    SettingsSectionSpec(
        category = SettingsCategory.ACCOUNT,
        title = stringResource(R.string.settings_account),
        icon = Icons.Default.Person,
        subtitle = stringResource(R.string.settings_account_subtitle),
        destination = SettingsSectionDestination.Inline
    ),
    SettingsSectionSpec(
        category = SettingsCategory.ABOUT,
        title = stringResource(R.string.about_title),
        icon = Icons.Default.Info,
        subtitle = stringResource(R.string.settings_about_subtitle),
        destination = SettingsSectionDestination.Inline
    ),
    SettingsSectionSpec(
        category = SettingsCategory.ADVANCED,
        title = stringResource(R.string.settings_advanced),
        icon = Icons.Default.Build,
        subtitle = stringResource(R.string.settings_advanced_subtitle),
        destination = SettingsSectionDestination.Inline
    ),
    SettingsSectionSpec(
        category = SettingsCategory.DEBUG,
        title = stringResource(R.string.settings_debug),
        icon = Icons.Default.BugReport,
        subtitle = stringResource(R.string.settings_debug_subtitle),
        destination = SettingsSectionDestination.Inline
    )
)

@Composable
fun SettingsScreen(
    showBuiltInHeader: Boolean = true,
    onNavigateToTracking: () -> Unit = {},
    onNavigateToTrakt: () -> Unit = {},
    onNavigateToSimkl: () -> Unit = {},
    trackingReturnFocusAccount: String? = null,
    onTrackingReturnFocusConsumed: () -> Unit = {},
    onNavigateToAuthQrSignIn: () -> Unit = {},
    onNavigateToManageProfiles: () -> Unit = {},
    onNavigateToSupportersContributors: () -> Unit = {},
    profileViewModel: ProfileSettingsViewModel = hiltViewModel()
) {
    SettingsCompactContent {
        SettingsScreenContent(
            showBuiltInHeader = showBuiltInHeader,
            onNavigateToTracking = onNavigateToTracking,
            onNavigateToTrakt = onNavigateToTrakt,
            onNavigateToSimkl = onNavigateToSimkl,
            trackingReturnFocusAccount = trackingReturnFocusAccount,
            onTrackingReturnFocusConsumed =
                onTrackingReturnFocusConsumed,
            onNavigateToAuthQrSignIn = onNavigateToAuthQrSignIn,
            onNavigateToManageProfiles = onNavigateToManageProfiles,
            onNavigateToSupportersContributors = onNavigateToSupportersContributors,
            profileViewModel = profileViewModel
        )
    }
}

@Composable
private fun SettingsScreenContent(
    showBuiltInHeader: Boolean = true,
    onNavigateToTracking: () -> Unit = {},
    onNavigateToTrakt: () -> Unit = {},
    onNavigateToSimkl: () -> Unit = {},
    trackingReturnFocusAccount: String? = null,
    onTrackingReturnFocusConsumed: () -> Unit = {},
    onNavigateToAuthQrSignIn: () -> Unit = {},
    onNavigateToManageProfiles: () -> Unit = {},
    onNavigateToSupportersContributors: () -> Unit = {},
    profileViewModel: ProfileSettingsViewModel = hiltViewModel()
) {
    val isPrimaryProfileActive by profileViewModel.isPrimaryProfileActive.collectAsStateWithLifecycle()

    val allSectionSpecs = rememberSettingsSectionSpecs()
    val visibleSections = remember(isPrimaryProfileActive, allSectionSpecs) {
        allSectionSpecs.filter { section ->
            when (section.category) {
                SettingsCategory.DEBUG -> BuildConfig.IS_DEBUG_BUILD
                SettingsCategory.PROFILES -> isPrimaryProfileActive
                SettingsCategory.ACCOUNT -> isPrimaryProfileActive
                SettingsCategory.TRACKING -> true
                else -> true
            }
        }
    }

    var selectedCategory by rememberSaveable {
        mutableStateOf(
            visibleSections.firstOrNull()?.category ?: SettingsCategory.APPEARANCE
        )
    }
    var railReturnFocusCategory by rememberSaveable {
        mutableStateOf(selectedCategory)
    }
    val railFocusRequesters = remember(visibleSections) {
        visibleSections.associate { it.category to FocusRequester() }
    }
    val contentFocusRequesters = remember {
            mapOf(
                SettingsCategory.APPEARANCE to FocusRequester(),
                SettingsCategory.LAYOUT to FocusRequester(),
                SettingsCategory.INTEGRATION to FocusRequester(),
                SettingsCategory.TRACKING to FocusRequester(),
                SettingsCategory.PLAYBACK to FocusRequester(),
                SettingsCategory.ADVANCED to FocusRequester(),
                SettingsCategory.ABOUT to FocusRequester()
            )
    }
    val railContainerFocusRequester = remember { FocusRequester() }
    val integrationHubFocusRequester = remember { FocusRequester() }
    val integrationTmdbFocusRequester = remember { FocusRequester() }
    val integrationMdbListFocusRequester = remember { FocusRequester() }
    val integrationAnimeSkipFocusRequester = remember { FocusRequester() }
    val integrationDebridFocusRequester = remember { FocusRequester() }
    var integrationSection by remember { mutableStateOf(IntegrationSettingsSection.Hub) }
    var pendingContentFocusCategory by remember { mutableStateOf<SettingsCategory?>(null) }
    var pendingContentFocusRequestId by remember { mutableLongStateOf(0L) }
    var allowDetailAutofocus by remember { mutableStateOf(false) }
    var detailHasFocus by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current

    /*
     * The main Settings UI embeds category content directly instead of
     * going through each category's standalone scaffold. Provide the same
     * Haze environment here so dialogs opened from embedded categories can
     * blur the actual Settings UI beneath them.
     */
    val settingsDialogHazeState = remember { HazeState() }
    val settingsDialogBlurEnabled =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val settingsDialogGlassEnvironment = remember(
        settingsDialogHazeState,
        settingsDialogBlurEnabled
    ) {
        HomePopupGlassEnvironment(
            hazeState = settingsDialogHazeState,
            blurEnabled = settingsDialogBlurEnabled
        )
    }

    LaunchedEffect(visibleSections) {
        if (visibleSections.none { it.category == selectedCategory }) {
            selectedCategory = visibleSections.firstOrNull()?.category ?: SettingsCategory.APPEARANCE
        }
        if (visibleSections.none { it.category == railReturnFocusCategory }) {
            railReturnFocusCategory = selectedCategory
        }
    }

    LaunchedEffect(Unit) {
        runCatching { railContainerFocusRequester.requestFocus() }
    }

    LaunchedEffect(trackingReturnFocusAccount) {
        if (!trackingReturnFocusAccount.isNullOrBlank()) {
            allowDetailAutofocus = true
            selectedCategory = SettingsCategory.TRACKING
            railReturnFocusCategory = SettingsCategory.TRACKING
        }
    }

    LaunchedEffect(pendingContentFocusRequestId) {
        val category = pendingContentFocusCategory ?: return@LaunchedEffect
        delay(SETTINGS_DETAIL_FOCUS_DELAY_MS)
        val requester = contentFocusRequesters[category]
        val requested = if (requester != null) {
            runCatching { requester.requestFocus() }.isSuccess
        } else {
            false
        }
        if (!requested) {
            focusManager.moveFocus(FocusDirection.Right)
        }
        pendingContentFocusCategory = null
    }

    BackHandler(enabled = detailHasFocus) {
        allowDetailAutofocus = false
        val requested = railFocusRequesters[railReturnFocusCategory]?.let { requester ->
            runCatching { requester.requestFocus() }.isSuccess
        } ?: false
        if (!requested) {
            runCatching { railContainerFocusRequester.requestFocus() }
        }
    }

    CompositionLocalProvider(
        LocalHomePopupGlassEnvironment provides settingsDialogGlassEnvironment
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (settingsDialogBlurEnabled) {
                        Modifier.haze(settingsDialogHazeState)
                    } else {
                        Modifier
                    }
                )
        ) {
        SettingsGlassBackdrop(modifier = Modifier.fillMaxSize())
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = 24.dp,
                    end = 24.dp,
                    top = if (showBuiltInHeader) 18.dp else 56.dp,
                    bottom = 18.dp
                )
        ) {
        SettingsWorkspaceSurface(
            modifier = Modifier
                .fillMaxSize()
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                var railHadFocus by remember { mutableStateOf(false) }
                val railListState = rememberLazyListState()

                Box(
                    modifier = Modifier
                        .width(190.dp)
                        .fillMaxHeight()
                ) {
                    LazyColumn(
                        state = railListState,
                        modifier = Modifier
                            .focusRequester(railContainerFocusRequester)
                            .fillMaxSize()
                        .onFocusChanged { state ->
                            val justGainedFocus = !railHadFocus && state.hasFocus
                            railHadFocus = state.hasFocus
                            if (justGainedFocus) {
                                val requester = railFocusRequesters[railReturnFocusCategory]
                                val requested = if (requester != null) {
                                    runCatching { requester.requestFocus() }.isSuccess
                                } else {
                                    false
                                }
                                if (!requested) {
                                    focusManager.moveFocus(FocusDirection.Down)
                                }
                            }
                        }
                        .onPreviewKeyEvent { event ->
                            when {
                                event.type == KeyEventType.KeyDown &&
                                    event.key == Key.DirectionLeft -> {
                                    // Settings owns the full workspace; never
                                    // let LEFT escape to the app sidebar.
                                    true
                                }
                                event.type == KeyEventType.KeyDown &&
                                    event.key == Key.DirectionRight -> {
                                    allowDetailAutofocus = true
                                    false
                                }
                                else -> false
                            }
                        },
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
                ) {
                    items(
                        items = visibleSections,
                        key = { it.category }
                    ) { section ->
                        SettingsRailButton(
                            title = section.title,
                            icon = section.icon,
                            rawIconRes = section.rawIconRes,
                            isSelected = selectedCategory == section.category,
                            focusRequester = railFocusRequesters[section.category],
                            onClick = {
                                railReturnFocusCategory = section.category
                                if (section.destination == SettingsSectionDestination.External) {
                                    when (section.category) {
                                        SettingsCategory.ACCOUNT -> onNavigateToAuthQrSignIn()
                                        SettingsCategory.TRACKING -> onNavigateToTracking()
                                        else -> Unit
                                    }
                                } else {
                                    if (section.category == SettingsCategory.INTEGRATION) {
                                        integrationSection = IntegrationSettingsSection.Hub
                                    }
                                    allowDetailAutofocus = true
                                    selectedCategory = section.category
                                    pendingContentFocusCategory = section.category
                                    pendingContentFocusRequestId += 1L
                                }
                            }
                        )
                    }
                }
                SettingsVerticalScrollIndicators(state = railListState)
            }

            Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                                val movedLeft = focusManager.moveFocus(FocusDirection.Left)
                                if (!movedLeft) {
                                    allowDetailAutofocus = false
                                    val requested = railFocusRequesters[selectedCategory]?.let { requester ->
                                        runCatching { requester.requestFocus() }.isSuccess
                                    } ?: false
                                    if (!requested) {
                                        runCatching { railContainerFocusRequester.requestFocus() }
                                    }
                                }
                                true
                            } else {
                                false
                            }
                        }
                        .onFocusChanged { state ->
                            detailHasFocus = state.hasFocus
                            if (state.hasFocus && !allowDetailAutofocus) {
                                railFocusRequesters[selectedCategory]?.let { requester ->
                                    runCatching { requester.requestFocus() }
                                }
                            }
                        }
                ) {
                    when (selectedCategory) {
                        SettingsCategory.PROFILES -> ProfileSettingsContent(
                            onManageProfiles = onNavigateToManageProfiles
                        )
                        SettingsCategory.APPEARANCE -> ThemeSettingsContent(
                            initialFocusRequester = if (allowDetailAutofocus) {
                                contentFocusRequesters[SettingsCategory.APPEARANCE]
                            } else {
                                null
                            }
                        )
                        SettingsCategory.LAYOUT -> LayoutSettingsContent(
                            initialFocusRequester = if (allowDetailAutofocus) {
                                contentFocusRequesters[SettingsCategory.LAYOUT]
                            } else {
                                null
                            }
                        )
                        SettingsCategory.PLAYBACK -> PlaybackSettingsContent(
                            initialFocusRequester = if (allowDetailAutofocus) {
                                contentFocusRequesters[SettingsCategory.PLAYBACK]
                            } else {
                                null
                            }
                        )
                        SettingsCategory.ADVANCED -> NetworkSettingsContent(
                            initialFocusRequester = if (allowDetailAutofocus) {
                                contentFocusRequesters[SettingsCategory.ADVANCED]
                            } else {
                                null
                            }
                        )
                        SettingsCategory.INTEGRATION -> IntegrationSettingsContent(
                            selectedSection = integrationSection,
                            onSelectSection = { integrationSection = it },
                            initialFocusRequester = if (allowDetailAutofocus) {
                                contentFocusRequesters[SettingsCategory.INTEGRATION]
                            } else {
                                null
                            },
                            hubFocusRequester = integrationHubFocusRequester,
                            tmdbFocusRequester = integrationTmdbFocusRequester,
                            mdbListFocusRequester = integrationMdbListFocusRequester,
                            animeSkipFocusRequester = integrationAnimeSkipFocusRequester,
                            debridFocusRequester = integrationDebridFocusRequester,
                            autoFocusEnabled = allowDetailAutofocus
                        )
                        SettingsCategory.ABOUT -> AboutSettingsContent(
                            onNavigateToSupportersContributors = onNavigateToSupportersContributors,
                            initialFocusRequester = if (allowDetailAutofocus) {
                                contentFocusRequesters[SettingsCategory.ABOUT]
                            } else {
                                null
                            }
                        )
                        SettingsCategory.PLUGINS -> PluginsSettingsContent()
                        SettingsCategory.ACCOUNT -> AccountSettingsInline(
                            onNavigateToAuthQrSignIn = onNavigateToAuthQrSignIn
                        )
                        SettingsCategory.DEBUG -> DebugSettingsContent()
                        SettingsCategory.TRACKING -> TrackingSettingsContent(
                            onNavigateToTrakt = onNavigateToTrakt,
                            onNavigateToSimkl = onNavigateToSimkl,
                            initialFocusRequester =
                                contentFocusRequesters[
                                    SettingsCategory.TRACKING
                                ],
                            returnFocusAccount =
                                trackingReturnFocusAccount,
                            onReturnFocusConsumed =
                                onTrackingReturnFocusConsumed
                        )
                    }
                }
            }
        }
        }
    }
}
}

@Composable
private fun PluginsSettingsContent() {
    val pluginViewModel: com.nuvio.tv.ui.screens.plugin.PluginViewModel = hiltViewModel()
    val pluginUiState by pluginViewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_plugins),
            subtitle = stringResource(R.string.settings_plugins_section_subtitle)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.TopStart
        ) {
            PluginScreenContent(
                uiState = pluginUiState,
                viewModel = pluginViewModel,
                showHeader = false
            )
        }
    }
}

@Composable
private fun AccountSettingsInline(
    onNavigateToAuthQrSignIn: () -> Unit
) {
    val accountViewModel: com.nuvio.tv.ui.screens.account.AccountViewModel = hiltViewModel()
    val accountUiState by accountViewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_account),
            subtitle = stringResource(R.string.settings_account_section_subtitle)
        )
        com.nuvio.tv.ui.screens.account.AccountSettingsContent(
            uiState = accountUiState,
            viewModel = accountViewModel,
            onNavigateToAuthQrSignIn = onNavigateToAuthQrSignIn
        )
    }
}

@Composable
private fun IntegrationSettingsContent(
    selectedSection: IntegrationSettingsSection,
    onSelectSection: (IntegrationSettingsSection) -> Unit,
    initialFocusRequester: FocusRequester?,
    hubFocusRequester: FocusRequester,
    tmdbFocusRequester: FocusRequester,
    mdbListFocusRequester: FocusRequester,
    animeSkipFocusRequester: FocusRequester,
    debridFocusRequester: FocusRequester,
    autoFocusEnabled: Boolean
) {
    val integrationAnimationScope = rememberCoroutineScope()
    val integrationListState = rememberLazyListState()
    val integrationListSettleOffsetY = remember { Animatable(0f) }
    var animatedFlattenBoundaries by remember {
        mutableStateOf<Set<Int>>(emptySet())
    }
    var deferredBottomCornerSections by remember {
        mutableStateOf<Set<IntegrationSettingsSection>>(emptySet())
    }

    val hubEntryFocusRequester =
        initialFocusRequester ?: hubFocusRequester
    val tmdbParentFocusRequester = remember { FocusRequester() }
    val mdbListParentFocusRequester = remember { FocusRequester() }
    val animeSkipParentFocusRequester = remember { FocusRequester() }

    fun parentFocusRequesterFor(
        section: IntegrationSettingsSection
    ): FocusRequester = when (section) {
        IntegrationSettingsSection.Debrid ->
            hubEntryFocusRequester
        IntegrationSettingsSection.Tmdb ->
            tmdbParentFocusRequester
        IntegrationSettingsSection.MdbList ->
            mdbListParentFocusRequester
        IntegrationSettingsSection.AnimeSkip ->
            animeSkipParentFocusRequester
        IntegrationSettingsSection.Hub ->
            hubEntryFocusRequester
    }

    fun restoreCollapsedParentFocus(
        section: IntegrationSettingsSection
    ) {
        integrationAnimationScope.launch {
            androidx.compose.runtime.withFrameNanos { }
            runCatching {
                parentFocusRequesterFor(section).requestFocus()
            }
        }
    }

    val visibleSections = listOf(
        IntegrationSettingsSection.Debrid,
        IntegrationSettingsSection.Tmdb,
        IntegrationSettingsSection.MdbList,
        IntegrationSettingsSection.AnimeSkip
    )
    val expandedSections =
        if (selectedSection == IntegrationSettingsSection.Hub) {
            emptySet()
        } else {
            setOf(selectedSection)
        }

    fun groupPositionFor(
        section: IntegrationSettingsSection,
        expanded: Set<IntegrationSettingsSection> =
            expandedSections
    ): SettingsGroupPosition {
        val index = visibleSections.indexOf(section)
        if (index < 0) return SettingsGroupPosition.SINGLE
        if (section in expanded) {
            return SettingsGroupPosition.TOP
        }

        val startsGroup =
            index == 0 ||
                visibleSections[index - 1] in expanded
        val endsGroup =
            index == visibleSections.lastIndex ||
                visibleSections[index + 1] in expanded
        return when {
            startsGroup && endsGroup ->
                SettingsGroupPosition.SINGLE
            startsGroup -> SettingsGroupPosition.TOP
            endsGroup -> SettingsGroupPosition.BOTTOM
            else -> SettingsGroupPosition.MIDDLE
        }
    }

    fun headerTopRounded(
        section: IntegrationSettingsSection,
        expanded: Set<IntegrationSettingsSection>
    ): Boolean {
        return when (groupPositionFor(section, expanded)) {
            SettingsGroupPosition.SINGLE,
            SettingsGroupPosition.TOP -> true
            SettingsGroupPosition.MIDDLE,
            SettingsGroupPosition.BOTTOM -> false
        }
    }

    fun headerBottomRounded(
        section: IntegrationSettingsSection,
        expanded: Set<IntegrationSettingsSection>
    ): Boolean {
        return when (groupPositionFor(section, expanded)) {
            SettingsGroupPosition.SINGLE,
            SettingsGroupPosition.BOTTOM -> true
            SettingsGroupPosition.TOP,
            SettingsGroupPosition.MIDDLE -> false
        }
    }

    fun beginBoundaryReturnAnimation(
        boundaries: Set<Int>
    ) {
        animatedFlattenBoundaries = boundaries
        if (boundaries.isNotEmpty()) {
            integrationAnimationScope.launch {
                delay(260)
                if (animatedFlattenBoundaries == boundaries) {
                    animatedFlattenBoundaries = emptySet()
                }
            }
        }
    }

    fun immediateFlattenBoundariesForCollapse(
        section: IntegrationSettingsSection,
        before: Set<IntegrationSettingsSection>,
        after: Set<IntegrationSettingsSection>
    ): Set<Int> {
        val sectionIndex = visibleSections.indexOf(section)
        if (sectionIndex < 0) return emptySet()

        return listOf(sectionIndex - 1, sectionIndex)
            .filter {
                it >= 0 && it < visibleSections.lastIndex
            }
            .filter { boundaryIndex ->
                val upper = visibleSections[boundaryIndex]
                val lower =
                    visibleSections[boundaryIndex + 1]

                val upperRoundedBefore =
                    headerBottomRounded(upper, before)
                val lowerRoundedBefore =
                    headerTopRounded(lower, before)
                val upperRoundedAfter =
                    headerBottomRounded(upper, after)
                val lowerRoundedAfter =
                    headerTopRounded(lower, after)

                val finalBoundaryIsFlat =
                    !upperRoundedAfter &&
                        !lowerRoundedAfter
                val roundedEdgeNeedsToFlatten =
                    (
                        upperRoundedBefore &&
                            !upperRoundedAfter
                        ) ||
                        (
                            lowerRoundedBefore &&
                                !lowerRoundedAfter
                            )

                finalBoundaryIsFlat &&
                    roundedEdgeNeedsToFlatten
            }
            .toSet()
    }

    fun animateTopFlattenFor(
        section: IntegrationSettingsSection
    ): Boolean {
        val index = visibleSections.indexOf(section)
        return index > 0 &&
            (index - 1) in animatedFlattenBoundaries
    }

    fun animateBottomFlattenFor(
        section: IntegrationSettingsSection
    ): Boolean {
        val index = visibleSections.indexOf(section)
        return index >= 0 &&
            index < visibleSections.lastIndex &&
            index in animatedFlattenBoundaries
    }

    fun releaseDeferredBottomAfterCollapse(
        section: IntegrationSettingsSection
    ) {
        integrationAnimationScope.launch {
            delay(240)
            if (
                section !in deferredBottomCornerSections
            ) {
                return@launch
            }
            androidx.compose.runtime.withFrameNanos { }
            deferredBottomCornerSections =
                deferredBottomCornerSections - section
        }
    }

    fun collapseSection(
        section: IntegrationSettingsSection,
        itemKey: String
    ) {
        val before = expandedSections
        val after = emptySet<IntegrationSettingsSection>()

        beginBoundaryReturnAnimation(
            immediateFlattenBoundariesForCollapse(
                section = section,
                before = before,
                after = after
            )
        )
        deferredBottomCornerSections =
            deferredBottomCornerSections + section

        val listHasBeenScrolled =
            integrationListState.firstVisibleItemIndex > 0 ||
                integrationListState
                    .firstVisibleItemScrollOffset > 0
        val itemOffsetBefore =
            integrationListState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key == itemKey }
                ?.offset
                ?.toFloat()

        if (
            !listHasBeenScrolled ||
            itemOffsetBefore == null
        ) {
            onSelectSection(
                IntegrationSettingsSection.Hub
            )
            restoreCollapsedParentFocus(section)
            releaseDeferredBottomAfterCollapse(section)
            return
        }

        integrationAnimationScope.launch {
            integrationListSettleOffsetY.stop()
            integrationListSettleOffsetY.snapTo(0f)

            integrationListState.scrollToItem(
                index = 0,
                scrollOffset = 0
            )

            val itemOffsetAfter =
                integrationListState.layoutInfo
                    .visibleItemsInfo
                    .firstOrNull { it.key == itemKey }
                    ?.offset
                    ?.toFloat()

            if (itemOffsetAfter != null) {
                integrationListSettleOffsetY.snapTo(
                    itemOffsetBefore - itemOffsetAfter
                )
            }

            onSelectSection(
                IntegrationSettingsSection.Hub
            )
            restoreCollapsedParentFocus(section)
            releaseDeferredBottomAfterCollapse(section)

            integrationListSettleOffsetY.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = 520,
                    easing = FastOutSlowInEasing
                )
            )
        }
    }

    fun toggleSection(
        section: IntegrationSettingsSection,
        itemKey: String
    ) {
        if (selectedSection == section) {
            collapseSection(section, itemKey)
        } else {
            deferredBottomCornerSections =
                deferredBottomCornerSections - section
            animatedFlattenBoundaries = emptySet()
            onSelectSection(section)
        }
    }

    BackHandler(
        enabled =
            selectedSection != IntegrationSettingsSection.Hub
    ) {
        val section = selectedSection
        if (section != IntegrationSettingsSection.Hub) {
            collapseSection(
                section = section,
                itemKey = when (section) {
                    IntegrationSettingsSection.Debrid ->
                        "integration_debrid"
                    IntegrationSettingsSection.Tmdb ->
                        "integration_tmdb"
                    IntegrationSettingsSection.MdbList ->
                        "integration_mdblist"
                    IntegrationSettingsSection.AnimeSkip ->
                        "integration_animeskip"
                    IntegrationSettingsSection.Hub ->
                        "integration_debrid"
                }
            )
        }
    }

    LaunchedEffect(autoFocusEnabled) {
        if (
            autoFocusEnabled &&
            selectedSection ==
                IntegrationSettingsSection.Hub
        ) {
            runCatching {
                hubEntryFocusRequester.requestFocus()
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(
                R.string.settings_integrations_section
            ),
            subtitle = stringResource(
                R.string.settings_integrations_section_subtitle
            )
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds()
        ) {
            LazyColumn(
                state = integrationListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationY =
                            integrationListSettleOffsetY.value
                    },
                contentPadding =
                    androidx.compose.foundation.layout.PaddingValues(
                        bottom = 12.dp
                    ),
                verticalArrangement =
                    Arrangement.spacedBy(SettingsRowGap)
            ) {
                item(key = "integration_debrid") {
                    IntegrationExpandableSection(
                        title = stringResource(
                            R.string.debrid_title
                        ),
                        subtitle = stringResource(
                            R.string.debrid_subtitle
                        ),
                        expanded =
                            selectedSection ==
                                IntegrationSettingsSection.Debrid,
                        onToggle = {
                            toggleSection(
                                IntegrationSettingsSection.Debrid,
                                "integration_debrid"
                            )
                        },
                        focusRequester =
                            hubEntryFocusRequester,
                        groupPosition = groupPositionFor(
                            IntegrationSettingsSection.Debrid
                        ),
                        animateTopFlatten =
                            animateTopFlattenFor(
                                IntegrationSettingsSection.Debrid
                            ),
                        animateBottomFlatten =
                            animateBottomFlattenFor(
                                IntegrationSettingsSection.Debrid
                            ),
                        deferBottomCorner =
                            IntegrationSettingsSection.Debrid in
                                deferredBottomCornerSections
                    ) {
                        DebridSettingsContent(
                            initialFocusRequester =
                                debridFocusRequester,
                            embedded = true
                        )
                    }
                }

                item(key = "integration_tmdb") {
                    IntegrationExpandableSection(
                        title = "TMDB",
                        subtitle = stringResource(
                            R.string.settings_tmdb_subtitle
                        ),
                        expanded =
                            selectedSection ==
                                IntegrationSettingsSection.Tmdb,
                        onToggle = {
                            toggleSection(
                                IntegrationSettingsSection.Tmdb,
                                "integration_tmdb"
                            )
                        },
                        focusRequester = tmdbParentFocusRequester,
                        groupPosition = groupPositionFor(
                            IntegrationSettingsSection.Tmdb
                        ),
                        animateTopFlatten =
                            animateTopFlattenFor(
                                IntegrationSettingsSection.Tmdb
                            ),
                        animateBottomFlatten =
                            animateBottomFlattenFor(
                                IntegrationSettingsSection.Tmdb
                            ),
                        deferBottomCorner =
                            IntegrationSettingsSection.Tmdb in
                                deferredBottomCornerSections
                    ) {
                        TmdbSettingsContent(
                            initialFocusRequester =
                                tmdbFocusRequester,
                            embedded = true
                        )
                    }
                }

                item(key = "integration_mdblist") {
                    IntegrationExpandableSection(
                        title = "MDBList",
                        subtitle = stringResource(
                            R.string.settings_mdblist_subtitle
                        ),
                        expanded =
                            selectedSection ==
                                IntegrationSettingsSection.MdbList,
                        onToggle = {
                            toggleSection(
                                IntegrationSettingsSection.MdbList,
                                "integration_mdblist"
                            )
                        },
                        focusRequester = mdbListParentFocusRequester,
                        groupPosition = groupPositionFor(
                            IntegrationSettingsSection.MdbList
                        ),
                        animateTopFlatten =
                            animateTopFlattenFor(
                                IntegrationSettingsSection.MdbList
                            ),
                        animateBottomFlatten =
                            animateBottomFlattenFor(
                                IntegrationSettingsSection.MdbList
                            ),
                        deferBottomCorner =
                            IntegrationSettingsSection.MdbList in
                                deferredBottomCornerSections
                    ) {
                        MDBListSettingsContent(
                            initialFocusRequester =
                                mdbListFocusRequester,
                            embedded = true
                        )
                    }
                }

                item(key = "integration_animeskip") {
                    IntegrationExpandableSection(
                        title = "Anime-Skip",
                        subtitle = stringResource(
                            R.string.settings_animeskip_subtitle
                        ),
                        expanded =
                            selectedSection ==
                                IntegrationSettingsSection.AnimeSkip,
                        onToggle = {
                            toggleSection(
                                IntegrationSettingsSection.AnimeSkip,
                                "integration_animeskip"
                            )
                        },
                        focusRequester = animeSkipParentFocusRequester,
                        groupPosition = groupPositionFor(
                            IntegrationSettingsSection.AnimeSkip
                        ),
                        animateTopFlatten =
                            animateTopFlattenFor(
                                IntegrationSettingsSection.AnimeSkip
                            ),
                        animateBottomFlatten =
                            animateBottomFlattenFor(
                                IntegrationSettingsSection.AnimeSkip
                            ),
                        deferBottomCorner =
                            IntegrationSettingsSection.AnimeSkip in
                                deferredBottomCornerSections
                    ) {
                        AnimeSkipSettingsContent(
                            initialFocusRequester =
                                animeSkipFocusRequester,
                            embedded = true
                        )
                    }
                }
            }

            SettingsVerticalScrollIndicators(
                state = integrationListState
            )
        }
    }
}

@Composable
private fun IntegrationExpandableSection(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    groupPosition: SettingsGroupPosition,
    animateTopFlatten: Boolean,
    animateBottomFlatten: Boolean,
    deferBottomCorner: Boolean,
    focusRequester: FocusRequester? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(
            if (expanded) SettingsRowGap else 0.dp
        )
    ) {
        SettingsActionRow(
            title = title,
            subtitle = subtitle,
            onClick = onToggle,
            trailingIcon =
                if (expanded) {
                    Icons.Default.ExpandMore
                } else {
                    Icons.Default.ChevronRight
                },
            modifier =
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                },
            showDivider = false,
            groupPosition = when {
                expanded -> SettingsGroupPosition.TOP
                deferBottomCorner -> when (groupPosition) {
                    SettingsGroupPosition.SINGLE ->
                        SettingsGroupPosition.TOP
                    SettingsGroupPosition.BOTTOM ->
                        SettingsGroupPosition.MIDDLE
                    SettingsGroupPosition.TOP ->
                        SettingsGroupPosition.TOP
                    SettingsGroupPosition.MIDDLE ->
                        SettingsGroupPosition.MIDDLE
                }
                else -> groupPosition
            },
            animateTopFlatten = animateTopFlatten,
            animateBottomFlatten = animateBottomFlatten
        )

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = tween(
                    durationMillis = 240,
                    easing = FastOutSlowInEasing
                ),
                expandFrom = Alignment.Top
            ),
            exit = shrinkVertically(
                animationSpec = tween(
                    durationMillis = 240,
                    easing = FastOutSlowInEasing
                ),
                shrinkTowards = Alignment.Top
            )
        ) {
            SettingsExpandedSectionSurface {
                content()
            }
        }
    }
}
