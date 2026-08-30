package dev.herdroid.core.keyvault.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.herdroid.core.keyvault.KeyVault
import dev.herdroid.core.keyvault.SshKeyVault

@Module
@InstallIn(SingletonComponent::class)
abstract class KeyVaultModule {
    @Binds
    internal abstract fun bindKeyVault(implementation: SshKeyVault): KeyVault
}
