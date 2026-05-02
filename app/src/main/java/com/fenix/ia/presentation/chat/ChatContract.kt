package com.fenix.ia.presentation.chat

import com.fenix.ia.domain.model.ApiProvider
import com.fenix.ia.domain.model.DocumentNode
import com.fenix.ia.domain.model.Message
import com.fenix.ia.domain.model.Tool

sealed class ChatIntent {
    data class SendMessage(
        val content: String,
        val attachmentUris: List<String> = emptyList()  // P6: URIs de archivos adjuntos
    ) : ChatIntent()
    data class ToggleDocumentCheckpoint(val documentId: String) : ChatIntent()
    data class LoadChat(val chatId: String) : ChatIntent()
    data class SelectProvider(val provider: ApiProvider?) : ChatIntent()
    data class ToggleTool(val toolId: String) : ChatIntent()  // P5: toggle granular por sesión
    data class AddAttachmentUri(val uri: String) : ChatIntent()     // P6: adjuntar archivo
    object ClearPendingAttachments : ChatIntent()                   // P6: limpiar adjuntos
    object StopStreaming : ChatIntent()
    object RegenerateLastMessage : ChatIntent()
    object DismissError : ChatIntent()
}

/**
 * Modo de inyección de contexto documental al LLM.
 */
enum class ContextMode {
    NONE,
    FULL,
    RAG
}

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val documents: List<DocumentNode> = emptyList(),
    val isStreaming: Boolean = false,
    val streamingBuffer: String = "",
    val activeProvider: ApiProvider? = null,
    val selectedProvider: ApiProvider? = null,
    val availableProviders: List<ApiProvider> = emptyList(),
    val contextMode: ContextMode = ContextMode.NONE,
    val contextDocumentCount: Int = 0,
    val contextTokenCount: Int = 0,
    val error: String? = null,
    val isLoading: Boolean = false,
    // P5: herramientas disponibles y habilitadas para este chat
    val allTools: List<Tool> = emptyList(),
    val enabledToolIds: Set<String> = emptySet(),
    // P6: adjuntos pendientes antes de enviar
    val pendingAttachmentUris: List<String> = emptyList()
)

/**
 * FASE 12: ScrollToBottom eliminado del sealed class.
 * El scroll se maneja exclusivamente via LaunchedEffect(messages.size) en ChatScreen.
 */
sealed class ChatEffect {
    data class ShowError(val message: String) : ChatEffect()
    data class OpenFilePicker(val allowedMimeTypes: List<String>) : ChatEffect()
}
