package com.fenix.ia.domain.repository

import com.fenix.ia.domain.model.Message
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    fun getMessagesByChat(chatId: String): Flow<List<Message>>
    suspend fun insertMessage(message: Message)
    suspend fun deleteMessagesInChat(chatId: String)
    suspend fun deleteMessage(messageId: String)
}
