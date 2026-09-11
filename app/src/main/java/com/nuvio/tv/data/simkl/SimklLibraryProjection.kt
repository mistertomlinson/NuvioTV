package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingListStatus
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.PosterShape

const val SIMKL_STATUS_SELECTION_GROUP = "simkl:status"

data class SimklLibraryStatusDefinition(
    val status: SimklListStatus,
    val key: String,
    val title: String,
    val trackingStatus: TrackingListStatus,
    val supportedContentTypes: Set<String>,
    val isMembershipDestination: Boolean = true
)

data class SimklLibraryProjection(
    val items: List<LibraryEntry>,
    val itemsByStatus: Map<String, List<LibraryEntry>>,
    val tabs: List<LibraryListTab>
)

val simklLibraryStatusDefinitions = listOf(
    SimklLibraryStatusDefinition(
        SimklListStatus.WATCHING,
        "simkl:status:watching",
        "Watching",
        TrackingListStatus.WATCHING,
        setOf("series", "anime")
    ),
    SimklLibraryStatusDefinition(
        SimklListStatus.PLAN_TO_WATCH,
        "simkl:status:plantowatch",
        "Plan to Watch",
        TrackingListStatus.PLAN_TO_WATCH,
        setOf("movie", "series", "anime")
    ),
    SimklLibraryStatusDefinition(
        SimklListStatus.ON_HOLD,
        "simkl:status:hold",
        "On Hold",
        TrackingListStatus.ON_HOLD,
        setOf("series", "anime")
    ),
    SimklLibraryStatusDefinition(
        SimklListStatus.COMPLETED,
        "simkl:status:completed",
        "Completed",
        TrackingListStatus.COMPLETED,
        setOf("movie", "series", "anime"),
        isMembershipDestination = false
    ),
    SimklLibraryStatusDefinition(
        SimklListStatus.DROPPED,
        "simkl:status:dropped",
        "Dropped",
        TrackingListStatus.DROPPED,
        setOf("movie", "series", "anime")
    )
)

fun SimklSyncSnapshot.toSimklLibraryProjection(): SimklLibraryProjection {
    val itemsByStatus = simklLibraryStatusDefinitions.associate { definition ->
        definition.key to entries.asSequence()
            .filter { entry -> entry.status == definition.status }
            .filterNot { entry ->
                definition.status == SimklListStatus.PLAN_TO_WATCH &&
                    entry.mediaType == SimklMediaType.MOVIES &&
                    entry.media?.let { media ->
                        playback.any { session ->
                            session.media?.matchesTarget(media) == true
                        }
                    } == true
            }
            .mapNotNull { entry -> entry.toLibraryEntry(definition.key, lastSyncedAtEpochMs) }
            .toList()
            .coalesceSharedExternalIdentity()
            .sortedByDescending(LibraryEntry::listedAt)
    }
    val tabs = simklLibraryStatusDefinitions.map { definition ->
        LibraryListTab(
            key = definition.key,
            title = definition.title,
            type = if (definition.status == SimklListStatus.PLAN_TO_WATCH) {
                LibraryListTab.Type.WATCHLIST
            } else {
                LibraryListTab.Type.STATUS
            },
            trackingProviderId = TrackingProviderId.SIMKL.storageId,
            selectionGroup = SIMKL_STATUS_SELECTION_GROUP,
            supportedContentTypes = definition.supportedContentTypes,
            isMembershipDestination = definition.isMembershipDestination
        )
    }
    val projectedItems = itemsByStatus.values.flatten()
        .coalesceSharedExternalIdentity()
        .sortedByDescending(LibraryEntry::listedAt)
    return SimklLibraryProjection(
        items = projectedItems,
        itemsByStatus = itemsByStatus,
        tabs = tabs
    )
}

/**
 * A locally-created Simkl entry can initially be keyed by TMDB and later return
 * from Simkl with IMDb/Simkl IDs. Collapse those aliases without relying on a
 * title match, which could incorrectly merge remakes or similarly named media.
 */
private fun List<LibraryEntry>.coalesceSharedExternalIdentity(): List<LibraryEntry> {
    val result = mutableListOf<LibraryEntry>()
    for (candidate in this) {
        val existingIndex = result.indexOfFirst { existing ->
            existing.sharesExternalIdentityWith(candidate)
        }
        if (existingIndex < 0) {
            result += candidate
        } else {
            result[existingIndex] = result[existingIndex].mergeDuplicate(candidate)
        }
    }
    return result
}

private fun LibraryEntry.sharesExternalIdentityWith(other: LibraryEntry): Boolean {
    if (!type.equals(other.type, ignoreCase = true)) return false
    if (simklId != null && other.simklId != null) return simklId == other.simklId
    if (!imdbId.isNullOrBlank() && !other.imdbId.isNullOrBlank()) {
        return imdbId.equals(other.imdbId, ignoreCase = true)
    }
    if (tmdbId != null && other.tmdbId != null) return tmdbId == other.tmdbId
    return id == other.id
}

private fun LibraryEntry.mergeDuplicate(other: LibraryEntry): LibraryEntry {
    val (preferred, fallback) = if (identityRichness >= other.identityRichness) {
        this to other
    } else {
        other to this
    }
    return preferred.copy(
        poster = preferred.poster?.takeIf(String::isNotBlank) ?: fallback.poster,
        background = preferred.background?.takeIf(String::isNotBlank) ?: fallback.background,
        logo = preferred.logo?.takeIf(String::isNotBlank) ?: fallback.logo,
        description = preferred.description?.takeIf(String::isNotBlank) ?: fallback.description,
        releaseInfo = preferred.releaseInfo?.takeIf(String::isNotBlank) ?: fallback.releaseInfo,
        imdbRating = preferred.imdbRating ?: fallback.imdbRating,
        genres = preferred.genres.takeIf { it.isNotEmpty() } ?: fallback.genres,
        addonBaseUrl = preferred.addonBaseUrl?.takeIf(String::isNotBlank) ?: fallback.addonBaseUrl,
        listKeys = preferred.listKeys + fallback.listKeys,
        listedAt = maxOf(preferred.listedAt, fallback.listedAt),
        imdbId = preferred.imdbId?.takeIf(String::isNotBlank) ?: fallback.imdbId,
        tmdbId = preferred.tmdbId ?: fallback.tmdbId,
        traktId = preferred.traktId ?: fallback.traktId,
        simklId = preferred.simklId ?: fallback.simklId,
        trackingProviderItemId = preferred.trackingProviderItemId
            ?.takeIf(String::isNotBlank) ?: fallback.trackingProviderItemId,
        trackingSourceUrl = preferred.trackingSourceUrl
            ?.takeIf(String::isNotBlank) ?: fallback.trackingSourceUrl
    )
}

private val LibraryEntry.identityRichness: Int
    get() =
        (if (simklId != null) 8 else 0) +
            (if (!imdbId.isNullOrBlank()) 4 else 0) +
            (if (tmdbId != null) 2 else 0) +
            (if (!poster.isNullOrBlank()) 2 else 0) +
            (if (!releaseInfo.isNullOrBlank()) 1 else 0)

fun simklLibraryStatusDefinition(key: String): SimklLibraryStatusDefinition? =
    simklLibraryStatusDefinitions.firstOrNull { definition -> definition.key == key }

fun simklLibraryStatusDefinition(status: TrackingListStatus): SimklLibraryStatusDefinition? =
    simklLibraryStatusDefinitions.firstOrNull { definition -> definition.trackingStatus == status }

private fun SimklLibraryEntry.toLibraryEntry(
    listKey: String,
    lastSyncedAtEpochMs: Long?
): LibraryEntry? {
    val media = media ?: return null
    val contentId = media.canonicalContentId() ?: return null
    val simklId = media.ids.simklIdValue()?.toLongOrNull()
    val entryType = when (mediaType) {
        SimklMediaType.MOVIES -> "movie"
        SimklMediaType.ANIME -> if (animeType == "movie") "movie" else "series"
        SimklMediaType.SHOWS -> "series"
    }
    return LibraryEntry(
        id = contentId,
        type = entryType,
        name = media.title?.takeIf(String::isNotBlank) ?: contentId,
        poster = resolvedPosterUrl(),
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = media.year?.toString(),
        imdbRating = null,
        genres = emptyList(),
        addonBaseUrl = null,
        listKeys = setOf(listKey),
        listedAt = parseSimklUtcEpochMs(addedToWatchlistAt)
            ?: parseSimklUtcEpochMs(lastWatchedAt)
            ?: lastSyncedAtEpochMs
            ?: 0L,
        imdbId = media.ids.idValue("imdb"),
        tmdbId = media.ids.idValue("tmdb")?.toIntOrNull(),
        simklId = simklId,
        trackingProviderId = TrackingProviderId.SIMKL.storageId,
        trackingProviderItemId = simklId?.let { "simkl:$it" },
        trackingSourceUrl = buildSimklSourceUrl(mediaType, media)
    )
}
