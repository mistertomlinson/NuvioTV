package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.SeasonalEffectsDataStore
import com.nuvio.tv.domain.model.SeasonalEffectsConfig
import com.nuvio.tv.domain.model.SnowBlur
import com.nuvio.tv.domain.model.SnowOpacity
import com.nuvio.tv.domain.model.SnowSizeProfile
import com.nuvio.tv.domain.model.SnowStartupMode
import com.nuvio.tv.domain.model.SnowSway
import com.nuvio.tv.domain.model.SnowWind
import com.nuvio.tv.domain.model.SnowfallSpeed
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
    val config: StateFlow<SeasonalEffectsConfig> = dataStore.config.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SeasonalEffectsConfig.Recommended
    )

    fun setEnabled(value: Boolean) = launch { dataStore.setEnabled(value) }
    fun setStartupMode(value: SnowStartupMode) = launch { dataStore.setStartupMode(value) }
    fun setParticleCount(value: Int) = launch { dataStore.setParticleCount(value) }
    fun setTargetFps(value: Int) = launch { dataStore.setTargetFps(value) }
    fun setSnowfallSpeed(value: SnowfallSpeed) = launch { dataStore.setSnowfallSpeed(value) }
    fun setSizeProfile(value: SnowSizeProfile) = launch { dataStore.setSizeProfile(value) }
    fun setWind(value: SnowWind) = launch { dataStore.setWind(value) }
    fun setSway(value: SnowSway) = launch { dataStore.setSway(value) }
    fun setOpacity(value: SnowOpacity) = launch { dataStore.setOpacity(value) }
    fun setDepthLayers(value: Int) = launch { dataStore.setDepthLayers(value) }
    fun setReduceDensityWhileScrolling(value: Boolean) = launch {
        dataStore.setReduceDensityWhileScrolling(value)
    }
    fun setReduceFpsWhileScrolling(value: Boolean) = launch {
        dataStore.setReduceFpsWhileScrolling(value)
    }
    fun setFreezeWhileScrolling(value: Boolean) = launch {
        dataStore.setFreezeWhileScrolling(value)
    }
    fun setPauseDuringHeroTrailer(value: Boolean) = launch {
        dataStore.setPauseDuringHeroTrailer(value)
    }
    fun setBlur(value: SnowBlur) = launch { dataStore.setBlur(value) }
    fun resetRecommended() = launch { dataStore.resetRecommended() }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
