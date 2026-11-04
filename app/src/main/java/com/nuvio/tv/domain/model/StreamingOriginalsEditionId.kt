package com.nuvio.tv.domain.model

import java.net.URLDecoder

/**
 * Streaming Originals may offer multiple verified editions of one movie.
 * The sidecar ID is only a route to its first-party presentation. The canonical
 * IMDb/TMDB ID stays in use for playback, enrichment, library and history.
 */
internal data class StreamingOriginalsEditionRoute(
    val platformId: String,
    val canonicalId: String,
    val providerId: String
)

internal object StreamingOriginalsEditionId {
    private const val PREFIX = "soedition:"
    private val platformPattern = Regex("^[a-z0-9_-]+$")
    private val canonicalPattern = Regex("^(?:tt[0-9]{5,12}|tmdb:[0-9]+)$")

    fun parse(value: String): StreamingOriginalsEditionRoute? {
        if (!value.startsWith(PREFIX) || value.length > 512) return null
        val parts = value.removePrefix(PREFIX).split(':')
        if (parts.size != 3 || !platformPattern.matches(parts[0])) return null
        return try {
            val canonicalId = URLDecoder.decode(parts[1], "UTF-8")
            val providerId = URLDecoder.decode(parts[2], "UTF-8")
            if (!canonicalPattern.matches(canonicalId) ||
                providerId.isBlank() || providerId.length > 256
            ) return null
            StreamingOriginalsEditionRoute(parts[0], canonicalId, providerId)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun detailsId(canonicalId: String, editionDetailId: String?): String {
        val route = editionDetailId?.let(::parse)
        return if (route?.canonicalId == canonicalId) editionDetailId else canonicalId
    }
}
