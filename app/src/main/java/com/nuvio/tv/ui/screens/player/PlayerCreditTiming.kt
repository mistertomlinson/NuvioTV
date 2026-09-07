package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.remote.api.CreditAnalyzeRequest
import com.nuvio.tv.data.remote.api.CreditAnalyzerJobResponse
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.PosterShape
import java.security.MessageDigest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val CREDIT_JOB_POLL_INTERVAL_MS = 5_000L
private const val MOVIE_FALLBACK_THRESHOLD = 0.95

internal fun PlayerRuntimeController.evaluateCreditTiming(
    positionMs: Long,
    durationMs: Long
) {
    if (!hasRenderedFirstFrame || positionMs < CREDIT_ANALYZER_TRIGGER_POSITION_MS) return
    if (_uiState.value.creditTiming.status != CreditTimingStatus.NOT_STARTED) return

    val mediaKey = buildCreditAnalyzerMediaKey()
    creditAnalysisIdentity = mediaKey
    if (!creditAnalyzerRepository.isConfigured || !isCreditAnalyzerStreamEligible() || mediaKey == null) {
        useCreditTimingFallback()
        return
    }

    _uiState.update {
        it.copy(creditTiming = CreditTimingUiState(status = CreditTimingStatus.RUNNING))
    }
    creditAnalysisJob?.cancel()
    creditAnalysisJob = scope.launch {
        val isEpisode = contentType.equals("series", ignoreCase = true) ||
            contentType.equals("tv", ignoreCase = true)
        if (isEpisode) {
            val season = currentSeason
            val episode = currentEpisode
            if (season != null && episode != null) {
                val introDbIntervals = skipIntroRepository.getIntroDbIntervalsForMedia(
                    mediaId = currentVideoId ?: contentId,
                    season = season,
                    episode = episode
                )
                if (!isActive || creditAnalysisIdentity != mediaKey) return@launch
                if (introDbIntervals.isNotEmpty()) {
                    introDbCreditIntervals = introDbIntervals
                    val outroStartMs = introDbIntervals
                        .filter { it.type.equals("outro", ignoreCase = true) }
                        .minOfOrNull { (it.startTime * 1_000.0).toLong() }
                    _uiState.update {
                        it.copy(
                            creditTiming = CreditTimingUiState(
                                status = CreditTimingStatus.INTRO_DB_AVAILABLE,
                                creditsStartMs = outroStartMs,
                                finalCreditsStartMs = outroStartMs
                            )
                        )
                    }
                    if (shouldUsePostPlayRecommendations(_uiState.value) &&
                        _uiState.value.postPlayRecommendations.isEmpty() &&
                        !_uiState.value.isPostPlayRecommendationLoading
                    ) {
                        loadPostPlayRecommendations()
                    }
                    return@launch
                }
            }
        }

        val request = CreditAnalyzeRequest(
            mediaUrl = currentStreamUrl,
            mediaKey = mediaKey,
            contentKey = buildCreditAnalyzerContentKey(),
            durationMs = durationMs.takeIf { it >= 60_000L },
            sizeBytes = currentVideoSize?.takeIf { it > 0L },
            title = buildCreditAnalyzerTitle(),
            contentType = if (isEpisode) "episode" else if (contentType.equals("movie", true)) "movie" else "other"
        )
        var job = creditAnalyzerRepository.submit(request).getOrElse { error ->
            if (error is CancellationException) throw error
            if (creditAnalysisIdentity != mediaKey) return@launch
            Log.w(PlayerRuntimeController.TAG, "Credit analyzer submit failed: ${error.message}")
            useCreditTimingFallback()
            return@launch
        }
        applyCreditAnalyzerFallback(job, mediaKey)

        while (isActive && job.status in setOf("queued", "running")) {
            delay(CREDIT_JOB_POLL_INTERVAL_MS)
            job = creditAnalyzerRepository.getJob(job.jobId).getOrElse { error ->
                if (error is CancellationException) throw error
                if (creditAnalysisIdentity != mediaKey) return@launch
                Log.w(PlayerRuntimeController.TAG, "Credit analyzer poll failed: ${error.message}")
                useCreditTimingFallback()
                return@launch
            }
        }
        applyCreditAnalyzerResult(job, mediaKey)
    }
}

private fun PlayerRuntimeController.applyCreditAnalyzerFallback(
    job: CreditAnalyzerJobResponse,
    expectedIdentity: String
) {
    if (creditAnalysisIdentity != expectedIdentity) return
    val fallback = job.fallback ?: return
    Log.i(
        PlayerRuntimeController.TAG,
        "Credit analyzer cross-release fallback ready: " +
            "sourceDurationMs=${fallback.sourceDurationMs}, " +
            "targetDurationMs=${fallback.targetDurationMs}, " +
            "runtimeDifferenceMs=${fallback.runtimeDifferenceMs}, " +
            "finalCreditsStartMs=${fallback.finalCreditsStartMs}"
    )
    _uiState.update {
        it.copy(
            creditTiming = CreditTimingUiState(
                status = CreditTimingStatus.RUNNING,
                creditsStartMs = fallback.creditsStartMs,
                finalCreditsStartMs = fallback.finalCreditsStartMs,
                hasPostCreditScenes = fallback.finalCreditsStartMs > fallback.creditsStartMs,
                confidence = fallback.confidence
            )
        )
    }
    if (shouldUsePostPlayRecommendations(_uiState.value) &&
        _uiState.value.postPlayRecommendations.isEmpty() &&
        !_uiState.value.isPostPlayRecommendationLoading
    ) {
        loadPostPlayRecommendations()
    }
}

private fun PlayerRuntimeController.applyCreditAnalyzerResult(
    job: CreditAnalyzerJobResponse,
    expectedIdentity: String
) {
    if (creditAnalysisIdentity != expectedIdentity) return
    val result = job.result
    if (job.status != "complete" || result?.detected != true || result.finalCreditsStartMs == null) {
        if (!job.error.isNullOrBlank()) {
            Log.w(PlayerRuntimeController.TAG, "Credit analyzer failed: ${job.error}")
        }
        useCreditTimingFallback()
        return
    }
    _uiState.update {
        it.copy(
            creditTiming = CreditTimingUiState(
                status = CreditTimingStatus.COMPLETE,
                creditsStartMs = result.creditsStartMs,
                finalCreditsStartMs = result.finalCreditsStartMs,
                hasPostCreditScenes = result.postCreditScenes.isNotEmpty(),
                confidence = result.confidence
            )
        )
    }
    if (shouldUsePostPlayRecommendations(_uiState.value) &&
        _uiState.value.postPlayRecommendations.isEmpty() &&
        !_uiState.value.isPostPlayRecommendationLoading
    ) {
        loadPostPlayRecommendations()
    }
}

private fun PlayerRuntimeController.useCreditTimingFallback() {
    _uiState.update {
        it.copy(creditTiming = it.creditTiming.copy(status = CreditTimingStatus.FALLBACK))
    }
    if (shouldUsePostPlayRecommendations(_uiState.value) &&
        _uiState.value.postPlayRecommendations.isEmpty() &&
        !_uiState.value.isPostPlayRecommendationLoading
    ) {
        loadPostPlayRecommendations()
    }
}

internal fun PlayerRuntimeController.resetCreditTimingForNewPlayback() {
    creditAnalysisJob?.cancel()
    creditAnalysisJob = null
    recommendationLoadJob?.cancel()
    recommendationLoadJob = null
    recommendationMetadataJob?.cancel()
    recommendationMetadataJob = null
    postPlayTrailerCountdownJob?.cancel()
    postPlayTrailerCountdownJob = null
    pendingPostPlayRecommendationIndex = null
    ratingTransitionJob?.cancel()
    ratingTransitionJob = null
    creditAnalysisIdentity = null
    introDbCreditIntervals = emptyList()
    _uiState.update {
        it.copy(
            creditTiming = CreditTimingUiState(),
            postPlayRecommendations = emptyList(),
            postPlayRecommendationIndex = 0,
            isPostPlayRecommendationLoading = false,
            isPostPlayRecommendationVisible = false,
            postPlayRecommendationDismissed = false,
            isPostPlayTrailerPlaying = false,
            hasPlayedPostPlayTrailer = false,
            postPlayTrailerCountdownSec = null,
            manualEndActionRequested = false,
            showRatingOverlay = false,
            ratingSubmitted = false,
            showPlayerBlackout = false,
            pendingRating = null,
            ratingOverlayDestination = null,
            creditRatingPromptHandled = false
        )
    }
}

internal fun PlayerRuntimeController.isEndActionTriggerReached(
    positionMs: Long,
    durationMs: Long
): Boolean {
    if (_uiState.value.manualEndActionRequested) return true
    val timing = _uiState.value.creditTiming
    authoritativeEndActionDecision(timing, positionMs)?.let { return it }

    val effectiveDuration = durationMs.takeIf { it > 0L } ?: lastKnownDuration
    if (contentType.equals("movie", ignoreCase = true)) {
        if (effectiveDuration <= 0L) return false
        return positionMs.toDouble() / effectiveDuration.toDouble() >= MOVIE_FALLBACK_THRESHOLD
    }
    return PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
        positionMs = positionMs,
        durationMs = effectiveDuration,
        skipIntervals = (introDbCreditIntervals + skipIntervals).distinct(),
        thresholdMode = nextEpisodeThresholdModeSetting,
        thresholdPercent = nextEpisodeThresholdPercentSetting,
        thresholdMinutesBeforeEnd = nextEpisodeThresholdMinutesBeforeEndSetting
    )
}

internal fun PlayerRuntimeController.evaluatePostPlayRecommendations(
    positionMs: Long,
    durationMs: Long
) {
    val state = _uiState.value
    if (!shouldUsePostPlayRecommendations(state)) return

    if (state.postPlayRecommendations.isEmpty() &&
        !state.isPostPlayRecommendationLoading &&
        !state.postPlayRecommendationDismissed &&
        positionMs >= CREDIT_ANALYZER_TRIGGER_POSITION_MS &&
        (state.manualEndActionRequested ||
            state.creditTiming.status != CreditTimingStatus.RUNNING ||
            state.creditTiming.finalCreditsStartMs != null)
    ) {
        loadPostPlayRecommendations()
    }

    if (state.postPlayRecommendations.isNotEmpty() &&
        !state.postPlayRecommendationDismissed &&
        !state.blocksEndActionForRating &&
        isEndActionTriggerReached(positionMs, durationMs)
    ) {
        _uiState.update {
            it.copy(
                isPostPlayRecommendationVisible = true,
                showControls = false,
                showPauseOverlay = false,
                showNextEpisodeCard = false
            )
        }
    }
}

private fun PlayerRuntimeController.shouldUsePostPlayRecommendations(state: PlayerUiState): Boolean {
    return when (contentType?.trim()?.lowercase()) {
        "movie", "film" -> true
        "series", "tv", "show", "tvshow" ->
            state.isNextEpisodeMetadataResolved && state.nextEpisode?.hasAired != true
        else -> false
    }
}

internal fun PlayerRuntimeController.preparePostPlayRecommendationsForManualEndAction() {
    val state = _uiState.value
    if (!shouldUsePostPlayRecommendations(state)) return
    if (state.postPlayRecommendations.isEmpty() && !state.isPostPlayRecommendationLoading) {
        loadPostPlayRecommendations()
    }
}

private fun PlayerRuntimeController.loadPostPlayRecommendations() {
    recommendationLoadJob?.cancel()
    val expectedIdentity = listOf(
        creditAnalysisIdentity,
        currentVideoId,
        currentSeason?.toString(),
        currentEpisode?.toString()
    ).joinToString("|")
    _uiState.update { it.copy(isPostPlayRecommendationLoading = true) }
    recommendationLoadJob = scope.launch {
        try {
            val type = when (contentType?.trim()?.lowercase()) {
                "series", "tv", "show", "tvshow" -> ContentType.SERIES
                else -> ContentType.MOVIE
            }
            val sourceId = contentId ?: currentVideoId
            val tmdbId = sourceId?.let { tmdbService.ensureTmdbId(it, type.toApiString()) }
            val language = tmdbSettingsDataStore.settings.first().language
            val recommendations = if (tmdbId == null) {
                emptyList()
            } else {
                tmdbMetadataService.fetchMoreLikeThis(
                    tmdbId = tmdbId,
                    contentType = type,
                    language = language,
                    maxItems = 12
                )
            }
            val mapped = recommendations
                .filterNot { it.id == sourceId || it.id == "tmdb:$tmdbId" }
                .take(MAX_POST_PLAY_RECOMMENDATION_CANDIDATES)
                .map { item ->
                    PostPlayRecommendation(
                        id = item.id,
                        contentType = item.apiType,
                        title = item.name,
                        backdrop = item.backdropUrl,
                        poster = item.poster,
                        logo = item.logo,
                        description = item.description,
                        releaseInfo = item.releaseInfo,
                        genres = item.genres,
                        runtime = item.runtime,
                        imdbRating = item.imdbRating
                    )
                }
            if (mapped.isEmpty()) {
                if (recommendationIdentity() == expectedIdentity) {
                    _uiState.update { it.copy(isPostPlayRecommendationLoading = false) }
                }
                return@launch
            }
            val readyRecommendations = mutableListOf<PostPlayRecommendation>()
            for (batch in mapped.chunked(MAX_POST_PLAY_RECOMMENDATIONS)) {
                val resolvedBatch = coroutineScope {
                    batch.map { candidate ->
                        async { resolvePostPlayRecommendation(candidate) }
                    }.awaitAll()
                }
                readyRecommendations += resolvedBatch.filter {
                    it.isPresentationReady()
                }
                if (readyRecommendations.size >= MAX_POST_PLAY_RECOMMENDATIONS) break
            }
            val displayRecommendations = readyRecommendations
                .take(MAX_POST_PLAY_RECOMMENDATIONS)
            if (displayRecommendations.isEmpty()) {
                if (recommendationIdentity() == expectedIdentity) {
                    _uiState.update { it.copy(isPostPlayRecommendationLoading = false) }
                }
                return@launch
            }
            val currentIdentity = recommendationIdentity()
            if (!isActive || currentIdentity != expectedIdentity) return@launch
            val playerPosition = _exoPlayer?.currentPosition?.coerceAtLeast(0L)
                ?: _uiState.value.currentPosition
            val playerDuration = _exoPlayer?.duration?.takeIf { it > 0L }
                ?: _uiState.value.duration
            val shouldShowImmediately =
                !_uiState.value.postPlayRecommendationDismissed &&
                !_uiState.value.blocksEndActionForRating &&
                !shouldShowCreditRatingPrompt(_uiState.value, playerPosition) &&
                isEndActionTriggerReached(playerPosition, playerDuration)
            _uiState.update {
                it.copy(
                    postPlayRecommendations = displayRecommendations,
                    postPlayRecommendationIndex = 0,
                    isPostPlayRecommendationLoading = false,
                    isPostPlayRecommendationVisible = shouldShowImmediately,
                    showControls = if (shouldShowImmediately) false else it.showControls,
                    showPauseOverlay = if (shouldShowImmediately) false else it.showPauseOverlay
                )
            }
            if (_uiState.value.playbackEnded) {
                schedulePostPlayTrailerAfterPlaybackEnded()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(PlayerRuntimeController.TAG, "Post-play recommendations failed: ${error.message}")
            if (recommendationIdentity() == expectedIdentity) {
                _uiState.update { it.copy(isPostPlayRecommendationLoading = false) }
            }
        }
    }
}

private suspend fun PlayerRuntimeController.resolvePostPlayRecommendation(
    candidate: PostPlayRecommendation
): PostPlayRecommendation = try {
    val type = when (candidate.contentType.trim().lowercase()) {
        "series", "tv", "show", "tvshow" -> ContentType.SERIES
        else -> ContentType.MOVIE
    }
    val settings = tmdbSettingsDataStore.settings.first()
    val tmdbId = candidate.id
        .removePrefix("tmdb:")
        .substringBefore(':')
        .takeIf { it.toIntOrNull() != null }
        ?: tmdbService.ensureTmdbId(candidate.id, candidate.contentType)
    val enrichment = if (tmdbId != null) {
        withTimeoutOrNull(12_000L) {
            tmdbMetadataService.fetchEnrichment(
                tmdbId = tmdbId,
                contentType = type,
                language = settings.language
            )
        }
    } else {
        null
    }
    val imdbId = tmdbId?.toIntOrNull()?.let { numericId ->
        runCatching {
            tmdbService.tmdbToImdb(numericId, candidate.contentType)
        }.getOrNull()
    }
    val meta = withTimeoutOrNull(8_000L) {
        metaRepository.getMetaFromAllAddons(
            type = candidate.contentType,
            id = imdbId ?: candidate.id
        ).first { it !is NetworkResult.Loading }
            .let { (it as? NetworkResult.Success)?.data }
    }
    // MDBList only needs a stable media type and external ID. Do not make its
    // ratings depend on the optional addon-meta request completing in time.
    val ratingsMeta = meta ?: Meta(
        id = imdbId ?: candidate.id,
        type = type,
        rawType = candidate.contentType,
        name = enrichment?.localizedTitle ?: candidate.title,
        poster = enrichment?.poster ?: candidate.poster,
        posterShape = PosterShape.POSTER,
        background = enrichment?.backdrop ?: candidate.backdrop,
        logo = enrichment?.logo ?: candidate.logo,
        description = enrichment?.description ?: candidate.description,
        releaseInfo = enrichment?.releaseInfo ?: candidate.releaseInfo,
        imdbRating = candidate.imdbRating,
        genres = enrichment?.genres ?: candidate.genres,
        runtime = enrichment?.runtimeMinutes?.toString() ?: candidate.runtime,
        director = emptyList(),
        cast = emptyList(),
        videos = emptyList(),
        country = null,
        awards = null,
        language = null,
        links = emptyList(),
        imdbId = imdbId
    )
    val (ratings, trailerSource) = coroutineScope {
        val ratingsJob = async {
            runCatching {
                withTimeoutOrNull(3_000L) {
                    mdbListRepository.getRatingsForMeta(
                        meta = ratingsMeta,
                        fallbackItemId = candidate.id,
                        fallbackItemType = candidate.contentType
                    )?.ratings
                }
            }.getOrNull()
        }
        val trailerJob = async {
            withTimeoutOrNull(15_000L) {
                trailerService.getTrailerPlaybackSource(
                    title = enrichment?.localizedTitle?.takeIf { it.isNotBlank() }
                        ?: meta?.name?.takeIf { it.isNotBlank() }
                        ?: candidate.title,
                    year = enrichment?.releaseInfo ?: meta?.releaseInfo ?: candidate.releaseInfo,
                    tmdbId = tmdbId,
                    type = candidate.contentType
                )
            }
        }
        ratingsJob.await() to trailerJob.await()
    }
    candidate.copy(
        playbackVideoId = imdbId ?: candidate.playbackVideoId,
        title = enrichment?.localizedTitle?.takeIf { it.isNotBlank() }
            ?: meta?.name?.takeIf { it.isNotBlank() }
            ?: candidate.title,
        backdrop = enrichment?.backdrop ?: meta?.backdropUrl ?: candidate.backdrop,
        poster = enrichment?.poster ?: meta?.poster ?: candidate.poster,
        logo = enrichment?.logo ?: meta?.logo ?: candidate.logo,
        description = enrichment?.description ?: meta?.description ?: candidate.description,
        releaseInfo = enrichment?.releaseInfo ?: meta?.releaseInfo ?: candidate.releaseInfo,
        genres = enrichment?.genres?.takeIf { it.isNotEmpty() }
            ?: meta?.genres?.takeIf { it.isNotEmpty() }
            ?: candidate.genres,
        runtime = enrichment?.runtimeMinutes?.toString() ?: meta?.runtime ?: candidate.runtime,
        imdbRating = meta?.imdbRating ?: candidate.imdbRating,
        tmdbRating = enrichment?.rating?.toFloat(),
        mdbListRatings = ratings,
        trailerVideoUrl = trailerSource?.videoUrl,
        trailerAudioUrl = trailerSource?.audioUrl,
        metadataResolved = true
    )
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Log.w(PlayerRuntimeController.TAG, "Post-play metadata failed: ${error.message}")
    candidate.copy(metadataResolved = true)
}

private fun PlayerRuntimeController.publishResolvedPostPlayRecommendation(
    index: Int,
    candidate: PostPlayRecommendation,
    enriched: PostPlayRecommendation
) {
    val selectResolved = pendingPostPlayRecommendationIndex == index
    _uiState.update { state ->
        val current = state.postPlayRecommendations.getOrNull(index)
        if (current?.id != candidate.id) return@update state
        state.copy(
            postPlayRecommendations = state.postPlayRecommendations.toMutableList().apply {
                this[index] = enriched
            },
            postPlayRecommendationIndex = if (selectResolved) index
                else state.postPlayRecommendationIndex
        )
    }
    if (selectResolved) pendingPostPlayRecommendationIndex = null
}

private fun PlayerRuntimeController.enrichPostPlayRecommendation(index: Int) {
    val candidate = _uiState.value.postPlayRecommendations.getOrNull(index) ?: return
    if (candidate.metadataResolved || recommendationMetadataJob?.isActive == true) return
    val expectedIdentity = recommendationIdentity()
    recommendationMetadataJob = scope.launch {
        val enriched = resolvePostPlayRecommendation(candidate)
        if (!isActive || recommendationIdentity() != expectedIdentity) return@launch
        publishResolvedPostPlayRecommendation(index, candidate, enriched)
    }
}

private fun PlayerRuntimeController.recommendationIdentity(): String = listOf(
    creditAnalysisIdentity,
    currentVideoId,
    currentSeason?.toString(),
    currentEpisode?.toString()
).joinToString("|")

internal fun PlayerRuntimeController.showAdjacentPostPlayRecommendation(direction: Int) {
    val state = _uiState.value
    if (state.postPlayRecommendations.isEmpty()) return
    val newIndex = (state.postPlayRecommendationIndex + direction)
        .coerceIn(0, state.postPlayRecommendations.lastIndex)
    if (newIndex == state.postPlayRecommendationIndex) return
    postPlayTrailerCountdownJob?.cancel()
    postPlayTrailerCountdownJob = null
    _uiState.update {
        it.copy(
            isPostPlayTrailerPlaying = false,
            postPlayTrailerCountdownSec = null
        )
    }
    val recommendation = state.postPlayRecommendations[newIndex]
    if (recommendation.metadataResolved) {
        pendingPostPlayRecommendationIndex = null
        _uiState.update { current ->
            current.copy(postPlayRecommendationIndex = newIndex)
        }
        return
    }

    pendingPostPlayRecommendationIndex = newIndex
    if (recommendationLoadJob?.isActive != true) {
        enrichPostPlayRecommendation(newIndex)
    }
}

internal fun PlayerRuntimeController.returnToPlayerFromPostPlay() {
    _uiState.update {
        it.copy(
            isPostPlayRecommendationVisible = false,
            postPlayRecommendationDismissed = true,
            manualEndActionRequested = false
        )
    }
}

internal fun PlayerRuntimeController.startPostPlayTrailer() {
    val state = _uiState.value
    if (state.isPostPlayTrailerPlaying || state.postPlayRecommendation?.hasTrailer != true) return
    postPlayTrailerCountdownJob?.cancel()
    postPlayTrailerCountdownJob = null
    releasePlayer()
    _uiState.update {
        it.copy(
            isPostPlayTrailerPlaying = true,
            hasPlayedPostPlayTrailer = true,
            postPlayTrailerCountdownSec = null
        )
    }
}

internal fun PlayerRuntimeController.stopPostPlayTrailer() {
    _uiState.update {
        it.copy(
            isPostPlayTrailerPlaying = false,
            hasPlayedPostPlayTrailer = true,
            postPlayTrailerCountdownSec = null
        )
    }
}

internal fun PlayerRuntimeController.schedulePostPlayTrailerAfterPlaybackEnded() {
    val state = _uiState.value
    if (!state.isPostPlayRecommendationVisible ||
        state.isPostPlayTrailerPlaying ||
        state.hasPlayedPostPlayTrailer ||
        state.postPlayRecommendation?.hasTrailer != true ||
        postPlayTrailerCountdownJob?.isActive == true
    ) {
        return
    }
    postPlayTrailerCountdownJob = scope.launch {
        val enabled = runCatching { trailerSettingsDataStore.settings.first().enabled }
            .getOrDefault(true)
        if (!enabled) return@launch
        for (seconds in 5 downTo 1) {
            _uiState.update { it.copy(postPlayTrailerCountdownSec = seconds) }
            delay(1_000L)
        }
        startPostPlayTrailer()
    }
}

private fun PlayerRuntimeController.isCreditAnalyzerStreamEligible(): Boolean {
    val uri = runCatching { Uri.parse(currentStreamUrl) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    if (currentHeaders.isNotEmpty()) return false
    val identityText = listOf(
        currentAddonName,
        _uiState.value.currentStreamName,
        currentStreamDescription,
        uri.host
    ).joinToString(" ").lowercase()
    return "pengu" !in identityText
}

private fun PlayerRuntimeController.buildCreditAnalyzerMediaKey(): String? {
    val size = currentVideoSize?.takeIf { it > 0L }
    currentInfoHash?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let { hash ->
        return "torrent:$hash:${currentFileIdx ?: -1}:${size ?: -1}"
    }
    currentVideoHash?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let { hash ->
        return "video:$hash:${size ?: -1}"
    }
    val filename = currentFilename?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
    val mediaId = currentVideoId ?: contentId
    if (filename == null && mediaId.isNullOrBlank() && size == null) return null
    val rawIdentity = listOfNotNull(mediaId, filename, size?.toString()).joinToString("|")
    return "release:${sha256(rawIdentity)}"
}

private fun PlayerRuntimeController.buildCreditAnalyzerContentKey(): String? =
    buildCreditAnalyzerContentKey(
        contentType = contentType,
        contentId = contentId,
        videoId = currentVideoId,
        season = currentSeason,
        episode = currentEpisode
    )

internal fun buildCreditAnalyzerContentKey(
    contentType: String?,
    contentId: String?,
    videoId: String?,
    season: Int?,
    episode: Int?
): String? {
    val normalizedType = contentType?.trim()?.lowercase()
    val stableId = (contentId ?: videoId)?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        ?: return null
    val rawIdentity = when (normalizedType) {
        "series", "tv", "show", "tvshow" -> {
            if (season == null || episode == null) return null
            "episode|$stableId|season:$season|episode:$episode"
        }
        "movie", "film" -> "movie|$stableId"
        else -> "other|$stableId"
    }
    return sha256(rawIdentity)
}

private fun PlayerRuntimeController.buildCreditAnalyzerTitle(): String {
    val episodeLabel = if (currentSeason != null && currentEpisode != null) {
        " S${currentSeason}E${currentEpisode}"
    } else {
        ""
    }
    return "${contentName ?: title}$episodeLabel".take(300)
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private const val MAX_POST_PLAY_RECOMMENDATIONS = 4
private const val MAX_POST_PLAY_RECOMMENDATION_CANDIDATES = 12
