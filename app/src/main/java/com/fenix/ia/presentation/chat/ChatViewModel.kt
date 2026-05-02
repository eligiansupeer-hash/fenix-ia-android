package com.fenix.ia.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fenix.ia.data.local.objectbox.RagEngine
import com.fenix.ia.data.remote.LlmInferenceRouter
import com.fenix.ia.data.remote.LlmMessage
import com.fenix.ia.data.remote.StreamEvent
import com.fenix.ia.data.remote.TaskType
import com.fenix.ia.domain.model.ApiProvider
import com.fenix.ia.domain.model.DocumentNode
import com.fenix.ia.domain.model.Message
import com.fenix.ia.domain.model.MessageRole
import com.fenix.ia.domain.repository.ApiKeyRepository
import com.fenix.ia.domain.repository.DocumentRepository
import com.fenix.ia.domain.repository.MessageRepository
import com.fenix.ia.domain.repository.ToolRepository
import com.fenix.ia.local.LocalLlmEngine
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
    private val documentRepository: DocumentRepository,
    private val ragEngine: RagEngine,
    private val apiKeyRepository: ApiKeyRepository,
    private val localLlmEngine: LocalLlmEngine,
    private val toolRepository: ToolRepository  // P5: inyectado para control granular de tools
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _effects = Channel<ChatEffect>(Channel.BUFFERED)
    val effects: Flow<ChatEffect> = _effects.receiveAsFlow()

    private var currentChatId: String = ""
    private var currentProjectId: String = ""  // P4: puede ser vacío en chats generales
    private var streamingJob: Job? = null

    companion object {
        private const val FULL_CONTEXT_TOKEN_LIMIT = 60_000
        private const val CHARS_PER_TOKEN = 4
        private const val RAG_CHUNK_LIMIT = 20
        private const val MAX_HISTORY_MESSAGES = 30
    }

    fun loadChat(chatId: String, projectId: String) {
        currentChatId = chatId
        currentProjectId = projectId

        viewModelScope.launch {
            messageRepository.getMessagesByChat(chatId)
                .catch { e -> _uiState.update { it.copy(error = e.message) } }
                .collect { messages -> _uiState.update { it.copy(messages = messages) } }
        }

        // P4: Solo carga documentos si hay proyecto asociado
        if (projectId.isNotBlank()) {
            viewModelScope.launch {
                documentRepository.getDocumentsByProject(projectId)
                    .collect { docs -> _uiState.update { it.copy(documents = docs) } }
            }
        }

        viewModelScope.launch {
            apiKeyRepository.getConfiguredProviders().collect { cloudProviders ->
                _uiState.update { state ->
                    val all = buildList {
                        if (localLlmEngine.isReady.value) add(ApiProvider.LOCAL_ON_DEVICE)
                        addAll(cloudProviders)
                    }
                    state.copy(availableProviders = all)
                }
            }
        }

        viewModelScope.launch {
            localLlmEngine.isReady.collect { ready ->
                _uiState.update { state ->
                    val providers = state.availableProviders.toMutableList()
                    if (ready) {
                        if (ApiProvider.LOCAL_ON_DEVICE !in providers)
                            providers.add(0, ApiProvider.LOCAL_ON_DEVICE)
                    } else {
                        providers.remove(ApiProvider.LOCAL_ON_DEVICE)
                    }
                    val newSelected =
                        if (!ready && state.selectedProvider == ApiProvider.LOCAL_ON_DEVICE) null
                        else state.selectedProvider
                    state.copy(availableProviders = providers, selectedProvider = newSelected)
                }
            }
        }

        // P5: carga todas las herramientas y las habilitadas para este chat
        viewModelScope.launch {
            toolRepository.getAllTools().collect { tools ->
                _uiState.update { it.copy(allTools = tools) }
            }
        }
        viewModelScope.launch {
            toolRepository.getEnabledToolIdsForChat(chatId).collect { enabledIds ->
                _uiState.update { it.copy(enabledToolIds = enabledIds.toSet()) }
            }
        }
    }

    fun processIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.SendMessage              -> sendMessage(intent.content, intent.attachmentUris)
            is ChatIntent.ToggleDocumentCheckpoint -> toggleCheckpoint(intent.documentId)
            is ChatIntent.StopStreaming            -> stopStreaming()
            is ChatIntent.LoadChat                 -> loadChat(intent.chatId, "")
            is ChatIntent.RegenerateLastMessage    -> regenerateLastMessage()
            is ChatIntent.DismissError             -> _uiState.update { it.copy(error = null) }
            is ChatIntent.SelectProvider           -> _uiState.update { it.copy(selectedProvider = intent.provider) }
            is ChatIntent.ToggleTool               -> toggleTool(intent.toolId)         // P5
            is ChatIntent.AddAttachmentUri         -> addAttachment(intent.uri)          // P6
            is ChatIntent.ClearPendingAttachments  -> _uiState.update { it.copy(pendingAttachmentUris = emptyList()) }
        }
    }

    // P5: alterna el estado de una herramienta en este chat
    private fun toggleTool(toolId: String) {
        viewModelScope.launch {
            val isCurrentlyEnabled = toolId in _uiState.value.enabledToolIds
            toolRepository.setToolEnabledForChat(currentChatId, toolId, !isCurrentlyEnabled)
        }
    }

    // P6: agrega un URI de adjunto a la lista pendiente
    private fun addAttachment(uri: String) {
        _uiState.update { it.copy(pendingAttachmentUris = it.pendingAttachmentUris + uri) }
    }

    private fun sendMessage(content: String, attachmentUris: List<String>) {
        viewModelScope.launch {
            if (_uiState.value.isStreaming) return@launch

            // P6: combina adjuntos explícitos con los pendientes acumulados en estado
            val allAttachmentUris = (attachmentUris + _uiState.value.pendingAttachmentUris).distinct()

            val userMessage = Message(
                id = UUID.randomUUID().toString(),
                chatId = currentChatId,
                role = MessageRole.USER,
                content = content,
                timestamp = System.currentTimeMillis(),
                attachmentUris = allAttachmentUris  // P6
            )
            messageRepository.insertMessage(userMessage)

            // P6: limpia adjuntos pendientes tras envío
            _uiState.update { it.copy(pendingAttachmentUris = emptyList()) }

            // P4: solo consulta documentos si hay proyecto
            val checkedDocs = if (currentProjectId.isNotBlank())
                documentRepository.getCheckedDocuments(currentProjectId)
            else emptyList()

            val (systemPrompt, estimatedTokens, contextMode) =
                buildContextForQuery(content, checkedDocs)

            val provider = _uiState.value.selectedProvider
                ?: llmRouter.selectProvider(
                    estimatedTokens = estimatedTokens,
                    taskType = detectTaskType(content)
                )

            // P5: obtiene tools habilitadas para pasar al router
            val activeTools = _uiState.value.allTools
                .filter { it.id in _uiState.value.enabledToolIds }

            _uiState.update {
                it.copy(
                    isStreaming = true,
                    streamingBuffer = "",
                    activeProvider = provider,
                    contextMode = contextMode,
                    contextDocumentCount = checkedDocs.size,
                    contextTokenCount = estimatedTokens,
                    error = null
                )
            }

            streamingJob = viewModelScope.launch {
                val history = _uiState.value.messages
                    .takeLast(MAX_HISTORY_MESSAGES)
                    .map { LlmMessage(role = it.role.name.lowercase(), content = it.content) }
                collectInferenceStream(history, systemPrompt, provider, activeTools)
            }
        }
    }

    private fun regenerateLastMessage() {
        viewModelScope.launch {
            if (_uiState.value.isStreaming) return@launch
            val messages = _uiState.value.messages
            val lastAssistant =
                messages.lastOrNull { it.role == MessageRole.ASSISTANT } ?: return@launch
            val lastUserContent =
                messages.lastOrNull { it.role == MessageRole.USER }?.content ?: return@launch

            messageRepository.deleteMessage(lastAssistant.id)

            val checkedDocs = if (currentProjectId.isNotBlank())
                documentRepository.getCheckedDocuments(currentProjectId)
            else emptyList()

            val (systemPrompt, estimatedTokens, contextMode) =
                buildContextForQuery(lastUserContent, checkedDocs)

            val provider = _uiState.value.selectedProvider
                ?: llmRouter.selectProvider(
                    estimatedTokens = estimatedTokens,
                    taskType = detectTaskType(lastUserContent)
                )

            val activeTools = _uiState.value.allTools
                .filter { it.id in _uiState.value.enabledToolIds }

            val history = messages
                .filter { it.id != lastAssistant.id }
                .takeLast(MAX_HISTORY_MESSAGES)
                .map { LlmMessage(it.role.name.lowercase(), it.content) }

            _uiState.update {
                it.copy(
                    isStreaming = true,
                    streamingBuffer = "",
                    activeProvider = provider,
                    contextMode = contextMode,
                    contextDocumentCount = checkedDocs.size,
                    contextTokenCount = estimatedTokens,
                    error = null
                )
            }

            streamingJob = viewModelScope.launch {
                collectInferenceStream(history, systemPrompt, provider, activeTools)
            }
        }
    }

    private suspend fun buildContextForQuery(
        query: String,
        checkedDocs: List<DocumentNode>
    ): Triple<String, Int, ContextMode> {
        if (checkedDocs.isEmpty()) {
            val prompt = "Eres FENIX IA, un asistente inteligente y preciso."
            return Triple(prompt, estimateTokens(query), ContextMode.NONE)
        }

        val docIds = checkedDocs.map { it.id }
        val totalDocTokens = ragEngine.estimateTotalTokens(docIds)

        val contextBlock: String
        val contextMode: ContextMode

        if (totalDocTokens <= FULL_CONTEXT_TOKEN_LIMIT) {
            contextMode = ContextMode.FULL
            val fullTexts = ragEngine.getFullTextByDocumentNodeIds(docIds)
            contextBlock = buildString {
                if (fullTexts.isEmpty()) {
                    appendLine("(Los documentos seleccionados aún no terminaron de procesarse.)")
                } else {
                    fullTexts.entries.forEachIndexed { index, (nodeId, text) ->
                        val name = checkedDocs.getOrNull(index)?.name ?: nodeId
                        appendLine("════════════════════════════════")
                        appendLine("DOCUMENTO: $name")
                        appendLine("════════════════════════════════")
                        appendLine(text)
                        appendLine()
                    }
                }
            }
        } else {
            contextMode = ContextMode.RAG
            val relevantChunks =
                ragEngine.searchInDocuments(query, docIds, limit = RAG_CHUNK_LIMIT)
            contextBlock = if (relevantChunks.isEmpty()) {
                "(No se encontraron fragmentos relevantes. Los documentos pueden estar procesándose todavía.)"
            } else {
                buildString {
                    appendLine("Fragmentos más relevantes para la consulta (ordenados por relevancia):")
                    appendLine()
                    relevantChunks.forEachIndexed { i, chunk ->
                        val name =
                            checkedDocs.firstOrNull { it.id == chunk.documentNodeId }?.name
                                ?: chunk.documentNodeId
                        appendLine("── Fragmento ${i + 1} de «$name» ──")
                        appendLine(chunk.textPayload)
                        appendLine()
                    }
                }
            }
        }

        val systemPrompt = buildString {
            appendLine("Eres FENIX IA, un asistente inteligente y preciso.")
            appendLine()
            appendLine("El usuario ha seleccionado los siguientes documentos como contexto:")
            checkedDocs.forEachIndexed { i, doc -> appendLine("  ${i + 1}. ${doc.name}") }
            appendLine()
            when (contextMode) {
                ContextMode.FULL -> {
                    appendLine("Tenés acceso al texto COMPLETO de todos los documentos.")
                    appendLine("Podés responder cualquier pregunta sobre su contenido con precisión.")
                }
                ContextMode.RAG -> {
                    appendLine("Los documentos son extensos (~${totalDocTokens / 1000}k tokens).")
                    appendLine("Se incluyen los fragmentos más relevantes para la pregunta actual.")
                    appendLine("Si el usuario pregunta algo fuera de los fragmentos, indicalo.")
                }
                ContextMode.NONE -> {}
            }
            appendLine()
            appendLine("══════════════ CONTENIDO ══════════════")
            append(contextBlock)
            appendLine("══════════════ FIN ══════════════")
        }

        val estimatedTokens = estimateTokens(systemPrompt) + estimateTokens(query)
        return Triple(systemPrompt, estimatedTokens, contextMode)
    }

    /**
     * Colecta el stream de inferencia y actualiza el estado MVI.
     * P5: recibe la lista de tools habilitadas para pasarla al router.
     * FASE 11: StreamEvent.Error limpia streamingBuffer explícitamente.
     * FASE 12: ScrollToBottom eliminado — scroll vía LaunchedEffect en ChatScreen.
     */
    private suspend fun collectInferenceStream(
        history: List<LlmMessage>,
        systemPrompt: String,
        provider: ApiProvider,
        tools: List<com.fenix.ia.domain.model.Tool> = emptyList()  // P5
    ) {
        val assistantMessageId = UUID.randomUUID().toString()
        llmRouter.streamCompletion(
            messages = history,
            systemPrompt = systemPrompt,
            provider = provider,
            tools = tools  // P5: inyecta tools habilitadas al router
        ).collect { event ->
            when (event) {
                is StreamEvent.Token ->
                    _uiState.update { state ->
                        state.copy(streamingBuffer = state.streamingBuffer + event.text)
                    }
                is StreamEvent.Done -> {
                    val finalContent = _uiState.value.streamingBuffer
                    if (finalContent.isNotBlank()) {
                        messageRepository.insertMessage(
                            Message(
                                id = assistantMessageId,
                                chatId = currentChatId,
                                role = MessageRole.ASSISTANT,
                                content = finalContent,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }
                    _uiState.update { it.copy(isStreaming = false, streamingBuffer = "") }
                }
                is StreamEvent.Error ->
                    // FASE 11: streamingBuffer limpiado explícitamente en error
                    _uiState.update {
                        it.copy(
                            isStreaming = false,
                            streamingBuffer = "",
                            error = event.message
                        )
                    }
                is StreamEvent.ProviderFallback ->
                    _uiState.update { it.copy(activeProvider = event.to) }
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

    private fun estimateTokens(text: String): Int =
        (text.length / CHARS_PER_TOKEN).coerceAtLeast(1)

    private fun detectTaskType(content: String): TaskType {
        val lower = content.lowercase()
        return when {
            lower.contains("código") || lower.contains("code") ||
            lower.contains("function") || lower.contains("class") ||
            lower.contains("kotlin") || lower.contains("python") ||
            lower.contains("java") || lower.contains("sql") -> TaskType.CODE_GENERATION
            lower.length > 300 -> TaskType.DOCUMENT_ANALYSIS
            else -> TaskType.FAST_CHAT
        }
    }
}
