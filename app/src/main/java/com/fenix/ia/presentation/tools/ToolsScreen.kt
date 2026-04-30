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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fenix.ia.domain.model.Tool
import com.fenix.ia.tools.ToolResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    projectId: String,
    onBack: () -> Unit,
    vm: ToolsViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf<Tool?>(null) }

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
                Icon(Icons.Default.Add, "Nueva tool")
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
                    "Catálogo global — reactivo en tiempo real",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            items(state.tools, key = { it.id }) { tool ->
                ToolCard(
                    tool = tool,
                    onToggle = { vm.toggle(tool) },
                    onTest = { testing = tool }
                )
            }
        }
    }

    // Diálogo test de tool
    testing?.let { t ->
        var args by remember { mutableStateOf("{}") }
        AlertDialog(
            onDismissRequest = { testing = null },
            title = { Text("Probar: ${t.name}") },
            text = {
                Column {
                    Text(
                        "Input schema: ${t.inputSchema}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = args,
                        onValueChange = { args = it },
                        label = { Text("Args JSON") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4
                    )
                    if (state.lastResult != null) {
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        val resultText = when (val r = state.lastResult) {
                            is ToolResult.Success -> r.outputJson
                            is ToolResult.Error   -> "Error: ${r.message}"
                            else -> ""
                        }
                        Text(
                            "Resultado: $resultText",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.execute(t, args); testing = null }) {
                    Text("Ejecutar")
                }
            },
            dismissButton = {
                TextButton(onClick = { testing = null }) { Text("Cerrar") }
            }
        )
    }

    // Diálogo crear tool con IA
    if (showCreate) {
        var desc by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("Nueva herramienta con IA") },
            text = {
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("Describe qué debe hacer la herramienta") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.createWithAi(desc, projectId); showCreate = false },
                    enabled = desc.isNotBlank()
                ) { Text("Generar con IA") }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text("Cancelar") }
            }
        )
    }

    // Overlay de generación en progreso
    if (state.isCreating) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text("Generando herramienta con IA...", Modifier.padding(top = 8.dp))
            }
        }
    }

    // Mostrar error si existe
    if (state.error != null) {
        LaunchedEffect(state.error) {
            // El error se muestra inline; el ViewModel lo puede limpiar
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolCard(
    tool: Tool,
    onToggle: () -> Unit,
    onTest: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(tool.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    tool.description.take(80),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Text(
                    tool.executionType.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Switch(checked = tool.isEnabled, onCheckedChange = { onToggle() })
                TextButton(onClick = onTest) { Text("Probar") }
            }
        }
    }
}
