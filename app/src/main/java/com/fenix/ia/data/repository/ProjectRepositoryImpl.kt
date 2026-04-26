package com.fenix.ia.data.repository

import com.fenix.ia.data.local.db.dao.ProjectDao
import com.fenix.ia.data.local.db.entities.ProjectEntity
import com.fenix.ia.domain.model.Project
import com.fenix.ia.domain.repository.ProjectRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProjectRepositoryImpl @Inject constructor(
    private val dao: ProjectDao
) : ProjectRepository {

    override fun getAllProjects(): Flow<List<Project>> =
        dao.getAllProjects().map { entities -> entities.map { it.toDomain() } }

    override suspend fun createProject(project: Project) =
        dao.insertProject(project.toEntity())

    override suspend fun updateProject(project: Project) =
        dao.updateProject(project.toEntity())

    override suspend fun deleteProject(projectId: String) =
        dao.deleteProject(projectId)

    override suspend fun getProjectById(projectId: String): Project? =
        dao.getProjectById(projectId)?.toDomain()

    private fun ProjectEntity.toDomain() = Project(
        id = id, name = name, systemPrompt = systemPrompt,
        createdAt = createdAt, updatedAt = updatedAt
    )

    private fun Project.toEntity() = ProjectEntity(
        id = id, name = name, systemPrompt = systemPrompt,
        createdAt = createdAt, updatedAt = updatedAt
    )
}
