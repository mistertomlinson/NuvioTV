package com.nuvio.tv.domain.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Video
import kotlinx.coroutines.flow.Flow

interface MetaRepository {
    fun getMeta(
        addonBaseUrl: String,
        type: String,
        id: String
    ): Flow<NetworkResult<Meta>>
    
    fun getMetaFromAllAddons(
        type: String,
        id: String
    ): Flow<NetworkResult<Meta>>

    fun getMetaFromPrimaryAddon(
        type: String,
        id: String
    ): Flow<NetworkResult<Meta>>

    /**
     * Collect Season 0 videos from every installed metadata addon that can
     * describe this series. Unlike getMetaFromAllAddons(), this intentionally
     * does not stop at the first successful metadata provider.
     *
     * Used only by the special-episode stream resolver.
     */
    suspend fun getSeasonZeroVideosFromAllAddons(
        type: String,
        id: String
    ): List<Video>
    
    fun clearCache()
}
