package com.fenix.ia.domain.usecase

import com.fenix.ia.domain.model.Chat
import com.fenix.ia.domain.repository.ChatRepository
import java.util.UUID
import javax.inject.Inject

class CreateChatUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    suspend operator fun invoke(projectId: String, title: String = "Nuevo chat"): Chat {
        val chat = Chat(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            title = title,
            createdAt = System.currentTimeMillis()
        )
        chatRepository.createChat(chat)
        return chat
    }
}
