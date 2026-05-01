package com.fenix.ia.presentation.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fenix.ia.domain.model.Tool
import com.fenix.ia.domain.model.ToolExecutionType
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
    var testingTool by remember { mutableStateOf<Tool?>(null) }
    var testQuery by remember { mutableStateOf("") }

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
                    "Las herramientas son usadas automáticamente por los agentes de IA.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                Spacer(Modifier.height(4.dp))
            }
            items(state.tools, key = { it.id }) { tool ->
                ToolCard(
                    tool     = tool,
                    onToggle = { vm.toggle(tool) },
                    onTest   = {
                        testingTool = tool
                        testQuery = ""
                    }
                )
            }
        }
    }

    // ── Diálogo de prueba — lenguaje natural, sin JSON ──────────────────────
    testingTool?.let { tool ->
        AlertDialog(
            onDismissRequest = { testingTool = null; vm.clearResult() },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Probar: ")
                    Text(
                        tool.name,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            text = {
                Column {
                    // Descripción legible — no el schema crudo
                    Text(
                        tool.description,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tipo: ${tool.executionType.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(Modifier.height(12.dp))

                    // Input en lenguaje natural
                    OutlinedTextField(
                        value = testQuery,
                        onValueChange = { testQuery = it },
                        label = { Text(tool.examplePrompt) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                        placeholder = { Text(tool.exampleInput, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)) }
                    )

                    // Resultado
                    if (state.isExecuting) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Ejecutando...", style = MaterialTheme.typography.bodySmall)
                    }

                    state.lastResult?.let { result ->
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        when (result) {
                            is ToolResult.Success -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("Resultado:", style = MaterialTheme.typography.labelMedium)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    result.outputJson.take(400),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            is ToolResult.Error -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Error,
                                        null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        result.message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            else -> {}
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.executeNatural(tool, testQuery, projectId) },
                    enabled = testQuery.isNotBlank() && !state.isExecuting
                ) {
                    Text("Ejecutar")
                }
            },
            dismissButton = {
                TextButton(onClick = { testingTool = null; vm.clearResult() }) {
                    Text("Cerrar")
                }
            }
        )
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

// ── ToolCard ─────────────────────────────────────────────────────────────────

@Composable
private fun ToolCard(tool: Tool, onToggle: () -> Unit, onTest: () -> Unit) {
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
            Column(horizontalAlignment = Alignment.End) {
                Switch(checked = tool.isEnabled, onCheckedChange = { onToggle() })
                TextButton(onClick = onTest, enabled = tool.isEnabled) {
                    Text("Probar")
                }
            }
        }
    }
}

// ── Extensiones de UI sobre el dominio ───────────────────────────────────────

/** Label legible para el tipo de ejecución. */
val ToolExecutionType.label: String get() = when (this) {
    ToolExecutionType.NATIVE_KOTLIN -> "Kotlin nativo"
    ToolExecutionType.JAVASCRIPT    -> "JavaScript sandbox"
    ToolExecutionType.HTTP_EXTERNAL -> "HTTP externo"
}

/** Placeholder de input adaptado a cada tool. */
val Tool.exampleInput: String get() = when (name) {
    "web_search"       -> "inteligencia artificial educación"
    "scrape_content"   -> "https://ejemplo.com"
    "summarize"        -> "Pegá acá el texto a resumir..."
    "translate"        -> "Hello, how are you? → español"
    "read_file"        -> "/sdcard/Download/documento.txt"
    "create_file"      -> "Mi nota importante"
    "store_knowledge"  -> "Concepto importante a recordar"
    "retrieve_context" -> "¿Qué sé sobre machine learning?"
    "run_code"         -> "return JSON.stringify({resultado: 42})"
    "deep_research"    -> "impacto de la IA en el trabajo"
    else               -> "Ingresá los datos..."
}

/** Etiqueta del campo de input adaptada a cada tool. */
val Tool.examplePrompt: String get() = when (name) {
    "web_search"       -> "¿Qué querés buscar?"
    "scrape_content"   -> "URL de la página"
    "summarize"        -> "Texto a resumir"
    "translate"        -> "Texto y idioma destino"
    "read_file"        -> "Ruta del archivo"
    "create_file"      -> "Contenido del archivo"
    "store_knowledge"  -> "Conocimiento a guardar"
    "retrieve_context" -> "¿Qué querés recordar?"
    "run_code"         -> "Código JavaScript ES6"
    "deep_research"    -> "Tema a investigar"
    else               -> "Datos de entrada"
}
