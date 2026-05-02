package com.fenix.ia.domain.usecase

import com.fenix.ia.data.repository.ChatRepositoryImpl
import com.fenix.ia.domain.model.Chat
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

// P4: Caso de uso para recuperar historial de chats sin proyecto
class GetGeneralChatsUseCase @Inject constructor(
    private val chatRepository: ChatRepositoryImpl
) {
    operator fun invoke(): Flow<List<Chat>> = chatRepository.getGeneralChats()
}
