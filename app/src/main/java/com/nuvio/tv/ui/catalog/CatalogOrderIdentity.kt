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

const val SEASONAL_SPOTLIGHT_ADDON_ID =
    "community.seasonalspotlight"

const val SEASONAL_SPOTLIGHT_ORDER_ANCHOR =
    "__nuvio_internal_seasonal_spotlight_order_anchor__"

private const val SEASONAL_SPOTLIGHT_GROUP =
    "seasonalspotlight"

/**
 * Seasonal Spotlight changes row names and active collections throughout
 * the year, but all of its concrete catalog keys represent one saved Home
 * position.
 *
 * Accept both the stable internal anchor and legacy/current concrete keys
 * so existing installs retain their chosen position.
 */
fun seasonalSpotlightGroup(
    key: String
): String? {
    return if (
        key == SEASONAL_SPOTLIGHT_ORDER_ANCHOR ||
        key.startsWith(
            "${SEASONAL_SPOTLIGHT_ADDON_ID}_"
        )
    ) {
        SEASONAL_SPOTLIGHT_GROUP
    } else {
        null
    }
}

/**
 * Collapse all Seasonal Spotlight members to one persistent order anchor.
 * The first Seasonal Spotlight occurrence determines the group's position.
 */
fun collapseSeasonalSpotlightOrderKeys(
    keys: List<String>
): List<String> {
    val seen =
        mutableSetOf<String>()

    val result =
        mutableListOf<String>()

    keys.forEach { key ->
        val persistedKey =
            if (
                seasonalSpotlightGroup(
                    key
                ) != null
            ) {
                SEASONAL_SPOTLIGHT_ORDER_ANCHOR
            } else {
                key
            }

        if (seen.add(persistedKey)) {
            result.add(persistedKey)
        }
    }

    return result
}

/**
 * Expand saved dynamic catalog identities into the current concrete Home
 * catalogs.
 *
 * Watchly keeps its existing logical-group behavior. Seasonal Spotlight
 * receives equivalent stable-group treatment without sharing identities
 * with Watchly.
 */
fun reconcileDynamicCatalogOrder(
    defaultOrder: List<String>,
    savedOrderKeys: List<String>,
    myListKey: String
): List<String> {
    val availableSet =
        defaultOrder.toSet()

    val watchlyMembersByGroup =
        linkedMapOf<
            String,
            MutableList<String>
        >()

    defaultOrder.forEach { key ->
        watchlyCatalogGroup(key)
            ?.let { group ->
                watchlyMembersByGroup
                    .getOrPut(group) {
                        mutableListOf()
                    }
                    .add(key)
            }
    }

    val seasonalMembers =
        defaultOrder.filter { key ->
            seasonalSpotlightGroup(
                key
            ) != null
        }

    val consumedWatchlyGroups =
        mutableSetOf<String>()

    var consumedSeasonalSpotlight =
        false

    val savedValid =
        mutableListOf<String>()

    savedOrderKeys.forEach { savedKey ->
        val savedWatchlyGroup =
            watchlySavedGroup(
                savedKey
            )

        val savedSeasonalGroup =
            seasonalSpotlightGroup(
                savedKey
            )

        when {
            savedWatchlyGroup != null -> {
                if (
                    consumedWatchlyGroups.add(
                        savedWatchlyGroup
                    )
                ) {
                    savedValid.addAll(
                        watchlyMembersByGroup[
                            savedWatchlyGroup
                        ].orEmpty()
                    )
                }
            }

            savedSeasonalGroup != null -> {
                if (
                    !consumedSeasonalSpotlight
                ) {
                    consumedSeasonalSpotlight =
                        true

                    savedValid.addAll(
                        seasonalMembers
                    )
                }
            }

            savedKey in availableSet -> {
                savedValid.add(
                    savedKey
                )
            }
        }
    }

    val savedSet =
        savedValid.toSet()

    val missing =
        defaultOrder.filterNot {
            it in savedSet
        }

    val mergedOrder =
        savedValid.toMutableList()

    val insertedMissingWatchlyGroups =
        mutableSetOf<String>()

    missing.forEach { missingKey ->
        if (missingKey == myListKey) {
            mergedOrder.add(
                0,
                missingKey
            )
            return@forEach
        }

        /*
         * If Seasonal Spotlight has no persisted anchor yet, keep any current
         * slots adjacent in manifest order. Once Catalog Management persists
         * the anchor, they will reclaim that exact saved position.
         */
        if (
            seasonalSpotlightGroup(
                missingKey
            ) != null
        ) {
            val lastSeasonalIndex =
                mergedOrder.indexOfLast {
                    seasonalSpotlightGroup(
                        it
                    ) != null
                }

            if (lastSeasonalIndex >= 0) {
                mergedOrder.add(
                    lastSeasonalIndex + 1,
                    missingKey
                )
            } else {
                mergedOrder.add(
                    missingKey
                )
            }

            return@forEach
        }

        val group =
            watchlyCatalogGroup(
                missingKey
            )

        if (group == null) {
            mergedOrder.add(
                missingKey
            )
            return@forEach
        }

        if (
            !insertedMissingWatchlyGroups.add(
                group
            )
        ) {
            return@forEach
        }

        val members =
            watchlyMembersByGroup[
                group
            ].orEmpty()
                .filterNot {
                    it in mergedOrder
                }

        if (members.isEmpty()) {
            return@forEach
        }

        val sameGroupInsertAt =
            mergedOrder.indexOfLast {
                watchlyCatalogGroup(
                    it
                ) == group
            }

        if (sameGroupInsertAt >= 0) {
            mergedOrder.addAll(
                sameGroupInsertAt + 1,
                members
            )
        } else {
            val lastWatchlyInsertAt =
                mergedOrder.indexOfLast {
                    watchlyCatalogGroup(
                        it
                    ) != null
                }

            if (lastWatchlyInsertAt >= 0) {
                mergedOrder.addAll(
                    lastWatchlyInsertAt + 1,
                    members
                )
            } else {
                mergedOrder.addAll(
                    members
                )
            }
        }
    }

    return mergedOrder
}
