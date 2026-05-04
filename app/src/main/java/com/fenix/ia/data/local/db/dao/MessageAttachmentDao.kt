package com.fenix.ia.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fenix.ia.data.local.db.entities.MessageAttachmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageAttachmentDao {
    @Query("SELECT * FROM message_attachments WHERE chatId = :chatId ORDER BY createdAt ASC")
    fun getAttachmentsByChat(chatId: String): Flow<List<MessageAttachmentEntity>>

    @Query("SELECT * FROM message_attachments WHERE messageId = :messageId ORDER BY createdAt ASC")
    suspend fun getAttachmentsByMessage(messageId: String): List<MessageAttachmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachments(attachments: List<MessageAttachmentEntity>)

    @Query("DELETE FROM message_attachments WHERE messageId = :messageId")
    suspend fun deleteAttachmentsByMessage(messageId: String)
}
