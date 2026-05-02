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
    private val toolExecutor: ToolExecutor          // LOOP AGÉNTICO: ejecuta tool calls
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
        private const val MAX_AGENTIC_ITERATIONS = 5   // freno anti-bucle
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

    private fun sendMessage(content: String, attachmentUris: List<String>) {
        viewModelScope.launch {
            if (_uiState.value.isStreaming) return@launch

            val allAttachmentUris = (attachmentUris + _uiState.value.pendingAttachmentUris).distinct()

            val userMessage = Message(
                id = UUID.randomUUID().toString(),
                chatId = currentChatId,
                role = MessageRole.USER,
                content = content,
                timestamp = System.currentTimeMillis(),
                attachmentUris = allAttachmentUris
            )
            messageRepository.insertMessage(userMessage)
            _uiState.update { it.copy(pendingAttachmentUris = emptyList()) }

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

                runAgenticLoop(history, systemPrompt, provider, activeTools)
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
                runAgenticLoop(history, systemPrompt, provider, activeTools)
            }
        }
    }

    // ── LOOP AGÉNTICO ────────────────────────────────────────────────────────
    /**
     * Ejecuta hasta MAX_AGENTIC_ITERATIONS ciclos:
     *   1. Llama al LLM y acumula el stream en el buffer visible
     *   2. Si la respuesta contiene <tool_call>, extrae las llamadas
     *   3. Ejecuta cada tool via ToolExecutor
     *   4. Inyecta los resultados como mensaje "tool" en el historial
     *   5. Vuelve a llamar al LLM con el historial ampliado
     *   6. Si no hay tool calls → persiste la respuesta final y termina
     */
    private suspend fun runAgenticLoop(
        initialHistory: List<LlmMessage>,
        systemPrompt: String,
        provider: ApiProvider,
        activeTools: List<com.fenix.ia.domain.model.Tool>
    ) {
        var history = initialHistory.toMutableList()
        var iteration = 0

        while (iteration < MAX_AGENTIC_ITERATIONS) {
            iteration++

            // 1. Llamar al LLM y acumular en streamingBuffer
            val llmOutput = collectStreamToString(history, systemPrompt, provider, activeTools)

            if (llmOutput == null) {
                // Error ya emitido dentro de collectStreamToString
                return
            }

            // 2. ¿Hay tool calls en la respuesta?
            if (!ToolCallParser.hasToolCall(llmOutput)) {
                // Sin tool calls → respuesta final, persistir y salir
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

            // 3. Extraer tool calls
            val toolCalls = ToolCallParser.extractAll(llmOutput)
            val visibleBeforeTools = ToolCallParser.stripToolCalls(llmOutput).trim()

            // Mostrar al usuario el texto visible que precedió a las tool calls (si hay)
            if (visibleBeforeTools.isNotBlank()) {
                _uiState.update { it.copy(streamingBuffer = "⚙️ Ejecutando ${toolCalls.size} herramienta(s)...") }
            } else {
                _uiState.update { it.copy(streamingBuffer = "⚙️ Ejecutando ${toolCalls.size} herramienta(s)...") }
            }

            // Añadir respuesta del asistente (con tool_calls) al historial interno
            history.add(LlmMessage("assistant", llmOutput))

            // 4. Ejecutar cada tool y construir bloque de resultados
            val toolResultsBuilder = StringBuilder()
            toolCalls.forEach { call ->
                val tool = activeTools.firstOrNull { it.name == call.name }
                    ?: _uiState.value.allTools.firstOrNull { it.name == call.name }

                val resultJson = if (tool == null) {
                    """{"error":"Tool '${call.name}' no encontrada o no está habilitada en este chat."}"""
                } else {
                    when (val result = toolExecutor.execute(tool, call.argsJson)) {
                        is ToolResult.Success -> result.outputJson
                        is ToolResult.Error   -> """{"error":"${result.message.replace("\"", "'")}"}"""
                    }
                }

                toolResultsBuilder.appendLine(
                    "RESULTADO DE HERRAMIENTA [${call.name}]:\n$resultJson"
                )
            }

            // 5. Inyectar resultados como mensaje "user" (formato que entienden todos los proveedores)
            history.add(LlmMessage("user",
                "Los resultados de las herramientas son:\n\n${toolResultsBuilder.trim()}\n\n" +
                "Usando esta información, respondé la consulta original del usuario de forma clara y completa."
            ))

            // Continuar el loop con el historial ampliado
        }

        // Si se agotaron las iteraciones, avisar
        _uiState.update {
            it.copy(
                isStreaming     = false,
                streamingBuffer = "",
                error           = "El agente alcanzó el límite de $MAX_AGENTIC_ITERATIONS iteraciones."
            )
        }
    }

    /**
     * Colecta el stream del LLM en un String completo.
     * Actualiza streamingBuffer en tiempo real para que el usuario vea la respuesta parcial.
     * Retorna null si ocurrió un error (ya emitido al uiState).
     */
    private suspend fun collectStreamToString(
        history: List<LlmMessage>,
        systemPrompt: String,
        provider: ApiProvider,
        tools: List<com.fenix.ia.domain.model.Tool>
    ): String? {
        _uiState.update { it.copy(streamingBuffer = "") }
        var output = ""
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
                    // Mostrar solo el texto sin etiquetas tool_call en el buffer visible
                    _uiState.update {
                        it.copy(streamingBuffer = ToolCallParser.stripToolCalls(output))
                    }
                }
                is StreamEvent.ProviderFallback ->
                    _uiState.update { it.copy(activeProvider = event.to) }
                is StreamEvent.Error -> {
                    // FASE 11: limpiar buffer en error
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

    // ── Context building ─────────────────────────────────────────────────────

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
                "(No se encontraron fragmentos relevantes.)"
            } else {
                buildString {
                    appendLine("Fragmentos más relevantes para la consulta:")
                    appendLine()
                    relevantChunks.forEachIndexed { i, chunk ->
                        val name = checkedDocs.firstOrNull { it.id == chunk.documentNodeId }?.name
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

    // ── Helpers ───────────────────────────────────────────────────────────────

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
