package com.fenix.ia.presentation.research

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fenix.ia.research.DeepResearchEngine
import com.fenix.ia.research.ResearchEvent
import com.fenix.ia.research.toDisplayString
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel de la pantalla de Deep Research.
 *
 * Responsabilidades:
 * - Mantener el estado de UI (topic, depth, eventos, síntesis, fuentes)
 * - Lanzar / cancelar la investigación contra [DeepResearchEngine]
 * - Convertir [ResearchEvent] en strings para el log de UI
 */
@HiltViewModel
class ResearchViewModel @Inject constructor(
    private val engine: DeepResearchEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(ResearchUiState())
    val uiState: StateFlow<ResearchUiState> = _uiState.asStateFlow()

    private var researchJob: Job? = null

    fun onTopicChange(value: String) {
        _uiState.update { it.copy(topic = value) }
    }

    fun onDepthChange(value: Int) {
        _uiState.update { it.copy(depth = value.coerceIn(1, 3)) }
    }

    fun start(projectId: String) {
        val topic = _uiState.value.topic.trim()
        if (topic.isBlank()) return

        researchJob?.cancel()
        _uiState.update { it.copy(
            isRunning  = true,
            events     = emptyList(),
            synthesis  = "",
            sources    = emptyList(),
            error      = null
        )}

        researchJob = viewModelScope.launch {
            engine.research(
                topic     = topic,
                projectId = projectId,
                depth     = _uiState.value.depth
            ).collect { event ->
                _uiState.update { state ->
                    state.copy(
                        events    = state.events + event.toDisplayString(),
                        synthesis = if (event is ResearchEvent.Done) event.synthesis else state.synthesis,
                        sources   = if (event is ResearchEvent.Done) event.sources   else state.sources,
                        isRunning = event !is ResearchEvent.Done
                    )
                }
            }
        }
    }

    fun stop() {
        researchJob?.cancel()
        _uiState.update { it.copy(isRunning = false) }
    }
}

data class ResearchUiState(
    val topic:     String       = "",
    val depth:     Int          = 2,
    val isRunning: Boolean      = false,
    val events:    List<String> = emptyList(),
    val synthesis: String       = "",
    val sources:   List<String> = emptyList(),
    val error:     String?      = null
)
