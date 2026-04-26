package com.fenix.ia.data.remote

import com.fenix.ia.domain.model.ApiProvider

sealed class StreamEvent {
    data class Token(val text: String) : StreamEvent()
    object Done : StreamEvent()
    data class Error(val message: String) : StreamEvent()
    data class ProviderFallback(val from: ApiProvider, val to: ApiProvider) : StreamEvent()
}

enum class TaskType { FAST_CHAT, CODE_GENERATION, DOCUMENT_ANALYSIS, REASONING }

data class LlmMessage(val role: String, val content: String)
