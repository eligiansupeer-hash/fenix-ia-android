package com.fenix.ia.presentation.projects

import com.fenix.ia.domain.model.Project

sealed class ProjectIntent {
    data class CreateProject(val name: String, val systemPrompt: String) : ProjectIntent()
    data class DeleteProject(val projectId: String) : ProjectIntent()
    data class SelectProject(val project: Project) : ProjectIntent()
    object LoadProjects : ProjectIntent()
}

data class ProjectUiState(
    val projects: List<Project> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val showCreateDialog: Boolean = false
)

sealed class ProjectEffect {
    data class NavigateToProject(val projectId: String) : ProjectEffect()
    data class ShowError(val message: String) : ProjectEffect()
}
