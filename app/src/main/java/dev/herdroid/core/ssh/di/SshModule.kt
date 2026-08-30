package dev.herdroid.core.ssh.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.herdroid.core.ssh.HardwareKeyOperations
import dev.herdroid.core.ssh.keys.HardwareSshKeyStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SshModule {
    @Binds
    @Singleton
    internal abstract fun bindHardwareKeyOperations(implementation: HardwareSshKeyStore): HardwareKeyOperations
}
