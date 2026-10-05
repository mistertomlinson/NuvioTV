package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.ThemeDataStore
import com.nuvio.tv.domain.model.AppFont
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.BadgeColorStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ThemeSettingsUiState(
    val selectedTheme: AppTheme = AppTheme.WHITE,
    val availableThemes: List<AppTheme> = listOf(AppTheme.WHITE) + AppTheme.entries.filterNot { it == AppTheme.WHITE },
    val selectedFont: AppFont = AppFont.INTER,
    val availableFonts: List<AppFont> = AppFont.entries.toList(),
    val selectedBadgeColorStyle: BadgeColorStyle = BadgeColorStyle.NUVIO,
    val availableBadgeColorStyles: List<BadgeColorStyle> = BadgeColorStyle.entries.toList()
)

sealed class ThemeSettingsEvent {
    data class SelectTheme(val theme: AppTheme) : ThemeSettingsEvent()
    data class SelectFont(val font: AppFont) : ThemeSettingsEvent()
    data class SelectBadgeColorStyle(val style: BadgeColorStyle) : ThemeSettingsEvent()
}

@HiltViewModel
class ThemeSettingsViewModel @Inject constructor(
    private val themeDataStore: ThemeDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(ThemeSettingsUiState())
    val uiState: StateFlow<ThemeSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            themeDataStore.selectedTheme
                .distinctUntilChanged()
                .collectLatest { theme ->
                    _uiState.update { state ->
                        if (state.selectedTheme == theme) state else state.copy(selectedTheme = theme)
                    }
                }
        }
        viewModelScope.launch {
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

    private fun currentTheme(): AppTheme {
        return _uiState.value.selectedTheme
    }

    fun onEvent(event: ThemeSettingsEvent) {
        when (event) {
            is ThemeSettingsEvent.SelectTheme -> selectTheme(event.theme)
            is ThemeSettingsEvent.SelectFont -> selectFont(event.font)
            is ThemeSettingsEvent.SelectBadgeColorStyle -> selectBadgeColorStyle(event.style)
        }
    }

    private fun selectTheme(theme: AppTheme) {
        if (currentTheme() == theme) return
        viewModelScope.launch {
            themeDataStore.setTheme(theme)
        }
    }

    private fun selectFont(font: AppFont) {
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
}
