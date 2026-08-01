package com.nuvio.tv.core.di

import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.data.simkl.AndroidSimklAuthStorage
import com.nuvio.tv.data.simkl.AndroidSimklSyncStorage
import com.nuvio.tv.data.simkl.SimklApiSyncRemote
import com.nuvio.tv.data.simkl.SimklSyncRemote
import com.nuvio.tv.data.simkl.SimklSyncStorage
import com.nuvio.tv.data.simkl.SimklAuthStorage
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SimklAuthModule {

    @Binds
    @Singleton
    abstract fun bindSimklAuthStorage(
        storage: AndroidSimklAuthStorage
    ): SimklAuthStorage

    @Binds
    @IntoSet
    abstract fun bindSimklProfileScopedCredentialStore(
        storage: AndroidSimklAuthStorage
    ): ProfileScopedCredentialStore

    @Binds
    @Singleton
    abstract fun bindSimklSyncStorage(
        storage: AndroidSimklSyncStorage
    ): SimklSyncStorage

    @Binds
    @Singleton
    abstract fun bindSimklSyncRemote(
        remote: SimklApiSyncRemote
    ): SimklSyncRemote
}
