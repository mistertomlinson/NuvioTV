package com.nuvio.tv.data.simkl

import javax.inject.Inject

class SimklSyncEngine internal constructor(
    private val remote: SimklSyncRemote,
    private val nowEpochMs: () -> Long
) {
    @Inject
    constructor(remote: SimklSyncRemote) : this(remote, System::currentTimeMillis)

    suspend fun synchronize(current: SimklSyncSnapshot): SimklSyncSnapshot {
        if (!current.isInitialized) return initialSync()

        val activities = remote.fetchActivities()
        if (activities.all == current.watermark) {
            return current.copy(
                activities = activities,
                lastCheckedAtEpochMs = nowEpochMs()
            )
        }
        if (current.watermark == null) return initialSync()

        var entries = current.entries
        if (hasAllItemsActivityChanged(current.activities, activities)) {
            val delta = remote.fetchAllItems(SimklAllItemsRequest.Changes(current.watermark))
            entries = mergeSimklDelta(entries, delta)
        }
        if (hasRemovalActivityChanged(current.activities, activities)) {
            entries = reconcileRemovedSimklEntries(
                entries,
                remote.fetchAllItems(SimklAllItemsRequest.CurrentIds)
            )
        }
        val playback = if (hasPlaybackActivityChanged(current.activities, activities)) {
            remote.fetchPlayback()
        } else {
            current.playback
        }
        val now = nowEpochMs()
        return current.copy(
            watermark = activities.all,
            activities = activities,
            entries = entries,
            playback = playback,
            lastSyncedAtEpochMs = now,
            lastCheckedAtEpochMs = now
        ).reconcileWatchedPlayback()
    }

    private suspend fun initialSync(): SimklSyncSnapshot {
        val entries = buildList {
            SimklMediaType.entries.forEach { type ->
                addAll(remote.fetchAllItems(SimklAllItemsRequest.Bootstrap(type)).entriesFor(type))
            }
        }
        val playback = remote.fetchPlayback()
        val activities = remote.fetchActivities()
        val now = nowEpochMs()
        return SimklSyncSnapshot(
            isInitialized = true,
            watermark = activities.all,
            activities = activities,
            entries = entries.coalesceSharedIdentity(),
            playback = playback,
            lastSyncedAtEpochMs = now,
            lastCheckedAtEpochMs = now
        )
    }
}

fun mergeSimklDelta(
    current: List<SimklLibraryEntry>,
    delta: SimklAllItemsResponse
): List<SimklLibraryEntry> {
    val merged = current.coalesceSharedIdentity().toMutableList()
    delta.presentTypes().forEach { type ->
        delta.entriesFor(type).forEach { entry ->
            if (entry.stableKey() != null) {
                val matches = merged.filter { existing ->
                    existing.sharesIdentityWith(entry)
                }
                merged.removeAll(matches.toSet())
                merged += matches.fold(entry) { incoming, fallback ->
                    incoming.withPresentationFallback(fallback)
                }
            }
        }
    }
    return merged.coalesceSharedIdentity().sortedWith(simklEntryComparator)
}

private fun List<SimklLibraryEntry>.coalesceSharedIdentity(): List<SimklLibraryEntry> {
    val result = mutableListOf<SimklLibraryEntry>()
    for (candidate in this) {
        val existingIndex = result.indexOfFirst { existing ->
            existing.sharesIdentityWith(candidate)
        }
        if (existingIndex < 0) {
            result += candidate
        } else {
            val existing = result[existingIndex]
            val preferred = if (candidate.presentationRichness > existing.presentationRichness) {
                candidate.withPresentationFallback(existing)
            } else {
                existing.withPresentationFallback(candidate)
            }
            result[existingIndex] = preferred
        }
    }
    return result
}

private fun SimklLibraryEntry.sharesIdentityWith(other: SimklLibraryEntry): Boolean {
    if (mediaType != other.mediaType) return false
    val candidateMedia = media ?: return false
    val otherMedia = other.media ?: return false
    return candidateMedia.sharesExternalIdentityWith(otherMedia)
}

private fun SimklMedia.sharesExternalIdentityWith(other: SimklMedia): Boolean {
    ids.simklIdValue()?.let { id ->
        other.ids.simklIdValue()?.let { return id == it }
    }
    val prioritizedKeys = listOf("mal", "anidb", "anilist", "kitsu", "tvdb", "imdb", "tmdb")
    prioritizedKeys.forEach { key ->
        val id = ids.idValue(key)
        val otherId = other.ids.idValue(key)
        if (id != null && otherId != null) return id.equals(otherId, ignoreCase = true)
    }
    return false
}

private val SimklLibraryEntry.presentationRichness: Int
    get() =
        (if (media?.ids?.simklIdValue() != null) 8 else 0) +
            (if (!media?.ids?.idValue("imdb").isNullOrBlank()) 4 else 0) +
            (if (!media?.ids?.idValue("tmdb").isNullOrBlank()) 2 else 0) +
            (if (!resolvedPosterUrl().isNullOrBlank()) 2 else 0) +
            (if (media?.year != null) 1 else 0)

/**
 * Simkl delta responses can omit presentation fields. Keep richer cached artwork
 * until the delta supplies canonical Simkl artwork of its own.
 */
private fun SimklLibraryEntry.withPresentationFallback(
    fallback: SimklLibraryEntry
): SimklLibraryEntry {
    val incomingMedia = media
    val mergedMedia = incomingMedia?.mergeMissing(fallback.media) ?: fallback.media
    val hasCanonicalPoster = !incomingMedia?.poster.isNullOrBlank()
    return copy(
        animeType = animeType ?: fallback.animeType,
        localPosterUrl = if (hasCanonicalPoster) {
            localPosterUrl
        } else {
            localPosterUrl?.takeIf(String::isNotBlank) ?: fallback.localPosterUrl
        },
        show = mergedMedia.takeIf { mediaType != SimklMediaType.MOVIES },
        movie = mergedMedia.takeIf { mediaType == SimklMediaType.MOVIES }
    )
}

fun reconcileRemovedSimklEntries(
    current: List<SimklLibraryEntry>,
    authoritative: SimklAllItemsResponse
): List<SimklLibraryEntry> {
    val allowedKeys = SimklMediaType.entries.flatMapTo(mutableSetOf()) { type ->
        authoritative.entriesFor(type).mapNotNull(SimklLibraryEntry::stableKey)
    }
    return current.filter { entry -> entry.stableKey() in allowedKeys }
        .sortedWith(simklEntryComparator)
}

private fun hasAllItemsActivityChanged(previous: SimklActivities?, current: SimklActivities): Boolean {
    if (previous == null) return true
    return SimklMediaType.entries.any { type ->
        current.domain(type).hasAllItemsActivityChangedFrom(previous.domain(type))
    }
}

private fun SimklActivityDomain.hasAllItemsActivityChangedFrom(previous: SimklActivityDomain): Boolean =
    ratedAt != previous.ratedAt ||
        plantowatch != previous.plantowatch ||
        watching != previous.watching ||
        completed != previous.completed ||
        hold != previous.hold ||
        dropped != previous.dropped

private fun hasRemovalActivityChanged(previous: SimklActivities?, current: SimklActivities): Boolean =
    previous == null ||
    SimklMediaType.entries.any { type ->
        previous.domain(type).removedFromList != current.domain(type).removedFromList
    }

private fun hasPlaybackActivityChanged(previous: SimklActivities?, current: SimklActivities): Boolean =
    previous == null ||
    SimklMediaType.entries.any { type ->
        previous.domain(type).playback != current.domain(type).playback
    }

private val simklEntryComparator = compareBy<SimklLibraryEntry>(
    { entry -> entry.mediaType.ordinal },
    { entry -> entry.media?.title.orEmpty().lowercase() },
    { entry -> entry.stableKey().orEmpty() }
)
