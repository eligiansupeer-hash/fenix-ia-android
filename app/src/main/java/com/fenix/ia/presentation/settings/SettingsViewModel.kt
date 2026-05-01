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

    private val MODEL_DOWNLOAD_URL =
        "https://storage.googleapis.com/mediapipe-models/llm_inference/gemma-2b-it-gpu-int4/float32/1/gemma-2b-it-gpu-int4.bin"

    init {
        viewModelScope.launch {
            apiKeyRepository.getConfiguredProviders().collect { providers ->
                _uiState.update { it.copy(configuredProviders = providers) }
            }
        }
        viewModelScope.launch {
            val capable    = localLlmEngine.isCapable()
            val downloaded = localLlmEngine.isModelDownloaded()
            _uiState.update { it.copy(isLocalCapable = capable, isModelDownloaded = downloaded) }
        }
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
                    localError = if (!success)
                        "Error al descargar el modelo. Revisá tu conexión e intentá de nuevo."
                    else null
                )
            }
        }
    }

    fun activateModel() {
        viewModelScope.launch {
            _uiState.update { it.copy(localError = null) }
            val ok = localLlmEngine.initialize()
            if (!ok) {
                _uiState.update {
                    it.copy(localError = "No se pudo inicializar el modelo. Verificá que el dispositivo tenga >= 4 GB RAM.")
                }
            }
        }
    }

    /**
     * Libera el modelo explícitamente (usuario desactiva IA Local).
     * El ciclo de vida (onStop) también lo libera automáticamente al ir a background.
     */
    fun releaseModel() {
        localLlmEngine.release()
    }

    override fun onCleared() {
        super.onCleared()
        // El ciclo de vida del modelo es gestionado por ProcessLifecycleOwner en LocalLlmEngine.
        // No se libera aquí para que el modelo siga activo en otras pantallas de la app.
    }
}
