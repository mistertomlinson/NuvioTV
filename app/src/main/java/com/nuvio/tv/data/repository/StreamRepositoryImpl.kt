package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.nuvio.tv.R
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.core.debrid.DebridStreamPresentation
import com.nuvio.tv.core.debrid.LocalDebridAvailabilityService
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.streams.StreamBadgePresentation
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.LocalScraperResult
import com.nuvio.tv.domain.model.PluginRepository
import com.nuvio.tv.domain.model.ProxyHeaders
import com.nuvio.tv.domain.model.ScraperInfo
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.StreamRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.net.URLEncoder
import javax.inject.Inject

private const val TAG = "StreamRepositoryImpl"

class StreamRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: AddonApi,
    private val addonRepository: AddonRepository,
    private val pluginManager: PluginManager,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val metaRepository: MetaRepository,
    private val debridStreamPresentation: DebridStreamPresentation,
    private val streamBadgePresentation: StreamBadgePresentation,
    private val localDebridAvailabilityService: LocalDebridAvailabilityService
) : StreamRepository {
    private enum class StreamFailureKind {
        MISSING,
        REQUEST_FAILED
    }

    private data class StreamAttemptFailure(
        val addonName: String,
        val kind: StreamFailureKind,
        val detail: String
    )

    private val seasonZeroCandidateCache =
        java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    override fun getStreamsFromAllAddons(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        seriesTitle: String?,
        episodeTitle: String?
    ): Flow<NetworkResult<List<AddonStreams>>> = flow {
        emit(NetworkResult.Loading)

        try {
            val addons = addonRepository.getInstalledAddons().first().enabledAddons()

            // Convert once for local plugins and Season-0 metadata resolution.
            val tmdbId = tmdbService.ensureTmdbId(videoId, type)
            Log.d(TAG, "Video ID: $videoId -> TMDB ID: $tmdbId (type: $type)")

            // Season 1+ keeps the exact existing single-ID behavior.
            // Season 0 resolves title-matched alternate numbering first.
            val candidateVideoIds = if (
                season == 0 &&
                !episodeTitle.isNullOrBlank()
            ) {
                resolveSeasonZeroVideoIds(
                    type = type,
                    videoId = videoId,
                    tmdbId = tmdbId,
                    seriesTitle = seriesTitle,
                    episodeTitle = episodeTitle
                )
            } else {
                listOf(videoId)
            }

            val streamAddons = addons.filter { addon ->
                candidateVideoIds.any { candidateId ->
                    addon.supportsStreamResource(type, candidateId)
                }
            }

            val pluginRequest = buildPluginRequest(tmdbId, type, videoId)
            val attemptedAddonNames = streamAddons.map { it.displayName }
            val attemptedFailures = java.util.Collections.synchronizedList(
                mutableListOf<StreamAttemptFailure>()
            )

            // Accumulate results as they arrive
            val accumulatedResults = mutableListOf<AddonStreams>()

            coroutineScope {
                // Channel to receive results as they complete
                val resultChannel = Channel<AddonStreams>(Channel.UNLIMITED)
                
                // Track number of pending jobs
                val totalJobs = streamAddons.size +
                    (if (pluginRequest != null) 1 else 0)
                val completedJobs = java.util.concurrent.atomic.AtomicInteger(0)

                // Launch addon jobs
                streamAddons.forEach { addon ->
                    launch {
                        try {
                            val streamsResult = if (
                                season == 0 &&
                                !episodeTitle.isNullOrBlank()
                            ) {
                                getSeasonZeroStreamsFromAddon(
                                    addon = addon,
                                    type = type,
                                    candidateVideoIds = candidateVideoIds,
                                    originalVideoId = videoId,
                                    episodeTitle = episodeTitle
                                )
                            } else {
                                getStreamsFromAddon(addon.baseUrl, type, videoId)
                            }
                            when (streamsResult) {
                                is NetworkResult.Success -> {
                                    if (streamsResult.data.isNotEmpty()) {
                                        val namedStreams = streamsResult.data.map {
                                            it.copy(addonName = addon.displayName, addonLogo = addon.logo)
                                        }
                                        resultChannel.send(
                                            AddonStreams(
                                                addonName = addon.displayName,
                                                addonLogo = addon.logo,
                                                streams = namedStreams
                                            )
                                        )
                                    } else {
                                        // Stream endpoint returned empty - try inline
                                        // streams from meta response as fallback.
                                        val inlineStreams = fetchInlineStreamsFromMeta(
                                            addon, type, videoId
                                        )
                                        if (inlineStreams.isNotEmpty()) {
                                            resultChannel.send(
                                                AddonStreams(
                                                    addonName = addon.displayName,
                                                    addonLogo = addon.logo,
                                                    streams = inlineStreams
                                                )
                                            )
                                        } else {
                                            attemptedFailures += buildMissingStreamFailure(addon)
                                        }
                                    }
                                }
                                is NetworkResult.Error -> {
                                    attemptedFailures += buildAddonFailure(addon, streamsResult)
                                }
                                NetworkResult.Loading -> Unit
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Log.e(TAG, "Addon ${addon.name} failed: ${e.message}")
                            attemptedFailures += StreamAttemptFailure(
                                addonName = addon.displayName,
                                kind = StreamFailureKind.REQUEST_FAILED,
                                detail = e.message ?: context.getString(com.nuvio.tv.R.string.stream_error_detail_addon_request_failed)
                            )
                        } finally {
                            if (completedJobs.incrementAndGet() >= totalJobs) {
                                resultChannel.close()
                            }
                        }
                    }
                }

                // Launch plugin jobs if we have a supported plugin id - each scraper sends its own result
                if (pluginRequest != null) {
                    launch {
                        try {
                            // Stream plugins individually
                            streamLocalPlugins(
                                pluginId = pluginRequest.id,
                                mediaType = pluginRequest.mediaType,
                                pluginSource = pluginRequest.source,
                                season = season,
                                episode = episode,
                                resultChannel = resultChannel
                            ) {
                                if (completedJobs.incrementAndGet() >= totalJobs) {
                                    resultChannel.close()
                                }
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Log.e(TAG, "Plugin execution failed: ${e.message}")
                            if (completedJobs.incrementAndGet() >= totalJobs) {
                                resultChannel.close()
                            }
                        }
                    }
                }

                // Handle case where there are no jobs
                if (totalJobs == 0) {
                    resultChannel.close()
                }

                // Emit results as they arrive
                for (result in resultChannel) {
                    val validatedResult = if (season == 0) {
                        val credibleStreams = result.streams.filter { stream ->
                            stream.isCredibleSeasonZeroResult(episodeTitle)
                        }

                        if (credibleStreams.size != result.streams.size) {
                            Log.d(
                                TAG,
                                "Season0 rejected ${result.streams.size - credibleStreams.size} " +
                                    "wrong-season stream(s) from ${result.addonName}"
                            )
                        }

                        if (credibleStreams.isEmpty()) {
                            continue
                        }

                        result.copy(streams = credibleStreams)
                    } else {
                        result
                    }

                    // Existing Connected Services/debrid availability path remains
                    // downstream and optional. Discovery above does not depend on it.
                    val checkingResult =
                        localDebridAvailabilityService.markChecking(listOf(validatedResult))
                            .firstOrNull() ?: validatedResult

                    val checkedResult =
                        localDebridAvailabilityService.annotateCachedAvailability(listOf(checkingResult))
                            .firstOrNull() ?: checkingResult

                    mergePresentedResult(accumulatedResults, checkedResult)
                    emit(NetworkResult.Success(accumulatedResults.toList()))

                    Log.d(
                        TAG,
                        "Emitted ${accumulatedResults.size} addon(s), latest: " +
                            "${checkedResult.addonName} with ${checkedResult.streams.size} streams"
                    )
                }
            }

            // Emit final result (even if empty)
            if (accumulatedResults.isEmpty()) {
                val errorMessage = buildAggregateFailureMessage(
                    type = type,
                    id = videoId,
                    attemptedAddonNames = attemptedAddonNames,
                    failures = attemptedFailures.toList()
                )
                if (errorMessage != null) {
                    emit(NetworkResult.Error(errorMessage))
                } else {
                    emit(NetworkResult.Success(emptyList()))
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to fetch streams: ${e.message}", e)
            emit(NetworkResult.Error(e.message ?: context.getString(com.nuvio.tv.R.string.stream_error_fetch_failed)))
        }
    }

    private data class PluginRequest(
        val id: String,
        val mediaType: String,
        val source: String
    )

    private fun buildPluginRequest(tmdbId: String?, type: String, videoId: String): PluginRequest? {
        if (tmdbId != null) {
            return PluginRequest(
                id = tmdbId,
                mediaType = normalizeTmdbPluginType(type),
                source = "TMDB"
            )
        }

        if (!videoId.canRunLocalPlugins()) return null

        return PluginRequest(
            id = if (videoId.startsWith("kitsu:", ignoreCase = true)) {
                cleanKitsuPluginId(videoId)
            } else {
                videoId
            },
            mediaType = type.lowercase(),
            source = videoId.substringBefore(":").uppercase()
        )
    }

    private fun normalizeTmdbPluginType(type: String): String {
        return when (type.lowercase()) {
            "series", "tv", "show" -> "tv"
            else -> type.lowercase()
        }
    }

    private fun cleanKitsuPluginId(videoId: String): String {
        val parts = videoId.split(":")
        return if (parts.size > 2 && parts.last().toIntOrNull() != null) {
            parts.dropLast(1).joinToString(":")
        } else {
            videoId
        }
    }

    private suspend fun mergePresentedResult(
        accumulatedResults: MutableList<AddonStreams>,
        result: AddonStreams
    ) {
        val existingIndex = accumulatedResults.indexOfFirst { it.addonName == result.addonName }
        if (existingIndex >= 0) {
            val existing = accumulatedResults[existingIndex]
            val merged = existing.copy(
                streams = mergeStreams(existing.streams, result.streams)
            )
            accumulatedResults[existingIndex] = presentStreams(merged)
        } else {
            accumulatedResults.add(presentStreams(result))
        }
    }

    private suspend fun presentStreams(result: AddonStreams): AddonStreams {
        val debridPresented = debridStreamPresentation.apply(listOf(result)).firstOrNull() ?: result
        return streamBadgePresentation.apply(listOf(debridPresented)).firstOrNull() ?: debridPresented
    }

    private fun mergeStreams(existing: List<Stream>, incoming: List<Stream>): List<Stream> {
        val streamsByKey = LinkedHashMap<String, Stream>()
        existing.forEach { stream -> streamsByKey[stream.dedupKey()] = stream }
        incoming.forEach { stream -> streamsByKey[stream.dedupKey()] = stream }
        return streamsByKey.values.toList()
    }

    /**
     * Stream local plugin results - each scraper sends results individually
     */
    private fun String.canRunLocalPlugins(): Boolean {
        return startsWith("kitsu:", ignoreCase = true) ||
            startsWith("anilist:", ignoreCase = true) ||
            startsWith("mal:", ignoreCase = true)
    }

    private suspend fun streamLocalPlugins(
        pluginId: String,
        mediaType: String,
        pluginSource: String,
        season: Int?,
        episode: Int?,
        resultChannel: Channel<AddonStreams>,
        onComplete: () -> Unit
    ) {
        // Check if plugins are enabled
        if (!pluginManager.pluginsEnabled.first()) {
            Log.d(TAG, "Plugins are disabled")
            onComplete()
            return
        }

        Log.d(TAG, "Streaming plugins for $pluginSource: $pluginId, type: $mediaType")

        try {
            val groupByRepository = pluginManager.groupStreamsByRepository.first()
            val repositoriesById = if (groupByRepository) {
                pluginManager.repositories.first().associateBy { it.id }
            } else {
                emptyMap()
            }

            // Collect streaming results from each scraper
            pluginManager.executeScrapersStreaming(
                tmdbId = pluginId,
                mediaType = mediaType,
                season = season,
                episode = episode
            ).collect { (scraperName, results) ->
                if (results.isNotEmpty()) {
                    val addonName = scraperName
                    val addonStreams = AddonStreams(
                        addonName = addonName,
                        addonLogo = null,
                        streams = results.map { result -> result.toPluginStream(addonName) }
                    )
                    resultChannel.send(addonStreams)
                    Log.d(TAG, "Streamed ${results.size} results from $addonName")
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to stream plugins: ${e.message}", e)
        } finally {
            onComplete()
        }
    }

private fun LocalScraperResult.toPluginStream(addonName: String): Stream {
        val baseTitle = title.takeIf { it.isNotBlank() }
        val baseName = name?.takeIf { it.isNotBlank() }
        val quality = quality?.takeIf { it.isNotBlank() }
        val qualityLabel = quality ?: context.getString(com.nuvio.tv.R.string.stream_quality_unknown)
        val displayName = buildString {
            append(baseName ?: baseTitle ?: addonName)
            if (!toString().contains(qualityLabel)) {
                append(" - ").append(qualityLabel)
            }
        }.takeIf { it.isNotBlank() }
        val displayTitle = (baseTitle ?: baseName ?: addonName).takeIf { it.isNotBlank() }

        return Stream(
            name = displayName,
            title = displayTitle,
            url = url,
            addonName = addonName,
            addonLogo = null,
            description = buildDescription(this),
            behaviorHints = headers?.let { headers ->
                StreamBehaviorHints(
                    notWebReady = null,
                    bingeGroup = null,
                    countryWhitelist = null,
                    proxyHeaders = ProxyHeaders(request = headers, response = null)
                )
            },
            infoHash = infoHash,
            fileIdx = null,
            ytId = null,
            externalUrl = null,
            quality = quality,
            qualityValue = parseQualityValue(quality)
        )
    }

    private fun Stream.dedupKey(): String {
        val providerSuffix = debridCacheStatus?.providerId?.let { ":$it" } ?: ""
        return (infoHash?.lowercase()?.let { hash -> "$hash:${fileIdx ?: ""}$providerSuffix" }
            ?: clientResolve?.infoHash?.lowercase()?.let { hash -> "$hash:${clientResolve.fileIdx}$providerSuffix" }
            ?: url
            ?: externalUrl
            ?: ytId
            ?: "${addonName}:${name}:${title}")
    }

    /**
     * Build a description string from scraper result
     */
    private fun buildDescription(result: com.nuvio.tv.domain.model.LocalScraperResult): String? {
        // Quality is shown in the stream name — only show size/language in description
        val parts = mutableListOf<String>()
        result.size?.let { parts.add(it) }
        result.language?.let { parts.add(it) }
        return if (parts.isNotEmpty()) parts.joinToString(" • ") else null
    }

    private fun parseQualityValue(quality: String?): Int {
        if (quality == null) return -1
        val lower = quality.lowercase()
        return when {
            lower.contains("4k") || lower.contains("2160") -> 2160
            lower.contains("1080") -> 1080
            lower.contains("800") -> 800
            lower.contains("720") -> 720
            lower.contains("480") -> 480
            lower.contains("360") -> 360
            else -> -1
        }
    }

    private suspend fun resolveSeasonZeroVideoIds(
        type: String,
        videoId: String,
        tmdbId: String?,
        seriesTitle: String?,
        episodeTitle: String
    ): List<String> {
        val normalizedTitle = normalizeSpecialTitle(episodeTitle)
        if (normalizedTitle.isBlank()) return listOf(videoId)

        val baseId = seasonZeroBaseId(videoId)
        val cacheKey = "$baseId|$normalizedTitle"

        seasonZeroCandidateCache[cacheKey]?.let { cached ->
            Log.d(
                TAG,
                "Season0 candidate cache hit show=${seriesTitle.orEmpty()} " +
                    "title=$episodeTitle ids=${cached.joinToString()}"
            )
            return cached
        }

        val discoveredIds = kotlinx.coroutines.withTimeoutOrNull(2_500L) {
            coroutineScope {
                val addonVideosDeferred = async {
                    try {
                        metaRepository.getSeasonZeroVideosFromAllAddons(
                            type = type,
                            id = baseId
                        )
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.d(TAG, "Season0 metadata-addon lookup failed: ${e.message}")
                        emptyList()
                    }
                }

                val tmdbEpisodesDeferred = async {
                    if (tmdbId.isNullOrBlank()) {
                        emptyMap()
                    } else {
                        try {
                            tmdbMetadataService.fetchEpisodeEnrichment(
                                tmdbId = tmdbId,
                                seasonNumbers = listOf(0)
                            )
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Log.d(TAG, "Season0 TMDB lookup failed: ${e.message}")
                            emptyMap()
                        }
                    }
                }

                buildList {
                    addonVideosDeferred.await()
                        .filter { specialTitlesMatch(episodeTitle, it.title) }
                        .forEach { video ->
                            if (video.id.isNotBlank()) add(video.id)

                            video.episode?.let { alternateEpisode ->
                                add("$baseId:0:$alternateEpisode")
                            }
                        }

                    tmdbEpisodesDeferred.await().forEach { (key, enrichment) ->
                        if (
                            key.first == 0 &&
                            !enrichment.title.isNullOrBlank() &&
                            specialTitlesMatch(episodeTitle, enrichment.title)
                        ) {
                            add("$baseId:0:${key.second}")
                        }
                    }
                }
            }
        }.orEmpty()

        // Original ID remains a concurrent safety candidate; there is no
        // sequential "try original, then fallback" delay.
        val candidates = (discoveredIds + videoId)
            .filter { it.isNotBlank() }
            .distinct()

        seasonZeroCandidateCache[cacheKey] = candidates

        Log.d(
            TAG,
            "Season0 resolved show=${seriesTitle.orEmpty()} title=$episodeTitle " +
                "original=$videoId candidates=${candidates.joinToString()}"
        )

        return candidates
    }

    private suspend fun getSeasonZeroStreamsFromAddon(
        addon: Addon,
        type: String,
        candidateVideoIds: List<String>,
        originalVideoId: String,
        episodeTitle: String
    ): NetworkResult<List<Stream>> = coroutineScope {
        val supportedIds = candidateVideoIds.filter { candidateId ->
            addon.supportsStreamResource(type, candidateId)
        }

        if (supportedIds.isEmpty()) {
            return@coroutineScope NetworkResult.Success(emptyList())
        }

        /*
         * When title resolution found an alternate Season-0 ID, the original
         * ID becomes a compatibility fallback rather than unquestioned truth.
         *
         * Example:
         *   Nuvio metadata:    Taskmaster NY Treat 2023 -> S00E03
         *   alternate source:  Taskmaster NY Treat 2023 -> S00E85
         *
         * Torrentio's S00E03 endpoint can legitimately describe a completely
         * different special. Alternate IDs were discovered by matching the
         * requested special title, so they are trusted candidates. Streams
         * from the original fallback are retained only when their release
         * text also identifies the requested special.
         *
         * This intentionally does NOT require an S00E## token. A release such
         * as "Sherlock.The.Abominable.Bride.1080p" remains valid.
         */
        val hasAlternateCandidate = supportedIds.any { it != originalVideoId }

        val streams = supportedIds.map { candidateId ->
            async {
                val fetched = when (
                    val result = getStreamsFromAddon(
                        baseUrl = addon.baseUrl,
                        type = type,
                        videoId = candidateId
                    )
                ) {
                    is NetworkResult.Success -> {
                        if (result.data.isNotEmpty()) {
                            result.data
                        } else {
                            fetchInlineStreamsFromMeta(
                                addon = addon,
                                type = type,
                                videoId = candidateId
                            )
                        }
                    }

                    is NetworkResult.Error -> emptyList()
                    NetworkResult.Loading -> emptyList()
                }

                if (
                    candidateId == originalVideoId &&
                    hasAlternateCandidate
                ) {
                    val filtered = fetched.filter { stream ->
                        stream.matchesRequestedSeasonZeroTitle(episodeTitle)
                    }

                    if (filtered.size != fetched.size) {
                        Log.d(
                            TAG,
                            "Season0 original-fallback rejected " +
                                "${fetched.size - filtered.size} stream(s) " +
                                "addon=${addon.displayName} id=$candidateId " +
                                "title=$episodeTitle"
                        )
                    }

                    filtered
                } else {
                    fetched
                }
            }
        }.awaitAll()
            .flatten()
            .distinctBy { it.dedupKey() }

        NetworkResult.Success(streams)
    }

    private fun Stream.matchesRequestedSeasonZeroTitle(
        requestedTitle: String
    ): Boolean {
        val requested = normalizeSpecialTitle(requestedTitle)
        if (requested.isBlank()) return true

        val resolve = clientResolve
        val parsed = resolve?.stream?.raw?.parsed

        val fields = listOfNotNull(
            behaviorHints?.filename,
            resolve?.filename,
            resolve?.torrentName,
            resolve?.title,
            resolve?.stream?.raw?.filename,
            resolve?.stream?.raw?.torrentName,
            parsed?.rawTitle,
            parsed?.parsedTitle,
            title,
            description
        )

        return fields.any { field ->
            val normalizedField = normalizeSpecialTitle(field)

            // Strongest signal: requested special title appears intact in
            // the release text. Check this before fuzzy identity logic so a
            // parent pack name containing unrelated numbering cannot poison
            // an otherwise exact title match.
            (
                requested.length >= 8 &&
                normalizedField.contains(requested)
            ) ||
                specialTitlesMatch(requestedTitle, field)
        }
    }

    private fun Stream.isCredibleSeasonZeroResult(
        requestedTitle: String?
    ): Boolean {
        val resolve = clientResolve
        val parsed = resolve?.stream?.raw?.parsed

        val structuredSeasons = buildList {
            resolve?.season?.let(::add)
            parsed?.seasons?.let(::addAll)
        }.distinct()

        val structuredEpisodes = buildList {
            resolve?.episode?.let(::add)
            parsed?.episodes?.let(::addAll)
        }.distinct()

        /*
         * Scene/P2P releases often represent specials as SxxE00 rather than
         * Season 0. Examples we have verified:
         *
         *   Beast Games special -> S01E00
         *   Taskmaster special  -> S14E00
         *
         * E00 is therefore strong "special" evidence even when the season
         * component is non-zero. A normal S01E02/S14E03 etc. remains wrong.
         */
        val structuredLooksLikeSpecial =
            structuredSeasons.any { it == 0 } ||
                structuredEpisodes.any { it == 0 }

        if (
            structuredSeasons.isNotEmpty() &&
            structuredSeasons.none { it == 0 } &&
            !structuredLooksLikeSpecial
        ) {
            return false
        }

        val searchable = listOfNotNull(
            name,
            title,
            description,
            behaviorHints?.filename,
            resolve?.title,
            resolve?.torrentName,
            resolve?.filename,
            resolve?.stream?.raw?.torrentName,
            resolve?.stream?.raw?.filename,
            parsed?.rawTitle,
            parsed?.parsedTitle
        ).joinToString(" ")

        /*
         * Accept:
         *   S00E85  -> canonical Season-0 notation
         *   S01E00  -> season-associated special
         *   S14E00  -> season-associated special
         *
         * Reject:
         *   S01E02
         *   S14E03
         *
         * Releases with no S/E token remain allowed.
         */
        val explicitEpisodes = Regex(
            """(?i)(?:^|[^a-z0-9])s0*(\d{1,2})e0*(\d{1,3})(?:[^a-z0-9]|$)"""
        ).findAll(searchable)
            .mapNotNull { match ->
                val seasonNumber =
                    match.groupValues.getOrNull(1)?.toIntOrNull()
                        ?: return@mapNotNull null
                val episodeNumber =
                    match.groupValues.getOrNull(2)?.toIntOrNull()
                        ?: return@mapNotNull null

                seasonNumber to episodeNumber
            }
            .toList()

        if (
            explicitEpisodes.isNotEmpty() &&
            explicitEpisodes.none { (seasonNumber, episodeNumber) ->
                seasonNumber == 0 || episodeNumber == 0
            }
        ) {
            return false
        }

        /*
         * Scene releases sometimes encode specials as S01E00, S14E00, etc.
         * E00 tells us that it is special-like, but not WHICH special it is.
         *
         * When acceptance depends on a non-zero-season E00, require the
         * release text to identify the requested special. Canonical S00
         * releases and title-only releases keep their existing behavior.
         */
        val hasCanonicalSeasonZero =
            structuredSeasons.any { it == 0 } ||
                explicitEpisodes.any { (seasonNumber, _) ->
                    seasonNumber == 0
                }

        val hasSceneStyleSpecial =
            (
                structuredSeasons.any { it != 0 } &&
                    structuredEpisodes.any { it == 0 }
            ) ||
                explicitEpisodes.any { (seasonNumber, episodeNumber) ->
                    seasonNumber != 0 && episodeNumber == 0
                }

        if (
            !hasCanonicalSeasonZero &&
            hasSceneStyleSpecial &&
            !requestedTitle.isNullOrBlank() &&
            !matchesRequestedSeasonZeroTitle(requestedTitle)
        ) {
            return false
        }

        /*
         * Some valid special releases carry no SxxExx token at all and are
         * identified only by their release/title text. Preserve those, but
         * require that text to identify the requested special. This also
         * rejects addon utility/action streams and temporary placeholders
         * that contain neither episode identity nor the requested title.
         */
        val hasNoEpisodeIdentity =
            structuredSeasons.isEmpty() &&
                structuredEpisodes.isEmpty() &&
                explicitEpisodes.isEmpty()

        if (
            hasNoEpisodeIdentity &&
            !requestedTitle.isNullOrBlank() &&
            !matchesRequestedSeasonZeroTitle(requestedTitle)
        ) {
            return false
        }

        return true
    }

    private fun seasonZeroBaseId(videoId: String): String {
        val clean = videoId.substringBefore('/')
        val parts = clean.split(":")

        return if (
            parts.size >= 3 &&
            parts[parts.lastIndex].toIntOrNull() != null &&
            parts[parts.lastIndex - 1].toIntOrNull() != null
        ) {
            parts.dropLast(2).joinToString(":")
        } else {
            clean
        }
    }

    private fun normalizeSpecialTitle(value: String): String =
        value.lowercase()
        .replace(Regex("""(?<=\d),(?=\d)"""), "")
            .replace("&", " and ")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")

    /**
     * Numbers that materially identify a special.
     *
     * Examples:
     * - "New Year Treat 2023" must not match 2021/2024.
     * - "Episode 4" must not fuzzy-match "Episode 5".
     * - "Episodes 1-3" must preserve both endpoint numbers.
     *
     * Ordinary incidental numbers are deliberately not harvested unless they
     * are a four-digit year or follow an episode/part/series-style label.
     */
    private fun specialIdentityNumbers(value: String): Set<String> {
        val identities = linkedSetOf<String>()
        val normalized = normalizeSpecialTitle(value)

        Regex("""\b(?:19|20)\d{2}\b""")
            .findAll(normalized)
            .forEach { match ->
                identities += "year:${match.value}"
            }

        Regex(
            """(?i)\b(?:episode|episodes|ep|part|pt|chapter|chapters|volume|vol|season|series)\s*#?\s*(\d{1,3})(?:\s*(?:-|–|—|&|and|to)\s*(\d{1,3}))?"""
        ).findAll(value).forEach { match ->
            match.groupValues
                .drop(1)
                .filter { it.isNotBlank() }
                .mapNotNull { it.toIntOrNull() }
                .forEach { number ->
                    identities += "seq:$number"
                }
        }

        return identities
    }

    private fun specialTitlesMatch(
        requested: String,
        candidate: String
    ): Boolean {
        val left = normalizeSpecialTitle(requested)
        val right = normalizeSpecialTitle(candidate)

        if (left.isBlank() || right.isBlank()) return false

        // Explicit identity numbers are decisive. This check comes before
        // containment/fuzzy matching so 2023 cannot match 2021/2024/yearless,
        // and Episode 4 cannot match Episode 5.
        val leftIdentity = specialIdentityNumbers(requested)
        val rightIdentity = specialIdentityNumbers(candidate)

        if (
            (leftIdentity.isNotEmpty() || rightIdentity.isNotEmpty()) &&
            leftIdentity != rightIdentity
        ) {
            return false
        }

        if (left == right) return true

        if (
            left.length >= 8 &&
            right.length >= 8 &&
            (left.contains(right) || right.contains(left))
        ) {
            return true
        }

        val ignored = setOf(
            "a", "an", "the", "of", "and",
            "special", "episode", "episodes"
        )

        val leftTokens = left.split(" ")
            .filter { it.length > 1 && it !in ignored }
            .toSet()

        val rightTokens = right.split(" ")
            .filter { it.length > 1 && it !in ignored }
            .toSet()

        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return false

        val overlap = leftTokens.intersect(rightTokens).size.toDouble()
        val dice = (2.0 * overlap) /
            (leftTokens.size + rightTokens.size).toDouble()

        return dice >= 0.72
    }

    override suspend fun getStreamsFromAddon(
        baseUrl: String,
        type: String,
        videoId: String
    ): NetworkResult<List<Stream>> {
        val cleanBaseUrl = baseUrl.trimEnd('/')
        val queryStart = cleanBaseUrl.indexOf('?')
        val basePath = if (queryStart >= 0) cleanBaseUrl.substring(0, queryStart).trimEnd('/') else cleanBaseUrl
        val baseQuery = if (queryStart >= 0) cleanBaseUrl.substring(queryStart) else ""
        val encodedType = encodePathSegment(type)
        val encodedVideoId = encodePathSegment(videoId)
        val streamUrl = "$basePath/stream/$encodedType/$encodedVideoId.json$baseQuery"
        Log.d(TAG, "Fetching streams type=$type videoId=$videoId url=$streamUrl")

        // First, get addon info for name and logo
        val addonResult = addonRepository.fetchAddon(baseUrl)
        val addonName = when (addonResult) {
            is NetworkResult.Success -> addonResult.data.displayName
            else -> context.getString(com.nuvio.tv.R.string.stream_addon_unknown)
        }
        val addonLogo = when (addonResult) {
            is NetworkResult.Success -> addonResult.data.logo
            else -> null
        }

        return when (val result = safeApiCall { api.getStreams(streamUrl) }) {
            is NetworkResult.Success -> {
                val streams = result.data.streams?.map { 
                    it.toDomain(addonName, addonLogo) 
                } ?: emptyList()
                Log.d(TAG, "Streams success addon=$addonName count=${streams.size} url=$streamUrl")
                NetworkResult.Success(streams)
            }
            is NetworkResult.Error -> {
                Log.w(
                    TAG,
                    "Streams failed addon=$addonName code=${result.code} message=${result.message} url=$streamUrl"
                )
                result
            }
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    /**
     * Check if addon supports stream resource for the given type and video id.
     * Respects the resource-level idPrefixes declared in the addon manifest,
     * falling back to the top-level addon idPrefixes if the resource doesn't
     * declare its own.
     */
    private fun Addon.supportsStreamResource(type: String, videoId: String): Boolean {
        return resources.any { resource ->
            resource.name == "stream" &&
            (resource.types.isEmpty() || resource.types.contains(type)) &&
            run {
                val prefixes = resource.idPrefixes?.takeIf { it.isNotEmpty() }
                    ?: idPrefixes.takeIf { it.isNotEmpty() }
                prefixes == null || prefixes.any { prefix -> videoId.startsWith(prefix) }
            }
        }
    }

    /**
     * Fetch meta for the given content and extract inline streams from the
     * matching video entry.  Returns an empty list when the addon doesn't
     * support meta or the video has no inline streams.
     */
    private suspend fun fetchInlineStreamsFromMeta(
        addon: Addon,
        type: String,
        videoId: String
    ): List<Stream> {
        // For inline streams the meta is fetched using the content-level ID
        // (everything before the video-specific suffix).  For "other" type
        // the videoId IS the content ID; for series it is contentId:S:E.
        val contentId = videoId.substringBefore(":")
            .takeIf { it.isNotBlank() }
            ?: videoId
        // Reconstruct a content-level ID that keeps the addon-specific prefix.
        // e.g. "realdebrid:ABC:3" → "realdebrid:ABC"
        val metaId = run {
            val parts = videoId.split(":")
            // Drop trailing numeric segment(s) that represent video index
            val contentParts = parts.dropLastWhile { it.toIntOrNull() != null }
            if (contentParts.isNotEmpty()) contentParts.joinToString(":") else videoId
        }
        val cleanBaseUrl = addon.baseUrl.trimEnd('/')
        val queryStart = cleanBaseUrl.indexOf('?')
        val basePath = if (queryStart >= 0) cleanBaseUrl.substring(0, queryStart).trimEnd('/') else cleanBaseUrl
        val baseQuery = if (queryStart >= 0) cleanBaseUrl.substring(queryStart) else ""
        val encodedType = encodePathSegment(type)
        val encodedMetaId = encodePathSegment(metaId)
        val metaUrl = "$basePath/meta/$encodedType/$encodedMetaId.json$baseQuery"
        Log.d(TAG, "Fetching inline streams via meta type=$type metaId=$metaId videoId=$videoId url=$metaUrl")
        return try {
            when (val result = safeApiCall { api.getMeta(metaUrl) }) {
                is NetworkResult.Success -> {
                    val metaDto = result.data.meta ?: return emptyList()
                    val matchingVideo = metaDto.videos?.firstOrNull { it.id == videoId }
                    val streams = matchingVideo?.streams
                        ?.mapNotNull { streamDto -> streamDto.toDomain(addon.displayName, addon.logo) }
                        ?: emptyList()
                    Log.d(TAG, "Inline streams from meta: addon=${addon.displayName} videoId=$videoId found=${streams.size}")
                    streams
                }
                else -> emptyList()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Failed to fetch inline streams from meta for ${addon.displayName}: ${e.message}")
            emptyList()
        }
    }

    private fun buildMissingStreamFailure(addon: Addon): StreamAttemptFailure {
        return StreamAttemptFailure(
            addonName = addon.displayName,
            kind = StreamFailureKind.MISSING,
            detail = context.getString(com.nuvio.tv.R.string.stream_error_detail_no_streams_for_id)
        )
    }

    private fun buildAddonFailure(addon: Addon, error: NetworkResult.Error): StreamAttemptFailure {
        if (error.code == 404 || error.message.equals("Not Found", ignoreCase = true)) {
            return buildMissingStreamFailure(addon)
        }
        val normalizedReason = when {
            error.message.contains("Unable to resolve host", ignoreCase = true) ->
                context.getString(com.nuvio.tv.R.string.stream_error_detail_addon_unreachable)
            error.message.contains("Failed to connect", ignoreCase = true) ->
                context.getString(com.nuvio.tv.R.string.stream_error_detail_addon_connection_failed)
            error.message.contains("timeout", ignoreCase = true) ->
                context.getString(com.nuvio.tv.R.string.stream_error_detail_addon_timeout)
            error.message.contains("CLEARTEXT communication", ignoreCase = true) ->
                context.getString(com.nuvio.tv.R.string.stream_error_detail_addon_cleartext_blocked)
            error.message.isBlank() ->
                context.getString(com.nuvio.tv.R.string.stream_error_detail_addon_request_failed)
            else -> error.message.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase() else char.toString()
            }
        }
        val httpSuffix = error.code?.let { " (HTTP $it)" } ?: ""
        return StreamAttemptFailure(
            addonName = addon.displayName,
            kind = StreamFailureKind.REQUEST_FAILED,
            detail = "$normalizedReason$httpSuffix"
        )
    }

    private fun buildAggregateFailureMessage(
        type: String,
        id: String,
        attemptedAddonNames: List<String>,
        failures: List<StreamAttemptFailure>
    ): String? {
        if (attemptedAddonNames.isEmpty()) {
            return context.getString(R.string.error_stream_no_supported_addon, type)
        }

        val triedAddons = attemptedAddonNames.joinToString(", ")
        val missingOnly = failures.isNotEmpty() && failures.all { it.kind == StreamFailureKind.MISSING }
        if (failures.isEmpty() || missingOnly) {
            return context.getString(R.string.error_stream_tried_none, triedAddons, id, type)
        }

        val issueSummary = failures
            .filter { it.kind == StreamFailureKind.REQUEST_FAILED }
            .distinctBy { it.addonName to it.detail }
            .take(3)
            .joinToString("; ") { "${it.addonName}: ${it.detail}" }

        return if (issueSummary.isBlank()) {
            context.getString(R.string.error_stream_tried_generic, triedAddons, id, type)
        } else {
            context.getString(R.string.error_stream_tried_issues, triedAddons, id, type, issueSummary)
        }
    }

    private fun encodePathSegment(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
