package com.nuvio.tv.data.repository

import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.simkl.SimklAuthRepository
import com.nuvio.tv.data.simkl.SimklMutationService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Routes title ratings exclusively to the selected watch-progress provider.
 *
 * A rating is available only when the selected provider is Trakt or Simkl
 * and that provider is currently authenticated. Nuvio Sync never receives
 * title ratings.
 */
@Singleton
class TrackingRatingCoordinator @Inject constructor(
    private val settingsDataStore: TraktSettingsDataStore,
    private val traktScrobbleService: TraktScrobbleService,
    private val traktEpisodeMappingService: TraktEpisodeMappingService,
    private val simklAuthRepository: SimklAuthRepository,
    private val simklMutationService: SimklMutationService
) {
    suspend fun isAvailable(): Boolean = when (settingsDataStore.watchProgressSource.first()) {
        WatchProgressSource.TRAKT -> traktScrobbleService.isTraktAuthenticated()
        WatchProgressSource.SIMKL -> simklAuthRepository.state.value.isAuthenticated
        WatchProgressSource.NUVIO_SYNC -> false
    }

    suspend fun submit(
        media: TrackingMediaReference,
        rating: Int
    ): Boolean {
        require(rating in 1..10) { "Rating must be between 1 and 10" }
        require(media.hasResolvableIdentity) {
            "Rating requires a media ID or title"
        }

        return when (settingsDataStore.watchProgressSource.first()) {
            WatchProgressSource.TRAKT -> {
                if (!traktScrobbleService.isTraktAuthenticated()) return false
                val item = media.toTraktRatingItem() ?: return false
                traktScrobbleService.postRating(item = item, rating = rating)
                true
            }

            WatchProgressSource.SIMKL -> {
                if (!simklAuthRepository.state.value.isAuthenticated) return false
                simklMutationService.rate(media = media, rating = rating)
            }

            WatchProgressSource.NUVIO_SYNC -> false
        }
    }

    private suspend fun TrackingMediaReference.toTraktRatingItem(): TraktScrobbleItem? {
        val traktIds = TraktIdsDto(
            trakt = ids.trakt.toIntExactOrNull(),
            imdb = ids.imdb?.takeIf(String::isNotBlank),
            tmdb = ids.tmdb.toIntExactOrNull(),
            tvdb = ids.tvdb?.toIntOrNull()
        )

        if (kind == TrackingMediaKind.MOVIE) {
            return TraktScrobbleItem.Movie(
                title = title,
                year = year,
                ids = traktIds
            )
        }

        val episodeReference = episode ?: return null
        val season = episodeReference.season ?: return null
        val contentId = catalog?.contentId
            ?: ids.imdb
            ?: ids.tmdb?.let { "tmdb:$it" }
            ?: return null

        val mapped = traktEpisodeMappingService.resolveEpisodeMapping(
            contentId = contentId,
            contentType = catalog?.contentType ?: "series",
            videoId = catalog?.videoId,
            season = season,
            episode = episodeReference.number
        )

        return TraktScrobbleItem.Episode(
            showTitle = title,
            showYear = year,
            showIds = traktIds,
            season = mapped?.season ?: season,
            number = mapped?.episode ?: episodeReference.number,
            episodeTitle = episodeReference.title
        )
    }

    private fun Long?.toIntExactOrNull(): Int? =
        this?.takeIf { value -> value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }
            ?.toInt()
}
