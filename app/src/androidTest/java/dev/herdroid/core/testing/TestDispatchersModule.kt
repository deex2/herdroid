package dev.herdroid.core.testing

import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import dev.herdroid.core.common.Dispatcher
import dev.herdroid.core.common.DispatchersModule
import dev.herdroid.core.common.HerdroidDispatchers
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [DispatchersModule::class],
)
@OptIn(ExperimentalCoroutinesApi::class)
object TestDispatchersModule {
    @Provides
    @Singleton
    fun testDispatcher(): TestDispatcher = UnconfinedTestDispatcher()

    @Provides
    @Dispatcher(HerdroidDispatchers.IO)
    fun io(dispatcher: TestDispatcher): CoroutineDispatcher = dispatcher

    @Provides
    @Dispatcher(HerdroidDispatchers.Default)
    fun defaultDispatcher(dispatcher: TestDispatcher): CoroutineDispatcher = dispatcher
}
