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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch

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
    val trackingAnimationScope = rememberCoroutineScope()
    val accountsParentFocusRequester = remember { FocusRequester() }
    val sourcesParentFocusRequester = remember { FocusRequester() }
    val traktFocusRequester = remember { FocusRequester() }
    val simklFocusRequester = remember { FocusRequester() }

    var accountsExpanded by remember { mutableStateOf(false) }
    var sourcesExpanded by remember { mutableStateOf(false) }
    var accountsClosing by remember { mutableStateOf(false) }
    var sourcesClosing by remember { mutableStateOf(false) }
    var librarySourceExpanded by remember { mutableStateOf(false) }
    var watchProgressExpanded by remember { mutableStateOf(false) }

    fun toggleAccounts() {
        if (accountsExpanded) {
            accountsClosing = true
            accountsExpanded = false
            trackingAnimationScope.launch {
                delay(240L)
                accountsClosing = false
            }
        } else {
            accountsClosing = false
            accountsExpanded = true
        }
    }

    fun toggleSources() {
        if (sourcesExpanded) {
            sourcesClosing = true
            librarySourceExpanded = false
            watchProgressExpanded = false
            sourcesExpanded = false
            trackingAnimationScope.launch {
                delay(240L)
                sourcesClosing = false
            }
        } else {
            sourcesClosing = false
            sourcesExpanded = true
        }
    }

    /*
     * A Tracking detail destination is removed from composition while Trakt
     * or Simkl is on top. Re-open Accounts first so the exact child row exists,
     * then restore that row instead of allowing Settings to fall back to rail.
     */
    LaunchedEffect(returnFocusAccount) {
        val target =
            returnFocusAccount ?: return@LaunchedEffect
        accountsClosing = false
        accountsExpanded = true

        // The pop transition is 350 ms. Restore only after Settings is
        // genuinely visible again so the outgoing account screen cannot
        // steal or visually replay this focus request.
        delay(380L)

        val requester = when (target) {
            "trakt" -> traktFocusRequester
            "simkl" -> simklFocusRequester
            else -> null
        }

        if (requester == null) {
            onReturnFocusConsumed()
            return@LaunchedEffect
        }

        var focused = runCatching {
            requester.requestFocus()
        }.getOrDefault(false)

        if (!focused) {
            androidx.compose.runtime.withFrameNanos { }
            focused = runCatching {
                requester.requestFocus()
            }.getOrDefault(false)
        }

        if (focused) {
            onReturnFocusConsumed()
        }
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
                verticalArrangement =
                    Arrangement.spacedBy(SettingsRowGap)
            ) {
                item(key = "tracking_accounts") {
                    TrackingExpandableGroup(
                        title = stringResource(
                            R.string.tracking_accounts_title
                        ),
                        subtitle = stringResource(
                            R.string.tracking_accounts_subtitle
                        ),
                        expanded = accountsExpanded,
                        closing = accountsClosing,
                        closedPosition =
                            if (sourcesExpanded) {
                                SettingsGroupPosition.SINGLE
                            } else {
                                SettingsGroupPosition.TOP
                            },
                        expandedPosition =
                            SettingsGroupPosition.TOP,
                        closingPosition =
                            SettingsGroupPosition.TOP,
                        forceAnimateBottomFlatten =
                            sourcesClosing,
                        onToggle = ::toggleAccounts,
                        focusRequester =
                            initialFocusRequester
                                ?: accountsParentFocusRequester
                    ) {
                        SettingsActionRow(
                            title = stringResource(R.string.trakt_name),
                            subtitle = traktAccountSubtitle(traktState),
                            value = traktAccountStatus(traktState),
                            onClick = onNavigateToTrakt,
                            modifier = Modifier.focusRequester(
                                traktFocusRequester
                            ),
                            showDivider = false,
                            groupPosition =
                                SettingsGroupPosition.MIDDLE
                        )

                        SettingsActionRow(
                            title = stringResource(R.string.simkl_name),
                            subtitle = simklAccountSubtitle(simklState),
                            value = simklAccountStatus(simklState),
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
                    TrackingExpandableGroup(
                        title = stringResource(
                            R.string.tracking_sources_title
                        ),
                        subtitle = stringResource(
                            R.string.tracking_sources_subtitle_compact
                        ),
                        expanded = sourcesExpanded,
                        closing = sourcesClosing,
                        closedPosition =
                            if (accountsExpanded) {
                                SettingsGroupPosition.SINGLE
                            } else {
                                SettingsGroupPosition.BOTTOM
                            },
                        expandedPosition =
                            SettingsGroupPosition.TOP,
                        closingPosition =
                            if (accountsExpanded) {
                                SettingsGroupPosition.TOP
                            } else {
                                SettingsGroupPosition.MIDDLE
                            },
                        forceAnimateTopFlatten =
                            accountsClosing ||
                                (
                                    sourcesClosing &&
                                        !accountsExpanded
                                    ),
                        onToggle = ::toggleSources,
                        focusRequester = sourcesParentFocusRequester
                    ) {
                        TrackingNestedSourceRow(
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
                            expanded = librarySourceExpanded,
                            collapsedPosition =
                                SettingsGroupPosition.MIDDLE,
                            onToggle = {
                                librarySourceExpanded =
                                    !librarySourceExpanded
                            }
                        ) {
                            trackingState.availableLibrarySourceModes
                                .forEach { mode ->
                                    TrackingChoiceRow(
                                        title = librarySourceLabel(mode),
                                        selected =
                                            mode ==
                                                trackingState
                                                    .librarySourceMode,
                                        groupPosition =
                                            SettingsGroupPosition.MIDDLE,
                                        onClick = {
                                            trackingViewModel
                                                .selectLibrarySourceMode(
                                                    mode
                                                )
                                            librarySourceExpanded = false
                                        }
                                    )
                                }
                        }

                        TrackingNestedSourceRow(
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
                            expanded = watchProgressExpanded,
                            collapsedPosition =
                                SettingsGroupPosition.BOTTOM,
                            onToggle = {
                                watchProgressExpanded =
                                    !watchProgressExpanded
                            }
                        ) {
                            val choices =
                                trackingState.availableWatchProgressSources
                            choices.forEachIndexed { index, source ->
                                TrackingChoiceRow(
                                    title =
                                        watchProgressSourceLabel(source),
                                    selected =
                                        source ==
                                            trackingState
                                                .watchProgressSource,
                                    groupPosition =
                                        if (index == choices.lastIndex) {
                                            SettingsGroupPosition.BOTTOM
                                        } else {
                                            SettingsGroupPosition.MIDDLE
                                        },
                                    onClick = {
                                        trackingViewModel
                                            .selectWatchProgressSource(
                                                source
                                            )
                                        watchProgressExpanded = false
                                    }
                                )
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
private fun TrackingExpandableGroup(
    title: String,
    subtitle: String,
    expanded: Boolean,
    closing: Boolean,
    closedPosition: SettingsGroupPosition,
    expandedPosition: SettingsGroupPosition,
    closingPosition: SettingsGroupPosition,
    forceAnimateTopFlatten: Boolean = false,
    forceAnimateBottomFlatten: Boolean = false,
    onToggle: () -> Unit,
    focusRequester: FocusRequester,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    var previousExpanded by remember {
        mutableStateOf(expanded)
    }
    val opening = expanded && !previousExpanded

    LaunchedEffect(expanded) {
        previousExpanded = expanded
    }

    val position = when {
        expanded -> expandedPosition
        closing -> closingPosition
        else -> closedPosition
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(
            if (expanded || closing) SettingsRowGap else 0.dp
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
            modifier = Modifier.focusRequester(focusRequester),
            showDivider = false,
            groupPosition = position,
            /*
             * Flattening always uses the same 240 ms shape animation.
             * Rounding outward already animates automatically.
             */
            animateTopFlatten =
                forceAnimateTopFlatten ||
                    (opening &&
                        position == SettingsGroupPosition.MIDDLE),
            animateBottomFlatten =
                forceAnimateBottomFlatten ||
                    (opening &&
                        position == SettingsGroupPosition.TOP)
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
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement =
                    Arrangement.spacedBy(SettingsRowGap)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun TrackingNestedSourceRow(
    title: String,
    subtitle: String,
    value: String,
    enabled: Boolean,
    expanded: Boolean,
    collapsedPosition: SettingsGroupPosition,
    onToggle: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    var previousExpanded by remember {
        mutableStateOf(expanded)
    }
    val opening = expanded && !previousExpanded
    val closing = !expanded && previousExpanded

    LaunchedEffect(expanded) {
        if (expanded) {
            previousExpanded = true
        } else if (previousExpanded) {
            delay(240L)
            previousExpanded = false
        }
    }

    val effectivePosition =
        if (
            (expanded || closing) &&
            collapsedPosition == SettingsGroupPosition.BOTTOM
        ) {
            SettingsGroupPosition.MIDDLE
        } else {
            collapsedPosition
        }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(
            if (expanded || closing) SettingsRowGap else 0.dp
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
            groupPosition = effectivePosition,
            animateBottomFlatten =
                opening &&
                    collapsedPosition ==
                        SettingsGroupPosition.BOTTOM
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
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement =
                    Arrangement.spacedBy(SettingsRowGap)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun TrackingChoiceRow(
    title: String,
    selected: Boolean,
    groupPosition: SettingsGroupPosition,
    onClick: () -> Unit
) {
    SettingsActionRow(
        title = title,
        subtitle = null,
        value = null,
        onClick = onClick,
        trailingIcon =
            if (selected) {
                Icons.Default.Check
            } else {
                Icons.Default.ChevronRight
            },
        showDivider = false,
        groupPosition = groupPosition
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
