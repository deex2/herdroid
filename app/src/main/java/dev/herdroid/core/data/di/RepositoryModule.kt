package dev.herdroid.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.herdroid.core.data.ConnectionRouteRepository
import dev.herdroid.core.data.KeyMetadataRepository
import dev.herdroid.core.data.RouteRepository
import dev.herdroid.core.data.RouteStore

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    internal abstract fun bindRouteRepository(implementation: RouteStore): RouteRepository

    @Binds
    internal abstract fun bindConnectionRouteRepository(implementation: RouteStore): ConnectionRouteRepository

    @Binds
    internal abstract fun bindKeyMetadataRepository(implementation: RouteStore): KeyMetadataRepository
}
