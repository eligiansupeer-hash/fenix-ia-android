package com.fenix.ia.presentation.chat

import com.fenix.ia.domain.model.ApiProvider
import com.fenix.ia.domain.model.DocumentNode
import com.fenix.ia.domain.model.Message

sealed class ChatIntent {
    data class SendMessage(val content: String, val attachmentIds: List<String> = emptyList()) : ChatIntent()
    data class ToggleDocumentCheckpoint(val documentId: String) : ChatIntent()
    data class LoadChat(val chatId: String) : ChatIntent()
    object StopStreaming : ChatIntent()
    object RegenerateLastMessage : ChatIntent()
    object DismissError : ChatIntent()
}

/**
 * Modo de inyección de contexto documental al LLM.
 * Se muestra en la UI para que el usuario sepa cómo se están leyendo sus documentos.
 */
enum class ContextMode {
    /** Sin documentos seleccionados — solo conversación. */
    NONE,
    /** Texto completo de todos los documentos seleccionados — el LLM los lee enteros. */
    FULL,
    /** RAG semántico — los documentos son muy largos, se inyectan solo los fragmentos relevantes. */
    RAG
}

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val documents: List<DocumentNode> = emptyList(),
    val isStreaming: Boolean = false,
    val streamingBuffer: String = "",
    val activeProvider: ApiProvider? = null,
    val contextMode: ContextMode = ContextMode.NONE,
    val contextDocumentCount: Int = 0,      // Cuántos documentos están siendo leídos
    val contextTokenCount: Int = 0,         // Tokens totales del contexto (estimado)
    val error: String? = null,
    val isLoading: Boolean = false
)

sealed class ChatEffect {
    data class ShowError(val message: String) : ChatEffect()
    object ScrollToBottom : ChatEffect()
    data class OpenFilePicker(val allowedMimeTypes: List<String>) : ChatEffect()
}
