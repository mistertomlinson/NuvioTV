@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class SeasonalSettingsSection {
    SNOWFALL,
    PERFORMANCE_GUARDRAILS,
    EXPERIMENTAL
}

private const val SEASONAL_SECTION_ANIMATION_MS = 240
private const val SEASONAL_BOUNDARY_RETURN_MS = 260L
private val ParticleCounts = listOf(30, 45, 60, 80, 100, 120)
private val FpsValues = listOf(15, 24, 30, 60)
private val DepthValues = listOf(1, 2, 3)

@Composable
internal fun SeasonalEffectsSettingsContent(
    viewModel: SeasonalEffectsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val firstFocusRequester = initialFocusRequester ?: remember { FocusRequester() }

    var snowfallExpanded by rememberSaveable { mutableStateOf(false) }
    var guardrailsExpanded by rememberSaveable { mutableStateOf(false) }
    var experimentalExpanded by rememberSaveable { mutableStateOf(false) }

    val animationScope = rememberCoroutineScope()
    var animatedFlattenBoundaries by remember {
        mutableStateOf<Set<Int>>(emptySet())
    }
    var deferredBottomCornerSections by remember {
        mutableStateOf<Set<SeasonalSettingsSection>>(emptySet())
    }

    val visibleSections = listOf(
        SeasonalSettingsSection.SNOWFALL,
        SeasonalSettingsSection.PERFORMANCE_GUARDRAILS,
        SeasonalSettingsSection.EXPERIMENTAL
    )
    val expandedSections = buildSet {
        if (snowfallExpanded) add(SeasonalSettingsSection.SNOWFALL)
        if (guardrailsExpanded) add(SeasonalSettingsSection.PERFORMANCE_GUARDRAILS)
        if (experimentalExpanded) add(SeasonalSettingsSection.EXPERIMENTAL)
    }

    fun groupPositionFor(
        section: SeasonalSettingsSection,
        expanded: Set<SeasonalSettingsSection> = expandedSections
    ): SettingsGroupPosition {
        val index = visibleSections.indexOf(section)
        if (index < 0) return SettingsGroupPosition.SINGLE
        if (section in expanded) return SettingsGroupPosition.TOP

        val startsGroup = index == 0 || visibleSections[index - 1] in expanded
        val endsGroup =
            index == visibleSections.lastIndex || visibleSections[index + 1] in expanded
        return when {
            startsGroup && endsGroup -> SettingsGroupPosition.SINGLE
            startsGroup -> SettingsGroupPosition.TOP
            endsGroup -> SettingsGroupPosition.BOTTOM
            else -> SettingsGroupPosition.MIDDLE
        }
    }

    fun headerTopRounded(
        section: SeasonalSettingsSection,
        expanded: Set<SeasonalSettingsSection>
    ): Boolean = when (groupPositionFor(section, expanded)) {
        SettingsGroupPosition.SINGLE,
        SettingsGroupPosition.TOP -> true
        SettingsGroupPosition.MIDDLE,
        SettingsGroupPosition.BOTTOM -> false
    }

    fun headerBottomRounded(
        section: SeasonalSettingsSection,
        expanded: Set<SeasonalSettingsSection>
    ): Boolean = when (groupPositionFor(section, expanded)) {
        SettingsGroupPosition.SINGLE,
        SettingsGroupPosition.BOTTOM -> true
        SettingsGroupPosition.TOP,
        SettingsGroupPosition.MIDDLE -> false
    }

    fun applySectionExpandedState(
        section: SeasonalSettingsSection,
        expanded: Boolean
    ) {
        when (section) {
            SeasonalSettingsSection.SNOWFALL -> snowfallExpanded = expanded
            SeasonalSettingsSection.PERFORMANCE_GUARDRAILS -> guardrailsExpanded = expanded
            SeasonalSettingsSection.EXPERIMENTAL -> experimentalExpanded = expanded
        }
    }

    fun beginBoundaryReturnAnimation(boundaries: Set<Int>) {
        animatedFlattenBoundaries = boundaries
        if (boundaries.isNotEmpty()) {
            animationScope.launch {
                delay(SEASONAL_BOUNDARY_RETURN_MS)
                if (animatedFlattenBoundaries == boundaries) {
                    animatedFlattenBoundaries = emptySet()
                }
            }
        }
    }

    fun immediateFlattenBoundariesForCollapse(
        section: SeasonalSettingsSection,
        before: Set<SeasonalSettingsSection>,
        after: Set<SeasonalSettingsSection>
    ): Set<Int> {
        val sectionIndex = visibleSections.indexOf(section)
        if (sectionIndex < 0) return emptySet()

        return listOf(sectionIndex - 1, sectionIndex)
            .filter { it >= 0 && it < visibleSections.lastIndex }
            .filter { boundaryIndex ->
                val upper = visibleSections[boundaryIndex]
                val lower = visibleSections[boundaryIndex + 1]
                val upperRoundedBefore = headerBottomRounded(upper, before)
                val lowerRoundedBefore = headerTopRounded(lower, before)
                val upperRoundedAfter = headerBottomRounded(upper, after)
                val lowerRoundedAfter = headerTopRounded(lower, after)
                val finalBoundaryIsFlat =
                    !upperRoundedAfter && !lowerRoundedAfter
                val roundedEdgeNeedsToFlatten =
                    (upperRoundedBefore && !upperRoundedAfter) ||
                        (lowerRoundedBefore && !lowerRoundedAfter)

                finalBoundaryIsFlat && roundedEdgeNeedsToFlatten
            }
            .toSet()
    }

    fun releaseDeferredBottomCornerAfterCollapse(section: SeasonalSettingsSection) {
        animationScope.launch {
            delay(SEASONAL_SECTION_ANIMATION_MS.toLong())
            if (section !in deferredBottomCornerSections) return@launch
            androidx.compose.runtime.withFrameNanos { }
            deferredBottomCornerSections = deferredBottomCornerSections - section
        }
    }

    fun setSectionExpandedWithCornerPolicy(
        section: SeasonalSettingsSection,
        expanded: Boolean
    ) {
        val before = expandedSections
        val after = if (expanded) before + section else before - section

        if (expanded) {
            deferredBottomCornerSections = deferredBottomCornerSections - section
            animatedFlattenBoundaries = emptySet()
            applySectionExpandedState(section, true)
            return
        }

        beginBoundaryReturnAnimation(
            immediateFlattenBoundariesForCollapse(
                section = section,
                before = before,
                after = after
            )
        )
        deferredBottomCornerSections = deferredBottomCornerSections + section
        applySectionExpandedState(section, false)
        releaseDeferredBottomCornerAfterCollapse(section)
    }

    fun animateTopFlattenFor(section: SeasonalSettingsSection): Boolean {
        val index = visibleSections.indexOf(section)
        return index > 0 && (index - 1) in animatedFlattenBoundaries
    }

    fun animateBottomFlattenFor(section: SeasonalSettingsSection): Boolean {
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
            title = "Seasonal effects",
            subtitle = "Tune Home snowfall and performance guardrails in one test build"
        )

        val listState = rememberLazyListState()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(SettingsRowGap)
            ) {
                item(key = "seasonal_master") {
                    SettingsToggleRow(
                        title = "Seasonal effects",
                        subtitle = "Master switch. Off removes the snow Canvas and frame clock from Home.",
                        checked = config.enabled,
                        onToggle = { viewModel.setEnabled(!config.enabled) },
                        modifier = Modifier.focusRequester(firstFocusRequester),
                        showDivider = false,
                        groupPosition = SettingsGroupPosition.SINGLE
                    )
                }

                item(key = "seasonal_snowfall") {
                    SeasonalLabSection(
                        title = "Snowfall",
                        description = "Startup, density, frame rate, motion, size and depth",
                        expanded = snowfallExpanded,
                        onToggle = {
                            setSectionExpandedWithCornerPolicy(
                                SeasonalSettingsSection.SNOWFALL,
                                !snowfallExpanded
                            )
                        },
                        groupPosition = groupPositionFor(SeasonalSettingsSection.SNOWFALL),
                        animateTopFlatten = animateTopFlattenFor(
                            SeasonalSettingsSection.SNOWFALL
                        ),
                        animateBottomFlatten = animateBottomFlattenFor(
                            SeasonalSettingsSection.SNOWFALL
                        ),
                        deferBottomCorner =
                            SeasonalSettingsSection.SNOWFALL in deferredBottomCornerSections
                    ) {
                        SettingsActionRow(
                            title = "Startup",
                            subtitle = "Pre-filled, fall in from top, or a 1.5 second soft fade",
                            value = pretty(config.startupMode.name),
                            onClick = {
                                viewModel.setStartupMode(nextEnum(config.startupMode))
                            }
                        )
                        SettingsActionRow(
                            title = "Flakes",
                            subtitle = "Total particle pool drawn while Home is idle",
                            value = config.particleCount.toString(),
                            onClick = {
                                viewModel.setParticleCount(
                                    nextValue(ParticleCounts, config.particleCount)
                                )
                            }
                        )
                        SettingsActionRow(
                            title = "Animation FPS",
                            subtitle = "Snow clock only; the Home UI keeps its normal frame rate",
                            value = "${config.targetFps} fps",
                            onClick = {
                                viewModel.setTargetFps(nextValue(FpsValues, config.targetFps))
                            }
                        )
                        SettingsActionRow(
                            title = "Fall speed",
                            subtitle = "Per-flake speed still varies inside this multiplier",
                            value = pretty(config.snowfallSpeed.name),
                            onClick = {
                                viewModel.setSnowfallSpeed(nextEnum(config.snowfallSpeed))
                            }
                        )
                        SettingsActionRow(
                            title = "Size profile",
                            subtitle = "Mixed gives the strongest near/far depth impression",
                            value = pretty(config.sizeProfile.name),
                            onClick = { viewModel.setSizeProfile(nextEnum(config.sizeProfile)) }
                        )
                        SettingsActionRow(
                            title = "Wind",
                            subtitle = "Directional horizontal movement across the screen",
                            value = pretty(config.wind.name),
                            onClick = { viewModel.setWind(nextEnum(config.wind)) }
                        )
                        SettingsActionRow(
                            title = "Sway",
                            subtitle = "Independent sinusoidal side-to-side motion per flake",
                            value = pretty(config.sway.name),
                            onClick = { viewModel.setSway(nextEnum(config.sway)) }
                        )
                        SettingsActionRow(
                            title = "Opacity",
                            subtitle = "Overall visibility without increasing particle count",
                            value = pretty(config.opacity.name),
                            onClick = { viewModel.setOpacity(nextEnum(config.opacity)) }
                        )
                        SettingsActionRow(
                            title = "Depth layers",
                            subtitle = "Changes apparent speed, brightness and flake size by depth",
                            value = config.depthLayers.toString(),
                            onClick = {
                                viewModel.setDepthLayers(nextValue(DepthValues, config.depthLayers))
                            }
                        )
                    }
                }

                item(key = "seasonal_guardrails") {
                    SeasonalLabSection(
                        title = "Performance guardrails",
                        description = "Mix and match protections while Home is moving",
                        expanded = guardrailsExpanded,
                        onToggle = {
                            setSectionExpandedWithCornerPolicy(
                                SeasonalSettingsSection.PERFORMANCE_GUARDRAILS,
                                !guardrailsExpanded
                            )
                        },
                        groupPosition = groupPositionFor(
                            SeasonalSettingsSection.PERFORMANCE_GUARDRAILS
                        ),
                        animateTopFlatten = animateTopFlattenFor(
                            SeasonalSettingsSection.PERFORMANCE_GUARDRAILS
                        ),
                        animateBottomFlatten = animateBottomFlattenFor(
                            SeasonalSettingsSection.PERFORMANCE_GUARDRAILS
                        ),
                        deferBottomCorner =
                            SeasonalSettingsSection.PERFORMANCE_GUARDRAILS in
                                deferredBottomCornerSections
                    ) {
                        SettingsToggleRow(
                            title = "Reduce density while scrolling",
                            subtitle = "Temporarily draws 55% of the selected flakes",
                            checked = config.reduceDensityWhileScrolling,
                            onToggle = {
                                viewModel.setReduceDensityWhileScrolling(
                                    !config.reduceDensityWhileScrolling
                                )
                            }
                        )
                        SettingsToggleRow(
                            title = "Reduce FPS while scrolling",
                            subtitle = "Caps the snow clock at 15 fps only while the Home list moves",
                            checked = config.reduceFpsWhileScrolling,
                            onToggle = {
                                viewModel.setReduceFpsWhileScrolling(
                                    !config.reduceFpsWhileScrolling
                                )
                            }
                        )
                        SettingsToggleRow(
                            title = "Freeze while scrolling",
                            subtitle = "Stops simulation updates during real vertical Home scrolling",
                            checked = config.freezeWhileScrolling,
                            onToggle = {
                                viewModel.setFreezeWhileScrolling(!config.freezeWhileScrolling)
                            }
                        )
                        SettingsToggleRow(
                            title = "Pause during hero trailer",
                            subtitle = "Leaves decoder/GPU headroom while Home video is playing",
                            checked = config.pauseDuringHeroTrailer,
                            onToggle = {
                                viewModel.setPauseDuringHeroTrailer(
                                    !config.pauseDuringHeroTrailer
                                )
                            }
                        )
                    }
                }

                item(key = "seasonal_experimental") {
                    SeasonalLabSection(
                        title = "Experimental / risk lab",
                        description = "Intentionally more expensive rendering for A/B testing",
                        expanded = experimentalExpanded,
                        onToggle = {
                            setSectionExpandedWithCornerPolicy(
                                SeasonalSettingsSection.EXPERIMENTAL,
                                !experimentalExpanded
                            )
                        },
                        groupPosition = groupPositionFor(
                            SeasonalSettingsSection.EXPERIMENTAL
                        ),
                        animateTopFlatten = animateTopFlattenFor(
                            SeasonalSettingsSection.EXPERIMENTAL
                        ),
                        animateBottomFlatten = animateBottomFlattenFor(
                            SeasonalSettingsSection.EXPERIMENTAL
                        ),
                        deferBottomCorner =
                            SeasonalSettingsSection.EXPERIMENTAL in deferredBottomCornerSections
                    ) {
                        SettingsActionRow(
                            title = "Snow blur",
                            subtitle = "Risky: whole-layer blur may force offscreen GPU rendering",
                            value = pretty(config.blur.name),
                            onClick = { viewModel.setBlur(nextEnum(config.blur)) }
                        )
                        SettingsActionRow(
                            title = "Reset recommended defaults",
                            subtitle = "Resets tuning but keeps the master Seasonal Effects switch unchanged",
                            value = null,
                            onClick = { viewModel.resetRecommended() }
                        )
                    }
                }
            }
            SettingsVerticalScrollIndicators(state = listState)
        }
    }
}

@Composable
private fun SeasonalLabSection(
    title: String,
    description: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    groupPosition: SettingsGroupPosition,
    animateTopFlatten: Boolean = false,
    animateBottomFlatten: Boolean = false,
    deferBottomCorner: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(
            if (expanded || deferBottomCorner) SettingsRowGap else 0.dp
        )
    ) {
        SettingsActionRow(
            title = title,
            subtitle = description,
            value = if (expanded) "Open" else "Closed",
            onClick = onToggle,
            trailingIcon = if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
            showDivider = false,
            groupPosition = when {
                expanded -> SettingsGroupPosition.TOP
                deferBottomCorner -> when (groupPosition) {
                    SettingsGroupPosition.SINGLE -> SettingsGroupPosition.TOP
                    SettingsGroupPosition.BOTTOM -> SettingsGroupPosition.MIDDLE
                    SettingsGroupPosition.TOP -> SettingsGroupPosition.TOP
                    SettingsGroupPosition.MIDDLE -> SettingsGroupPosition.MIDDLE
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
                    durationMillis = SEASONAL_SECTION_ANIMATION_MS,
                    easing = FastOutSlowInEasing
                ),
                expandFrom = Alignment.Top
            ),
            exit = shrinkVertically(
                animationSpec = tween(
                    durationMillis = SEASONAL_SECTION_ANIMATION_MS,
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

private fun <T> nextValue(values: List<T>, current: T): T {
    val index = values.indexOf(current)
    return values[(if (index < 0) 0 else index + 1) % values.size]
}

private inline fun <reified T : Enum<T>> nextEnum(current: T): T {
    val values = enumValues<T>()
    return values[(current.ordinal + 1) % values.size]
}

private fun pretty(value: String): String = value
    .lowercase()
    .replace('_', ' ')
    .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
