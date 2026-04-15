package com.nuvio.tv.ui.screens.addon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
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
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(CatalogOrderUiState())
    val uiState: StateFlow<CatalogOrderUiState> = _uiState.asStateFlow()
    private var disabledKeysCache: Set<String> = emptySet()
    private var numberedKeysCache: Set<String> = emptySet()
    private var outlineNumberedKeysCache: Set<String> = emptySet()

    init {
        observeCatalogs()
    }

    fun moveUp(key: String) {
        moveCatalog(key, -1)
    }

    fun moveDown(key: String) {
        moveCatalog(key, 1)
    }

    fun toggleCatalogEnabled(disableKey: String) {
        val updatedDisabled = disabledKeysCache.toMutableSet().apply {
            if (disableKey in this) remove(disableKey) else add(disableKey)
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.setDisabledHomeCatalogKeys(updatedDisabled.toList())
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

    fun toggleShowAllCatalogsOnHome() {
        val current = _uiState.value.showAllCatalogsOnHome
        viewModelScope.launch {
            layoutPreferenceDataStore.setShowAllCatalogsOnHome(!current)
        }
    }

    private fun moveCatalog(key: String, direction: Int) {
        val currentKeys = _uiState.value.items.map { it.key }
        val currentIndex = currentKeys.indexOf(key)
        if (currentIndex == -1) return

        val newIndex = currentIndex + direction
        if (newIndex !in currentKeys.indices) return

        val reordered = currentKeys.toMutableList().apply {
            val item = removeAt(currentIndex)
            add(newIndex, item)
        }

        viewModelScope.launch {
            layoutPreferenceDataStore.setHomeCatalogOrderKeys(reordered)
        }
    }

    private fun observeCatalogs() {
        viewModelScope.launch {
            combine(
                addonRepository.getInstalledAddons(),
                layoutPreferenceDataStore.homeCatalogOrderKeys,
                layoutPreferenceDataStore.disabledHomeCatalogKeys,
                layoutPreferenceDataStore.numberedHomeCatalogKeys,
                layoutPreferenceDataStore.outlineNumberedHomeCatalogKeys,
                layoutPreferenceDataStore.useThemeColorForNumbers,
            layoutPreferenceDataStore.aggregateStreamingPlatformsEnabled,
            layoutPreferenceDataStore.showAllCatalogsOnHome
            ) { args ->
                val addons = args[0] as List<*>
                val savedOrderKeys = args[1] as List<*>
                val disabledKeys = args[2] as List<*>
                val numberedKeys = args[3] as List<*>
                val outlineNumberedKeys = args[4] as List<*>
                val useThemeColor = args[5] as Boolean
                val aggregatePlatforms = args[6] as Boolean
                val showAllOnHome = args[7] as Boolean
                Pair(
                Triple(
                    buildOrderedCatalogItems(
                        addons = addons as List<com.nuvio.tv.domain.model.Addon>,
                        savedOrderKeys = savedOrderKeys as List<String>,
                        disabledKeys = (disabledKeys as List<String>).toSet(),
                        numberedKeys = (numberedKeys as List<String>).toSet(),
                        outlineNumberedKeys = (outlineNumberedKeys as List<String>).toSet()
                    ),
                    useThemeColor,
                    Unit
                ), aggregatePlatforms to showAllOnHome)
            }.collectLatest { (triple, aggregatePair) ->
                val (aggregatePlatforms, showAllOnHome) = aggregatePair
                val (orderedItems, useThemeColor, _) = triple
                disabledKeysCache = orderedItems.filter { it.isDisabled }.map { it.disableKey }.toSet()
                numberedKeysCache = orderedItems.filter { it.numberStyle == com.nuvio.tv.ui.screens.home.NumberStyle.SOLID }.map { it.key }.toSet()
                outlineNumberedKeysCache = orderedItems.filter { it.numberStyle == com.nuvio.tv.ui.screens.home.NumberStyle.OUTLINE }.map { it.key }.toSet()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        items = orderedItems,
                        useThemeColorForNumbers = useThemeColor,
                        aggregateStreamingPlatformsEnabled = aggregatePlatforms,
                        showAllCatalogsOnHome = showAllOnHome
                    )
                }
            }
        }
    }

    private fun buildOrderedCatalogItems(
        addons: List<Addon>,
        savedOrderKeys: List<String>,
        disabledKeys: Set<String>,
        numberedKeys: Set<String> = emptySet(),
        outlineNumberedKeys: Set<String> = emptySet()
    ): List<CatalogOrderItem> {
        val defaultEntries = buildDefaultCatalogEntries(addons)
        val availableMap = defaultEntries.associateBy { it.key }
        val defaultOrderKeys = defaultEntries.map { it.key }

        val savedValid = savedOrderKeys
            .asSequence()
            .filter { it in availableMap }
            .distinct()
            .toList()

        val savedKeySet = savedValid.toSet()
        val missing = defaultOrderKeys.filterNot { it in savedKeySet }
        val effectiveOrder = savedValid + missing

        return effectiveOrder.mapIndexedNotNull { index, key ->
            val entry = availableMap[key] ?: return@mapIndexedNotNull null
            CatalogOrderItem(
                key = entry.key,
                disableKey = entry.disableKey,
                catalogName = entry.catalogName,
                addonName = entry.addonName,
                typeLabel = entry.typeLabel,
                isDisabled = entry.disableKey in disabledKeys,
                numberStyle = when {
                    entry.key in outlineNumberedKeys -> com.nuvio.tv.ui.screens.home.NumberStyle.OUTLINE
                    entry.key in numberedKeys -> com.nuvio.tv.ui.screens.home.NumberStyle.SOLID
                    else -> com.nuvio.tv.ui.screens.home.NumberStyle.OFF
                },
                canMoveUp = index > 0,
                canMoveDown = index < effectiveOrder.lastIndex
            )
        }
    }

    private fun buildDefaultCatalogEntries(addons: List<Addon>): List<CatalogOrderEntry> {
        val entries = mutableListOf<CatalogOrderEntry>()
        val seenKeys = mutableSetOf<String>()

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
    val items: List<CatalogOrderItem> = emptyList(),
    val useThemeColorForNumbers: Boolean = false,
    val aggregateStreamingPlatformsEnabled: Boolean = false,
    val showAllCatalogsOnHome: Boolean = false
)

data class CatalogOrderItem(
    val key: String,
    val disableKey: String,
    val catalogName: String,
    val addonName: String,
    val typeLabel: String,
    val isDisabled: Boolean,
    val numberStyle: com.nuvio.tv.ui.screens.home.NumberStyle = com.nuvio.tv.ui.screens.home.NumberStyle.OFF,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean
)

private data class CatalogOrderEntry(
    val key: String,
    val disableKey: String,
    val catalogName: String,
    val addonName: String,
    val typeLabel: String
)

