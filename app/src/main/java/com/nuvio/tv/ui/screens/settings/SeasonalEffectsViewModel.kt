package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.SeasonalEffectsDataStore
import com.nuvio.tv.domain.model.SeasonalEffectsConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SeasonalEffectsViewModel @Inject constructor(
    private val dataStore: SeasonalEffectsDataStore
) : ViewModel() {
    val config: StateFlow<SeasonalEffectsConfig> =
        dataStore.config.stateIn(
            scope = viewModelScope,
            started =
                SharingStarted.WhileSubscribed(
                    5_000
                ),
            initialValue =
                SeasonalEffectsConfig.Recommended
        )

    fun setEnabled(value: Boolean) {
        viewModelScope.launch {
            dataStore.setEnabled(value)
        }
    }
}
