@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.os.Build
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
        subtitle = stringResource(R.string.settings_tracking_description),
        destination = SettingsSectionDestination.External
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
    onNavigateToAuthQrSignIn: () -> Unit = {},
    onNavigateToManageProfiles: () -> Unit = {},
    onNavigateToSupportersContributors: () -> Unit = {},
    profileViewModel: ProfileSettingsViewModel = hiltViewModel()
) {
    SettingsCompactContent {
        SettingsScreenContent(
            showBuiltInHeader = showBuiltInHeader,
            onNavigateToTracking = onNavigateToTracking,
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
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                                allowDetailAutofocus = true
                                false
                            } else {
                                false
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
                        SettingsCategory.TRACKING -> Unit
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
    BackHandler(enabled = selectedSection != IntegrationSettingsSection.Hub) {
        onSelectSection(IntegrationSettingsSection.Hub)
    }
    val hubEntryFocusRequester = initialFocusRequester ?: hubFocusRequester

    LaunchedEffect(selectedSection, autoFocusEnabled) {
        if (!autoFocusEnabled) return@LaunchedEffect
        val requester = when (selectedSection) {
            IntegrationSettingsSection.Hub -> hubEntryFocusRequester
            IntegrationSettingsSection.Debrid -> debridFocusRequester
            IntegrationSettingsSection.Tmdb -> tmdbFocusRequester
            IntegrationSettingsSection.MdbList -> mdbListFocusRequester
            IntegrationSettingsSection.AnimeSkip -> animeSkipFocusRequester
        }
        runCatching { requester.requestFocus() }
    }

    when (selectedSection) {
        IntegrationSettingsSection.Hub -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SettingsDetailHeader(
                    title = stringResource(R.string.settings_integrations_section),
                    subtitle = stringResource(R.string.settings_integrations_section_subtitle)
                )

                SettingsGroupCard(
                    modifier = Modifier
                        .fillMaxWidth(),
                    segmented = true
                ) {
                    val integrationHubState = rememberLazyListState()
                    Box(modifier = Modifier.fillMaxWidth()) {
                        LazyColumn(
                            state = integrationHubState,
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(SettingsRowGap)
                        ) {
                            item(key = "integration_hub_debrid") {
                                SettingsActionRow(
                                    title = stringResource(R.string.debrid_title),
                                    subtitle = stringResource(R.string.debrid_subtitle),
                                    onClick = { onSelectSection(IntegrationSettingsSection.Debrid) },
                                    modifier = Modifier.focusRequester(hubEntryFocusRequester)
                                )
                            }
                            item(key = "integration_hub_tmdb") {
                                SettingsActionRow(
                                    title = "TMDB",
                                    subtitle = stringResource(R.string.settings_tmdb_subtitle),
                                    onClick = { onSelectSection(IntegrationSettingsSection.Tmdb) }
                                )
                            }
                            item(key = "integration_hub_mdblist") {
                                SettingsActionRow(
                                    title = "MDBList",
                                    subtitle = stringResource(R.string.settings_mdblist_subtitle),
                                    onClick = { onSelectSection(IntegrationSettingsSection.MdbList) }
                                )
                            }
                            item(key = "integration_hub_animeskip") {
                                SettingsActionRow(
                                    title = "Anime-Skip",
                                    subtitle = stringResource(R.string.settings_animeskip_subtitle),
                                    onClick = { onSelectSection(IntegrationSettingsSection.AnimeSkip) }
                                )
                            }
                        }
                        SettingsVerticalScrollIndicators(state = integrationHubState)
                    }
                }
            }
        }

        IntegrationSettingsSection.Debrid -> {
            DebridSettingsContent(
                initialFocusRequester = debridFocusRequester
            )
        }

        IntegrationSettingsSection.Tmdb -> {
            TmdbSettingsContent(
                initialFocusRequester = tmdbFocusRequester
            )
        }

        IntegrationSettingsSection.MdbList -> {
            MDBListSettingsContent(
                initialFocusRequester = mdbListFocusRequester
            )
        }

        IntegrationSettingsSection.AnimeSkip -> {
            AnimeSkipSettingsContent(
                initialFocusRequester = animeSkipFocusRequester
            )
        }
    }
}
