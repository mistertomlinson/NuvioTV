package com.nuvio.tv.core.di

import com.nuvio.tv.ui.components.HomeTrailerPlayerHolder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext

@Module
@InstallIn(SingletonComponent::class)
object PlayerModule {

    @Provides
    @Singleton
    fun provideHomeTrailerPlayerHolder(
        @ApplicationContext context: Context
    ): HomeTrailerPlayerHolder = HomeTrailerPlayerHolder(context)
}
