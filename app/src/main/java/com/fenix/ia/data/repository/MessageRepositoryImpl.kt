package com.fenix.ia.data.repository

import com.fenix.ia.data.local.db.dao.MessageDao
import com.fenix.ia.data.local.db.entities.MessageEntity
import com.fenix.ia.domain.model.Message
import com.fenix.ia.domain.model.MessageRole
import com.fenix.ia.domain.repository.MessageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepositoryImpl @Inject constructor(
    private val dao: MessageDao
) : MessageRepository {

    override fun getMessagesByChat(chatId: String): Flow<List<Message>> =
        dao.getMessagesByChat(chatId).map { entities -> entities.map { it.toDomain() } }

    override suspend fun insertMessage(message: Message) = dao.insertMessage(message.toEntity())

    override suspend fun deleteMessagesInChat(chatId: String) = dao.deleteMessagesInChat(chatId)

    override suspend fun deleteMessage(messageId: String) = dao.deleteMessageById(messageId)

    private fun MessageEntity.toDomain() = Message(
        id = id, chatId = chatId,
        role = MessageRole.valueOf(role),
        content = content, timestamp = timestamp
    )

    private fun Message.toEntity() = MessageEntity(
        id = id, chatId = chatId,
        role = role.name,
        content = content, timestamp = timestamp
    )
}
