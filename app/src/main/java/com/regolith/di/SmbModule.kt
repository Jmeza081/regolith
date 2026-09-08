package com.regolith.di

import com.regolith.data.credentials.CredentialStore
import com.regolith.data.credentials.KeystoreCredentialStore
import com.regolith.data.smb.JcifsGateway
import com.regolith.domain.smb.SmbGateway
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Interface -> implementation bindings. Tests replace these with fakes;
 * production gets jcifs-ng and the Keystore.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SmbModule {
    @Binds abstract fun bindSmbGateway(impl: JcifsGateway): SmbGateway
    @Binds abstract fun bindCredentialStore(impl: KeystoreCredentialStore): CredentialStore
}
