package com.regolith.desktop

import com.regolith.data.credentials.CredentialStore

/** The Keychain, as a map. */
class InMemoryCredentialStore : CredentialStore {
    val passwords = mutableMapOf<Long, String>()
    override suspend fun get(serverId: Long): String? = passwords[serverId]
    override suspend fun put(serverId: Long, password: String) { passwords[serverId] = password }
    override suspend fun clear(serverId: Long) { passwords.remove(serverId) }
}
