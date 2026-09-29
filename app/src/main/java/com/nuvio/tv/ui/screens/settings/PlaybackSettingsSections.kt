@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.data.local.AddonSubtitleStartupMode
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.TrailerSettings
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class PlaybackSection {
    GENERAL,
    STREAM_SELECTION,
    AUDIO_TRAILER,
    SUBTITLES
}

private data class PlaybackGeneralUi(
    val isExternalPlayer: Boolean,
    val frameRateMatchingLabel: String
)

private data class PlaybackStreamSelectionUi(
    val playerPreferenceLabel: String
)

private fun frameRateMatchingModeLabel(mode: FrameRateMatchingMode, off: String, onStart: String, onStartStop: String): String {
    return when (mode) {
        FrameRateMatchingMode.OFF -> off
        FrameRateMatchingMode.START -> onStart
        FrameRateMatchingMode.START_STOP -> onStartStop
    }
}

@Composable
internal fun PlaybackSettingsSections(
    initialFocusRequester: FocusRequester? = null,
    playerSettings: PlayerSettings,
    trailerSettings: TrailerSettings,
    onShowPlayerPreferenceDialog: () -> Unit,
    onShowAudioLanguageDialog: () -> Unit,
    onShowSecondaryAudioLanguageDialog: () -> Unit,
    onShowDecoderPriorityDialog: () -> Unit,
    onShowLanguageDialog: () -> Unit,
    onShowSecondaryLanguageDialog: () -> Unit,
    onShowSubtitleStartupModeDialog: () -> Unit,
    onShowTextColorDialog: () -> Unit,
    onShowBackgroundColorDialog: () -> Unit,
    onShowOutlineColorDialog: () -> Unit,
    onShowStreamAutoPlayModeDialog: () -> Unit,
    onShowStreamAutoPlaySourceDialog: () -> Unit,
    onShowStreamAutoPlayAddonSelectionDialog: () -> Unit,
    onShowStreamAutoPlayPluginSelectionDialog: () -> Unit,
    onShowStreamRegexDialog: () -> Unit,
    onShowNextEpisodeThresholdModeDialog: () -> Unit,
    onShowReuseLastLinkCacheDialog: () -> Unit,
    onSetStreamAutoPlayNextEpisodeEnabled: (Boolean) -> Unit,
    onSetStreamAutoPlayPreferBingeGroupForNextEpisode: (Boolean) -> Unit,
    onSetNextEpisodeThresholdPercent: (Float) -> Unit,
    onSetNextEpisodeThresholdMinutesBeforeEnd: (Float) -> Unit,
    onSetStreamAutoPlayTimeoutSeconds: (Int) -> Unit,
    onSetReuseLastLinkEnabled: (Boolean) -> Unit,
    onSetLoadingOverlayEnabled: (Boolean) -> Unit,
    onSetPauseOverlayEnabled: (Boolean) -> Unit,
    onSetOsdClockEnabled: (Boolean) -> Unit,
    onSetSkipIntroEnabled: (Boolean) -> Unit,
    onSetFrameRateMatchingMode: (FrameRateMatchingMode) -> Unit,
    onSetResolutionMatchingEnabled: (Boolean) -> Unit,
    onSetTrailerEnabled: (Boolean) -> Unit,
    onSetTrailerDelaySeconds: (Int) -> Unit,
    onSetSkipSilence: (Boolean) -> Unit,
    onSetTunnelingEnabled: (Boolean) -> Unit,
    onSetMapDV7ToHevc: (Boolean) -> Unit,
    onSetSubtitleSize: (Int) -> Unit,
    onSetSubtitleVerticalOffset: (Int) -> Unit,
    onSetSubtitleBold: (Boolean) -> Unit,
    onSetSubtitleOutlineEnabled: (Boolean) -> Unit,
    onSetUseLibass: (Boolean) -> Unit,
    onSetLibassRenderType: (com.nuvio.tv.data.local.LibassRenderType) -> Unit
) {
    var generalExpanded by rememberSaveable { mutableStateOf(false) }
    var afrExpanded by rememberSaveable { mutableStateOf(false) }
    var streamExpanded by rememberSaveable { mutableStateOf(false) }
    var audioTrailerExpanded by rememberSaveable { mutableStateOf(false) }
    var subtitlesExpanded by rememberSaveable { mutableStateOf(false) }

    val playbackAnimationScope = rememberCoroutineScope()
    var animatedFlattenBoundaries by remember {
        mutableStateOf<Set<Int>>(emptySet())
    }
    var deferredBottomCornerSections by remember {
        mutableStateOf<Set<PlaybackSection>>(emptySet())
    }

    val visibleSections = listOf(
        PlaybackSection.GENERAL,
        PlaybackSection.STREAM_SELECTION,
        PlaybackSection.AUDIO_TRAILER,
        PlaybackSection.SUBTITLES
    )

    val expandedSections = buildSet {
        if (generalExpanded) add(PlaybackSection.GENERAL)
        if (streamExpanded) add(PlaybackSection.STREAM_SELECTION)
        if (audioTrailerExpanded) add(PlaybackSection.AUDIO_TRAILER)
        if (subtitlesExpanded) add(PlaybackSection.SUBTITLES)
    }

    fun groupPositionFor(
        section: PlaybackSection,
        expanded: Set<PlaybackSection> = expandedSections
    ): SettingsGroupPosition {
        val index = visibleSections.indexOf(section)
        if (index < 0) return SettingsGroupPosition.SINGLE
        if (section in expanded) return SettingsGroupPosition.TOP

        val startsGroup =
            index == 0 || visibleSections[index - 1] in expanded
        val endsGroup =
            index == visibleSections.lastIndex ||
                visibleSections[index + 1] in expanded

        return when {
            startsGroup && endsGroup -> SettingsGroupPosition.SINGLE
            startsGroup -> SettingsGroupPosition.TOP
            endsGroup -> SettingsGroupPosition.BOTTOM
            else -> SettingsGroupPosition.MIDDLE
        }
    }

    fun headerTopRounded(
        section: PlaybackSection,
        expanded: Set<PlaybackSection>
    ): Boolean {
        return when (groupPositionFor(section, expanded)) {
            SettingsGroupPosition.SINGLE,
            SettingsGroupPosition.TOP -> true
            SettingsGroupPosition.MIDDLE,
            SettingsGroupPosition.BOTTOM -> false
        }
    }

    fun headerBottomRounded(
        section: PlaybackSection,
        expanded: Set<PlaybackSection>
    ): Boolean {
        return when (groupPositionFor(section, expanded)) {
            SettingsGroupPosition.SINGLE,
            SettingsGroupPosition.BOTTOM -> true
            SettingsGroupPosition.TOP,
            SettingsGroupPosition.MIDDLE -> false
        }
    }

    fun applySectionExpandedState(
        section: PlaybackSection,
        expanded: Boolean
    ) {
        when (section) {
            PlaybackSection.GENERAL ->
                generalExpanded = expanded
            PlaybackSection.STREAM_SELECTION ->
                streamExpanded = expanded
            PlaybackSection.AUDIO_TRAILER ->
                audioTrailerExpanded = expanded
            PlaybackSection.SUBTITLES ->
                subtitlesExpanded = expanded
        }
    }

    fun beginBoundaryReturnAnimation(
        boundaries: Set<Int>
    ) {
        animatedFlattenBoundaries = boundaries
        if (boundaries.isNotEmpty()) {
            playbackAnimationScope.launch {
                delay(260L)
                if (animatedFlattenBoundaries == boundaries) {
                    animatedFlattenBoundaries = emptySet()
                }
            }
        }
    }

    fun immediateFlattenBoundariesForCollapse(
        section: PlaybackSection,
        before: Set<PlaybackSection>,
        after: Set<PlaybackSection>
    ): Set<Int> {
        val sectionIndex = visibleSections.indexOf(section)
        if (sectionIndex < 0) return emptySet()

        return listOf(sectionIndex - 1, sectionIndex)
            .filter { boundaryIndex ->
                boundaryIndex >= 0 &&
                    boundaryIndex < visibleSections.lastIndex
            }
            .filter { boundaryIndex ->
                val upper = visibleSections[boundaryIndex]
                val lower = visibleSections[boundaryIndex + 1]

                val upperRoundedBefore =
                    headerBottomRounded(upper, before)
                val lowerRoundedBefore =
                    headerTopRounded(lower, before)
                val upperRoundedAfter =
                    headerBottomRounded(upper, after)
                val lowerRoundedAfter =
                    headerTopRounded(lower, after)

                val finalBoundaryIsFlat =
                    !upperRoundedAfter && !lowerRoundedAfter
                val roundedEdgeNeedsToFlatten =
                    (upperRoundedBefore && !upperRoundedAfter) ||
                        (lowerRoundedBefore && !lowerRoundedAfter)

                finalBoundaryIsFlat &&
                    roundedEdgeNeedsToFlatten
            }
            .toSet()
    }

    fun setSectionExpandedWithCornerPolicy(
        section: PlaybackSection,
        expanded: Boolean
    ) {
        val before = expandedSections
        val after =
            if (expanded) {
                before + section
            } else {
                before - section
            }

        if (expanded) {
            deferredBottomCornerSections =
                deferredBottomCornerSections - section
            animatedFlattenBoundaries = emptySet()
            applySectionExpandedState(section, true)
            return
        }

        beginBoundaryReturnAnimation(
            immediateFlattenBoundariesForCollapse(
                section = section,
                before = before,
                after = after
            )
        )

        deferredBottomCornerSections =
            deferredBottomCornerSections + section
        applySectionExpandedState(section, false)

        playbackAnimationScope.launch {
            delay(240L)
            if (section in deferredBottomCornerSections) {
                androidx.compose.runtime.withFrameNanos { }
                deferredBottomCornerSections =
                    deferredBottomCornerSections - section
            }
        }
    }

    fun animateTopFlattenFor(
        section: PlaybackSection
    ): Boolean {
        val index = visibleSections.indexOf(section)
        return index > 0 &&
            (index - 1) in animatedFlattenBoundaries
    }

    fun animateBottomFlattenFor(
        section: PlaybackSection
    ): Boolean {
        val index = visibleSections.indexOf(section)
        return index >= 0 &&
            index < visibleSections.lastIndex &&
            index in animatedFlattenBoundaries
    }

    val defaultGeneralHeaderFocus = remember { FocusRequester() }
    val afrHeaderFocus = remember { FocusRequester() }
    val streamHeaderFocus = remember { FocusRequester() }
    val audioTrailerHeaderFocus = remember { FocusRequester() }
    val subtitlesHeaderFocus = remember { FocusRequester() }
    val generalHeaderFocus = initialFocusRequester ?: defaultGeneralHeaderFocus

    var focusedSection by remember { mutableStateOf<PlaybackSection?>(null) }

    val strAfrOff = stringResource(R.string.playback_afr_off)
    val strAfrOnStart = stringResource(R.string.playback_afr_on_start)
    val strAfrOnStartStop = stringResource(R.string.playback_afr_on_start_stop)
    val strSectionGeneral = stringResource(R.string.playback_section_general)
    val strSectionGeneralDesc = stringResource(R.string.playback_section_general_desc)
    val strSectionPlayer = stringResource(R.string.playback_section_player)
    val strSectionPlayerDesc = stringResource(R.string.playback_section_player_desc)
    val strSectionAudio = stringResource(R.string.playback_section_audio)
    val strSectionAudioDesc = stringResource(R.string.playback_section_audio_desc)
    val strSectionSubtitles = stringResource(R.string.playback_section_subtitles)
    val strSectionSubtitlesDesc = stringResource(R.string.playback_section_subtitles_desc)

    val generalUi = PlaybackGeneralUi(
        isExternalPlayer =
            playerSettings.playerPreference == PlayerPreference.EXTERNAL,
        frameRateMatchingLabel = frameRateMatchingModeLabel(
            mode = playerSettings.frameRateMatchingMode,
            off = strAfrOff,
            onStart = strAfrOnStart,
            onStartStop = strAfrOnStartStop
        )
    )
    val streamSelectionUi = PlaybackStreamSelectionUi(
        playerPreferenceLabel = when (playerSettings.playerPreference) {
            PlayerPreference.INTERNAL ->
                stringResource(R.string.playback_player_internal)
            PlayerPreference.EXTERNAL ->
                stringResource(R.string.playback_player_external)
            PlayerPreference.ASK_EVERY_TIME ->
                stringResource(R.string.playback_player_ask)
        }
    )

    LaunchedEffect(generalExpanded, focusedSection) {
        if (
            !generalExpanded &&
            focusedSection == PlaybackSection.GENERAL
        ) {
            generalHeaderFocus.requestFocus()
        }
    }
    LaunchedEffect(streamExpanded, focusedSection) {
        if (
            !streamExpanded &&
            focusedSection == PlaybackSection.STREAM_SELECTION
        ) {
            streamHeaderFocus.requestFocus()
        }
    }
    LaunchedEffect(audioTrailerExpanded, focusedSection) {
        if (
            !audioTrailerExpanded &&
            focusedSection == PlaybackSection.AUDIO_TRAILER
        ) {
            audioTrailerHeaderFocus.requestFocus()
        }
    }
    LaunchedEffect(subtitlesExpanded, focusedSection) {
        if (
            !subtitlesExpanded &&
            focusedSection == PlaybackSection.SUBTITLES
        ) {
            subtitlesHeaderFocus.requestFocus()
        }
    }

    val playbackListState = rememberLazyListState()
    Box(modifier = Modifier.fillMaxWidth()) {
        LazyColumn(
            state = playbackListState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(0.dp),
            verticalArrangement =
                Arrangement.spacedBy(SettingsRowGap)
        ) {
            item(key = "general_group") {
                PlaybackExpandableGroup(
                    title = strSectionGeneral,
                    description = strSectionGeneralDesc,
                    expanded = generalExpanded,
                    onToggle = {
                        setSectionExpandedWithCornerPolicy(
                            PlaybackSection.GENERAL,
                            !generalExpanded
                        )
                    },
                    focusRequester = generalHeaderFocus,
                    groupPosition = groupPositionFor(
                        PlaybackSection.GENERAL
                    ),
                    animateTopFlatten = animateTopFlattenFor(
                        PlaybackSection.GENERAL
                    ),
                    animateBottomFlatten = animateBottomFlattenFor(
                        PlaybackSection.GENERAL
                    ),
                    deferBottomCorner =
                        PlaybackSection.GENERAL in
                            deferredBottomCornerSections,
                    onHeaderFocused = {
                        focusedSection = PlaybackSection.GENERAL
                    }
                ) {
                    ToggleSettingsItem(
                        icon = Icons.Default.Image,
                        title = stringResource(
                            R.string.playback_loading_overlay
                        ),
                        subtitle = stringResource(
                            R.string.playback_loading_overlay_sub
                        ),
                        isChecked = playerSettings.loadingOverlayEnabled,
                        onCheckedChange = onSetLoadingOverlayEnabled,
                        onFocused = {
                            focusedSection = PlaybackSection.GENERAL
                        },
                        enabled = !generalUi.isExternalPlayer
                    )

                    ToggleSettingsItem(
                        icon = Icons.Default.PauseCircle,
                        title = stringResource(
                            R.string.playback_pause_overlay
                        ),
                        subtitle = stringResource(
                            R.string.playback_pause_overlay_sub
                        ),
                        isChecked = playerSettings.pauseOverlayEnabled,
                        onCheckedChange = onSetPauseOverlayEnabled,
                        onFocused = {
                            focusedSection = PlaybackSection.GENERAL
                        },
                        enabled = !generalUi.isExternalPlayer
                    )

                    ToggleSettingsItem(
                        icon = Icons.Default.Timer,
                        title = stringResource(
                            R.string.playback_osd_clock
                        ),
                        subtitle = stringResource(
                            R.string.playback_show_clock_sub
                        ),
                        isChecked = playerSettings.osdClockEnabled,
                        onCheckedChange = onSetOsdClockEnabled,
                        onFocused = {
                            focusedSection = PlaybackSection.GENERAL
                        },
                        enabled = !generalUi.isExternalPlayer
                    )

                    ToggleSettingsItem(
                        icon = Icons.Default.History,
                        title = stringResource(
                            R.string.playback_skip_intro
                        ),
                        subtitle = stringResource(
                            R.string.playback_skip_intro_sub
                        ),
                        isChecked = playerSettings.skipIntroEnabled,
                        onCheckedChange = onSetSkipIntroEnabled,
                        onFocused = {
                            focusedSection = PlaybackSection.GENERAL
                        },
                        enabled = !generalUi.isExternalPlayer
                    )

                    PlaybackExpandableGroup(
                        title = stringResource(
                            R.string.playback_auto_frame_rate
                        ),
                        description = generalUi.frameRateMatchingLabel,
                        expanded = afrExpanded,
                        onToggle = {
                            afrExpanded = !afrExpanded
                        },
                        focusRequester = afrHeaderFocus,
                        onHeaderFocused = {
                            focusedSection = PlaybackSection.GENERAL
                        },
                        enabled = !generalUi.isExternalPlayer,
                        groupPosition = SettingsGroupPosition.BOTTOM
                    ) {
                        FrameRateMatchingModeOptions(
                            selectedMode =
                                playerSettings.frameRateMatchingMode,
                            resolutionMatchingEnabled =
                                playerSettings.resolutionMatchingEnabled,
                            onSelect = onSetFrameRateMatchingMode,
                            onSetResolutionMatchingEnabled =
                                onSetResolutionMatchingEnabled,
                            onFocused = {
                                focusedSection =
                                    PlaybackSection.GENERAL
                            },
                            enabled = !generalUi.isExternalPlayer
                        )
                    }
                }
            }

            item(key = "stream_selection_group") {
                PlaybackExpandableGroup(
                    title = strSectionPlayer,
                    description = strSectionPlayerDesc,
                    expanded = streamExpanded,
                    onToggle = {
                        setSectionExpandedWithCornerPolicy(
                            PlaybackSection.STREAM_SELECTION,
                            !streamExpanded
                        )
                    },
                    focusRequester = streamHeaderFocus,
                    groupPosition = groupPositionFor(
                        PlaybackSection.STREAM_SELECTION
                    ),
                    animateTopFlatten = animateTopFlattenFor(
                        PlaybackSection.STREAM_SELECTION
                    ),
                    animateBottomFlatten = animateBottomFlattenFor(
                        PlaybackSection.STREAM_SELECTION
                    ),
                    deferBottomCorner =
                        PlaybackSection.STREAM_SELECTION in
                            deferredBottomCornerSections,
                    onHeaderFocused = {
                        focusedSection =
                            PlaybackSection.STREAM_SELECTION
                    }
                ) {
                    NavigationSettingsItem(
                        icon = Icons.Default.PlayArrow,
                        title = stringResource(R.string.playback_player),
                        subtitle =
                            streamSelectionUi.playerPreferenceLabel,
                        onClick = onShowPlayerPreferenceDialog,
                        onFocused = {
                            focusedSection =
                                PlaybackSection.STREAM_SELECTION
                        }
                    )

                    autoPlaySettingsItems(
                        playerSettings = playerSettings,
                        onShowModeDialog =
                            onShowStreamAutoPlayModeDialog,
                        onShowSourceDialog =
                            onShowStreamAutoPlaySourceDialog,
                        onShowAddonSelectionDialog =
                            onShowStreamAutoPlayAddonSelectionDialog,
                        onShowPluginSelectionDialog =
                            onShowStreamAutoPlayPluginSelectionDialog,
                        onShowRegexDialog =
                            onShowStreamRegexDialog,
                        onShowNextEpisodeThresholdModeDialog =
                            onShowNextEpisodeThresholdModeDialog,
                        onShowReuseLastLinkCacheDialog =
                            onShowReuseLastLinkCacheDialog,
                        onSetStreamAutoPlayNextEpisodeEnabled =
                            onSetStreamAutoPlayNextEpisodeEnabled,
                        onSetStreamAutoPlayPreferBingeGroupForNextEpisode =
                            onSetStreamAutoPlayPreferBingeGroupForNextEpisode,
                        onSetNextEpisodeThresholdPercent =
                            onSetNextEpisodeThresholdPercent,
                        onSetNextEpisodeThresholdMinutesBeforeEnd =
                            onSetNextEpisodeThresholdMinutesBeforeEnd,
                        onSetStreamAutoPlayTimeoutSeconds =
                            onSetStreamAutoPlayTimeoutSeconds,
                        onSetReuseLastLinkEnabled =
                            onSetReuseLastLinkEnabled,
                        onItemFocused = {
                            focusedSection =
                                PlaybackSection.STREAM_SELECTION
                        }
                    )
                }
            }

            item(key = "audio_trailer_group") {
                PlaybackExpandableGroup(
                    title = strSectionAudio,
                    description = strSectionAudioDesc,
                    expanded = audioTrailerExpanded,
                    onToggle = {
                        setSectionExpandedWithCornerPolicy(
                            PlaybackSection.AUDIO_TRAILER,
                            !audioTrailerExpanded
                        )
                    },
                    focusRequester = audioTrailerHeaderFocus,
                    groupPosition = groupPositionFor(
                        PlaybackSection.AUDIO_TRAILER
                    ),
                    animateTopFlatten = animateTopFlattenFor(
                        PlaybackSection.AUDIO_TRAILER
                    ),
                    animateBottomFlatten = animateBottomFlattenFor(
                        PlaybackSection.AUDIO_TRAILER
                    ),
                    deferBottomCorner =
                        PlaybackSection.AUDIO_TRAILER in
                            deferredBottomCornerSections,
                    onHeaderFocused = {
                        focusedSection =
                            PlaybackSection.AUDIO_TRAILER
                    }
                ) {
                    trailerAndAudioSettingsItems(
                        playerSettings = playerSettings,
                        trailerSettings = trailerSettings,
                        onShowAudioLanguageDialog =
                            onShowAudioLanguageDialog,
                        onShowSecondaryAudioLanguageDialog =
                            onShowSecondaryAudioLanguageDialog,
                        onShowDecoderPriorityDialog =
                            onShowDecoderPriorityDialog,
                        onSetTrailerEnabled = onSetTrailerEnabled,
                        onSetTrailerDelaySeconds =
                            onSetTrailerDelaySeconds,
                        onSetSkipSilence = onSetSkipSilence,
                        onSetTunnelingEnabled =
                            onSetTunnelingEnabled,
                        onSetMapDV7ToHevc = onSetMapDV7ToHevc,
                        onItemFocused = {
                            focusedSection =
                                PlaybackSection.AUDIO_TRAILER
                        },
                        enabled = !generalUi.isExternalPlayer
                    )
                }
            }

            item(key = "subtitles_group") {
                PlaybackExpandableGroup(
                    title = strSectionSubtitles,
                    description = strSectionSubtitlesDesc,
                    expanded = subtitlesExpanded,
                    onToggle = {
                        setSectionExpandedWithCornerPolicy(
                            PlaybackSection.SUBTITLES,
                            !subtitlesExpanded
                        )
                    },
                    focusRequester = subtitlesHeaderFocus,
                    groupPosition = groupPositionFor(
                        PlaybackSection.SUBTITLES
                    ),
                    animateTopFlatten = animateTopFlattenFor(
                        PlaybackSection.SUBTITLES
                    ),
                    animateBottomFlatten = animateBottomFlattenFor(
                        PlaybackSection.SUBTITLES
                    ),
                    deferBottomCorner =
                        PlaybackSection.SUBTITLES in
                            deferredBottomCornerSections,
                    onHeaderFocused = {
                        focusedSection =
                            PlaybackSection.SUBTITLES
                    }
                ) {
                    subtitleSettingsItems(
                        playerSettings = playerSettings,
                        onShowLanguageDialog =
                            onShowLanguageDialog,
                        onShowSecondaryLanguageDialog =
                            onShowSecondaryLanguageDialog,
                        onShowSubtitleStartupModeDialog =
                            onShowSubtitleStartupModeDialog,
                        onShowTextColorDialog =
                            onShowTextColorDialog,
                        onShowBackgroundColorDialog =
                            onShowBackgroundColorDialog,
                        onShowOutlineColorDialog =
                            onShowOutlineColorDialog,
                        onSetSubtitleSize = onSetSubtitleSize,
                        onSetSubtitleVerticalOffset =
                            onSetSubtitleVerticalOffset,
                        onSetSubtitleBold = onSetSubtitleBold,
                        onSetSubtitleOutlineEnabled =
                            onSetSubtitleOutlineEnabled,
                        onSetUseLibass = onSetUseLibass,
                        onSetLibassRenderType =
                            onSetLibassRenderType,
                        onItemFocused = {
                            focusedSection =
                                PlaybackSection.SUBTITLES
                        },
                        enabled = !generalUi.isExternalPlayer
                    )
                }
            }
        }

        SettingsVerticalScrollIndicators(state = playbackListState)
    }
}

@Composable
private fun PlaybackExpandableGroup(
    title: String,
    description: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    focusRequester: FocusRequester,
    onHeaderFocused: () -> Unit,
    enabled: Boolean = true,
    groupPosition: SettingsGroupPosition =
        SettingsGroupPosition.SINGLE,
    animateTopFlatten: Boolean = false,
    animateBottomFlatten: Boolean = false,
    deferBottomCorner: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    var previousExpanded by remember {
        mutableStateOf(expanded)
    }
    val opening = expanded && !previousExpanded

    LaunchedEffect(expanded) {
        previousExpanded = expanded
    }

    val effectiveHeaderPosition = when {
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
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(
            if (expanded || deferBottomCorner) {
                SettingsRowGap
            } else {
                0.dp
            }
        )
    ) {
        PlaybackSectionHeader(
            title = title,
            description = description,
            expanded = expanded,
            onToggle = onToggle,
            focusRequester = focusRequester,
            onFocused = onHeaderFocused,
            enabled = enabled,
            groupPosition = effectiveHeaderPosition,
            animateTopFlatten = animateTopFlatten,
            animateBottomFlatten =
                animateBottomFlatten || opening
        )

        androidx.compose.animation.AnimatedVisibility(
            visible = expanded,
            enter = androidx.compose.animation.expandVertically(
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = 240,
                    easing =
                        androidx.compose.animation.core.FastOutSlowInEasing
                ),
                expandFrom = Alignment.Top
            ),
            exit = androidx.compose.animation.shrinkVertically(
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = 240,
                    easing =
                        androidx.compose.animation.core.FastOutSlowInEasing
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
private fun PlaybackSectionHeader(
    title: String,
    description: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    enabled: Boolean = true,
    groupPosition: SettingsGroupPosition =
        SettingsGroupPosition.SINGLE,
    animateTopFlatten: Boolean = false,
    animateBottomFlatten: Boolean = false
) {
    SettingsActionRow(
        title = title,
        subtitle = description,
        value =
            if (expanded) {
                stringResource(R.string.playback_afr_open)
            } else {
                stringResource(R.string.playback_afr_closed)
            },
        onClick = onToggle,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        onFocused = onFocused,
        enabled = enabled,
        trailingIcon =
            if (expanded) {
                Icons.Default.ExpandMore
            } else {
                Icons.Default.ChevronRight
            },
        showDivider = false,
        groupPosition = groupPosition,
        animateTopFlatten = animateTopFlatten,
        animateBottomFlatten = animateBottomFlatten
    )
}

@Composable
private fun FrameRateMatchingModeOptions(
    selectedMode: FrameRateMatchingMode,
    resolutionMatchingEnabled: Boolean,
    onSelect: (FrameRateMatchingMode) -> Unit,
    onSetResolutionMatchingEnabled: (Boolean) -> Unit,
    onFocused: () -> Unit,
    enabled: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SettingsRowGap)
    ) {
        RenderTypeSettingsItem(
            title = stringResource(R.string.playback_afr_off),
            subtitle = stringResource(R.string.playback_afr_off_sub),
            isSelected = selectedMode == FrameRateMatchingMode.OFF,
            onClick = { onSelect(FrameRateMatchingMode.OFF) },
            onFocused = onFocused,
            enabled = enabled
        )


        RenderTypeSettingsItem(
            title = stringResource(R.string.playback_afr_on_start),
            subtitle = stringResource(R.string.playback_afr_on_start_sub),
            isSelected = selectedMode == FrameRateMatchingMode.START,
            onClick = { onSelect(FrameRateMatchingMode.START) },
            onFocused = onFocused,
            enabled = enabled
        )


        RenderTypeSettingsItem(
            title = stringResource(R.string.playback_afr_on_start_stop),
            subtitle = stringResource(R.string.playback_afr_on_start_stop_sub),
            isSelected = selectedMode == FrameRateMatchingMode.START_STOP,
            onClick = { onSelect(FrameRateMatchingMode.START_STOP) },
            onFocused = onFocused,
            enabled = enabled
        )


        ToggleSettingsItem(
            icon = Icons.Default.Image,
            title = stringResource(R.string.playback_resolution_matching),
            subtitle = stringResource(R.string.playback_resolution_matching_sub),
            isChecked = resolutionMatchingEnabled,
            onCheckedChange = onSetResolutionMatchingEnabled,
            onFocused = onFocused,
            enabled = enabled
        )
    }
}

@Composable
internal fun PlaybackSettingsDialogsHost(
    playerSettings: PlayerSettings,
    installedAddonNames: List<String>,
    enabledPluginNames: List<String>,
    showPlayerPreferenceDialog: Boolean,
    showLanguageDialog: Boolean,
    showSecondaryLanguageDialog: Boolean,
    showSubtitleStartupModeDialog: Boolean,
    showTextColorDialog: Boolean,
    showBackgroundColorDialog: Boolean,
    showOutlineColorDialog: Boolean,
    showAudioLanguageDialog: Boolean,
    showSecondaryAudioLanguageDialog: Boolean,
    showDecoderPriorityDialog: Boolean,
    showStreamAutoPlayModeDialog: Boolean,
    showStreamAutoPlaySourceDialog: Boolean,
    showStreamAutoPlayAddonSelectionDialog: Boolean,
    showStreamAutoPlayPluginSelectionDialog: Boolean,
    showStreamRegexDialog: Boolean,
    showNextEpisodeThresholdModeDialog: Boolean,
    showReuseLastLinkCacheDialog: Boolean,
    onSetPlayerPreference: (PlayerPreference) -> Unit,
    onDismissPlayerPreferenceDialog: () -> Unit,
    onSetSubtitlePreferredLanguage: (String?) -> Unit,
    onSetSubtitleSecondaryLanguage: (String?) -> Unit,
    onSetAddonSubtitleStartupMode: (AddonSubtitleStartupMode) -> Unit,
    onSetSubtitleTextColor: (Color) -> Unit,
    onSetSubtitleBackgroundColor: (Color) -> Unit,
    onSetSubtitleOutlineColor: (Color) -> Unit,
    onSetPreferredAudioLanguage: (String) -> Unit,
    onSetSecondaryPreferredAudioLanguage: (String?) -> Unit,
    onSetDecoderPriority: (Int) -> Unit,
    onSetStreamAutoPlayMode: (com.nuvio.tv.data.local.StreamAutoPlayMode) -> Unit,
    onSetStreamAutoPlaySource: (com.nuvio.tv.data.local.StreamAutoPlaySource) -> Unit,
    onSetNextEpisodeThresholdMode: (com.nuvio.tv.data.local.NextEpisodeThresholdMode) -> Unit,
    onSetStreamAutoPlayRegex: (String) -> Unit,
    onSetStreamAutoPlaySelectedAddons: (Set<String>) -> Unit,
    onSetStreamAutoPlaySelectedPlugins: (Set<String>) -> Unit,
    onSetReuseLastLinkCacheHours: (Int) -> Unit,
    onDismissLanguageDialog: () -> Unit,
    onDismissSecondaryLanguageDialog: () -> Unit,
    onDismissSubtitleStartupModeDialog: () -> Unit,
    onDismissTextColorDialog: () -> Unit,
    onDismissBackgroundColorDialog: () -> Unit,
    onDismissOutlineColorDialog: () -> Unit,
    onDismissAudioLanguageDialog: () -> Unit,
    onDismissSecondaryAudioLanguageDialog: () -> Unit,
    onDismissDecoderPriorityDialog: () -> Unit,
    onDismissStreamAutoPlayModeDialog: () -> Unit,
    onDismissStreamAutoPlaySourceDialog: () -> Unit,
    onDismissStreamRegexDialog: () -> Unit,
    onDismissStreamAutoPlayAddonSelectionDialog: () -> Unit,
    onDismissStreamAutoPlayPluginSelectionDialog: () -> Unit,
    onDismissNextEpisodeThresholdModeDialog: () -> Unit,
    onDismissReuseLastLinkCacheDialog: () -> Unit
) {
    if (showPlayerPreferenceDialog) {
        PlayerPreferenceDialog(
            currentPreference = playerSettings.playerPreference,
            onPreferenceSelected = { preference ->
                onSetPlayerPreference(preference)
                onDismissPlayerPreferenceDialog()
            },
            onDismiss = onDismissPlayerPreferenceDialog
        )
    }

    SubtitleSettingsDialogs(
        showLanguageDialog = showLanguageDialog,
        showSecondaryLanguageDialog = showSecondaryLanguageDialog,
        showSubtitleStartupModeDialog = showSubtitleStartupModeDialog,
        showTextColorDialog = showTextColorDialog,
        showBackgroundColorDialog = showBackgroundColorDialog,
        showOutlineColorDialog = showOutlineColorDialog,
        playerSettings = playerSettings,
        onSetPreferredLanguage = onSetSubtitlePreferredLanguage,
        onSetSecondaryLanguage = onSetSubtitleSecondaryLanguage,
        onSetAddonSubtitleStartupMode = onSetAddonSubtitleStartupMode,
        onSetTextColor = onSetSubtitleTextColor,
        onSetBackgroundColor = onSetSubtitleBackgroundColor,
        onSetOutlineColor = onSetSubtitleOutlineColor,
        onDismissLanguageDialog = onDismissLanguageDialog,
        onDismissSecondaryLanguageDialog = onDismissSecondaryLanguageDialog,
        onDismissSubtitleStartupModeDialog = onDismissSubtitleStartupModeDialog,
        onDismissTextColorDialog = onDismissTextColorDialog,
        onDismissBackgroundColorDialog = onDismissBackgroundColorDialog,
        onDismissOutlineColorDialog = onDismissOutlineColorDialog
    )

    AudioSettingsDialogs(
        showAudioLanguageDialog = showAudioLanguageDialog,
        showSecondaryAudioLanguageDialog = showSecondaryAudioLanguageDialog,
        showDecoderPriorityDialog = showDecoderPriorityDialog,
        selectedLanguage = playerSettings.preferredAudioLanguage,
        selectedSecondaryLanguage = playerSettings.secondaryPreferredAudioLanguage,
        selectedPriority = playerSettings.decoderPriority,
        onSetPreferredAudioLanguage = onSetPreferredAudioLanguage,
        onSetSecondaryPreferredAudioLanguage = onSetSecondaryPreferredAudioLanguage,
        onSetDecoderPriority = onSetDecoderPriority,
        onDismissAudioLanguageDialog = onDismissAudioLanguageDialog,
        onDismissSecondaryAudioLanguageDialog = onDismissSecondaryAudioLanguageDialog,
        onDismissDecoderPriorityDialog = onDismissDecoderPriorityDialog
    )

    AutoPlaySettingsDialogs(
        showModeDialog = showStreamAutoPlayModeDialog,
        showSourceDialog = showStreamAutoPlaySourceDialog,
        showRegexDialog = showStreamRegexDialog,
        showAddonSelectionDialog = showStreamAutoPlayAddonSelectionDialog,
        showPluginSelectionDialog = showStreamAutoPlayPluginSelectionDialog,
        showNextEpisodeThresholdModeDialog = showNextEpisodeThresholdModeDialog,
        showReuseLastLinkCacheDialog = showReuseLastLinkCacheDialog,
        playerSettings = playerSettings,
        installedAddonNames = installedAddonNames,
        enabledPluginNames = enabledPluginNames,
        onSetMode = onSetStreamAutoPlayMode,
        onSetSource = onSetStreamAutoPlaySource,
        onSetNextEpisodeThresholdMode = onSetNextEpisodeThresholdMode,
        onSetRegex = onSetStreamAutoPlayRegex,
        onSetSelectedAddons = onSetStreamAutoPlaySelectedAddons,
        onSetSelectedPlugins = onSetStreamAutoPlaySelectedPlugins,
        onSetReuseLastLinkCacheHours = onSetReuseLastLinkCacheHours,
        onDismissModeDialog = onDismissStreamAutoPlayModeDialog,
        onDismissSourceDialog = onDismissStreamAutoPlaySourceDialog,
        onDismissRegexDialog = onDismissStreamRegexDialog,
        onDismissAddonSelectionDialog = onDismissStreamAutoPlayAddonSelectionDialog,
        onDismissPluginSelectionDialog = onDismissStreamAutoPlayPluginSelectionDialog,
        onDismissNextEpisodeThresholdModeDialog = onDismissNextEpisodeThresholdModeDialog,
        onDismissReuseLastLinkCacheDialog = onDismissReuseLastLinkCacheDialog
    )
}

@Composable
private fun PlayerPreferenceDialog(
    currentPreference: PlayerPreference,
    onPreferenceSelected: (PlayerPreference) -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val options = listOf(
        Triple(PlayerPreference.INTERNAL, stringResource(R.string.playback_player_internal), "Use NuvioTV's built-in player"),
        Triple(PlayerPreference.EXTERNAL, stringResource(R.string.playback_player_external), stringResource(R.string.playback_player_external_desc)),
        Triple(PlayerPreference.ASK_EVERY_TIME, stringResource(R.string.playback_player_ask), stringResource(R.string.playback_player_ask_desc))
    )

    NuvioDialog(
        glass = true,
        enhancedGlass = true,
        onDismiss = onDismiss,
        title = stringResource(R.string.playback_player),
        width = 420.dp,
        suppressFirstKeyUp = false
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
        ) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(
                    count = options.size,
                    key = { index -> options[index].first.name }
                ) { index ->
                    val (preference, title, description) = options[index]
                    val isSelected = preference == currentPreference

                    Card(
                        onClick = { onPreferenceSelected(preference) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (index == 0) Modifier.focusRequester(focusRequester) else Modifier),
                        colors = CardDefaults.colors(
                            containerColor = if (isSelected) SettingsGlassControlSelectedColor else SettingsGlassRowColor,
                            focusedContainerColor = SettingsGlassRowFocusedColor
                        ),
                        shape = CardDefaults.shape(shape = RoundedCornerShape(10.dp)),
                        scale = CardDefaults.scale(focusedScale = 1f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = title,
                                    color = if (isSelected) NuvioColors.Primary else NuvioColors.TextPrimary,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = description,
                                    color = NuvioColors.TextSecondary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (isSelected) {
                                Spacer(modifier = Modifier.width(12.dp))
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(R.string.cd_selected),
                                    tint = NuvioColors.Primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
