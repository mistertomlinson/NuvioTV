package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.SeasonalEffectsConfig
import com.nuvio.tv.domain.model.SnowBlur
import com.nuvio.tv.domain.model.SnowOpacity
import com.nuvio.tv.domain.model.SnowSizeProfile
import com.nuvio.tv.domain.model.SnowStartupMode
import com.nuvio.tv.domain.model.SnowSway
import com.nuvio.tv.domain.model.SnowWind
import com.nuvio.tv.domain.model.SnowfallSpeed
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

@Singleton
class SeasonalEffectsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "seasonal_effects"
        private val particleCounts = setOf(30, 45, 60, 80, 100, 120)
        private val targetFpsValues = setOf(15, 24, 30, 60)
    }

    private val enabledKey = booleanPreferencesKey("enabled")
    private val startupModeKey = stringPreferencesKey("startup_mode")
    private val particleCountKey = intPreferencesKey("particle_count")
    private val targetFpsKey = intPreferencesKey("target_fps")
    private val snowfallSpeedKey = stringPreferencesKey("snowfall_speed")
    private val sizeProfileKey = stringPreferencesKey("size_profile")
    private val windKey = stringPreferencesKey("wind")
    private val swayKey = stringPreferencesKey("sway")
    private val opacityKey = stringPreferencesKey("opacity")
    private val depthLayersKey = intPreferencesKey("depth_layers")
    private val reduceDensityWhileScrollingKey = booleanPreferencesKey("reduce_density_scrolling")
    private val reduceFpsWhileScrollingKey = booleanPreferencesKey("reduce_fps_scrolling")
    private val freezeWhileScrollingKey = booleanPreferencesKey("freeze_scrolling")
    private val pauseDuringHeroTrailerKey = booleanPreferencesKey("pause_hero_trailer")
    private val blurKey = stringPreferencesKey("blur")

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    val config: Flow<SeasonalEffectsConfig> =
        profileManager.activeProfileId.flatMapLatest { profileId ->
            factory.get(profileId, FEATURE).data.map(::decode)
        }

    private fun decode(prefs: Preferences): SeasonalEffectsConfig {
        val defaults = SeasonalEffectsConfig.Recommended
        return SeasonalEffectsConfig(
            enabled = prefs[enabledKey] ?: defaults.enabled,
            startupMode = enumValueOrDefault(
                prefs[startupModeKey], defaults.startupMode
            ),
            particleCount = (prefs[particleCountKey] ?: defaults.particleCount)
                .takeIf(particleCounts::contains) ?: defaults.particleCount,
            targetFps = (prefs[targetFpsKey] ?: defaults.targetFps)
                .takeIf(targetFpsValues::contains) ?: defaults.targetFps,
            snowfallSpeed = enumValueOrDefault(
                prefs[snowfallSpeedKey], defaults.snowfallSpeed
            ),
            sizeProfile = enumValueOrDefault(
                prefs[sizeProfileKey], defaults.sizeProfile
            ),
            wind = enumValueOrDefault(prefs[windKey], defaults.wind),
            sway = enumValueOrDefault(prefs[swayKey], defaults.sway),
            opacity = enumValueOrDefault(prefs[opacityKey], defaults.opacity),
            depthLayers = (prefs[depthLayersKey] ?: defaults.depthLayers).coerceIn(1, 3),
            reduceDensityWhileScrolling = prefs[reduceDensityWhileScrollingKey]
                ?: defaults.reduceDensityWhileScrolling,
            reduceFpsWhileScrolling = prefs[reduceFpsWhileScrollingKey]
                ?: defaults.reduceFpsWhileScrolling,
            freezeWhileScrolling = prefs[freezeWhileScrollingKey]
                ?: defaults.freezeWhileScrolling,
            pauseDuringHeroTrailer = prefs[pauseDuringHeroTrailerKey]
                ?: defaults.pauseDuringHeroTrailer,
            blur = enumValueOrDefault(prefs[blurKey], defaults.blur)
        )
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(
        raw: String?,
        default: T
    ): T = raw?.let { value ->
        enumValues<T>().firstOrNull { it.name == value }
    } ?: default

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        store().edit(block)
    }

    suspend fun setEnabled(value: Boolean) = edit { it[enabledKey] = value }
    suspend fun setStartupMode(value: SnowStartupMode) = edit { it[startupModeKey] = value.name }
    suspend fun setParticleCount(value: Int) = edit {
        it[particleCountKey] = value.takeIf(particleCounts::contains) ?: 60
    }
    suspend fun setTargetFps(value: Int) = edit {
        it[targetFpsKey] = value.takeIf(targetFpsValues::contains) ?: 30
    }
    suspend fun setSnowfallSpeed(value: SnowfallSpeed) = edit { it[snowfallSpeedKey] = value.name }
    suspend fun setSizeProfile(value: SnowSizeProfile) = edit { it[sizeProfileKey] = value.name }
    suspend fun setWind(value: SnowWind) = edit { it[windKey] = value.name }
    suspend fun setSway(value: SnowSway) = edit { it[swayKey] = value.name }
    suspend fun setOpacity(value: SnowOpacity) = edit { it[opacityKey] = value.name }
    suspend fun setDepthLayers(value: Int) = edit { it[depthLayersKey] = value.coerceIn(1, 3) }
    suspend fun setReduceDensityWhileScrolling(value: Boolean) = edit {
        it[reduceDensityWhileScrollingKey] = value
    }
    suspend fun setReduceFpsWhileScrolling(value: Boolean) = edit {
        it[reduceFpsWhileScrollingKey] = value
    }
    suspend fun setFreezeWhileScrolling(value: Boolean) = edit {
        it[freezeWhileScrollingKey] = value
    }
    suspend fun setPauseDuringHeroTrailer(value: Boolean) = edit {
        it[pauseDuringHeroTrailerKey] = value
    }
    suspend fun setBlur(value: SnowBlur) = edit { it[blurKey] = value.name }

    suspend fun resetRecommended() {
        val d = SeasonalEffectsConfig.Recommended
        store().edit { prefs ->
            // Preserve the master switch so tuning reset does not unexpectedly
            // turn a currently visible test effect off.
            prefs[startupModeKey] = d.startupMode.name
            prefs[particleCountKey] = d.particleCount
            prefs[targetFpsKey] = d.targetFps
            prefs[snowfallSpeedKey] = d.snowfallSpeed.name
            prefs[sizeProfileKey] = d.sizeProfile.name
            prefs[windKey] = d.wind.name
            prefs[swayKey] = d.sway.name
            prefs[opacityKey] = d.opacity.name
            prefs[depthLayersKey] = d.depthLayers
            prefs[reduceDensityWhileScrollingKey] = d.reduceDensityWhileScrolling
            prefs[reduceFpsWhileScrollingKey] = d.reduceFpsWhileScrolling
            prefs[freezeWhileScrollingKey] = d.freezeWhileScrolling
            prefs[pauseDuringHeroTrailerKey] = d.pauseDuringHeroTrailer
            prefs[blurKey] = d.blur.name
        }
    }
}
