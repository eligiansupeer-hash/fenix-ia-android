package com.fenix.ia.updater

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UpdateUiState(
    val isChecking: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Int = 0,
    val updateAvailable: UpdateResult.UpdateAvailable? = null,
    val isUpToDate: Boolean = false,
    val noReleases: Boolean = false,
    val error: String? = null,
    val installReady: Boolean = false
)

sealed class UpdateIntent {
    object CheckForUpdate      : UpdateIntent()
    object DownloadAndInstall  : UpdateIntent()
    object DismissError        : UpdateIntent()
    object DismissUpdateDialog : UpdateIntent()
}

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updateChecker: UpdateChecker
) : ViewModel() {

    private val _uiState = MutableStateFlow(UpdateUiState())
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    fun processIntent(intent: UpdateIntent) {
        when (intent) {
            is UpdateIntent.CheckForUpdate      -> checkForUpdate()
            is UpdateIntent.DownloadAndInstall  -> downloadAndInstall()
            is UpdateIntent.DismissError        -> _uiState.update { it.copy(error = null) }
            is UpdateIntent.DismissUpdateDialog -> _uiState.update { it.copy(updateAvailable = null) }
        }
    }

    private fun checkForUpdate() {
        viewModelScope.launch {
            _uiState.update { it.copy(isChecking = true, error = null, isUpToDate = false, noReleases = false) }
            when (val result = updateChecker.checkForUpdate()) {
                is UpdateResult.UpdateAvailable -> _uiState.update { it.copy(isChecking = false, updateAvailable = result) }
                is UpdateResult.UpToDate        -> _uiState.update { it.copy(isChecking = false, isUpToDate = true) }
                is UpdateResult.NoReleases      -> _uiState.update { it.copy(isChecking = false, noReleases = true) }
                is UpdateResult.Error           -> _uiState.update { it.copy(isChecking = false, error = result.message) }
            }
        }
    }

    private fun downloadAndInstall() {
        viewModelScope.launch {
            val apkUrl = _uiState.value.updateAvailable?.apkUrl
            if (apkUrl.isNullOrBlank()) {
                // URL R2 estable — no necesita re-fetch. Si llegamos aquí sin URL,
                // es un error de flujo (usuario presionó descargar sin haber hecho check).
                _uiState.update { it.copy(error = "Verificá la actualización antes de descargar.") }
                return@launch
            }

            _uiState.update { it.copy(isDownloading = true, downloadProgress = 0, error = null) }

            // R2: URL permanente sin TTL → no hay que re-fetchear la URL en cada intento.
            // UpdateChecker.downloadAndInstall() hace resume automático si existe tmpFile.
            when (val result = updateChecker.downloadAndInstall(
                apkUrl     = apkUrl,
                onProgress = { pct -> _uiState.update { it.copy(downloadProgress = pct) } }
            )) {
                is DownloadResult.Success -> _uiState.update {
                    it.copy(isDownloading = false, installReady = true, downloadProgress = 100, updateAvailable = null)
                }
                is DownloadResult.Error -> _uiState.update {
                    it.copy(isDownloading = false, error = result.message)
                }
            }
        }
    }
}
