package com.fenix.ia.presentation.projects

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.fenix.ia.domain.model.Chat
import com.fenix.ia.domain.model.DocumentNode
import com.fenix.ia.domain.repository.ChatRepository
import com.fenix.ia.domain.repository.DocumentRepository
import com.fenix.ia.domain.repository.ProjectRepository
import com.fenix.ia.ingestion.DocumentIngestionWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ProjectDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectRepository: ProjectRepository,
    private val chatRepository: ChatRepository,
    private val documentRepository: DocumentRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProjectDetailUiState())
    val uiState: StateFlow<ProjectDetailUiState> = _uiState.asStateFlow()

    private val _effects = Channel<ProjectDetailEffect>(Channel.BUFFERED)
    val effects: Flow<ProjectDetailEffect> = _effects.receiveAsFlow()

    fun loadProject(projectId: String) {
        _uiState.update { it.copy(projectId = projectId, isLoading = true) }

        // Carga nombre del proyecto
        viewModelScope.launch {
            projectRepository.getProjectById(projectId)?.let { project ->
                _uiState.update { it.copy(projectName = project.name, isLoading = false) }
            }
        }

        // Observa chats en tiempo real
        viewModelScope.launch {
            chatRepository.getChatsByProject(projectId)
                .catch { e -> _uiState.update { it.copy(error = e.message) } }
                .collect { chats -> _uiState.update { it.copy(chats = chats) } }
        }

        // Observa documentos en tiempo real
        viewModelScope.launch {
            documentRepository.getDocumentsByProject(projectId)
                .catch { e -> _uiState.update { it.copy(error = e.message) } }
                .collect { docs -> _uiState.update { it.copy(documents = docs) } }
        }
    }

    fun processIntent(intent: ProjectDetailIntent) {
        when (intent) {
            is ProjectDetailIntent.CreateChat -> createChat()
            is ProjectDetailIntent.SelectChat -> navigateToChat(intent.chat)
            is ProjectDetailIntent.DeleteChat -> deleteChat(intent.chatId)
            is ProjectDetailIntent.IngestDocument -> ingestDocument(intent.uri)
            is ProjectDetailIntent.DeleteDocument -> deleteDocument(intent.documentId)
            is ProjectDetailIntent.ToggleDocumentCheckpoint -> toggleCheckpoint(intent.documentId, intent.isChecked)
        }
    }

    private fun createChat() {
        viewModelScope.launch {
            val projectId = _uiState.value.projectId
            val chatCount = _uiState.value.chats.size + 1
            val chat = Chat(
                id = UUID.randomUUID().toString(),
                projectId = projectId,
                title = "Chat $chatCount",
                createdAt = System.currentTimeMillis()
            )
            chatRepository.createChat(chat)
            _effects.send(ProjectDetailEffect.NavigateToChat(chat.id))
        }
    }

    private fun navigateToChat(chat: Chat) {
        viewModelScope.launch {
            _effects.send(ProjectDetailEffect.NavigateToChat(chat.id))
        }
    }

    private fun deleteChat(chatId: String) {
        viewModelScope.launch {
            try {
                chatRepository.deleteChat(chatId)
            } catch (e: Exception) {
                _effects.send(ProjectDetailEffect.ShowError(e.message ?: "Error al eliminar chat"))
            }
        }
    }

    private fun ingestDocument(uri: Uri) {
        viewModelScope.launch {
            val projectId = _uiState.value.projectId
            _uiState.update { it.copy(isIngesting = true) }

            try {
                // Resuelve metadata del archivo desde el URI
                val (fileName, mimeType, realPath) = resolveUri(uri)

                // Inserta el nodo en Room primero (aparece inmediatamente en la UI)
                val docId = UUID.randomUUID().toString()
                val doc = DocumentNode(
                    id = docId,
                    projectId = projectId,
                    name = fileName,
                    absolutePath = realPath,
                    mimeType = mimeType,
                    semanticSummary = "Procesando...",
                    createdAt = System.currentTimeMillis()
                )
                documentRepository.insertDocument(doc)

                // Dispara WorkManager para la ingesta vectorial en background
                val workData = workDataOf(
                    DocumentIngestionWorker.KEY_DOCUMENT_ID to docId,
                    DocumentIngestionWorker.KEY_FILE_PATH to realPath,
                    DocumentIngestionWorker.KEY_MIME_TYPE to mimeType,
                    DocumentIngestionWorker.KEY_PROJECT_ID to projectId.hashCode().toLong()
                )

                val request = OneTimeWorkRequestBuilder<DocumentIngestionWorker>()
                    .setInputData(workData)
                    .build()

                WorkManager.getInstance(context).enqueue(request)

            } catch (e: Exception) {
                _effects.send(ProjectDetailEffect.ShowError("Error cargando documento: ${e.message}"))
            } finally {
                _uiState.update { it.copy(isIngesting = false) }
            }
        }
    }

    private fun deleteDocument(documentId: String) {
        viewModelScope.launch {
            try {
                documentRepository.deleteDocument(documentId)
            } catch (e: Exception) {
                _effects.send(ProjectDetailEffect.ShowError(e.message ?: "Error al eliminar"))
            }
        }
    }

    private fun toggleCheckpoint(documentId: String, isChecked: Boolean) {
        viewModelScope.launch {
            documentRepository.updateCheckpoint(documentId, isChecked)
        }
    }

    /**
     * Resuelve un content URI a (nombre, mimeType, rutaReal).
     * Para URIs de MediaStore/Picker copia el archivo a filesDir del app.
     */
    private fun resolveUri(uri: Uri): Triple<String, String, String> {
        val contentResolver = context.contentResolver
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"

        // Obtiene nombre del archivo
        var fileName = "documento_${System.currentTimeMillis()}"
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) fileName = cursor.getString(idx)
                }
            }

        // Copia el archivo al directorio interno del app para que WorkManager pueda accederlo
        val destDir = java.io.File(context.filesDir, "projects/${_uiState.value.projectId}/docs").also { it.mkdirs() }
        val destFile = java.io.File(destDir, fileName)

        contentResolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        }

        return Triple(fileName, mimeType, destFile.absolutePath)
    }
}
