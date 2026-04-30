package com.fenix.ia.presentation.artifacts

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class ArtifactFile(
    val name: String,
    val path: String,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAt: Long
)

data class ArtifactsUiState(
    val artifacts: List<ArtifactFile> = emptyList(),
    val filtered: List<ArtifactFile> = emptyList(),
    val filters: List<String> = listOf("Todos"),
    val activeFilter: String = "Todos",
    val selected: Set<ArtifactFile> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class ArtifactsViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArtifactsUiState())
    val uiState: StateFlow<ArtifactsUiState> = _uiState.asStateFlow()

    /**
     * Carga artefactos desde filesDir/projects/{projectId}/artifacts/
     * Agrupa los filtros de extensión disponibles dinámicamente.
     */
    fun load(projectId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val artifacts = scanArtifacts(projectId)
            val extensions = artifacts.map { it.name.substringAfterLast('.', "").uppercase() }
                .distinct().sorted()
            val filters = listOf("Todos") + extensions
            _uiState.update {
                it.copy(
                    artifacts = artifacts,
                    filtered = artifacts,
                    filters = filters,
                    isLoading = false
                )
            }
        }
    }

    fun setFilter(filter: String) {
        val all = _uiState.value.artifacts
        val filtered = if (filter == "Todos") all
        else all.filter { it.name.endsWith(".${filter.lowercase()}") }
        _uiState.update { it.copy(activeFilter = filter, filtered = filtered) }
    }

    fun toggleSelect(artifact: ArtifactFile) {
        val current = _uiState.value.selected.toMutableSet()
        if (artifact in current) current.remove(artifact) else current.add(artifact)
        _uiState.update { it.copy(selected = current) }
    }

    fun exportOne(artifact: ArtifactFile) {
        shareFile(File(artifact.path), artifact.mimeType)
    }

    fun exportSelected() {
        _uiState.value.selected.forEach { shareFile(File(it.path), it.mimeType) }
    }

    fun delete(artifact: ArtifactFile) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { File(artifact.path).delete() }
            _uiState.update { state ->
                val updated = state.artifacts.filter { it.path != artifact.path }
                state.copy(
                    artifacts = updated,
                    filtered = updated.applyFilter(state.activeFilter),
                    selected = state.selected.filter { it.path != artifact.path }.toSet()
                )
            }
        }
    }

    private suspend fun scanArtifacts(projectId: String): List<ArtifactFile> =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "projects/$projectId/artifacts")
            if (!dir.exists()) return@withContext emptyList()
            dir.listFiles()
                ?.filter { it.isFile }
                ?.sortedByDescending { it.lastModified() }
                ?.map { f ->
                    ArtifactFile(
                        name = f.name,
                        path = f.absolutePath,
                        mimeType = mimeFor(f.name),
                        sizeBytes = f.length(),
                        createdAt = f.lastModified()
                    )
                } ?: emptyList()
        }

    private fun shareFile(file: File, mimeType: String) {
        val uri = Uri.fromFile(file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Exportar ${file.name}").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun List<ArtifactFile>.applyFilter(filter: String) =
        if (filter == "Todos") this
        else filter { it.name.endsWith(".${filter.lowercase()}") }

    private fun mimeFor(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "md"   -> "text/markdown"
        "txt"  -> "text/plain"
        "json" -> "application/json"
        "csv"  -> "text/csv"
        "html" -> "text/html"
        "kt"   -> "text/x-kotlin"
        "py"   -> "text/x-python"
        "js"   -> "text/javascript"
        "pdf"  -> "application/pdf"
        else   -> "application/octet-stream"
    }
}
