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
    val downloadProgress: Int = 0,          // 0-100
    val updateAvailable: UpdateResult.UpdateAvailable? = null,
    val isUpToDate: Boolean = false,
    val noReleases: Boolean = false,        // repo sin releases publicados — no es error
    val error: String? = null,
    val installReady: Boolean = false       // descarga completa, esperando confirmación usuario
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
            is UpdateIntent.CheckForUpdate -> checkForUpdate()
            is UpdateIntent.DownloadAndInstall -> downloadAndInstall()
            is UpdateIntent.DismissError -> _uiState.update { it.copy(error = null) }
            is UpdateIntent.DismissUpdateDialog -> _uiState.update { it.copy(updateAvailable = null) }
        }
    }

    private fun checkForUpdate() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isChecking = true,
                    error = null,
                    isUpToDate = false,
                    noReleases = false
                )
            }

            when (val result = updateChecker.checkForUpdate()) {
                is UpdateResult.UpdateAvailable -> {
                    _uiState.update {
                        it.copy(isChecking = false, updateAvailable = result)
                    }
                }
                is UpdateResult.UpToDate -> {
                    _uiState.update {
                        it.copy(isChecking = false, isUpToDate = true)
                    }
                }
                is UpdateResult.NoReleases -> {
                    // El repo no tiene releases — informamos sin mostrar error
                    _uiState.update {
                        it.copy(isChecking = false, noReleases = true)
                    }
                }
                is UpdateResult.Error -> {
                    _uiState.update {
                        it.copy(isChecking = false, error = result.message)
                    }
                }
            }
        }
    }

    private fun downloadAndInstall() {
        val update = _uiState.value.updateAvailable ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isDownloading = true, updateAvailable = null) }

            when (val result = updateChecker.downloadAndInstall(
                apkUrl = update.apkUrl,
                onProgress = { pct ->
                    _uiState.update { it.copy(downloadProgress = pct) }
                }
            )) {
                is DownloadResult.Success -> {
                    _uiState.update {
                        it.copy(isDownloading = false, installReady = true, downloadProgress = 100)
                    }
                }
                is DownloadResult.Error -> {
                    _uiState.update {
                        it.copy(isDownloading = false, error = result.message)
                    }
                }
            }
        }
    }
}
