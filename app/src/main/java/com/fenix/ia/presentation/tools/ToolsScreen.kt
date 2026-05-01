package com.fenix.ia.presentation.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fenix.ia.domain.model.Tool
import com.fenix.ia.domain.model.ToolExecutionType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    projectId: String,
    onBack: () -> Unit,
    vm: ToolsViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsState()
    var showCreate by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.loadTools() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Herramientas (${state.tools.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Default.Add, "Nueva herramienta")
            }
        }
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    "Catálogo global — las herramientas son invocadas automáticamente por los agentes de IA.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                Spacer(Modifier.height(4.dp))
            }
            items(state.tools, key = { it.id }) { tool ->
                ToolCard(
                    tool     = tool,
                    onToggle = { vm.toggle(tool) }
                )
            }
        }
    }

    // ── Diálogo crear herramienta con IA ────────────────────────────────────
    if (showCreate) {
        var desc by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("Nueva herramienta con IA") },
            text = {
                Column {
                    Text(
                        "Describí qué debe hacer la herramienta. La IA generará el código automáticamente.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = desc,
                        onValueChange = { desc = it },
                        label = { Text("Descripción") },
                        placeholder = { Text("Ej: Convertir texto a mayúsculas") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6
                    )
                    if (state.error != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            state.error!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.createWithAi(desc, projectId); showCreate = false },
                    enabled = desc.isNotBlank() && !state.isCreating
                ) { Text("Generar con IA") }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text("Cancelar") }
            }
        )
    }

    // Overlay de generación
    if (state.isCreating) {
        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Card(Modifier.padding(32.dp)) {
                Column(
                    Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Generando herramienta con IA...")
                    Text(
                        "El agente Programador está escribiendo el código",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

// ── ToolCard — solo toggle, sin "Probar" ─────────────────────────────────────

@Composable
private fun ToolCard(tool: Tool, onToggle: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tool.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.width(6.dp))
                    if (tool.isUserGenerated) {
                        SuggestionChip(
                            onClick = {},
                            label = { Text("creada por IA", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    tool.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    tool.executionType.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            // Solo el toggle — la IA invoca las tools, el usuario solo activa/desactiva
            Switch(checked = tool.isEnabled, onCheckedChange = { onToggle() })
        }
    }
}

// ── Extension — label legible para el tipo de ejecución ──────────────────────

val ToolExecutionType.label: String
    get() = when (this) {
        ToolExecutionType.NATIVE_KOTLIN -> "Kotlin nativo"
        ToolExecutionType.JAVASCRIPT    -> "JavaScript sandbox"
        ToolExecutionType.HTTP_EXTERNAL -> "HTTP externo"
    }
