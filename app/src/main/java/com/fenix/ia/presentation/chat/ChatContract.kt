package com.fenix.ia.presentation.chat

import com.fenix.ia.domain.model.ApiProvider
import com.fenix.ia.domain.model.DocumentNode
import com.fenix.ia.domain.model.Message

sealed class ChatIntent {
    data class SendMessage(val content: String, val attachmentIds: List<String> = emptyList()) : ChatIntent()
    data class ToggleDocumentCheckpoint(val documentId: String) : ChatIntent()
    data class LoadChat(val chatId: String) : ChatIntent()
    object StopStreaming : ChatIntent()
    data class RegenerateLastMessage(val chatId: String) : ChatIntent()
}

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val documents: List<DocumentNode> = emptyList(),
    val isStreaming: Boolean = false,
    val streamingBuffer: String = "",   // Acumula tokens SSE
    val activeProvider: ApiProvider? = null,
    val error: String? = null,
    val isLoading: Boolean = false
)

sealed class ChatEffect {
    data class ShowError(val message: String) : ChatEffect()
    object ScrollToBottom : ChatEffect()
    data class OpenFilePicker(val allowedMimeTypes: List<String>) : ChatEffect()
}
