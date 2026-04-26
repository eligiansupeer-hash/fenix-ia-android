package com.fenix.ia.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fenix.ia.domain.model.ApiProvider
import com.fenix.ia.domain.repository.ApiKeyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val configuredProviders: List<ApiProvider> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val apiKeyRepository: ApiKeyRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            apiKeyRepository.getConfiguredProviders().collect { providers ->
                _uiState.update { it.copy(configuredProviders = providers) }
            }
        }
    }

    fun saveKey(provider: ApiProvider, rawKey: String) {
        viewModelScope.launch {
            try {
                apiKeyRepository.saveApiKey(provider, rawKey)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun deleteKey(provider: ApiProvider) {
        viewModelScope.launch {
            apiKeyRepository.deleteApiKey(provider)
        }
    }
}
