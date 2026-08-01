@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

@Composable
fun TrackingSettingsScreen(
    traktViewModel: TraktViewModel = hiltViewModel(),
    simklViewModel: SimklSettingsViewModel = hiltViewModel(),
    trackingViewModel: TrackingSettingsViewModel = hiltViewModel(),
    onNavigateToTrakt: () -> Unit,
    onNavigateToSimkl: () -> Unit,
    onBackPress: () -> Unit
) {
    val traktState by traktViewModel.uiState.collectAsStateWithLifecycle()
    val simklState by simklViewModel.uiState.collectAsStateWithLifecycle()
    val trackingState by trackingViewModel.uiState.collectAsStateWithLifecycle()

    val firstFocusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    var showLibrarySourceDialog by remember { mutableStateOf(false) }
    var showWatchProgressDialog by remember { mutableStateOf(false) }

    val hasDialog = showLibrarySourceDialog || showWatchProgressDialog

    BackHandler(enabled = !hasDialog) {
        onBackPress()
    }

    LaunchedEffect(Unit) {
        delay(160L)
        runCatching { firstFocusRequester.requestFocus() }
    }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.settings_tracking_title),
        subtitle = stringResource(R.string.settings_tracking_description)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_tracking_title),
            subtitle = stringResource(R.string.settings_tracking_description)
        )

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item(key = "tracking_accounts") {
                    SettingsGroupCard(
                        title = stringResource(R.string.tracking_accounts_title),
                        subtitle = stringResource(R.string.tracking_accounts_subtitle)
                    ) {
                        SettingsActionRow(
                            title = stringResource(R.string.trakt_name),
                            subtitle = traktAccountSubtitle(traktState),
                            value = traktAccountStatus(traktState),
                            onClick = onNavigateToTrakt,
                            modifier = Modifier.focusRequester(firstFocusRequester)
                        )

                        SettingsActionRow(
                            title = stringResource(R.string.simkl_name),
                            subtitle = simklAccountSubtitle(simklState),
                            value = simklAccountStatus(simklState),
                            onClick = onNavigateToSimkl
                        )
                    }
                }

                item(key = "tracking_sources") {
                    SettingsGroupCard(
                        title = stringResource(R.string.tracking_sources_title),
                        subtitle = stringResource(R.string.tracking_sources_subtitle)
                    ) {
                        SettingsActionRow(
                            title = stringResource(R.string.trakt_library_source_title),
                            subtitle = stringResource(R.string.trakt_library_source_subtitle),
                            value = librarySourceLabel(trackingState.librarySourceMode),
                            enabled = trackingState.isReady,
                            onClick = { showLibrarySourceDialog = true }
                        )

                        SettingsActionRow(
                            title = stringResource(R.string.trakt_watch_progress_title),
                            subtitle = stringResource(R.string.trakt_watch_progress_subtitle),
                            value = watchProgressSourceLabel(
                                trackingState.watchProgressSource
                            ),
                            enabled = trackingState.isReady,
                            onClick = { showWatchProgressDialog = true }
                        )
                    }
                }
            }

            SettingsVerticalScrollIndicators(state = listState)
        }
    }

    if (showLibrarySourceDialog) {
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.trakt_library_source_dialog_title),
            subtitle = stringResource(
                R.string.tracking_library_source_dialog_subtitle
            ),
            options = trackingState.availableLibrarySourceModes.map { mode ->
                SettingsPickerOption(
                    value = mode,
                    title = librarySourceLabel(mode)
                )
            },
            selectedValue = trackingState.librarySourceMode,
            onOptionSelected = { mode ->
                trackingViewModel.selectLibrarySourceMode(mode)
                showLibrarySourceDialog = false
            },
            onDismiss = { showLibrarySourceDialog = false },
            width = 620.dp,
            maxHeight = 340.dp
        )
    }

    if (showWatchProgressDialog) {
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.trakt_watch_progress_dialog_title),
            subtitle = stringResource(
                R.string.tracking_watch_progress_dialog_subtitle
            ),
            options = trackingState.availableWatchProgressSources.map { source ->
                SettingsPickerOption(
                    value = source,
                    title = watchProgressSourceLabel(source)
                )
            },
            selectedValue = trackingState.watchProgressSource,
            onOptionSelected = { source ->
                trackingViewModel.selectWatchProgressSource(source)
                showWatchProgressDialog = false
            },
            onDismiss = { showWatchProgressDialog = false },
            width = 660.dp,
            maxHeight = 360.dp
        )
    }
}

@Composable
private fun traktAccountSubtitle(state: TraktUiState): String {
    return when (state.mode) {
        TraktConnectionMode.CONNECTED -> stringResource(
            R.string.trakt_connected_as,
            state.username ?: stringResource(R.string.trakt_user_fallback)
        )
        TraktConnectionMode.AWAITING_APPROVAL ->
            stringResource(R.string.trakt_awaiting_instruction)
        TraktConnectionMode.DISCONNECTED ->
            stringResource(R.string.trakt_description)
    }
}

@Composable
private fun traktAccountStatus(state: TraktUiState): String {
    return when {
        state.isLoading && state.mode != TraktConnectionMode.CONNECTED ->
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
private fun simklAccountSubtitle(state: SimklSettingsUiState): String {
    return when (state.mode) {
        SimklConnectionMode.CONNECTED -> stringResource(
            R.string.simkl_connected_as,
            state.username ?: stringResource(R.string.simkl_user_fallback)
        )
        SimklConnectionMode.AWAITING_APPROVAL ->
            stringResource(R.string.simkl_awaiting_instruction)
        SimklConnectionMode.DISCONNECTED ->
            stringResource(R.string.simkl_description)
    }
}

@Composable
private fun simklAccountStatus(state: SimklSettingsUiState): String {
    return when {
        state.isLoading && state.mode != SimklConnectionMode.CONNECTED ->
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
private fun watchProgressSourceLabel(source: WatchProgressSource): String {
    return when (source) {
        WatchProgressSource.TRAKT -> stringResource(R.string.trakt_name)
        WatchProgressSource.SIMKL -> stringResource(R.string.simkl_name)
        WatchProgressSource.NUVIO_SYNC ->
            stringResource(R.string.trakt_watch_progress_source_nuvio)
    }
}

@Composable
private fun librarySourceLabel(mode: LibrarySourceMode): String {
    return when (mode) {
        LibrarySourceMode.TRAKT -> stringResource(R.string.trakt_name)
        LibrarySourceMode.SIMKL -> stringResource(R.string.simkl_name)
        LibrarySourceMode.LOCAL ->
            stringResource(R.string.trakt_library_source_nuvio)
    }
}
