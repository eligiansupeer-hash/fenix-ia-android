package com.fenix.ia.data.repository

import com.fenix.ia.data.local.db.dao.ToolDao
import com.fenix.ia.data.local.db.entities.ToolEntity
import com.fenix.ia.domain.model.Tool
import com.fenix.ia.domain.model.ToolExecutionType
import com.fenix.ia.domain.repository.ToolRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolRepositoryImpl @Inject constructor(
    private val dao: ToolDao
) : ToolRepository {

    override fun getAllTools(): Flow<List<Tool>> =
        dao.getAllTools().map { list -> list.map { it.toDomain() } }

    override fun getToolByName(name: String): Flow<Tool?> =
        dao.getToolByName(name).map { it?.toDomain() }

    override suspend fun insertTool(tool: Tool) =
        dao.insertTool(tool.toEntity())

    override suspend fun updateTool(tool: Tool) =
        dao.updateTool(tool.toEntity())

    override suspend fun deleteTool(id: String) =
        dao.deleteTool(id)

    override suspend fun getEnabledTools(): List<Tool> =
        dao.getEnabledTools().map { it.toDomain() }

    // ---- mappers ----

    private fun ToolEntity.toDomain() = Tool(
        id = id,
        name = name,
        description = description,
        inputSchema = inputSchema,
        outputSchema = outputSchema,
        permissions = try {
            Json.decodeFromString<List<String>>(permissions)
        } catch (e: Exception) { emptyList() },
        executionType = try {
            ToolExecutionType.valueOf(executionType)
        } catch (e: Exception) { ToolExecutionType.NATIVE_KOTLIN },
        jsBody = jsBody,
        isEnabled = isEnabled,
        isUserGenerated = isUserGenerated,
        createdAt = createdAt
    )

    private fun Tool.toEntity() = ToolEntity(
        id = id,
        name = name,
        description = description,
        inputSchema = inputSchema,
        outputSchema = outputSchema,
        permissions = Json.encodeToString(permissions),
        executionType = executionType.name,
        jsBody = jsBody,
        isEnabled = isEnabled,
        isUserGenerated = isUserGenerated,
        createdAt = createdAt
    )
}
