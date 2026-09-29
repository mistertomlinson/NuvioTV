@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.AppFont
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.ThemeColors
import com.nuvio.tv.ui.theme.getFontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private enum class AppearanceSection {
    THEME,
    FONT,
    LANGUAGE
}

private const val APPEARANCE_COLLAPSE_MILLIS = 240
private const val APPEARANCE_LIST_SETTLE_MILLIS = 520

@Composable
fun ThemeSettingsScreen(
    viewModel: ThemeSettingsViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    BackHandler { onBackPress() }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.appearance_title),
        subtitle = stringResource(R.string.appearance_subtitle)
    ) {
        ThemeSettingsContent(viewModel = viewModel)
    }
}

@Composable
fun ThemeSettingsContent(
    viewModel: ThemeSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appearanceAnimationScope = rememberCoroutineScope()
    val appearanceListState = rememberLazyListState()
    val appearanceListSettleOffsetY = remember { Animatable(0f) }

    var fontExpanded by remember { mutableStateOf(false) }
    var languageExpanded by remember { mutableStateOf(false) }
    var pendingFontFocusRestore by remember { mutableStateOf(false) }
    var pendingLanguageFocusRestore by remember { mutableStateOf(false) }
    var pendingLanguageRestart by remember { mutableStateOf(false) }
    var animatedFlattenBoundaries by remember {
        mutableStateOf<Set<Int>>(emptySet())
    }
    var deferredBottomCornerSections by remember {
        mutableStateOf<Set<AppearanceSection>>(emptySet())
    }

    val fontHeaderFocus = remember { FocusRequester() }
    val languageHeaderFocus = remember { FocusRequester() }

    val strLanguageSystem =
        stringResource(R.string.appearance_language_system)
    val supportedLocales = remember(strLanguageSystem) {
        val tags = listOf(
            "en", "de", "es", "es-419", "hu", "fr", "it", "pl",
            "pt-PT", "pt-BR", "tr", "cs", "sk", "sl", "sv", "ro", "ja",
            "nl", "vi", "hi", "lt"
        )
        listOf(null to strLanguageSystem) + tags.map { tag ->
            val locale = Locale.forLanguageTag(tag)
            tag to locale.getDisplayName(locale)
                .replaceFirstChar { it.uppercase() }
        }.sortedBy { it.second }
    }
    var selectedTag by remember {
        mutableStateOf(
            context.getSharedPreferences(
                "app_locale",
                Context.MODE_PRIVATE
            )
                .getString("locale_tag", null)
                ?.takeIf { it.isNotEmpty() }
        )
    }
    val currentLocaleName =
        supportedLocales.firstOrNull { it.first == selectedTag }
            ?.second
            ?: strLanguageSystem
    val strRestartHint =
        stringResource(R.string.appearance_language_restart_hint)

    LaunchedEffect(fontExpanded, pendingFontFocusRestore) {
        if (!fontExpanded && pendingFontFocusRestore) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { fontHeaderFocus.requestFocus() }
            pendingFontFocusRestore = false
        }
    }

    LaunchedEffect(languageExpanded, pendingLanguageFocusRestore) {
        if (!languageExpanded && pendingLanguageFocusRestore) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { languageHeaderFocus.requestFocus() }
            pendingLanguageFocusRestore = false
        }
    }

    LaunchedEffect(pendingLanguageRestart, languageExpanded) {
        if (pendingLanguageRestart && !languageExpanded) {
            /*
             * Let both the 240 ms child collapse and the possible 520 ms
             * short-list settle complete before Activity recreation.
             */
            delay(APPEARANCE_LIST_SETTLE_MILLIS.toLong() + 80L)
            context.findActivity()?.recreate()
                ?: Toast.makeText(
                    context,
                    strRestartHint,
                    Toast.LENGTH_LONG
                ).show()
            pendingLanguageRestart = false
        }
    }

    val visibleSections = listOf(
        AppearanceSection.THEME,
        AppearanceSection.FONT,
        AppearanceSection.LANGUAGE
    )
    val expandedSections = buildSet {
        if (fontExpanded) add(AppearanceSection.FONT)
        if (languageExpanded) add(AppearanceSection.LANGUAGE)
    }

    fun groupPositionFor(
        section: AppearanceSection,
        expanded: Set<AppearanceSection> = expandedSections
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
        section: AppearanceSection,
        expanded: Set<AppearanceSection>
    ): Boolean {
        return when (groupPositionFor(section, expanded)) {
            SettingsGroupPosition.SINGLE,
            SettingsGroupPosition.TOP -> true
            SettingsGroupPosition.MIDDLE,
            SettingsGroupPosition.BOTTOM -> false
        }
    }

    fun headerBottomRounded(
        section: AppearanceSection,
        expanded: Set<AppearanceSection>
    ): Boolean {
        return when (groupPositionFor(section, expanded)) {
            SettingsGroupPosition.SINGLE,
            SettingsGroupPosition.BOTTOM -> true
            SettingsGroupPosition.TOP,
            SettingsGroupPosition.MIDDLE -> false
        }
    }

    fun applySectionExpandedState(
        section: AppearanceSection,
        expanded: Boolean
    ) {
        when (section) {
            AppearanceSection.THEME -> Unit
            AppearanceSection.FONT -> fontExpanded = expanded
            AppearanceSection.LANGUAGE -> languageExpanded = expanded
        }
    }

    fun beginBoundaryReturnAnimation(
        boundaries: Set<Int>
    ) {
        animatedFlattenBoundaries = boundaries
        if (boundaries.isNotEmpty()) {
            appearanceAnimationScope.launch {
                delay(260)
                if (animatedFlattenBoundaries == boundaries) {
                    animatedFlattenBoundaries = emptySet()
                }
            }
        }
    }

    fun immediateFlattenBoundariesForCollapse(
        section: AppearanceSection,
        before: Set<AppearanceSection>,
        after: Set<AppearanceSection>
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
                val roundedEdgeNeedsToFlatten =
                    (upperRoundedBefore && !upperRoundedAfter) ||
                        (lowerRoundedBefore && !lowerRoundedAfter)

                finalBoundaryIsFlat && roundedEdgeNeedsToFlatten
            }
            .toSet()
    }

    fun prepareSectionCollapseCorners(
        section: AppearanceSection,
        before: Set<AppearanceSection>,
        after: Set<AppearanceSection>
    ) {
        beginBoundaryReturnAnimation(
            immediateFlattenBoundariesForCollapse(
                section = section,
                before = before,
                after = after
            )
        )
        deferredBottomCornerSections =
            deferredBottomCornerSections + section
    }

    fun releaseDeferredBottomCornerAfterCollapse(
        section: AppearanceSection
    ) {
        appearanceAnimationScope.launch {
            delay(APPEARANCE_COLLAPSE_MILLIS.toLong())
            if (section !in deferredBottomCornerSections) {
                return@launch
            }
            androidx.compose.runtime.withFrameNanos { }
            deferredBottomCornerSections =
                deferredBottomCornerSections - section
        }
    }

    fun animateTopFlattenFor(section: AppearanceSection): Boolean {
        val index = visibleSections.indexOf(section)
        return index > 0 &&
            (index - 1) in animatedFlattenBoundaries
    }

    fun animateBottomFlattenFor(section: AppearanceSection): Boolean {
        val index = visibleSections.indexOf(section)
        return index >= 0 &&
            index < visibleSections.lastIndex &&
            index in animatedFlattenBoundaries
    }

    fun expandSection(section: AppearanceSection) {
        deferredBottomCornerSections =
            deferredBottomCornerSections - section
        animatedFlattenBoundaries = emptySet()
        applySectionExpandedState(section, true)
    }

    fun collapseSection(
        section: AppearanceSection,
        itemKey: String
    ) {
        val before = expandedSections
        val after = before - section

        prepareSectionCollapseCorners(
            section = section,
            before = before,
            after = after
        )

        val listHasBeenScrolled =
            appearanceListState.firstVisibleItemIndex > 0 ||
                appearanceListState.firstVisibleItemScrollOffset > 0
        val itemOffsetBefore =
            appearanceListState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key == itemKey }
                ?.offset
                ?.toFloat()

        if (!listHasBeenScrolled || itemOffsetBefore == null) {
            applySectionExpandedState(section, false)
            releaseDeferredBottomCornerAfterCollapse(section)
            return
        }

        appearanceAnimationScope.launch {
            appearanceListSettleOffsetY.stop()
            appearanceListSettleOffsetY.snapTo(0f)

            /*
             * The collapsed Appearance page is intentionally short. Put the
             * LazyColumn in that final legal scroll state before its content
             * shrinks, then visually preserve the old position and glide it
             * home instead of exposing LazyColumn's max-scroll clamp.
             */
            appearanceListState.scrollToItem(
                index = 0,
                scrollOffset = 0
            )

            val itemOffsetAfter =
                appearanceListState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.key == itemKey }
                    ?.offset
                    ?.toFloat()

            if (itemOffsetAfter != null) {
                appearanceListSettleOffsetY.snapTo(
                    itemOffsetBefore - itemOffsetAfter
                )
            }

            applySectionExpandedState(section, false)
            releaseDeferredBottomCornerAfterCollapse(section)

            appearanceListSettleOffsetY.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = APPEARANCE_LIST_SETTLE_MILLIS,
                    easing = FastOutSlowInEasing
                )
            )
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SettingsDetailHeader(
                title = stringResource(R.string.appearance_title),
                subtitle = stringResource(R.string.appearance_subtitle)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clipToBounds()
            ) {
                LazyColumn(
                    state = appearanceListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            translationY =
                                appearanceListSettleOffsetY.value
                        },
                    contentPadding = PaddingValues(bottom = 12.dp),
                    verticalArrangement =
                        Arrangement.spacedBy(SettingsRowGap)
                ) {
                    item(key = "appearance_theme") {
                        AppearanceThemeSelector(
                            themes = uiState.availableThemes,
                            selectedTheme = uiState.selectedTheme,
                            onThemeSelected = { theme ->
                                viewModel.onEvent(
                                    ThemeSettingsEvent.SelectTheme(theme)
                                )
                            },
                            focusRequester = initialFocusRequester,
                            groupPosition = groupPositionFor(
                                AppearanceSection.THEME
                            ),
                            animateTopFlatten = animateTopFlattenFor(
                                AppearanceSection.THEME
                            ),
                            animateBottomFlatten = animateBottomFlattenFor(
                                AppearanceSection.THEME
                            )
                        )
                    }

                    item(key = "appearance_font") {
                        AppearanceExpandableSection(
                            title = stringResource(R.string.appearance_font),
                            subtitle = stringResource(
                                R.string.appearance_font_subtitle
                            ),
                            value = uiState.selectedFont.displayName,
                            expanded = fontExpanded,
                            onToggle = {
                                if (fontExpanded) {
                                    collapseSection(
                                        AppearanceSection.FONT,
                                        "appearance_font"
                                    )
                                } else {
                                    expandSection(AppearanceSection.FONT)
                                }
                            },
                            focusRequester = fontHeaderFocus,
                            groupPosition = groupPositionFor(
                                AppearanceSection.FONT
                            ),
                            animateTopFlatten = animateTopFlattenFor(
                                AppearanceSection.FONT
                            ),
                            animateBottomFlatten = animateBottomFlattenFor(
                                AppearanceSection.FONT
                            ),
                            deferBottomCorner =
                                AppearanceSection.FONT in
                                    deferredBottomCornerSections
                        ) {
                            uiState.availableFonts.forEach { font ->
                                AppearanceChoiceRow(
                                    label = font.displayName,
                                    selected =
                                        font == uiState.selectedFont,
                                    fontFamily = getFontFamily(font),
                                    onClick = {
                                        viewModel.onEvent(
                                            ThemeSettingsEvent
                                                .SelectFont(font)
                                        )
                                        pendingFontFocusRestore = true
                                        collapseSection(
                                            AppearanceSection.FONT,
                                            "appearance_font"
                                        )
                                    }
                                )
                            }
                        }
                    }

                    item(key = "appearance_language") {
                        AppearanceExpandableSection(
                            title = stringResource(
                                R.string.appearance_language
                            ),
                            subtitle = stringResource(
                                R.string.appearance_language_subtitle
                            ),
                            value = currentLocaleName,
                            expanded = languageExpanded,
                            onToggle = {
                                if (languageExpanded) {
                                    collapseSection(
                                        AppearanceSection.LANGUAGE,
                                        "appearance_language"
                                    )
                                } else {
                                    expandSection(
                                        AppearanceSection.LANGUAGE
                                    )
                                }
                            },
                            focusRequester = languageHeaderFocus,
                            groupPosition = groupPositionFor(
                                AppearanceSection.LANGUAGE
                            ),
                            animateTopFlatten = animateTopFlattenFor(
                                AppearanceSection.LANGUAGE
                            ),
                            animateBottomFlatten = animateBottomFlattenFor(
                                AppearanceSection.LANGUAGE
                            ),
                            deferBottomCorner =
                                AppearanceSection.LANGUAGE in
                                    deferredBottomCornerSections
                        ) {
                            supportedLocales.forEach { (tag, name) ->
                                AppearanceChoiceRow(
                                    label = name,
                                    selected = tag == selectedTag,
                                    onClick = {
                                        val previousTag = selectedTag
                                        context.getSharedPreferences(
                                            "app_locale",
                                            Context.MODE_PRIVATE
                                        )
                                            .edit()
                                            .putString(
                                                "locale_tag",
                                                tag ?: ""
                                            )
                                            .apply()
                                        selectedTag = tag
                                        if (previousTag != tag) {
                                            pendingLanguageRestart = true
                                        }
                                        pendingLanguageFocusRestore = true
                                        collapseSection(
                                            AppearanceSection.LANGUAGE,
                                            "appearance_language"
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                SettingsVerticalScrollIndicators(
                    state = appearanceListState
                )
            }
        }
    }
}

@Composable
private fun AppearanceThemeSelector(
    themes: List<AppTheme>,
    selectedTheme: AppTheme,
    onThemeSelected: (AppTheme) -> Unit,
    focusRequester: FocusRequester?,
    groupPosition: SettingsGroupPosition,
    animateTopFlatten: Boolean,
    animateBottomFlatten: Boolean
) {
    val shape = animatedSettingsGroupShape(
        position = groupPosition,
        animateTopFlatten = animateTopFlatten,
        animateBottomFlatten = animateBottomFlatten
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SettingsRightSurfaceColor)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            text = stringResource(R.string.appearance_color_theme),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioColors.TextPrimary
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            themes.forEachIndexed { index, theme ->
                AppearanceThemeSwatch(
                    theme = theme,
                    selected = theme == selectedTheme,
                    onClick = { onThemeSelected(theme) },
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (
                                index == 0 &&
                                focusRequester != null
                            ) {
                                Modifier.focusRequester(focusRequester)
                            } else {
                                Modifier
                            }
                        )
                )
            }
        }
    }
}

@Composable
private fun AppearanceThemeSwatch(
    theme: AppTheme,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val palette = ThemeColors.getColorPalette(theme)
    val shape = RoundedCornerShape(10.dp)

    Card(
        onClick = onClick,
        modifier = modifier
            .height(62.dp)
            .onFocusChanged { state ->
                isFocused = state.isFocused
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
                .fillMaxSize()
                .padding(horizontal = 5.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(palette.secondary),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(
                            R.string.cd_selected
                        ),
                        tint = palette.onSecondary,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(3.dp))

            Text(
                text = theme.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    isFocused || selected -> NuvioColors.TextPrimary
                    else -> NuvioColors.TextSecondary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AppearanceExpandableSection(
    title: String,
    subtitle: String,
    value: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    focusRequester: FocusRequester,
    groupPosition: SettingsGroupPosition,
    animateTopFlatten: Boolean,
    animateBottomFlatten: Boolean,
    deferBottomCorner: Boolean,
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
            subtitle = subtitle,
            value = value,
            onClick = onToggle,
            trailingIcon =
                if (expanded) {
                    Icons.Default.ExpandMore
                } else {
                    Icons.Default.ChevronRight
                },
            modifier = Modifier.focusRequester(focusRequester),
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
                    durationMillis = APPEARANCE_COLLAPSE_MILLIS,
                    easing = FastOutSlowInEasing
                ),
                expandFrom = Alignment.Top
            ),
            exit = shrinkVertically(
                animationSpec = tween(
                    durationMillis = APPEARANCE_COLLAPSE_MILLIS,
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
private fun AppearanceChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    fontFamily: FontFamily? = null
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(49.dp)
            .onFocusChanged { state ->
                isFocused = state.isFocused
            },
        colors = CardDefaults.colors(
            containerColor = SettingsRightSurfaceColor,
            focusedContainerColor = SettingsRightSurfaceFocusedColor
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border.None
        ),
        shape = CardDefaults.shape(
            RoundedCornerShape(SettingsInnerRowRadius)
        ),
        scale = CardDefaults.scale(
            focusedScale = 1f,
            pressedScale = 1f
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = fontFamily,
                color =
                    if (isFocused || selected) {
                        NuvioColors.TextPrimary
                    } else {
                        NuvioColors.TextSecondary
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(
                        R.string.cd_selected
                    ),
                    tint = NuvioColors.Secondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
