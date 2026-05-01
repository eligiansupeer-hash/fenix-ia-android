package com.fenix.ia.presentation.workflow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fenix.ia.domain.model.ApiProvider
import com.fenix.ia.orchestrator.OrchestratorEngine
import com.fenix.ia.orchestrator.OrchestratorEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WorkflowViewModel @Inject constructor(
    private val orchestrator: OrchestratorEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(WorkflowUiState())
    val uiState: StateFlow<WorkflowUiState> = _uiState.asStateFlow()

    private var job: Job? = null

    fun start(goal: String, projectId: String, provider: ApiProvider? = null) {
        if (goal.isBlank()) return
        job = viewModelScope.launch {
            _uiState.update {
                it.copy(isRunning = true, events = emptyList(), output = "", error = null)
            }
            orchestrator.executeWorkflow(goal, projectId, provider).collect { event ->
                _uiState.update { state ->
                    state.copy(
                        events = state.events + event,
                        currentStep = when (event) {
                            is OrchestratorEvent.StepStarted -> event.step.stepIndex
                            else -> state.currentStep
                        },
                        totalSteps = when (event) {
                            is OrchestratorEvent.PlanReady -> event.plan.steps.size
                            else -> state.totalSteps
                        },
                        output = when (event) {
                            is OrchestratorEvent.WorkflowDone -> event.finalOutput
                            else -> state.output
                        },
                        error = when (event) {
                            is OrchestratorEvent.WorkflowFailed -> event.reason
                            else -> state.error
                        },
                        isRunning = event !is OrchestratorEvent.WorkflowDone
                                 && event !is OrchestratorEvent.WorkflowFailed
                    )
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        _uiState.update { it.copy(isRunning = false) }
    }

    fun reset() {
        stop()
        _uiState.value = WorkflowUiState()
    }
}

data class WorkflowUiState(
    val isRunning: Boolean = false,
    val events: List<OrchestratorEvent> = emptyList(),
    val currentStep: Int = -1,
    val totalSteps: Int = 0,
    val output: String = "",
    val error: String? = null
)

/** Texto legible para mostrar cada evento en la lista de log. */
fun OrchestratorEvent.toDisplayString(): String = when (this) {
    is OrchestratorEvent.PlanningStarted -> "🧠 Planificando: $goal"
    is OrchestratorEvent.PlanReady       -> "📋 Plan listo: ${plan.steps.size} pasos"
    is OrchestratorEvent.StepStarted     -> "▶ Paso ${step.stepIndex + 1} — ${step.role.displayName}"
    is OrchestratorEvent.StepDone        -> "✅ Paso ${step.stepIndex + 1} completado"
    is OrchestratorEvent.StepError       -> "⚠️ Error en paso ${step.stepIndex + 1}: $error"
    is OrchestratorEvent.AuditStarted    -> "🔍 Auditando paso ${step.stepIndex + 1}..."
    is OrchestratorEvent.AuditCorrected  -> "✏️ Auditor aplicó ${issues.size} corrección(es)"
    is OrchestratorEvent.ToolExecuted    -> "🔧 Herramienta: $toolName → ${resultJson.take(60)}…"
    is OrchestratorEvent.WorkflowDone    -> "🎉 Workflow completado"
    is OrchestratorEvent.WorkflowFailed  -> "❌ Workflow fallido: $reason"
}
