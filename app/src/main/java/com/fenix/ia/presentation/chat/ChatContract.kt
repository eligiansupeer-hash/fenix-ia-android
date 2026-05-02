package com.fenix.ia.presentation.chat

import com.fenix.ia.domain.model.ApiProvider
import com.fenix.ia.domain.model.DocumentNode
import com.fenix.ia.domain.model.Message
import com.fenix.ia.domain.model.Tool

sealed class ChatIntent {
    data class SendMessage(
        val content: String,
        val attachmentUris: List<String> = emptyList()
    ) : ChatIntent()
    data class ToggleDocumentCheckpoint(val documentId: String) : ChatIntent()
    data class LoadChat(val chatId: String) : ChatIntent()
    data class SelectProvider(val provider: ApiProvider?) : ChatIntent()
    data class ToggleTool(val toolId: String) : ChatIntent()
    data class AddAttachmentUri(val uri: String) : ChatIntent()
    // Reintentar desde un mensaje de usuario específico (borra todo lo posterior y re-ejecuta)
    data class RetryFromMessage(val userMessageId: String) : ChatIntent()
    object ClearPendingAttachments : ChatIntent()
    object StopStreaming : ChatIntent()
    object RegenerateLastMessage : ChatIntent()
    object DismissError : ChatIntent()
}

enum class ContextMode { NONE, FULL, RAG }

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val documents: List<DocumentNode> = emptyList(),
    val isStreaming: Boolean = false,
    val isSending: Boolean = false,           // guard anti-doble-envío
    val streamingBuffer: String = "",
    val activeProvider: ApiProvider? = null,
    val selectedProvider: ApiProvider? = null,
    val availableProviders: List<ApiProvider> = emptyList(),
    val contextMode: ContextMode = ContextMode.NONE,
    val contextDocumentCount: Int = 0,
    val contextTokenCount: Int = 0,
    val error: String? = null,
    val isLoading: Boolean = false,
    val allTools: List<Tool> = emptyList(),
    val enabledToolIds: Set<String> = emptySet(),
    val pendingAttachmentUris: List<String> = emptyList()
)

sealed class ChatEffect {
    data class ShowError(val message: String) : ChatEffect()
    data class OpenFilePicker(val allowedMimeTypes: List<String>) : ChatEffect()
}
