package dev.herdroid.core.data.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.herdroid.core.common.Dispatcher
import dev.herdroid.core.common.HerdroidDispatchers
import dev.herdroid.core.data.ProcessDatabaseState
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    internal fun provideDatabaseState(
        @ApplicationContext context: Context,
        @Dispatcher(HerdroidDispatchers.IO) ioDispatcher: CoroutineDispatcher,
    ): ProcessDatabaseState = ProcessDatabaseState.open(context, ioDispatcher)
}
