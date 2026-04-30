package com.fenix.ia.orchestrator

import com.fenix.ia.data.local.objectbox.RagEngine
import com.fenix.ia.data.remote.LlmInferenceRouter
import com.fenix.ia.data.remote.LlmMessage
import com.fenix.ia.data.remote.StreamEvent
import com.fenix.ia.data.remote.TaskType
import com.fenix.ia.domain.model.AgentRole
import com.fenix.ia.domain.model.ApiProvider
import com.fenix.ia.domain.model.WorkflowPlan
import com.fenix.ia.domain.model.WorkflowStep
import com.fenix.ia.domain.model.WorkflowStatus
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
 * Implementa el patrón Blackboard: cada paso deposita su output en un mapa
 * indexado por stepIndex. Los pasos siguientes acceden al contexto de sus
 * dependencias via [dependsOnSteps].
 *
 * Flujo por paso:
 *   1. Recuperar contexto RAG relevante (búsqueda semántica en ObjectBox)
 *   2. Recuperar outputs de pasos previos del Blackboard
 *   3. Inferencia del agente (LLM con systemPrompt y temperature del rol)
 *   4. Audit opcional vía AUDITOR (temperatura 0.0)
 *   5. Indexar output en RagEngine (Blackboard persistente)
 *   6. Emitir StepDone
 */
@Singleton
class OrchestratorEngine @Inject constructor(
    private val llmRouter: LlmInferenceRouter,
    private val ragEngine: RagEngine
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
    }

    /**
     * Ejecuta un workflow completo para el objetivo dado.
     * Emite [OrchestratorEvent] a medida que avanza.
     *
     * @param goal       Objetivo en lenguaje natural
     * @param projectId  ID del proyecto (para RAG y Blackboard persistente)
     * @param provider   Proveedor LLM preferido (null = selección automática)
     */
    fun executeWorkflow(
        goal: String,
        projectId: String,
        provider: ApiProvider? = null
    ): Flow<OrchestratorEvent> = flow {

        emit(OrchestratorEvent.PlanningStarted(goal))

        // ── PASO 1: Planificación ──────────────────────────────────────────────
        val resolvedProvider = provider ?: ApiProvider.GROQ
        val plan = planTask(goal, resolvedProvider)
        emit(OrchestratorEvent.PlanReady(plan))

        if (plan.steps.isEmpty()) {
            emit(OrchestratorEvent.WorkflowFailed("El planificador no generó ningún paso"))
            return@flow
        }

        // ── PASO 2: Ejecución en orden topológico ─────────────────────────────
        val blackboard = mutableMapOf<Int, String>()   // stepIndex → output

        for (step in plan.steps.sortedBy { it.stepIndex }) {
            emit(OrchestratorEvent.StepStarted(step))

            // Contexto RAG: búsqueda semántica en ObjectBox
            val ragChunks = try {
                ragEngine.search(step.instruction, projectId.hashCode().toLong(), 5)
            } catch (e: Exception) { emptyList() }

            val ragCtx = ragChunks.joinToString("\n---\n") { it.textPayload }

            // Contexto Blackboard: outputs de pasos previos declarados como dependencia
            val priorCtx = step.dependsOnSteps
                .mapNotNull { blackboard[it] }
                .joinToString("\n\n")

            val fullCtx = buildString {
                if (ragCtx.isNotEmpty()) {
                    appendLine("=== CONTEXTO RAG ===")
                    appendLine(ragCtx)
                }
                if (priorCtx.isNotEmpty()) {
                    appendLine("=== RESULTADOS DE PASOS PREVIOS ===")
                    appendLine(priorCtx)
                }
            }

            // ── PASO 3: Inferencia del agente ─────────────────────────────────
            val agentProvider = provider ?: llmRouter.selectProvider(
                estimatedTokens = fullCtx.length / 4,
                taskType = step.role.toTaskType()
            )

            var output = ""
            var inferenceError: String? = null

            llmRouter.streamCompletion(
                messages = listOf(
                    LlmMessage("user", buildString {
                        appendLine(step.instruction)
                        if (fullCtx.isNotBlank()) {
                            appendLine()
                            append(fullCtx)
                        }
                    })
                ),
                systemPrompt = step.role.systemPrompt,
                provider = agentProvider,
                temperature = step.role.temperature
            ).collect { event ->
                when (event) {
                    is StreamEvent.Token -> output += event.text
                    is StreamEvent.Error -> inferenceError = event.message
                    else -> Unit
                }
            }

            if (inferenceError != null) {
                emit(OrchestratorEvent.StepError(step, inferenceError!!))
                // Continúa con output vacío — el siguiente paso lo ve en blackboard como ""
            }

            // ── PASO 4: Audit opcional ────────────────────────────────────────
            if (step.requiresAudit && output.isNotBlank()) {
                emit(OrchestratorEvent.AuditStarted(step))
                val audit = auditOutput(step.instruction, output, agentProvider)
                if (!audit.approved) {
                    output = audit.correctedOutput ?: output
                    emit(OrchestratorEvent.AuditCorrected(step, audit.issues))
                }
            }

            // ── PASO 5: Persistir en Blackboard ──────────────────────────────
            if (output.isNotBlank()) {
                try {
                    ragEngine.indexDocument(
                        projectId     = projectId.hashCode().toLong(),
                        documentNodeId = "workflow_step_${step.stepIndex}_${System.currentTimeMillis()}",
                        text          = output
                    )
                } catch (e: Exception) {
                    // No bloquear el workflow si falla el indexado
                }
            }
            blackboard[step.stepIndex] = output
            emit(OrchestratorEvent.StepDone(step, output))
        }

        // ── PASO 6: Output final ──────────────────────────────────────────────
        val lastIndex = plan.steps.maxOf { it.stepIndex }
        val finalOutput = blackboard[lastIndex] ?: ""
        emit(OrchestratorEvent.WorkflowDone(finalOutput))

    }.flowOn(Dispatchers.IO)

    // ── Planificación ──────────────────────────────────────────────────────────

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
            // Extrae el bloque JSON si el LLM envolvió la respuesta en ```json ... ```
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
                    role           = AgentRole.valueOf(
                        o["role"]!!.jsonPrimitive.content.uppercase()
                    ),
                    instruction    = o["instruction"]!!.jsonPrimitive.content,
                    dependsOnSteps = o["dependsOnSteps"]?.jsonArray
                        ?.map { it.jsonPrimitive.int } ?: emptyList(),
                    requiresAudit  = o["requiresAudit"]?.jsonPrimitive?.booleanOrNull ?: false
                )
            }
            WorkflowPlan(
                id         = UUID.randomUUID().toString(),
                userGoal   = goal,
                steps      = steps,
                status     = WorkflowStatus.PENDING
            )
        } catch (e: Exception) {
            // Fallback: paso único con SINTETIZADOR
            WorkflowPlan(
                id       = UUID.randomUUID().toString(),
                userGoal = goal,
                steps    = listOf(
                    WorkflowStep(
                        stepIndex   = 0,
                        role        = AgentRole.SINTETIZADOR,
                        instruction = goal
                    )
                )
            )
        }
    }

    // ── Auditoría ──────────────────────────────────────────────────────────────

    private data class AuditResult(
        val approved: Boolean,
        val issues: List<String>,
        val correctedOutput: String?
    )

    private suspend fun auditOutput(
        instruction: String,
        output: String,
        provider: ApiProvider
    ): AuditResult {
        var json = ""
        llmRouter.streamCompletion(
            messages = listOf(
                LlmMessage(
                    "user",
                    "INSTRUCCIÓN ORIGINAL:\n$instruction\n\nOUTPUT A AUDITAR:\n$output"
                )
            ),
            systemPrompt = AgentRole.AUDITOR.systemPrompt,
            provider     = provider,
            temperature  = AgentRole.AUDITOR.temperature
        ).collect { event ->
            if (event is StreamEvent.Token) json += event.text
        }

        return try {
            val o = Json { ignoreUnknownKeys = true }
                .parseToJsonElement(json.trim()).jsonObject
            AuditResult(
                approved        = o["approved"]?.jsonPrimitive?.booleanOrNull ?: true,
                issues          = o["issues"]?.jsonArray
                    ?.map { it.jsonPrimitive.content } ?: emptyList(),
                correctedOutput = o["correctedOutput"]?.jsonPrimitive?.contentOrNull
            )
        } catch (e: Exception) {
            AuditResult(approved = true, issues = emptyList(), correctedOutput = null)
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Mapea el rol del agente al TaskType del LlmRouter para selección óptima de proveedor. */
    private fun AgentRole.toTaskType(): TaskType = when (this) {
        AgentRole.PROGRAMADOR  -> TaskType.CODE_GENERATION
        AgentRole.ANALISTA     -> TaskType.REASONING
        AgentRole.INVESTIGADOR -> TaskType.DOCUMENT_ANALYSIS
        else                   -> TaskType.FAST_CHAT
    }
}
