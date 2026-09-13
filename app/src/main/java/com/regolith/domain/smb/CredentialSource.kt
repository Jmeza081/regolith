package com.regolith.domain.smb

/**
 * "The credentials for this server", and nothing else about servers. What
 * a component needs when it must open a share but has no business with
 * how passwords are kept; `SourceRepository` is the one implementation.
 */
interface CredentialSource {
    suspend fun credentialsFor(serverId: Long): SmbCredentials
}
