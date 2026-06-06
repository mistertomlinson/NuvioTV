package com.nuvio.tv.data.local

import com.nuvio.tv.core.streams.StreamBadgeRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StreamBadgeSettingsDataStore @Inject constructor() {
    val settings: Flow<com.nuvio.tv.core.streams.StreamBadgeSettings> = flowOf(com.nuvio.tv.core.streams.StreamBadgeSettings())
}
