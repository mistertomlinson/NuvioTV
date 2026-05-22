package com.nuvio.tv.data.repository

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktEpisodeDto
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nuvio.tv.data.remote.dto.trakt.TraktScrobbleRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktAddRatingRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingMovieDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingShowDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingSeasonDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingEpisodeDto
import com.nuvio.tv.data.remote.dto.trakt.TraktShowDto
import com.nuvio.tv.core.profile.ProfileManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

sealed interface TraktScrobbleItem {
    val itemKey: String

    data class Movie(
        val title: String?,
        val year: Int?,
        val ids: TraktIdsDto
    ) : TraktScrobbleItem {
        override val itemKey: String =
            "movie:${ids.imdb ?: ids.tmdb ?: ids.trakt ?: title.orEmpty()}:${year ?: 0}"
    }

    data class Episode(
        val showTitle: String?,
        val showYear: Int?,
        val showIds: TraktIdsDto,
        val season: Int,
        val number: Int,
        val episodeTitle: String?
    ) : TraktScrobbleItem {
        override val itemKey: String =
            "episode:${showIds.imdb ?: showIds.tmdb ?: showIds.trakt ?: showTitle.orEmpty()}:$season:$number"
    }
}

@Singleton
class TraktScrobbleService @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService,
    private val traktProgressService: TraktProgressService,
    private val profileManager: ProfileManager
) {
    private data class ScrobbleStamp(
        val action: String,
        val itemKey: String,
        val progress: Float,
        val timestampMs: Long
    )

    private var lastScrobbleStamp: ScrobbleStamp? = null
    private val minSendIntervalMs = 8_000L
    private val progressWindow = 1.5f

    suspend fun scrobbleStart(item: TraktScrobbleItem, progressPercent: Float) {
        sendScrobble(action = "start", item = item, progressPercent = progressPercent)
    }

    suspend fun scrobbleStop(item: TraktScrobbleItem, progressPercent: Float) {
        sendScrobble(action = "stop", item = item, progressPercent = progressPercent)
    }

    suspend fun scrobblePause(item: TraktScrobbleItem, progressPercent: Float) {
        sendScrobble(action = "pause", item = item, progressPercent = progressPercent)
    }

    suspend fun isTraktAuthenticated(): Boolean {
        return traktAuthService.getCurrentAuthState().isAuthenticated &&
            traktAuthService.hasRequiredCredentials()
    }

    suspend fun postRating(
        item: TraktScrobbleItem,
        rating: Int
    ) {
        if (!traktAuthService.getCurrentAuthState().isAuthenticated) return
        if (!traktAuthService.hasRequiredCredentials()) return

        val clampedRating = rating.coerceIn(1, 10)
        val ratedAt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())

        // Resolve full Trakt IDs if we only have an IMDB ID
        val resolvedIds = when (item) {
            is TraktScrobbleItem.Movie -> resolveIdsIfNeeded(item.ids, expectedType = "movie")
            is TraktScrobbleItem.Episode -> resolveIdsIfNeeded(item.showIds, expectedType = "show")
        }

        val body = when (item) {
            is TraktScrobbleItem.Movie -> TraktAddRatingRequestDto(
                movies = listOf(
                    TraktRatingMovieDto(
                        title = item.title,
                        year = item.year,
                        ids = resolvedIds,
                        rating = clampedRating,
                        ratedAt = ratedAt
                    )
                )
            )
            // Rate at show level so Watchly can read it from /users/me/ratings/shows
            is TraktScrobbleItem.Episode -> TraktAddRatingRequestDto(
                shows = listOf(
                    TraktRatingShowDto(
                        title = item.showTitle,
                        year = item.showYear,
                        ids = resolvedIds,
                        rating = clampedRating,
                        ratedAt = ratedAt
                    )
                )
            )
        }

        android.util.Log.d("TraktRating", "postRating sending: item=$item rating=$clampedRating body=$body")
        var result = runCatching {
            traktAuthService.executeAuthorizedWriteRequest { authHeader ->
                traktApi.addRating(authHeader, body)
            }
        }
        // If added=0 with no not_found, token may have been stale — force refresh and retry once
        val addedCount = result.getOrNull()?.body()?.added?.let {
            (it.movies ?: 0) + (it.shows ?: 0) + (it.episodes ?: 0)
        } ?: -1
        val notFoundEmpty = result.getOrNull()?.body()?.notFound?.let {
            it.movies.isNullOrEmpty() && it.shows.isNullOrEmpty() && it.episodes.isNullOrEmpty()
        } ?: false
        if (addedCount == 0 && notFoundEmpty) {
            android.util.Log.d("TraktRating", "postRating: added=0 with fresh token attempt, forcing token refresh")
            traktAuthService.refreshTokenIfNeeded(force = true)
            result = runCatching {
                traktAuthService.executeAuthorizedWriteRequest { authHeader ->
                    traktApi.addRating(authHeader, body)
                }
            }
        }
        result.onSuccess { response ->
            val responseBody = response?.body()
            val errStr = response?.errorBody()?.string()
            android.util.Log.d("TraktRating", "postRating response: code=${response?.code()} " +
                "added=[movies=${responseBody?.added?.movies} shows=${responseBody?.added?.shows} episodes=${responseBody?.added?.episodes}] " +
                "notFound=[movies=${responseBody?.notFound?.movies?.map { it.ids }} shows=${responseBody?.notFound?.shows?.map { it.ids }} " +
                "episodes=${responseBody?.notFound?.episodes?.map { it.number }}] error=$errStr")
        }
        result.onFailure { e ->
            android.util.Log.w("TraktRating", "postRating exception", e)
        }
    }

    private suspend fun resolveIdsIfNeeded(
        ids: com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto,
        expectedType: String
    ): com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto {
        // If we already have a trakt or tmdb ID, no resolution needed
        if (ids.trakt != null || ids.tmdb != null) return ids
        val imdbId = ids.imdb?.takeIf { it.isNotBlank() } ?: return ids

        return runCatching {
            val response = traktAuthService.executeAuthorizedRequest { authHeader ->
                traktApi.searchById(
                    authorization = authHeader,
                    idType = "imdb",
                    id = imdbId,
                    type = expectedType
                )
            } ?: return@runCatching ids
            if (!response.isSuccessful) return@runCatching ids
            val result = response.body()?.firstOrNull { it.type == expectedType }
                ?: return@runCatching ids
            val resolvedIds = if (expectedType == "movie") result.movie?.ids
                             else result.show?.ids
            resolvedIds ?: ids
        }.onFailure { e ->
            android.util.Log.w("TraktRating", "resolveIdsIfNeeded failed for $imdbId", e)
        }.getOrDefault(ids)
    }

    private suspend fun sendScrobble(
        action: String,
        item: TraktScrobbleItem,
        progressPercent: Float
    ) {
        android.util.Log.d("TraktScrobble", "sendScrobble: action=$action item=${item.itemKey} progress=$progressPercent authenticated=${traktAuthService.getCurrentAuthState().isAuthenticated}")
        if (!traktAuthService.getCurrentAuthState().isAuthenticated) return
        if (!traktAuthService.hasRequiredCredentials()) return

        val clampedProgress = progressPercent.coerceIn(0f, 100f)
        if (action != "stop" && action != "pause" && shouldSkip(action, item.itemKey, clampedProgress)) return

        val requestBody = buildRequestBody(item, clampedProgress)

        val response = traktAuthService.executeAuthorizedWriteRequest { authHeader ->
            when (action) {
                "start" -> traktApi.scrobbleStart(authHeader, requestBody)
                else -> traktApi.scrobbleStop(authHeader, requestBody)
            }
        } ?: return

        if (response.isSuccessful || response.code() == 409) {
            lastScrobbleStamp = ScrobbleStamp(
                action = action,
                itemKey = item.itemKey,
                progress = clampedProgress,
                timestampMs = System.currentTimeMillis()
            )
            if (action == "stop") {
                traktProgressService.refreshNow()
            }
        }
    }

    internal fun buildRequestBody(
        item: TraktScrobbleItem,
        clampedProgress: Float
    ): TraktScrobbleRequestDto {
        return when (item) {
            is TraktScrobbleItem.Movie -> TraktScrobbleRequestDto(
                movie = TraktMovieDto(
                    title = item.title,
                    year = item.year,
                    ids = item.ids
                ),
                progress = clampedProgress,
                appVersion = BuildConfig.VERSION_NAME
            )

            is TraktScrobbleItem.Episode -> TraktScrobbleRequestDto(
                show = TraktShowDto(
                    title = item.showTitle,
                    year = item.showYear,
                    ids = item.showIds
                ),
                episode = TraktEpisodeDto(
                    title = item.episodeTitle,
                    season = item.season,
                    number = item.number
                ),
                progress = clampedProgress,
                appVersion = BuildConfig.VERSION_NAME
            )
        }
    }

    private fun shouldSkip(action: String, itemKey: String, progress: Float): Boolean {
        val last = lastScrobbleStamp ?: return false
        val now = System.currentTimeMillis()
        val isSameWindow = now - last.timestampMs < minSendIntervalMs
        val isSameAction = last.action == action
        val isSameItem = last.itemKey == itemKey
        val isNearProgress = abs(last.progress - progress) <= progressWindow
        return isSameWindow && isSameAction && isSameItem && isNearProgress
    }
}
