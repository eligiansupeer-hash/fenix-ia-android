package com.fenix.ia.domain.repository

import com.fenix.ia.domain.model.Tool
import kotlinx.coroutines.flow.Flow

interface ToolRepository {
    fun getAllTools(): Flow<List<Tool>>
    fun getToolByName(name: String): Flow<Tool?>
    suspend fun insertTool(tool: Tool)
    suspend fun updateTool(tool: Tool)
    suspend fun deleteTool(id: String)
    suspend fun getEnabledTools(): List<Tool>
}
