package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.SeasonalEffectsConfig
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
    }

    private val enabledKey =
        booleanPreferencesKey("enabled")

    private fun store(
        profileId: Int =
            profileManager.activeProfileId.value
    ) =
        factory.get(
            profileId,
            FEATURE
        )

    /*
     * Every former lab/tuning preference is intentionally ignored.
     *
     * The only persisted choice is whether Seasonal Effects are enabled.
     * The visual configuration is the approved fixed configuration in
     * SeasonalEffectsConfig.Recommended.
     */
    val config: Flow<SeasonalEffectsConfig> =
        profileManager.activeProfileId
            .flatMapLatest { profileId ->
                factory.get(
                    profileId,
                    FEATURE
                ).data.map { prefs ->
                    SeasonalEffectsConfig.Recommended.copy(
                        enabled =
                            prefs[enabledKey]
                                ?: SeasonalEffectsConfig
                                    .Recommended
                                    .enabled
                    )
                }
            }

    suspend fun setEnabled(value: Boolean) {
        store().edit {
            it[enabledKey] = value
        }
    }
}
