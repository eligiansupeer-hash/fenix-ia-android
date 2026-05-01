package com.fenix.ia.presentation.chat

import com.fenix.ia.domain.model.ApiProvider
import com.fenix.ia.domain.model.DocumentNode
import com.fenix.ia.domain.model.Message

sealed class ChatIntent {
    data class SendMessage(val content: String, val attachmentIds: List<String> = emptyList()) : ChatIntent()
    data class ToggleDocumentCheckpoint(val documentId: String) : ChatIntent()
    data class LoadChat(val chatId: String) : ChatIntent()
    data class SelectProvider(val provider: ApiProvider?) : ChatIntent()  // null = auto-selección
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
    val selectedProvider: ApiProvider? = null,          // null = auto-selección por el router
    val availableProviders: List<ApiProvider> = emptyList(),
    val contextMode: ContextMode = ContextMode.NONE,
    val contextDocumentCount: Int = 0,
    val contextTokenCount: Int = 0,
    val error: String? = null,
    val isLoading: Boolean = false
)

/**
 * FASE 12: ScrollToBottom eliminado del sealed class.
 * El scroll se maneja exclusivamente via LaunchedEffect(messages.size) en ChatScreen.
 */
sealed class ChatEffect {
    data class ShowError(val message: String) : ChatEffect()
    data class OpenFilePicker(val allowedMimeTypes: List<String>) : ChatEffect()
}
