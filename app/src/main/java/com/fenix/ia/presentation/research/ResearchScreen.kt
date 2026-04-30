package com.fenix.ia.presentation.research

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch

/**
 * Pantalla de Deep Research.
 *
 * Ruta: Routes.RESEARCH = "research/{projectId}"
 * Acceso: botón Search en la BottomAppBar de ProjectDetailScreen
 *
 * Funcionalidad:
 * - Input del tema + slider de profundidad (1–3 iteraciones)
 * - Botón Investigar / Detener
 * - Log scrollable de eventos en tiempo real
 * - Card de síntesis final con lista de fuentes
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResearchScreen(
    projectId: String,
    onBack: () -> Unit,
    vm: ResearchViewModel = hiltViewModel()
) {
    val state     by vm.uiState.collectAsState()
    val listState  = rememberLazyListState()
    val scope      = rememberCoroutineScope()

    // Auto-scroll al último evento del log
    LaunchedEffect(state.events.size) {
        if (state.events.isNotEmpty()) {
            scope.launch { listState.animateScrollToItem(state.events.size - 1) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Deep Research") },
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

            // ── Input del tema ─────────────────────────────────────────────────
            OutlinedTextField(
                value           = state.topic,
                onValueChange   = vm::onTopicChange,
                label           = { Text("Tema a investigar") },
                placeholder     = { Text("Ej: inteligencia artificial generativa en educación") },
                modifier        = Modifier.fillMaxWidth(),
                maxLines        = 3,
                enabled         = !state.isRunning
            )

            Spacer(Modifier.height(12.dp))

            // ── Slider de profundidad ──────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text  = "Profundidad: ${state.depth}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(120.dp)
                )
                Slider(
                    value         = state.depth.toFloat(),
                    onValueChange = { vm.onDepthChange(it.toInt()) },
                    valueRange    = 1f..3f,
                    steps         = 1,
                    enabled       = !state.isRunning,
                    modifier      = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── Botón Investigar / Detener ─────────────────────────────────────
            Button(
                onClick = { if (state.isRunning) vm.stop() else vm.start(projectId) },
                modifier = Modifier.fillMaxWidth(),
                enabled  = state.isRunning || state.topic.isNotBlank(),
                colors   = if (state.isRunning)
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                else
                    ButtonDefaults.buttonColors()
            ) {
                Icon(
                    imageVector        = if (state.isRunning) Icons.Default.Stop else Icons.Default.Search,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(if (state.isRunning) "Detener" else "Investigar")
            }

            Spacer(Modifier.height(8.dp))

            // Barra de progreso indeterminada mientras corre
            if (state.isRunning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
            }

            HorizontalDivider()
            Spacer(Modifier.height(4.dp))

            // ── Log de eventos ─────────────────────────────────────────────────
            LazyColumn(
                state   = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(state.events) { line ->
                    Text(
                        text     = line,
                        style    = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }

                // ── Síntesis final ─────────────────────────────────────────────
                if (state.synthesis.isNotBlank()) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text  = "Síntesis",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text     = state.synthesis,
                                modifier = Modifier.padding(12.dp),
                                style    = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                // ── Lista de fuentes ───────────────────────────────────────────
                if (state.sources.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text  = "Fuentes (${state.sources.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    items(state.sources) { url ->
                        Text(
                            text     = "• $url",
                            style    = MaterialTheme.typography.bodySmall,
                            color    = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}
