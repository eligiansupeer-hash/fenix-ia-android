package com.fenix.ia.presentation.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fenix.ia.domain.repository.ProjectRepository
import com.fenix.ia.domain.usecase.CreateProjectUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProjectViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val createProjectUseCase: CreateProjectUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProjectUiState())
    val uiState: StateFlow<ProjectUiState> = _uiState.asStateFlow()

    private val _effect = Channel<ProjectEffect>(Channel.BUFFERED)
    val effect: Flow<ProjectEffect> = _effect.receiveAsFlow()

    init {
        processIntent(ProjectIntent.LoadProjects)
    }

    fun processIntent(intent: ProjectIntent) {
        when (intent) {
            is ProjectIntent.LoadProjects -> loadProjects()
            is ProjectIntent.CreateProject -> createProject(intent.name, intent.systemPrompt)
            is ProjectIntent.DeleteProject -> deleteProject(intent.projectId)
            is ProjectIntent.SelectProject -> {
                viewModelScope.launch {
                    _effect.send(ProjectEffect.NavigateToProject(intent.project.id))
                }
            }
        }
    }

    private fun loadProjects() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            projectRepository.getAllProjects()
                .catch { e -> _uiState.update { it.copy(error = e.message, isLoading = false) } }
                .collect { projects ->
                    _uiState.update { it.copy(projects = projects, isLoading = false) }
                }
        }
    }

    private fun createProject(name: String, systemPrompt: String) {
        viewModelScope.launch {
            try {
                createProjectUseCase(name, systemPrompt)
                _uiState.update { it.copy(showCreateDialog = false) }
            } catch (e: Exception) {
                _effect.send(ProjectEffect.ShowError(e.message ?: "Error desconocido"))
            }
        }
    }

    private fun deleteProject(projectId: String) {
        viewModelScope.launch {
            try {
                projectRepository.deleteProject(projectId)
            } catch (e: Exception) {
                _effect.send(ProjectEffect.ShowError(e.message ?: "Error al eliminar"))
            }
        }
    }
}
