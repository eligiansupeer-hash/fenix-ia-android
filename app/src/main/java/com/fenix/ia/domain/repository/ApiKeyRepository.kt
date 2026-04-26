package com.fenix.ia.domain.repository

import com.fenix.ia.domain.model.ApiProvider
import kotlinx.coroutines.flow.Flow

interface ApiKeyRepository {
    suspend fun saveApiKey(provider: ApiProvider, rawKey: String)  // Cifra internamente
    suspend fun getDecryptedKey(provider: ApiProvider): String?
    suspend fun deleteApiKey(provider: ApiProvider)
    fun getConfiguredProviders(): Flow<List<ApiProvider>>
}
