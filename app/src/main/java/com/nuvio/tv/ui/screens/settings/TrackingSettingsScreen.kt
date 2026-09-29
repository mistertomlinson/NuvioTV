@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.data.simkl.SimklConnectionMode
import com.nuvio.tv.domain.model.LibrarySourceMode
import kotlinx.coroutines.delay

private enum class TrackingSourceSection {
    LIBRARY,
    WATCH_PROGRESS
}

@Composable
fun TrackingSettingsScreen(
    traktViewModel: TraktViewModel = hiltViewModel(),
    simklViewModel: SimklSettingsViewModel = hiltViewModel(),
    trackingViewModel: TrackingSettingsViewModel = hiltViewModel(),
    onNavigateToTrakt: () -> Unit,
    onNavigateToSimkl: () -> Unit,
    onBackPress: () -> Unit
) {
    val firstFocusRequester = remember { FocusRequester() }

    BackHandler { onBackPress() }

    LaunchedEffect(Unit) {
        delay(160L)
        runCatching { firstFocusRequester.requestFocus() }
    }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.settings_tracking_title),
        subtitle = stringResource(
            R.string.settings_tracking_description_compact
        )
    ) {
        TrackingSettingsContent(
            traktViewModel = traktViewModel,
            simklViewModel = simklViewModel,
            trackingViewModel = trackingViewModel,
            onNavigateToTrakt = onNavigateToTrakt,
            onNavigateToSimkl = onNavigateToSimkl,
            initialFocusRequester = firstFocusRequester
        )
    }
}

@Composable
fun TrackingSettingsContent(
    traktViewModel: TraktViewModel = hiltViewModel(),
    simklViewModel: SimklSettingsViewModel = hiltViewModel(),
    trackingViewModel: TrackingSettingsViewModel = hiltViewModel(),
    onNavigateToTrakt: () -> Unit,
    onNavigateToSimkl: () -> Unit,
    initialFocusRequester: FocusRequester? = null,
    returnFocusAccount: String? = null,
    onReturnFocusConsumed: () -> Unit = {}
) {
    val traktState by
        traktViewModel.uiState.collectAsStateWithLifecycle()
    val simklState by
        simklViewModel.uiState.collectAsStateWithLifecycle()
    val trackingState by
        trackingViewModel.uiState.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    val simklFocusRequester = remember { FocusRequester() }
    var expandedSource by remember {
        mutableStateOf<TrackingSourceSection?>(null)
    }

    LaunchedEffect(returnFocusAccount) {
        val target = returnFocusAccount ?: return@LaunchedEffect
        delay(90L)
        when (target) {
            "trakt" -> initialFocusRequester?.let { requester ->
                runCatching { requester.requestFocus() }
            }
            "simkl" -> runCatching {
                simklFocusRequester.requestFocus()
            }
        }
        onReturnFocusConsumed()
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_tracking_title),
            subtitle = stringResource(
                R.string.settings_tracking_description_compact
            )
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "tracking_accounts") {
                    SettingsGroupCard(
                        title = stringResource(
                            R.string.tracking_accounts_title
                        ),
                        subtitle = stringResource(
                            R.string.tracking_accounts_subtitle
                        ),
                        segmented = true
                    ) {
                        SettingsActionRow(
                            title = stringResource(R.string.trakt_name),
                            subtitle =
                                traktAccountSubtitle(traktState),
                            value =
                                traktAccountStatus(traktState),
                            onClick = onNavigateToTrakt,
                            modifier =
                                if (
                                    initialFocusRequester != null
                                ) {
                                    Modifier.focusRequester(
                                        initialFocusRequester
                                    )
                                } else {
                                    Modifier
                                },
                            showDivider = false,
                            groupPosition =
                                SettingsGroupPosition.TOP
                        )

                        SettingsActionRow(
                            title = stringResource(R.string.simkl_name),
                            subtitle =
                                simklAccountSubtitle(simklState),
                            value =
                                simklAccountStatus(simklState),
                            onClick = onNavigateToSimkl,
                            modifier = Modifier.focusRequester(
                                simklFocusRequester
                            ),
                            showDivider = false,
                            groupPosition =
                                SettingsGroupPosition.BOTTOM
                        )
                    }
                }

                item(key = "tracking_sources") {
                    SettingsGroupCard(
                        title = stringResource(
                            R.string.tracking_sources_title
                        ),
                        subtitle = stringResource(
                            R.string.tracking_sources_subtitle_compact
                        ),
                        segmented = true
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement =
                                Arrangement.spacedBy(SettingsRowGap)
                        ) {
                            TrackingExpandableSourceRow(
                                title = stringResource(
                                    R.string.trakt_library_source_title
                                ),
                                subtitle = stringResource(
                                    R.string.trakt_library_source_subtitle_compact
                                ),
                                value = librarySourceLabel(
                                    trackingState.librarySourceMode
                                ),
                                enabled = trackingState.isReady,
                                expanded =
                                    expandedSource ==
                                        TrackingSourceSection.LIBRARY,
                                groupPosition = when (expandedSource) {
                                    TrackingSourceSection.LIBRARY ->
                                        SettingsGroupPosition.TOP
                                    TrackingSourceSection.WATCH_PROGRESS ->
                                        SettingsGroupPosition.SINGLE
                                    null ->
                                        SettingsGroupPosition.TOP
                                },
                                onToggle = {
                                    expandedSource =
                                        if (
                                            expandedSource ==
                                            TrackingSourceSection.LIBRARY
                                        ) {
                                            null
                                        } else {
                                            TrackingSourceSection.LIBRARY
                                        }
                                }
                            ) {
                                trackingState
                                    .availableLibrarySourceModes
                                    .forEach { mode ->
                                        TrackingChoiceRow(
                                            title =
                                                librarySourceLabel(mode),
                                            selected =
                                                mode ==
                                                    trackingState
                                                        .librarySourceMode,
                                            onClick = {
                                                trackingViewModel
                                                    .selectLibrarySourceMode(
                                                        mode
                                                    )
                                                expandedSource = null
                                            }
                                        )
                                    }
                            }

                            TrackingExpandableSourceRow(
                                title = stringResource(
                                    R.string.trakt_watch_progress_title
                                ),
                                subtitle = stringResource(
                                    R.string.trakt_watch_progress_subtitle_compact
                                ),
                                value = watchProgressSourceLabel(
                                    trackingState.watchProgressSource
                                ),
                                enabled = trackingState.isReady,
                                expanded =
                                    expandedSource ==
                                        TrackingSourceSection.WATCH_PROGRESS,
                                groupPosition = when (expandedSource) {
                                    TrackingSourceSection.WATCH_PROGRESS ->
                                        SettingsGroupPosition.TOP
                                    TrackingSourceSection.LIBRARY ->
                                        SettingsGroupPosition.SINGLE
                                    null ->
                                        SettingsGroupPosition.BOTTOM
                                },
                                onToggle = {
                                    expandedSource =
                                        if (
                                            expandedSource ==
                                            TrackingSourceSection.WATCH_PROGRESS
                                        ) {
                                            null
                                        } else {
                                            TrackingSourceSection.WATCH_PROGRESS
                                        }
                                }
                            ) {
                                trackingState
                                    .availableWatchProgressSources
                                    .forEach { source ->
                                        TrackingChoiceRow(
                                            title =
                                                watchProgressSourceLabel(
                                                    source
                                                ),
                                            selected =
                                                source ==
                                                    trackingState
                                                        .watchProgressSource,
                                            onClick = {
                                                trackingViewModel
                                                    .selectWatchProgressSource(
                                                        source
                                                    )
                                                expandedSource = null
                                            }
                                        )
                                    }
                            }
                        }
                    }
                }
            }

            SettingsVerticalScrollIndicators(state = listState)
        }
    }
}

@Composable
private fun TrackingExpandableSourceRow(
    title: String,
    subtitle: String,
    value: String,
    enabled: Boolean,
    expanded: Boolean,
    groupPosition: SettingsGroupPosition,
    onToggle: () -> Unit,
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
            value = value,
            enabled = enabled,
            onClick = onToggle,
            trailingIcon =
                if (expanded) {
                    Icons.Default.ExpandMore
                } else {
                    Icons.Default.ChevronRight
                },
            showDivider = false,
            groupPosition =
                if (expanded) {
                    SettingsGroupPosition.TOP
                } else {
                    groupPosition
                }
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

@Composable
private fun TrackingChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    SettingsActionRow(
        title = title,
        subtitle = null,
        value =
            if (selected) {
                stringResource(R.string.cd_selected)
            } else {
                null
            },
        onClick = onClick,
        trailingIcon = Icons.Default.ChevronRight,
        showDivider = false,
        groupPosition = SettingsGroupPosition.MIDDLE
    )
}

@Composable
private fun traktAccountSubtitle(state: TraktUiState): String {
    return when (state.mode) {
        TraktConnectionMode.CONNECTED -> stringResource(
            R.string.trakt_connected_as,
            state.username
                ?: stringResource(R.string.trakt_user_fallback)
        )
        TraktConnectionMode.AWAITING_APPROVAL ->
            stringResource(R.string.trakt_awaiting_instruction)
        TraktConnectionMode.DISCONNECTED ->
            stringResource(R.string.tracking_trakt_subtitle_compact)
    }
}

@Composable
private fun traktAccountStatus(state: TraktUiState): String {
    return when {
        state.isLoading &&
            state.mode != TraktConnectionMode.CONNECTED ->
            stringResource(R.string.tracking_status_connecting)
        state.mode == TraktConnectionMode.CONNECTED ->
            stringResource(R.string.tracking_status_connected)
        state.mode == TraktConnectionMode.AWAITING_APPROVAL ->
            stringResource(R.string.tracking_status_waiting)
        else ->
            stringResource(R.string.tracking_status_disconnected)
    }
}

@Composable
private fun simklAccountSubtitle(
    state: SimklSettingsUiState
): String {
    return when (state.mode) {
        SimklConnectionMode.CONNECTED -> stringResource(
            R.string.simkl_connected_as,
            state.username
                ?: stringResource(R.string.simkl_user_fallback)
        )
        SimklConnectionMode.AWAITING_APPROVAL ->
            stringResource(R.string.simkl_awaiting_instruction)
        SimklConnectionMode.DISCONNECTED ->
            stringResource(R.string.tracking_simkl_subtitle_compact)
    }
}

@Composable
private fun simklAccountStatus(
    state: SimklSettingsUiState
): String {
    return when {
        state.isLoading &&
            state.mode != SimklConnectionMode.CONNECTED ->
            stringResource(R.string.tracking_status_connecting)
        state.mode == SimklConnectionMode.CONNECTED ->
            stringResource(R.string.tracking_status_connected)
        state.mode == SimklConnectionMode.AWAITING_APPROVAL ->
            stringResource(R.string.tracking_status_waiting)
        else ->
            stringResource(R.string.tracking_status_disconnected)
    }
}

@Composable
private fun watchProgressSourceLabel(
    source: WatchProgressSource
): String {
    return when (source) {
        WatchProgressSource.TRAKT ->
            stringResource(R.string.trakt_name)
        WatchProgressSource.SIMKL ->
            stringResource(R.string.simkl_name)
        WatchProgressSource.NUVIO_SYNC ->
            stringResource(
                R.string.trakt_watch_progress_source_nuvio
            )
    }
}

@Composable
private fun librarySourceLabel(
    mode: LibrarySourceMode
): String {
    return when (mode) {
        LibrarySourceMode.TRAKT ->
            stringResource(R.string.trakt_name)
        LibrarySourceMode.SIMKL ->
            stringResource(R.string.simkl_name)
        LibrarySourceMode.LOCAL ->
            stringResource(R.string.trakt_library_source_nuvio)
    }
}
