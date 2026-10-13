package com.nuvio.tv.data.trailer

private val FALLBACK_TRAILER_VARIANTS = setOf(
    "trailer",
    "trialer",
    "traler",
    "trailor",
    "trailerr",
    "traeler",
    "trailr"
)

private val FALLBACK_TV_PROMO_VARIANTS = setOf(
    "promo",
    "preview",
    "teaser"
)

private val FALLBACK_EXPLICIT_YEAR_REGEX = Regex("""\b(?:19|20)\d{2}\b""")

internal data class TvTrailerCandidateEvaluation(
    val hasFullTitle: Boolean,
    val hasPromoWord: Boolean,
    val wrongMedium: Boolean,
    val yearMatches: Boolean,
    val conflictingYear: Boolean,
    val networkMatches: Boolean,
    val hasSeasonOne: Boolean,
    val titleLeading: Boolean,
    val ambiguousTitle: Boolean,
    val identityCorroborated: Boolean,
    val accepted: Boolean,
    val tier: Int
)

/**
 * Applies the identity policy for YouTube TV-trailer fallback results.
 *
 * Ambiguous one-meaningful-word titles deliberately fail closed: when a known
 * network/platform is available, the candidate must name it; without a known
 * network/platform, YouTube fallback is not trusted for that title. Season/year
 * language may rank a candidate but cannot independently prove identity.
 *
 * An explicit contradictory year is also a hard rejection. A candidate that
 * omits the year is still eligible because official uploads often do so.
 */
internal fun evaluateTvTrailerCandidate(
    requestedTitle: String,
    requestedYear: String?,
    requestedNetwork: String?,
    candidateTitle: String
): TvTrailerCandidateEvaluation {
    val normalizedTitle = normalizeTrailerMatchText(requestedTitle)
    val normalizedCandidate = normalizeTrailerMatchText(candidateTitle)
    val normalizedNetwork = normalizeTrailerMatchText(requestedNetwork.orEmpty())
    val normalizedYear =
        requestedYear
            ?.trim()
            ?.takeIf { it.matches(Regex("""\d{4}""")) }

    val candidateWords =
        normalizedCandidate
            .split(" ")
            .filter { it.isNotBlank() }

    val titleIdentityWords =
        normalizedTitle
            .split(" ")
            .filter { it.isNotBlank() }
            .filterNot { it in setOf("the", "a", "an") }

    val ambiguousTitle = titleIdentityWords.size <= 1

    val hasFullTitle =
        containsNormalizedTrailerPhrase(
            normalizedText = normalizedCandidate,
            normalizedPhrase = normalizedTitle
        )

    val hasTrailerWord =
        candidateWords.any { it in FALLBACK_TRAILER_VARIANTS }

    val hasExplicitTvPromo =
        candidateWords.any { it in FALLBACK_TV_PROMO_VARIANTS }

    val hasPromoWord = hasTrailerWord || hasExplicitTvPromo

    val wrongMedium =
        candidateWords.any { word ->
            word == "movie" || word == "film"
        }

    val candidateYears =
        FALLBACK_EXPLICIT_YEAR_REGEX
            .findAll(normalizedCandidate)
            .map { it.value }
            .toSet()

    val yearMatches =
        normalizedYear != null && normalizedYear in candidateYears

    val conflictingYear =
        normalizedYear != null &&
            candidateYears.isNotEmpty() &&
            normalizedYear !in candidateYears

    val networkMatches =
        normalizedNetwork.isNotBlank() &&
            containsNormalizedTrailerPhrase(
                normalizedText = normalizedCandidate,
                normalizedPhrase = normalizedNetwork
            )

    val hasSeasonOne =
        containsNormalizedTrailerPhrase(
            normalizedText = normalizedCandidate,
            normalizedPhrase = "season 1"
        )

    val titleLeading =
        normalizedCandidate == normalizedTitle ||
            normalizedCandidate.startsWith("$normalizedTitle ")

    val identityCorroborated =
        !ambiguousTitle ||
            (normalizedNetwork.isNotBlank() && networkMatches)

    val accepted =
        hasFullTitle &&
            hasPromoWord &&
            !wrongMedium &&
            !conflictingYear &&
            identityCorroborated

    /*
     * Network/platform evidence is intentionally stronger than generic launch
     * wording. For ambiguous titles every accepted result necessarily has a
     * network match; for distinctive titles the lower tiers preserve existing
     * fallback coverage.
     */
    val tier =
        when {
            networkMatches && titleLeading && hasSeasonOne && hasTrailerWord -> 5
            networkMatches -> 4
            titleLeading && hasSeasonOne && hasTrailerWord -> 3
            titleLeading && hasExplicitTvPromo -> 2
            yearMatches -> 1
            else -> 0
        }

    return TvTrailerCandidateEvaluation(
        hasFullTitle = hasFullTitle,
        hasPromoWord = hasPromoWord,
        wrongMedium = wrongMedium,
        yearMatches = yearMatches,
        conflictingYear = conflictingYear,
        networkMatches = networkMatches,
        hasSeasonOne = hasSeasonOne,
        titleLeading = titleLeading,
        ambiguousTitle = ambiguousTitle,
        identityCorroborated = identityCorroborated,
        accepted = accepted,
        tier = tier
    )
}

private fun normalizeTrailerMatchText(value: String): String =
    value
        .replace("\\u0026", "&")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

private fun containsNormalizedTrailerPhrase(
    normalizedText: String,
    normalizedPhrase: String
): Boolean {
    if (normalizedPhrase.isBlank()) return false
    return " $normalizedText ".contains(" $normalizedPhrase ")
}
