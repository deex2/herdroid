package dev.herdroid.session.impl

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.herdroid.session.api.ConnectionSession
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SessionModule {
    @Binds
    @Singleton
    internal abstract fun bindConnectionSession(implementation: ProcessConnectionSession): ConnectionSession
}
