package com.fenix.ia.data.repository

import com.fenix.ia.data.local.security.SecureApiManager
import com.fenix.ia.domain.model.ApiProvider
import com.fenix.ia.domain.repository.ApiKeyRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApiKeyRepositoryImpl @Inject constructor(
    private val secureApiManager: SecureApiManager
) : ApiKeyRepository {

    override suspend fun saveApiKey(provider: ApiProvider, rawKey: String) =
        secureApiManager.saveApiKey(provider, rawKey)

    override suspend fun getDecryptedKey(provider: ApiProvider): String? =
        secureApiManager.getDecryptedKey(provider)

    override suspend fun deleteApiKey(provider: ApiProvider) =
        secureApiManager.deleteApiKey(provider)

    override fun getConfiguredProviders(): Flow<List<ApiProvider>> =
        secureApiManager.getConfiguredProviders()
}
