package com.fenix.ia.orchestrator

import com.fenix.ia.domain.model.WorkflowPlan
import com.fenix.ia.domain.model.WorkflowStep

/**
 * Eventos emitidos por OrchestratorEngine como Flow.
 * La UI (WorkflowScreen) los consume para actualizar el grafo de nodos en tiempo real.
 */
sealed class OrchestratorEvent {

    /** El orquestador recibió el objetivo y empezó a planificar. */
    data class PlanningStarted(val goal: String) : OrchestratorEvent()

    /** El LLM generó el plan completo de pasos. */
    data class PlanReady(val plan: WorkflowPlan) : OrchestratorEvent()

    /** Un paso específico comenzó su ejecución. */
    data class StepStarted(val step: WorkflowStep) : OrchestratorEvent()

    /** Un paso terminó y produjo output. */
    data class StepDone(val step: WorkflowStep, val output: String) : OrchestratorEvent()

    /** Un paso falló. El workflow puede continuar o abortar según la política. */
    data class StepError(val step: WorkflowStep, val error: String) : OrchestratorEvent()

    /** El AUDITOR comenzó a verificar el output de un paso. */
    data class AuditStarted(val step: WorkflowStep) : OrchestratorEvent()

    /** El AUDITOR detectó problemas y aplicó correcciones al output. */
    data class AuditCorrected(
        val step: WorkflowStep,
        val issues: List<String>
    ) : OrchestratorEvent()

    /** Todos los pasos completados. finalOutput es el resultado del último paso. */
    data class WorkflowDone(val finalOutput: String) : OrchestratorEvent()

    /** El workflow falló de forma irrecuperable. */
    data class WorkflowFailed(val reason: String) : OrchestratorEvent()
}
