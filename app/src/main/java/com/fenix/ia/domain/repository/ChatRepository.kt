package com.fenix.ia.domain.repository

import com.fenix.ia.domain.model.Chat
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun getChatsByProject(projectId: String): Flow<List<Chat>>
    suspend fun createChat(chat: Chat)
    suspend fun deleteChat(chatId: String)
}
