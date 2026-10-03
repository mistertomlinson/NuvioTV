@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Timer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.ui.components.ClassicLayoutPreview
import com.nuvio.tv.ui.components.GridLayoutPreview
import com.nuvio.tv.ui.components.ModernLayoutPreview
import com.nuvio.tv.ui.components.CardCwStylePreview
import com.nuvio.tv.ui.components.WideCwStylePreview
import com.nuvio.tv.ui.components.PosterCwStylePreview
import com.nuvio.tv.ui.theme.NuvioColors
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.nuvio.tv.domain.model.CardDepthStyle
import com.nuvio.tv.domain.model.DEFAULT_CARD_DEPTH_EDGE_COVERAGE
import com.nuvio.tv.domain.model.DEFAULT_CARD_DEPTH_EDGE_STRENGTH
import com.nuvio.tv.domain.model.DEFAULT_CARD_DEPTH_SHEEN_STRENGTH
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.cardDepthVisual
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LayoutSettingsScreen(
    viewModel: LayoutSettingsViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    BackHandler { onBackPress() }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.layout_title),
        subtitle = stringResource(R.string.layout_subtitle)
    ) {
        LayoutSettingsContent(viewModel = viewModel)
    }
}

private enum class LayoutSettingsSection {
    HOME_CONTENT,
    CONTINUE_WATCHING,
    DETAIL_PAGE,
    FOCUSED_POSTER,
    POSTER_CARD_STYLE
}

private const val DEFAULT_SECTION_COLLAPSE_MILLIS = 240
private const val POSTER_CARD_STYLE_COLLAPSE_MILLIS = 240
private const val POSTER_CARD_STYLE_LIST_SETTLE_MILLIS = 520

@Composable
fun LayoutSettingsContent(
    viewModel: LayoutSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var homeContentExpanded by rememberSaveable { mutableStateOf(false) }
    var continueWatchingExpanded by rememberSaveable { mutableStateOf(false) }
    var detailPageExpanded by rememberSaveable { mutableStateOf(false) }
    var focusedPosterExpanded by rememberSaveable { mutableStateOf(false) }
    var posterCardStyleExpanded by rememberSaveable { mutableStateOf(false) }
    var showCardDepthFineTuneDialog by remember {
        mutableStateOf(false)
    }

    val defaultHomeContentHeaderFocus = remember { FocusRequester() }
    val homeContentHeaderFocus = initialFocusRequester ?: defaultHomeContentHeaderFocus
    val continueWatchingHeaderFocus = remember { FocusRequester() }
    val detailPageHeaderFocus = remember { FocusRequester() }
    val focusedPosterHeaderFocus = remember { FocusRequester() }
    val posterCardStyleHeaderFocus = remember { FocusRequester() }

    var focusedSection by remember { mutableStateOf<LayoutSettingsSection?>(null) }
    val layoutAnimationScope = rememberCoroutineScope()
    val posterListSettleOffsetY = remember { Animatable(0f) }
    var animatedFlattenBoundaries by remember {
        mutableStateOf<Set<Int>>(emptySet())
    }
    var deferredBottomCornerSections by remember {
        mutableStateOf<Set<LayoutSettingsSection>>(emptySet())
    }

    LaunchedEffect(homeContentExpanded, focusedSection) {
        if (!homeContentExpanded && focusedSection == LayoutSettingsSection.HOME_CONTENT) {
            homeContentHeaderFocus.requestFocus()
        }
    }
    LaunchedEffect(continueWatchingExpanded, focusedSection) {
        if (!continueWatchingExpanded && focusedSection == LayoutSettingsSection.CONTINUE_WATCHING) {
            continueWatchingHeaderFocus.requestFocus()
        }
    }
    LaunchedEffect(detailPageExpanded, focusedSection) {
        if (!detailPageExpanded && focusedSection == LayoutSettingsSection.DETAIL_PAGE) {
            detailPageHeaderFocus.requestFocus()
        }
    }
    LaunchedEffect(focusedPosterExpanded, focusedSection) {
        if (!focusedPosterExpanded && focusedSection == LayoutSettingsSection.FOCUSED_POSTER) {
            focusedPosterHeaderFocus.requestFocus()
        }
    }
    LaunchedEffect(posterCardStyleExpanded, focusedSection) {
        if (!posterCardStyleExpanded && focusedSection == LayoutSettingsSection.POSTER_CARD_STYLE) {
            posterCardStyleHeaderFocus.requestFocus()
        }
    }

    val visibleSections = listOf(
        LayoutSettingsSection.HOME_CONTENT,
        LayoutSettingsSection.CONTINUE_WATCHING,
        LayoutSettingsSection.DETAIL_PAGE,
        LayoutSettingsSection.FOCUSED_POSTER,
        LayoutSettingsSection.POSTER_CARD_STYLE
    )
    val expandedSections = buildSet {
        if (homeContentExpanded) add(LayoutSettingsSection.HOME_CONTENT)
        if (continueWatchingExpanded) add(LayoutSettingsSection.CONTINUE_WATCHING)
        if (detailPageExpanded) add(LayoutSettingsSection.DETAIL_PAGE)
        if (focusedPosterExpanded) add(LayoutSettingsSection.FOCUSED_POSTER)
        if (posterCardStyleExpanded) add(LayoutSettingsSection.POSTER_CARD_STYLE)
    }
    fun groupPositionFor(
        section: LayoutSettingsSection,
        expanded: Set<LayoutSettingsSection> = expandedSections
    ): SettingsGroupPosition {
        val index = visibleSections.indexOf(section)
        if (index < 0) return SettingsGroupPosition.SINGLE
        if (section in expanded) return SettingsGroupPosition.TOP

        val startsGroup =
            index == 0 || visibleSections[index - 1] in expanded
        val endsGroup =
            index == visibleSections.lastIndex ||
                visibleSections[index + 1] in expanded
        return when {
            startsGroup && endsGroup -> SettingsGroupPosition.SINGLE
            startsGroup -> SettingsGroupPosition.TOP
            endsGroup -> SettingsGroupPosition.BOTTOM
            else -> SettingsGroupPosition.MIDDLE
        }
    }

    fun headerTopRounded(
        section: LayoutSettingsSection,
        expanded: Set<LayoutSettingsSection>
    ): Boolean {
        return when (groupPositionFor(section, expanded)) {
            SettingsGroupPosition.SINGLE,
            SettingsGroupPosition.TOP -> true
            SettingsGroupPosition.MIDDLE,
            SettingsGroupPosition.BOTTOM -> false
        }
    }

    fun headerBottomRounded(
        section: LayoutSettingsSection,
        expanded: Set<LayoutSettingsSection>
    ): Boolean {
        return when (groupPositionFor(section, expanded)) {
            SettingsGroupPosition.SINGLE,
            SettingsGroupPosition.BOTTOM -> true
            SettingsGroupPosition.TOP,
            SettingsGroupPosition.MIDDLE -> false
        }
    }

    fun applySectionExpandedState(
        section: LayoutSettingsSection,
        expanded: Boolean
    ) {
        when (section) {
            LayoutSettingsSection.HOME_CONTENT ->
                homeContentExpanded = expanded
            LayoutSettingsSection.CONTINUE_WATCHING ->
                continueWatchingExpanded = expanded
            LayoutSettingsSection.DETAIL_PAGE ->
                detailPageExpanded = expanded
            LayoutSettingsSection.FOCUSED_POSTER ->
                focusedPosterExpanded = expanded
            LayoutSettingsSection.POSTER_CARD_STYLE ->
                posterCardStyleExpanded = expanded
        }
    }

    fun beginBoundaryReturnAnimation(
        boundaries: Set<Int>
    ) {
        animatedFlattenBoundaries = boundaries
        if (boundaries.isNotEmpty()) {
            layoutAnimationScope.launch {
                delay(260)
                if (animatedFlattenBoundaries == boundaries) {
                    animatedFlattenBoundaries = emptySet()
                }
            }
        }
    }

    fun immediateFlattenBoundariesForCollapse(
        section: LayoutSettingsSection,
        before: Set<LayoutSettingsSection>,
        after: Set<LayoutSettingsSection>
    ): Set<Int> {
        val sectionIndex = visibleSections.indexOf(section)
        if (sectionIndex < 0) return emptySet()

        return listOf(sectionIndex - 1, sectionIndex)
            .filter { boundaryIndex ->
                boundaryIndex >= 0 &&
                    boundaryIndex < visibleSections.lastIndex
            }
            .filter { boundaryIndex ->
                val upper = visibleSections[boundaryIndex]
                val lower = visibleSections[boundaryIndex + 1]

                val upperRoundedBefore =
                    headerBottomRounded(upper, before)
                val lowerRoundedBefore =
                    headerTopRounded(lower, before)
                val upperRoundedAfter =
                    headerBottomRounded(upper, after)
                val lowerRoundedAfter =
                    headerTopRounded(lower, after)

                val finalBoundaryIsFlat =
                    !upperRoundedAfter && !lowerRoundedAfter
                val aRoundedEdgeNeedsToFlatten =
                    (upperRoundedBefore && !upperRoundedAfter) ||
                        (lowerRoundedBefore && !lowerRoundedAfter)

                /*
                 * Start flattening NOW whenever a rounded persistent header
                 * edge will eventually meet a flat edge after the collapsing
                 * child rows disappear. By the time physical contact occurs,
                 * both sides are already flat; no late snap is needed.
                 */
                finalBoundaryIsFlat &&
                    aRoundedEdgeNeedsToFlatten
            }
            .toSet()
    }

    fun prepareSectionCollapseCorners(
        section: LayoutSettingsSection,
        before: Set<LayoutSettingsSection>,
        after: Set<LayoutSettingsSection>
    ) {
        /*
         * Every persistent header edge that will need to be flat at contact
         * begins its rounded -> flat animation at collapse start.
         */
        beginBoundaryReturnAnimation(
            immediateFlattenBoundariesForCollapse(
                section = section,
                before = before,
                after = after
            )
        )

        /*
         * The collapsing parent's bottom edge is different: the child list
         * still occupies that edge, so it must remain flat until the rows are
         * completely gone. Only then may its final outside rounding animate.
         */
        deferredBottomCornerSections =
            deferredBottomCornerSections + section
    }

    fun releaseDeferredBottomCornerAfterCollapse(
        section: LayoutSettingsSection,
        collapseDurationMillis: Int =
            DEFAULT_SECTION_COLLAPSE_MILLIS
    ) {
        layoutAnimationScope.launch {
            delay(collapseDurationMillis.toLong())

            if (section !in deferredBottomCornerSections) {
                return@launch
            }

            androidx.compose.runtime.withFrameNanos { }
            deferredBottomCornerSections =
                deferredBottomCornerSections - section
        }
    }

    fun setSectionExpandedWithCornerPolicy(
        section: LayoutSettingsSection,
        expanded: Boolean
    ) {
        val before = expandedSections
        val after =
            if (expanded) {
                before + section
            } else {
                before - section
            }

        if (expanded) {
            deferredBottomCornerSections =
                deferredBottomCornerSections - section
            animatedFlattenBoundaries = emptySet()
            applySectionExpandedState(section, true)
            return
        }

        prepareSectionCollapseCorners(
            section = section,
            before = before,
            after = after
        )
        applySectionExpandedState(section, false)
        releaseDeferredBottomCornerAfterCollapse(section)
    }

    fun animateTopFlattenFor(
        section: LayoutSettingsSection
    ): Boolean {
        val index = visibleSections.indexOf(section)
        return index > 0 &&
            (index - 1) in animatedFlattenBoundaries
    }

    fun animateBottomFlattenFor(
        section: LayoutSettingsSection
    ): Boolean {
        val index = visibleSections.indexOf(section)
        return index >= 0 &&
            index < visibleSections.lastIndex &&
            index in animatedFlattenBoundaries
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.layout_title),
            subtitle = stringResource(R.string.layout_subtitle)
        )

        val layoutListState = rememberLazyListState()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds()
        ) {
        LazyColumn(
            state = layoutListState,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationY = posterListSettleOffsetY.value
                },
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(SettingsRowGap)
        ) {
            item(key = "home_content_section") {
                CollapsibleSectionCard(
                    title = stringResource(R.string.layout_section_content),
                    description = stringResource(R.string.layout_section_content_desc),
                    expanded = homeContentExpanded,
                    onToggle = {
                        setSectionExpandedWithCornerPolicy(
                            LayoutSettingsSection.HOME_CONTENT,
                            !homeContentExpanded
                        )
                    },
                    focusRequester = homeContentHeaderFocus,
                    onFocused = { focusedSection = LayoutSettingsSection.HOME_CONTENT },
                    groupPosition = groupPositionFor(
                        LayoutSettingsSection.HOME_CONTENT
                    ),
                    animateTopFlatten = animateTopFlattenFor(
                        LayoutSettingsSection.HOME_CONTENT
                    ),
                    animateBottomFlatten = animateBottomFlattenFor(
                        LayoutSettingsSection.HOME_CONTENT
                    ),
                    deferBottomCorner =
                        LayoutSettingsSection.HOME_CONTENT in
                            deferredBottomCornerSections
                ) {
                    CompactToggleRow(
                        title = stringResource(R.string.layout_landscape_posters),
                        subtitle = stringResource(R.string.layout_landscape_posters_sub),
                        checked = uiState.modernLandscapePostersEnabled,
                        onToggle = {
                            viewModel.onEvent(
                                LayoutSettingsEvent.SetModernLandscapePostersEnabled(
                                    !uiState.modernLandscapePostersEnabled
                                )
                            )
                        },
                        onFocused = { focusedSection = LayoutSettingsSection.HOME_CONTENT }
                    )

                    CompactToggleRow(
                        title = stringResource(R.string.layout_show_discover),
                        subtitle = stringResource(R.string.layout_show_discover_sub),
                        checked = uiState.searchDiscoverEnabled,
                        onToggle = {
                            viewModel.onEvent(
                                LayoutSettingsEvent.SetSearchDiscoverEnabled(!uiState.searchDiscoverEnabled)
                            )
                        },
                        onFocused = { focusedSection = LayoutSettingsSection.HOME_CONTENT }
                    )
                    CompactToggleRow(
                        title = stringResource(R.string.layout_catalog_type),
                        subtitle = stringResource(R.string.layout_catalog_type_sub),
                        checked = uiState.catalogTypeSuffixEnabled,
                        onToggle = {
                            viewModel.onEvent(
                                LayoutSettingsEvent.SetCatalogTypeSuffixEnabled(!uiState.catalogTypeSuffixEnabled)
                            )
                        },
                        onFocused = { focusedSection = LayoutSettingsSection.HOME_CONTENT }
                    )
                    CompactToggleRow(
                        title = stringResource(R.string.layout_hide_unreleased),
                        subtitle = stringResource(R.string.layout_hide_unreleased_sub),
                        checked = uiState.hideUnreleasedContent,
                        onToggle = {
                            viewModel.onEvent(
                                LayoutSettingsEvent.SetHideUnreleasedContent(!uiState.hideUnreleasedContent)
                            )
                        },
                        onFocused = { focusedSection = LayoutSettingsSection.HOME_CONTENT }
                    )
                    CompactToggleRow(
                        title = stringResource(R.string.layout_hide_new_season_badge),
                        subtitle = stringResource(R.string.layout_hide_new_season_badge_sub),
                        checked = uiState.hideNewSeasonBadge,
                        onToggle = {
                            viewModel.onEvent(
                                LayoutSettingsEvent.SetHideNewSeasonBadge(!uiState.hideNewSeasonBadge)
                            )
                        },
                        onFocused = { focusedSection = LayoutSettingsSection.HOME_CONTENT }
                    )
                }
            }

            item(key = "continue_watching_section") {
                CollapsibleSectionCard(
                    title = stringResource(R.string.layout_section_continue_watching),
                    description = stringResource(R.string.layout_section_continue_watching_desc),
                    expanded = continueWatchingExpanded,
                    onToggle = {
                        setSectionExpandedWithCornerPolicy(
                            LayoutSettingsSection.CONTINUE_WATCHING,
                            !continueWatchingExpanded
                        )
                    },
                    focusRequester = continueWatchingHeaderFocus,
                    onFocused = {
                        focusedSection = LayoutSettingsSection.CONTINUE_WATCHING
                    },
                    groupPosition = groupPositionFor(
                        LayoutSettingsSection.CONTINUE_WATCHING
                    ),
                    animateTopFlatten = animateTopFlattenFor(
                        LayoutSettingsSection.CONTINUE_WATCHING
                    ),
                    animateBottomFlatten = animateBottomFlattenFor(
                        LayoutSettingsSection.CONTINUE_WATCHING
                    ),
                    deferBottomCorner =
                        LayoutSettingsSection.CONTINUE_WATCHING in
                            deferredBottomCornerSections
                ) {
                    LayoutControlGroup(
                        groupPosition = SettingsGroupPosition.BOTTOM
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SettingsRightSurfaceColor)
                                .padding(10.dp)
                                .focusGroup(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ContinueWatchingStyleCard(
                                style = ContinueWatchingCardStyle.CARD,
                                isSelected =
                                    uiState.continueWatchingCardStyle ==
                                        ContinueWatchingCardStyle.CARD,
                                onClick = {
                                    viewModel.onEvent(
                                        LayoutSettingsEvent.SetContinueWatchingCardStyle(
                                            ContinueWatchingCardStyle.CARD
                                        )
                                    )
                                },
                                onFocused = {
                                    focusedSection =
                                        LayoutSettingsSection.CONTINUE_WATCHING
                                },
                                modifier = Modifier.weight(1f)
                            )
                            ContinueWatchingStyleCard(
                                style = ContinueWatchingCardStyle.WIDE,
                                isSelected =
                                    uiState.continueWatchingCardStyle ==
                                        ContinueWatchingCardStyle.WIDE,
                                onClick = {
                                    viewModel.onEvent(
                                        LayoutSettingsEvent.SetContinueWatchingCardStyle(
                                            ContinueWatchingCardStyle.WIDE
                                        )
                                    )
                                },
                                onFocused = {
                                    focusedSection =
                                        LayoutSettingsSection.CONTINUE_WATCHING
                                },
                                modifier = Modifier.weight(1f)
                            )
                            ContinueWatchingStyleCard(
                                style = ContinueWatchingCardStyle.POSTER,
                                isSelected =
                                    uiState.continueWatchingCardStyle ==
                                        ContinueWatchingCardStyle.POSTER,
                                onClick = {
                                    viewModel.onEvent(
                                        LayoutSettingsEvent.SetContinueWatchingCardStyle(
                                            ContinueWatchingCardStyle.POSTER
                                        )
                                    )
                                },
                                onFocused = {
                                    focusedSection =
                                        LayoutSettingsSection.CONTINUE_WATCHING
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            item(key = "detail_page_section") {
                CollapsibleSectionCard(
                    title = stringResource(R.string.layout_section_detail),
                    description = stringResource(R.string.layout_section_detail_desc),
                    expanded = detailPageExpanded,
                    onToggle = {
                        setSectionExpandedWithCornerPolicy(
                            LayoutSettingsSection.DETAIL_PAGE,
                            !detailPageExpanded
                        )
                    },
                    focusRequester = detailPageHeaderFocus,
                    onFocused = { focusedSection = LayoutSettingsSection.DETAIL_PAGE },
                    groupPosition = groupPositionFor(
                        LayoutSettingsSection.DETAIL_PAGE
                    ),
                    animateTopFlatten = animateTopFlattenFor(
                        LayoutSettingsSection.DETAIL_PAGE
                    ),
                    animateBottomFlatten = animateBottomFlattenFor(
                        LayoutSettingsSection.DETAIL_PAGE
                    ),
                    deferBottomCorner =
                        LayoutSettingsSection.DETAIL_PAGE in
                            deferredBottomCornerSections
                ) {
                    CompactToggleRow(
                        title = stringResource(R.string.layout_blur_unwatched),
                        subtitle = stringResource(R.string.layout_blur_unwatched_sub),
                        checked = uiState.blurUnwatchedEpisodes,
                        onToggle = {
                            viewModel.onEvent(
                                LayoutSettingsEvent.SetBlurUnwatchedEpisodes(!uiState.blurUnwatchedEpisodes)
                            )
                        },
                        onFocused = { focusedSection = LayoutSettingsSection.DETAIL_PAGE }
                    )

                    CompactToggleRow(
                        title = stringResource(R.string.layout_trailer_button),
                        subtitle = stringResource(R.string.layout_trailer_button_sub),
                        checked = uiState.detailPageTrailerButtonEnabled,
                        onToggle = {
                            viewModel.onEvent(
                                LayoutSettingsEvent.SetDetailPageTrailerButtonEnabled(
                                    !uiState.detailPageTrailerButtonEnabled
                                )
                            )
                        },
                        onFocused = { focusedSection = LayoutSettingsSection.DETAIL_PAGE }
                    )

                    CompactToggleRow(
                        title = stringResource(R.string.layout_prefer_external_meta),
                        subtitle = stringResource(R.string.layout_prefer_external_meta_sub),
                        checked = uiState.preferExternalMetaAddonDetail,
                        onToggle = {
                            viewModel.onEvent(
                                LayoutSettingsEvent.SetPreferExternalMetaAddonDetail(
                                    !uiState.preferExternalMetaAddonDetail
                                )
                            )
                        },
                        onFocused = { focusedSection = LayoutSettingsSection.DETAIL_PAGE }
                    )
                }
            }

            item(key = "focused_poster_section") {
                CollapsibleSectionCard(
                    title = stringResource(R.string.layout_section_focused),
                    description = stringResource(R.string.layout_section_focused_desc),
                    expanded = focusedPosterExpanded,
                    onToggle = {
                        setSectionExpandedWithCornerPolicy(
                            LayoutSettingsSection.FOCUSED_POSTER,
                            !focusedPosterExpanded
                        )
                    },
                    focusRequester = focusedPosterHeaderFocus,
                    onFocused = { focusedSection = LayoutSettingsSection.FOCUSED_POSTER },
                    groupPosition = groupPositionFor(
                        LayoutSettingsSection.FOCUSED_POSTER
                    ),
                    animateTopFlatten = animateTopFlattenFor(
                        LayoutSettingsSection.FOCUSED_POSTER
                    ),
                    animateBottomFlatten = animateBottomFlattenFor(
                        LayoutSettingsSection.FOCUSED_POSTER
                    ),
                    deferBottomCorner =
                        LayoutSettingsSection.FOCUSED_POSTER in
                            deferredBottomCornerSections
                ) {
                    val isModernLandscape = uiState.modernLandscapePostersEnabled
                    val showAutoplayRow = uiState.focusedPosterBackdropExpandEnabled || isModernLandscape

                    if (!isModernLandscape) {
                        CompactToggleRow(
                            title = stringResource(R.string.layout_expand_poster),
                            subtitle = stringResource(R.string.layout_expand_poster_sub),
                            checked = uiState.focusedPosterBackdropExpandEnabled,
                            onToggle = {
                                viewModel.onEvent(
                                    LayoutSettingsEvent.SetFocusedPosterBackdropExpandEnabled(
                                        !uiState.focusedPosterBackdropExpandEnabled
                                    )
                                )
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.FOCUSED_POSTER }
                        )
                    }

                    if (!isModernLandscape && uiState.focusedPosterBackdropExpandEnabled) {
                        SliderSettingsItem(
                            icon = Icons.Default.Timer,
                            title = stringResource(R.string.layout_expand_delay),
                            subtitle = stringResource(R.string.layout_expand_delay_sub),
                            value = uiState.focusedPosterBackdropExpandDelaySeconds,
                            valueText = "${uiState.focusedPosterBackdropExpandDelaySeconds}s",
                            minValue = 0,
                            maxValue = 10,
                            step = 1,
                            onValueChange = { seconds ->
                                viewModel.onEvent(
                                    LayoutSettingsEvent.SetFocusedPosterBackdropExpandDelaySeconds(seconds)
                                )
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.FOCUSED_POSTER }
                        )
                    }

                    if (showAutoplayRow) {
                        CompactToggleRow(
                            title = stringResource(R.string.layout_autoplay_trailer),
                            subtitle = stringResource(R.string.layout_autoplay_trailer_sub),
                            checked = uiState.focusedPosterBackdropTrailerEnabled,
                            onToggle = {
                                viewModel.onEvent(
                                    LayoutSettingsEvent.SetFocusedPosterBackdropTrailerEnabled(
                                        !uiState.focusedPosterBackdropTrailerEnabled
                                    )
                                )
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.FOCUSED_POSTER }
                        )
                    }

                    if (
                        showAutoplayRow &&
                        uiState.focusedPosterBackdropTrailerEnabled &&
                        uiState.focusedPosterBackdropTrailerPlaybackTarget ==
                            FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD
                    ) {
                        CompactToggleRow(
                            title = stringResource(
                                R.string.layout_expand_landscape_posters
                            ),
                            subtitle = stringResource(
                                R.string.layout_expand_landscape_posters_sub
                            ),
                            checked = uiState.expandLandscapePostersEnabled,
                            onToggle = {
                                viewModel.onEvent(
                                    LayoutSettingsEvent.SetExpandLandscapePostersEnabled(
                                        !uiState.expandLandscapePostersEnabled
                                    )
                                )
                            },
                            onFocused = {
                                focusedSection =
                                    LayoutSettingsSection.FOCUSED_POSTER
                            }
                        )
                    }

                    if (showAutoplayRow && uiState.focusedPosterBackdropTrailerEnabled) {
                        CompactToggleRow(
                            title = stringResource(R.string.layout_trailer_muted),
                            subtitle = stringResource(R.string.layout_trailer_muted_sub_preview),
                            checked = uiState.focusedPosterBackdropTrailerMuted,
                            onToggle = {
                                viewModel.onEvent(
                                    LayoutSettingsEvent.SetFocusedPosterBackdropTrailerMuted(
                                        !uiState.focusedPosterBackdropTrailerMuted
                                    )
                                )
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.FOCUSED_POSTER }
                        )
                    }

                    if (
                        showAutoplayRow &&
                        uiState.focusedPosterBackdropTrailerEnabled
                    ) {
                        ModernTrailerPlaybackTargetRow(
                            selectedTarget = uiState.focusedPosterBackdropTrailerPlaybackTarget,
                            onTargetSelected = { target ->
                                viewModel.onEvent(
                                    LayoutSettingsEvent.SetFocusedPosterBackdropTrailerPlaybackTarget(target)
                                )
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.FOCUSED_POSTER }
                        )
                    }

                    // Allow Letterboxing — Modern UI only, shown when trailer plays in Hero Media
                    if (
                        showAutoplayRow &&
                        uiState.focusedPosterBackdropTrailerEnabled &&
                        uiState.focusedPosterBackdropTrailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.HERO_MEDIA
                    ) {
                        CompactToggleRow(
                            title = stringResource(R.string.layout_trailer_allow_letterboxing),
                            subtitle = stringResource(R.string.layout_trailer_allow_letterboxing_sub),
                            checked = uiState.heroTrailerAllowLetterboxing,
                            onToggle = {
                                viewModel.onEvent(
                                    LayoutSettingsEvent.SetHeroTrailerAllowLetterboxing(
                                        !uiState.heroTrailerAllowLetterboxing
                                    )
                                )
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.FOCUSED_POSTER }
                        )
                    }

                    // Hide Backdrop on Trailer — both UIs, shown when trailer plays in expanded card
                    if (
                        showAutoplayRow &&
                        uiState.focusedPosterBackdropTrailerEnabled &&
                        (
                            uiState.focusedPosterBackdropTrailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD
                        )
                    ) {
                        CompactToggleRow(
                            title = stringResource(R.string.layout_no_backdrop_image),
                            subtitle = stringResource(R.string.layout_no_backdrop_image_sub),
                            checked = uiState.focusedPosterNoBackdropImage,
                            onToggle = {
                                viewModel.onEvent(
                                    LayoutSettingsEvent.SetFocusedPosterNoBackdropImage(
                                        !uiState.focusedPosterNoBackdropImage
                                    )
                                )
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.FOCUSED_POSTER }
                        )
                    }
                }
            }

            item(key = "poster_style_section") {
                CollapsibleSectionCard(
                    title = stringResource(R.string.layout_section_card_style),
                    description = stringResource(R.string.layout_section_card_style_desc),
                    expanded = posterCardStyleExpanded,
                    onToggle = {
                        if (!posterCardStyleExpanded) {
                            setSectionExpandedWithCornerPolicy(
                                LayoutSettingsSection.POSTER_CARD_STYLE,
                                true
                            )
                        } else {
                            val onlyPosterCardStyleExpanded =
                                !homeContentExpanded &&
                                    !continueWatchingExpanded &&
                                    !detailPageExpanded &&
                                    !focusedPosterExpanded
                            val listHasBeenScrolled =
                                layoutListState.firstVisibleItemIndex > 0 ||
                                    layoutListState.firstVisibleItemScrollOffset > 0

                            if (
                                onlyPosterCardStyleExpanded &&
                                listHasBeenScrolled
                            ) {
                                val section =
                                    LayoutSettingsSection.POSTER_CARD_STYLE
                                val before = expandedSections
                                val after = before - section
                                val posterOffsetBefore =
                                    layoutListState.layoutInfo
                                        .visibleItemsInfo
                                        .firstOrNull {
                                            it.key ==
                                                "poster_style_section"
                                        }
                                        ?.offset
                                        ?.toFloat()

                                prepareSectionCollapseCorners(
                                    section = section,
                                    before = before,
                                    after = after
                                )

                                layoutAnimationScope.launch {
                                    posterListSettleOffsetY.stop()
                                    posterListSettleOffsetY.snapTo(0f)

                                    /*
                                     * Put LazyColumn into the legal final
                                     * scroll state BEFORE its content becomes
                                     * short enough to clamp. Then counter that
                                     * internal jump with an equal GPU
                                     * translation. Visually nothing has moved
                                     * yet, but there is no remaining scroll
                                     * offset for LazyColumn to snap later.
                                     */
                                    layoutListState.scrollToItem(
                                        index = 0,
                                        scrollOffset = 0
                                    )

                                    val posterOffsetAfter =
                                        layoutListState.layoutInfo
                                            .visibleItemsInfo
                                            .firstOrNull {
                                                it.key ==
                                                    "poster_style_section"
                                            }
                                            ?.offset
                                            ?.toFloat()

                                    if (
                                        posterOffsetBefore != null &&
                                        posterOffsetAfter != null
                                    ) {
                                        posterListSettleOffsetY.snapTo(
                                            posterOffsetBefore -
                                                posterOffsetAfter
                                        )
                                    }

                                    applySectionExpandedState(
                                        section,
                                        false
                                    )
                                    releaseDeferredBottomCornerAfterCollapse(
                                        section,
                                        POSTER_CARD_STYLE_COLLAPSE_MILLIS
                                    )

                                    /*
                                     * The remaining parent list now glides
                                     * into its true final position slowly,
                                     * independent of the child-row shrink and
                                     * independent of LazyColumn's max-scroll
                                     * clamp.
                                     */
                                    posterListSettleOffsetY.animateTo(
                                        targetValue = 0f,
                                        animationSpec = tween(
                                            durationMillis =
                                                POSTER_CARD_STYLE_LIST_SETTLE_MILLIS,
                                            easing = FastOutSlowInEasing
                                        )
                                    )
                                }
                            } else {
                                setSectionExpandedWithCornerPolicy(
                                    LayoutSettingsSection.POSTER_CARD_STYLE,
                                    false
                                )
                            }
                        }
                    },
                    focusRequester = posterCardStyleHeaderFocus,
                    onFocused = { focusedSection = LayoutSettingsSection.POSTER_CARD_STYLE },
                    groupPosition = groupPositionFor(
                        LayoutSettingsSection.POSTER_CARD_STYLE
                    ),
                    animateTopFlatten = animateTopFlattenFor(
                        LayoutSettingsSection.POSTER_CARD_STYLE
                    ),
                    animateBottomFlatten = animateBottomFlattenFor(
                        LayoutSettingsSection.POSTER_CARD_STYLE
                    ),
                    deferBottomCorner =
                        LayoutSettingsSection.POSTER_CARD_STYLE in
                            deferredBottomCornerSections,
                    collapseDurationMillis =
                        POSTER_CARD_STYLE_COLLAPSE_MILLIS
                ) {
                    PosterCardStyleControls(
                        widthDp = uiState.posterCardWidthDp,
                        cornerRadiusDp = uiState.posterCardCornerRadiusDp,
                        onWidthSelected = { width ->
                            viewModel.onEvent(LayoutSettingsEvent.SetPosterCardWidth(width))
                        },
                        onCornerRadiusSelected = { radius ->
                            viewModel.onEvent(LayoutSettingsEvent.SetPosterCardCornerRadius(radius))
                        },
                        onReset = {
                            viewModel.onEvent(LayoutSettingsEvent.ResetPosterCardStyle)
                        },
                        onFocused = { focusedSection = LayoutSettingsSection.POSTER_CARD_STYLE }
                    )

                    CardDepthStyleControls(
                        style = uiState.cardDepthStyle,
                        onEnabledChange = { enabled ->
                            viewModel.onEvent(
                                LayoutSettingsEvent
                                    .SetCardDepthEnabled(enabled)
                            )
                        },
                        onEdgeStrengthChange = { strength ->
                            viewModel.onEvent(
                                LayoutSettingsEvent
                                    .SetCardDepthEdgeStrength(
                                        strength
                                    )
                            )
                        },
                        onSheenStrengthChange = { strength ->
                            viewModel.onEvent(
                                LayoutSettingsEvent
                                    .SetCardDepthSheenStrength(
                                        strength
                                    )
                            )
                        },
                        onEdgeCoverageChange = { coverage ->
                            viewModel.onEvent(
                                LayoutSettingsEvent
                                    .SetCardDepthEdgeCoverage(
                                        coverage
                                    )
                            )
                        },
                        onFineTune = {
                            showCardDepthFineTuneDialog = true
                        },
                        onReset = {
                            viewModel.onEvent(
                                LayoutSettingsEvent
                                    .ResetCardDepthStyle
                            )
                        },
                        onFocused = {
                            focusedSection =
                                LayoutSettingsSection
                                    .POSTER_CARD_STYLE
                        }
                    )

                    if (showCardDepthFineTuneDialog) {
                        CardDepthFineTuneDialog(
                            style = uiState.cardDepthStyle,
                            onEdgeStrengthChange = { strength ->
                                viewModel.onEvent(
                                    LayoutSettingsEvent
                                        .SetCardDepthEdgeStrength(
                                            strength
                                        )
                                )
                            },
                            onSheenStrengthChange = { strength ->
                                viewModel.onEvent(
                                    LayoutSettingsEvent
                                        .SetCardDepthSheenStrength(
                                            strength
                                        )
                                )
                            },
                            onEdgeCoverageChange = { coverage ->
                                viewModel.onEvent(
                                    LayoutSettingsEvent
                                        .SetCardDepthEdgeCoverage(
                                            coverage
                                        )
                                )
                            },
                            onReset = {
                                viewModel.onEvent(
                                    LayoutSettingsEvent
                                        .SetCardDepthEdgeStrength(
                                            DEFAULT_CARD_DEPTH_EDGE_STRENGTH
                                        )
                                )
                                viewModel.onEvent(
                                    LayoutSettingsEvent
                                        .SetCardDepthSheenStrength(
                                            DEFAULT_CARD_DEPTH_SHEEN_STRENGTH
                                        )
                                )
                                viewModel.onEvent(
                                    LayoutSettingsEvent
                                        .SetCardDepthEdgeCoverage(
                                            DEFAULT_CARD_DEPTH_EDGE_COVERAGE
                                        )
                                )
                            },
                            onDismiss = {
                                showCardDepthFineTuneDialog = false
                            }
                        )
                    }

                }
            }
        }
        SettingsVerticalScrollIndicators(state = layoutListState)
        }
    }
}

@Composable
private fun CollapsibleSectionCard(
    title: String,
    description: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    groupPosition: SettingsGroupPosition,
    animateTopFlatten: Boolean = false,
    animateBottomFlatten: Boolean = false,
    deferBottomCorner: Boolean = false,
    collapseDurationMillis: Int = DEFAULT_SECTION_COLLAPSE_MILLIS,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(
            if (expanded) SettingsRowGap else 0.dp
        )
    ) {
        SettingsActionRow(
            title = title,
            subtitle = description,
            value = if (expanded) stringResource(R.string.layout_open) else stringResource(R.string.layout_closed),
            onClick = onToggle,
            trailingIcon = if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
            modifier = Modifier.focusRequester(focusRequester),
            onFocused = onFocused,
            showDivider = false,
            groupPosition = when {
                expanded -> SettingsGroupPosition.TOP
                deferBottomCorner -> when (groupPosition) {
                    SettingsGroupPosition.SINGLE ->
                        SettingsGroupPosition.TOP
                    SettingsGroupPosition.BOTTOM ->
                        SettingsGroupPosition.MIDDLE
                    SettingsGroupPosition.TOP ->
                        SettingsGroupPosition.TOP
                    SettingsGroupPosition.MIDDLE ->
                        SettingsGroupPosition.MIDDLE
                }
                else -> groupPosition
            },
            animateTopFlatten = animateTopFlatten,
            animateBottomFlatten = animateBottomFlatten
        )

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = tween(
                    durationMillis = 240,
                    easing = FastOutSlowInEasing
                ),
                expandFrom = Alignment.Top
            ),
            exit = shrinkVertically(
                animationSpec = tween(
                    durationMillis = collapseDurationMillis,
                    easing = FastOutSlowInEasing
                ),
                shrinkTowards = Alignment.Top
            )
        ) {
            SettingsExpandedSectionSurface {
                content()
            }
        }
    }
}

@Composable
private fun CompactToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onToggle: () -> Unit,
    onFocused: () -> Unit
) {
    SettingsToggleRow(
        title = title,
        subtitle = subtitle,
        checked = checked,
        onToggle = onToggle,
        onFocused = onFocused
    )
}

@Composable
private fun ContinueWatchingStyleCard(
    style: ContinueWatchingCardStyle,
    isSelected: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)

    Card(
        onClick = onClick,
        modifier = modifier.onFocusChanged { state ->
            val nowFocused = state.isFocused
            if (isFocused != nowFocused) {
                isFocused = nowFocused
                if (nowFocused) onFocused()
            }
        },
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = SettingsRightSurfaceFocusedColor
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border.None
        ),
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(
            focusedScale = 1f,
            pressedScale = 1f
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp)
            ) {
                when (style) {
                    ContinueWatchingCardStyle.CARD ->
                        CardCwStylePreview(Modifier.fillMaxSize())
                    ContinueWatchingCardStyle.WIDE ->
                        WideCwStylePreview(Modifier.fillMaxSize())
                    ContinueWatchingCardStyle.POSTER ->
                        PosterCwStylePreview(Modifier.fillMaxSize())
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.cd_selected),
                        tint = NuvioColors.Secondary,
                        modifier = Modifier
                            .size(16.dp)
                            .padding(end = 6.dp)
                    )
                }

                Text(
                    text = when (style) {
                        ContinueWatchingCardStyle.CARD ->
                            stringResource(R.string.layout_cw_card_style_card)
                        ContinueWatchingCardStyle.WIDE ->
                            stringResource(R.string.layout_cw_card_style_wide)
                        ContinueWatchingCardStyle.POSTER ->
                            stringResource(R.string.layout_cw_card_style_poster)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color =
                        if (isSelected || isFocused) {
                            NuvioColors.TextPrimary
                        } else {
                            NuvioColors.TextSecondary
                        }
                )
            }
        }
    }
}

@Composable
private fun ModernTrailerPlaybackTargetRow(
    selectedTarget: FocusedPosterTrailerPlaybackTarget,
    onTargetSelected: (FocusedPosterTrailerPlaybackTarget) -> Unit,
    onFocused: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SettingsRightSurfaceColor)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = stringResource(R.string.layout_trailer_location),
            style = MaterialTheme.typography.labelLarge,
            color = NuvioColors.TextSecondary
        )
        Text(
            text = stringResource(R.string.layout_trailer_location_sub),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioColors.TextTertiary
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingsChoiceChip(
                label = stringResource(R.string.layout_trailer_expanded_card),
                selected = selectedTarget == FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD,
                onClick = {
                    onTargetSelected(FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD)
                },
                onFocused = onFocused
            )
            SettingsChoiceChip(
                label = stringResource(R.string.layout_trailer_hero_media),
                selected = selectedTarget == FocusedPosterTrailerPlaybackTarget.HERO_MEDIA,
                onClick = {
                    onTargetSelected(FocusedPosterTrailerPlaybackTarget.HERO_MEDIA)
                },
                onFocused = onFocused
            )
        }
    }
}

@Composable
private fun LayoutCard(
    layout: HomeLayout,
    isSelected: Boolean,
    showLivePreview: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = modifier.onFocusChanged { state ->
            val nowFocused = state.isFocused
            if (isFocused != nowFocused) {
                isFocused = nowFocused
                if (nowFocused) onFocused()
            }
        },
        colors = CardDefaults.colors(
            containerColor = if (isSelected) SettingsGlassControlSelectedColor else SettingsGlassRowColor,
            focusedContainerColor = SettingsGlassRowFocusedColor
        ),
        border = CardDefaults.border(
            border = if (isSelected) Border(
                border = BorderStroke(1.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(SettingsSecondaryCardRadius)
            ) else Border.None,
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(SettingsSecondaryCardRadius)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(SettingsSecondaryCardRadius)),
        scale = CardDefaults.scale(focusedScale = 1.025f, pressedScale = 0.99f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp)
            ) {
                if (showLivePreview) {
                    when (layout) {
                        HomeLayout.CLASSIC -> ClassicLayoutPreview(modifier = Modifier.fillMaxWidth())
                        HomeLayout.GRID -> GridLayoutPreview(modifier = Modifier.fillMaxWidth())
                        HomeLayout.MODERN -> ModernLayoutPreview(modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    LayoutPreviewPlaceholder()
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.cd_selected),
                        tint = NuvioColors.FocusRing,
                        modifier = Modifier
                            .size(16.dp)
                            .padding(end = 6.dp)
                    )
                }
                Text(
                    text = when (layout) {
                        HomeLayout.CLASSIC -> stringResource(R.string.layout_classic)
                        HomeLayout.GRID -> stringResource(R.string.layout_grid)
                        HomeLayout.MODERN -> stringResource(R.string.layout_modern)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected || isFocused) NuvioColors.TextPrimary else NuvioColors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun LayoutPreviewPlaceholder() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp)
            .background(
                color = SettingsGlassRowColor,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(10.dp)
                .background(SettingsGlassBorderColor, RoundedCornerShape(999.dp))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(SettingsGlassGroupColor, RoundedCornerShape(10.dp))
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(10.dp)
                        .background(SettingsGlassBorderColor, RoundedCornerShape(999.dp))
                )
            }
        }
    }
}

@Composable
private fun CatalogChip(
    catalogInfo: CatalogInfo,
    isSelected: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit
) {
    SettingsChoiceChip(
        label = catalogInfo.name,
        selected = isSelected,
        onClick = onClick,
        onFocused = onFocused
    )
}

@Composable
private fun LayoutControlGroup(
    groupPosition: SettingsGroupPosition = SettingsGroupPosition.SINGLE,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = animatedSettingsGroupShape(groupPosition)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape),
        verticalArrangement = Arrangement.spacedBy(SettingsRowGap),
        content = content
    )
}

@Composable
private fun CardDepthStyleControls(
    style: CardDepthStyle,
    onEnabledChange: (Boolean) -> Unit,
    onEdgeStrengthChange: (Int) -> Unit,
    onSheenStrengthChange: (Int) -> Unit,
    onEdgeCoverageChange: (Int) -> Unit,
    onFineTune: () -> Unit,
    onReset: () -> Unit,
    onFocused: () -> Unit
) {    val edgeOptions = listOf(
        PresetOption(
            stringResource(
                R.string.settings_card_depth_edge_subtle
            ),
            28
        ),
        PresetOption(
            stringResource(
                R.string.settings_card_depth_edge_balanced
            ),
            42
        ),
        PresetOption(
            stringResource(
                R.string.settings_card_depth_edge_bold
            ),
            56
        )
    )

    val sheenOptions = listOf(
        PresetOption(
            stringResource(
                R.string.settings_card_depth_sheen_off
            ),
            0
        ),
        PresetOption(
            stringResource(
                R.string.settings_card_depth_sheen_soft
            ),
            10
        ),
        PresetOption(
            stringResource(
                R.string.settings_card_depth_sheen_bright
            ),
            16
        )
    )

    val coverageOptions = listOf(
        PresetOption(
            stringResource(
                R.string.settings_card_depth_coverage_top
            ),
            0
        ),
        PresetOption(
            stringResource(
                R.string.settings_card_depth_coverage_half
            ),
            50
        ),
        PresetOption(
            stringResource(
                R.string.settings_card_depth_coverage_full
            ),
            100
        )
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SettingsRowGap)
    ) {
        LayoutControlGroup {
            CompactToggleRow(
                title = stringResource(
                    R.string.settings_card_depth_enabled
                ),
                subtitle = stringResource(
                    R.string.settings_card_depth_description
                ),
                checked = style.enabled,
                onToggle = {
                    onEnabledChange(!style.enabled)
                },
                onFocused = onFocused
            )

            if (style.enabled) {
                OptionRow(
                    title = stringResource(
                        R.string.settings_card_depth_edge
                    ),
                    selectedValue = style.edgeStrength,
                    options = edgeOptions,
                    onSelected = onEdgeStrengthChange,
                    onFocused = onFocused
                )

                OptionRow(
                    title = stringResource(
                        R.string.settings_card_depth_sheen
                    ),
                    selectedValue = style.sheenStrength,
                    options = sheenOptions,
                    onSelected = onSheenStrengthChange,
                    onFocused = onFocused
                )

                OptionRow(
                    title = stringResource(
                        R.string.settings_card_depth_edge_coverage
                    ),
                    selectedValue = style.edgeCoverage,
                    options = coverageOptions,
                    onSelected = onEdgeCoverageChange,
                    onFocused = onFocused
                )

                SettingsActionRow(
                    title = stringResource(
                        R.string.settings_card_depth_fine_tune
                    ),
                    subtitle = stringResource(
                        R.string.settings_card_depth_fine_tune_hint_tv
                    ),
                    onClick = onFineTune,
                    trailingIcon = Icons.Default.Tune,
                    onFocused = onFocused
                )
            }

            SettingsResetButton(
                onClick = onReset,
                modifier = Modifier.fillMaxWidth(),
                onFocused = onFocused,
                groupPosition = SettingsGroupPosition.BOTTOM
            )
        }
    }
}

@Composable
private fun CardDepthFineTuneDialog(
    style: CardDepthStyle,
    onEdgeStrengthChange: (Int) -> Unit,
    onSheenStrengthChange: (Int) -> Unit,
    onEdgeCoverageChange: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val initialFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        initialFocusRequester.requestFocus()
    }

    NuvioDialog(
        glass = true,
        enhancedGlass = true,
        compact = true,
        onDismiss = onDismiss,
        title = stringResource(
            R.string.settings_card_depth_fine_tune_title
        ),
        subtitle = stringResource(
            R.string.settings_card_depth_fine_tune_hint_tv
        ),
        width = 580.dp,
        usePlatformDefaultWidth = false
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.spacedBy(16.dp)
        ) {
            CardDepthPreview(
                style = style,
                modifier =
                    Modifier
                        .width(220.dp)
                        .aspectRatio(2f / 3f)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {
                SliderSettingsItem(
                    icon = null,
                    title = stringResource(
                        R.string.settings_card_depth_edge_value
                    ),
                    value =
                        style.edgeStrength
                            .coerceAtMost(70),
                    valueText =
                        "${style.edgeStrength}%",
                    minValue = 0,
                    maxValue = 70,
                    step = 1,
                    onValueChange = onEdgeStrengthChange,
                    modifier = Modifier.focusRequester(initialFocusRequester),
                    useDialogGlass = true
                )

                SliderSettingsItem(
                    icon = null,
                    title = stringResource(
                        R.string.settings_card_depth_sheen_value
                    ),
                    value =
                        style.sheenStrength
                            .coerceAtMost(25),
                    valueText =
                        "${style.sheenStrength}%",
                    minValue = 0,
                    maxValue = 25,
                    step = 1,
                    onValueChange =
                        onSheenStrengthChange,
                    useDialogGlass = true
                )

                SliderSettingsItem(
                    icon = null,
                    title = stringResource(
                        R.string.settings_card_depth_coverage_value
                    ),
                    value = style.edgeCoverage,
                    valueText =
                        "${style.edgeCoverage}%",
                    minValue = 0,
                    maxValue = 100,
                    step = 1,
                    onValueChange =
                        onEdgeCoverageChange,
                    useDialogGlass = true
                )

                SettingsResetButton(
                    onClick = onReset,
                    modifier = Modifier.fillMaxWidth(),
                    useDialogGlass = true
                )
            }
        }
    }
}

@Composable
private fun CardDepthPreview(
    style: CardDepthStyle,
    modifier: Modifier = Modifier
) {
    val shape =
        RoundedCornerShape(12.dp)

    Box(
        modifier =
            modifier
                .clip(shape)
                .background(
                    Brush.linearGradient(
                        colors =
                            listOf(
                                Color(0xFF33415C),
                                Color(0xFF232D42),
                                Color(0xFF141A28)
                            )
                    )
                )
                .cardDepthVisual(
                    shape = shape,
                    edgeStrength =
                        style.edgeStrength.toFloat(),
                    sheenStrength =
                        style.sheenStrength.toFloat(),
                    edgeCoverage =
                        style.edgeCoverage.toFloat()
                )
    )
}

@Composable
private fun PosterCardStyleControls(
    widthDp: Int,
    cornerRadiusDp: Int,
    onWidthSelected: (Int) -> Unit,
    onCornerRadiusSelected: (Int) -> Unit,
    onReset: () -> Unit,
    onFocused: () -> Unit
) {    val widthOptions = listOf(
        PresetOption(stringResource(R.string.layout_preset_compact), 104),
        PresetOption(stringResource(R.string.layout_preset_dense), 112),
        PresetOption(stringResource(R.string.layout_preset_standard), 120),
        PresetOption(stringResource(R.string.layout_preset_balanced), 126),
        PresetOption(stringResource(R.string.layout_preset_comfort), 134),
        PresetOption(stringResource(R.string.layout_preset_large), 140)
    )
    val radiusOptions = listOf(
        PresetOption(stringResource(R.string.layout_preset_sharp), 0),
        PresetOption(stringResource(R.string.layout_preset_subtle), 4),
        PresetOption(stringResource(R.string.layout_preset_classic), 8),
        PresetOption(stringResource(R.string.layout_preset_rounded), 12),
        PresetOption(stringResource(R.string.layout_preset_pill), 16)
    )


    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SettingsRowGap)
    ) {
        LayoutControlGroup(
            groupPosition = SettingsGroupPosition.BOTTOM
        ) {
            OptionRow(
                title = stringResource(R.string.layout_card_width),
                selectedValue = widthDp,
                options = widthOptions,
                onSelected = onWidthSelected,
                onFocused = onFocused
            )
            OptionRow(
                title = stringResource(R.string.layout_card_radius),
                selectedValue = cornerRadiusDp,
                options = radiusOptions,
                onSelected = onCornerRadiusSelected,
                onFocused = onFocused
            )
            SettingsResetButton(
                onClick = onReset,
                modifier = Modifier.fillMaxWidth(),
                onFocused = onFocused,
                groupPosition = SettingsGroupPosition.BOTTOM
            )
        }
    }
}

@Composable
private fun OptionRow(
    title: String,
    selectedValue: Int,
    options: List<PresetOption>,
    onSelected: (Int) -> Unit,
    onFocused: () -> Unit
) {
    val selectedLabel = options.firstOrNull { it.value == selectedValue }?.label ?: stringResource(R.string.layout_custom)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SettingsRightSurfaceColor)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "$title ($selectedLabel)",
            style = MaterialTheme.typography.labelLarge,
            color = NuvioColors.TextSecondary
        )

        LazyRow(
            contentPadding = PaddingValues(end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = options,
                key = { it.value }
            ) { option ->
                ValueChip(
                    label = option.label,
                    isSelected = option.value == selectedValue,
                    onClick = { onSelected(option.value) },
                    onFocused = onFocused
                )
            }
        }
    }
}

@Composable
private fun ValueChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit
) {
    SettingsChoiceChip(
        label = label,
        selected = isSelected,
        onClick = onClick,
        onFocused = onFocused
    )
}

private data class PresetOption(
    val label: String,
    val value: Int
)
