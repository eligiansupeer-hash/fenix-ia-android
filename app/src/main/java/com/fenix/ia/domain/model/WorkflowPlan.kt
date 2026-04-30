package com.fenix.ia.domain.model

/**
 * Representa un plan de ejecución multi-agente generado por el Orquestador.
 *
 * @param id          UUID único del workflow
 * @param userGoal    Objetivo original expresado por el usuario
 * @param steps       Pasos ordenados topológicamente
 * @param status      Estado de ejecución actual
 */
data class WorkflowPlan(
    val id: String,
    val userGoal: String,
    val steps: List<WorkflowStep>,
    val status: WorkflowStatus = WorkflowStatus.PENDING
)

/**
 * Un paso atómico del workflow, asignado a un agente especializado.
 *
 * @param stepIndex       Índice de orden (0-based). El Orquestador ejecuta en orden ascendente.
 * @param role            Agente responsable de este paso
 * @param instruction     Instrucción completa para el agente
 * @param dependsOnSteps  Índices de pasos cuyos outputs este paso necesita como contexto
 * @param requiresAudit   Si true, el AUDITOR verifica el output antes de propagarlo al Blackboard
 */
data class WorkflowStep(
    val stepIndex: Int,
    val role: AgentRole,
    val instruction: String,
    val dependsOnSteps: List<Int> = emptyList(),
    val requiresAudit: Boolean = false
)

/**
 * Estados del ciclo de vida de un WorkflowPlan.
 *
 * PENDING  → creado, esperando ejecución
 * RUNNING  → al menos un paso en curso
 * PAUSED   → detenido por el usuario o por error recuperable
 * DONE     → todos los pasos completados con éxito
 * FAILED   → al menos un paso falló de forma no recuperable
 */
enum class WorkflowStatus {
    PENDING,
    RUNNING,
    PAUSED,
    DONE,
    FAILED
}
