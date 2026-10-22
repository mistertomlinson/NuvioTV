package com.nuvio.tv.ui.screens.library

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.focusable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TraktListPrivacy
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.GlassDialogAnimatedPanel
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.formatAddonTypeLabel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.settings.SettingsCompactContent
import com.nuvio.tv.ui.screens.settings.SettingsGlassBorderColor
import com.nuvio.tv.ui.screens.settings.SettingsGlassCanvasBrush
import com.nuvio.tv.ui.screens.settings.SettingsGlassGroupColor
import com.nuvio.tv.ui.screens.settings.SettingsGlassRowColor
import com.nuvio.tv.ui.screens.settings.SettingsGlassRowFocusedColor
import com.nuvio.tv.ui.screens.settings.SettingsRightSurfaceColor
import com.nuvio.tv.ui.screens.settings.SettingsRightSurfaceFocusedColor
import com.nuvio.tv.ui.screens.settings.SettingsInsetControlColor
import com.nuvio.tv.ui.screens.settings.SettingsInsetControlFocusedColor
import com.nuvio.tv.ui.screens.settings.SettingsDialogGlassInsetColor
import com.nuvio.tv.ui.screens.settings.SettingsDialogGlassInsetFocusedColor
import com.nuvio.tv.ui.screens.settings.SettingsDialogGlassSelectedColor
import com.nuvio.tv.ui.screens.settings.SettingsDialogFocusScale
import com.nuvio.tv.ui.screens.settings.SettingsDialogPressedScale
import com.nuvio.tv.ui.screens.settings.SettingsDialogGlassIdleColor
import com.nuvio.tv.ui.components.glassDialogFocusTransform
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import com.nuvio.tv.ui.screens.settings.SettingsDialogPillShape
import dev.chrisbanes.haze.haze

private const val KEY_REPEAT_THROTTLE_MS = 80L

@Composable
private fun localizedTypeLabel(key: String): String = when (key.lowercase()) {
    LibraryTypeTab.ALL_KEY -> stringResource(R.string.library_type_all)
    "movie" -> stringResource(R.string.type_movie)
    "series" -> stringResource(R.string.type_series)
    else -> formatAddonTypeLabel(key)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel = hiltViewModel(),
    showBuiltInHeader: Boolean = true,
    onNavigateToDetail: (String, String, String?) -> Unit
) {
    SettingsCompactContent {
        LibraryScreenContent(
            viewModel = viewModel,
            showBuiltInHeader = showBuiltInHeader,
            onNavigateToDetail = onNavigateToDetail
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LibraryScreenContent(
    viewModel: LibraryViewModel = hiltViewModel(),
    showBuiltInHeader: Boolean = true,
    onNavigateToDetail: (String, String, String?) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var expandedPicker by remember { mutableStateOf<String?>(null) }
    val libraryPickerHazeState = remember { HazeState() }
    val libraryPickerAnchors = remember { mutableStateMapOf<String, IntRect>() }
    var libraryRootOffsetPx by remember { mutableStateOf(IntOffset.Zero) }
    val pickerFocusRequesters = remember {
        mapOf(
            "list" to FocusRequester(),
            "type" to FocusRequester(),
            "sort" to FocusRequester()
        )
    }
    val primaryFocusRequester = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    var pendingPrimaryFocus by remember { mutableStateOf(true) }
    var lastFocusedPosterKey by rememberSaveable { mutableStateOf<String?>(null) }
    val visibleItemKeys = remember(uiState.visibleItems) {
        uiState.visibleItems.map { "${it.type}:${it.id}" }
    }
    val posterFocusRequesters = remember(visibleItemKeys) {
        visibleItemKeys.associateWith { FocusRequester() }
    }
    val firstVisiblePosterKey = visibleItemKeys.firstOrNull()
    val posterCardStyle = PosterCardDefaults.Style

    LaunchedEffect(uiState.isLoading) {
        if (uiState.isLoading) {
            pendingPrimaryFocus = true
        }
    }

    LaunchedEffect(uiState.isLoading, uiState.sourceMode, uiState.listTabs.size) {
        if (!uiState.isLoading && pendingPrimaryFocus) {
            val restoreKey = lastFocusedPosterKey
            val restoreRequester = restoreKey?.let { posterFocusRequesters[it] }

            var focused = false
            if (restoreRequester != null) {
                /*
                 * rememberLazyGridState already preserves the exact Library
                 * viewport while Details is on top. Do not force-scroll here:
                 * the old poster-only index ignored the header/filter grid
                 * items and shifted the viewport before focus was restored.
                 */
                focused = runCatching { restoreRequester.requestFocus() }.isSuccess
                if (!focused) {
                    delay(16)
                    focused = runCatching { restoreRequester.requestFocus() }.isSuccess
                }
            }

            if (!focused) {
                focused = runCatching { primaryFocusRequester.requestFocus() }.isSuccess
            }
            if (!focused) {
                delay(16)
                runCatching { primaryFocusRequester.requestFocus() }
            }
            pendingPrimaryFocus = false
        }
    }

    LaunchedEffect(uiState.sortSelectionVersion, firstVisiblePosterKey) {
        if (uiState.sortSelectionVersion <= 0L) return@LaunchedEffect
        val targetKey = firstVisiblePosterKey ?: return@LaunchedEffect
        runCatching { gridState.scrollToItem(0) }
        var focused = false
        repeat(6) {
            focused = posterFocusRequesters[targetKey]
                ?.let { requester -> runCatching { requester.requestFocus() }.isSuccess }
                ?: false
            if (focused) return@LaunchedEffect
            delay(24)
        }
    }

    if (uiState.isLoading) {
        val loadingFocusRequester = remember { FocusRequester() }
        LaunchedEffect(uiState.isLoading) {
            loadingFocusRequester.requestFocus()
        }

        Box(
            modifier = Modifier
                .fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(loadingFocusRequester)
                    .focusable()
            )
            Column(
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LoadingIndicator()
                Text(
                    text = stringResource(R.string.library_syncing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextSecondary
                )
            }
        }
        return
    }

    val lastKeyRepeatTime = remember { longArrayOf(0L) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                val rootPosition = coordinates.positionInRoot()
                libraryRootOffsetPx = IntOffset(
                    x = rootPosition.x.roundToInt(),
                    y = rootPosition.y.roundToInt()
                )
            }
    ) {
        LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = posterCardStyle.width),
        state = gridState,
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (expandedPicker != null) {
                    Modifier.haze(libraryPickerHazeState)
                } else {
                    Modifier
                }
            )
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount > 0) {
                    val now = System.currentTimeMillis()
                    if (now - lastKeyRepeatTime[0] < KEY_REPEAT_THROTTLE_MS) {
                        return@onPreviewKeyEvent true
                    }
                    lastKeyRepeatTime[0] = now
                }
                false
            },
        contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 18.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.library_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (showBuiltInHeader) NuvioColors.TextPrimary else Color.Transparent,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = if (uiState.sourceMode == LibrarySourceMode.TRAKT) "TRAKT" else stringResource(R.string.library_source_local),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (showBuiltInHeader) NuvioColors.TextTertiary else Color.Transparent,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 2.sp
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            LibrarySelectorsRow(
                sourceMode = uiState.sourceMode,
                listTabs = uiState.listTabs,
                typeTabs = uiState.availableTypeTabs,
                sortOptions = uiState.availableSortOptions,
                selectedListKey = uiState.selectedListKey,
                selectedTypeTab = uiState.selectedTypeTab,
                selectedSortOption = uiState.selectedSortOption,
                primaryFocusRequester = primaryFocusRequester,
                pickerFocusRequesters = pickerFocusRequesters,
                expandedPicker = expandedPicker,
                onAnchorBoundsChanged = { picker, bounds ->
                    libraryPickerAnchors[picker] = bounds
                },
                onExpandedChange = { picker, shouldExpand ->
                    expandedPicker = if (shouldExpand) picker else null
                }
            )
        }

        if (uiState.sourceMode == LibrarySourceMode.TRAKT) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LibraryActionsRow(
                    pending = uiState.pendingOperation,
                    isSyncing = uiState.isSyncing,
                    onManageLists = viewModel::onOpenManageLists,
                    onRefresh = viewModel::onRefresh
                )
            }
        }

        if (uiState.visibleItems.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                val selectedTypeLabel = uiState.selectedTypeTab?.let { localizedTypeLabel(it.key) }?.lowercase() ?: stringResource(R.string.library_type_items)
                val title = when (uiState.sourceMode) {
                    LibrarySourceMode.LOCAL -> stringResource(R.string.library_empty_local_title, selectedTypeLabel)
                    LibrarySourceMode.TRAKT -> stringResource(R.string.library_empty_trakt_title, selectedTypeLabel)
                    LibrarySourceMode.SIMKL -> stringResource(R.string.library_empty_simkl_title, selectedTypeLabel)
                }
                val subtitle = when (uiState.sourceMode) {
                    LibrarySourceMode.LOCAL -> stringResource(R.string.library_empty_local_subtitle)
                    LibrarySourceMode.TRAKT -> stringResource(R.string.library_empty_trakt_subtitle)
                    LibrarySourceMode.SIMKL -> stringResource(R.string.library_empty_simkl_subtitle)
                }
                EmptyScreenState(
                    title = title,
                    subtitle = subtitle,
                    icon = Icons.Default.BookmarkBorder
                )
            }
        }

        items(uiState.visibleItems, key = { "${it.type}:${it.id}" }) { item ->
            val focusKey = "${item.type}:${item.id}"
            GridContentCard(
                item = item.toMetaPreview().copy(posterShape = PosterShape.POSTER),
                posterCardStyle = posterCardStyle,
                focusRequester = posterFocusRequesters[focusKey],
                showLabel = true,
                onFocused = {
                    lastFocusedPosterKey = focusKey
                },
                onClick = {
                    lastFocusedPosterKey = focusKey
                    onNavigateToDetail(item.id, item.type, item.addonBaseUrl)
                }
            )
        }

        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(modifier = Modifier.height(8.dp)) }
    }

        val overlayPicker = expandedPicker
        if (overlayPicker != null) {
            val overlayAnchor = libraryPickerAnchors[overlayPicker]
            val overlayOptions = when (overlayPicker) {
                "list" -> uiState.listTabs.map { LibraryOption(it.title, it.key) }
                "type" -> uiState.availableTypeTabs.map {
                    LibraryOption(localizedTypeLabel(it.key), it.key)
                }
                "sort" -> uiState.availableSortOptions.map {
                    LibraryOption(stringResource(it.labelResId), it.key)
                }
                else -> emptyList()
            }
            val overlaySelectedValue = when (overlayPicker) {
                "list" -> uiState.selectedListKey
                "type" -> uiState.selectedTypeTab?.key
                "sort" -> uiState.selectedSortOption.key
                else -> null
            }

            if (overlayAnchor != null && overlayOptions.isNotEmpty()) {
                LibraryInWindowDropdown(
                    pickerKey = overlayPicker,
                    anchorBounds = overlayAnchor,
                    rootOffset = libraryRootOffsetPx,
                    selectedValue = overlaySelectedValue,
                    options = overlayOptions,
                    hazeState = libraryPickerHazeState,
                    anchorFocusRequester = pickerFocusRequesters.getValue(overlayPicker),
                    onDismiss = {
                        runCatching {
                            pickerFocusRequesters.getValue(overlayPicker).requestFocus()
                        }
                        expandedPicker = null
                    },
                    onSelect = { option ->
                        runCatching {
                            pickerFocusRequesters.getValue(overlayPicker).requestFocus()
                        }
                        expandedPicker = null
                        when (overlayPicker) {
                            "list" -> viewModel.onSelectListTab(option.value)
                            "type" -> uiState.availableTypeTabs
                                .firstOrNull { it.key == option.value }
                                ?.let(viewModel::onSelectTypeTab)
                            "sort" -> uiState.availableSortOptions
                                .firstOrNull { it.key == option.value }
                                ?.let(viewModel::onSelectSortOption)
                        }
                    }
                )
            }
        }
    }

    if (uiState.showManageDialog && uiState.sourceMode == LibrarySourceMode.TRAKT) {
        ManageListsDialog(
            tabs = uiState.listTabs,
            selectedKey = uiState.manageSelectedListKey,
            errorMessage = uiState.errorMessage,
            pending = uiState.pendingOperation,
            onSelect = viewModel::onSelectManageList,
            onCreate = viewModel::onStartCreateList,
            onEdit = viewModel::onStartEditList,
            onMoveUp = viewModel::onMoveSelectedListUp,
            onMoveDown = viewModel::onMoveSelectedListDown,
            onDelete = { showDeleteConfirm = true },
            onDismiss = viewModel::onCloseManageLists
        )
    }

    if (showDeleteConfirm) {
        ConfirmDeleteDialog(
            pending = uiState.pendingOperation,
            onConfirm = {
                showDeleteConfirm = false
                viewModel.onDeleteSelectedList()
            },
            onCancel = { showDeleteConfirm = false }
        )
    }

    val listEditor = uiState.listEditorState
    if (listEditor != null && uiState.showManageDialog) {
        ListEditorDialog(
            state = listEditor,
            pending = uiState.pendingOperation,
            onNameChanged = viewModel::onUpdateEditorName,
            onDescriptionChanged = viewModel::onUpdateEditorDescription,
            onPrivacyChanged = viewModel::onUpdateEditorPrivacy,
            onSave = viewModel::onSubmitEditor,
            onCancel = viewModel::onCancelEditor
        )
    }

    val transientMessage = uiState.transientMessage
    if (!transientMessage.isNullOrBlank()) {
        Box(
            modifier = Modifier
                .fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.TopCenter
        ) {
            Text(
                text = transientMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioColors.TextPrimary,
                modifier = Modifier
                    .padding(top = 18.dp)
                    .background(SettingsGlassGroupColor, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LibrarySelectorsRow(
    sourceMode: LibrarySourceMode,
    listTabs: List<LibraryListTab>,
    typeTabs: List<LibraryTypeTab>,
    sortOptions: List<LibrarySortOption>,
    selectedListKey: String?,
    selectedTypeTab: LibraryTypeTab?,
    selectedSortOption: LibrarySortOption,
    primaryFocusRequester: FocusRequester,
    pickerFocusRequesters: Map<String, FocusRequester>,
    expandedPicker: String?,
    onAnchorBoundsChanged: (String, IntRect) -> Unit,
    onExpandedChange: (String, Boolean) -> Unit
) {
    val selectedListLabel = listTabs.firstOrNull { it.key == selectedListKey }?.title ?: "Select"
    val selectedTypeLabel = selectedTypeTab?.let { localizedTypeLabel(it.key) }
        ?: stringResource(R.string.library_type_all)
    val selectedSortLabel = stringResource(selectedSortOption.labelResId)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement =
            if (sourceMode == LibrarySourceMode.TRAKT) {
                Arrangement.spacedBy(24.dp)
            } else {
                Arrangement.SpaceEvenly
            }
    ) {
        if (sourceMode == LibrarySourceMode.TRAKT) {
            LibraryDropdownAnchor(
                pickerKey = "list",
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(primaryFocusRequester),
                anchorFocusRequester = pickerFocusRequesters.getValue("list"),
                title = stringResource(R.string.library_filter_list),
                value = selectedListLabel,
                expanded = expandedPicker == "list",
                onAnchorBoundsChanged = onAnchorBoundsChanged,
                onExpandedChange = { onExpandedChange("list", it) }
            )
        }

        LibraryDropdownAnchor(
            pickerKey = "type",
            modifier = if (sourceMode == LibrarySourceMode.TRAKT) {
                Modifier.weight(1f)
            } else {
                Modifier
                    .width(320.dp)
                    .focusRequester(primaryFocusRequester)
            },
            anchorFocusRequester = pickerFocusRequesters.getValue("type"),
            title = stringResource(R.string.library_filter_type),
            value = selectedTypeLabel,
            expanded = expandedPicker == "type",
            onAnchorBoundsChanged = onAnchorBoundsChanged,
            onExpandedChange = { onExpandedChange("type", it) }
        )

        if (sortOptions.isNotEmpty()) {
            LibraryDropdownAnchor(
                pickerKey = "sort",
                modifier = if (sourceMode == LibrarySourceMode.TRAKT) {
                    Modifier.weight(1f)
                } else {
                    Modifier.width(320.dp)
                },
                anchorFocusRequester = pickerFocusRequesters.getValue("sort"),
                title = stringResource(R.string.library_filter_sort),
                value = selectedSortLabel,
                expanded = expandedPicker == "sort",
                onAnchorBoundsChanged = onAnchorBoundsChanged,
                onExpandedChange = { onExpandedChange("sort", it) }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LibraryDropdownAnchor(
    pickerKey: String,
    modifier: Modifier = Modifier,
    anchorFocusRequester: FocusRequester,
    title: String,
    value: String,
    expanded: Boolean,
    onAnchorBoundsChanged: (String, IntRect) -> Unit,
    onExpandedChange: (Boolean) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .focusRequester(anchorFocusRequester)
            .onGloballyPositioned { coordinates ->
                val rootPosition = coordinates.positionInRoot()
                val left = rootPosition.x.roundToInt()
                val top = rootPosition.y.roundToInt()
                onAnchorBoundsChanged(
                    pickerKey,
                    IntRect(
                        left = left,
                        top = top,
                        right = left + coordinates.size.width,
                        bottom = top + coordinates.size.height
                    )
                )
            }
    ) {
        Card(
            onClick = { onExpandedChange(!expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isFocused = it.isFocused },
            shape = CardDefaults.shape(shape = RoundedCornerShape(14.dp)),
            colors = CardDefaults.colors(
                containerColor = SettingsGlassRowColor,
                focusedContainerColor = SettingsGlassRowFocusedColor
            ),
            border = CardDefaults.border(
                border = androidx.tv.material3.Border(
                    border = BorderStroke(1.dp, SettingsGlassBorderColor),
                    shape = RoundedCornerShape(14.dp)
                ),
                focusedBorder = androidx.tv.material3.Border.None
            ),
            scale = CardDefaults.scale(
                focusedScale = SettingsDialogFocusScale,
                pressedScale = SettingsDialogPressedScale
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioColors.TextTertiary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        imageVector = if (expanded) {
                            Icons.Default.KeyboardArrowUp
                        } else {
                            Icons.Default.KeyboardArrowDown
                        },
                        contentDescription = if (expanded) {
                            "Collapse $title"
                        } else {
                            "Expand $title"
                        },
                        tint = if (isFocused) {
                            NuvioColors.Secondary
                        } else {
                            NuvioColors.TextSecondary
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LibraryInWindowDropdown(
    pickerKey: String,
    anchorBounds: IntRect,
    rootOffset: IntOffset,
    selectedValue: String?,
    options: List<LibraryOption>,
    hazeState: HazeState,
    anchorFocusRequester: FocusRequester,
    onDismiss: () -> Unit,
    onSelect: (LibraryOption) -> Unit
) {
    val density = LocalDensity.current
    val optionFocusRequesters = remember(pickerKey, options) {
        options.associate { option -> option.value to FocusRequester() }
    }
    var focusedOptionValue by remember(pickerKey) {
        mutableStateOf<String?>(null)
    }
    val shape = RoundedCornerShape(14.dp)
    val gapPx = with(density) { 8.dp.roundToPx() }
    val menuWidth = with(density) { anchorBounds.width.toDp() }
    val localX = anchorBounds.left - rootOffset.x
    val localY = anchorBounds.bottom - rootOffset.y + gapPx
    val blurEnabled =
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S

    LaunchedEffect(pickerKey, selectedValue, options) {
        withFrameNanos { }
        val requester =
            selectedValue
                ?.let(optionFocusRequesters::get)
                ?: options.firstOrNull()?.value?.let(optionFocusRequesters::get)
                ?: return@LaunchedEffect

        var focused = runCatching { requester.requestFocus() }.getOrDefault(false)
        if (!focused) {
            delay(16L)
            runCatching { requester.requestFocus() }
        }
    }

    val hazeBackgroundColor = NuvioColors.Background
    val surfaceModifier =
        if (blurEnabled) {
            Modifier
                .clip(shape)
                .hazeEffect(state = hazeState) {
                    this.blurEnabled = true
                    /*
                     * Haze 1.x needs an opaque base when blurring Lazy content.
                     * Without it, the original sharp source remains visible
                     * behind the blurred layer and leaks through around each
                     * poster's bounds, making blur look strongest only in the
                     * center of every card.
                     */
                    this.backgroundColor = hazeBackgroundColor
                    this.blurRadius = 29.dp
                    this.noiseFactor = 0.025f
                }
        } else {
            Modifier.clip(shape)
        }

    Box(
        modifier = Modifier
            .offset { IntOffset(localX, localY) }
            .width(menuWidth)
            .heightIn(max = 260.dp)
            .zIndex(100f)
            .then(surfaceModifier)
            .background(
                brush = SettingsGlassCanvasBrush,
                shape = shape
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.09f),
                shape = shape
            )
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (
                    native.action == AndroidKeyEvent.ACTION_DOWN &&
                    native.keyCode == AndroidKeyEvent.KEYCODE_BACK
                ) {
                    runCatching { anchorFocusRequester.requestFocus() }
                    onDismiss()
                    true
                } else {
                    false
                }
            }
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                horizontal = 12.dp,
                vertical = 8.dp
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(options, key = { it.value }) { option ->
                val isSelected = option.value == selectedValue
                val isOptionFocused = option.value == focusedOptionValue
                val optionScale by animateFloatAsState(
                    targetValue = if (isOptionFocused) {
                        SettingsDialogFocusScale
                    } else {
                        1f
                    },
                    animationSpec = tween(
                        durationMillis = 160,
                        easing = FastOutSlowInEasing
                    ),
                    label = "libraryDropdownOptionScale"
                )

                Card(
                    onClick = {
                        runCatching { anchorFocusRequester.requestFocus() }
                        onSelect(option)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(
                            optionFocusRequesters.getValue(option.value)
                        )
                        .graphicsLayer {
                            scaleX = optionScale
                            scaleY = optionScale
                        }
                        .onFocusChanged { state ->
                            val hasFocus = state.isFocused || state.hasFocus
                            focusedOptionValue = when {
                                hasFocus -> option.value
                                focusedOptionValue == option.value -> null
                                else -> focusedOptionValue
                            }
                        },
                    shape = CardDefaults.shape(
                        shape = RoundedCornerShape(10.dp)
                    ),
                    colors = CardDefaults.colors(
                        containerColor = if (isSelected) {
                            SettingsDialogGlassSelectedColor
                        } else {
                            Color.Transparent
                        },
                        focusedContainerColor = SettingsGlassRowFocusedColor
                    ),
                    border = CardDefaults.border(
                        border = androidx.tv.material3.Border.None,
                        focusedBorder = androidx.tv.material3.Border.None
                    ),
                    scale = CardDefaults.scale(
                        focusedScale = 1f,
                        pressedScale = 1f
                    )
                ) {
                    Text(
                        text = option.label,
                        color = if (isOptionFocused || isSelected) {
                            NuvioColors.TextPrimary
                        } else {
                            NuvioColors.TextSecondary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

private data class LibraryOption(
    val label: String,
    val value: String
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LibraryActionsRow(
    pending: Boolean,
    isSyncing: Boolean,
    onManageLists: () -> Unit,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onManageLists,
            enabled = !pending && !isSyncing,
            colors = ButtonDefaults.colors(
                containerColor = SettingsRightSurfaceColor,
                focusedContainerColor = SettingsRightSurfaceFocusedColor,
                contentColor = NuvioColors.TextPrimary
            )
        ) {
            Text(stringResource(R.string.library_manage_lists))
        }
        Button(
            onClick = onRefresh,
            enabled = !pending && !isSyncing,
            colors = ButtonDefaults.colors(
                containerColor = SettingsRightSurfaceColor,
                focusedContainerColor = SettingsRightSurfaceFocusedColor,
                contentColor = NuvioColors.TextPrimary
            )
        ) {
            Text(if (isSyncing) "Syncing..." else "Sync")
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ManageListsDialog(
    tabs: List<LibraryListTab>,
    selectedKey: String?,
    errorMessage: String?,
    pending: Boolean,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val personalTabs = remember(tabs) { tabs.filter { it.type == LibraryListTab.Type.PERSONAL } }
    val firstFocusRequester = remember { FocusRequester() }
    val closeFocusRequester = remember { FocusRequester() }

    LaunchedEffect(personalTabs.size) {
        val target = if (personalTabs.isNotEmpty()) firstFocusRequester else closeFocusRequester
        val focused = runCatching { target.requestFocus() }.isSuccess
        if (!focused) {
            delay(16)
            runCatching { target.requestFocus() }
        }
    }

    NuvioDialog(
        glass = true,
        enhancedGlass = true,
        compact = true,
        onDismiss = onDismiss,
        title = stringResource(R.string.library_manage_trakt_lists),
        width = 520.dp,
        suppressFirstKeyUp = false
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFFFB6B6)
                    )
                }

                if (personalTabs.isEmpty()) {
                    Text(
                        text = stringResource(R.string.library_no_lists),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.extendedColors.textSecondary
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(personalTabs, key = { it.key }) { tab ->
                            val selected = tab.key == selectedKey
                            Button(
                                onClick = { onSelect(tab.key) },
                                enabled = !pending,
                                modifier = (if (tab.key == personalTabs.firstOrNull()?.key) {
                                    Modifier
                                        .fillMaxWidth()
                                        .focusRequester(firstFocusRequester)
                                } else {
                                    Modifier.fillMaxWidth()
                                }).glassDialogFocusTransform(),
                                colors = ButtonDefaults.colors(
                                    containerColor = if (selected) SettingsDialogGlassSelectedColor else SettingsDialogGlassIdleColor,
                        contentColor =
                            if (selected) {
                                NuvioColors.TextPrimary
                            } else {
                                NuvioColors.TextSecondary
                            }
                                ,
    focusedContainerColor = SettingsGlassRowFocusedColor
,
    focusedContentColor = NuvioColors.TextPrimary
)
                            ,
    scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        border = ButtonDefaults.border(border = androidx.tv.material3.Border.None, focusedBorder = androidx.tv.material3.Border.None)
    ,
        shape = ButtonDefaults.shape(SettingsDialogPillShape)
    ) {
                                Text(
                                    text = tab.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = onCreate,
                        enabled = !pending,
                        colors = ButtonDefaults.colors(
                            containerColor = SettingsDialogGlassIdleColor,
                            focusedContainerColor = SettingsGlassRowFocusedColor,
                            contentColor = NuvioColors.TextSecondary
                        ,
    focusedContentColor = NuvioColors.TextPrimary
)
                    ,
    scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        modifier = Modifier.glassDialogFocusTransform()
    ,
        border = ButtonDefaults.border(border = androidx.tv.material3.Border.None, focusedBorder = androidx.tv.material3.Border.None)
    ,
        shape = ButtonDefaults.shape(SettingsDialogPillShape)
    ) { Text(stringResource(R.string.library_list_create)) }
                    Button(
                        onClick = onEdit,
                        enabled = !pending && selectedKey != null,
                        colors = ButtonDefaults.colors(
                            containerColor = SettingsDialogGlassIdleColor,
                            focusedContainerColor = SettingsGlassRowFocusedColor,
                            contentColor = NuvioColors.TextSecondary
                        ,
    focusedContentColor = NuvioColors.TextPrimary
)
                    ,
    scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        modifier = Modifier.glassDialogFocusTransform()
    ,
        border = ButtonDefaults.border(border = androidx.tv.material3.Border.None, focusedBorder = androidx.tv.material3.Border.None)
    ,
        shape = ButtonDefaults.shape(SettingsDialogPillShape)
    ) { Text(stringResource(R.string.library_list_edit)) }
                    Button(
                        onClick = onMoveUp,
                        enabled = !pending && selectedKey != null,
                        colors = ButtonDefaults.colors(
                            containerColor = SettingsDialogGlassIdleColor,
                            focusedContainerColor = SettingsGlassRowFocusedColor,
                            contentColor = NuvioColors.TextSecondary
                        ,
    focusedContentColor = NuvioColors.TextPrimary
)
                    ,
    scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        modifier = Modifier.glassDialogFocusTransform()
    ,
        border = ButtonDefaults.border(border = androidx.tv.material3.Border.None, focusedBorder = androidx.tv.material3.Border.None)
    ,
        shape = ButtonDefaults.shape(SettingsDialogPillShape)
    ) { Text(stringResource(R.string.library_list_move_up)) }
                    Button(
                        onClick = onMoveDown,
                        enabled = !pending && selectedKey != null,
                        colors = ButtonDefaults.colors(
                            containerColor = SettingsDialogGlassIdleColor,
                            focusedContainerColor = SettingsGlassRowFocusedColor,
                            contentColor = NuvioColors.TextSecondary
                        ,
    focusedContentColor = NuvioColors.TextPrimary
)
                    ,
    scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        modifier = Modifier.glassDialogFocusTransform()
    ,
        border = ButtonDefaults.border(border = androidx.tv.material3.Border.None, focusedBorder = androidx.tv.material3.Border.None)
    ,
        shape = ButtonDefaults.shape(SettingsDialogPillShape)
    ) { Text(stringResource(R.string.library_list_move_down)) }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = onDelete,
                        enabled = !pending && selectedKey != null,
                        colors = ButtonDefaults.colors(
                            containerColor = Color(0xFF4A2323),
                            contentColor = NuvioColors.TextPrimary
                        ,
    focusedContainerColor = SettingsGlassRowFocusedColor
,
    focusedContentColor = NuvioColors.TextPrimary
)
                    ,
    scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        modifier = Modifier.glassDialogFocusTransform()
    ,
        border = ButtonDefaults.border(border = androidx.tv.material3.Border.None, focusedBorder = androidx.tv.material3.Border.None)
    ,
        shape = ButtonDefaults.shape(SettingsDialogPillShape)
    ) { Text(stringResource(R.string.library_list_delete)) }
                    Button(
                        onClick = onDismiss,
                        enabled = !pending,
                        modifier = (Modifier.focusRequester(closeFocusRequester)).glassDialogFocusTransform(),
                        colors = ButtonDefaults.colors(
                            containerColor = SettingsDialogGlassIdleColor,
                            focusedContainerColor = SettingsGlassRowFocusedColor,
                            contentColor = NuvioColors.TextSecondary
                        ,
    focusedContentColor = NuvioColors.TextPrimary
)
                    ,
    scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        border = ButtonDefaults.border(border = androidx.tv.material3.Border.None, focusedBorder = androidx.tv.material3.Border.None)
    ,
        shape = ButtonDefaults.shape(SettingsDialogPillShape)
    ) { Text(stringResource(R.string.library_list_close)) }
                }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ListEditorDialog(
    state: LibraryListEditorState,
    pending: Boolean,
    onNameChanged: (String) -> Unit,
    onDescriptionChanged: (String) -> Unit,
    onPrivacyChanged: (TraktListPrivacy) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    val nameFocusRequester = remember { FocusRequester() }
    val descriptionFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var nameEditing by remember { mutableStateOf(false) }
    var descriptionEditing by remember { mutableStateOf(false) }

    fun isSelectKey(keyCode: Int): Boolean {
        return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
            keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
    }

    LaunchedEffect(Unit) {
        nameFocusRequester.requestFocus()
    }

    NuvioDialog(
        glass = true,
        enhancedGlass = true,
        compact = true,
        onDismiss = onCancel,
        title = if (state.mode == LibraryListEditorState.Mode.CREATE) "Create List" else "Edit List",
        width = 480.dp
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = state.name,
            onValueChange = onNameChanged,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(nameFocusRequester)
                .onFocusChanged {
                    if (!it.isFocused) {
                        nameEditing = false
                    }
                }
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action == AndroidKeyEvent.ACTION_DOWN && isSelectKey(native.keyCode)) {
                        nameEditing = true
                        descriptionEditing = false
                        keyboardController?.show()
                    }
                    false
                },
            enabled = !pending,
            readOnly = !nameEditing,
            singleLine = true,
            maxLines = 1,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    nameEditing = false
                    keyboardController?.hide()
                }
            ),
            label = { androidx.compose.material3.Text(stringResource(R.string.library_list_name_label)) },
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = NuvioColors.TextPrimary,
                unfocusedTextColor = NuvioColors.TextPrimary,
                focusedContainerColor = SettingsDialogGlassInsetFocusedColor,
                unfocusedContainerColor = SettingsDialogGlassInsetColor,
                focusedBorderColor = SettingsGlassBorderColor,
                unfocusedBorderColor = SettingsGlassBorderColor,
                focusedLabelColor = NuvioColors.TextSecondary,
                unfocusedLabelColor = NuvioColors.TextTertiary,
                cursorColor = NuvioColors.Secondary
            )
        )

        androidx.compose.material3.OutlinedTextField(
            value = state.description,
            onValueChange = onDescriptionChanged,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(descriptionFocusRequester)
                .onFocusChanged {
                    if (!it.isFocused) {
                        descriptionEditing = false
                    }
                }
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action == AndroidKeyEvent.ACTION_DOWN && isSelectKey(native.keyCode)) {
                        descriptionEditing = true
                        nameEditing = false
                        keyboardController?.show()
                    }
                    false
                },
            enabled = !pending,
            readOnly = !descriptionEditing,
            minLines = 3,
            maxLines = 5,
            label = { androidx.compose.material3.Text(stringResource(R.string.library_list_description_label)) },
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = NuvioColors.TextPrimary,
                unfocusedTextColor = NuvioColors.TextPrimary,
                focusedContainerColor = SettingsDialogGlassInsetFocusedColor,
                unfocusedContainerColor = SettingsDialogGlassInsetColor,
                focusedBorderColor = SettingsGlassBorderColor,
                unfocusedBorderColor = SettingsGlassBorderColor,
                focusedLabelColor = NuvioColors.TextSecondary,
                unfocusedLabelColor = NuvioColors.TextTertiary,
                cursorColor = NuvioColors.Secondary
            )
        )

        Text(
            text = stringResource(R.string.library_list_privacy),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.extendedColors.textSecondary
        )

        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(TraktListPrivacy.entries.toList(), key = { it.name }) { privacy ->
                val selected = privacy == state.privacy
                Button(
                    onClick = { onPrivacyChanged(privacy) },
                    enabled = !pending,
                    colors = ButtonDefaults.colors(
                        containerColor = if (selected) SettingsDialogGlassSelectedColor else SettingsDialogGlassIdleColor,
                        contentColor =
                            if (selected) {
                                NuvioColors.TextPrimary
                            } else {
                                NuvioColors.TextSecondary
                            }
                    ,
    focusedContainerColor = SettingsGlassRowFocusedColor
,
    focusedContentColor = NuvioColors.TextPrimary
)
                ,
    scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        modifier = Modifier.glassDialogFocusTransform()
    ,
        border = ButtonDefaults.border(border = androidx.tv.material3.Border.None, focusedBorder = androidx.tv.material3.Border.None)
    ,
        shape = ButtonDefaults.shape(SettingsDialogPillShape)
    ) {
                    Text(privacy.apiValue.replaceFirstChar { it.uppercase() })
                }
            }
        }

        Button(
            onClick = onSave,
            enabled = !pending,
            modifier = (Modifier.fillMaxWidth()).glassDialogFocusTransform(),
            colors = ButtonDefaults.colors(
                containerColor = SettingsDialogGlassIdleColor,
                focusedContainerColor = SettingsGlassRowFocusedColor,
                contentColor = NuvioColors.TextSecondary
            ,
    focusedContentColor = NuvioColors.TextPrimary
)
        ,
    scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        border = ButtonDefaults.border(border = androidx.tv.material3.Border.None, focusedBorder = androidx.tv.material3.Border.None)
    ,
        shape = ButtonDefaults.shape(SettingsDialogPillShape)
    ) {
            Text(if (pending) "Saving..." else "Save")
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ConfirmDeleteDialog(
    pending: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    NuvioDialog(
        glass = true,
        enhancedGlass = true,
        compact = true,
        onDismiss = onCancel,
        title = stringResource(R.string.library_delete_title),
        subtitle = stringResource(R.string.library_delete_subtitle),
        width = 360.dp
    ) {
        Button(
            onClick = onConfirm,
            enabled = !pending,
            modifier = (Modifier.fillMaxWidth()).glassDialogFocusTransform(),
            colors = ButtonDefaults.colors(
                containerColor = Color(0xFF4A2323),
                contentColor = NuvioColors.TextPrimary
            ,
    focusedContainerColor = SettingsGlassRowFocusedColor
,
    focusedContentColor = NuvioColors.TextPrimary
)
        ,
    scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        border = ButtonDefaults.border(border = androidx.tv.material3.Border.None, focusedBorder = androidx.tv.material3.Border.None)
    ,
        shape = ButtonDefaults.shape(SettingsDialogPillShape)
    ) {
            Text(stringResource(R.string.library_list_delete))
        }
    }
}
