package com.fenix.ia.presentation.drawer

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fenix.ia.domain.model.Chat
import com.fenix.ia.domain.model.DocumentNode
import com.fenix.ia.domain.model.Project
import com.fenix.ia.domain.repository.ChatRepository
import com.fenix.ia.domain.repository.DocumentRepository
import com.fenix.ia.domain.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class CreatedFileItem(
    val name: String,
    val path: String,
    val projectId: String,
    val sizeBytes: Long,
    val modifiedAt: Long
)

data class FenixDrawerState(
    val projects: List<Project> = emptyList(),
    val recentChats: List<Chat> = emptyList(),
    val createdFiles: List<CreatedFileItem> = emptyList(),
    val uploadedDocuments: List<DocumentNode> = emptyList()
)

@HiltViewModel
class FenixDrawerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    projectRepository: ProjectRepository,
    chatRepository: ChatRepository,
    documentRepository: DocumentRepository
) : ViewModel() {

    private val createdFiles = MutableStateFlow<List<CreatedFileItem>>(emptyList())

    val uiState: StateFlow<FenixDrawerState> = combine(
        projectRepository.getAllProjects(),
        chatRepository.getRecentChats(25),
        documentRepository.getRecentDocuments(40),
        createdFiles
    ) { projects, chats, documents, files ->
        FenixDrawerState(
            projects = projects,
            recentChats = chats,
            uploadedDocuments = documents,
            createdFiles = files
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FenixDrawerState())

    init {
        refreshCreatedFiles()
    }

    fun refreshCreatedFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val projectRoot = File(context.filesDir, "projects")
            val sistemaRoot = File(context.filesDir, "sistema_fenix")
            if (!projectRoot.exists() && !sistemaRoot.exists()) {
                createdFiles.value = emptyList()
                return@launch
            }
            val projectFiles = if (projectRoot.exists()) projectRoot
                .walkTopDown()
                .filter { it.isFile }
                .filterNot { it.name.endsWith(".tmp", ignoreCase = true) }
                .map { file ->
                    val relative = file.relativeTo(projectRoot).invariantSeparatorsPath
                    CreatedFileItem(
                        name = file.name,
                        path = file.absolutePath,
                        projectId = relative.substringBefore('/', missingDelimiterValue = "general"),
                        sizeBytes = file.length(),
                        modifiedAt = file.lastModified()
                    )
                }
                .toList()
            else emptyList()

            val sistemaFiles = if (sistemaRoot.exists()) sistemaRoot
                .walkTopDown()
                .filter { it.isFile && it.name.endsWith(".docx", ignoreCase = true) }
                .map { file ->
                    CreatedFileItem(
                        name = file.name,
                        path = file.absolutePath,
                        projectId = "sistema_fenix",
                        sizeBytes = file.length(),
                        modifiedAt = file.lastModified()
                    )
                }
                .toList()
            else emptyList()

            createdFiles.value = (projectFiles + sistemaFiles)
                .sortedByDescending { it.modifiedAt }
                .take(40)
                .toList()
        }
    }
}
