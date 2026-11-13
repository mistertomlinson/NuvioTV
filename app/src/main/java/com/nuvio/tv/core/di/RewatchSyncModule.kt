package com.nuvio.tv.core.di

import com.nuvio.tv.data.local.AndroidRewatchSyncRuntime
import com.nuvio.tv.data.local.RewatchSyncFacade
import com.nuvio.tv.domain.rewatch.RewatchCommittedSaveSink
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * PlayerViewModel sees only the public, replay-only sink contract. Hilt
 * constructs this provider lazily if and only if an explicitly validated
 * build enables the dormant cross-device rollout flag.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object RewatchSyncModule {
    @Provides
    @Singleton
    fun provideRewatchCommittedSaveSink(
        runtime: AndroidRewatchSyncRuntime
    ): RewatchCommittedSaveSink = runtime.committedSaveSink

    @Provides
    @Singleton
    fun provideRewatchSyncFacade(
        runtime: AndroidRewatchSyncRuntime
    ): RewatchSyncFacade = runtime
}
