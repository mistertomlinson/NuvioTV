package com.nuvio.tv.data.local

import java.util.Locale

fun ReleaseReminderRecord.matchesContentIdentity(
    candidateId: String,
    candidateType: String,
    candidateImdbId: String? = null
): Boolean {
    if (
        normalizeReleaseReminderType(itemType) !=
        normalizeReleaseReminderType(candidateType)
    ) {
        return false
    }

    val reminderTokens = releaseReminderIdentityTokens(
        rawId = itemId,
        imdbId = imdbId,
        tmdbId = tmdbId,
        traktId = traktId,
        simklId = simklId
    )

    val candidateTokens = releaseReminderIdentityTokens(
        rawId = candidateId,
        imdbId = candidateImdbId,
        tmdbId = null,
        traktId = null,
        simklId = null
    )

    return reminderTokens
        .intersect(candidateTokens)
        .isNotEmpty()
}

private fun releaseReminderIdentityTokens(
    rawId: String,
    imdbId: String?,
    tmdbId: Int?,
    traktId: Int?,
    simklId: Long?
): Set<String> = buildSet {
    val raw = rawId.trim().lowercase(Locale.ROOT)

    if (raw.isNotBlank()) {
        add("raw:$raw")
    }

    Regex("tt\\d+", RegexOption.IGNORE_CASE)
        .find(raw)
        ?.value
        ?.lowercase(Locale.ROOT)
        ?.let { add("imdb:$it") }

    imdbId
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it.isNotBlank() }
        ?.let { add("imdb:$it") }

    tmdbId?.let { add("tmdb:$it") }
    traktId?.let { add("trakt:$it") }
    simklId?.let { add("simkl:$it") }

    if (raw.startsWith("tmdb:")) {
        add(
            "tmdb:" +
                raw.removePrefix("tmdb:")
                    .substringBefore(':')
        )
    }

    if (raw.startsWith("trakt:")) {
        add(
            "trakt:" +
                raw.removePrefix("trakt:")
                    .substringBefore(':')
        )
    }

    if (raw.startsWith("simkl:")) {
        add(
            "simkl:" +
                raw.removePrefix("simkl:")
                    .substringBefore(':')
        )
    }
}

private fun normalizeReleaseReminderType(
    raw: String
): String =
    when (raw.trim().lowercase(Locale.ROOT)) {
        "film" -> "movie"
        "show", "tv" -> "series"
        else -> raw.trim().lowercase(Locale.ROOT)
    }
