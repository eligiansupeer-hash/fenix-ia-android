package com.fenix.ia.orchestrator

import com.fenix.ia.data.local.objectbox.RagEngine
import com.fenix.ia.data.remote.LlmInferenceRouter
import com.fenix.ia.data.remote.LlmMessage
import com.fenix.ia.data.remote.StreamEvent
import com.fenix.ia.data.remote.TaskType
import com.fenix.ia.domain.model.AgentRole
import com.fenix.ia.domain.model.ApiProvider
import com.fenix.ia.domain.model.Tool
import com.fenix.ia.domain.model.WorkflowPlan
import com.fenix.ia.domain.model.WorkflowStep
import com.fenix.ia.domain.model.WorkflowStatus
import com.fenix.ia.domain.repository.ToolRepository
import com.fenix.ia.tools.ToolCallParser
import com.fenix.ia.tools.ToolExecutor
import com.fenix.ia.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Motor central del sistema de agentes autónomos.
 *
 * Flujo por paso:
 *   1. Construir system prompt con herramientas disponibles para el rol
 *   2. Inferencia del agente
 *   3. Si el output contiene <tool_call> → ejecutar via ToolExecutor → inyectar resultado → repetir
 *   4. Audit opcional
 *   5. Persistir en Blackboard (RagEngine)
 *   6. Emitir StepDone
 *
 * Formato de tool call:
 *   <tool_call>
 *   {"name": "web_search", "args": {"query": "...", "maxResults": 5}}
 *   </tool_call>
 */
@Singleton
class OrchestratorEngine @Inject constructor(
    private val llmRouter: LlmInferenceRouter,
    private val ragEngine: RagEngine,
    private val toolExecutor: ToolExecutor,
    private val toolRepository: ToolRepository
) {
    companion object {
        private val PLANNER_PROMPT = """
            Eres un planificador multi-agente. Dado un objetivo, genera un plan de ejecución.
            Responde EXCLUSIVAMENTE con JSON válido, sin ningún texto fuera del JSON.
            Esquema requerido:
            {
              "steps": [
                {
                  "stepIndex": 0,
                  "role": "ANALISTA|INVESTIGADOR|PROGRAMADOR|REDACTOR|SINTETIZADOR",
                  "instruction": "instrucción completa para el agente",
                  "dependsOnSteps": [],
                  "requiresAudit": false
                }
              ]
            }
            Reglas:
            - stepIndex empieza en 0 y es consecutivo
            - El último paso suele ser SINTETIZADOR o REDACTOR para generar el entregable final
            - requiresAudit=true solo para pasos críticos (código, datos estructurados)
            - NUNCA incluyas al AUDITOR como paso del plan (es invocado automáticamente)
        """.trimIndent()

        private const val MAX_TOOL_ITERATIONS = 6
    }

    // ── API pública ────────────────────────────────────────────────────────────

    fun executeWorkflow(
        goal: String,
        projectId: String,
        provider: ApiProvider? = null
    ): Flow<OrchestratorEvent> = flow {

        emit(OrchestratorEvent.PlanningStarted(goal))

        // Cargar herramientas habilitadas. Si falla, el workflow se cancela con error visible.
        val enabledTools = try {
            toolRepository.getEnabledTools()
        } catch (e: Exception) {
            emit(OrchestratorEvent.WorkflowFailed(
                "No se pudo cargar el catálogo de herramientas: ${e.message}"
            ))
            return@flow
        }

        val resolvedProvider = provider ?: ApiProvider.GROQ
        val plan = planTask(goal, resolvedProvider)
        emit(OrchestratorEvent.PlanReady(plan))

        if (plan.steps.isEmpty()) {
            emit(OrchestratorEvent.WorkflowFailed("El planificador no generó ningún paso"))
            return@flow
        }

        val blackboard = mutableMapOf<Int, String>()

        for (step in plan.steps.sortedBy { it.stepIndex }) {
            emit(OrchestratorEvent.StepStarted(step))

            val allowedTools = enabledTools.filter { it.name in step.role.allowedTools }

            val ragCtx = try {
                ragEngine.search(step.instruction, projectId.hashCode().toLong(), 5)
                    .joinToString("\n---\n") { it.textPayload }
            } catch (e: Exception) { "" }

            val priorCtx = step.dependsOnSteps
                .mapNotNull { blackboard[it] }
                .joinToString("\n\n")

            val contextBlock = buildString {
                if (ragCtx.isNotEmpty()) {
                    appendLine("=== CONTEXTO RAG ==="); appendLine(ragCtx)
                }
                if (priorCtx.isNotEmpty()) {
                    appendLine("=== RESULTADOS DE PASOS PREVIOS ==="); appendLine(priorCtx)
                }
            }

            val agentProvider = provider ?: llmRouter.selectProvider(
                estimatedTokens = contextBlock.length / 4,
                taskType = step.role.toTaskType()
            )

            val output = runToolUseLoop(
                step         = step,
                initialCtx   = contextBlock,
                allowedTools = allowedTools,
                provider     = agentProvider,
                onToolCall   = { toolName, argsJson, result ->
                    emit(OrchestratorEvent.ToolExecuted(toolName, argsJson, result))
                }
            )

            val finalOutput = if (step.requiresAudit && output.isNotBlank()) {
                emit(OrchestratorEvent.AuditStarted(step))
                val audit = auditOutput(step.instruction, output, agentProvider)
                if (!audit.approved) {
                    emit(OrchestratorEvent.AuditCorrected(step, audit.issues))
                    audit.correctedOutput ?: output
                } else output
            } else output

            if (finalOutput.isNotBlank()) {
                try {
                    ragEngine.indexDocument(
                        projectId      = projectId.hashCode().toLong(),
                        documentNodeId = "workflow_step_${step.stepIndex}_${System.currentTimeMillis()}",
                        text           = finalOutput
                    )
                } catch (_: Exception) {}
            }
            blackboard[step.stepIndex] = finalOutput
            emit(OrchestratorEvent.StepDone(step, finalOutput))
        }

        val lastIndex   = plan.steps.maxOf { it.stepIndex }
        val finalOutput = blackboard[lastIndex] ?: ""
        emit(OrchestratorEvent.WorkflowDone(finalOutput))

    }.flowOn(Dispatchers.IO)

    // ── Loop de tool-use ──────────────────────────────────────────────────────

    private suspend fun runToolUseLoop(
        step: WorkflowStep,
        initialCtx: String,
        allowedTools: List<Tool>,
        provider: ApiProvider,
        onToolCall: suspend (toolName: String, argsJson: String, result: String) -> Unit
    ): String {

        val systemPrompt = buildAgentSystemPrompt(step.role, allowedTools)

        val messages = mutableListOf<LlmMessage>(
            LlmMessage("user", buildString {
                appendLine(step.instruction)
                if (initialCtx.isNotBlank()) {
                    appendLine()
                    append(initialCtx)
                }
            })
        )

        var lastOutput = ""

        repeat(MAX_TOOL_ITERATIONS) { _ ->
            var iterOutput = ""
            var inferenceError: String? = null

            llmRouter.streamCompletion(
                messages     = messages,
                systemPrompt = systemPrompt,
                provider     = provider,
                temperature  = step.role.temperature
            ).collect { event ->
                when (event) {
                    is StreamEvent.Token -> iterOutput += event.text
                    is StreamEvent.Error -> inferenceError = event.message
                    else -> Unit
                }
            }

            if (inferenceError != null || iterOutput.isBlank()) {
                return lastOutput.ifBlank { iterOutput }
            }

            if (!ToolCallParser.hasToolCall(iterOutput)) {
                lastOutput = ToolCallParser.stripToolCalls(iterOutput)
                return lastOutput
            }

            val toolCalls = ToolCallParser.extractAll(iterOutput)
            val toolResultsBlock = StringBuilder()

            for (call in toolCalls) {
                val tool = allowedTools.firstOrNull { it.name == call.name }
                val resultJson = if (tool == null) {
                    """{"error": "Tool '${call.name}' no disponible para este agente"}"""
                } else {
                    when (val r = toolExecutor.execute(tool, call.argsJson)) {
                        is ToolResult.Success -> r.outputJson
                        is ToolResult.Error   -> """{"error": "${r.message}"}"""
                        else                  -> """{"error": "resultado desconocido"}"""
                    }
                }

                onToolCall(call.name, call.argsJson, resultJson)

                toolResultsBlock.appendLine("=== RESULTADO DE ${call.name} ===")
                toolResultsBlock.appendLine(resultJson)
                toolResultsBlock.appendLine()
            }

            val cleanOutput = ToolCallParser.stripToolCalls(iterOutput)

            messages.add(LlmMessage("assistant", iterOutput))
            messages.add(LlmMessage("user",
                "Resultados de las herramientas ejecutadas:\n\n$toolResultsBlock\n" +
                "Continuá con tu tarea usando esta información. Si ya tenés todo lo necesario, " +
                "responde con el resultado final sin usar más herramientas."))

            lastOutput = cleanOutput
        }

        return lastOutput
    }

    // ── System prompt con tools ───────────────────────────────────────────────

    private fun buildAgentSystemPrompt(role: AgentRole, tools: List<Tool>): String {
        if (tools.isEmpty()) return role.systemPrompt

        val toolsCatalog = tools.joinToString("\n\n") { tool ->
            buildString {
                appendLine("• **${tool.name}**: ${tool.description}")
                appendLine("  Input schema: ${tool.inputSchema}")
                appendLine("  Output schema: ${tool.outputSchema}")
            }
        }

        return buildString {
            appendLine(role.systemPrompt)
            appendLine()
            appendLine("═══════════════════════════════════════")
            appendLine("HERRAMIENTAS DISPONIBLES:")
            appendLine("═══════════════════════════════════════")
            appendLine(toolsCatalog)
            appendLine()
            appendLine("Para usar una herramienta, incluí en tu respuesta:")
            appendLine("<tool_call>")
            appendLine("""{"name": "nombre_exacto", "args": { ...argumentos... }}""")
            appendLine("</tool_call>")
            appendLine()
            appendLine("Podés usar múltiples herramientas en secuencia.")
            appendLine("Cuando ya tengas toda la información necesaria, respondé sin usar más herramientas.")
        }
    }

    // ── Planificación ─────────────────────────────────────────────────────────

    private suspend fun planTask(goal: String, provider: ApiProvider): WorkflowPlan {
        var json = ""
        llmRouter.streamCompletion(
            messages     = listOf(LlmMessage("user", "OBJETIVO: $goal")),
            systemPrompt = PLANNER_PROMPT,
            provider     = provider
        ).collect { event ->
            if (event is StreamEvent.Token) json += event.text
        }
        return parseWorkflowPlan(goal, json.trim())
    }

    private fun parseWorkflowPlan(goal: String, json: String): WorkflowPlan {
        return try {
            val clean = json
                .substringAfter("```json\n", json)
                .substringAfter("```\n", json)
                .substringBefore("```")
                .trim()

            val root  = Json.parseToJsonElement(clean).jsonObject
            val steps = root["steps"]!!.jsonArray.mapIndexed { i, el ->
                val o = el.jsonObject
                WorkflowStep(
                    stepIndex      = o["stepIndex"]?.jsonPrimitive?.intOrNull ?: i,
                    role           = AgentRole.valueOf(o["role"]!!.jsonPrimitive.content.uppercase()),
                    instruction    = o["instruction"]!!.jsonPrimitive.content,
                    dependsOnSteps = o["dependsOnSteps"]?.jsonArray?.map { it.jsonPrimitive.int } ?: emptyList(),
                    requiresAudit  = o["requiresAudit"]?.jsonPrimitive?.booleanOrNull ?: false
                )
            }
            WorkflowPlan(UUID.randomUUID().toString(), goal, steps, WorkflowStatus.PENDING)
        } catch (e: Exception) {
            WorkflowPlan(
                id       = UUID.randomUUID().toString(),
                userGoal = goal,
                steps    = listOf(WorkflowStep(0, AgentRole.SINTETIZADOR, goal))
            )
        }
    }

    // ── Auditoría ─────────────────────────────────────────────────────────────

    private data class AuditResult(val approved: Boolean, val issues: List<String>, val correctedOutput: String?)

    private suspend fun auditOutput(instruction: String, output: String, provider: ApiProvider): AuditResult {
        var json = ""
        llmRouter.streamCompletion(
            messages = listOf(LlmMessage("user",
                "INSTRUCCIÓN ORIGINAL:\n$instruction\n\nOUTPUT A AUDITAR:\n$output")),
            systemPrompt = AgentRole.AUDITOR.systemPrompt,
            provider     = provider,
            temperature  = AgentRole.AUDITOR.temperature
        ).collect { event -> if (event is StreamEvent.Token) json += event.text }

        return try {
            val o = Json { ignoreUnknownKeys = true }.parseToJsonElement(json.trim()).jsonObject
            AuditResult(
                approved        = o["approved"]?.jsonPrimitive?.booleanOrNull ?: true,
                issues          = o["issues"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                correctedOutput = o["correctedOutput"]?.jsonPrimitive?.contentOrNull
            )
        } catch (e: Exception) {
            AuditResult(true, emptyList(), null)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun AgentRole.toTaskType(): TaskType = when (this) {
        AgentRole.PROGRAMADOR  -> TaskType.CODE_GENERATION
        AgentRole.ANALISTA     -> TaskType.REASONING
        AgentRole.INVESTIGADOR -> TaskType.DOCUMENT_ANALYSIS
        else                   -> TaskType.FAST_CHAT
    }
}
