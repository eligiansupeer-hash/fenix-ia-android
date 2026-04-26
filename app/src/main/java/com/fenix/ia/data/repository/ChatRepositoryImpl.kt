package com.fenix.ia.data.repository

import com.fenix.ia.data.local.db.dao.ChatDao
import com.fenix.ia.data.local.db.entities.ChatEntity
import com.fenix.ia.domain.model.Chat
import com.fenix.ia.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val dao: ChatDao
) : ChatRepository {

    override fun getChatsByProject(projectId: String): Flow<List<Chat>> =
        dao.getChatsByProject(projectId).map { entities -> entities.map { it.toDomain() } }

    override suspend fun createChat(chat: Chat) = dao.insertChat(chat.toEntity())

    override suspend fun deleteChat(chatId: String) = dao.deleteChat(chatId)

    private fun ChatEntity.toDomain() = Chat(id = id, projectId = projectId, title = title, createdAt = createdAt)
    private fun Chat.toEntity() = ChatEntity(id = id, projectId = projectId, title = title, createdAt = createdAt)
}
