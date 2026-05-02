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
    object CheckForUpdate : UpdateIntent()
    object DownloadAndInstall : UpdateIntent()
    object DismissError : UpdateIntent()
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
            is UpdateIntent.CheckForUpdate     -> checkForUpdate()
            is UpdateIntent.DownloadAndInstall -> downloadAndInstall()
            is UpdateIntent.DismissError       -> _uiState.update { it.copy(error = null) }
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
            _uiState.update { it.copy(isDownloading = true, downloadProgress = 0, updateAvailable = null, error = null) }

            // FIX: re-fetch siempre la URL antes de descargar para obtener un token presignado fresco.
            // Las URLs de GitHub Releases expiran en ~60s — si el usuario tardó en presionar
            // "Descargar" o si es un reintento, la URL guardada en updateAvailable ya venció.
            val freshUrl = when (val fresh = updateChecker.checkForUpdate()) {
                is UpdateResult.UpdateAvailable -> fresh.apkUrl
                else -> {
                    // Versión ya instalada o error de red — no hay nada que descargar
                    _uiState.update {
                        it.copy(
                            isDownloading = false,
                            error = if (fresh is UpdateResult.Error) fresh.message
                                    else "Ya tenés la versión más reciente instalada."
                        )
                    }
                    return@launch
                }
            }

            when (val result = updateChecker.downloadAndInstall(
                apkUrl     = freshUrl,
                onProgress = { pct -> _uiState.update { it.copy(downloadProgress = pct) } }
            )) {
                is DownloadResult.Success -> _uiState.update {
                    it.copy(isDownloading = false, installReady = true, downloadProgress = 100)
                }
                is DownloadResult.Error -> _uiState.update {
                    it.copy(isDownloading = false, error = result.message)
                }
            }
        }
    }
}
