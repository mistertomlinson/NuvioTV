from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text()


def write(path: str, text: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text)


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected exactly one match, found {count}\n--- anchor ---\n{old}")
    write(path, text.replace(old, new, 1))


# Domain preference model.
write(
    "app/src/main/java/com/nuvio/tv/domain/model/BadgeColorStyle.kt",
    """package com.nuvio.tv.domain.model

enum class BadgeColorStyle {
    NUVIO,
    LEGACY
}
""",
)

# Centralized semantic badge palette. NUVIO intentionally preserves the current
# release gradient behavior, while LEGACY reproduces the official Nuvio colors.
write(
    "app/src/main/java/com/nuvio/tv/ui/theme/NuvioBadgeColors.kt",
    """package com.nuvio.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import com.nuvio.tv.domain.model.BadgeColorStyle

internal enum class NuvioBadgeSemantic {
    STANDARD,
    NEW_EPISODE,
    NEW_SEASON,
    AVAILABLE_NOW
}

private val LegacyNewEpisodeBadge = Color(0xFF1D4ED8)
private val LegacyNewSeasonBadge = Color(0xFFB45309)
private val LegacyAvailableNowBadge = Color(0xFFB91C1C)

@Composable
@ReadOnlyComposable
internal fun nuvioBadgeBrush(semantic: NuvioBadgeSemantic): Brush {
    val background = NuvioTheme.colors.Background

    return when (NuvioTheme.badgeColorStyle) {
        BadgeColorStyle.NUVIO -> when (semantic) {
            NuvioBadgeSemantic.STANDARD -> SolidColor(background.copy(alpha = 0.8f))
            NuvioBadgeSemantic.NEW_EPISODE,
            NuvioBadgeSemantic.NEW_SEASON,
            NuvioBadgeSemantic.AVAILABLE_NOW -> NuvioGradients.ReleaseStatusBadge
        }

        BadgeColorStyle.LEGACY -> when (semantic) {
            NuvioBadgeSemantic.STANDARD -> SolidColor(background.copy(alpha = 0.8f))
            NuvioBadgeSemantic.NEW_EPISODE -> SolidColor(LegacyNewEpisodeBadge)
            NuvioBadgeSemantic.NEW_SEASON -> SolidColor(LegacyNewSeasonBadge)
            NuvioBadgeSemantic.AVAILABLE_NOW -> SolidColor(LegacyAvailableNowBadge)
        }
    }
}
""",
)

# Persist Badge Colors with the same profile-scoped store as theme/font.
path = "app/src/main/java/com/nuvio/tv/data/local/ThemeDataStore.kt"
replace_once(
    path,
    "import com.nuvio.tv.domain.model.AppTheme\n",
    "import com.nuvio.tv.domain.model.AppTheme\nimport com.nuvio.tv.domain.model.BadgeColorStyle\n",
)
replace_once(
    path,
    '    private val fontKey = stringPreferencesKey("selected_font")\n',
    '    private val fontKey = stringPreferencesKey("selected_font")\n    private val badgeColorStyleKey = stringPreferencesKey("badge_color_style")\n',
)
replace_once(
    path,
    """    suspend fun setTheme(theme: AppTheme) {
""",
    """    val selectedBadgeColorStyle: Flow<BadgeColorStyle> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            val styleName = prefs[badgeColorStyleKey] ?: BadgeColorStyle.NUVIO.name
            try {
                BadgeColorStyle.valueOf(styleName)
            } catch (e: IllegalArgumentException) {
                BadgeColorStyle.NUVIO
            }
        }
    }

    suspend fun setTheme(theme: AppTheme) {
""",
)
replace_once(
    path,
    """    suspend fun setFont(font: AppFont) {
        store().edit { prefs ->
            prefs[fontKey] = font.name
        }
    }
}""",
    """    suspend fun setFont(font: AppFont) {
        store().edit { prefs ->
            prefs[fontKey] = font.name
        }
    }

    suspend fun setBadgeColorStyle(style: BadgeColorStyle) {
        store().edit { prefs ->
            prefs[badgeColorStyleKey] = style.name
        }
    }
}""",
)

# Expose/persist selection through the Appearance ViewModel.
path = "app/src/main/java/com/nuvio/tv/ui/screens/settings/ThemeSettingsViewModel.kt"
replace_once(
    path,
    "import com.nuvio.tv.domain.model.AppTheme\n",
    "import com.nuvio.tv.domain.model.AppTheme\nimport com.nuvio.tv.domain.model.BadgeColorStyle\n",
)
replace_once(
    path,
    """    val selectedFont: AppFont = AppFont.INTER,
    val availableFonts: List<AppFont> = AppFont.entries.toList()
""",
    """    val selectedFont: AppFont = AppFont.INTER,
    val availableFonts: List<AppFont> = AppFont.entries.toList(),
    val selectedBadgeColorStyle: BadgeColorStyle = BadgeColorStyle.NUVIO,
    val availableBadgeColorStyles: List<BadgeColorStyle> = BadgeColorStyle.entries.toList()
""",
)
replace_once(
    path,
    """sealed class ThemeSettingsEvent {
    data class SelectTheme(val theme: AppTheme) : ThemeSettingsEvent()
    data class SelectFont(val font: AppFont) : ThemeSettingsEvent()
}
""",
    """sealed class ThemeSettingsEvent {
    data class SelectTheme(val theme: AppTheme) : ThemeSettingsEvent()
    data class SelectFont(val font: AppFont) : ThemeSettingsEvent()
    data class SelectBadgeColorStyle(val style: BadgeColorStyle) : ThemeSettingsEvent()
}
""",
)
replace_once(
    path,
    """        viewModelScope.launch {
            themeDataStore.selectedFont
                .distinctUntilChanged()
                .collectLatest { font ->
                    _uiState.update { state ->
                        if (state.selectedFont == font) state else state.copy(selectedFont = font)
                    }
                }
        }
    }
""",
    """        viewModelScope.launch {
            themeDataStore.selectedFont
                .distinctUntilChanged()
                .collectLatest { font ->
                    _uiState.update { state ->
                        if (state.selectedFont == font) state else state.copy(selectedFont = font)
                    }
                }
        }
        viewModelScope.launch {
            themeDataStore.selectedBadgeColorStyle
                .distinctUntilChanged()
                .collectLatest { style ->
                    _uiState.update { state ->
                        if (state.selectedBadgeColorStyle == style) state
                        else state.copy(selectedBadgeColorStyle = style)
                    }
                }
        }
    }
""",
)
replace_once(
    path,
    """        when (event) {
            is ThemeSettingsEvent.SelectTheme -> selectTheme(event.theme)
            is ThemeSettingsEvent.SelectFont -> selectFont(event.font)
        }
""",
    """        when (event) {
            is ThemeSettingsEvent.SelectTheme -> selectTheme(event.theme)
            is ThemeSettingsEvent.SelectFont -> selectFont(event.font)
            is ThemeSettingsEvent.SelectBadgeColorStyle -> selectBadgeColorStyle(event.style)
        }
""",
)
replace_once(
    path,
    """    private fun selectFont(font: AppFont) {
        if (_uiState.value.selectedFont == font) return
        viewModelScope.launch {
            themeDataStore.setFont(font)
        }
    }
}""",
    """    private fun selectFont(font: AppFont) {
        if (_uiState.value.selectedFont == font) return
        viewModelScope.launch {
            themeDataStore.setFont(font)
        }
    }

    private fun selectBadgeColorStyle(style: BadgeColorStyle) {
        if (_uiState.value.selectedBadgeColorStyle == style) return
        viewModelScope.launch {
            themeDataStore.setBadgeColorStyle(style)
        }
    }
}""",
)

# Make the selected palette available app-wide through the existing NuvioTheme.
path = "app/src/main/java/com/nuvio/tv/ui/theme/Theme.kt"
replace_once(
    path,
    "import com.nuvio.tv.domain.model.AppTheme\n",
    "import com.nuvio.tv.domain.model.AppTheme\nimport com.nuvio.tv.domain.model.BadgeColorStyle\n",
)
replace_once(
    path,
    "val LocalAppTheme = staticCompositionLocalOf { AppTheme.WHITE }\n",
    "val LocalAppTheme = staticCompositionLocalOf { AppTheme.WHITE }\nval LocalBadgeColorStyle = staticCompositionLocalOf { BadgeColorStyle.NUVIO }\n",
)
replace_once(
    path,
    """fun NuvioTheme(
    appTheme: AppTheme = AppTheme.WHITE,
    appFont: AppFont = AppFont.INTER,
    content: @Composable () -> Unit
) {
""",
    """fun NuvioTheme(
    appTheme: AppTheme = AppTheme.WHITE,
    appFont: AppFont = AppFont.INTER,
    badgeColorStyle: BadgeColorStyle = BadgeColorStyle.NUVIO,
    content: @Composable () -> Unit
) {
""",
)
replace_once(
    path,
    """        LocalNuvioColors provides colorScheme,
        LocalNuvioExtendedColors provides extendedColors,
        LocalAppTheme provides appTheme
""",
    """        LocalNuvioColors provides colorScheme,
        LocalNuvioExtendedColors provides extendedColors,
        LocalAppTheme provides appTheme,
        LocalBadgeColorStyle provides badgeColorStyle
""",
)
replace_once(
    path,
    """    val currentTheme: AppTheme
        @Composable
        @ReadOnlyComposable
        get() = LocalAppTheme.current
}""",
    """    val currentTheme: AppTheme
        @Composable
        @ReadOnlyComposable
        get() = LocalAppTheme.current

    val badgeColorStyle: BadgeColorStyle
        @Composable
        @ReadOnlyComposable
        get() = LocalBadgeColorStyle.current
}""",
)

# Collect the profile preference once at the app root and provide it through NuvioTheme.
path = "app/src/main/java/com/nuvio/tv/MainActivity.kt"
replace_once(
    path,
    "import com.nuvio.tv.domain.model.AppTheme\n",
    "import com.nuvio.tv.domain.model.AppTheme\nimport com.nuvio.tv.domain.model.BadgeColorStyle\n",
)
replace_once(
    path,
    """private data class MainUiPrefs(
    val theme: AppTheme = AppTheme.WHITE,
    val font: AppFont = AppFont.INTER,
    val hasChosenLayout: Boolean? = null,
    val cardDepthStyle: CardDepthStyle = CardDepthStyle()
)
""",
    """private data class MainUiPrefs(
    val theme: AppTheme = AppTheme.WHITE,
    val font: AppFont = AppFont.INTER,
    val badgeColorStyle: BadgeColorStyle = BadgeColorStyle.NUVIO,
    val hasChosenLayout: Boolean? = null,
    val cardDepthStyle: CardDepthStyle = CardDepthStyle()
)
""",
)
replace_once(
    path,
    """                combine(
                    themeDataStore.selectedTheme,
                    themeDataStore.selectedFont,
                    layoutPreferenceDataStore.hasChosenLayout,
                ) { theme, font, hasChosenLayout ->
                    MainUiPrefs(
                        theme = theme,
                        font = font,
                        hasChosenLayout = hasChosenLayout,
                    )
                }.combine(layoutPreferenceDataStore.cardDepthStyle) { prefs, cardDepthStyle ->
""",
    """                combine(
                    themeDataStore.selectedTheme,
                    themeDataStore.selectedFont,
                    themeDataStore.selectedBadgeColorStyle,
                    layoutPreferenceDataStore.hasChosenLayout,
                ) { theme, font, badgeColorStyle, hasChosenLayout ->
                    MainUiPrefs(
                        theme = theme,
                        font = font,
                        badgeColorStyle = badgeColorStyle,
                        hasChosenLayout = hasChosenLayout,
                    )
                }.combine(layoutPreferenceDataStore.cardDepthStyle) { prefs, cardDepthStyle ->
""",
)
replace_once(
    path,
    """            NuvioTheme(appTheme = mainUiPrefs.theme, appFont = mainUiPrefs.font) {
""",
    """            NuvioTheme(
                appTheme = mainUiPrefs.theme,
                appFont = mainUiPrefs.font,
                badgeColorStyle = mainUiPrefs.badgeColorStyle
            ) {
""",
)

# Add Appearance strings without changing any existing wording.
path = "app/src/main/res/values/strings.xml"
replace_once(
    path,
    '    <string name="appearance_color_theme">Color Theme</string>\n',
    '    <string name="appearance_color_theme">Color Theme</string>\n'
    '    <string name="appearance_badge_colors">Badge Colors</string>\n'
    '    <string name="appearance_badge_colors_subtitle">Choose your badge color style</string>\n'
    '    <string name="appearance_badge_colors_nuvio">Nuvio Colors</string>\n'
    '    <string name="appearance_badge_colors_legacy">Legacy Colors</string>\n',
)

# Insert Badge Colors as the expandable section directly between Color Theme and Font.
path = "app/src/main/java/com/nuvio/tv/ui/screens/settings/ThemeSettingsScreen.kt"
replace_once(
    path,
    "import com.nuvio.tv.domain.model.AppTheme\n",
    "import com.nuvio.tv.domain.model.AppTheme\nimport com.nuvio.tv.domain.model.BadgeColorStyle\n",
)
replace_once(
    path,
    """private enum class AppearanceSection {
    THEME,
    FONT,
    LANGUAGE
}
""",
    """private enum class AppearanceSection {
    THEME,
    BADGE_COLORS,
    FONT,
    LANGUAGE
}
""",
)
replace_once(
    path,
    """    var fontExpanded by remember { mutableStateOf(false) }
    var languageExpanded by remember { mutableStateOf(false) }
    var pendingFontFocusRestore by remember { mutableStateOf(false) }
""",
    """    var badgeColorsExpanded by remember { mutableStateOf(false) }
    var fontExpanded by remember { mutableStateOf(false) }
    var languageExpanded by remember { mutableStateOf(false) }
    var pendingBadgeColorsFocusRestore by remember { mutableStateOf(false) }
    var pendingFontFocusRestore by remember { mutableStateOf(false) }
""",
)
replace_once(
    path,
    """    val fontHeaderFocus = remember { FocusRequester() }
    val languageHeaderFocus = remember { FocusRequester() }
""",
    """    val badgeColorsHeaderFocus = remember { FocusRequester() }
    val fontHeaderFocus = remember { FocusRequester() }
    val languageHeaderFocus = remember { FocusRequester() }
""",
)
replace_once(
    path,
    """    LaunchedEffect(fontExpanded, pendingFontFocusRestore) {
""",
    """    LaunchedEffect(badgeColorsExpanded, pendingBadgeColorsFocusRestore) {
        if (!badgeColorsExpanded && pendingBadgeColorsFocusRestore) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { badgeColorsHeaderFocus.requestFocus() }
            pendingBadgeColorsFocusRestore = false
        }
    }

    LaunchedEffect(fontExpanded, pendingFontFocusRestore) {
""",
)
replace_once(
    path,
    """    val visibleSections = listOf(
        AppearanceSection.THEME,
        AppearanceSection.FONT,
        AppearanceSection.LANGUAGE
    )
    val expandedSections = buildSet {
        if (fontExpanded) add(AppearanceSection.FONT)
        if (languageExpanded) add(AppearanceSection.LANGUAGE)
    }
""",
    """    val visibleSections = listOf(
        AppearanceSection.THEME,
        AppearanceSection.BADGE_COLORS,
        AppearanceSection.FONT,
        AppearanceSection.LANGUAGE
    )
    val expandedSections = buildSet {
        if (badgeColorsExpanded) add(AppearanceSection.BADGE_COLORS)
        if (fontExpanded) add(AppearanceSection.FONT)
        if (languageExpanded) add(AppearanceSection.LANGUAGE)
    }
""",
)
replace_once(
    path,
    """        when (section) {
            AppearanceSection.THEME -> Unit
            AppearanceSection.FONT -> fontExpanded = expanded
            AppearanceSection.LANGUAGE -> languageExpanded = expanded
        }
""",
    """        when (section) {
            AppearanceSection.THEME -> Unit
            AppearanceSection.BADGE_COLORS -> badgeColorsExpanded = expanded
            AppearanceSection.FONT -> fontExpanded = expanded
            AppearanceSection.LANGUAGE -> languageExpanded = expanded
        }
""",
)

badge_section = """                    item(key = \"appearance_badge_colors\") {
                        AppearanceExpandableSection(
                            title = stringResource(R.string.appearance_badge_colors),
                            subtitle = stringResource(
                                R.string.appearance_badge_colors_subtitle
                            ),
                            value = badgeColorStyleLabel(
                                uiState.selectedBadgeColorStyle
                            ),
                            expanded = badgeColorsExpanded,
                            onToggle = {
                                if (badgeColorsExpanded) {
                                    collapseSection(
                                        AppearanceSection.BADGE_COLORS,
                                        \"appearance_badge_colors\"
                                    )
                                } else {
                                    expandSection(
                                        AppearanceSection.BADGE_COLORS
                                    )
                                }
                            },
                            focusRequester = badgeColorsHeaderFocus,
                            groupPosition = groupPositionFor(
                                AppearanceSection.BADGE_COLORS
                            ),
                            animateTopFlatten = animateTopFlattenFor(
                                AppearanceSection.BADGE_COLORS
                            ),
                            animateBottomFlatten = animateBottomFlattenFor(
                                AppearanceSection.BADGE_COLORS
                            ),
                            deferBottomCorner =
                                AppearanceSection.BADGE_COLORS in
                                    deferredBottomCornerSections
                        ) {
                            uiState.availableBadgeColorStyles.forEach { style ->
                                AppearanceChoiceRow(
                                    label = badgeColorStyleLabel(style),
                                    selected =
                                        style == uiState.selectedBadgeColorStyle,
                                    onClick = {
                                        viewModel.onEvent(
                                            ThemeSettingsEvent
                                                .SelectBadgeColorStyle(style)
                                        )
                                        pendingBadgeColorsFocusRestore = true
                                        collapseSection(
                                            AppearanceSection.BADGE_COLORS,
                                            \"appearance_badge_colors\"
                                        )
                                    }
                                )
                            }
                        }
                    }

"""
replace_once(
    path,
    '                    item(key = "appearance_font") {\n',
    badge_section + '                    item(key = "appearance_font") {\n',
)
replace_once(
    path,
    """@Composable
private fun AppearanceThemeSelector(
""",
    """@Composable
private fun badgeColorStyleLabel(style: BadgeColorStyle): String =
    when (style) {
        BadgeColorStyle.NUVIO -> stringResource(R.string.appearance_badge_colors_nuvio)
        BadgeColorStyle.LEGACY -> stringResource(R.string.appearance_badge_colors_legacy)
    }

@Composable
private fun AppearanceThemeSelector(
""",
)

# Continue Watching: choose a semantic badge type, then resolve it through the shared palette.
path = "app/src/main/java/com/nuvio/tv/ui/components/ContinueWatchingSection.kt"
replace_once(
    path,
    "import com.nuvio.tv.ui.theme.NuvioTheme\n",
    "import com.nuvio.tv.ui.theme.NuvioTheme\nimport com.nuvio.tv.ui.theme.NuvioBadgeSemantic\nimport com.nuvio.tv.ui.theme.nuvioBadgeBrush\n",
)
replace_once(
    path,
    """    val badgeBackground = remember(
        bgColor,
        nextUp?.isReleaseAlert,
        nextUp?.isNewSeasonRelease
    ) {
        when {
            nextUp?.isNewSeasonRelease == true ||
                nextUp?.isReleaseAlert == true ->
                NuvioGradients.ReleaseStatusBadge

            else ->
                SolidColor(
                    bgColor.copy(alpha = 0.8f)
                )
        }
    }
""",
    """    val badgeBackground = nuvioBadgeBrush(
        semantic = when {
            nextUp?.isNewSeasonRelease == true ->
                NuvioBadgeSemantic.NEW_SEASON
            nextUp?.isReleaseAlert == true ->
                NuvioBadgeSemantic.NEW_EPISODE
            else ->
                NuvioBadgeSemantic.STANDARD
        }
    )
""",
)

# Catalog release badge: New Season uses legacy amber; Available Now uses agreed legacy red.
path = "app/src/main/java/com/nuvio/tv/ui/screens/home/ModernHomeRows.kt"
replace_once(
    path,
    "import com.nuvio.tv.ui.theme.NuvioGradients\n",
    "import com.nuvio.tv.ui.theme.NuvioGradients\nimport com.nuvio.tv.ui.theme.NuvioBadgeSemantic\nimport com.nuvio.tv.ui.theme.nuvioBadgeBrush\n",
)
replace_once(
    path,
    """                if (
                    showReleaseBadge &&
                    showHeavyOverlays
                ) {
                    Text(
""",
    """                if (
                    showReleaseBadge &&
                    showHeavyOverlays
                ) {
                    val releaseBadgeBrush = nuvioBadgeBrush(
                        semantic = if (
                            effectiveReleaseBadge == ReleaseReminderBadge.NEW_SEASON
                        ) {
                            NuvioBadgeSemantic.NEW_SEASON
                        } else {
                            NuvioBadgeSemantic.AVAILABLE_NOW
                        }
                    )
                    Text(
""",
)
replace_once(
    path,
    "brush = NuvioGradients.ReleaseStatusBadge,\n                                shape = RoundedCornerShape(5.dp)",
    "brush = releaseBadgeBrush,\n                                shape = RoundedCornerShape(5.dp)",
)

print("Badge Colors patch applied successfully")
