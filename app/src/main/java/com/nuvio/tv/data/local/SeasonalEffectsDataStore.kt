package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.HolidayMode
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
        private const val FEATURE =
            "seasonal_effects"
    }

    private val holidayModeKey =
        stringPreferencesKey(
            "holiday_mode"
        )

    private val oldHalloweenModeKey =
        stringPreferencesKey(
            "halloween_mode"
        )

    private val oldChristmasModeKey =
        stringPreferencesKey(
            "christmas_mode"
        )

    private val legacyEnabledKey =
        booleanPreferencesKey(
            "enabled"
        )

    private fun store(
        profileId: Int =
            profileManager.activeProfileId.value
    ) =
        factory.get(
            profileId,
            FEATURE
        )

    private fun decodeMode(
        raw: String?,
        fallback: HolidayMode
    ): HolidayMode =
        runCatching {
            raw?.let {
                HolidayMode.valueOf(it)
            }
        }.getOrNull() ?: fallback

    private fun resolveMode(
        prefs: Preferences
    ): HolidayMode {
        val map =
            prefs.asMap()

        if (
            map.containsKey(
                holidayModeKey
            )
        ) {
            return decodeMode(
                prefs[holidayModeKey],
                HolidayMode.AUTOMATIC
            )
        }

        val hasOldHalloween =
            map.containsKey(
                oldHalloweenModeKey
            )

        val hasOldChristmas =
            map.containsKey(
                oldChristmasModeKey
            )

        if (
            hasOldHalloween ||
            hasOldChristmas
        ) {
            val halloween =
                decodeMode(
                    prefs[oldHalloweenModeKey],
                    HolidayMode.AUTOMATIC
                )

            val christmas =
                decodeMode(
                    prefs[oldChristmasModeKey],
                    HolidayMode.AUTOMATIC
                )

            return when {
                christmas !=
                    HolidayMode.OFF ->
                    christmas

                halloween ==
                    HolidayMode.ON ->
                    HolidayMode.AUTOMATIC

                hasOldChristmas ->
                    HolidayMode.OFF

                else ->
                    HolidayMode.AUTOMATIC
            }
        }

        if (
            map.containsKey(
                legacyEnabledKey
            )
        ) {
            return if (
                prefs[legacyEnabledKey] ==
                true
            ) {
                HolidayMode.ON
            } else {
                HolidayMode.OFF
            }
        }

        return HolidayMode.AUTOMATIC
    }

    val config:
        Flow<SeasonalEffectsConfig> =
        profileManager.activeProfileId
            .flatMapLatest {
                    profileId ->

                factory.get(
                    profileId,
                    FEATURE
                ).data.map {
                        prefs ->

                    SeasonalEffectsConfig
                        .Recommended
                        .copy(
                            holidayMode =
                                resolveMode(
                                    prefs
                                )
                        )
                }
            }

    suspend fun setHolidayMode(
        mode: HolidayMode
    ) {
        store().edit {
                prefs ->

            prefs[holidayModeKey] =
                mode.name

            prefs.remove(
                oldHalloweenModeKey
            )

            prefs.remove(
                oldChristmasModeKey
            )

            prefs.remove(
                legacyEnabledKey
            )
        }
    }
}
