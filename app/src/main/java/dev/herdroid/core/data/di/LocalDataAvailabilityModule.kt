package dev.herdroid.core.data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.herdroid.core.data.LocalDataAvailability
import dev.herdroid.core.data.ProcessDatabaseState
import kotlinx.coroutines.flow.StateFlow

@Module
@InstallIn(SingletonComponent::class)
object LocalDataAvailabilityModule {
    @Provides
    internal fun provideLocalDataAvailability(state: ProcessDatabaseState): StateFlow<LocalDataAvailability> =
        state.availability
}
