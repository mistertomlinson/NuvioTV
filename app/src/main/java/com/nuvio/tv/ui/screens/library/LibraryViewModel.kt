package com.nuvio.tv.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.repository.TraktLibraryService
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.TraktListPrivacy
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import com.nuvio.tv.R
import java.util.Locale
import javax.inject.Inject

data class LibraryTypeTab(
    val key: String,
    val label: String
) {
    companion object {
        const val ALL_KEY = "__all__"
        val All = LibraryTypeTab(key = ALL_KEY, label = "All")
    }
}

enum class LibrarySortOption(
    val key: String,
    val labelResId: Int
) {
    DEFAULT("default", R.string.library_sort_trakt_order),
    ADDED_DESC("added_desc", R.string.library_sort_added_desc),
    ADDED_ASC("added_asc", R.string.library_sort_added_asc),
    TITLE_ASC("title_asc", R.string.library_sort_title_asc),
    TITLE_DESC("title_desc", R.string.library_sort_title_desc);

    companion object {
        val TraktOptions = listOf(DEFAULT, ADDED_DESC, ADDED_ASC, TITLE_ASC, TITLE_DESC)
        val LocalOptions = listOf(ADDED_DESC, ADDED_ASC, TITLE_ASC, TITLE_DESC)
    }
}

data class LibraryListEditorState(
    val mode: Mode,
    val listId: String? = null,
    val name: String = "",
    val description: String = "",
    val privacy: TraktListPrivacy = TraktListPrivacy.PRIVATE
) {
    enum class Mode {
        CREATE,
        EDIT
    }
}

data class LibraryUiState(
    val sourceMode: LibrarySourceMode = LibrarySourceMode.LOCAL,
    val allItems: List<LibraryEntry> = emptyList(),
    val visibleItems: List<LibraryEntry> = emptyList(),
    val listTabs: List<LibraryListTab> = emptyList(),
    val availableTypeTabs: List<LibraryTypeTab> = emptyList(),
    val availableSortOptions: List<LibrarySortOption> = emptyList(),
    val selectedListKey: String? = null,
    val selectedTypeTab: LibraryTypeTab? = null,
    val selectedSortOption: LibrarySortOption = LibrarySortOption.DEFAULT,
    val sortSelectionVersion: Long = 0L,
    val posterCardWidthDp: Int = 126,
    val posterCardCornerRadiusDp: Int = 12,
    val isLoading: Boolean = true,
    val isSyncing: Boolean = false,
    val errorMessage: String? = null,
    val transientMessage: String? = null,
    val showManageDialog: Boolean = false,
    val manageSelectedListKey: String? = null,
    val listEditorState: LibraryListEditorState? = null,
    val pendingOperation: Boolean = false
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val metaRepository: MetaRepository,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private var messageClearJob: Job? = null
    private val simklMetadata = mutableMapOf<String, LibraryMetadata>()
    private val attemptedSimklMetadataKeys = mutableSetOf<String>()
    private val simklMetadataSemaphore = Semaphore(4)

    private data class LibraryMetadata(
        val name: String?,
        val poster: String?,
        val background: String?,
        val logo: String?,
        val description: String?,
        val releaseInfo: String?,
        val imdbRating: Float?,
        val genres: List<String>
    )

    init {
        observeLayoutPreferences()
        observeLibraryData()
    }

    fun onSelectTypeTab(tab: LibraryTypeTab) {
        _uiState.update { current ->
            val updated = current.copy(selectedTypeTab = tab)
            updated.withVisibleItems()
        }
    }

    fun onSelectListTab(listKey: String) {
        _uiState.update { current ->
            val updated = current.copy(selectedListKey = listKey)
            updated.withVisibleItems()
        }
    }

    fun onSelectSortOption(option: LibrarySortOption) {
        _uiState.update { current ->
            val nextVersion = if (current.selectedSortOption != option) {
                current.sortSelectionVersion + 1L
            } else {
                current.sortSelectionVersion
            }
            val updated = current.copy(
                selectedSortOption = option,
                sortSelectionVersion = nextVersion
            )
            updated.withVisibleItems()
        }
    }

    fun onRefresh() {
        if (_uiState.value.isSyncing) return
        viewModelScope.launch {
            setTransientMessage("Syncing Trakt library...")
            runCatching {
                libraryRepository.refreshNow()
                setTransientMessage("Library synced")
            }.onFailure { error ->
                setError(error.message ?: "Failed to refresh library")
            }
        }
    }

    fun onOpenManageLists() {
        _uiState.update { current ->
            if (current.sourceMode != LibrarySourceMode.TRAKT) {
                return@update current
            }
            current.copy(
                showManageDialog = true,
                manageSelectedListKey = current.manageSelectedListKey
                    ?: current.listTabs.firstOrNull { it.type == LibraryListTab.Type.PERSONAL }?.key
            )
        }
    }

    fun onCloseManageLists() {
        _uiState.update { current ->
            current.copy(
                showManageDialog = false,
                listEditorState = null,
                errorMessage = null
            )
        }
    }

    fun onSelectManageList(listKey: String) {
        _uiState.update { it.copy(manageSelectedListKey = listKey) }
    }

    fun onStartCreateList() {
        _uiState.update {
            it.copy(
                listEditorState = LibraryListEditorState(mode = LibraryListEditorState.Mode.CREATE),
                errorMessage = null
            )
        }
    }

    fun onStartEditList() {
        val selected = selectedManagePersonalList() ?: return
        _uiState.update {
            it.copy(
                listEditorState = LibraryListEditorState(
                    mode = LibraryListEditorState.Mode.EDIT,
                    listId = selected.traktListId?.toString(),
                    name = selected.title,
                    description = selected.description.orEmpty(),
                    privacy = selected.privacy ?: TraktListPrivacy.PRIVATE
                ),
                errorMessage = null
            )
        }
    }

    fun onUpdateEditorName(value: String) {
        _uiState.update { current ->
            val editor = current.listEditorState ?: return@update current
            current.copy(listEditorState = editor.copy(name = value))
        }
    }

    fun onUpdateEditorDescription(value: String) {
        _uiState.update { current ->
            val editor = current.listEditorState ?: return@update current
            current.copy(listEditorState = editor.copy(description = value))
        }
    }

    fun onUpdateEditorPrivacy(value: TraktListPrivacy) {
        _uiState.update { current ->
            val editor = current.listEditorState ?: return@update current
            current.copy(listEditorState = editor.copy(privacy = value))
        }
    }

    fun onCancelEditor() {
        _uiState.update { it.copy(listEditorState = null, errorMessage = null) }
    }

    fun onSubmitEditor() {
        val editor = _uiState.value.listEditorState ?: return
        val name = editor.name.trim()
        if (name.isBlank()) {
            setError("List name is required")
            return
        }
        if (_uiState.value.pendingOperation) return

        viewModelScope.launch {
            _uiState.update { it.copy(pendingOperation = true, errorMessage = null) }
            runCatching {
                when (editor.mode) {
                    LibraryListEditorState.Mode.CREATE -> {
                        libraryRepository.createPersonalList(
                            name = name,
                            description = editor.description.trim().ifBlank { null },
                            privacy = editor.privacy
                        )
                        setTransientMessage("List created")
                    }
                    LibraryListEditorState.Mode.EDIT -> {
                        val listId = editor.listId
                            ?: throw IllegalStateException("Invalid list")
                        libraryRepository.updatePersonalList(
                            listId = listId,
                            name = name,
                            description = editor.description.trim().ifBlank { null },
                            privacy = editor.privacy
                        )
                        setTransientMessage("List updated")
                    }
                }
            }.onSuccess {
                _uiState.update { it.copy(listEditorState = null, pendingOperation = false) }
            }.onFailure { error ->
                _uiState.update { it.copy(pendingOperation = false) }
                setError(error.message ?: "Failed to save list")
            }
        }
    }

    fun onDeleteSelectedList() {
        val selected = selectedManagePersonalList() ?: return
        val listId = selected.traktListId?.toString() ?: return
        if (_uiState.value.pendingOperation) return

        viewModelScope.launch {
            _uiState.update { it.copy(pendingOperation = true, errorMessage = null) }
            runCatching {
                libraryRepository.deletePersonalList(listId)
                setTransientMessage("List deleted")
            }.onSuccess {
                _uiState.update { it.copy(pendingOperation = false) }
            }.onFailure { error ->
                _uiState.update { it.copy(pendingOperation = false) }
                setError(error.message ?: "Failed to delete list")
            }
        }
    }

    fun onMoveSelectedListUp() {
        reorderSelectedList(moveUp = true)
    }

    fun onMoveSelectedListDown() {
        reorderSelectedList(moveUp = false)
    }

    fun onClearTransientMessage() {
        _uiState.update { it.copy(transientMessage = null) }
    }

    private fun observeLibraryData() {
        viewModelScope.launch {
            combine(
                libraryRepository.sourceMode,
                libraryRepository.isSyncing,
                libraryRepository.libraryItems,
                libraryRepository.listTabs
            ) { sourceMode, isSyncing, items, listTabs ->
                DataBundle(
                    sourceMode = sourceMode,
                    isSyncing = isSyncing,
                    items = items,
                    listTabs = listTabs
                )
            }.collectLatest { (sourceMode, isSyncing, items, listTabs) ->
                val presentedItems = if (sourceMode == LibrarySourceMode.SIMKL) {
                    items.map(::applySimklMetadata)
                } else {
                    items
                }
                _uiState.update { current ->
                    val nextSelectedList = when {
                        sourceMode == LibrarySourceMode.TRAKT -> {
                            current.selectedListKey
                                ?.takeIf { key -> listTabs.any { it.key == key } }
                                ?: listTabs.firstOrNull()?.key
                        }
                        else -> null
                    }

                    val nextManageSelected = current.manageSelectedListKey
                        ?.takeIf { key ->
                            listTabs.any { tab ->
                                tab.key == key && tab.type == LibraryListTab.Type.PERSONAL
                            }
                        }
                        ?: listTabs.firstOrNull { it.type == LibraryListTab.Type.PERSONAL }?.key

                    val itemsForTypeTabs = if (sourceMode == LibrarySourceMode.TRAKT) {
                        val listKey = nextSelectedList
                        if (listKey.isNullOrBlank()) presentedItems else presentedItems.filter { it.listKeys.contains(listKey) }
                    } else {
                        presentedItems
                    }
                    val typeTabs = buildTypeTabs(itemsForTypeTabs)
                    val nextSelectedType = current.selectedTypeTab
                        ?.takeIf { selected -> typeTabs.any { it.key == selected.key } }
                        ?: LibraryTypeTab.All
                    val sortOptions = if (sourceMode == LibrarySourceMode.TRAKT) {
                        LibrarySortOption.TraktOptions
                    } else {
                        LibrarySortOption.LocalOptions
                    }
                    val nextSelectedSort = current.selectedSortOption
                        .takeIf { it in sortOptions }
                        ?: if (sourceMode == LibrarySourceMode.TRAKT) LibrarySortOption.DEFAULT else LibrarySortOption.ADDED_DESC

                    val updated = current.copy(
                        sourceMode = sourceMode,
                        allItems = presentedItems,
                        listTabs = listTabs,
                        availableTypeTabs = typeTabs,
                        availableSortOptions = sortOptions,
                        selectedTypeTab = nextSelectedType,
                        selectedListKey = nextSelectedList,
                        selectedSortOption = nextSelectedSort,
                        manageSelectedListKey = nextManageSelected,
                        isSyncing = sourceMode == LibrarySourceMode.TRAKT && isSyncing,
                        isLoading = sourceMode == LibrarySourceMode.TRAKT &&
                            isSyncing &&
                            items.isEmpty() &&
                            listTabs.isEmpty()
                    )
                    updated.withVisibleItems()
                }

                if (sourceMode == LibrarySourceMode.SIMKL) {
                    hydrateMissingSimklMetadata(items)
                }
            }
        }
    }

    /**
     * Simkl owns list membership and stable IDs, but its sync projection does not
     * guarantee presentation metadata. Resolve missing artwork only while the
     * Library screen's ViewModel exists so Home launch and scrolling remain untouched.
     */
    private fun hydrateMissingSimklMetadata(items: List<LibraryEntry>) {
        items.asSequence()
            .filter { item -> item.poster.isNullOrBlank() }
            .forEach { item ->
                val key = libraryItemKey(item)
                if (simklMetadata.containsKey(key) || !attemptedSimklMetadataKeys.add(key)) {
                    return@forEach
                }

                viewModelScope.launch {
                    val metadata = simklMetadataSemaphore.withPermit {
                        resolveSimklMetadata(item)
                    } ?: return@launch

                    simklMetadata[key] = metadata
                    _uiState.update { current ->
                        val updatedItems = current.allItems.map { currentItem ->
                            if (libraryItemKey(currentItem) == key) {
                                currentItem.withMetadata(metadata)
                            } else {
                                currentItem
                            }
                        }
                        current.copy(allItems = updatedItems).withVisibleItems()
                    }
                }
            }
    }

    private suspend fun resolveSimklMetadata(item: LibraryEntry): LibraryMetadata? {
        val typeCandidates = if (item.type.equals("movie", ignoreCase = true)) {
            listOf("movie")
        } else {
            listOf("series", "tv")
        }
        val idCandidates = buildList {
            item.imdbId?.takeIf { it.isNotBlank() }?.let(::add)
            item.tmdbId?.let { add("tmdb:$it") }
            add(item.id)
            item.simklId?.let { add("simkl:$it") }
        }.distinct()

        var best: LibraryMetadata? = null
        for (type in typeCandidates) {
            for (id in idCandidates) {
                val result = withTimeoutOrNull(3_500L) {
                    metaRepository.getMetaFromAllAddons(type = type, id = id)
                        .first { it !is NetworkResult.Loading }
                } ?: continue
                val meta = (result as? NetworkResult.Success)?.data ?: continue
                val candidate = LibraryMetadata(
                    name = meta.name,
                    poster = meta.poster,
                    background = meta.background,
                    logo = meta.logo,
                    description = meta.description,
                    releaseInfo = meta.releaseInfo,
                    imdbRating = meta.imdbRating,
                    genres = meta.genres
                )
                best = best?.withFallback(candidate) ?: candidate
                if (!best.poster.isNullOrBlank()) return best
            }
        }
        return null
    }

    private fun applySimklMetadata(item: LibraryEntry): LibraryEntry =
        simklMetadata[libraryItemKey(item)]?.let { metadata ->
            item.withMetadata(metadata)
        } ?: item

    private fun LibraryEntry.withMetadata(metadata: LibraryMetadata): LibraryEntry = copy(
        name = name.takeIf { it.isNotBlank() && it != id }
            ?: metadata.name?.takeIf { it.isNotBlank() }
            ?: name,
        poster = poster?.takeIf { it.isNotBlank() }
            ?: metadata.poster?.takeIf { it.isNotBlank() },
        background = background?.takeIf { it.isNotBlank() }
            ?: metadata.background?.takeIf { it.isNotBlank() },
        logo = logo?.takeIf { it.isNotBlank() }
            ?: metadata.logo?.takeIf { it.isNotBlank() },
        description = description?.takeIf { it.isNotBlank() }
            ?: metadata.description?.takeIf { it.isNotBlank() },
        releaseInfo = releaseInfo?.takeIf { it.isNotBlank() }
            ?: metadata.releaseInfo?.takeIf { it.isNotBlank() },
        imdbRating = imdbRating ?: metadata.imdbRating,
        genres = genres.ifEmpty { metadata.genres }
    )

    private fun LibraryMetadata.withFallback(fallback: LibraryMetadata): LibraryMetadata = copy(
        name = name?.takeIf { it.isNotBlank() }
            ?: fallback.name?.takeIf { it.isNotBlank() },
        poster = poster?.takeIf { it.isNotBlank() }
            ?: fallback.poster?.takeIf { it.isNotBlank() },
        background = background?.takeIf { it.isNotBlank() }
            ?: fallback.background?.takeIf { it.isNotBlank() },
        logo = logo?.takeIf { it.isNotBlank() }
            ?: fallback.logo?.takeIf { it.isNotBlank() },
        description = description?.takeIf { it.isNotBlank() }
            ?: fallback.description?.takeIf { it.isNotBlank() },
        releaseInfo = releaseInfo?.takeIf { it.isNotBlank() }
            ?: fallback.releaseInfo?.takeIf { it.isNotBlank() },
        imdbRating = imdbRating ?: fallback.imdbRating,
        genres = genres.ifEmpty { fallback.genres }
    )

    private fun libraryItemKey(item: LibraryEntry): String =
        "${item.type.lowercase(Locale.ROOT)}:${item.id}"

    private fun observeLayoutPreferences() {
        viewModelScope.launch {
            combine(
                layoutPreferenceDataStore.posterCardWidthDp,
                layoutPreferenceDataStore.posterCardCornerRadiusDp
            ) { widthDp, cornerRadiusDp ->
                widthDp to cornerRadiusDp
            }.collectLatest { (widthDp, cornerRadiusDp) ->
                _uiState.update { current ->
                    if (current.posterCardWidthDp == widthDp &&
                        current.posterCardCornerRadiusDp == cornerRadiusDp
                    ) {
                        current
                    } else {
                        current.copy(
                            posterCardWidthDp = widthDp,
                            posterCardCornerRadiusDp = cornerRadiusDp
                        )
                    }
                }
            }
        }
    }

    private data class DataBundle(
        val sourceMode: LibrarySourceMode,
        val isSyncing: Boolean,
        val items: List<LibraryEntry>,
        val listTabs: List<LibraryListTab>
    )

    private fun reorderSelectedList(moveUp: Boolean) {
        val state = _uiState.value
        if (state.pendingOperation) return

        val personalTabs = state.listTabs.filter { it.type == LibraryListTab.Type.PERSONAL }
        val selectedKey = state.manageSelectedListKey ?: return
        val selectedIndex = personalTabs.indexOfFirst { it.key == selectedKey }
        if (selectedIndex < 0) return

        val targetIndex = if (moveUp) selectedIndex - 1 else selectedIndex + 1
        if (targetIndex !in personalTabs.indices) return

        val reordered = personalTabs.toMutableList().apply {
            add(targetIndex, removeAt(selectedIndex))
        }
        val orderedIds = reordered.mapNotNull { tab ->
            tab.traktListId?.toString() ?: tab.key.removePrefix(TraktLibraryService.PERSONAL_KEY_PREFIX)
        }

        viewModelScope.launch {
            _uiState.update { it.copy(pendingOperation = true, errorMessage = null) }
            runCatching {
                libraryRepository.reorderPersonalLists(orderedIds)
                setTransientMessage("List order updated")
            }.onSuccess {
                _uiState.update { it.copy(pendingOperation = false) }
            }.onFailure { error ->
                _uiState.update { it.copy(pendingOperation = false) }
                setError(error.message ?: "Failed to reorder lists")
            }
        }
    }

    private fun selectedManagePersonalList(): LibraryListTab? {
        val state = _uiState.value
        val selectedKey = state.manageSelectedListKey ?: return null
        return state.listTabs.firstOrNull { it.key == selectedKey && it.type == LibraryListTab.Type.PERSONAL }
    }

    private fun setError(message: String) {
        _uiState.update { it.copy(errorMessage = message, transientMessage = message) }
        messageClearJob?.cancel()
        messageClearJob = viewModelScope.launch {
            delay(2800)
            _uiState.update { it.copy(transientMessage = null) }
        }
    }

    private fun setTransientMessage(message: String) {
        _uiState.update { it.copy(transientMessage = message, errorMessage = null) }
        messageClearJob?.cancel()
        messageClearJob = viewModelScope.launch {
            delay(2200)
            _uiState.update { it.copy(transientMessage = null) }
        }
    }

    private fun buildTypeTabs(items: List<LibraryEntry>): List<LibraryTypeTab> {
        val byKey = linkedMapOf<String, LibraryTypeTab>()
        items.forEach { entry ->
            val key = entry.type.trim().ifBlank { "unknown" }.lowercase(Locale.ROOT)
            if (byKey.containsKey(key)) return@forEach
            byKey[key] = LibraryTypeTab(
                key = key,
                label = prettifyTypeLabel(key)
            )
        }
        return listOf(LibraryTypeTab.All) + byKey.values
    }

    private fun prettifyTypeLabel(key: String): String {
        return key
            .replace('_', ' ')
            .replace('-', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { token ->
                token.replaceFirstChar { ch ->
                    if (ch.isLowerCase()) ch.titlecase(Locale.ROOT) else ch.toString()
                }
            }
            .ifBlank { "Unknown" }
    }

    private fun LibraryUiState.withVisibleItems(): LibraryUiState {
        val selectedTypeKey = selectedTypeTab?.key
        val typeFiltered = allItems.filter { entry ->
            selectedTypeKey == null ||
                selectedTypeKey == LibraryTypeTab.ALL_KEY ||
                entry.type.trim().lowercase(Locale.ROOT) == selectedTypeKey
        }

        val listFiltered = if (sourceMode == LibrarySourceMode.TRAKT) {
            val listKey = selectedListKey ?: ""
            typeFiltered.filter { entry -> entry.listKeys.contains(listKey) }
        } else {
            typeFiltered
        }

        val sorted = when (selectedSortOption) {
            LibrarySortOption.DEFAULT -> if (sourceMode == LibrarySourceMode.TRAKT) {
                listFiltered.sortedWith(
                    compareBy<LibraryEntry> { it.traktRank ?: Int.MAX_VALUE }
                        .thenByDescending { it.listedAt }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id } }
                        .thenBy { it.id }
                )
            } else {
                listFiltered
            }
            LibrarySortOption.ADDED_DESC -> listFiltered.sortedWith(
                compareByDescending<LibraryEntry> { it.listedAt }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id } }
                    .thenBy { it.id }
            )
            LibrarySortOption.ADDED_ASC -> listFiltered.sortedWith(
                compareBy<LibraryEntry> { it.listedAt }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id } }
                    .thenBy { it.id }
            )
            LibrarySortOption.TITLE_ASC -> listFiltered.sortedWith(
                compareBy<LibraryEntry> { it.name.ifBlank { it.id }.lowercase(Locale.ROOT) }
                    .thenBy { it.id }
            )
            LibrarySortOption.TITLE_DESC -> listFiltered.sortedWith(
                compareByDescending<LibraryEntry> { it.name.ifBlank { it.id }.lowercase(Locale.ROOT) }
                    .thenBy { it.id }
            )
        }

        return copy(visibleItems = sorted)
    }
}
