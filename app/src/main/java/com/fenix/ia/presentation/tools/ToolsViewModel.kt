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
    private val orchestrator: OrchestratorEngine,
    private val policy: PolicyEngine
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

    fun execute(tool: Tool, argsJson: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isExecuting = true, lastResult = null) }
            val result = executor.execute(tool, argsJson)
            _uiState.update { it.copy(isExecuting = false, lastResult = result) }
        }
    }

    /**
     * Flujo completo de creación de herramienta con IA:
     * OrchestratorEngine genera código JS → PolicyEngine audita → Room persiste → UI actualiza.
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

                // Extraer bloque de código JS (con o sin fences)
                val js = rawOutput
                    .substringAfter("```js\n", rawOutput)
                    .substringBefore("```")
                    .trim()
                    .ifBlank { rawOutput.trim() }

                // PolicyEngine audita ANTES de persistir (R-05)
                val policyResult = policy.audit(js)
                if (!policyResult.isAllowed) {
                    _uiState.update {
                        it.copy(
                            isCreating = false,
                            error = "PolicyEngine rechazó: ${policyResult.reason}"
                        )
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
                        id = UUID.randomUUID().toString(),
                        name = name,
                        description = description,
                        inputSchema = "{\"input\":\"string\"}",
                        outputSchema = "{\"result\":\"string\"}",
                        permissions = emptyList(),
                        executionType = ToolExecutionType.JAVASCRIPT,
                        jsBody = js,
                        isEnabled = true,
                        isUserGenerated = true,
                        createdAt = System.currentTimeMillis()
                    )
                )
                _uiState.update { it.copy(isCreating = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isCreating = false, error = e.message) }
            }
        }
    }
}
