package com.fenix.ia.presentation.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fenix.ia.domain.model.Tool
import com.fenix.ia.domain.model.ToolExecutionType
import com.fenix.ia.domain.repository.ToolRepository
import com.fenix.ia.orchestrator.OrchestratorEngine
import com.fenix.ia.orchestrator.OrchestratorEvent
import com.fenix.ia.sandbox.PolicyEngine
import com.fenix.ia.tools.ToolExecutor
import com.fenix.ia.tools.ToolResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject

data class ToolsUiState(
    val tools: List<Tool> = emptyList(),
    val isExecuting: Boolean = false,
    val isCreating: Boolean = false,
    val lastResult: ToolResult? = null,
    val error: String? = null
)

@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val toolRepo: ToolRepository,
    private val executor: ToolExecutor,
    private val orchestrator: OrchestratorEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(ToolsUiState())
    val uiState: StateFlow<ToolsUiState> = _uiState.asStateFlow()

    fun loadTools() {
        viewModelScope.launch {
            toolRepo.getAllTools().collect { tools ->
                _uiState.update { it.copy(tools = tools) }
            }
        }
    }

    fun toggle(tool: Tool) {
        viewModelScope.launch {
            toolRepo.updateTool(tool.copy(isEnabled = !tool.isEnabled))
        }
    }

    fun clearResult() {
        _uiState.update { it.copy(lastResult = null, error = null) }
    }

    /**
     * Ejecuta una tool con input en lenguaje natural.
     * Construye el JSON de args automáticamente — el usuario nunca ve JSON.
     */
    fun executeNatural(tool: Tool, naturalInput: String, projectId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isExecuting = true, lastResult = null) }
            val argsJson = buildNaturalArgs(tool, naturalInput, projectId)
            val result   = executor.execute(tool, argsJson)
            _uiState.update { it.copy(isExecuting = false, lastResult = result) }
        }
    }

    /** Uso interno — ejecuta con JSON de args directo (llamado por el Orquestador). */
    fun execute(tool: Tool, argsJson: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isExecuting = true, lastResult = null) }
            val result = executor.execute(tool, argsJson)
            _uiState.update { it.copy(isExecuting = false, lastResult = result) }
        }
    }

    /**
     * Mapea input en lenguaje natural al JSON correcto para cada tool.
     */
    private fun buildNaturalArgs(tool: Tool, input: String, projectId: String): String =
        buildJsonObject {
            when (tool.name) {
                "web_search"        -> { put("query", input); put("maxResults", 5) }
                "scrape_content"    -> { put("url", input.trim()); put("cssSelector", "") }
                "summarize"         -> { put("text", input); put("maxWords", 200) }
                "translate"         -> {
                    val parts = input.split("→", "->")
                    put("text", parts.getOrNull(0)?.trim() ?: input)
                    put("targetLang", parts.getOrNull(1)?.trim() ?: "español")
                }
                "read_file"         -> { put("path", input.trim()); put("maxChars", 10_000) }
                "create_file"       -> {
                    put("fileName", "nota_${System.currentTimeMillis()}.txt")
                    put("content", input)
                    put("projectId", projectId)
                }
                "store_knowledge"   -> { put("text", input); put("projectId", projectId); put("tag", "manual") }
                "retrieve_context"  -> { put("query", input); put("projectId", projectId); put("limit", 5) }
                "run_code"          -> { put("code", input); put("inputData", "") }
                "deep_research"     -> { put("topic", input); put("depth", 2); put("projectId", projectId) }
                "search_in_project" -> { put("query", input); put("projectId", projectId); put("limit", 10) }
                else                -> put("input", input)
            }
        }.toString()

    /**
     * Crea herramienta con IA:
     * Orquestador genera JS → PolicyEngine audita → Room persiste.
     */
    fun createWithAi(description: String, projectId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isCreating = true, error = null) }
            try {
                var rawOutput = ""
                orchestrator.executeWorkflow(
                    goal = "Genera una herramienta JavaScript ES6 para: $description\n" +
                           "REGLAS ESTRICTAS: sin fetch, sin eval, sin DOM, sin localStorage.\n" +
                           "Recibe datos via variable payload (ArrayBuffer).\n" +
                           "Retorna JSON string. Responde SOLO con el código JS.",
                    projectId = projectId
                ).collect { event ->
                    if (event is OrchestratorEvent.WorkflowDone) rawOutput = event.finalOutput
                }

                val js = rawOutput
                    .substringAfter("```js\n", rawOutput)
                    .substringBefore("```")
                    .trim()
                    .ifBlank { rawOutput.trim() }

                val policyResult = PolicyEngine.evaluate(js)
                if (!policyResult.allowed) {
                    _uiState.update {
                        it.copy(isCreating = false, error = "PolicyEngine rechazó: ${policyResult.reason}")
                    }
                    return@launch
                }

                val name = description
                    .lowercase()
                    .replace(' ', '_')
                    .replace(Regex("[^a-z0-9_]"), "")
                    .take(30)

                toolRepo.insertTool(
                    Tool(
                        id              = UUID.randomUUID().toString(),
                        name            = name,
                        description     = description,
                        inputSchema     = "{\"input\":\"string\"}",
                        outputSchema    = "{\"result\":\"string\"}",
                        permissions     = emptyList(),
                        executionType   = ToolExecutionType.JAVASCRIPT,
                        jsBody          = js,
                        isEnabled       = true,
                        isUserGenerated = true,
                        createdAt       = System.currentTimeMillis()
                    )
                )
                _uiState.update { it.copy(isCreating = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isCreating = false, error = e.message) }
            }
        }
    }
}
