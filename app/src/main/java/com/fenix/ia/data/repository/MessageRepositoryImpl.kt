package com.fenix.ia.data.repository

import com.fenix.ia.data.local.db.dao.MessageDao
import com.fenix.ia.data.local.db.dao.MessageAttachmentDao
import com.fenix.ia.data.local.db.entities.MessageAttachmentEntity
import com.fenix.ia.data.local.db.entities.MessageEntity
import com.fenix.ia.domain.model.Message
import com.fenix.ia.domain.model.MessageRole
import com.fenix.ia.domain.repository.MessageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepositoryImpl @Inject constructor(
    private val dao: MessageDao,
    private val attachmentDao: MessageAttachmentDao
) : MessageRepository {

    override fun getMessagesByChat(chatId: String): Flow<List<Message>> =
        combine(
            dao.getMessagesByChat(chatId),
            attachmentDao.getAttachmentsByChat(chatId)
        ) { entities, attachments ->
            val byMessage = attachments.groupBy { it.messageId }
            entities.map { it.toDomain(byMessage[it.id].orEmpty()) }
        }

    override suspend fun insertMessage(message: Message) {
        dao.insertMessage(message.toEntity())
        if (message.attachmentUris.isNotEmpty()) {
            attachmentDao.deleteAttachmentsByMessage(message.id)
            attachmentDao.insertAttachments(
                message.attachmentUris.map { uri ->
                    MessageAttachmentEntity(
                        id = UUID.randomUUID().toString(),
                        messageId = message.id,
                        chatId = message.chatId,
                        projectId = "",
                        documentId = "",
                        mimeType = "",
                        status = "pending",
                        checksum = "",
                        sourceUri = uri,
                        privateUri = "",
                        createdAt = message.timestamp
                    )
                }
            )
        }
    }

    override suspend fun deleteMessagesInChat(chatId: String) = dao.deleteMessagesInChat(chatId)

    override suspend fun deleteMessage(messageId: String) {
        attachmentDao.deleteAttachmentsByMessage(messageId)
        dao.deleteMessageById(messageId)
    }

    override suspend fun getAttachmentUris(messageId: String): List<String> =
        attachmentDao.getAttachmentsByMessage(messageId).map { it.privateUri.ifBlank { it.sourceUri } }

    private fun MessageEntity.toDomain(attachments: List<MessageAttachmentEntity>) = Message(
        id = id, chatId = chatId,
        role = MessageRole.valueOf(role),
        content = content, timestamp = timestamp,
        attachmentUris = attachments.map { it.privateUri.ifBlank { it.sourceUri } }.ifEmpty {
            attachmentUris
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        }
    )

    private fun Message.toEntity() = MessageEntity(
        id = id, chatId = chatId,
        role = role.name,
        content = content, timestamp = timestamp,
        attachmentUris = attachmentUris
            .takeIf { it.isNotEmpty() }
            ?.joinToString(",")
    )
}
