package com.fenix.ia.domain.repository

import com.fenix.ia.domain.model.Project
import kotlinx.coroutines.flow.Flow

interface ProjectRepository {
    fun getAllProjects(): Flow<List<Project>>
    suspend fun createProject(project: Project)
    suspend fun updateProject(project: Project)
    suspend fun deleteProject(projectId: String)
    suspend fun getProjectById(projectId: String): Project?
}
