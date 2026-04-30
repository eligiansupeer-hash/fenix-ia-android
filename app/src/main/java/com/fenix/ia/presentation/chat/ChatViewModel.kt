package com.fenix.ia.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fenix.ia.data.local.objectbox.RagEngine
import com.fenix.ia.data.remote.LlmInferenceRouter
import com.fenix.ia.data.remote.LlmMessage
import com.fenix.ia.data.remote.StreamEvent
import com.fenix.ia.data.remote.TaskType
import com.fenix.ia.domain.model.DocumentNode
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
    private val documentRepository: DocumentRepository,
    private val ragEngine: RagEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _effects = Channel<ChatEffect>(Channel.BUFFERED)
    val effects: Flow<ChatEffect> = _effects.receiveAsFlow()

    private var currentChatId: String = ""
    private var currentProjectId: String = ""
    private var streamingJob: Job? = null

    companion object {
        /**
         * Umbral de tokens para decidir estrategia de contexto:
         *
         * <= FULL_CONTEXT_TOKEN_LIMIT → texto completo de todos los docs en el system prompt.
         *    El LLM ve TODO el contenido. Ideal para docs cortos/medianos.
         *
         *  > FULL_CONTEXT_TOKEN_LIMIT → RAG semántico: solo los chunks más relevantes
         *    para la query actual. Necesario para docs muy largos o muchos docs.
         *
         * 60_000 tokens ≈ límite conservador que funciona en Gemini (1M tokens),
         * Groq (128k), Mistral (128k) y OpenRouter (según modelo).
         * Para Gemini se puede subir a 500_000 si se detecta ese proveedor.
         */
        private const val FULL_CONTEXT_TOKEN_LIMIT = 60_000

        // Cuántos chars equivalen aproximadamente a 1 token (promedio inglés/español)
        private const val CHARS_PER_TOKEN = 4

        // Cuántos chunks RAG traer cuando el contenido es demasiado largo
        private const val RAG_CHUNK_LIMIT = 20

        // Máximo de mensajes de historial a incluir en cada request
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
            is ChatIntent.RegenerateLastMessage -> regenerateLastMessage()
            is ChatIntent.DismissError -> _uiState.update { it.copy(error = null) }
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

            val checkedDocs = documentRepository.getCheckedDocuments(currentProjectId)
            val (systemPrompt, estimatedTokens) = buildContextForQuery(content, checkedDocs)

            val provider = llmRouter.selectProvider(
                estimatedTokens = estimatedTokens,
                taskType = detectTaskType(content)
            )

            _uiState.update {
                it.copy(isStreaming = true, streamingBuffer = "", activeProvider = provider, error = null)
            }

            streamingJob = viewModelScope.launch {
                val history = _uiState.value.messages
                    .takeLast(MAX_HISTORY_MESSAGES)
                    .map { LlmMessage(role = it.role.name.lowercase(), content = it.content) }
                collectInferenceStream(history, systemPrompt, provider)
            }
        }
    }

    private fun regenerateLastMessage() {
        viewModelScope.launch {
            if (_uiState.value.isStreaming) return@launch
            val messages = _uiState.value.messages
            val lastAssistant = messages.lastOrNull { it.role == MessageRole.ASSISTANT } ?: return@launch
            val lastUserContent = messages.lastOrNull { it.role == MessageRole.USER }?.content ?: return@launch

            messageRepository.deleteMessage(lastAssistant.id)

            val checkedDocs = documentRepository.getCheckedDocuments(currentProjectId)
            val (systemPrompt, estimatedTokens) = buildContextForQuery(lastUserContent, checkedDocs)

            val provider = llmRouter.selectProvider(
                estimatedTokens = estimatedTokens,
                taskType = detectTaskType(lastUserContent)
            )

            val history = messages
                .filter { it.id != lastAssistant.id }
                .takeLast(MAX_HISTORY_MESSAGES)
                .map { LlmMessage(it.role.name.lowercase(), it.content) }

            _uiState.update {
                it.copy(isStreaming = true, streamingBuffer = "", activeProvider = provider, error = null)
            }

            streamingJob = viewModelScope.launch {
                collectInferenceStream(history, systemPrompt, provider)
            }
        }
    }

    /**
     * Construye el contexto documental para la query actual usando la estrategia adecuada:
     *
     * Estrategia A — Texto completo (docs cortos/medianos):
     *   Si el total de tokens de los documentos seleccionados cabe en el context window,
     *   manda TODO el contenido de TODOS los documentos. El LLM puede responder
     *   cualquier pregunta sobre cualquier parte del documento.
     *
     * Estrategia B — RAG semántico (docs largos):
     *   Si el total supera el umbral, busca los chunks más relevantes para la query
     *   actual. El LLM solo ve los fragmentos pertinentes a la pregunta.
     *
     * Retorna (systemPrompt, estimatedTokensTotal).
     */
    private suspend fun buildContextForQuery(
        query: String,
        checkedDocs: List<DocumentNode>
    ): Pair<String, Int> {
        if (checkedDocs.isEmpty()) {
            val prompt = "Eres FENIX IA, un asistente inteligente y preciso."
            return Pair(prompt, estimateTokens(query))
        }

        val docIds = checkedDocs.map { it.id }

        // Mide el tamaño total del contenido indexado
        val totalTokens = ragEngine.estimateTotalTokens(docIds)

        val contextBlock: String
        val strategyLabel: String

        if (totalTokens <= FULL_CONTEXT_TOKEN_LIMIT) {
            // ── Estrategia A: texto completo ──────────────────────────────────
            strategyLabel = "COMPLETO"
            val fullTexts = ragEngine.getFullTextByDocumentNodeIds(docIds)
            contextBlock = buildString {
                fullTexts.entries.forEachIndexed { index, (nodeId, text) ->
                    val name = checkedDocs.getOrNull(index)?.name ?: nodeId
                    appendLine("════════════════════════════════")
                    appendLine("DOCUMENTO: $name")
                    appendLine("════════════════════════════════")
                    appendLine(text)
                    appendLine()
                }
            }
        } else {
            // ── Estrategia B: RAG semántico ───────────────────────────────────
            strategyLabel = "RAG"
            val relevantChunks = ragEngine.searchInDocuments(query, docIds, limit = RAG_CHUNK_LIMIT)
            contextBlock = if (relevantChunks.isEmpty()) {
                "(No se encontraron fragmentos relevantes para esta consulta en los documentos seleccionados.)"
            } else {
                buildString {
                    appendLine("Fragmentos relevantes de los documentos (ordenados por relevancia):")
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
            appendLine("Tienes acceso al contenido de los siguientes documentos cargados por el usuario:")
            checkedDocs.forEachIndexed { i, doc ->
                appendLine("  ${i + 1}. ${doc.name}")
            }
            appendLine()
            appendLine("Modo de contexto: $strategyLabel")
            if (strategyLabel == "COMPLETO") {
                appendLine("Tienes el texto COMPLETO de todos los documentos. Podés responder cualquier pregunta sobre su contenido.")
            } else {
                appendLine("Los documentos son extensos. Se incluyen los fragmentos más relevantes para la consulta actual.")
                appendLine("Si el usuario pregunta algo que no aparece en los fragmentos, indicalo claramente.")
            }
            appendLine()
            appendLine("══════ CONTENIDO DE DOCUMENTOS ══════")
            appendLine(contextBlock)
            appendLine("══════ FIN DE DOCUMENTOS ══════")
        }

        val totalEstimated = estimateTokens(systemPrompt) + estimateTokens(query)
        return Pair(systemPrompt, totalEstimated)
    }

    private suspend fun collectInferenceStream(
        history: List<LlmMessage>,
        systemPrompt: String,
        provider: com.fenix.ia.domain.model.ApiProvider
    ) {
        val assistantMessageId = UUID.randomUUID().toString()
        llmRouter.streamCompletion(
            messages = history,
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
                    // INVARIANTE NODO-13: streamingBuffer siempre se resetea a "" tras Done
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
