package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.debrid.DebridEpisodeSelectionContext
import com.nuvio.tv.core.debrid.DebridEpisodeTitleMatch
import com.nuvio.tv.core.debrid.debridEpisodeTitleMatch
import com.nuvio.tv.core.debrid.orderByDebridEpisodeTitleEvidence

import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.debrid.DirectDebridPlayableResult
import com.nuvio.tv.core.debrid.DirectDebridStreamFilter
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.ui.components.SourceChipItem
import com.nuvio.tv.ui.components.SourceChipStatus
import com.nuvio.tv.core.debrid.DebridProviders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class StreamAddonFilterResult(
    val selectedAddon: String?,
    val streams: List<Stream>
)

internal fun resolveStreamAddonFilter(
    allStreams: List<Stream>,
    availableAddons: List<String>,
    selectedAddon: String?
): StreamAddonFilterResult {
    val availableSelection = selectedAddon?.takeIf { it in availableAddons }
    return StreamAddonFilterResult(
        selectedAddon = availableSelection,
        streams = if (availableSelection == null) {
            allStreams
        } else {
            allStreams.filter { it.addonName == availableSelection }
        }
    )
}


internal fun PlayerRuntimeController.showEpisodesPanel() {
    _uiState.update {
        it.copy(
            showEpisodesPanel = true,
            showControls = true,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSpeedDialog = false,
            showMoreDialog = false
        )
    }

    
    val desiredSeason = currentSeason ?: _uiState.value.episodesSelectedSeason
    if (_uiState.value.episodesAll.isNotEmpty() && desiredSeason != null) {
        selectEpisodesSeason(desiredSeason)
    } else {
        loadEpisodesIfNeeded()
    }
}

internal fun PlayerRuntimeController.showSourcesPanel() {
    _uiState.update {
        it.copy(
            showSourcesPanel = true,
            showControls = true,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSpeedDialog = false,
            showMoreDialog = false,
            showEpisodesPanel = false,
            showEpisodeStreams = false,
            // Opening the cloud/source panel always starts on All. Apply this
            // in state rather than relying on a one-shot composable effect so
            // cached panels and visibility transitions behave identically.
            sourceSelectedAddonFilter = null,
            sourceFilteredStreams = it.sourceAllStreams
        )
    }
    loadSourceStreams(forceRefresh = false)
}

internal fun PlayerRuntimeController.buildSourceRequestKey(type: String, videoId: String, season: Int?, episode: Int?): String {
    return "$type|$videoId|${season ?: -1}|${episode ?: -1}"
}

internal fun PlayerRuntimeController.loadSourceStreams(forceRefresh: Boolean) {
    val type: String
    val vid: String
    val seasonArg: Int?
    val episodeArg: Int?

    if (contentType in listOf("series", "tv") && currentSeason != null && currentEpisode != null) {
        type = contentType ?: return
        vid = currentVideoId ?: contentId ?: return
        seasonArg = currentSeason
        episodeArg = currentEpisode
    } else {
        type = contentType ?: "movie"
        vid = contentId ?: return
        seasonArg = null
        episodeArg = null
    }

    val requestKey = buildSourceRequestKey(type = type, videoId = vid, season = seasonArg, episode = episodeArg)
    val state = _uiState.value
    val hasCachedPayload = state.sourceAllStreams.isNotEmpty() || state.sourceStreamsError != null
    if (!forceRefresh && requestKey == sourceStreamsCacheRequestKey && hasCachedPayload) {
        return
    }
    if (!forceRefresh && state.isLoadingSourceStreams && requestKey == sourceStreamsCacheRequestKey) {
        return
    }

    val targetChanged = requestKey != sourceStreamsCacheRequestKey
    sourceStreamsJob?.cancel()
    sourceChipErrorDismissJob?.cancel()
    sourceStreamsJob = scope.launch {
        sourceStreamsCacheRequestKey = requestKey
        _uiState.update {
            it.copy(
                isLoadingSourceStreams = true,
                sourceStreamsError = null,
                sourceAllStreams = if (forceRefresh || targetChanged) emptyList() else it.sourceAllStreams,
                sourceSelectedAddonFilter = if (forceRefresh || targetChanged) null else it.sourceSelectedAddonFilter,
                sourceFilteredStreams = if (forceRefresh || targetChanged) emptyList() else it.sourceFilteredStreams,
                sourceAvailableAddons = if (forceRefresh || targetChanged) emptyList() else it.sourceAvailableAddons,
                sourceChips = if (forceRefresh || targetChanged) emptyList() else it.sourceChips
            )
        }

        val installedAddons = addonRepository.getInstalledAddons().first()
        val installedAddonOrder = installedAddons.map { it.displayName }
        val debridSettings = debridSettingsDataStore.settings.first()
        updateSourceChipsForFetchStart(type, installedAddons)

        streamRepository.getStreamsFromAllAddons(
            type = type,
            videoId = vid,
            season = seasonArg,
            episode = episodeArg
        ).collect { result ->
            when (result) {
                is NetworkResult.Success -> {
                    val addonStreams = StreamAutoPlaySelector.orderAddonStreams(result.data, installedAddonOrder)
                    val episodeSelectionContext = currentDebridEpisodeSelectionContext()
                    val allStreams = withContext(Dispatchers.Default) {
                        DirectDebridStreamFilter.sortForSourceList(
                            streams = addonStreams.flatMap { it.streams },
                            settings = debridSettings
                        ).orderByDebridEpisodeTitleEvidence(episodeSelectionContext)
                    }
                    android.util.Log.d("PlayerRecovery", "Stream preload complete: ${allStreams.size} streams available for fallback")
                    val availableAddons = addonStreams.map { it.addonName }
                    _uiState.update {
                        val updatedChips = mergeSourceChipStatuses(
                            existing = it.sourceChips,
                            succeededNames = availableAddons
                        )
                        val filter = resolveStreamAddonFilter(
                            allStreams = allStreams,
                            // A loading bubble is a valid active destination.
                            // Keep its selection while other addons emit; its
                            // filtered list will populate when its result arrives.
                            availableAddons = updatedChips
                                .filter { chip -> chip.status != SourceChipStatus.ERROR }
                                .map { chip -> chip.name },
                            selectedAddon = it.sourceSelectedAddonFilter
                        )
                        it.copy(
                            isLoadingSourceStreams = false,
                            sourceAllStreams = allStreams,
                            sourceSelectedAddonFilter = filter.selectedAddon,
                            sourceFilteredStreams = filter.streams,
                            sourceAvailableAddons = availableAddons,
                            sourceChips = updatedChips,
                            sourceStreamsError = null
                        )
                    }
                }

                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoadingSourceStreams = false,
                            sourceStreamsError = result.message
                        )
                    }
                }

                NetworkResult.Loading -> {
                    _uiState.update { it.copy(isLoadingSourceStreams = true) }
                }
            }
        }
        markRemainingSourceChipsAsError()
    }
}

internal fun PlayerRuntimeController.dismissSourcesPanel() {
    _uiState.update {
        it.copy(
            showSourcesPanel = false,
            isLoadingSourceStreams = false
        )
    }
    sourceChipErrorDismissJob?.cancel()
    scheduleHideControls()
}

internal fun PlayerRuntimeController.filterSourceStreamsByAddon(addonName: String?) {
    val allStreams = _uiState.value.sourceAllStreams
    val filteredStreams = if (addonName == null) {
        allStreams
    } else {
        allStreams.filter { it.addonName == addonName }
    }
    _uiState.update {
        it.copy(
            sourceSelectedAddonFilter = addonName,
            sourceFilteredStreams = filteredStreams
        )
    }
}

private suspend fun PlayerRuntimeController.updateSourceChipsForFetchStart(
    type: String,
    installedAddons: List<com.nuvio.tv.domain.model.Addon>
) {
    val addonNames = installedAddons
        .filter { it.supportsStreamResourceForChip(type) }
        .map { it.displayName }

    val pluginNames = try {
        if (pluginManager.pluginsEnabled.first()) {
            pluginManager.enabledScrapers.first()
                .filter { it.supportsType(type) }
                .map { it.name }
                .distinct()
        } else {
            emptyList()
        }
    } catch (_: Exception) {
        emptyList()
    }

    val ordered = (addonNames + pluginNames).distinct()
    _uiState.update {
        it.copy(
            sourceChips = ordered.map { name -> SourceChipItem(name, SourceChipStatus.LOADING) }
        )
    }
}

private fun PlayerRuntimeController.mergeSourceChipStatuses(
    existing: List<SourceChipItem>,
    succeededNames: List<String>
): List<SourceChipItem> {
    if (succeededNames.isEmpty()) return existing
    if (existing.isEmpty()) {
        return succeededNames.distinct().map { SourceChipItem(it, SourceChipStatus.SUCCESS) }
    }

    val successSet = succeededNames.toSet()
    val updated = existing.map { chip ->
        if (chip.name in successSet) chip.copy(status = SourceChipStatus.SUCCESS) else chip
    }.toMutableList()

    val known = updated.map { it.name }.toSet()
    succeededNames.forEach { name ->
        if (name !in known) updated += SourceChipItem(name, SourceChipStatus.SUCCESS)
    }
    return updated
}

private fun PlayerRuntimeController.markRemainingSourceChipsAsError() {
    var markedAnyError = false
    _uiState.update { state ->
        if (!state.sourceChips.any { it.status == SourceChipStatus.LOADING }) return@update state
        markedAnyError = true
        val failedNames = state.sourceChips
            .filter { it.status == SourceChipStatus.LOADING }
            .mapTo(mutableSetOf()) { it.name }
        val selectedAddonFailed = state.sourceSelectedAddonFilter in failedNames
        state.copy(
            sourceChips = state.sourceChips.map { chip ->
                if (chip.status == SourceChipStatus.LOADING) {
                    chip.copy(status = SourceChipStatus.ERROR)
                } else {
                    chip
                }
            },
            sourceSelectedAddonFilter = if (selectedAddonFailed) null else state.sourceSelectedAddonFilter,
            sourceFilteredStreams = if (selectedAddonFailed) state.sourceAllStreams else state.sourceFilteredStreams
        )
    }
    if (!markedAnyError) return

    sourceChipErrorDismissJob?.cancel()
    sourceChipErrorDismissJob = scope.launch {
        delay(1600L)
        _uiState.update { state ->
            state.copy(
                sourceChips = state.sourceChips.filterNot { it.status == SourceChipStatus.ERROR }
            )
        }
    }
}

private fun com.nuvio.tv.domain.model.Addon.supportsStreamResourceForChip(type: String): Boolean {
    return resources.any { resource ->
        resource.name == "stream" &&
            (resource.types.isEmpty() || resource.types.any { it.equals(type, ignoreCase = true) })
    }
}

private fun PlayerRuntimeController.applySelectedStreamState(
    stream: Stream,
    url: String,
    headers: Map<String, String>
) {
    currentStreamUrl = url
    currentHeaders = headers
    currentFilename = stream.behaviorHints?.filename
        ?: stream.clientResolve?.filename
        ?: stream.clientResolve?.stream?.raw?.filename
        ?: navigationArgs.filename
    currentStreamMimeType = PlayerMediaSourceFactory.inferMimeType(
        url = url,
        filename = currentFilename
    )
    currentStreamBingeGroup = stream.behaviorHints?.bingeGroup
    currentVideoHash = stream.behaviorHints?.videoHash
    currentVideoSize = stream.behaviorHints?.videoSize
        ?: stream.clientResolve?.stream?.raw?.size
        ?: stream.debridCacheStatus?.cachedSize
    currentInfoHash = stream.infoHash ?: stream.clientResolve?.infoHash
    currentFileIdx = stream.fileIdx ?: stream.clientResolve?.fileIdx
    currentAddonName = stream.addonName
    currentAddonLogo = stream.addonLogo
    currentAddonBaseUrl = stream.addonBaseUrl
    currentStreamDescription = stream.description
    currentVideoCodec = null
    currentVideoWidth = null
    currentVideoHeight = null
    currentVideoBitrate = null
    resetCreditTimingForNewPlayback()
}

private fun PlayerRuntimeController.persistSelectedStreamForReuse(
    stream: Stream,
    url: String,
    headers: Map<String, String>
) {
    if (!streamReuseLastLinkEnabled) return

    val key = streamCacheKey ?: return
    val streamName = (stream.name?.takeIf { it.isNotBlank() } ?: stream.addonName)?.takeIf { it.isNotBlank() }
        ?: title

    scope.launch {
        streamLinkCacheDataStore.save(
            contentKey = key,
            url = url,
            streamName = streamName,
            headers = headers,
            filename = currentFilename,
            videoHash = currentVideoHash,
            videoSize = currentVideoSize,
            addonName = currentAddonName,
            addonBaseUrl = currentAddonBaseUrl
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.switchToSourceStream(
    stream: Stream,
    sourceStreamKey: String = stream.stableKey()
) {
    val episodeSelectionContext = currentDebridEpisodeSelectionContext()
    if (
        stream.debridEpisodeTitleMatch(episodeSelectionContext) ==
        DebridEpisodeTitleMatch.CONFLICT
    ) {
        _uiState.update {
            it.copy(sourceStreamsError = "Selected stream appears to be for a different episode")
        }
        return
    }

    val url = stream.getStreamUrl()
    if (url.isNullOrBlank()) {
        scope.launch(Dispatchers.Default) {
            val resolvedStream = resolveDirectDebridStreamIfNeeded(
                stream = stream,
                season = currentSeason,
                episode = currentEpisode,
                selectionContext = currentDebridEpisodeSelectionContext()
            )
            withContext(Dispatchers.Main) {
                if (resolvedStream == null || resolvedStream.getStreamUrl().isNullOrBlank()) {
                    _uiState.update { it.copy(sourceStreamsError = "Invalid stream URL") }
                } else {
                    switchToSourceStream(
                        stream = resolvedStream,
                        sourceStreamKey = sourceStreamKey
                    )
                }
            }
        }
        return
    }
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null

    flushPlaybackSnapshotForSwitchOrExit()

    val newHeaders = PlayerMediaSourceFactory.sanitizeHeaders(
        stream.behaviorHints?.proxyHeaders?.request
    )
    
    resetLoadingOverlayForNewStream()
    releasePlayer(flushPlaybackState = false)

    applySelectedStreamState(
        stream = stream,
        url = url,
        headers = newHeaders
    )
    persistSelectedStreamForReuse(stream = stream, url = url, headers = newHeaders)
    hasRetriedCurrentStreamAfter416 = false
    lastSavedPosition = 0L

    _uiState.update {
        it.copy(
            isBuffering = true,
            error = null,
            currentStreamName = stream.name ?: stream.addonName,
            currentStreamUrl = url,
            currentSourceStreamKey = sourceStreamKey,
            currentStreamInfoHash = currentInfoHash,
            currentStreamFileIdx = currentFileIdx,
            currentStreamAddonName = currentAddonName,
            currentStreamDescription = currentStreamDescription,
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            selectedAudioTrackIndex = -1,
            selectedSubtitleTrackIndex = -1,
            showSourcesPanel = false,
            isLoadingSourceStreams = false,
            sourceStreamsError = null
        )
    }
    showStreamSourceIndicator(stream)
    resetNextEpisodeCardState(clearEpisode = false)

    preparePlaybackBeforeStart(
        url = url,
        headers = newHeaders,
        loadSavedProgress = true
    )
}

internal fun PlayerRuntimeController.dismissEpisodesPanel() {
    _uiState.update {
        it.copy(
            showEpisodesPanel = false,
            showEpisodeStreams = false,
            isLoadingEpisodeStreams = false
        )
    }
    scheduleHideControls()
}

internal fun PlayerRuntimeController.selectEpisodesSeason(season: Int) {
    val all = _uiState.value.episodesAll
    if (all.isEmpty()) return

    val seasons = _uiState.value.episodesAvailableSeasons
    if (seasons.isNotEmpty() && season !in seasons) return

    val episodesForSeason = all
        .filter { (it.season ?: -1) == season }
        .sortedWith(compareBy<Video> { it.episode ?: Int.MAX_VALUE }.thenBy { it.title })

    _uiState.update {
        it.copy(
            episodesSelectedSeason = season,
            episodes = episodesForSeason
        )
    }
}

internal fun PlayerRuntimeController.applyEpisodesFromMetaVideos(
    allEpisodes: List<Video>
) {
    val seasons =
        allEpisodes
            .mapNotNull { it.season }
            .distinct()
            .sorted()

    val preferredSeason =
        when {
            currentSeason != null &&
                seasons.contains(
                    currentSeason
                ) ->
                currentSeason

            initialSeason != null &&
                seasons.contains(
                    initialSeason
                ) ->
                initialSeason

            else ->
                seasons
                    .firstOrNull {
                        it > 0
                    }
                    ?: seasons
                        .firstOrNull()
                    ?: 1
        }

    val selectedSeason =
        preferredSeason ?: 1

    val episodesForSeason =
        allEpisodes
            .filter {
                (it.season ?: -1) ==
                    selectedSeason
            }
            .sortedWith(
                compareBy<Video> {
                    it.episode
                        ?: Int.MAX_VALUE
                }.thenBy {
                    it.title
                }
            )

    _uiState.update {
        it.copy(
            isLoadingEpisodes = false,
            episodesAll = allEpisodes,
            episodesAvailableSeasons =
                seasons,
            episodesSelectedSeason =
                selectedSeason,
            episodes =
                episodesForSeason,
            episodesError = null
        )
    }
}

internal fun PlayerRuntimeController.loadEpisodesIfNeeded() {
    val type =
        contentType

    val id =
        contentId

    if (
        type.isNullOrBlank() ||
        id.isNullOrBlank()
    ) {
        return
    }

    if (
        type.trim().lowercase() !in
        listOf(
            "series",
            "tv"
        )
    ) {
        return
    }

    val state =
        _uiState.value

    if (
        state.episodesAll
            .isNotEmpty() ||
        state.isLoadingEpisodes
    ) {
        return
    }

    // The normal Player metadata load already resolved the canonical
    // episode list. Never re-resolve the show through another addon.
    if (metaVideos.isNotEmpty()) {
        applyEpisodesFromMetaVideos(
            metaVideos
        )
        return
    }

    // The sidebar can occasionally be opened before the normal metadata
    // request completes. In that race, use the exact same resolver.
    scope.launch {
        _uiState.update {
            it.copy(
                isLoadingEpisodes = true,
                episodesError = null
            )
        }

        val meta =
            resolveCanonicalPlayerMeta(
                id = id,
                type = type
            )

        if (meta != null) {
            applyMetaDetails(meta)
        } else {
            _uiState.update {
                it.copy(
                    isLoadingEpisodes =
                        false
                )
            }
        }
    }
}

internal fun PlayerRuntimeController.loadStreamsForEpisode(video: Video) {
    loadStreamsForEpisode(video = video, forceRefresh = false)
}

internal fun PlayerRuntimeController.buildEpisodeRequestKey(type: String, video: Video): String {
    return "$type|${video.id}|${video.season ?: -1}|${video.episode ?: -1}"
}

internal fun PlayerRuntimeController.loadStreamsForEpisode(video: Video, forceRefresh: Boolean) {
    val type = contentType
    if (type.isNullOrBlank()) {
        _uiState.update { it.copy(episodeStreamsError = "Missing content type") }
        return
    }

    val requestKey = buildEpisodeRequestKey(type = type, video = video)
    val state = _uiState.value
    val hasCachedPayload = state.episodeAllStreams.isNotEmpty() || state.episodeStreamsError != null
    if (!forceRefresh && requestKey == episodeStreamsCacheRequestKey && hasCachedPayload) {
        _uiState.update {
            it.copy(
                showEpisodeStreams = true,
                isLoadingEpisodeStreams = false,
                episodeStreamsForVideoId = video.id,
                episodeStreamsSeason = video.season,
                episodeStreamsEpisode = video.episode,
                episodeStreamsTitle = video.title
            )
        }
        return
    }

    val targetChanged = requestKey != episodeStreamsCacheRequestKey
    episodeStreamsJob?.cancel()
    episodeStreamsJob = scope.launch {
        episodeStreamsCacheRequestKey = requestKey
        // A newly opened episode (and an explicit reload) starts on All.
        // Carrying the previous episode's addon filter forward made the chip
        // row and first Up-navigation unexpectedly land on that addon.
        _uiState.update {
            it.copy(
                showEpisodeStreams = true,
                isLoadingEpisodeStreams = true,
                episodeStreamsError = null,
                episodeAllStreams = if (forceRefresh || targetChanged) emptyList() else it.episodeAllStreams,
                episodeSelectedAddonFilter = if (forceRefresh || targetChanged) null else it.episodeSelectedAddonFilter,
                episodeFilteredStreams = if (forceRefresh || targetChanged) emptyList() else it.episodeFilteredStreams,
                episodeAvailableAddons = if (forceRefresh || targetChanged) emptyList() else it.episodeAvailableAddons,
                episodeStreamsForVideoId = video.id,
                episodeStreamsSeason = video.season,
                episodeStreamsEpisode = video.episode,
                episodeStreamsTitle = video.title
            )
        }

        val installedAddons = addonRepository.getInstalledAddons().first()
        val installedAddonOrder = installedAddons.map { it.displayName }
        val debridSettings = debridSettingsDataStore.settings.first()

        streamRepository.getStreamsFromAllAddons(
            type = type,
            videoId = video.id,
            season = video.season,
            episode = video.episode
        ).collect { result ->
            when (result) {
                is NetworkResult.Success -> {
                    val addonStreams = StreamAutoPlaySelector.orderAddonStreams(result.data, installedAddonOrder)
                    val episodeSelectionContext = debridEpisodeSelectionContextFor(video)
                    val allStreams = withContext(Dispatchers.Default) {
                        DirectDebridStreamFilter.sortForSourceList(
                            streams = addonStreams.flatMap { it.streams },
                            settings = debridSettings
                        ).orderByDebridEpisodeTitleEvidence(episodeSelectionContext)
                    }
                    android.util.Log.d("PlayerRecovery", "Stream preload complete: ${allStreams.size} streams available for fallback")
                    val availableAddons = addonStreams.map { it.addonName }
                    _uiState.update {
                        // Read the filter from the state at emission time. Addons
                        // finish progressively, so a value captured before the
                        // request began may be stale after the user navigates the
                        // chip row while other addons are still loading.
                        val filter = resolveStreamAddonFilter(
                            allStreams = allStreams,
                            availableAddons = availableAddons,
                            selectedAddon = it.episodeSelectedAddonFilter
                        )
                        it.copy(
                            isLoadingEpisodeStreams = false,
                            episodeAllStreams = allStreams,
                            episodeSelectedAddonFilter = filter.selectedAddon,
                            episodeFilteredStreams = filter.streams,
                            episodeAvailableAddons = availableAddons,
                            episodeStreamsError = null
                        )
                    }
                }

                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoadingEpisodeStreams = false,
                            episodeStreamsError = result.message
                        )
                    }
                }

                NetworkResult.Loading -> {
                    _uiState.update { it.copy(isLoadingEpisodeStreams = true) }
                }
            }
        }
    }
}

internal fun PlayerRuntimeController.reloadEpisodeStreams() {
    val state = _uiState.value
    val targetVideoId = state.episodeStreamsForVideoId
    val targetVideo = sequenceOf(
        state.episodes.firstOrNull { it.id == targetVideoId },
        state.episodesAll.firstOrNull { it.id == targetVideoId },
        state.episodes.firstOrNull {
            it.season == state.episodeStreamsSeason && it.episode == state.episodeStreamsEpisode
        },
        state.episodesAll.firstOrNull {
            it.season == state.episodeStreamsSeason && it.episode == state.episodeStreamsEpisode
        }
    ).firstOrNull { it != null }

    if (targetVideo != null) {
        loadStreamsForEpisode(video = targetVideo, forceRefresh = true)
    }
}

internal fun PlayerRuntimeController.switchToEpisodeStream(stream: Stream, forcedTargetVideo: Video? = null) {
    val targetVideo = forcedTargetVideo
        ?: sequenceOf(
            _uiState.value.episodes.firstOrNull {
                it.id == _uiState.value.episodeStreamsForVideoId
            },
            _uiState.value.episodesAll.firstOrNull {
                it.id == _uiState.value.episodeStreamsForVideoId
            },
            _uiState.value.episodes.firstOrNull {
                it.season == _uiState.value.episodeStreamsSeason &&
                    it.episode == _uiState.value.episodeStreamsEpisode
            },
            _uiState.value.episodesAll.firstOrNull {
                it.season == _uiState.value.episodeStreamsSeason &&
                    it.episode == _uiState.value.episodeStreamsEpisode
            }
        ).firstOrNull { it != null }

    val episodeSelectionContext = debridEpisodeSelectionContextFor(targetVideo)
    if (stream.debridEpisodeTitleMatch(episodeSelectionContext) == DebridEpisodeTitleMatch.CONFLICT) {
        _uiState.update {
            it.copy(episodeStreamsError = "Selected stream appears to be for a different episode")
        }
        return
    }

    val url = stream.getStreamUrl()
    if (url.isNullOrBlank()) {
        scope.launch(Dispatchers.Default) {
            val resolvedStream = resolveDirectDebridStreamIfNeeded(
                stream = stream,
                season = targetVideo?.season ?: _uiState.value.episodeStreamsSeason,
                episode = targetVideo?.episode ?: _uiState.value.episodeStreamsEpisode,
                selectionContext = episodeSelectionContext
            )
            withContext(Dispatchers.Main) {
                if (resolvedStream == null || resolvedStream.getStreamUrl().isNullOrBlank()) {
                    _uiState.update { it.copy(episodeStreamsError = "Invalid stream URL") }
                } else {
                    switchToEpisodeStream(
                        stream = resolvedStream,
                        forcedTargetVideo = targetVideo
                    )
                }
            }
        }
        return
    }
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null

    flushPlaybackSnapshotForSwitchOrExit()

    val newHeaders = PlayerMediaSourceFactory.sanitizeHeaders(
        stream.behaviorHints?.proxyHeaders?.request
    )

    resetLoadingOverlayForNewStream()
    releasePlayer(flushPlaybackState = false)

    applySelectedStreamState(
        stream = stream,
        url = url,
        headers = newHeaders
    )
    persistSelectedStreamForReuse(stream = stream, url = url, headers = newHeaders)
    pendingSameSeriesTrackSelectionRestore =
        sameSeriesTrackSelectionPreference?.takeIf { contentType?.lowercase() in listOf("series", "tv") }
    hasRetriedCurrentStreamAfter416 = false
    currentVideoId = targetVideo?.id ?: _uiState.value.episodeStreamsForVideoId ?: currentVideoId
    currentSeason = targetVideo?.season ?: _uiState.value.episodeStreamsSeason ?: currentSeason
    currentEpisode = targetVideo?.episode ?: _uiState.value.episodeStreamsEpisode ?: currentEpisode
    currentEpisodeTitle = targetVideo?.title ?: _uiState.value.episodeStreamsTitle ?: currentEpisodeTitle
    currentTraktEpisodeMapping = null
    currentTraktEpisodeMappingKey = null
    lastSavedPosition = 0L
    hasMarkedCurrentItemCompleted = false

    _uiState.update {
        it.copy(
            isBuffering = true,
            error = null,
            currentSeason = currentSeason,
            currentEpisode = currentEpisode,
            currentEpisodeTitle = currentEpisodeTitle,
            currentStreamName = stream.name ?: stream.addonName,
            currentStreamUrl = url,
            // Keep the selected row identity so the Sources panel can pin the
            // now-playing stream to the top even when its resolved URL is
            // different from the addon result.
            currentSourceStreamKey = stream.stableKey(),
            currentStreamInfoHash = currentInfoHash,
            currentStreamFileIdx = currentFileIdx,
            currentStreamAddonName = currentAddonName,
            currentStreamDescription = currentStreamDescription,
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            selectedAudioTrackIndex = -1,
            selectedSubtitleTrackIndex = -1,
            showEpisodesPanel = false,
            showEpisodeStreams = false,
            isLoadingEpisodeStreams = false,
            episodeStreamsError = null,
            
            parentalWarnings = emptyList(),
            showParentalGuide = false,
            parentalGuideHasShown = false,
            
            activeSkipInterval = null,
            skipIntervalDismissed = false,
            showNextEpisodeCard = false,
            nextEpisodeCardDismissed = false,
            nextEpisodeAutoPlaySearching = false,
            nextEpisodeAutoPlaySourceName = null,
            nextEpisodeAutoPlayCountdownSec = null
        )
    }
    showStreamSourceIndicator(stream)
    recomputeNextEpisode(resetVisibility = true)

    updateEpisodeDescription()

    playbackStartedForParentalGuide = false
    skipIntervals = emptyList()
    skipIntroFetchedKey = null
    lastActiveSkipType = null

    
    fetchParentalGuide(contentId, contentType, currentSeason, currentEpisode)
    fetchSkipIntervals(contentId, currentSeason, currentEpisode)

    preparePlaybackBeforeStart(
        url = url,
        headers = newHeaders,
        loadSavedProgress = true
    )
}

internal fun PlayerRuntimeController.showEpisodeStreamPicker(video: Video, forceRefresh: Boolean = true) {
    _uiState.update {
        it.copy(
            showEpisodesPanel = true,
            showEpisodeStreams = true,
            showSourcesPanel = false,
            showControls = true,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSpeedDialog = false,
            showMoreDialog = false,
            episodesSelectedSeason = video.season ?: it.episodesSelectedSeason
        )
    }
    loadEpisodesIfNeeded()
    loadStreamsForEpisode(video = video, forceRefresh = forceRefresh)
}


internal fun PlayerRuntimeController.debridEpisodeSelectionContextFor(
    video: Video?
): DebridEpisodeSelectionContext? {
    val targetSeason = video?.season ?: currentSeason
    val targetEpisode = video?.episode ?: currentEpisode
    if (targetSeason == null || targetEpisode == null) return null

    val targetTitle = video?.title ?: currentEpisodeTitle
    val seasonTitles = _uiState.value.episodesAll
        .asSequence()
        .filter { it.season == targetSeason }
        .map { it.title }
        .filter { it.isNotBlank() }
        .distinct()
        .toList()

    return DebridEpisodeSelectionContext(
        season = targetSeason,
        episode = targetEpisode,
        title = targetTitle,
        seasonEpisodeTitles = seasonTitles
    )
}

internal fun PlayerRuntimeController.currentDebridEpisodeSelectionContext():
    DebridEpisodeSelectionContext? =
    debridEpisodeSelectionContextFor(video = null)

internal suspend fun PlayerRuntimeController.resolveDirectDebridStreamIfNeeded(
    stream: Stream,
    season: Int?,
    episode: Int?,
    selectionContext: DebridEpisodeSelectionContext? = null
): Stream? {
    if (stream.getStreamUrl() != null) {
        return stream.takeUnless {
            it.debridEpisodeTitleMatch(selectionContext) == DebridEpisodeTitleMatch.CONFLICT
        }
    }

    return when (
        val result = directDebridResolver.resolveToPlayableStream(
            stream = stream,
            season = season,
            episode = episode,
            selectionContext = selectionContext
        )
    ) {
        is DirectDebridPlayableResult.Success -> result.stream.takeUnless {
            it.debridEpisodeTitleMatch(selectionContext) == DebridEpisodeTitleMatch.CONFLICT
        }
        DirectDebridPlayableResult.MissingApiKey,
        DirectDebridPlayableResult.NotCached,
        DirectDebridPlayableResult.Stale,
        DirectDebridPlayableResult.Error -> null
    }
}

internal fun shouldRunNextEpisodeAutoPlayCountdown(
    userInitiated: Boolean
): Boolean = !userInitiated

internal fun PlayerRuntimeController.playNextEpisode(userInitiated: Boolean = false) {
    val nextVideo = nextEpisodeVideo ?: return
    val type = contentType ?: return
    val nextEpisodeSelectionContext = debridEpisodeSelectionContextFor(nextVideo)

    val state = _uiState.value
    if (state.nextEpisode?.hasAired == false) {
        return
    }
    if (!userInitiated && (state.nextEpisodeAutoPlaySearching || state.nextEpisodeAutoPlayCountdownSec != null)) {
        return
    }

    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = scope.launch {
        try {
            val playerSettings = playerSettingsDataStore.playerSettings.first()
            val shouldAutoSelectInManualMode =
                playerSettings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL &&
                    (
                        playerSettings.streamAutoPlayNextEpisodeEnabled ||
                            playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode
                        )
            if (playerSettings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL && !shouldAutoSelectInManualMode) {
                _uiState.update {
                    it.copy(
                        showNextEpisodeCard = false,
                        nextEpisodeCardDismissed = true,
                        nextEpisodeAutoPlaySearching = false,
                        nextEpisodeAutoPlaySourceName = null,
                        nextEpisodeAutoPlayCountdownSec = null
                    )
                }
                showEpisodeStreamPicker(video = nextVideo, forceRefresh = true)
                return@launch
            }

            _uiState.update {
                it.copy(
                    showNextEpisodeCard = true,
                    nextEpisodeCardDismissed = false,
                    nextEpisodeAutoPlaySearching = true,
                    nextEpisodeAutoPlaySourceName = null,
                    nextEpisodeAutoPlayCountdownSec = null
                )
            }

            val installedAddons = addonRepository.getInstalledAddons().first()
            val installedAddonOrder = installedAddons.map { it.displayName }
            val effectiveMode = if (shouldAutoSelectInManualMode) {
                StreamAutoPlayMode.FIRST_STREAM
            } else {
                playerSettings.streamAutoPlayMode
            }
            val effectiveSource = if (shouldAutoSelectInManualMode) {
                StreamAutoPlaySource.ALL_SOURCES
            } else {
                playerSettings.streamAutoPlaySource
            }
            val effectiveSelectedAddons = if (shouldAutoSelectInManualMode) {
                emptySet()
            } else {
                playerSettings.streamAutoPlaySelectedAddons
            }
            val effectiveSelectedPlugins = if (shouldAutoSelectInManualMode) {
                emptySet()
            } else {
                playerSettings.streamAutoPlaySelectedPlugins
            }
            val effectiveRegex = if (shouldAutoSelectInManualMode) {
                ""
            } else {
                playerSettings.streamAutoPlayRegex
            }
            var selectedStream: Stream? = null
            var lastSuccessData: List<AddonStreams>? = null
            var autoSelectTriggered = false
            var timeoutElapsed = false
            var lastError: NetworkResult.Error? = null

            fun trySelectStream(
                data: List<AddonStreams>,
                excludedStreamKeys: Set<String> = emptySet()
            ): Stream? {
                val orderedStreams = StreamAutoPlaySelector.orderAddonStreams(data, installedAddonOrder)
                val allStreams = orderedStreams
                    .flatMap { it.streams }
                    .orderByDebridEpisodeTitleEvidence(nextEpisodeSelectionContext)
                    .filterNot {
                        it.debridEpisodeTitleMatch(nextEpisodeSelectionContext) ==
                            DebridEpisodeTitleMatch.CONFLICT
                    }
                    .filterNot { it.stableKey() in excludedStreamKeys }

                return StreamAutoPlaySelector.selectAutoPlayStream(
                    streams = allStreams,
                    mode = effectiveMode,
                    regexPattern = effectiveRegex,
                    source = effectiveSource,
                    installedAddonNames = installedAddonOrder.toSet(),
                    selectedAddons = effectiveSelectedAddons,
                    selectedPlugins = effectiveSelectedPlugins,
                    preferredBingeGroup = if (playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode) {
                        currentStreamBingeGroup
                    } else {
                        null
                    },
                    preferBingeGroupInSelection = playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode
                )
            }

            val timeoutSeconds = playerSettings.streamAutoPlayTimeoutSeconds
            val selectionReady = kotlinx.coroutines.CompletableDeferred<Unit>()

            val innerJob = scope.launch {
                streamRepository.getStreamsFromAllAddons(
                    type = type,
                    videoId = nextVideo.id,
                    season = nextVideo.season,
                    episode = nextVideo.episode
                ).collect { result ->
                    when (result) {
                        is NetworkResult.Success -> {
                            lastSuccessData = result.data
                            if (timeoutElapsed && !autoSelectTriggered) {
                                val candidate = trySelectStream(result.data)
                                if (candidate != null) {
                                    selectedStream = candidate
                                    autoSelectTriggered = true
                                    selectionReady.complete(Unit)
                                }
                            }
                        }
                        is NetworkResult.Error -> lastError = result
                        NetworkResult.Loading -> Unit
                    }
                }

                if (!autoSelectTriggered) {
                    val candidate = lastSuccessData?.let(::trySelectStream)
                    if (candidate != null) {
                        selectedStream = candidate
                        autoSelectTriggered = true
                    }
                }

                selectionReady.complete(Unit)
            }

            val timeoutMs = timeoutSeconds * 1_000L
            if (PlayerSettings.isBoundedTimeout(timeoutSeconds)) {
                delay(timeoutMs)
            }
            timeoutElapsed = true

            if (!autoSelectTriggered && lastSuccessData != null) {
                val candidate = trySelectStream(lastSuccessData)
                if (candidate != null) {
                    selectedStream = candidate
                    autoSelectTriggered = true
                    selectionReady.complete(Unit)
                }
            }

            if (selectedStream == null && !innerJob.isCompleted) {
                selectionReady.await()
            }

            if (selectedStream != null) {
                innerJob.cancel()
            } else {
                innerJob.join()
            }

            var streamToPlay: Stream? = null
            val rejectedStreamKeys = linkedSetOf<String>()
            var candidate = selectedStream

            while (candidate != null && rejectedStreamKeys.size < 5) {
                val attempted = candidate
                val resolvedCandidate = resolveDirectDebridStreamIfNeeded(
                    stream = attempted,
                    season = nextVideo.season,
                    episode = nextVideo.episode,
                    selectionContext = nextEpisodeSelectionContext
                ) ?: attempted.takeIf { directCandidate ->
                    !directCandidate.getStreamUrl().isNullOrBlank() &&
                        directCandidate.debridEpisodeTitleMatch(nextEpisodeSelectionContext) !=
                            DebridEpisodeTitleMatch.CONFLICT
                }

                if (resolvedCandidate != null) {
                    streamToPlay = resolvedCandidate
                    break
                }

                rejectedStreamKeys += attempted.stableKey()
                android.util.Log.d(
                    "PlayerRecovery",
                    "Next episode candidate rejected; trying another source"
                )

                candidate = lastSuccessData?.let { data ->
                    trySelectStream(
                        data = data,
                        excludedStreamKeys = rejectedStreamKeys
                    )
                }
            }
            if (streamToPlay != null) {
                val sourceName = (streamToPlay.name?.takeIf { it.isNotBlank() } ?: streamToPlay.addonName).trim()
                if (shouldRunNextEpisodeAutoPlayCountdown(userInitiated)) {
                    for (remaining in 3 downTo 1) {
                        _uiState.update {
                            it.copy(
                                showNextEpisodeCard = true,
                                nextEpisodeCardDismissed = false,
                                nextEpisodeAutoPlaySearching = false,
                                nextEpisodeAutoPlaySourceName = sourceName,
                                nextEpisodeAutoPlayCountdownSec = remaining
                            )
                        }
                        delay(1000)
                    }
                }
                _uiState.update {
                    it.copy(
                        showNextEpisodeCard = false,
                        nextEpisodeCardDismissed = true,
                        nextEpisodeAutoPlaySearching = false,
                        nextEpisodeAutoPlaySourceName = null,
                        nextEpisodeAutoPlayCountdownSec = null
                    )
                }
                switchToEpisodeStream(stream = streamToPlay, forcedTargetVideo = nextVideo)
            } else {
                _uiState.update {
                    it.copy(
                        showNextEpisodeCard = false,
                        nextEpisodeCardDismissed = true,
                        nextEpisodeAutoPlaySearching = false,
                        nextEpisodeAutoPlaySourceName = null,
                        nextEpisodeAutoPlayCountdownSec = null
                    )
                }
                showEpisodeStreamPicker(
                    video = nextVideo,
                    forceRefresh = lastError != null
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    showNextEpisodeCard = false,
                    nextEpisodeCardDismissed = true,
                    nextEpisodeAutoPlaySearching = false,
                    nextEpisodeAutoPlaySourceName = null,
                    nextEpisodeAutoPlayCountdownSec = null
                )
            }
            showEpisodeStreamPicker(video = nextVideo, forceRefresh = false)
        }
    }
}
