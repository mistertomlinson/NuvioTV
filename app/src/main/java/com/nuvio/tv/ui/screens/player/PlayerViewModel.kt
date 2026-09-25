package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.SharingStarted
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.data.repository.parseContentIds
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.TrackingScrobbleCoordinator
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.StreamLinkCacheDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.data.repository.CreditAnalyzerRepository
import com.nuvio.tv.data.repository.MDBListRepository
import com.nuvio.tv.data.repository.ParentalGuideRepository
import com.nuvio.tv.data.repository.SkipIntroRepository
import com.nuvio.tv.data.repository.TraktEpisodeMappingService
import com.nuvio.tv.data.repository.TrackingRatingCoordinator
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val watchProgressRepository: WatchProgressRepository,
    private val metaRepository: MetaRepository,
    private val libraryRepository: LibraryRepository,
    private val streamRepository: StreamRepository,
    private val addonRepository: AddonRepository,
    private val pluginManager: PluginManager,
    private val subtitleRepository: com.nuvio.tv.domain.repository.SubtitleRepository,
    private val parentalGuideRepository: ParentalGuideRepository,
    private val trackingScrobbleCoordinator: TrackingScrobbleCoordinator,
    private val trackingRatingCoordinator: TrackingRatingCoordinator,
    private val traktEpisodeMappingService: TraktEpisodeMappingService,
    private val skipIntroRepository: SkipIntroRepository,
    private val creditAnalyzerRepository: CreditAnalyzerRepository,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val mdbListRepository: MDBListRepository,
    private val trailerService: TrailerService,
    private val trailerSettingsDataStore: TrailerSettingsDataStore,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val streamLinkCacheDataStore: StreamLinkCacheDataStore,
    private val layoutPreferenceDataStore: com.nuvio.tv.data.local.LayoutPreferenceDataStore,
    private val watchedItemsPreferences: com.nuvio.tv.data.local.WatchedItemsPreferences,
    savedStateHandle: SavedStateHandle,
    private val directDebridResolver: DirectDebridResolver,
    private val debridSettingsDataStore: DebridSettingsDataStore
) : ViewModel() {

    private val controller = PlayerRuntimeController(
        context = context,
        watchProgressRepository = watchProgressRepository,
        metaRepository = metaRepository,
        streamRepository = streamRepository,
        addonRepository = addonRepository,
        pluginManager = pluginManager,
        subtitleRepository = subtitleRepository,
        parentalGuideRepository = parentalGuideRepository,
        trackingScrobbleCoordinator = trackingScrobbleCoordinator,
        trackingRatingCoordinator = trackingRatingCoordinator,
        traktEpisodeMappingService = traktEpisodeMappingService,
        skipIntroRepository = skipIntroRepository,
        creditAnalyzerRepository = creditAnalyzerRepository,
        tmdbService = tmdbService,
        tmdbMetadataService = tmdbMetadataService,
        tmdbSettingsDataStore = tmdbSettingsDataStore,
        mdbListRepository = mdbListRepository,
        trailerService = trailerService,
        trailerSettingsDataStore = trailerSettingsDataStore,
        playerSettingsDataStore = playerSettingsDataStore,
        streamLinkCacheDataStore = streamLinkCacheDataStore,
        layoutPreferenceDataStore = layoutPreferenceDataStore,
        watchedItemsPreferences = watchedItemsPreferences,
        savedStateHandle = savedStateHandle,
        scope = viewModelScope,
        directDebridResolver = directDebridResolver,
        debridSettingsDataStore = debridSettingsDataStore
    )

    val uiState: StateFlow<PlayerUiState>
        get() = controller.uiState

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val postPlayRecommendationInLibrary: StateFlow<Boolean> =
        controller.uiState
            .map { state ->
                state.postPlayRecommendation?.let { recommendation ->
                    recommendation.id to recommendation.contentType
                }
            }
            .distinctUntilChanged()
            .flatMapLatest { identity ->
                if (identity == null) {
                    flowOf(false)
                } else {
                    libraryRepository.isInWatchlist(
                        itemId = identity.first,
                        itemType = identity.second
                    )
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = false
            )

    /*
     * Canonical generated-logo geometry shared with Home and Details.
     *
     * Home permits the Small geometry only when aggregate platforms and the
     * full-width icon row are both active. Otherwise Home forces Large.
     */
    val fallbackTitleLogoLarge =
        kotlinx.coroutines.flow.combine(
            layoutPreferenceDataStore.aggregateStreamingPlatformsEnabled,
            layoutPreferenceDataStore.fullWidthIconRowEnabled,
            layoutPreferenceDataStore.heroMetadataLarge
        ) { aggregatePlatformsEnabled, fullWidthIconRowEnabled, heroMetadataLarge ->
            if (
                aggregatePlatformsEnabled &&
                fullWidthIconRowEnabled
            ) {
                heroMetadataLarge
            } else {
                true
            }
        }.distinctUntilChanged()

    val exoPlayer: ExoPlayer?
        get() = controller.exoPlayer

    private val _postPlayLibraryMessage =
        kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    val postPlayLibraryMessage: StateFlow<String?> =
        _postPlayLibraryMessage

    fun clearPostPlayLibraryMessage() {
        _postPlayLibraryMessage.value = null
    }

    fun togglePostPlayRecommendationLibrary(wasInWatchlist: Boolean) {
        val recommendation = controller.uiState.value.postPlayRecommendation ?: return

        viewModelScope.launch {
            runCatching {
                libraryRepository.toggleDefaultWithSignal(
                    recommendation.toLibraryEntryInput()
                )
            }.onSuccess {
                _postPlayLibraryMessage.value = context.getString(
                    if (wasInWatchlist) {
                        R.string.detail_removed_from_library
                    } else {
                        R.string.detail_added_to_library
                    }
                )
            }.onFailure { error ->
                android.util.Log.w(
                    "PlayerViewModel",
                    "Failed to update post-play My List: ${error.message}"
                )
            }
        }
    }

    fun getCurrentStreamUrl(): String = controller.getCurrentStreamUrl()

    fun getCurrentHeaders(): Map<String, String> = controller.getCurrentHeaders()

    fun stopAndRelease() {
        controller.stopAndRelease()
    }

    fun willPublishCwProgressOnRelease(): Boolean =
        controller.willPublishCwProgressOnRelease()

    fun isNextEpisodeAutoPlayEnabled(): Boolean =
        controller.streamAutoPlayNextEpisodeEnabledSetting

    fun scheduleHideControls() {
        controller.scheduleHideControls()
    }

    fun onUserInteraction() {
        controller.onUserInteraction()
    }

    fun hideControls() {
        controller.hideControls()
    }

    fun attachHostActivity(activity: android.app.Activity?) {
        controller.attachHostActivity(activity)
    }

    fun startInitialPlaybackIfNeeded() {
        controller.startInitialPlaybackIfNeeded()
    }

    fun onEvent(event: PlayerEvent) {
        controller.onEvent(event)
    }

    override fun onCleared() {
        controller.onCleared()
        super.onCleared()
    }
}

private fun PostPlayRecommendation.toLibraryEntryInput(): LibraryEntryInput {
    val year = Regex("(\\d{4})")
        .find(releaseInfo ?: "")
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()

    val parsedIds = parseContentIds(id)

    return LibraryEntryInput(
        itemId = id,
        itemType = contentType,
        title = title,
        year = year,
        traktId = parsedIds.trakt,
        imdbId = parsedIds.imdb,
        tmdbId = parsedIds.tmdb,
        poster = poster,
        background = backdrop,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        genres = genres
    )
}
