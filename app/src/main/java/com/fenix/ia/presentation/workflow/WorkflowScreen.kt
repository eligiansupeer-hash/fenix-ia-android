package com.fenix.ia.presentation.workflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fenix.ia.orchestrator.OrchestratorEvent
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowScreen(
    projectId: String,
    onBack: () -> Unit,
    vm: WorkflowViewModel = hiltViewModel()
) {
    val state   by vm.uiState.collectAsState()
    var goal    by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope   = rememberCoroutineScope()

    // Auto-scroll al último evento
    LaunchedEffect(state.events.size) {
        if (state.events.isNotEmpty()) {
            scope.launch { listState.animateScrollToItem(state.events.size - 1) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agentes autónomos") },
                navigationIcon = {
                    IconButton(onClick = {
                        vm.stop()
                        onBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {

            Spacer(Modifier.height(8.dp))

            // ── Input de objetivo ──────────────────────────────────────────────
            OutlinedTextField(
                value       = goal,
                onValueChange = { goal = it },
                label       = { Text("¿Qué querés lograr?") },
                placeholder = { Text("Ej: Investigar y redactar un informe sobre energía solar") },
                modifier    = Modifier.fillMaxWidth(),
                maxLines    = 4,
                enabled     = !state.isRunning
            )

            Spacer(Modifier.height(8.dp))

            // ── Botón iniciar / detener ────────────────────────────────────────
            Button(
                onClick = {
                    if (state.isRunning) vm.stop()
                    else { vm.reset(); vm.start(goal, projectId) }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled  = state.isRunning || goal.isNotBlank(),
                colors   = if (state.isRunning)
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                else
                    ButtonDefaults.buttonColors()
            ) {
                Icon(
                    imageVector = if (state.isRunning) Icons.Default.Stop else Icons.Default.FlashOn,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(if (state.isRunning) "Detener" else "Ejecutar workflow")
            }

            Spacer(Modifier.height(12.dp))

            // ── Grafo de nodos ─────────────────────────────────────────────────
            val planSteps = state.events
                .filterIsInstance<OrchestratorEvent.PlanReady>()
                .lastOrNull()?.plan?.steps ?: emptyList()

            if (planSteps.isNotEmpty()) {
                NodeGraphCanvas(
                    steps       = planSteps,
                    currentStep = state.currentStep,
                    modifier    = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
            }

            // Barra de progreso mientras corre
            if (state.isRunning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }

            HorizontalDivider()
            Spacer(Modifier.height(4.dp))

            // ── Log de eventos ─────────────────────────────────────────────────
            LazyColumn(
                state  = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(state.events) { event ->
                    Text(
                        text  = event.toDisplayString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = when (event) {
                            is OrchestratorEvent.StepError,
                            is OrchestratorEvent.WorkflowFailed ->
                                MaterialTheme.colorScheme.error
                            is OrchestratorEvent.WorkflowDone ->
                                MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }

            // ── Output final ───────────────────────────────────────────────────
            if (state.output.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))
                Text(
                    text  = "Resultado",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text     = state.output,
                        modifier = Modifier.padding(12.dp),
                        style    = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            // ── Error fatal ────────────────────────────────────────────────────
            state.error?.let { err ->
                Spacer(Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors   = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text  = err,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
