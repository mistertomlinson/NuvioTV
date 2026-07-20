package com.nuvio.tv.ui.screens.addon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CatalogOrderViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val traktAuthDataStore: TraktAuthDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(CatalogOrderUiState())
    private var isTraktAuthenticated: Boolean = false
    val uiState: StateFlow<CatalogOrderUiState> = _uiState.asStateFlow()
    private var disabledKeysCache: Set<String> = emptySet()
    private var numberedKeysCache: Set<String> = emptySet()
    private var outlineNumberedKeysCache: Set<String> = emptySet()
    private var landscapeKeysCache: Set<String> = emptySet()
    private var shuffleKeysCache: Set<String> = emptySet()

    init {
        viewModelScope.launch {
            traktAuthDataStore.isEffectivelyAuthenticated.collect { isAuth ->
                isTraktAuthenticated = isAuth
            }
        }
        observeCatalogs()
    }

    fun moveUp(key: String) {
        moveCatalog(key, -1)
    }

    fun moveDown(key: String) {
        moveCatalog(key, 1)
    }

    fun moveToTop(key: String) {
        val item = _uiState.value.items.find { it.key == key } ?: return
        val memberKeys = if (item.isGroup) item.groupMemberKeys else listOf(key)
        // Get current full key order from datastore perspective
        val currentItem = _uiState.value.items
        val allKeys = currentItem.flatMap { if (it.isGroup) it.groupMemberKeys else listOf(it.key) }
        val reordered = allKeys.toMutableList().apply {
            memberKeys.forEach { remove(it) }
            addAll(0, memberKeys)
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.setHomeCatalogOrderKeys(reordered)
        }
    }

    fun toggleCatalogEnabled(disableKey: String) {
        val updatedDisabled = disabledKeysCache.toMutableSet().apply {
            if (disableKey in this) remove(disableKey) else add(disableKey)
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.setDisabledHomeCatalogKeys(updatedDisabled.toList())
        }
    }

    fun toggleCatalogLandscape(key: String) {
        val updatedLandscape = landscapeKeysCache.toMutableSet().apply {
            if (key in this) remove(key) else add(key)
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.setLandscapeHomeCatalogKeys(updatedLandscape.toList())
        }
    }

    fun toggleCatalogShuffle(key: String) {
        val updatedShuffle = shuffleKeysCache.toMutableSet().apply {
            if (key in this) remove(key) else add(key)
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.setShuffledHomeCatalogKeys(updatedShuffle.toList())
            // Reset shuffle timestamp so the 12h timer restarts from now
            layoutPreferenceDataStore.setLastShuffleTimestampMs(System.currentTimeMillis())
        }
    }

        fun toggleCatalogNumbered(key: String) {
        val isSolid = key in numberedKeysCache
        val isOutline = key in outlineNumberedKeysCache
        val updatedSolid = numberedKeysCache.toMutableSet()
        val updatedOutline = outlineNumberedKeysCache.toMutableSet()
        when {
            isOutline -> { updatedOutline.remove(key) }
            isSolid -> { updatedSolid.remove(key); updatedOutline.add(key) }
            else -> { updatedSolid.add(key) }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.setNumberedHomeCatalogKeys(updatedSolid.toList())
            layoutPreferenceDataStore.setOutlineNumberedHomeCatalogKeys(updatedOutline.toList())
        }
    }

    fun toggleUseThemeColorForNumbers() {
        val current = _uiState.value.useThemeColorForNumbers
        viewModelScope.launch {
            layoutPreferenceDataStore.setUseThemeColorForNumbers(!current)
        }
    }

    fun toggleAggregatePlatforms() {
        val current = _uiState.value.aggregateStreamingPlatformsEnabled
        viewModelScope.launch {
            layoutPreferenceDataStore.setAggregateStreamingPlatformsEnabled(!current)
        }
    }

    fun toggleHeroMetadataLarge() {
        val current = _uiState.value.heroMetadataLarge
        viewModelScope.launch {
            layoutPreferenceDataStore.setHeroMetadataLarge(!current)
        }
    }

    fun toggleHidePlatformNameInCatalogTitle() {
        val current = _uiState.value.hidePlatformNameInCatalogTitleEnabled
        viewModelScope.launch {
            layoutPreferenceDataStore.setHidePlatformNameInCatalogTitleEnabled(!current)
        }
    }

    fun toggleShowAllCatalogsOnHome() {
        val current = _uiState.value.showAllCatalogsOnHome
        viewModelScope.launch {
            layoutPreferenceDataStore.setShowAllCatalogsOnHome(!current)
        }
    }

    fun toggleFastPlatformScroll() {
        val current = _uiState.value.fastPlatformScrollEnabled
        viewModelScope.launch {
            layoutPreferenceDataStore.setFastPlatformScrollEnabled(!current)
        }
    }

    fun toggleFullWidthIconRow() {
        val current = _uiState.value.fullWidthIconRowEnabled
        viewModelScope.launch {
            layoutPreferenceDataStore.setFullWidthIconRowEnabled(!current)
        }
    }

    fun toggleDimIconsOnRowExit() {
        val current = _uiState.value.dimIconsOnRowExitEnabled
        viewModelScope.launch {
            layoutPreferenceDataStore.setDimIconsOnRowExitEnabled(!current)
        }
    }

    private fun moveCatalog(key: String, direction: Int) {
        val item = _uiState.value.items.find { it.key == key } ?: return
        val memberKeys = if (item.isGroup) item.groupMemberKeys else listOf(key)
        val currentItems = _uiState.value.items
        val currentIndex = currentItems.indexOfFirst { it.key == key }
        if (currentIndex == -1) return

        val newIndex = currentIndex + direction
        if (newIndex !in currentItems.indices) return

        // Work with full flat key list for datastore
        val allKeys = currentItems.flatMap { if (it.isGroup) it.groupMemberKeys else listOf(it.key) }
        val reordered = allKeys.toMutableList().apply {
            memberKeys.forEach { remove(it) }
            // Find insertion point based on new collapsed index
            val targetItem = currentItems[newIndex]
            val targetKeys = if (targetItem.isGroup) targetItem.groupMemberKeys else listOf(targetItem.key)
            val targetFirstIndex = indexOf(targetKeys.first())
            if (direction > 0) {
                // Moving down: insert after the target group
                val insertAt = (indexOf(targetKeys.last()) + 1).coerceAtMost(size)
                addAll(insertAt, memberKeys)
            } else {
                // Moving up: insert before the target group
                val insertAt = targetFirstIndex.coerceAtLeast(0)
                addAll(insertAt, memberKeys)
            }
        }

        viewModelScope.launch {
            layoutPreferenceDataStore.setHomeCatalogOrderKeys(reordered)
        }
    }

    private fun observeCatalogs() {
        // Observe shuffled keys separately (combine() is capped at 12 args)
        viewModelScope.launch {
            layoutPreferenceDataStore.shuffledHomeCatalogKeys.collectLatest { keys ->
                shuffleKeysCache = keys.toSet()
                // Re-apply isShuffled flag to existing items without full rebuild
                _uiState.update { state ->
                    state.copy(
                        shuffledCatalogKeys = shuffleKeysCache,
                        items = state.items.map { item ->
                            item.copy(isShuffled = item.key in shuffleKeysCache)
                        }
                    )
                }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.heroMetadataLarge.collectLatest { large ->
                _uiState.update { it.copy(heroMetadataLarge = large) }
            }
        }
        viewModelScope.launch {
            combine(
                addonRepository.getInstalledAddons(),
                layoutPreferenceDataStore.homeCatalogOrderKeys,
                layoutPreferenceDataStore.disabledHomeCatalogKeys,
                layoutPreferenceDataStore.numberedHomeCatalogKeys,
                layoutPreferenceDataStore.outlineNumberedHomeCatalogKeys,
                layoutPreferenceDataStore.landscapeHomeCatalogKeys,
                layoutPreferenceDataStore.useThemeColorForNumbers,
            layoutPreferenceDataStore.aggregateStreamingPlatformsEnabled,
            layoutPreferenceDataStore.showAllCatalogsOnHome,
            layoutPreferenceDataStore.fullWidthIconRowEnabled,
            layoutPreferenceDataStore.fastPlatformScrollEnabled,
            layoutPreferenceDataStore.dimIconsOnRowExitEnabled
            ) { args ->
                // args[12] not available in 12-arg combine — shuffledKeys observed separately
                val addons = args[0] as List<*>
                val savedOrderKeys = args[1] as List<*>
                val disabledKeys = args[2] as List<*>
                val numberedKeys = args[3] as List<*>
                val outlineNumberedKeys = args[4] as List<*>
                val landscapeKeys = args[5] as List<*>
                val useThemeColor = args[6] as Boolean
                val aggregatePlatforms = args[7] as Boolean
                val showAllOnHome = args[8] as Boolean
                val fullWidthIconRow = args[9] as Boolean
                val fastPlatformScroll = args[10] as Boolean
                val dimIconsOnRowExit = args[11] as Boolean
Triple(
                    Triple(
                        buildOrderedCatalogItems(
                            addons = addons as List<com.nuvio.tv.domain.model.Addon>,
                            savedOrderKeys = savedOrderKeys as List<String>,
                            disabledKeys = (disabledKeys as List<String>).toSet(),
                            numberedKeys = (numberedKeys as List<String>).toSet(),
                            outlineNumberedKeys = (outlineNumberedKeys as List<String>).toSet(),
                            landscapeKeys = (landscapeKeys as List<String>).toSet()
                        ),
                        useThemeColor,
                        Unit
                    ),
                    aggregatePlatforms to showAllOnHome,
                    Triple(fullWidthIconRow, fastPlatformScroll, dimIconsOnRowExit)
                )
            }.combine(layoutPreferenceDataStore.modernLandscapePostersEnabled) { inner, globalLandscape ->
                inner to globalLandscape
            }.combine(layoutPreferenceDataStore.hidePlatformNameInCatalogTitleEnabled) { outer, hidePlatformName ->
                outer to hidePlatformName
            }.collectLatest { (outerResult, hidePlatformNameInCatalogTitle) ->
            val (innerResult, globalLandscapePosters) = outerResult
            val (triple, aggregatePair, fullWidthIconRowTriple) = innerResult
                val (aggregatePlatforms, showAllOnHome) = aggregatePair
                val (fullWidthIconRow, fastPlatformScroll, dimIconsOnRowExit) = fullWidthIconRowTriple
                val (orderedItems, useThemeColor, _) = triple
                disabledKeysCache = orderedItems.filter { it.isDisabled }.map { it.disableKey }.toSet()
                numberedKeysCache = orderedItems.filter { it.numberStyle == com.nuvio.tv.ui.screens.home.NumberStyle.SOLID }.map { it.key }.toSet()
                outlineNumberedKeysCache = orderedItems.filter { it.numberStyle == com.nuvio.tv.ui.screens.home.NumberStyle.OUTLINE }.map { it.key }.toSet()
                landscapeKeysCache = orderedItems.filter { it.isLandscape }.map { it.key }.toSet()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        items = orderedItems,
                        useThemeColorForNumbers = useThemeColor,
                        aggregateStreamingPlatformsEnabled = aggregatePlatforms,
                        showAllCatalogsOnHome = showAllOnHome,
                        fullWidthIconRowEnabled = fullWidthIconRow,
                        fastPlatformScrollEnabled = fastPlatformScroll,
                        dimIconsOnRowExitEnabled = dimIconsOnRowExit,
                        globalLandscapePostersEnabled = globalLandscapePosters,
                        hidePlatformNameInCatalogTitleEnabled = hidePlatformNameInCatalogTitle,
                        shuffledCatalogKeys = shuffleKeysCache
                    )
                }
            }
        }
    }

    // Maps a catalog key to its Watchly group identifier.
    // Key format: com.bimal.watchly_<type>_<catalogId>
    // type is "movie" or "series", catalogId is the Watchly catalog ID.
    private fun watchlyGroup(key: String): String? {
        if (!key.contains("com.bimal.watchly")) return null
        val isMovie = key.contains("_movie_")
        val isSeries = key.contains("_series_")
        val typeSuffix = when {
            isMovie -> "movie"
            isSeries -> "series"
            else -> "movie"
        }
        return when {
            key.contains("watchly.watched") -> "watchly.watched.$typeSuffix"
            key.contains("watchly.theme") -> "watchly.theme.$typeSuffix"
            key.contains("watchly.rec") -> "watchly.rec.$typeSuffix"
            key.contains("watchly.creators") -> "watchly.creators.$typeSuffix"
            key.contains("watchly.all.loved") -> "watchly.all.loved.$typeSuffix"
            key.contains("watchly.loved") -> "watchly.loved.$typeSuffix"
            key.contains("watchly.liked") -> "watchly.liked.$typeSuffix"
            else -> "watchly.other"
        }
    }

    private fun watchlyGroupLabel(groupKey: String): String {
        return when (groupKey) {
            "watchly.watched.movie" -> "Because You Watched • Movies"
            "watchly.watched.series" -> "Because You Watched • Series"
            "watchly.theme.movie" -> "Keyword • Movies"
            "watchly.theme.series" -> "Keyword • Series"
            "watchly.rec.movie" -> "Top Picks • Movies"
            "watchly.rec.series" -> "Top Picks • Series"
            "watchly.creators.movie" -> "Creators • Movies"
            "watchly.creators.series" -> "Creators • Series"
            "watchly.all.loved.movie" -> "Based on What You Loved • Movies"
            "watchly.all.loved.series" -> "Based on What You Loved • Series"
            "watchly.loved.movie" -> "More Like • Movies"
            "watchly.loved.series" -> "More Like • Series"
            "watchly.liked.movie" -> "Based on What You Liked • Movies"
            "watchly.liked.series" -> "Based on What You Liked • Series"
            else -> "Watchly"
        }
    }

    private fun buildOrderedCatalogItems(
        addons: List<Addon>,
        savedOrderKeys: List<String>,
        disabledKeys: Set<String>,
        numberedKeys: Set<String> = emptySet(),
        outlineNumberedKeys: Set<String> = emptySet(),
        landscapeKeys: Set<String> = emptySet()
    ): List<CatalogOrderItem> {
        val defaultEntries = buildDefaultCatalogEntries(addons)
        val availableMap = defaultEntries.associateBy { it.key }
        val defaultOrderKeys = defaultEntries.map { it.key }

        // For dynamic Watchly catalogs (e.g. watchly.loved.ttXXX, watchly.watched.ttXXX,
        // watchly.theme.*), the catalog ID changes each session based on the user's
        // recently loved/watched item. We match saved keys by their Watchly group prefix
        // so that a saved key like "watchly.loved.tt0111161" is treated as a valid
        // placeholder for the current session's "watchly.loved.tt0468569" key.
        fun watchlyGroupPrefix(key: String): String? {
            return when {
                key.contains("watchly.loved.") -> "watchly.loved."
                key.contains("watchly.watched.") -> "watchly.watched."
                key.contains("watchly.theme.") -> "watchly.theme."
                else -> null
            }
        }

        // Build a map from group prefix -> current available keys in that group
        val groupPrefixToAvailableKeys = mutableMapOf<String, MutableList<String>>()
        defaultOrderKeys.forEach { key ->
            val prefix = watchlyGroupPrefix(key)
            if (prefix != null) {
                groupPrefixToAvailableKeys.getOrPut(prefix) { mutableListOf() }.add(key)
            }
        }

        // Track which available keys have already been claimed by a saved key
        val claimedAvailableKeys = mutableSetOf<String>()

        val savedValid = savedOrderKeys
            .asSequence()
            .mapNotNull { savedKey ->
                when {
                    savedKey in availableMap -> savedKey // exact match
                    else -> {
                        // Try group prefix match for dynamic catalogs
                        val prefix = watchlyGroupPrefix(savedKey)
                        if (prefix != null) {
                            val available = groupPrefixToAvailableKeys[prefix]
                                ?.firstOrNull { it !in claimedAvailableKeys }
                            available
                        } else null
                    }
                }
            }
            .onEach { claimedAvailableKeys.add(it) }
            .distinct()
            .toList()

        val savedKeySet = savedValid.toSet()
        val missing = defaultOrderKeys.filterNot { it in savedKeySet }

        // For missing Watchly catalogs, find the insertion position based on
        // their group. Insert after the last saved key in the same group,
        // or after the last saved Watchly key of any group, or at the end.
        val effectiveOrder = savedValid.toMutableList()
        missing.forEach { missingKey ->
            val group = watchlyGroup(missingKey)
            android.util.Log.d("WatchlyOrder", "missing key=$missingKey group=$group")
            if (group == null) {
                effectiveOrder.add(missingKey)
            } else {
                var insertAt = effectiveOrder.indexOfLast { watchlyGroup(it) == group }
                android.util.Log.d("WatchlyOrder", "  sameGroupInsertAt=$insertAt")
                if (insertAt >= 0) {
                    effectiveOrder.add(insertAt + 1, missingKey)
                } else {
                    insertAt = effectiveOrder.indexOfLast { watchlyGroup(it) != null }
                    android.util.Log.d("WatchlyOrder", "  anyWatchlyInsertAt=$insertAt")
                    if (insertAt >= 0) {
                        effectiveOrder.add(insertAt + 1, missingKey)
                    } else {
                        effectiveOrder.add(missingKey)
                    }
                }
            }
        }

        // Collapse Watchly group members into single group rows
        val collapsedOrder = mutableListOf<String>() // representative key per row
        val groupRepresentatives = mutableMapOf<String, String>() // groupKey -> first key seen
        val groupMembers = mutableMapOf<String, MutableList<String>>() // groupKey -> all keys

        effectiveOrder.forEach { key ->
            val group = watchlyGroup(key)
            if (group != null) {
                if (!groupRepresentatives.containsKey(group)) {
                    groupRepresentatives[group] = key
                    groupMembers[group] = mutableListOf(key)
                    collapsedOrder.add(key) // use first key as row representative
                } else {
                    groupMembers[group]?.add(key)
                }
            } else {
                collapsedOrder.add(key)
            }
        }

        // Persist the effective order back to datastore so the home screen
        // picks up new Watchly catalog IDs at their correct group positions
        val flatEffectiveOrder = effectiveOrder.toList()
        if (flatEffectiveOrder != savedOrderKeys) {
            viewModelScope.launch {
                layoutPreferenceDataStore.setHomeCatalogOrderKeys(flatEffectiveOrder)
            }
        }

        return collapsedOrder.mapIndexedNotNull { index, key ->
            val entry = availableMap[key] ?: return@mapIndexedNotNull null
            val group = watchlyGroup(key)
            val members = if (group != null) groupMembers[group] ?: listOf(key) else listOf(key)
            val isGroup = group != null && members.size >= 1
            CatalogOrderItem(
                key = entry.key,
                disableKey = entry.disableKey,
                catalogName = if (isGroup) watchlyGroupLabel(group!!) else entry.catalogName,
                addonName = entry.addonName,
                typeLabel = entry.typeLabel,
                isDisabled = entry.disableKey in disabledKeys,
                numberStyle = when {
                    entry.key in outlineNumberedKeys -> com.nuvio.tv.ui.screens.home.NumberStyle.OUTLINE
                    entry.key in numberedKeys -> com.nuvio.tv.ui.screens.home.NumberStyle.SOLID
                    else -> com.nuvio.tv.ui.screens.home.NumberStyle.OFF
                },
                isLandscape = entry.key in landscapeKeys,
                isShuffled = entry.key in shuffleKeysCache,
                canMoveUp = index > 0,
                canMoveDown = index < collapsedOrder.lastIndex,
                isGroup = isGroup,
                groupSize = members.size,
                groupMemberKeys = members
            )
        }
    }

    private fun buildDefaultCatalogEntries(addons: List<Addon>): List<CatalogOrderEntry> {
        val entries = mutableListOf<CatalogOrderEntry>()
        val seenKeys = mutableSetOf<String>()

        // Inject My List as the first entry when Trakt is connected
        if (isTraktAuthenticated) {
            val myListKey = com.nuvio.tv.ui.screens.home.HomeViewModel.MY_LIST_CATALOG_KEY
            if (seenKeys.add(myListKey)) {
                entries.add(
                    CatalogOrderEntry(
                        key = myListKey,
                        disableKey = myListKey,
                        catalogName = "My List",
                        addonName = "Built-In",
                        typeLabel = "mixed"
                    )
                )
            }
        }

        addons.forEach { addon ->
            addon.catalogs
                .filterNot { it.isSearchOnlyCatalog() }
                .forEach { catalog ->
                    val key = catalogKey(
                        addonId = addon.id,
                        type = catalog.apiType,
                        catalogId = catalog.id
                    )
                    if (seenKeys.add(key)) {
                        entries.add(
                            CatalogOrderEntry(
                                key = key,
                                disableKey = disableKey(
                                    addonBaseUrl = addon.baseUrl,
                                    type = catalog.apiType,
                                    catalogId = catalog.id,
                                    catalogName = catalog.name
                                ),
                                catalogName = catalog.name,
                                addonName = addon.displayName,
                                typeLabel = catalog.apiType
                            )
                        )
                    }
                }
        }

        return entries
    }

    private fun catalogKey(addonId: String, type: String, catalogId: String): String {
        return "${addonId}_${type}_${catalogId}"
    }

    private fun disableKey(
        addonBaseUrl: String,
        type: String,
        catalogId: String,
        catalogName: String
    ): String {
        return "${addonBaseUrl}_${type}_${catalogId}_${catalogName}"
    }

    private fun CatalogDescriptor.isSearchOnlyCatalog(): Boolean {
        return extra.any { extra -> extra.name.equals("search", ignoreCase = true) && extra.isRequired }
    }
}

data class CatalogOrderUiState(
    val isLoading: Boolean = true,
    val globalLandscapePostersEnabled: Boolean = false,
    val items: List<CatalogOrderItem> = emptyList(),
    val useThemeColorForNumbers: Boolean = false,
    val aggregateStreamingPlatformsEnabled: Boolean = false,
    val showAllCatalogsOnHome: Boolean = false,
    val fullWidthIconRowEnabled: Boolean = false,
    val fastPlatformScrollEnabled: Boolean = false,
    val dimIconsOnRowExitEnabled: Boolean = false,
    val hidePlatformNameInCatalogTitleEnabled: Boolean = false,
    val shuffledCatalogKeys: Set<String> = emptySet(),
    val heroMetadataLarge: Boolean = false
)

data class CatalogOrderItem(
    val key: String,
    val disableKey: String,
    val catalogName: String,
    val addonName: String,
    val typeLabel: String,
    val isDisabled: Boolean,
    val numberStyle: com.nuvio.tv.ui.screens.home.NumberStyle = com.nuvio.tv.ui.screens.home.NumberStyle.OFF,
    val isLandscape: Boolean = false,
    val isShuffled: Boolean = false,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    // Group support for dynamic addon catalogs (e.g. Watchly)
    val isGroup: Boolean = false,
    val groupSize: Int = 1,
    val groupMemberKeys: List<String> = emptyList()
)

private data class CatalogOrderEntry(
    val key: String,
    val disableKey: String,
    val catalogName: String,
    val addonName: String,
    val typeLabel: String
)

