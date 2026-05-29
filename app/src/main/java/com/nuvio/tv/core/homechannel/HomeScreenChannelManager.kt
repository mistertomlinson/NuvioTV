package com.nuvio.tv.core.homechannel

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.WatchNextProgram
import androidx.tvprovider.media.tv.TvContractCompat
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.screens.home.NextUpInfo
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "HomeScreenChannelManager"
private const val PREFS_NAME = "home_screen_channel"
private const val MAX_PROGRAMS = 15

private fun channelPrefKey(profileId: Int) = "home_channel_id_p$profileId"
private fun channelDisplayName(profileName: String) = "$profileName — Continue Watching"

@Singleton
class HomeScreenChannelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val watchProgressRepository: WatchProgressRepository,
    private val watchProgressPreferences: WatchProgressPreferences,
    private val profileManager: ProfileManager
) {
    private val watchNextScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val watchNextMutex = Mutex()
    @Volatile private var watchNextDebounceJob: Job? = null
    private val lastKnownItemsByProfile = java.util.concurrent.ConcurrentHashMap<Int, List<ContinueWatchingItem>>()
    private val channelWriteMutexes = java.util.concurrent.ConcurrentHashMap<Int, Mutex>()
    private fun channelMutexFor(profileId: Int) = channelWriteMutexes.getOrPut(profileId) { Mutex() }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun buildDeepLink(progress: WatchProgress): Uri {
        val contentId = encode(progress.contentId)
        val contentType = encode(progress.contentType)
        val addonBaseUrl = progress.addonBaseUrl?.let { encode(it) } ?: ""
        return Uri.parse("nuvio://detail/$contentId/$contentType?addonBaseUrl=$addonBaseUrl")
    }

    private fun storeAppIconAsChannelLogo(channelId: Long) {
        try {
            val drawable = androidx.core.content.ContextCompat.getDrawable(
                context, com.nuvio.tv.R.drawable.ic_launcher
            ) ?: context.packageManager.getApplicationIcon(context.packageName)
            val w = 320
            val h = 320
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            ChannelLogoUtils.storeChannelLogo(context, channelId, bitmap)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to store channel logo", e)
        }
    }

    private fun getOrCreateChannelId(profileId: Int, profileName: String): Long? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefKey = channelPrefKey(profileId)
        val savedId = prefs.getLong(prefKey, -1L)
        if (savedId != -1L) {
            val cursor = context.contentResolver.query(
                TvContractCompat.buildChannelUri(savedId),
                null, null, null, null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    storeAppIconAsChannelLogo(savedId)
                    return savedId
                }
            }
        }

        return try {
            val channel = Channel.Builder()
                .setType(TvContractCompat.Channels.TYPE_PREVIEW)
                .setDisplayName(channelDisplayName(profileName))
                .setAppLinkIntentUri(Uri.parse("nuvio://home"))
                .build()

            val uri = context.contentResolver.insert(
                TvContractCompat.Channels.CONTENT_URI,
                channel.toContentValues()
            ) ?: return null

            val id = ContentUris.parseId(uri)
            prefs.edit().putLong(prefKey, id).apply()

            storeAppIconAsChannelLogo(id)
            TvContractCompat.requestChannelBrowsable(context, id)

            id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create channel", e)
            null
        }
    }

    fun deleteChannel(profileId: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefKey = channelPrefKey(profileId)
        val savedId = prefs.getLong(prefKey, -1L)
        if (savedId == -1L) return
        try {
            context.contentResolver.delete(
                TvContractCompat.buildChannelUri(savedId), null, null
            )
            prefs.edit().remove(prefKey).apply()
            Log.d(TAG, "Deleted channel for profile $profileId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete channel for profile $profileId", e)
        }
    }

    suspend fun refreshFromItems(items: List<ContinueWatchingItem>, profileId: Int, profileName: String) = withContext(Dispatchers.IO) {
        channelMutexFor(profileId).withLock {
        try {
            Log.d(TAG, "refreshFromItems() called with ${items.size} items for profile $profileId")
            lastKnownItemsByProfile[profileId] = items
            val channelId = getOrCreateChannelId(profileId, profileName) ?: run {
                Log.w(TAG, "Could not get or create channel")
                return@withContext
            }

            val limited = items.distinctBy {
                when (it) {
                    is ContinueWatchingItem.InProgress -> it.progress.contentId
                    is ContinueWatchingItem.NextUp -> it.info.contentId
                }
            }.take(MAX_PROGRAMS)

            // Always clear existing programs before inserting fresh data.
            // This prevents stale items from persisting if the pipeline emits
            // an empty list (e.g. Trakt not yet loaded) after a non-empty one.
            val existingCursor = context.contentResolver.query(
                TvContractCompat.buildPreviewProgramsUriForChannel(channelId),
                arrayOf("_id", "title"), null, null, null
            )
            val existingIds = mutableListOf<Long>()
            existingCursor?.use { cur ->
                while (cur.moveToNext()) {
                    existingIds.add(cur.getLong(0))
                }
            }
            existingIds.forEach { id ->
                context.contentResolver.delete(
                    TvContractCompat.buildPreviewProgramUri(id), null, null
                )
            }

            // Load enrichment cache for this profile to fill gaps in unenriched pipeline items
            val profileEnrichmentCache = try {
                val dir = java.io.File(context.filesDir, "cw_enrichment")
                val file = java.io.File(dir, "inprogress_${profileId}.json")
                if (file.exists()) {
                    val type = com.google.gson.reflect.TypeToken.getParameterized(
                        List::class.java,
                        com.nuvio.tv.data.local.CachedInProgressItem::class.java
                    ).type
                    val parsed: List<com.nuvio.tv.data.local.CachedInProgressItem>? =
                        com.google.gson.Gson().fromJson(file.readText(), type)
                    (parsed ?: emptyList()).associateBy { it.contentId }
                } else emptyMap()
            } catch (e: Exception) {
                emptyMap<String, com.nuvio.tv.data.local.CachedInProgressItem>()
            }

            limited.forEachIndexed { index, item ->
                try {
                    val (contentId, contentType, name, poster, backdrop, logo, addonBaseUrl, season, episode, episodeTitle, episodeThumbnail, episodeDescription) = when (item) {
                        is ContinueWatchingItem.InProgress -> {
                            val cached = profileEnrichmentCache[item.progress.contentId]
                            ItemFields(
                                contentId = item.progress.contentId,
                                contentType = item.progress.contentType,
                                name = cached?.name?.takeIf { it.isNotBlank() } ?: item.progress.name,
                                poster = item.progress.poster ?: cached?.poster,
                                backdrop = item.progress.backdrop ?: cached?.backdrop,
                                logo = item.progress.logo ?: cached?.logo,
                                addonBaseUrl = item.progress.addonBaseUrl,
                                season = item.progress.season ?: cached?.season,
                                episode = item.progress.episode ?: cached?.episode,
                                episodeTitle = item.progress.episodeTitle ?: cached?.episodeTitle,
                                episodeThumbnail = item.episodeThumbnail ?: cached?.episodeThumbnail,
                                episodeDescription = item.episodeDescription ?: cached?.episodeDescription
                            )
                        }
                        is ContinueWatchingItem.NextUp -> ItemFields(
                            contentId = item.info.contentId,
                            contentType = item.info.contentType,
                            name = item.info.name,
                            poster = item.info.poster,
                            backdrop = item.info.backdrop,
                            logo = item.info.logo,
                            addonBaseUrl = null,
                            season = item.info.season,
                            episode = item.info.episode,
                            episodeTitle = item.info.episodeTitle,
                            episodeThumbnail = item.info.thumbnail,
                            episodeDescription = item.info.episodeDescription
                        )
                    }

                    val deepLink = Uri.parse(
                        "nuvio://detail/${encode(contentId)}/${encode(contentType)}?addonBaseUrl=${addonBaseUrl?.let { encode(it) } ?: ""}&profileId=$profileId"
                    )


                    val rawPoster = poster?.takeIf { !it.contains("rpdb") && !it.contains("/posters/rpdb") }
                    val rawBackdrop = backdrop
                    val cardImageUri = (episodeThumbnail ?: rawBackdrop ?: rawPoster)?.let { Uri.parse(it) }
                    val posterUri = rawPoster?.let { Uri.parse(it) }
                    val logoUri = logo?.let { Uri.parse(it.replace(".svg", ".png")) }

                    val type = if (contentType == "series")
                        TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE
                    else
                        TvContractCompat.PreviewPrograms.TYPE_MOVIE

                    val duration = when (item) {
                            is ContinueWatchingItem.InProgress -> item.progress.duration.takeIf { it > 0 }?.toInt() ?: 0
                            is ContinueWatchingItem.NextUp -> 0
                        }
                    val position = when (item) {
                            is ContinueWatchingItem.InProgress -> item.progress.position.toInt()
                            is ContinueWatchingItem.NextUp -> 0
                        }

                    val program = PreviewProgram.Builder()
                        .setChannelId(channelId)
                        .setType(type)
                        .apply { season?.let { setSeasonNumber(it) } }
                        .apply { episode?.let { setEpisodeNumber(it) } }
                        .apply { episodeTitle?.let { setEpisodeTitle(it) } }
                        .apply { episodeDescription?.let { setDescription(it) } }
                        .apply { cardImageUri?.let { setThumbnailUri(it) } }
                        .apply { posterUri?.let { setPosterArtUri(it) } }
                        .apply { logoUri?.let { setLogoUri(it) } }
                        .setWeight(limited.size - index)
                        .setDurationMillis(duration)
                        .setLastPlaybackPositionMillis(position)
                        .setIntentUri(deepLink)
                        .build()

                    context.contentResolver.insert(
                        TvContractCompat.PreviewPrograms.CONTENT_URI,
                        program.toContentValues()
                    )
                    Log.d(TAG, "PreviewProgram inserted: $name S${season}E${episode} '$episodeTitle' type=$contentType")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to insert item", e)
                }
            }
            Log.d(TAG, "Refreshed channel with ${limited.size} items")
            // Debounce: cancel any pending WatchNext refresh and schedule a new one
            // so rapid back-to-back calls (e.g. profile 1 then profile 2) only run once
            watchNextDebounceJob?.cancel()
            val itemsSnapshot = limited.toList()
            val profileIdSnapshot = profileId
            watchNextDebounceJob = watchNextScope.launch {
                delay(1500)
                refreshWatchNext(itemsSnapshot, profileIdSnapshot)
            }
        } catch (e: Exception) {
            Log.e(TAG, "refreshFromItems failed", e)
        }
        } // end channelMutexFor(profileId).withLock
    }

    private suspend fun refreshWatchNext(currentItems: List<ContinueWatchingItem>, currentProfileId: Int) {
        watchNextMutex.withLock {
        try {
            val allFields = mutableListOf<WatchNextFields>()
            val seenIds = mutableSetOf<String>()

            // Current profile — fully enriched items
            currentItems.forEach { item ->
                when (item) {
                    is ContinueWatchingItem.InProgress -> {
                        val p = item.progress
                        if (seenIds.add(p.contentId)) {
                            allFields.add(WatchNextFields(
                                contentId = p.contentId,
                                contentType = p.contentType,
                                name = p.name,
                                poster = p.poster,
                                backdrop = p.backdrop,
                                logo = p.logo,
                                addonBaseUrl = p.addonBaseUrl,
                                season = p.season,
                                episode = p.episode,
                                episodeTitle = p.episodeTitle,
                                episodeDescription = item.episodeDescription,
                                episodeThumbnail = item.episodeThumbnail,
                                isInProgress = true,
                                position = p.position.toInt(),
                                duration = p.duration.takeIf { it > 0 }?.toInt() ?: 0,
                                lastWatched = p.lastWatched,
                                profileId = currentProfileId
                            ))
                        }
                    }
                    is ContinueWatchingItem.NextUp -> {
                        val info = item.info
                        if (seenIds.add(info.contentId)) {
                            allFields.add(WatchNextFields(
                                contentId = info.contentId,
                                contentType = info.contentType,
                                name = info.name,
                                poster = info.poster,
                                backdrop = info.backdrop,
                                logo = info.logo,
                                addonBaseUrl = null,
                                season = info.season,
                                episode = info.episode,
                                episodeTitle = info.episodeTitle,
                                episodeDescription = info.episodeDescription,
                                episodeThumbnail = info.thumbnail,
                                isInProgress = false,
                                position = 0,
                                duration = 0,
                                lastWatched = info.lastWatched,
                                profileId = currentProfileId
                            ))
                        }
                    }
                }
            }

            // Other profiles — use last known items if available, fall back to disk cache
            val allProfiles = profileManager.profiles.value
            allProfiles.filter { it.id != currentProfileId }.forEach { profile ->
                runCatching {
                    val cachedItems = lastKnownItemsByProfile[profile.id]
                    if (cachedItems != null) {
                        cachedItems.forEach { item ->
                            when (item) {
                                is ContinueWatchingItem.InProgress -> {
                                    val p = item.progress
                                    if (seenIds.add(p.contentId)) {
                                        allFields.add(WatchNextFields(
                                            contentId = p.contentId,
                                            contentType = p.contentType,
                                            name = p.name,
                                            poster = p.poster,
                                            backdrop = p.backdrop,
                                            logo = p.logo,
                                            addonBaseUrl = p.addonBaseUrl,
                                            season = p.season,
                                            episode = p.episode,
                                            episodeTitle = p.episodeTitle,
                                            episodeDescription = item.episodeDescription,
                                            episodeThumbnail = item.episodeThumbnail,
                                            isInProgress = true,
                                            position = p.position.toInt(),
                                            duration = p.duration.takeIf { it > 0 }?.toInt() ?: 0,
                                            lastWatched = p.lastWatched,
                                            profileId = profile.id
                                        ))
                                    }
                                }
                                is ContinueWatchingItem.NextUp -> {
                                    val info = item.info
                                    if (seenIds.add(info.contentId)) {
                                        allFields.add(WatchNextFields(
                                            contentId = info.contentId,
                                            contentType = info.contentType,
                                            name = info.name,
                                            poster = info.poster,
                                            backdrop = info.backdrop,
                                            logo = info.logo,
                                            addonBaseUrl = null,
                                            season = info.season,
                                            episode = info.episode,
                                            episodeTitle = info.episodeTitle,
                                            episodeDescription = info.episodeDescription,
                                            episodeThumbnail = info.thumbnail,
                                            isInProgress = false,
                                            position = 0,
                                            duration = 0,
                                            lastWatched = info.lastWatched,
                                            profileId = profile.id
                                        ))
                                    }
                                }
                            }
                        }
                        return@runCatching
                    }
                    val rawProgress = watchProgressPreferences.loadContinueWatchingForProfile(profile.id)
                    // Load enrichment from the file-based cache (ContinueWatchingEnrichmentCache)
                    // which is what the active pipeline writes to. The old DataStore-based
                    // InProgressEnrichmentEntry is no longer populated by the pipeline.
                    val enrichmentCache = run {
                        try {
                            val dir = java.io.File(context.filesDir, "cw_enrichment")
                            val file = java.io.File(dir, "inprogress_${profile.id}.json")
                            if (file.exists()) {
                                val type = com.google.gson.reflect.TypeToken.getParameterized(
                                    List::class.java,
                                    com.nuvio.tv.data.local.CachedInProgressItem::class.java
                                ).type
                                val parsed: List<com.nuvio.tv.data.local.CachedInProgressItem>? =
                                    com.google.gson.Gson().fromJson(file.readText(), type)
                                (parsed ?: emptyList()).associateBy { it.contentId }
                            } else emptyMap()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to read file-based enrichment cache for profile ${profile.id}", e)
                            emptyMap()
                        }
                    }
                    // Refresh this profile's channel row with enriched data now that
                    // the enrichment file is available (it may not have been ready when
                    // that profile was last active).
                    if (enrichmentCache.isNotEmpty()) {
                        val enrichedChannelItems = rawProgress.mapNotNull { p ->
                            val enrichment = enrichmentCache[p.contentId] ?: return@mapNotNull null
                            ContinueWatchingItem.InProgress(
                                progress = com.nuvio.tv.domain.model.WatchProgress(
                                    contentId = p.contentId,
                                    contentType = p.contentType,
                                    name = enrichment?.name?.takeIf { it.isNotBlank() } ?: p.name,
                                    poster = enrichment?.poster ?: p.poster,
                                    backdrop = enrichment?.backdrop ?: p.backdrop,
                                    logo = enrichment?.logo ?: p.logo,
                                    videoId = p.videoId,
                                    season = enrichment?.season ?: p.season,
                                    episode = enrichment?.episode ?: p.episode,
                                    episodeTitle = enrichment?.episodeTitle ?: p.episodeTitle,
                                    position = p.position,
                                    duration = p.duration,
                                    lastWatched = p.lastWatched,
                                    addonBaseUrl = p.addonBaseUrl,
                                    progressPercent = p.progressPercent
                                ),
                                episodeThumbnail = enrichment?.episodeThumbnail,
                                episodeDescription = enrichment?.episodeDescription,
                                episodeImdbRating = enrichment?.episodeImdbRating,
                                genres = enrichment?.genres ?: emptyList(),
                                releaseInfo = enrichment?.releaseInfo
                            )
                        }
                        if (enrichedChannelItems.isNotEmpty()) {
                            val profileName = profile.name
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                runCatching {
                                    refreshFromItems(enrichedChannelItems, profile.id, profileName)
                                }
                            }
                        }
                    }

                    Log.d(TAG, "Profile ${profile.id} rawProgress=${rawProgress.size} nextUpCache=${watchProgressPreferences.loadNextUpCache(profile.id).size}")
                    rawProgress.forEach { p ->
                        if (seenIds.add(p.contentId)) {
                            val enrichment = enrichmentCache[p.contentId]
                            allFields.add(WatchNextFields(
                                contentId = p.contentId,
                                contentType = p.contentType,
                                name = enrichment?.name?.takeIf { it.isNotBlank() } ?: p.name,
                                poster = enrichment?.poster ?: p.poster,
                                backdrop = enrichment?.backdrop ?: p.backdrop,
                                logo = enrichment?.logo ?: p.logo,
                                addonBaseUrl = p.addonBaseUrl,
                                season = enrichment?.season ?: p.season,
                                episode = enrichment?.episode ?: p.episode,
                                episodeTitle = enrichment?.episodeTitle ?: p.episodeTitle,
                                episodeDescription = enrichment?.episodeDescription,
                                episodeThumbnail = enrichment?.episodeThumbnail,
                                isInProgress = true,
                                position = p.position.toInt(),
                                duration = p.duration.takeIf { it > 0 }?.toInt() ?: 0,
                                lastWatched = p.lastWatched,
                                profileId = profile.id
                            ))
                        }
                    }
                    val nextUpCache = watchProgressPreferences.loadNextUpCache(profile.id)
                    nextUpCache.forEach { info ->
                        if (seenIds.add(info.contentId)) {
                            allFields.add(WatchNextFields(
                                contentId = info.contentId,
                                contentType = info.contentType,
                                name = info.name,
                                poster = info.poster,
                                backdrop = info.backdrop,
                                logo = info.logo,
                                addonBaseUrl = null,
                                season = info.season,
                                episode = info.episode,
                                episodeTitle = info.episodeTitle,
                                episodeDescription = info.episodeDescription,
                                episodeThumbnail = info.thumbnail,
                                isInProgress = false,
                                position = 0,
                                duration = 0,
                                lastWatched = info.lastWatched,
                                profileId = profile.id
                            ))
                        }
                    }
                }.onFailure { Log.w(TAG, "Failed to load CW for profile ${profile.id}", it) }
            }

            // Collect existing Watch Next IDs before inserting — we insert first,
            // then delete old entries. This ensures the provider is never empty during
            // the write, so Projectivy's post-resume poll always sees valid data.
            val oldWatchNextIds = mutableListOf<Long>()
            val existingWatchNext = context.contentResolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                arrayOf("_id", "package_name"),
                null, null, null
            )
            existingWatchNext?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(0)
                    val pkg = runCatching { cur.getString(1) }.getOrNull()
                    if (pkg == context.packageName) {
                        oldWatchNextIds.add(id)
                    }
                }
            }

            // Insert sorted by most recent first
            allFields.sortByDescending { it.lastWatched }
            allFields.forEach { fields ->
                try {
                    val deepLink = Uri.parse(
                        "nuvio://detail/${encode(fields.contentId)}/${encode(fields.contentType)}?addonBaseUrl=${fields.addonBaseUrl?.let { encode(it) } ?: ""}&profileId=${fields.profileId}"
                    )
                    val rawPoster = fields.poster?.takeIf { !it.contains("rpdb") && !it.contains("/posters/rpdb") }
                    val cardImageUri = (fields.episodeThumbnail ?: fields.backdrop ?: rawPoster)?.let { Uri.parse(it) }
                    val posterUri = rawPoster?.let { Uri.parse(it) }
                    val logoUri = fields.logo?.let { Uri.parse(it.replace(".svg", ".png")) }
                    val watchNextType = if (fields.isInProgress)
                        TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
                    else
                        TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT
                    val mediaType = if (fields.contentType == "series")
                        TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE
                    else
                        TvContractCompat.WatchNextPrograms.TYPE_MOVIE
                    val program = WatchNextProgram.Builder()
                        .setWatchNextType(watchNextType)
                        .setType(mediaType)
                        .setTitle(fields.name)
                        .apply { fields.season?.let { setSeasonNumber(it) } }
                        .apply { fields.episode?.let { setEpisodeNumber(it) } }
                        .apply { fields.episodeTitle?.let { setEpisodeTitle(it) } }
                        .apply { fields.episodeDescription?.takeIf { it.isNotBlank() }?.let { setDescription(it) } }
                        .apply { cardImageUri?.let { setThumbnailUri(it) } }
                        .apply { posterUri?.let { setPosterArtUri(it) } }
                        .setLastEngagementTimeUtcMillis(fields.lastWatched.takeIf { it > 0 } ?: System.currentTimeMillis())
                        .setLastPlaybackPositionMillis(fields.position)
                        .setDurationMillis(fields.duration)
                        .setIntentUri(deepLink)
                        .build()
                    context.contentResolver.insert(
                        TvContractCompat.WatchNextPrograms.CONTENT_URI,
                        program.toContentValues()
                    )
                    Log.d(TAG, "WatchNext inserted: ${fields.name} inProgress=${fields.isInProgress}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to insert WatchNext: ${fields.name}", e)
                }
            }

            // Now delete the old entries — provider was never empty during the write
            oldWatchNextIds.forEach { id ->
                context.contentResolver.delete(
                    TvContractCompat.buildWatchNextProgramUri(id), null, null
                )
            }
            Log.d(TAG, "WatchNext refreshed with ${allFields.size} items across all profiles")
        } catch (e: Exception) {
            Log.e(TAG, "refreshWatchNext failed", e)
        }
        } // end watchNextMutex.withLock
    }

    private data class WatchNextFields(
        val contentId: String,
        val contentType: String,
        val name: String,
        val poster: String?,
        val backdrop: String?,
        val logo: String?,
        val addonBaseUrl: String?,
        val season: Int?,
        val episode: Int?,
        val episodeTitle: String?,
        val episodeDescription: String?,
        val episodeThumbnail: String?,
        val isInProgress: Boolean,
        val position: Int,
        val duration: Int,
        val lastWatched: Long,
        val profileId: Int
    )

    private data class ItemFields(
        val contentId: String,
        val contentType: String,
        val name: String,
        val poster: String?,
        val backdrop: String?,
        val logo: String?,
        val addonBaseUrl: String?,
        val season: Int?,
        val episode: Int?,
        val episodeTitle: String?,
        val episodeThumbnail: String? = null,
        val episodeDescription: String? = null
    )


    /**
     * Reconciles stored channel IDs against the TV provider and valid profiles.
     * Also sweeps the TV provider for any channels registered by this package
     * that are not tracked in SharedPreferences (orphans from old code paths).
     * Safe to call on every app start.
     */
    fun cleanupOrphanChannels(validProfileIds: Set<Int>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        // Build set of channel IDs we currently track in prefs
        val allKeys = prefs.all.keys.filter { it.startsWith("home_channel_id_p") }
        val trackedChannelIds = mutableSetOf<Long>()

        for (key in allKeys) {
            val profileId = key.removePrefix("home_channel_id_p").toIntOrNull()
            val channelId = prefs.getLong(key, -1L)
            if (channelId == -1L) continue

            // Check if this channel still exists in the TV provider
            val exists = try {
                val cursor = context.contentResolver.query(
                    TvContractCompat.buildChannelUri(channelId),
                    arrayOf("_id"), null, null, null
                )
                cursor?.use { it.moveToFirst() } ?: false
            } catch (e: Exception) {
                false
            }

            if (!exists) {
                // Channel gone from provider — remove stale prefs entry
                editor.remove(key)
                Log.i(TAG, "Removed stale prefs entry key=$key channelId=$channelId")
                continue
            }

            trackedChannelIds.add(channelId)

            // Channel exists but profile was deleted — delete it
            if (profileId != null && profileId !in validProfileIds) {
                try {
                    context.contentResolver.delete(
                        TvContractCompat.buildChannelUri(channelId), null, null
                    )
                    Log.i(TAG, "Deleted channel for removed profile=$profileId channelId=$channelId")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete channel profileId=$profileId", e)
                }
                editor.remove(key)
                trackedChannelIds.remove(channelId)
            }
        }

        // Sweep provider for any channels registered by our package that
        // are NOT in our prefs — these are orphans from old code paths.
        try {
            val cursor = context.contentResolver.query(
                TvContractCompat.Channels.CONTENT_URI,
                arrayOf("_id", "package_name"),
                null, null, null
            )
            cursor?.use { cur ->
                while (cur.moveToNext()) {
                    val channelId = cur.getLong(0)
                    val pkg = runCatching { cur.getString(1) }.getOrNull()
                    if (pkg == context.packageName && channelId !in trackedChannelIds) {
                        try {
                            context.contentResolver.delete(
                                TvContractCompat.buildChannelUri(channelId), null, null
                            )
                            Log.i(TAG, "Deleted untracked orphan channel channelId=$channelId")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to delete orphan channel channelId=$channelId", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sweep provider for orphan channels", e)
        }

        editor.apply()
    }

}