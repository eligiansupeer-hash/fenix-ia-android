package com.fenix.ia.domain.usecase

import com.fenix.ia.domain.model.Project
import com.fenix.ia.domain.repository.ProjectRepository
import java.util.UUID
import javax.inject.Inject

class CreateProjectUseCase @Inject constructor(
    private val projectRepository: ProjectRepository
) {
    suspend operator fun invoke(name: String, systemPrompt: String) {
        require(name.isNotBlank()) { "El nombre del proyecto no puede estar vacío" }
        val now = System.currentTimeMillis()
        val project = Project(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            systemPrompt = systemPrompt.trim(),
            createdAt = now,
            updatedAt = now
        )
        projectRepository.createProject(project)
    }
}
