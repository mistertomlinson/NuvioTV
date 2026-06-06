package com.nuvio.tv.core.streams

import com.nuvio.tv.domain.model.AddonStreams
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StreamBadgePresentation @Inject constructor() {
    suspend fun apply(groups: List<AddonStreams>): List<AddonStreams> = groups
}
