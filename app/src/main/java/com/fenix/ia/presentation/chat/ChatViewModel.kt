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
import com.fenix.ia.domain.repository.UserPrefsRepository
import com.fenix.ia.local.LocalLlmEngine
import com.fenix.ia.tools.ToolCallParser
import com.fenix.ia.tools.ToolExecutor
import com.fenix.ia.tools.ToolResult
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
    private val toolRepository: ToolRepository,
    private val toolExecutor: ToolExecutor,
    private val userPrefsRepository: UserPrefsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _effects = Channel<ChatEffect>(Channel.BUFFERED)
    val effects: Flow<ChatEffect> = _effects.receiveAsFlow()

    private var currentChatId: String = ""
    private var currentProjectId: String = ""
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
            is ChatIntent.RetryFromMessage         -> retryFromMessage(intent.userMessageId)   // FIX
            is ChatIntent.ToggleDocumentCheckpoint -> toggleCheckpoint(intent.documentId)
            is ChatIntent.StopStreaming            -> stopStreaming()
            is ChatIntent.LoadChat                 -> loadChat(intent.chatId, "")
            is ChatIntent.RegenerateLastMessage    -> regenerateLastMessage()
            is ChatIntent.DismissError             -> _uiState.update { it.copy(error = null) }
            is ChatIntent.SelectProvider           -> _uiState.update { it.copy(selectedProvider = intent.provider) }
            is ChatIntent.ToggleTool               -> toggleTool(intent.toolId)
            is ChatIntent.AddAttachmentUri         -> addAttachment(intent.uri)
            is ChatIntent.ClearPendingAttachments  -> _uiState.update { it.copy(pendingAttachmentUris = emptyList()) }
        }
    }

    private fun toggleTool(toolId: String) {
        viewModelScope.launch {
            val isCurrentlyEnabled = toolId in _uiState.value.enabledToolIds
            toolRepository.setToolEnabledForChat(currentChatId, toolId, !isCurrentlyEnabled)
        }
    }

    private fun addAttachment(uri: String) {
        _uiState.update { it.copy(pendingAttachmentUris = it.pendingAttachmentUris + uri) }
    }

    // ── Envío con guard anti-doble-envío ──────────────────────────────────────

    private fun sendMessage(content: String, attachmentUris: List<String>) {
        viewModelScope.launch {
            val state = _uiState.value
            // FIX: bloquea si ya se está enviando O si ya hay streaming activo
            if (state.isSending || state.isStreaming) return@launch

            _uiState.update { it.copy(isSending = true) }

            val allAttachmentUris = (attachmentUris + state.pendingAttachmentUris).distinct()

            val userMessage = Message(
                id             = UUID.randomUUID().toString(),
                chatId         = currentChatId,
                role           = MessageRole.USER,
                content        = content,
                timestamp      = System.currentTimeMillis(),
                attachmentUris = allAttachmentUris
            )
            messageRepository.insertMessage(userMessage)
            _uiState.update { it.copy(pendingAttachmentUris = emptyList(), isSending = false) }

            launchAgentPipeline(userContent = content)
        }
    }

    // ── Reintentar desde mensaje (FIX) ────────────────────────────────────────

    /**
     * Borra todos los mensajes posteriores al mensaje de usuario indicado,
     * luego re-lanza el pipeline con el mismo contenido.
     */
    private fun retryFromMessage(userMessageId: String) {
        viewModelScope.launch {
            val state = _uiState.value
            if (state.isSending || state.isStreaming) return@launch

            val msgs      = state.messages
            val userMsg   = msgs.firstOrNull { it.id == userMessageId } ?: return@launch
            val userIndex = msgs.indexOf(userMsg)

            // Borra respuestas posteriores al mensaje retried
            msgs.drop(userIndex + 1).forEach { messageRepository.deleteMessage(it.id) }

            launchAgentPipeline(userContent = userMsg.content)
        }
    }

    private fun regenerateLastMessage() {
        viewModelScope.launch {
            if (_uiState.value.isStreaming) return@launch
            val messages        = _uiState.value.messages
            val lastAssistant   = messages.lastOrNull { it.role == MessageRole.ASSISTANT } ?: return@launch
            val lastUserContent = messages.lastOrNull { it.role == MessageRole.USER }?.content ?: return@launch

            messageRepository.deleteMessage(lastAssistant.id)
            launchAgentPipeline(userContent = lastUserContent)
        }
    }

    // ── Pipeline central ──────────────────────────────────────────────────────

    private suspend fun launchAgentPipeline(userContent: String) {
        val checkedDocs = if (currentProjectId.isNotBlank())
            documentRepository.getCheckedDocuments(currentProjectId)
        else emptyList()

        val (systemPrompt, estimatedTokens, contextMode) =
            buildContextForQuery(userContent, checkedDocs)

        val provider = _uiState.value.selectedProvider
            ?: llmRouter.selectProvider(
                estimatedTokens = estimatedTokens,
                taskType        = detectTaskType(userContent)
            )

        val activeTools   = _uiState.value.allTools.filter { it.id in _uiState.value.enabledToolIds }
        val maxIterations = userPrefsRepository.getMaxAgenticIterations()

        _uiState.update {
            it.copy(
                isStreaming          = true,
                streamingBuffer      = "",
                activeProvider       = provider,
                contextMode          = contextMode,
                contextDocumentCount = checkedDocs.size,
                contextTokenCount    = estimatedTokens,
                error                = null
            )
        }

        streamingJob = viewModelScope.launch {
            val history = _uiState.value.messages
                .takeLast(MAX_HISTORY_MESSAGES)
                .map { LlmMessage(role = it.role.name.lowercase(), content = it.content) }
            runAgenticLoop(history, systemPrompt, provider, activeTools, maxIterations)
        }
    }

    // ── LOOP AGÉNTICO (FIX: tool-use loop real en chat) ───────────────────────

    /**
     * Cada iteración:
     *   1. Llama al LLM → acumula stream completo
     *   2. Sin tool_call → persiste respuesta final, termina
     *   3. Con tool_call → ejecuta via ToolExecutor, inyecta resultado, itera
     *
     * El XML de <tool_call> se oculta del streamingBuffer visible; solo se muestra
     * el texto previo al tag y el estado de ejecución.
     */
    private suspend fun runAgenticLoop(
        initialHistory: List<LlmMessage>,
        systemPrompt: String,
        provider: ApiProvider,
        activeTools: List<com.fenix.ia.domain.model.Tool>,
        maxIterations: Int
    ) {
        val history   = initialHistory.toMutableList()
        var iteration = 0

        while (iteration < maxIterations) {
            iteration++

            val llmOutput = collectStreamToString(history, systemPrompt, provider, activeTools)
                ?: return   // error ya propagado al estado

            // Respuesta final — sin tool calls
            if (!ToolCallParser.hasToolCall(llmOutput)) {
                val visibleText = llmOutput.trim()
                if (visibleText.isNotBlank()) {
                    messageRepository.insertMessage(
                        Message(
                            id        = UUID.randomUUID().toString(),
                            chatId    = currentChatId,
                            role      = MessageRole.ASSISTANT,
                            content   = visibleText,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
                _uiState.update { it.copy(isStreaming = false, streamingBuffer = "") }
                return
            }

            // Hay tool calls — ejecutar
            val toolCalls = ToolCallParser.extractAll(llmOutput)
            _uiState.update {
                it.copy(streamingBuffer = "⚙️ Ejecutando ${toolCalls.size} herramienta(s)... (iteración $iteration/$maxIterations)")
            }

            history.add(LlmMessage("assistant", llmOutput))

            val resultsBlock = buildString {
                toolCalls.forEach { call ->
                    val tool = activeTools.firstOrNull { it.name == call.name }
                        ?: _uiState.value.allTools.firstOrNull { it.name == call.name }

                    val resultJson = if (tool == null) {
                        """{"error":"Tool '${call.name}' no encontrada o no habilitada en este chat."}"""
                    } else {
                        when (val r = toolExecutor.execute(tool, call.argsJson)) {
                            is ToolResult.Success -> r.outputJson
                            is ToolResult.Error   ->
                                """{"error":"${r.message.replace("\"", "'")}"}"""
                        }
                    }
                    appendLine("RESULTADO DE HERRAMIENTA [${call.name}]:\n$resultJson")
                }
            }

            history.add(
                LlmMessage(
                    "user",
                    "Los resultados de las herramientas son:\n\n${resultsBlock.trim()}\n\n" +
                    "Usando esta información, respondé la consulta original del usuario de forma clara y completa."
                )
            )
        }

        // Límite de iteraciones alcanzado
        _uiState.update {
            it.copy(
                isStreaming     = false,
                streamingBuffer = "",
                error           = "El agente alcanzó el límite de $maxIterations iteraciones. " +
                                  "Podés aumentarlo en Configuración → Agente."
            )
        }
    }

    private suspend fun collectStreamToString(
        history: List<LlmMessage>,
        systemPrompt: String,
        provider: ApiProvider,
        tools: List<com.fenix.ia.domain.model.Tool>
    ): String? {
        _uiState.update { it.copy(streamingBuffer = "") }
        var output   = ""
        var hadError = false

        llmRouter.streamCompletion(
            messages     = history,
            systemPrompt = systemPrompt,
            provider     = provider,
            tools        = tools
        ).collect { event ->
            when (event) {
                is StreamEvent.Token -> {
                    output += event.text
                    // Muestra texto limpio (sin XML de tool_call) durante el streaming
                    _uiState.update { it.copy(streamingBuffer = ToolCallParser.stripToolCalls(output)) }
                }
                is StreamEvent.ProviderFallback ->
                    _uiState.update { it.copy(activeProvider = event.to) }
                is StreamEvent.Error -> {
                    _uiState.update {
                        it.copy(isStreaming = false, streamingBuffer = "", error = event.message)
                    }
                    hadError = true
                }
                is StreamEvent.Done -> { /* output ya acumulado */ }
            }
        }

        return if (hadError) null else output
    }

    // ── Context building ──────────────────────────────────────────────────────

    private suspend fun buildContextForQuery(
        query: String,
        checkedDocs: List<DocumentNode>
    ): Triple<String, Int, ContextMode> {
        if (checkedDocs.isEmpty()) {
            return Triple(
                "Eres FENIX IA, un asistente inteligente y preciso.",
                estimateTokens(query),
                ContextMode.NONE
            )
        }

        val docIds         = checkedDocs.map { it.id }
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
                        appendLine("DOCUMENTO: $name"); appendLine("════════════════════════════════")
                        appendLine(text); appendLine()
                    }
                }
            }
        } else {
            contextMode = ContextMode.RAG
            val chunks = ragEngine.searchInDocuments(query, docIds, limit = RAG_CHUNK_LIMIT)
            contextBlock = if (chunks.isEmpty()) {
                "(No se encontraron fragmentos relevantes.)"
            } else buildString {
                appendLine("Fragmentos más relevantes para la consulta:"); appendLine()
                chunks.forEachIndexed { i, chunk ->
                    val name = checkedDocs.firstOrNull { it.id == chunk.documentNodeId }?.name ?: chunk.documentNodeId
                    appendLine("── Fragmento ${i + 1} de «$name» ──")
                    appendLine(chunk.textPayload); appendLine()
                }
            }
        }

        val systemPrompt = buildString {
            appendLine("Eres FENIX IA, un asistente inteligente y preciso."); appendLine()
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
            appendLine(); appendLine("══════════════ CONTENIDO ══════════════")
            append(contextBlock); appendLine("══════════════ FIN ══════════════")
        }

        return Triple(systemPrompt, estimateTokens(systemPrompt) + estimateTokens(query), contextMode)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun stopStreaming() {
        streamingJob?.cancel()
        _uiState.update { it.copy(isStreaming = false, streamingBuffer = "", isSending = false) }
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
            else               -> TaskType.FAST_CHAT
        }
    }
}
