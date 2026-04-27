package com.fenix.ia.presentation.projects

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
                // Resuelve metadata del URI (nombre + MIME) sin copiar el archivo a rutas absolutas
                val (fileName, mimeType) = resolveUriMetadata(uri)

                // Persiste el Content URI como string — WorkManager accede via contentResolver
                val docId = UUID.randomUUID().toString()
                val doc = DocumentNode(
                    id = docId,
                    projectId = projectId,
                    name = fileName,
                    uri = uri.toString(),           // Content URI — nunca ruta absoluta
                    mimeType = mimeType,
                    sizeBytes = resolveUriSize(uri),
                    semanticSummary = "Procesando...",
                    createdAt = System.currentTimeMillis()
                )
                documentRepository.insertDocument(doc)

                // CORRECCION: el Worker necesita el projectId como String.
                // Usamos un campo separado KEY_PROJECT_ID_STRING para que el Worker
                // pueda indexar en ObjectBox con un Long derivado del UUID de forma consistente.
                // Derivamos el Long como abs(UUID.hashCode()) — mismo cálculo en Worker y aquí.
                val projectIdLong = deriveProjectIdLong(projectId)
                val request = DocumentIngestionWorker.buildRequest(
                    documentId = docId,
                    projectId = projectIdLong,
                    projectIdString = projectId
                )
                androidx.work.WorkManager.getInstance(context).enqueue(request)

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
     * Resuelve nombre de archivo y MIME type desde un Content URI.
     * No copia el archivo — WorkManager accede al URI via contentResolver directamente.
     */
    private fun resolveUriMetadata(uri: Uri): Pair<String, String> {
        val contentResolver = context.contentResolver
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"

        var fileName = "documento_${System.currentTimeMillis()}"
        contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) fileName = cursor.getString(idx)
            }
        }

        return Pair(fileName, mimeType)
    }

    /**
     * Obtiene el tamaño del archivo en bytes desde el URI sin leerlo completo.
     * Retorna 0 si no se puede determinar (URI de Drive, etc.)
     */
    private fun resolveUriSize(uri: Uri): Long {
        return context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.SIZE),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (idx >= 0) cursor.getLong(idx) else 0L
            } else 0L
        } ?: 0L
    }

    companion object {
        /**
         * Deriva un Long consistente desde un UUID String para usar como projectId en ObjectBox.
         * INVARIANTE: mismo cálculo que DocumentIngestionWorker.deriveProjectIdLong().
         * Se usa Math.abs para evitar negativos (ObjectBox rechaza IDs negativos).
         */
        fun deriveProjectIdLong(projectId: String): Long {
            return Math.abs(projectId.hashCode().toLong())
        }
    }
}
