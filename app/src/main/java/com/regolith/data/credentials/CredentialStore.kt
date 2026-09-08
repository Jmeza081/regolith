package com.regolith.data.credentials

/**
 * Where server passwords live. Guardrail G6: encrypted with a key that
 * never leaves the Android Keystore, so a copied database or backup holds
 * only ciphertext. The `servers` table has no password column on purpose.
 */
interface CredentialStore {
    suspend fun get(serverId: Long): String?
    suspend fun put(serverId: Long, password: String)
    suspend fun clear(serverId: Long)
}
