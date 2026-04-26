package com.fenix.ia.data.local.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fenix.ia.domain.model.ApiProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

private val Context.secureDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "fenix_secure_prefs"
)

@Singleton
class SecureApiManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val KEYSTORE_ALIAS = "fenix_api_key_master"
        private const val KEY_PREFIX = "api_key_"
    }

    private val dataStore: DataStore<Preferences> = context.secureDataStore

    // Obtiene o crea la clave AES-256-GCM respaldada por TEE
    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        return if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            (keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        } else {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
            )
            keyGenerator.init(
                KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
            )
            keyGenerator.generateKey()
        }
    }

    suspend fun saveApiKey(provider: ApiProvider, rawKey: String) {
        // INVARIANTE: rawKey NUNCA se escribe en DataStore sin cifrar
        require(rawKey.isNotBlank()) { "API key no puede estar vacía" }

        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)

        val iv = cipher.iv
        val encryptedBytes = cipher.doFinal(rawKey.toByteArray(Charsets.UTF_8))

        // Almacena IV + ciphertext como Base64
        val payload = Base64.encodeToString(iv + encryptedBytes, Base64.DEFAULT)

        dataStore.edit { prefs ->
            prefs[stringPreferencesKey(KEY_PREFIX + provider.name)] = payload
        }
    }

    suspend fun getDecryptedKey(provider: ApiProvider): String? {
        val payload = dataStore.data.map { prefs ->
            prefs[stringPreferencesKey(KEY_PREFIX + provider.name)]
        }.firstOrNull() ?: return null

        return try {
            val decoded = Base64.decode(payload, Base64.DEFAULT)
            val iv = decoded.sliceArray(0 until 12)
            val ciphertext = decoded.sliceArray(12 until decoded.size)

            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))

            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            // Log del error pero NO lanzar — evita crash si el keystore fue reseteado
            null
        }
    }

    suspend fun deleteApiKey(provider: ApiProvider) {
        dataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey(KEY_PREFIX + provider.name))
        }
    }

    fun getConfiguredProviders(): Flow<List<ApiProvider>> = dataStore.data.map { prefs ->
        ApiProvider.values().filter { provider ->
            prefs.contains(stringPreferencesKey(KEY_PREFIX + provider.name))
        }
    }
}
