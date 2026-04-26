package com.fenix.ia.presentation.projects

import android.net.Uri
import com.fenix.ia.domain.model.Chat
import com.fenix.ia.domain.model.DocumentNode

sealed class ProjectDetailIntent {
    object CreateChat : ProjectDetailIntent()
    data class SelectChat(val chat: Chat) : ProjectDetailIntent()
    data class DeleteChat(val chatId: String) : ProjectDetailIntent()
    data class IngestDocument(val uri: Uri) : ProjectDetailIntent()
    data class DeleteDocument(val documentId: String) : ProjectDetailIntent()
    data class ToggleDocumentCheckpoint(val documentId: String, val isChecked: Boolean) : ProjectDetailIntent()
}

data class ProjectDetailUiState(
    val projectId: String = "",
    val projectName: String = "",
    val chats: List<Chat> = emptyList(),
    val documents: List<DocumentNode> = emptyList(),
    val isLoading: Boolean = false,
    val isIngesting: Boolean = false,
    val error: String? = null
)

sealed class ProjectDetailEffect {
    data class NavigateToChat(val chatId: String) : ProjectDetailEffect()
    data class ShowError(val message: String) : ProjectDetailEffect()
}
