package com.fenix.ia.domain.model

data class Message(
    val id: String,
    val chatId: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long,
    val isStreaming: Boolean = false,
    val attachmentUris: List<String> = emptyList() // P6: adjuntos del mensaje
)

enum class MessageRole { USER, ASSISTANT, SYSTEM }
