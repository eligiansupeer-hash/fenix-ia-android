package com.fenix.ia.domain.usecase

import com.fenix.ia.domain.model.Chat
import com.fenix.ia.domain.repository.ChatRepository
import java.util.UUID
import javax.inject.Inject

// P4: Instancia una sesión de chat huérfana (sin proyecto)
class CreateGeneralChatUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    suspend operator fun invoke(title: String = "Chat Global Nuevo"): Chat {
        val chat = Chat(
            id = UUID.randomUUID().toString(),
            projectId = "",   // "" → mapper lo convierte a NULL en BD
            title = title,
            createdAt = System.currentTimeMillis()
        )
        chatRepository.createChat(chat)
        return chat
    }
}
