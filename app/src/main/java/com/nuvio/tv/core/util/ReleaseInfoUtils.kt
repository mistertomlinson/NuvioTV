package com.nuvio.tv.core.util

import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private val YEAR_REGEX = Regex("""\b(19|20)\d{2}\b""")
private val ISO_DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE

/**
 * Returns true when this title should be hidden by Home's "Hide Unreleased Content" filter.
 *
 * Movies need a slightly stricter policy than TV. Once TMDB has actually resolved a movie's
 * home-availability metadata, a recent theatrical date alone is not proof that the movie is
 * streamable. Digital/physical availability wins when present; otherwise a generic release date
 * is accepted only for legacy movies at least one year old.
 *
 * Before TMDB resolution completes we deliberately retain the old generic-date behavior. That
 * keeps a recent, already-streaming movie eligible for Home's existing background enrichment
 * instead of hiding it before we have enough information to decide. Home's enrichment-readiness
 * gate prevents that provisional state from being exposed as a settled row.
 */
fun MetaPreview.isUnreleased(
    today: LocalDate,
    movieHomeAvailabilityResolved: Boolean = false
): Boolean {
    val isMovie = type == ContentType.MOVIE || apiType.equals("movie", ignoreCase = true)

    if (isMovie) {
        parseExactDate(digitalReleaseInfo)?.let { digitalDate ->
            return digitalDate.isAfter(today)
        }

        val genericDate = parseGenericReleaseDate(released, releaseInfo)

        // Until home availability has genuinely been resolved, preserve the old behavior so
        // recent streaming releases are not filtered out before TMDB can inspect them.
        if (!movieHomeAvailabilityResolved) {
            return genericDate?.isAfter(today) == true
        }

        // A resolved recent movie with no Digital/Physical date is theatrical-only/unknown for
        // Nuvio purposes. Keep the generic fallback solely for legacy catalog coverage.
        if (genericDate != null) {
            if (genericDate.isAfter(today)) return true
            return genericDate.isAfter(today.minusYears(1))
        }

        // Missing/unparseable dates remain fail-open. A metadata failure must not permanently
        // remove a title whose actual availability cannot be established.
        return false
    }

    // TV continues to use the normal first-air/release date policy.
    return parseGenericReleaseDate(released, releaseInfo)?.isAfter(today) == true
}

/**
 * Old enrichment caches may contain a theatrical date in digitalReleaseInfo because the previous
 * TMDB mapper used theatrical as a fallback. Revalidate only recent, already-opened movies; old
 * catalog titles keep the one-year fallback and future movies are already hidden by their normal
 * release date. This keeps the migration work small and off the UI thread.
 */
fun MetaPreview.needsHomeAvailabilityVerification(today: LocalDate): Boolean {
    val isMovie = type == ContentType.MOVIE || apiType.equals("movie", ignoreCase = true)
    if (!isMovie) return false

    val genericDate = parseGenericReleaseDate(released, releaseInfo) ?: return false
    if (genericDate.isAfter(today)) return false

    return genericDate.isAfter(today.minusYears(1))
}

fun CatalogRow.filterReleasedItems(
    today: LocalDate,
    movieHomeAvailabilityResolved: (MetaPreview) -> Boolean = { false }
): CatalogRow {
    val filtered = items.filterNot { item ->
        val resolved = movieHomeAvailabilityResolved(item)
        val hidden = item.isUnreleased(
            today = today,
            movieHomeAvailabilityResolved = resolved
        )

        hidden
    }
    return if (filtered.size == items.size) this else copy(items = filtered)
}

private fun parseGenericReleaseDate(
    released: String?,
    releaseInfo: String?
): LocalDate? {
    parseExactDate(released)?.let { return it }
    parseExactDate(releaseInfo)?.let { return it }

    val year = releaseInfo
        ?.let { YEAR_REGEX.find(it)?.value }
        ?.toIntOrNull()
        ?: return null

    // Year-only metadata is approximate by definition. January 1 keeps the legacy one-year
    // cutoff deterministic while retaining the previous "future year" behavior for TV.
    return LocalDate.of(year, 1, 1)
}

private fun parseExactDate(raw: String?): LocalDate? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val dateOnly = value.substringBefore('T')
    return try {
        LocalDate.parse(dateOnly, ISO_DATE_FORMATTER)
    } catch (_: DateTimeParseException) {
        null
    }
}
