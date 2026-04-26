package com.fenix.ia.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fenix.ia.data.remote.LlmInferenceRouter
import com.fenix.ia.data.remote.LlmMessage
import com.fenix.ia.data.remote.StreamEvent
import com.fenix.ia.data.remote.TaskType
import com.fenix.ia.domain.model.Message
import com.fenix.ia.domain.model.MessageRole
import com.fenix.ia.domain.repository.DocumentRepository
import com.fenix.ia.domain.repository.MessageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val llmRouter: LlmInferenceRouter,
    private val messageRepository: MessageRepository,
    private val documentRepository: DocumentRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _effects = Channel<ChatEffect>(Channel.BUFFERED)
    val effects: Flow<ChatEffect> = _effects.receiveAsFlow()

    private var currentChatId: String = ""
    private var currentProjectId: String = ""
    private var streamingJob: Job? = null

    fun loadChat(chatId: String, projectId: String) {
        currentChatId = chatId
        currentProjectId = projectId
        viewModelScope.launch {
            messageRepository.getMessagesByChat(chatId)
                .catch { e -> _uiState.update { it.copy(error = e.message) } }
                .collect { messages -> _uiState.update { it.copy(messages = messages) } }
        }
        viewModelScope.launch {
            documentRepository.getDocumentsByProject(projectId)
                .collect { docs -> _uiState.update { it.copy(documents = docs) } }
        }
    }

    fun processIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.SendMessage -> sendMessage(intent.content, intent.attachmentIds)
            is ChatIntent.ToggleDocumentCheckpoint -> toggleCheckpoint(intent.documentId)
            is ChatIntent.StopStreaming -> stopStreaming()
            is ChatIntent.LoadChat -> loadChat(intent.chatId, "")
            is ChatIntent.RegenerateLastMessage -> {} // TODO próxima sesión
        }
    }

    private fun sendMessage(content: String, attachmentIds: List<String>) {
        viewModelScope.launch {
            if (_uiState.value.isStreaming) return@launch

            val userMessage = Message(
                id = UUID.randomUUID().toString(),
                chatId = currentChatId,
                role = MessageRole.USER,
                content = content,
                timestamp = System.currentTimeMillis()
            )
            messageRepository.insertMessage(userMessage)

            val documentArray = documentRepository
                .getCheckedDocuments(currentProjectId)
                .joinToString("\n") { "[${it.name}]: ${it.semanticSummary}" }

            val estimatedTokens = estimateTokens(content + documentArray)
            val provider = llmRouter.selectProvider(estimatedTokens, detectTaskType(content))

            _uiState.update { it.copy(isStreaming = true, streamingBuffer = "", activeProvider = provider) }

            val assistantMessageId = UUID.randomUUID().toString()

            streamingJob = viewModelScope.launch {
                val systemPrompt = buildSystemPrompt(documentArray)

                llmRouter.streamCompletion(
                    messages = getConversationHistory(),
                    systemPrompt = systemPrompt,
                    provider = provider
                ).collect { event ->
                    when (event) {
                        is StreamEvent.Token -> {
                            _uiState.update { state ->
                                state.copy(streamingBuffer = state.streamingBuffer + event.text)
                            }
                        }
                        is StreamEvent.Done -> {
                            val finalContent = _uiState.value.streamingBuffer
                            messageRepository.insertMessage(
                                Message(
                                    id = assistantMessageId,
                                    chatId = currentChatId,
                                    role = MessageRole.ASSISTANT,
                                    content = finalContent,
                                    timestamp = System.currentTimeMillis()
                                )
                            )
                            // INVARIANTE: streamingBuffer se resetea a "" tras Done
                            _uiState.update { it.copy(isStreaming = false, streamingBuffer = "") }
                            _effects.send(ChatEffect.ScrollToBottom)
                        }
                        is StreamEvent.Error -> {
                            _uiState.update { it.copy(isStreaming = false, error = event.message) }
                        }
                        is StreamEvent.ProviderFallback -> {
                            _uiState.update { it.copy(activeProvider = event.to) }
                        }
                    }
                }
            }
        }
    }

    private fun stopStreaming() {
        streamingJob?.cancel()
        _uiState.update { it.copy(isStreaming = false, streamingBuffer = "") }
    }

    private fun toggleCheckpoint(documentId: String) {
        viewModelScope.launch {
            val doc = _uiState.value.documents.firstOrNull { it.id == documentId } ?: return@launch
            documentRepository.updateCheckpoint(documentId, !doc.isChecked)
        }
    }

    private fun getConversationHistory(): List<LlmMessage> =
        _uiState.value.messages.takeLast(20).map { msg ->
            LlmMessage(role = msg.role.name.lowercase(), content = msg.content)
        }

    private fun buildSystemPrompt(documentArray: String): String = buildString {
        appendLine("Eres FENIX IA, un asistente inteligente.")
        if (documentArray.isNotBlank()) {
            appendLine("\nDOCUMENTOS DEL PROYECTO:")
            appendLine(documentArray)
        }
    }

    private fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

    private fun detectTaskType(content: String): TaskType {
        val lower = content.lowercase()
        return when {
            lower.contains("código") || lower.contains("código") || lower.contains("function") || lower.contains("class") -> TaskType.CODE_GENERATION
            lower.length > 500 -> TaskType.DOCUMENT_ANALYSIS
            else -> TaskType.FAST_CHAT
        }
    }
}
