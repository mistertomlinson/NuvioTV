package com.nuvio.tv.core.player

/**
 * Keep episode stream-link reuse separate from the legacy namespace, which
 * could be polluted by episode switches saving under the previous video ID.
 * Movie reuse keys remain unchanged.
 */
internal fun playbackStreamLinkCacheKey(contentType: String?, videoId: String?): String? {
    val type = contentType?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    val id = videoId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val prefix = if (type == "series" || type == "tv" || type == "anime") "episode-v2|" else ""
    return "$prefix$type|$id"
}
