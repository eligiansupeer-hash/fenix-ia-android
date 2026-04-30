package com.fenix.ia.data.local.db.dao

import androidx.room.*
import com.fenix.ia.data.local.db.entities.ToolEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ToolDao {
    @Query("SELECT * FROM tools WHERE isEnabled = 1 ORDER BY name ASC")
    fun getAllTools(): Flow<List<ToolEntity>>

    @Query("SELECT * FROM tools WHERE name = :name LIMIT 1")
    fun getToolByName(name: String): Flow<ToolEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTool(tool: ToolEntity)

    @Update
    suspend fun updateTool(tool: ToolEntity)

    @Query("DELETE FROM tools WHERE id = :id")
    suspend fun deleteTool(id: String)

    @Query("SELECT * FROM tools WHERE isEnabled = 1")
    suspend fun getEnabledTools(): List<ToolEntity>
}
