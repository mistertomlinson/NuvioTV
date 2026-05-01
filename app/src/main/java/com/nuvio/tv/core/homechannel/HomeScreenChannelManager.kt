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
import androidx.tvprovider.media.tv.TvContractCompat
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.screens.home.NextUpInfo
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    private val watchProgressRepository: WatchProgressRepository
) {
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
            val drawable = context.packageManager.getApplicationIcon(context.packageName)
            val w = drawable.intrinsicWidth.coerceAtLeast(80)
            val h = drawable.intrinsicHeight.coerceAtLeast(80)
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
        try {
            Log.d(TAG, "refreshFromItems() called with ${items.size} items for profile $profileId")
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

            // Diff approach: query existing programs, only delete stale ones
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
            // Delete all existing first only if we have a full list ready
            if (limited.isNotEmpty()) {
                existingIds.forEach { id ->
                    context.contentResolver.delete(
                        TvContractCompat.buildPreviewProgramUri(id), null, null
                    )
                }
            }

            limited.forEachIndexed { index, item ->
                try {
                    val (contentId, contentType, name, poster, backdrop, logo, addonBaseUrl, season, episode, episodeTitle, episodeThumbnail) = when (item) {
                        is ContinueWatchingItem.InProgress -> ItemFields(
                            contentId = item.progress.contentId,
                            contentType = item.progress.contentType,
                            name = item.progress.name,
                            poster = item.progress.poster,
                            backdrop = item.progress.backdrop,
                            logo = item.progress.logo,
                            addonBaseUrl = item.progress.addonBaseUrl,
                            season = item.progress.season,
                            episode = item.progress.episode,
                            episodeTitle = item.progress.episodeTitle,
                            episodeThumbnail = item.episodeThumbnail
                        )
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
                            episodeThumbnail = item.info.thumbnail
                        )
                    }

                    val deepLink = Uri.parse(
                        "nuvio://detail/${encode(contentId)}/${encode(contentType)}?addonBaseUrl=${addonBaseUrl?.let { encode(it) } ?: ""}"
                    )

                    val rawPoster = poster?.takeIf { !it.contains("rpdb") && !it.contains("/posters/rpdb") }
                    val rawBackdrop = backdrop
                    val cardImageUri = (episodeThumbnail ?: rawBackdrop ?: rawPoster)?.let { Uri.parse(it) }
                    val posterUri = rawPoster?.let { Uri.parse(it) }
                    val logoUri = logo?.let { Uri.parse(it) }

                    val subtitle = if (season != null && episode != null) {
                        "S${season}E${episode}" + episodeTitle?.let { " · $it" }.orEmpty()
                    } else null

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
                        .setTitle(name)
                        .apply { subtitle?.let { setEpisodeTitle(it) } }
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
                    Log.d(TAG, "Inserted: $name cardImage=$cardImageUri")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to insert item", e)
                }
            }
            Log.d(TAG, "Refreshed channel with ${limited.size} items")
        } catch (e: Exception) {
            Log.e(TAG, "refreshFromItems failed", e)
        }
    }

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
        val episodeThumbnail: String? = null
    )


    suspend fun refresh() = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "refresh() called")
            val channelId = getOrCreateChannelId(1, "Profile 1") ?: run {
                Log.w(TAG, "Could not get or create channel")
                return@withContext
            }
            Log.d(TAG, "Channel ID: $channelId")

            // Wait up to 20s for items with metadata (posters/backdrops).
            // The flow emits non-empty early but metadata hydration is async —
            // wait until at least one item has artwork before inserting.
            val items = withTimeoutOrNull(20_000L) {
                watchProgressRepository.continueWatching
                    .first { items ->
                        items.isNotEmpty() && items.any { it.poster != null || it.backdrop != null }
                    }
            } ?: withTimeoutOrNull(5_000L) {
                watchProgressRepository.continueWatching.first { it.isNotEmpty() }
            } ?: watchProgressRepository.continueWatching.first()

            // Deduplicate by contentId — same title can appear as both InProgress and NextUp
            val limited = items.distinctBy { it.contentId }.take(MAX_PROGRAMS)
            Log.d(TAG, "Got ${items.size} raw items, ${limited.size} after dedup")
            items.forEach { Log.d(TAG, "  item: ${it.name} contentId=${it.contentId} poster=${it.poster} backdrop=${it.backdrop}") }

            // Diff approach: only clear and rebuild when we have items ready
            if (limited.isNotEmpty()) {
                val existingCursor2 = context.contentResolver.query(
                    TvContractCompat.buildPreviewProgramsUriForChannel(channelId),
                    arrayOf("_id"), null, null, null
                )
                val existingIds2 = mutableListOf<Long>()
                existingCursor2?.use { cur ->
                    while (cur.moveToNext()) { existingIds2.add(cur.getLong(0)) }
                }
                existingIds2.forEach { id ->
                    context.contentResolver.delete(
                        TvContractCompat.buildPreviewProgramUri(id), null, null
                    )
                }
            }

            limited.forEachIndexed { index, progress ->
                try {
                    val deepLink = buildDeepLink(progress)
                    // Use backdrop as primary card image (direct URL, landscape, Projectivy-compatible).
                    // Fall back to poster only if no backdrop available.
                    // Skip RPDB proxy URLs — Projectivy can't resolve them.
                    val rawPoster = progress.poster?.takeIf { !it.contains("rpdb") && !it.contains("/posters/rpdb") }
                    val rawBackdrop = progress.backdrop
                    val cardImageUri = (rawBackdrop ?: rawPoster)?.let { Uri.parse(it) }
                    val posterUri = rawPoster?.let { Uri.parse(it) }
                    val backdropUri = rawBackdrop?.let { Uri.parse(it) }
                    val logoUri = progress.logo?.let { Uri.parse(it) }

                    val subtitle = when {
                        progress.season != null && progress.episode != null ->
                            "S${progress.season}E${progress.episode}" +
                                progress.episodeTitle?.let { " · $it" }.orEmpty()
                        else -> null
                    }

                    val program = PreviewProgram.Builder()
                        .setChannelId(channelId)
                        .setType(
                            if (progress.contentType == "series")
                                TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE
                            else
                                TvContractCompat.PreviewPrograms.TYPE_MOVIE
                        )
                        .setTitle(progress.name)
                        .apply { subtitle?.let { setEpisodeTitle(it) } }
                        .apply { cardImageUri?.let { setThumbnailUri(it) } }
                        .apply { posterUri?.let { setPosterArtUri(it) } }
                        .apply { logoUri?.let { setLogoUri(it) } }
                        .setWeight(limited.size - index)
                        .setDurationMillis(progress.duration.takeIf { it > 0 }?.toInt() ?: 0)
                        .setLastPlaybackPositionMillis(progress.position.toInt())
                        .setIntentUri(deepLink)
                        .build()

                    context.contentResolver.insert(
                        TvContractCompat.PreviewPrograms.CONTENT_URI,
                        program.toContentValues()
                    )
                    Log.d(TAG, "Inserted: ${progress.name} cardImage=$cardImageUri")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to insert program for ${progress.name}", e)
                }
            }

            Log.d(TAG, "Refreshed channel with ${limited.size} items")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh home screen channel", e)
        }
    }
}
