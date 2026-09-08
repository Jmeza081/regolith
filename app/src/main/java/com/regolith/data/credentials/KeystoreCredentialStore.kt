package com.regolith.data.credentials

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AES-GCM in DataStore, key in the Android Keystore.
 *
 * The Keystore is hardware-backed storage the OS manages: we get a key
 * handle and can encrypt/decrypt with it, but the key bytes are never
 * readable by the app (or by anything that copies the app's files). There
 * is one key for all servers; each value is `base64(iv || ciphertext)`.
 */
@Singleton
class KeystoreCredentialStore @Inject constructor(
    private val store: DataStore<Preferences>,
) : CredentialStore {

    override suspend fun get(serverId: Long): String? = withContext(Dispatchers.IO) {
        val blob = store.data.first()[keyFor(serverId)] ?: return@withContext null
        decrypt(Base64.decode(blob, Base64.NO_WRAP))
    }

    override suspend fun put(serverId: Long, password: String) = withContext(Dispatchers.IO) {
        val blob = Base64.encodeToString(encrypt(password), Base64.NO_WRAP)
        store.edit { it[keyFor(serverId)] = blob }
        Unit
    }

    override suspend fun clear(serverId: Long) {
        store.edit { it.remove(keyFor(serverId)) }
    }

    private fun keyFor(serverId: Long) = stringPreferencesKey("credential_$serverId")

    private fun encrypt(plain: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return iv + ct
    }

    private fun decrypt(blob: ByteArray): String {
        val iv = blob.copyOfRange(0, IV_BYTES)
        val ct = blob.copyOfRange(IV_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "regolith_credentials_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
