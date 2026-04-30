package com.fenix.ia.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fenix.ia.domain.model.ApiProvider
import com.fenix.ia.domain.repository.ApiKeyRepository
import com.fenix.ia.local.LocalLlmEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val configuredProviders: List<ApiProvider> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    // IA Local (Fase 5)
    val isLocalCapable: Boolean = false,
    val isModelDownloaded: Boolean = false,
    val isModelReady: Boolean = false,
    val downloadProgress: Float = 0f,
    val isDownloading: Boolean = false,
    val localError: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val apiKeyRepository: ApiKeyRepository,
    private val localLlmEngine: LocalLlmEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    // URL del modelo en GitHub Releases (se puede actualizar vía OTA)
    private val MODEL_DOWNLOAD_URL =
        "https://github.com/eligiansupeer-hash/fenix-ia-android/releases/download/model-v1/llama3_2_1b_q4.task"

    init {
        // Providers configurados
        viewModelScope.launch {
            apiKeyRepository.getConfiguredProviders().collect { providers ->
                _uiState.update { it.copy(configuredProviders = providers) }
            }
        }
        // Estado de IA Local
        viewModelScope.launch {
            val capable = localLlmEngine.isCapable()
            val downloaded = localLlmEngine.isModelDownloaded()
            _uiState.update {
                it.copy(
                    isLocalCapable = capable,
                    isModelDownloaded = downloaded
                )
            }
        }
        // Observar isReady en tiempo real
        viewModelScope.launch {
            localLlmEngine.isReady.collect { ready ->
                _uiState.update { it.copy(isModelReady = ready) }
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

    /** Descarga el modelo Llama 3.2 1B Q4 desde GitHub Releases (~700 MB). */
    fun downloadModel() {
        viewModelScope.launch {
            _uiState.update { it.copy(isDownloading = true, downloadProgress = 0f, localError = null) }
            val success = localLlmEngine.downloadModel(MODEL_DOWNLOAD_URL) { progress ->
                _uiState.update { it.copy(downloadProgress = progress) }
            }
            _uiState.update {
                it.copy(
                    isDownloading = false,
                    isModelDownloaded = success,
                    localError = if (!success) "Error al descargar el modelo. Revisá tu conexión." else null
                )
            }
        }
    }

    /** Inicializa el modelo descargado en memoria nativa (MediaPipe). */
    fun activateModel() {
        viewModelScope.launch {
            _uiState.update { it.copy(localError = null) }
            val ok = localLlmEngine.initialize()
            if (!ok) {
                _uiState.update {
                    it.copy(localError = "No se pudo inicializar el modelo. Verificá RAM disponible.")
                }
            }
        }
    }

    /** Libera el modelo de la memoria nativa para reducir RAM. */
    fun releaseModel() {
        localLlmEngine.release()
    }

    override fun onCleared() {
        super.onCleared()
        // No liberamos el modelo al salir de Settings — sigue activo en otras pantallas
    }
}
