package com.fenix.ia.presentation.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.fenix.ia.attachments.AttachmentService
import com.fenix.ia.audit.AuditLogger
import com.fenix.ia.data.local.objectbox.RagEngine
import com.fenix.ia.data.local.objectbox.RagProjectId
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
import com.fenix.ia.ingestion.DocumentIngestionWorker
import com.fenix.ia.ingestion.DocxTextExtractor
import com.fenix.ia.ingestion.PdfTextExtractor
import com.fenix.ia.local.LocalLlmEngine
import com.fenix.ia.tools.ToolCallParser
import com.fenix.ia.tools.ToolExecutor
import com.fenix.ia.tools.ToolResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llmRouter: LlmInferenceRouter,
    private val messageRepository: MessageRepository,
    private val documentRepository: DocumentRepository,
    private val ragEngine: RagEngine,
    private val apiKeyRepository: ApiKeyRepository,
    private val localLlmEngine: LocalLlmEngine,
    private val toolRepository: ToolRepository,
    private val toolExecutor: ToolExecutor,
    private val userPrefsRepository: UserPrefsRepository,
    private val pdfExtractor: PdfTextExtractor,
    private val docxExtractor: DocxTextExtractor,
    private val attachmentService: AttachmentService
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
        private const val ATTACHMENT_CONTEXT_CHAR_LIMIT = 24_000
    }

    fun loadChat(chatId: String, projectId: String) {
        currentChatId = chatId
        currentProjectId = projectId
        AuditLogger.action("chat_load", mapOf("chatId" to chatId, "projectId" to projectId))

        viewModelScope.launch {
            messageRepository.getMessagesByChat(chatId)
                .catch { e ->
                    AuditLogger.error("chat_messages_load_failed", e, mapOf("chatId" to chatId))
                    _uiState.update { it.copy(error = e.message) }
                }
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
            is ChatIntent.SelectProvider           -> {
                AuditLogger.action("chat_select_provider", mapOf("provider" to (intent.provider?.name ?: "AUTO")))
                _uiState.update { it.copy(selectedProvider = intent.provider) }
            }
            is ChatIntent.ToggleTool               -> toggleTool(intent.toolId)
            is ChatIntent.AddAttachmentUri         -> addAttachment(intent.uri)
            is ChatIntent.ClearPendingAttachments  -> _uiState.update { it.copy(pendingAttachmentUris = emptyList()) }
        }
    }

    private fun toggleTool(toolId: String) {
        viewModelScope.launch {
            val isCurrentlyEnabled = toolId in _uiState.value.enabledToolIds
            AuditLogger.action(
                "chat_toggle_tool",
                mapOf("toolId" to toolId, "enabled" to (!isCurrentlyEnabled).toString())
            )
            toolRepository.setToolEnabledForChat(currentChatId, toolId, !isCurrentlyEnabled)
        }
    }

    private fun addAttachment(uri: String) {
        AuditLogger.action("chat_add_attachment", mapOf("uriLength" to uri.length.toString()))
        _uiState.update { it.copy(pendingAttachmentUris = it.pendingAttachmentUris + uri) }
    }

    // ── Envío con guard anti-doble-envío ──────────────────────────────────────

    private fun sendMessage(content: String, attachmentUris: List<String>) {
        viewModelScope.launch {
            val state = _uiState.value
            // FIX: bloquea si ya se está enviando O si ya hay streaming activo
            if (state.isSending || state.isStreaming) {
                AuditLogger.action("chat_send_blocked_busy", mapOf("chatId" to currentChatId))
                return@launch
            }

            AuditLogger.action(
                "chat_send_message",
                mapOf(
                    "chatId" to currentChatId,
                    "projectId" to currentProjectId,
                    "chars" to content.length.toString(),
                    "attachments" to (attachmentUris + state.pendingAttachmentUris).distinct().size.toString()
                )
            )
            AuditLogger.chat(
                role = "user",
                text = content,
                data = mapOf("chatId" to currentChatId, "projectId" to currentProjectId)
            )

            _uiState.update { it.copy(isSending = true) }

            val allAttachmentUris = (attachmentUris + state.pendingAttachmentUris).distinct()

            val messageRunId = UUID.randomUUID().toString()
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

            val attachedDocs = prepareMessageAttachments(userMessage.id, allAttachmentUris)
            val effectiveContent = buildUserContentForModel(content, attachedDocs)
            launchAgentPipeline(userMessage = userMessage, userContent = effectiveContent, attachedDocs = attachedDocs, messageRunId = messageRunId)
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

            val messageRunId = UUID.randomUUID().toString()
            val attachedDocs = prepareMessageAttachments(userMsg.id, messageRepository.getAttachmentUris(userMsg.id))
            val effectiveContent = buildUserContentForModel(userMsg.content, attachedDocs)
            launchAgentPipeline(userMessage = userMsg, userContent = effectiveContent, attachedDocs = attachedDocs, messageRunId = messageRunId)
        }
    }

    private fun regenerateLastMessage() {
        viewModelScope.launch {
            if (_uiState.value.isStreaming) return@launch
            val messages        = _uiState.value.messages
            val lastAssistant   = messages.lastOrNull { it.role == MessageRole.ASSISTANT } ?: return@launch
            val lastUserContent = messages.lastOrNull { it.role == MessageRole.USER }?.content ?: return@launch

            messageRepository.deleteMessage(lastAssistant.id)
            val lastUser = messages.lastOrNull { it.role == MessageRole.USER } ?: return@launch
            val messageRunId = UUID.randomUUID().toString()
            val attachedDocs = prepareMessageAttachments(lastUser.id, messageRepository.getAttachmentUris(lastUser.id))
            val effectiveContent = buildUserContentForModel(lastUserContent, attachedDocs)
            launchAgentPipeline(userMessage = lastUser, userContent = effectiveContent, attachedDocs = attachedDocs, messageRunId = messageRunId)
        }
    }

    // ── Pipeline central ──────────────────────────────────────────────────────

    private suspend fun launchAgentPipeline(
        userMessage: Message,
        userContent: String,
        attachedDocs: List<DocumentNode>,
        messageRunId: String
    ) {
        val checkedDocs = if (currentProjectId.isNotBlank())
            documentRepository.getCheckedDocuments(currentProjectId)
        else emptyList()
        val contextDocs = (checkedDocs + attachedDocs).distinctBy { it.id }

        val (systemPrompt, estimatedTokens, contextMode) =
            buildContextForQuery(userContent, contextDocs)

        val provider = _uiState.value.selectedProvider
            ?: llmRouter.selectProvider(
                estimatedTokens = estimatedTokens,
                taskType        = detectTaskType(userContent)
            )
        AuditLogger.action(
            "chat_provider_resolved",
            mapOf(
                "provider" to provider.name,
                "estimatedTokens" to estimatedTokens.toString(),
                "contextMode" to contextMode.name,
                "messageRunId" to messageRunId
            )
        )

        val activeTools   = _uiState.value.allTools.filter { it.id in _uiState.value.enabledToolIds }
        val maxIterations = userPrefsRepository.getMaxAgenticIterations()
        AuditLogger.action(
            "chat_agent_start",
            mapOf(
                "provider" to provider.name,
                "activeTools" to activeTools.size.toString(),
                "maxIterations" to maxIterations.toString(),
                "messageRunId" to messageRunId
            )
        )

        _uiState.update {
            it.copy(
                isStreaming          = true,
                streamingBuffer      = "",
                activeProvider       = provider,
                contextMode          = contextMode,
                contextDocumentCount = contextDocs.size,
                contextTokenCount    = estimatedTokens,
                error                = null
            )
        }

        streamingJob = viewModelScope.launch {
            val history = (_uiState.value.messages.filterNot { it.id == userMessage.id } + userMessage.copy(content = userContent))
                .takeLast(MAX_HISTORY_MESSAGES)
                .map { LlmMessage(role = it.role.name.lowercase(), content = it.content) }
            runAgenticLoop(history, systemPrompt, provider, activeTools, maxIterations, messageRunId)
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
        maxIterations: Int,
        messageRunId: String
    ) {
        val history   = initialHistory.toMutableList()
        var iteration = 0

        while (iteration < maxIterations) {
            iteration++

            val llmOutput = collectStreamToString(history, systemPrompt, provider, activeTools, messageRunId)
                ?: return   // error ya propagado al estado

            // Respuesta final — sin tool calls
            if (!ToolCallParser.hasToolCall(llmOutput)) {
                val visibleText = llmOutput.trim()
                if (visibleText.isNotBlank()) {
                    AuditLogger.chat(
                        role = "assistant_final",
                        text = visibleText,
                        data = mapOf("chatId" to currentChatId, "projectId" to currentProjectId, "provider" to provider.name, "messageRunId" to messageRunId)
                    )
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
            AuditLogger.action(
                "chat_tool_calls_detected",
                mapOf("count" to toolCalls.size.toString(), "iteration" to iteration.toString())
            )
            AuditLogger.chat(
                role = "assistant_tool_call_raw",
                text = llmOutput,
                data = mapOf("chatId" to currentChatId, "projectId" to currentProjectId, "provider" to provider.name, "messageRunId" to messageRunId)
            )
            _uiState.update {
                it.copy(streamingBuffer = "⚙️ Ejecutando ${toolCalls.size} herramienta(s)... (iteración $iteration/$maxIterations)")
            }

            history.add(LlmMessage("assistant", llmOutput))

            val resultsBlock = buildString {
                toolCalls.forEach { call ->
                    val tool = activeTools.firstOrNull { it.name == call.name }
                        ?: _uiState.value.allTools.firstOrNull { it.name == call.name }

                    val resultJson = if (tool == null) {
                        AuditLogger.action("chat_tool_missing", mapOf("tool" to call.name))
                        """{"error":"Tool '${call.name}' no encontrada o no habilitada en este chat."}"""
                    } else {
                        val toolCallId = UUID.randomUUID().toString()
                        AuditLogger.action("chat_tool_execute", mapOf("tool" to call.name, "toolCallId" to toolCallId, "messageRunId" to messageRunId))
                        when (val r = toolExecutor.execute(tool, call.argsJson)) {
                            is ToolResult.Success -> {
                                AuditLogger.action("chat_tool_success", mapOf("tool" to call.name, "toolCallId" to toolCallId, "messageRunId" to messageRunId))
                                r.outputJson
                            }
                            is ToolResult.Error   -> {
                                AuditLogger.action(
                                    "chat_tool_error",
                                    mapOf("tool" to call.name, "message" to r.message, "toolCallId" to toolCallId, "messageRunId" to messageRunId)
                                )
                                """{"error":"${r.message.replace("\"", "'")}"}"""
                            }
                        }
                    }
                    AuditLogger.chat(
                        role = "tool_result",
                        text = resultJson,
                        data = mapOf("chatId" to currentChatId, "tool" to call.name, "messageRunId" to messageRunId)
                    )
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
        AuditLogger.visibleMessage(
            "chat",
            "El agente alcanzó el límite de $maxIterations iteraciones. Podés aumentarlo en Configuración → Agente.",
            mapOf("kind" to "error")
        )
        AuditLogger.action("chat_agent_iteration_limit", mapOf("maxIterations" to maxIterations.toString()))
    }

    private suspend fun collectStreamToString(
        history: List<LlmMessage>,
        systemPrompt: String,
        provider: ApiProvider,
        tools: List<com.fenix.ia.domain.model.Tool>,
        messageRunId: String
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
                    AuditLogger.chat(
                        role = "assistant_stream_chunk",
                        text = event.text,
                        data = mapOf("chatId" to currentChatId, "projectId" to currentProjectId, "provider" to provider.name, "messageRunId" to messageRunId)
                    )
                    // Muestra texto limpio (sin XML de tool_call) durante el streaming
                    _uiState.update { it.copy(streamingBuffer = ToolCallParser.stripToolCalls(output)) }
                }
                is StreamEvent.ProviderFallback -> {
                    AuditLogger.action(
                        "chat_provider_fallback",
                        mapOf("from" to event.from.name, "to" to event.to.name)
                    )
                    _uiState.update { it.copy(activeProvider = event.to) }
                }
                is StreamEvent.Error -> {
                    AuditLogger.action("chat_stream_error", mapOf("provider" to provider.name, "message" to event.message, "messageRunId" to messageRunId))
                    AuditLogger.visibleMessage("chat", event.message, mapOf("kind" to "error", "provider" to provider.name))
                    _uiState.update {
                        it.copy(isStreaming = false, streamingBuffer = "", error = event.message)
                    }
                    hadError = true
                }
                is StreamEvent.Done -> {
                    AuditLogger.action(
                        "chat_stream_done",
                        mapOf("provider" to provider.name, "chars" to output.length.toString(), "messageRunId" to messageRunId)
                    )
                }
            }
        }

        return if (hadError) null else output
    }

    // ── Context building ──────────────────────────────────────────────────────

    private suspend fun prepareMessageAttachments(messageId: String, uris: List<String>): List<DocumentNode> {
        if (uris.isEmpty()) return emptyList()
        return attachmentService.prepareForMessage(
            chatId = currentChatId,
            messageId = messageId,
            projectId = currentProjectId,
            rawUris = uris
        ).map { it.document }
    }

    private suspend fun buildUserContentForModel(content: String, attachedDocs: List<DocumentNode>): String {
        if (attachedDocs.isEmpty()) return content
        val attachmentText = buildString {
            attachedDocs.forEach { doc ->
                val extracted = extractAttachmentText(doc).take(ATTACHMENT_CONTEXT_CHAR_LIMIT)
                appendLine()
                appendLine("--- ADJUNTO: ${doc.name} (${doc.mimeType}) ---")
                if (extracted.isBlank()) {
                    appendLine("El adjunto fue registrado y se esta procesando, pero todavia no hay texto extraible inmediato.")
                } else {
                    appendLine(extracted)
                }
                appendLine("--- FIN ADJUNTO ---")
            }
        }.trim()
        val base = content.ifBlank { "Analiza los adjuntos y responde con lo que puedas verificar de su contenido." }
        return "$base\n\n$attachmentText"
    }

    private suspend fun extractAttachmentText(doc: DocumentNode): String = withContext(Dispatchers.IO) {
        val mime = doc.mimeType.lowercase()
        return@withContext runCatching {
            when {
                mime.contains("pdf") || doc.name.endsWith(".pdf", ignoreCase = true) ->
                    pdfExtractor.extractText(doc)
                mime.contains("word") || mime.contains("docx") || doc.name.endsWith(".docx", ignoreCase = true) ->
                    docxExtractor.extractText(doc)
                mime.startsWith("text/") ||
                    doc.name.endsWith(".txt", ignoreCase = true) ||
                    doc.name.endsWith(".md", ignoreCase = true) ->
                    context.contentResolver.openInputStream(Uri.parse(doc.uri))?.bufferedReader()?.use { it.readText() }.orEmpty()
                else -> ""
            }
        }.onFailure { e ->
            AuditLogger.error("chat_attachment_extract_failed", e, mapOf("documentId" to doc.id, "name" to doc.name))
        }.getOrDefault("")
    }

    private fun enqueueAttachmentIngestion(doc: DocumentNode) {
        val request = DocumentIngestionWorker.buildRequest(
            documentId = doc.id,
            projectId = deriveProjectIdLong(doc.projectId),
            projectIdString = doc.projectId
        )
        WorkManager.getInstance(context).enqueueUniqueWork(
            "ingestion_queue_${doc.projectId}",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }

    private fun resolveAttachmentMetadata(uri: Uri): Triple<String, String, Long> {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri) ?: "application/octet-stream"
        var name = "adjunto_${System.currentTimeMillis()}"
        var size = 0L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex >= 0) size = cursor.getLong(sizeIndex)
            }
        }
        return Triple(name, mimeType, size)
    }

    private fun copyAttachmentToPrivateStorage(uri: Uri, projectId: String, documentId: String, fileName: String): Uri {
        val safeProjectId = projectId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)
        val safeFileName = fileName.replace('\\', '/').substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        val targetDir = File(context.filesDir, "uploaded_documents/$safeProjectId").also { it.mkdirs() }
        val target = File(targetDir, "${documentId}_$safeFileName")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("No se pudo abrir el adjunto")
        return Uri.fromFile(target)
    }

    private fun deriveProjectIdLong(projectId: String): Long =
        RagProjectId.stableLong(projectId)

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
        AuditLogger.action("chat_stop_streaming", mapOf("chatId" to currentChatId))
        streamingJob?.cancel()
        _uiState.update { it.copy(isStreaming = false, streamingBuffer = "", isSending = false) }
    }

    private fun toggleCheckpoint(documentId: String) {
        viewModelScope.launch {
            val doc = _uiState.value.documents.firstOrNull { it.id == documentId } ?: return@launch
            AuditLogger.action(
                "chat_toggle_document_checkpoint",
                mapOf("documentId" to documentId, "checked" to (!doc.isChecked).toString())
            )
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
