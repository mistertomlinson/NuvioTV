#!/usr/bin/env python3
from pathlib import Path

ROOT = Path.cwd()


def replace_once(rel: str, old: str, new: str) -> None:
    path = ROOT / rel
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{rel}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"patched {rel}")


# ---------------------------------------------------------------------------
# 1) Library selector group: center the two local/SIMKL bubbles with equal
#    outer/inter-bubble spacing, and give the expanded menu the same stronger
#    blurred/tinted glass language as the sidebar/dialog/rating surfaces.
# ---------------------------------------------------------------------------
LIB = "app/src/main/java/com/nuvio/tv/ui/screens/library/LibraryScreen.kt"

replace_once(
    LIB,
    "import androidx.compose.ui.Modifier\n",
    "import androidx.compose.ui.Modifier\nimport androidx.compose.ui.draw.clip\n"
)
replace_once(
    LIB,
    "import androidx.compose.ui.graphics.Color\n",
    "import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.graphics.Brush\n"
)
replace_once(
    LIB,
    "import com.nuvio.tv.ui.screens.settings.SettingsDialogPillShape\n",
    "import com.nuvio.tv.ui.screens.settings.SettingsDialogPillShape\n"
    "import dev.chrisbanes.haze.HazeState\n"
    "import dev.chrisbanes.haze.haze\n"
    "import dev.chrisbanes.haze.hazeEffect\n"
)

replace_once(
    LIB,
    "    var expandedPicker by remember { mutableStateOf<String?>(null) }\n"
    "    val primaryFocusRequester = remember { FocusRequester() }\n",
    "    var expandedPicker by remember { mutableStateOf<String?>(null) }\n"
    "    val libraryPickerHazeState = remember { HazeState() }\n"
    "    val primaryFocusRequester = remember { FocusRequester() }\n"
)

replace_once(
    LIB,
    "        modifier = Modifier\n"
    "            .fillMaxSize()\n"
    "            .onPreviewKeyEvent { event ->\n",
    "        modifier = Modifier\n"
    "            .fillMaxSize()\n"
    "            .then(\n"
    "                if (expandedPicker != null) {\n"
    "                    Modifier.haze(libraryPickerHazeState)\n"
    "                } else {\n"
    "                    Modifier\n"
    "                }\n"
    "            )\n"
    "            .onPreviewKeyEvent { event ->\n"
)

replace_once(
    LIB,
    "                primaryFocusRequester = primaryFocusRequester,\n"
    "                expandedPicker = expandedPicker,\n",
    "                primaryFocusRequester = primaryFocusRequester,\n"
    "                hazeState = libraryPickerHazeState,\n"
    "                expandedPicker = expandedPicker,\n"
)

lib_path = ROOT / LIB
lib_text = lib_path.read_text(encoding="utf-8")
row_start_marker = "@OptIn(ExperimentalTvMaterial3Api::class)\n@Composable\nprivate fun LibrarySelectorsRow("
row_end_marker = "\n@OptIn(ExperimentalTvMaterial3Api::class)\n@Composable\nprivate fun LibraryDropdownPicker("
start = lib_text.find(row_start_marker)
end = lib_text.find(row_end_marker, start)
if start < 0 or end < 0:
    raise SystemExit(f"{LIB}: unable to locate LibrarySelectorsRow block")

new_row = '''@OptIn(ExperimentalTvMaterial3Api::class)
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
    hazeState: HazeState,
    expandedPicker: String?,
    onExpandedChange: (String, Boolean) -> Unit,
    onSelectList: (String) -> Unit,
    onSelectType: (LibraryTypeTab) -> Unit,
    onSelectSort: (LibrarySortOption) -> Unit
) {
    val selectedListLabel = listTabs.firstOrNull { it.key == selectedListKey }?.title ?: "Select"
    val selectedTypeLabel = selectedTypeTab?.let { localizedTypeLabel(it.key) } ?: stringResource(R.string.library_type_all)
    val selectedSortLabel = stringResource(selectedSortOption.labelResId)

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Row(
            modifier =
                if (sourceMode == LibrarySourceMode.TRAKT) {
                    Modifier.fillMaxWidth()
                } else {
                    /*
                     * 2 x 320dp bubbles + three equal 24dp spaces gives a
                     * centered 712dp group: left edge, between bubbles, right
                     * edge all reserve the same breathing room. Focus scaling
                     * still leaves a visible gap between the two controls.
                     */
                    Modifier.width(712.dp)
                },
            horizontalArrangement =
                if (sourceMode == LibrarySourceMode.TRAKT) {
                    Arrangement.spacedBy(24.dp)
                } else {
                    Arrangement.SpaceEvenly
                }
        ) {
            if (sourceMode == LibrarySourceMode.TRAKT) {
                LibraryDropdownPicker(
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(primaryFocusRequester),
                    title = stringResource(R.string.library_filter_list),
                    value = selectedListLabel,
                    selectedValue = selectedListKey,
                    expanded = expandedPicker == "list",
                    options = listTabs.map { LibraryOption(it.title, it.key) },
                    hazeState = hazeState,
                    onExpandedChange = { onExpandedChange("list", it) },
                    onSelect = { onSelectList(it.value) }
                )
            }

            LibraryDropdownPicker(
                modifier = if (sourceMode == LibrarySourceMode.TRAKT) {
                    Modifier.weight(1f)
                } else {
                    Modifier
                        .width(320.dp)
                        .focusRequester(primaryFocusRequester)
                },
                title = stringResource(R.string.library_filter_type),
                value = selectedTypeLabel,
                selectedValue = selectedTypeTab?.key,
                expanded = expandedPicker == "type",
                options = typeTabs.map { LibraryOption(localizedTypeLabel(it.key), it.key) },
                hazeState = hazeState,
                onExpandedChange = { onExpandedChange("type", it) },
                onSelect = { option ->
                    typeTabs.firstOrNull { it.key == option.value }?.let(onSelectType)
                }
            )

            if (sortOptions.isNotEmpty()) {
                LibraryDropdownPicker(
                    modifier =
                        if (sourceMode == LibrarySourceMode.TRAKT) {
                            Modifier.weight(1f)
                        } else {
                            Modifier.width(320.dp)
                        },
                    title = stringResource(R.string.library_filter_sort),
                    value = selectedSortLabel,
                    selectedValue = selectedSortOption.key,
                    expanded = expandedPicker == "sort",
                    options = sortOptions.map { LibraryOption(stringResource(it.labelResId), it.key) },
                    hazeState = hazeState,
                    onExpandedChange = { onExpandedChange("sort", it) },
                    onSelect = { option ->
                        sortOptions.firstOrNull { it.key == option.value }?.let(onSelectSort)
                    }
                )
            }
        }
    }
}
'''
lib_text = lib_text[:start] + new_row + lib_text[end:]
lib_path.write_text(lib_text, encoding="utf-8")
print(f"patched {LIB} selector group")

replace_once(
    LIB,
    "    expanded: Boolean,\n"
    "    options: List<LibraryOption>,\n"
    "    onExpandedChange: (Boolean) -> Unit,\n",
    "    expanded: Boolean,\n"
    "    options: List<LibraryOption>,\n"
    "    hazeState: HazeState,\n"
    "    onExpandedChange: (Boolean) -> Unit,\n"
)

replace_once(
    LIB,
    "    var focusedOptionValue by remember(expanded) { mutableStateOf<String?>(null) }\n\n"
    "    Box(modifier = modifier) {\n",
    "    var focusedOptionValue by remember(expanded) { mutableStateOf<String?>(null) }\n"
    "    val popupShape = RoundedCornerShape(14.dp)\n"
    "    val popupGlassBrush = remember {\n"
    "        Brush.verticalGradient(\n"
    "            colors = listOf(\n"
    "                Color(0x702A3038),\n"
    "                Color(0x6420252C),\n"
    "                Color(0x6A24292F)\n"
    "            )\n"
    "        )\n"
    "    }\n"
    "    val popupGlassBorderColor = Color.White.copy(alpha = 0.09f)\n"
    "    val popupBlurEnabled =\n"
    "        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S\n"
    "    val popupBlurModifier =\n"
    "        if (popupBlurEnabled) {\n"
    "            Modifier\n"
    "                .clip(popupShape)\n"
    "                .hazeEffect(state = hazeState) {\n"
    "                    this.blurEnabled = true\n"
    "                    this.blurRadius = 29.dp\n"
    "                    this.noiseFactor = 0.025f\n"
    "                }\n"
    "        } else {\n"
    "            Modifier\n"
    "        }\n\n"
    "    Box(modifier = modifier) {\n"
)

replace_once(
    LIB,
    "        DropdownMenu(\n"
    "            expanded = expanded,\n"
    "            onDismissRequest = {\n"
    "                focusedOptionValue = null\n"
    "                onExpandedChange(false)\n"
    "            },\n"
    "            modifier = Modifier\n"
    "                .width(with(LocalDensity.current) { anchorSize.width.toDp() })\n"
    "                .heightIn(max = 260.dp),\n"
    "            shape = RoundedCornerShape(14.dp),\n"
    "            containerColor = SettingsGlassRowColor,\n"
    "            tonalElevation = 0.dp,\n"
    "            shadowElevation = 8.dp,\n"
    "            border = BorderStroke(1.dp, SettingsGlassBorderColor)\n"
    "        ) {\n",
    "        DropdownMenu(\n"
    "            expanded = expanded,\n"
    "            onDismissRequest = {\n"
    "                focusedOptionValue = null\n"
    "                onExpandedChange(false)\n"
    "            },\n"
    "            modifier = Modifier\n"
    "                .width(with(LocalDensity.current) { anchorSize.width.toDp() })\n"
    "                .heightIn(max = 260.dp)\n"
    "                .then(popupBlurModifier)\n"
    "                .background(\n"
    "                    brush = popupGlassBrush,\n"
    "                    shape = popupShape\n"
    "                ),\n"
    "            shape = popupShape,\n"
    "            containerColor = Color.Transparent,\n"
    "            tonalElevation = 0.dp,\n"
    "            shadowElevation = 12.dp,\n"
    "            border = BorderStroke(1.dp, popupGlassBorderColor)\n"
    "        ) {\n"
)


# ---------------------------------------------------------------------------
# 2) Root navigation: direct, explicit collapse signal. The previous patch
#    released preservation but effectiveExpandEnabled could still keep the
#    focused card expanded. This signal overrides every preservation/autoplay
#    path during the pre-snapshot window.
# ---------------------------------------------------------------------------
MAIN = "app/src/main/java/com/nuvio/tv/MainActivity.kt"
replace_once(
    MAIN,
    "val LocalPreserveSidebarTrailerPlayback = compositionLocalOf { false }\n\n",
    "val LocalPreserveSidebarTrailerPlayback = compositionLocalOf { false }\n"
    "val LocalRootNavigationCollapseExpandedCard = compositionLocalOf { false }\n\n"
)
replace_once(
    MAIN,
    "                LocalPreserveSidebarTrailerPlayback provides\n"
    "                    preserveSidebarTrailerPlayback,\n"
    "                LocalSidebarFocusRestoreActive provides\n",
    "                LocalPreserveSidebarTrailerPlayback provides\n"
    "                    preserveSidebarTrailerPlayback,\n"
    "                LocalRootNavigationCollapseExpandedCard provides\n"
    "                    (legacyRootTransitionTarget != null),\n"
    "                LocalSidebarFocusRestoreActive provides\n"
)
replace_once(
    MAIN,
    "                                                delay(220L)\n"
    "                                                repeat(2) {\n",
    "                                                /*\n"
    "                                                 * The expanded-card width uses\n"
    "                                                 * Compose's default spring. Give\n"
    "                                                 * the explicit collapse signal and\n"
    "                                                 * 260ms drawer exit enough time to\n"
    "                                                 * settle before PixelCopy.\n"
    "                                                 */\n"
    "                                                delay(360L)\n"
    "                                                repeat(2) {\n"
)

ROWS = "app/src/main/java/com/nuvio/tv/ui/screens/home/ModernHomeRows.kt"
replace_once(
    ROWS,
    "import com.nuvio.tv.LocalPreserveSidebarTrailerPlayback\n",
    "import com.nuvio.tv.LocalPreserveSidebarTrailerPlayback\n"
    "import com.nuvio.tv.LocalRootNavigationCollapseExpandedCard\n"
)
replace_once(
    ROWS,
    "    val preserveSidebarTrailerPlayback =\n"
    "        LocalPreserveSidebarTrailerPlayback.current\n"
    "    val sidebarFocusRestoreActive =\n",
    "    val preserveSidebarTrailerPlayback =\n"
    "        LocalPreserveSidebarTrailerPlayback.current\n"
    "    val rootNavigationCollapseExpandedCard =\n"
    "        LocalRootNavigationCollapseExpandedCard.current\n"
    "    val sidebarFocusRestoreActive =\n"
)
replace_once(
    ROWS,
    "    val isBackdropExpanded =\n"
    "        (\n",
    "    val isBackdropExpanded =\n"
    "        !rootNavigationCollapseExpandedCard &&\n"
    "        (\n"
)
replace_once(
    ROWS,
    "    val playTrailerInExpandedCard =\n"
    "        (\n",
    "    val playTrailerInExpandedCard =\n"
    "        !rootNavigationCollapseExpandedCard &&\n"
    "        (\n"
)


# ---------------------------------------------------------------------------
# 3) Series unwatch badge: optimistic presentation only. Begin the existing
#    shrink/fade immediately, but do NOT mutate provider/history truth. If the
#    async whole-series mutation fails, restore the badge from action status.
# ---------------------------------------------------------------------------
replace_once(
    ROWS,
    "    fun armWatchedExitAnimation(statusKey: String) {\n"
    "        val state = watchedExitGenerationStateFor(statusKey)\n"
    "        state.intValue = state.intValue + 1\n"
    "    }\n\n",
    "    fun beginWatchedExit(statusKey: String) {\n"
    "        val generation = watchedExitGenerationStateFor(statusKey)\n"
    "        generation.intValue = generation.intValue + 1\n"
    "        stateFor(watchedStates, statusKey).value = false\n"
    "    }\n\n"
    "    fun restoreWatched(statusKey: String) {\n"
    "        stateFor(watchedStates, statusKey).value = true\n"
    "    }\n\n"
)

HOME = "app/src/main/java/com/nuvio/tv/ui/screens/home/HomeScreen.kt"
replace_once(
    HOME,
    "    var watchedRatingHandoffIsSeries by remember {\n"
    "        mutableStateOf(false)\n"
    "    }\n\n"
    "    val watchedRatingHandoffActive =\n",
    "    var watchedRatingHandoffIsSeries by remember {\n"
    "        mutableStateOf(false)\n"
    "    }\n\n"
    "    /*\n"
    "     * Whole-series unwatch can take noticeable time because the\n"
    "     * repository removes episode history in a batch. Keep that async\n"
    "     * mutation authoritative, but let the poster badge begin its existing\n"
    "     * exit immediately. Value=false means we have not yet observed the\n"
    "     * ViewModel pending state for this optimistic action.\n"
    "     */\n"
    "    val optimisticSeriesUnwatchPendingObserved = remember {\n"
    "        androidx.compose.runtime.mutableStateMapOf<String, Boolean>()\n"
    "    }\n\n"
    "    LaunchedEffect(\n"
    "        uiState.seriesWatchedActionPending,\n"
    "        uiState.seriesWatchedActionStatus\n"
    "    ) {\n"
    "        optimisticSeriesUnwatchPendingObserved\n"
    "            .keys\n"
    "            .toList()\n"
    "            .forEach { statusKey ->\n"
    "                if (statusKey in uiState.seriesWatchedActionPending) {\n"
    "                    optimisticSeriesUnwatchPendingObserved[statusKey] = true\n"
    "                    return@forEach\n"
    "                }\n\n"
    "                if (optimisticSeriesUnwatchPendingObserved[statusKey] == true) {\n"
    "                    /*\n"
    "                     * Success publishes false. A failure leaves the old\n"
    "                     * true action status intact, so roll the presentation\n"
    "                     * back without touching history/provider state.\n"
    "                     */\n"
    "                    if (uiState.seriesWatchedActionStatus[statusKey] == true) {\n"
    "                        posterStatusRegistry.restoreWatched(statusKey)\n"
    "                    }\n"
    "                    optimisticSeriesUnwatchPendingObserved.remove(statusKey)\n"
    "                }\n"
    "            }\n"
    "    }\n\n"
    "    val watchedRatingHandoffActive =\n"
)

old_series_action = '''                } else if (isSeries) {
                    val wasAlreadyWatched =
                        uiState.seriesWatchedActionStatus[statusKey] == true

                    if (!wasAlreadyWatched) {
                        watchedRatingHandoffStatusKey = statusKey
                        watchedRatingHandoffObservedPending = false
                        watchedRatingHandoffIsSeries = true
                    } else {
                        /*
                         * Whole-series action state updates before the
                         * terminal poster-badge state. Arm this exact poster
                         * so the later false state still takes the normal
                         * shrink/fade watched-icon exit.
                         */
                        posterStatusRegistry
                            .armWatchedExitAnimation(statusKey)
                    }

                    viewModel.togglePosterSeriesWatched(
                        item = item,
                        addonBaseUrl = selectedPoster.addonBaseUrl
                    )

                    /*
                     * Mark Watched may hand directly to the rating overlay.
                     * Mark Unwatched has no rating step and closes immediately.
                     */
                    if (wasAlreadyWatched) {
                        posterOptionsTarget = null
                    }
'''
new_series_action = '''                } else if (isSeries) {
                    val wasAlreadyWatched =
                        uiState.seriesWatchedActionStatus[statusKey] == true

                    if (!wasAlreadyWatched) {
                        watchedRatingHandoffStatusKey = statusKey
                        watchedRatingHandoffObservedPending = false
                        watchedRatingHandoffIsSeries = true
                    }

                    /*
                     * togglePosterSeriesWatched synchronously publishes its
                     * pending key before launching the expensive episode batch.
                     * Start the optimistic badge exit immediately after that
                     * publication. The provider/history mutation itself remains
                     * unchanged and authoritative.
                     */
                    viewModel.togglePosterSeriesWatched(
                        item = item,
                        addonBaseUrl = selectedPoster.addonBaseUrl
                    )

                    if (wasAlreadyWatched) {
                        optimisticSeriesUnwatchPendingObserved[statusKey] = false
                        posterStatusRegistry.beginWatchedExit(statusKey)
                    }

                    /*
                     * Mark Watched may hand directly to the rating overlay.
                     * Mark Unwatched has no rating step and closes immediately.
                     */
                    if (wasAlreadyWatched) {
                        posterOptionsTarget = null
                    }
'''
replace_once(HOME, old_series_action, new_series_action)

# Staging-only patch helper should not be part of the finished feature commit.
Path(__file__).unlink()
print("Follow-up UI polish patch applied.")
