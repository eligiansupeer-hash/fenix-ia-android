package com.fenix.ia.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fenix.ia.data.local.db.entities.ChatToolEntity
import kotlinx.coroutines.flow.Flow

// P5: DAO para la tabla N:M chat_tools
@Dao
interface ChatToolDao {

    @Query("SELECT toolId FROM chat_tools WHERE chatId = :chatId AND isEnabled = 1")
    fun getEnabledToolIdsForChatFlow(chatId: String): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(chatTool: ChatToolEntity)
}
