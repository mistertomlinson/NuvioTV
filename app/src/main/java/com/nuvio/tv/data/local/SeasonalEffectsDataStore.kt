package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.HolidayMode
import com.nuvio.tv.domain.model.HolidayTheme
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

    private val legacyEnabledKey =
        booleanPreferencesKey("enabled")

    private val halloweenModeKey =
        stringPreferencesKey(
            "halloween_mode"
        )

    private val christmasModeKey =
        stringPreferencesKey(
            "christmas_mode"
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

    private fun resolveModes(
        prefs: Preferences
    ): Pair<HolidayMode, HolidayMode> {
        val map = prefs.asMap()

        val hasHalloweenMode =
            map.containsKey(
                halloweenModeKey
            )

        val hasChristmasMode =
            map.containsKey(
                christmasModeKey
            )

        if (
            !hasHalloweenMode &&
            !hasChristmasMode
        ) {
            val hasLegacyChoice =
                map.containsKey(
                    legacyEnabledKey
                )

            if (hasLegacyChoice) {
                return if (
                    prefs[legacyEnabledKey] == true
                ) {
                    HolidayMode.OFF to
                        HolidayMode.ON
                } else {
                    HolidayMode.OFF to
                        HolidayMode.OFF
                }
            }

            return HolidayMode.AUTOMATIC to
                HolidayMode.AUTOMATIC
        }

        return decodeMode(
            prefs[halloweenModeKey],
            HolidayMode.AUTOMATIC
        ) to decodeMode(
            prefs[christmasModeKey],
            HolidayMode.AUTOMATIC
        )
    }

    val config:
        Flow<SeasonalEffectsConfig> =
        profileManager.activeProfileId
            .flatMapLatest { profileId ->
                factory.get(
                    profileId,
                    FEATURE
                ).data.map { prefs ->
                    val modes =
                        resolveModes(prefs)

                    SeasonalEffectsConfig
                        .Recommended
                        .copy(
                            halloweenMode =
                                modes.first,
                            christmasMode =
                                modes.second
                        )
                }
            }

    suspend fun setHolidayMode(
        holiday: HolidayTheme,
        mode: HolidayMode
    ) {
        store().edit { prefs ->
            val current =
                resolveModes(prefs)

            var halloween =
                current.first

            var christmas =
                current.second

            when (holiday) {
                HolidayTheme.HALLOWEEN -> {
                    halloween = mode

                    if (
                        mode ==
                        HolidayMode.ON
                    ) {
                        christmas =
                            HolidayMode.OFF
                    }
                }

                HolidayTheme.CHRISTMAS -> {
                    christmas = mode

                    if (
                        mode ==
                        HolidayMode.ON
                    ) {
                        halloween =
                            HolidayMode.OFF
                    }
                }
            }

            prefs[halloweenModeKey] =
                halloween.name

            prefs[christmasModeKey] =
                christmas.name

            prefs.remove(
                legacyEnabledKey
            )
        }
    }
}
