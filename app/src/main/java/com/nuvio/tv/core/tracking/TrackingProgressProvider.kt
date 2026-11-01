package com.nuvio.tv.core.tracking

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf

interface TrackingProgressProvider {
    val providerId: TrackingProviderId
    val isAuthenticated: Flow<Boolean>
    val allProgress: Flow<List<WatchProgress>>
    val remoteProgressLoaded: Flow<Boolean>

    /*
     * Optional identity stamp for providers whose in-memory projection is
     * profile-scoped.
     *
     * A non-null Flow means the provider supports profile stamping.
     * An emitted null means a profile handoff is currently in progress.
     */
    val projectionProfileId: Flow<Int?>?
        get() = null

    val nextUpSeeds: Flow<List<WatchProgress>>
    val watchedMovieIds: Flow<Set<String>>
    val ownsCompletedHistoryProjection: Boolean
        get() = false
    val watchedItems: Flow<List<WatchedItem>>
        get() = flowOf(emptyList())

    fun episodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>>
    fun airedEpisodeOrder(contentId: String): Flow<List<Pair<Int, Int>>>
    fun isWatched(
        contentId: String,
        videoId: String?,
        season: Int?,
        episode: Int?
    ): Flow<Boolean>
    suspend fun watchedShowEpisodes(): Map<String, Set<Pair<Int, Int>>>
    suspend fun showIdSiblings(): Map<String, Set<String>>
    fun isWatchedByVideoId(videoId: String, episode: Int): Boolean = false
    suspend fun refresh(intent: TrackingRefreshIntent)
    suspend fun persistDurableProgress(progress: WatchProgress) = Unit
    suspend fun persistDurableProgressBatch(progressList: List<WatchProgress>) {
        progressList.forEach { progress -> persistDurableProgress(progress) }
    }
    suspend fun removeProgress(contentId: String, season: Int?, episode: Int?)
    suspend fun dismissNextUp(contentId: String, season: Int?, episode: Int?) = Unit
    fun applyOptimisticProgress(progress: WatchProgress, quiet: Boolean)
    fun applyOptimisticRemoval(
        contentId: String,
        videoId: String?,
        season: Int?,
        episode: Int?
    )
    fun clearOptimisticRemoval(
        contentId: String,
        videoId: String?,
        season: Int?,
        episode: Int?
    ) = Unit
    /*
     * Drop only a temporary optimistic playback/completion projection.
     *
     * This must not alter durable watched history, remote history, or
     * provider-level Next Up dismissal state.
     */
    fun clearOptimisticProgress(
        contentId: String,
        season: Int?,
        episode: Int?
    ) = Unit

    fun clearOptimistic()
    fun retainsLocalProgress(contentId: String): Boolean = false
    fun retainsLocalWatchedEpisode(item: WatchedItem): Boolean = false
    fun isHiddenFromProgress(contentId: String): Boolean
    fun continueWatchingCutoffEpochMs(daysCap: Int, nowEpochMs: Long): Long? = null
    fun shouldUseAsNextUpSeed(progress: WatchProgress, nowEpochMs: Long): Boolean =
        progress.isCompleted()

    /*
     * Provider-backed history may identify one show with a different external
     * ID than the addon/player used (for example TMDB locally and IMDb in the
     * provider projection). Resolve the parent to the ID already used by the
     * provider's Next Up seed before optimistic completion enters the shared
     * pipeline. Otherwise the stale provider seed and the fresh Player finale
     * can coexist as two different shows and an old cached Next Up can survive.
     *
     * Providers with stronger identity rules (Trakt) may override this.
     */
    suspend fun normalizeParentContentId(
        parentContentId: String,
        videoId: String?
    ): String {
        val siblings = showIdSiblings()
        if (siblings.isEmpty()) return parentContentId

        val providerSeedIds = nextUpSeeds.first().map(WatchProgress::contentId)
        return resolveProviderNextUpParentContentId(
            parentContentId = parentContentId,
            showIdSiblings = siblings,
            providerSeedContentIds = providerSeedIds
        )
    }

    suspend fun prepareNextUpSeed(progress: WatchProgress): WatchProgress
}

internal fun resolveProviderNextUpParentContentId(
    parentContentId: String,
    showIdSiblings: Map<String, Set<String>>,
    providerSeedContentIds: List<String>
): String {
    if (parentContentId.isBlank() || showIdSiblings.isEmpty()) return parentContentId

    val aliases = linkedSetOf<String>()

    fun addAlias(raw: String?) {
        val normalized = raw?.trim()?.lowercase()?.takeIf(String::isNotBlank) ?: return
        aliases.add(normalized)
        if (normalized.startsWith("imdb:")) {
            normalized.substringAfter(':').takeIf(String::isNotBlank)?.let(aliases::add)
        } else if (normalized.startsWith("tt")) {
            aliases.add("imdb:$normalized")
        }
    }

    addAlias(parentContentId)

    var changed: Boolean
    do {
        val before = aliases.size
        showIdSiblings.forEach { (contentId, siblings) ->
            val group = linkedSetOf<String>()

            fun addGroupAlias(raw: String?) {
                val normalized = raw?.trim()?.lowercase()?.takeIf(String::isNotBlank) ?: return
                group.add(normalized)
                if (normalized.startsWith("imdb:")) {
                    normalized.substringAfter(':').takeIf(String::isNotBlank)?.let(group::add)
                } else if (normalized.startsWith("tt")) {
                    group.add("imdb:$normalized")
                }
            }

            addGroupAlias(contentId)
            siblings.forEach(::addGroupAlias)

            if (group.any { it in aliases }) {
                group.forEach(::addAlias)
            }
        }
        changed = aliases.size != before
    } while (changed)

    return providerSeedContentIds.firstOrNull { providerContentId ->
        val normalized = providerContentId.trim().lowercase()
        normalized in aliases ||
            (normalized.startsWith("imdb:") && normalized.substringAfter(':') in aliases) ||
            (normalized.startsWith("tt") && "imdb:$normalized" in aliases)
    } ?: parentContentId
}

@Singleton
class TrackingProgressProviderRegistry @Inject constructor(
    providers: Set<@JvmSuppressWildcards TrackingProgressProvider>
) {
    private val providersById = providers.associateBy(TrackingProgressProvider::providerId)

    init {
        require(providersById.size == providers.size)
    }

    fun providers(): List<TrackingProgressProvider> =
        providersById.values.sortedBy { it.providerId.ordinal }

    fun provider(id: TrackingProviderId): TrackingProgressProvider? = providersById[id]
}

@Singleton
class TrackingHistoryWriterRegistry @Inject constructor(
    writers: Set<@JvmSuppressWildcards TrackingHistoryWriter>
) {
    private val writersById = writers.associateBy(TrackingHistoryWriter::providerId)

    init {
        require(providersById.size == providers.size)
    }

    fun writers(): List<TrackingHistoryWriter> =
        writersById.values.sortedBy { it.providerId.ordinal }

    fun writer(id: TrackingProviderId): TrackingHistoryWriter? = writersById[id]
}
