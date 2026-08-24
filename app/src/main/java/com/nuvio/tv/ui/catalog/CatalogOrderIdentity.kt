package com.nuvio.tv.ui.catalog

private const val WATCHLY_ADDON_KEY_PREFIX = "com.bimal.watchly_"
private const val WATCHLY_ORDER_ANCHOR_PREFIX =
    "__nuvio_internal_watchly_group_order_anchor__:"

/**
 * Returns the stable logical Watchly group represented by a concrete Nuvio
 * catalog key. The media type is part of the identity so movie and series
 * catalogs can never claim each other's saved positions.
 */
fun watchlyCatalogGroup(key: String): String? {
    if (!key.startsWith(WATCHLY_ADDON_KEY_PREFIX)) return null

    val remainder = key.removePrefix(WATCHLY_ADDON_KEY_PREFIX)
    val type = when {
        remainder.startsWith("movie_") -> "movie"
        remainder.startsWith("series_") -> "series"
        else -> return null
    }
    val catalogId = remainder.removePrefix("${type}_")

    val logicalGroup = when {
        catalogId.startsWith("watchly.watched.") -> "watchly.watched"
        catalogId.startsWith("watchly.theme.") -> "watchly.theme"
        catalogId == "watchly.rec" -> "watchly.rec"
        catalogId == "watchly.creators" -> "watchly.creators"
        catalogId == "watchly.all.loved" -> "watchly.all.loved"
        catalogId.startsWith("watchly.loved.") -> "watchly.loved"
        catalogId == "watchly.liked.all" -> "watchly.liked"
        else -> return null
    }

    return "$logicalGroup.$type"
}

fun watchlyOrderAnchor(group: String): String =
    WATCHLY_ORDER_ANCHOR_PREFIX + group

/**
 * Accept both the new stable anchor and legacy concrete Watchly keys.
 * Legacy keys are intentionally supported so existing installs migrate
 * without requiring the user to reorder catalogs again.
 */
fun watchlySavedGroup(key: String): String? {
    if (key.startsWith(WATCHLY_ORDER_ANCHOR_PREFIX)) {
        return key.removePrefix(WATCHLY_ORDER_ANCHOR_PREFIX)
            .takeIf { it.isNotBlank() }
    }
    return watchlyCatalogGroup(key)
}

/**
 * Persist one stable token per logical Watchly group instead of transient
 * catalog IDs. The first occurrence determines the group's saved position.
 */
fun collapseWatchlyOrderKeys(keys: List<String>): List<String> {
    val seen = mutableSetOf<String>()
    val result = mutableListOf<String>()

    keys.forEach { key ->
        val persistedKey = watchlySavedGroup(key)
            ?.let(::watchlyOrderAnchor)
            ?: key

        if (seen.add(persistedKey)) {
            result.add(persistedKey)
        }
    }

    return result
}
