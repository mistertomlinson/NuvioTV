package com.nuvio.tv.core.di

import com.nuvio.tv.core.tracking.TrackingHistoryWriter
import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProvider
import com.nuvio.tv.data.repository.TraktTrackingHistoryWriter
import com.nuvio.tv.data.repository.TraktTrackingProgressProvider
import com.nuvio.tv.data.repository.TraktTrackingProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Registers the existing Trakt implementation in the provider-neutral
 * progress registry. Simkl bindings remain in SimklAuthModule.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TraktTrackingModule {

    @Binds
    @IntoSet
    abstract fun bindTraktTrackingProvider(
        provider: TraktTrackingProvider
    ): TrackingProvider

    @Binds
    @IntoSet
    abstract fun bindTraktTrackingProgressProvider(
        provider: TraktTrackingProgressProvider
    ): TrackingProgressProvider

    @Binds
    @IntoSet
    abstract fun bindTraktTrackingHistoryWriter(
        writer: TraktTrackingHistoryWriter
    ): TrackingHistoryWriter

}
