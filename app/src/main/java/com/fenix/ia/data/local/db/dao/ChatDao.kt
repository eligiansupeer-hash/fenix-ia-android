package com.fenix.ia.data.local.db.dao

import androidx.room.*
import com.fenix.ia.data.local.db.entities.ChatEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats WHERE projectId = :projectId ORDER BY createdAt DESC")
    fun getChatsByProject(projectId: String): Flow<List<ChatEntity>>

    // P4: Chats globales — projectId es NULL (sin proyecto asociado)
    @Query("SELECT * FROM chats WHERE projectId IS NULL ORDER BY createdAt DESC")
    fun getGeneralChats(): Flow<List<ChatEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChat(chat: ChatEntity)

    @Query("DELETE FROM chats WHERE id = :chatId")
    suspend fun deleteChat(chatId: String)
}
