package com.nuvio.tv.domain.repository

import com.nuvio.tv.domain.model.WatchProgress
import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing watch progress data.
 */
interface WatchProgressRepository {
    
    /**
     * Get all watch progress items sorted by last watched (most recent first)
     */
    val allProgress: Flow<List<WatchProgress>>
    
    /**
     * Get items currently in progress (not completed, suitable for "Continue Watching")
     */
    val continueWatching: Flow<List<WatchProgress>>
    
    /**
     * Get watch progress for a specific content item (movie or series)
     */
    fun getProgress(contentId: String): Flow<WatchProgress?>
    
    /**
     * Get watch progress for a specific episode
     */
    fun getEpisodeProgress(contentId: String, season: Int, episode: Int): Flow<WatchProgress?>
    
    /**
     * Get all episode progress for a series as a map of (season, episode) to progress
     */
    fun getAllEpisodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>>


    /**
     * Returns whether the item is marked as watched/completed.
     * For series episodes pass both [season] and [episode].
     */
    fun isWatched(contentId: String, videoId: String? = null, season: Int? = null, episode: Int? = null): Flow<Boolean>
    
    /**
     * Get the aired episode order for a series when available from the current progress backend.
     */
    fun getAiredEpisodeOrder(contentId: String): Flow<List<Pair<Int, Int>>>

    /**
     * Get completed series episode seeds suitable for building a lightweight "Next Up".
     */
    fun observeNextUpSeeds(): Flow<List<WatchProgress>>

    /**
     * Emits true once the active remote tracking provider has completed its
     * initial progress load. Local/Nuvio Sync mode emits true immediately.
     */
    fun observeRemoteProgressLoaded(): Flow<Boolean>

    /**
     * Allows the active provider to remap a Next Up seed before metadata lookup.
     */
    suspend fun prepareNextUpSeed(progress: WatchProgress): WatchProgress

    /**
     * Emits immediate optimistic updates that should patch Continue Watching
     * without waiting for the regular progress flows to settle.
     */
    fun observeOptimisticContinueWatchingUpdates(): Flow<WatchProgress>

    fun observeWatchedMovieIds(): Flow<Set<String>>

    /**
     * Returns per-show watched episodes from the active source.
     */
    suspend fun getWatchedShowEpisodes(): Map<String, Set<Pair<Int, Int>>>

    /**
     * Returns sibling ID mapping: each content ID maps to its alternate IDs
     * from the same show (e.g. IMDB ↔ TMDB). Empty map for non-Trakt sources.
     */
    suspend fun getShowIdSiblings(): Map<String, Set<String>>

    /**
     * Provider-specific watched lookup for episode video IDs, including
     * anime IDs that cannot be reliably matched by the parent content ID.
     */
    fun isWatchedByVideoId(videoId: String, episode: Int): Boolean = false

    /**
     * Save or update watch progress
     */
    suspend fun saveProgress(progress: WatchProgress, syncRemote: Boolean = true)

    /**
     * Save or update multiple watch progress entries in a single batch.
     */
    suspend fun saveProgressBatch(progressList: List<WatchProgress>, syncRemote: Boolean = true)

    /**
     * Remove watch progress (playback only, does not affect Trakt history)
     */
    suspend fun removeProgress(contentId: String, season: Int? = null, episode: Int? = null)

    /**
     * Dismiss a Next Up seed through the active external tracking provider.
     *
     * Returns false when Nuvio Sync/local progress is active or the selected
     * external provider is unavailable.
     */
    suspend fun dismissNextUp(
        contentId: String,
        season: Int? = null,
        episode: Int? = null
    ): Boolean

    /**
     * Remove from watch history (marks as unwatched on Trakt)
     */
    suspend fun removeFromHistory(contentId: String, videoId: String? = null, season: Int? = null, episode: Int? = null)

    /**
     * Mark content as completed
     */
    suspend fun markAsCompleted(progress: WatchProgress)

    /**
     * Mark multiple episodes as completed in a single batch operation.
     */
    suspend fun markAsCompletedBatch(progressList: List<WatchProgress>)

    /**
     * Remove multiple episodes from history in a single batch operation.
     */
    suspend fun removeFromHistoryBatch(
        contentId: String,
        videoId: String?,
        episodes: List<Pair<Int, Int>>
    )

    /**
     * Clear all watch progress
     */
    suspend fun clearAll()

    /**
     * Returns true if the show is dropped/hidden from progress on the active source.
     */
    fun isDroppedShow(contentId: String): Boolean

    /**
     * Returns true when Trakt or Simkl currently owns the progress projection.
     */
    fun hasActiveTrackingProgressProvider(): Boolean

    /**
     * Returns whether the active provider supplies the completed-history
     * projection used to build Next Up.
     */
    fun activeProviderOwnsCompletedHistoryProjection(): Boolean

    /**
     * Returns the active provider's Continue Watching cutoff, when applicable.
     */
    fun activeProviderContinueWatchingCutoffEpochMs(
        daysCap: Int,
        nowEpochMs: Long
    ): Long?

    /**
     * Applies the active provider's completed-seed eligibility rules.
     */
    fun shouldUseAsNextUpSeed(
        progress: WatchProgress,
        nowEpochMs: Long
    ): Boolean

    /**
     * Lets the active provider replace an unresolvable parent ID with a
     * compatible ID found in the episode/video ID.
     */
    suspend fun normalizeParentContentId(
        parentContentId: String,
        videoId: String?
    ): String
}
