package com.fenix.ia.presentation.sistema

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.fenix.ia.audit.AuditLogger
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SistemaFenixScreen(
    onBack: () -> Unit,
    onOpenMenu: (() -> Unit)? = null,
    viewModel: SistemaFenixViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        viewModel.importDocuments(uris)
    }

    LaunchedEffect(Unit) {
        AuditLogger.screen("sistema_fenix")
    }
    LaunchedEffect(state.statusMessage, state.errorMessage) {
        val text = state.errorMessage.ifBlank { state.statusMessage }
        if (text.isNotBlank()) {
            AuditLogger.visibleMessage("sistema_fenix", text)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sistema Fenix") },
                navigationIcon = {
                    if (onOpenMenu != null) {
                        IconButton(onClick = onOpenMenu) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    } else {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.resetRun() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Nueva ejecucion")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .testTag("sistema_fenix_screen"),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Procesador documental",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Carga documentos, indica que queres procesar y FENIX genera un DOCX auditable con evidencia.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            AuditLogger.action("sistema_fenix_pick_documents")
                            documentPicker.launch(
                                arrayOf(
                                    "application/pdf",
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                    "text/plain",
                                    "text/markdown",
                                    "image/jpeg",
                                    "image/png"
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isProcessing
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Cargar documentos", modifier = Modifier.padding(start = 8.dp))
                    }

                    OutlinedTextField(
                        value = state.specification,
                        onValueChange = viewModel::updateSpecification,
                        label = { Text("Especificacion") },
                        placeholder = { Text("Ej: Procesar solo Unidad 1 del programa de Teoria de Intervencion") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        enabled = !state.isProcessing
                    )

                    Button(
                        onClick = {
                            AuditLogger.action("sistema_fenix_process_pressed")
                            viewModel.process()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isProcessing
                    ) {
                        Icon(Icons.Default.LocalFireDepartment, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Procesar con Sistema Fenix", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }

            IncandescentProgress(
                label = state.phaseLabel,
                progress = state.progress,
                isProcessing = state.isProcessing
            )

            if (state.errorMessage.isNotBlank()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = state.errorMessage,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            state.artifact?.let { artifact ->
                ArtifactCard(
                    artifact = artifact,
                    onOpen = {
                        AuditLogger.action("sistema_fenix_open_docx", mapOf("path" to artifact.path))
                        openArtifact(context, artifact.path)
                    }
                )
            }

            if (state.documents.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Documentos cargados", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        state.documents.forEach { doc ->
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(20.dp))
                                Column(Modifier.padding(start = 10.dp)) {
                                    Text(doc.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${doc.sizeBytes / 1024} KB · ${doc.sha256.take(12)}...", style = MaterialTheme.typography.labelSmall)
                                    if (doc.textPreview.isBlank()) {
                                        Text("Sin texto extraido todavia", style = MaterialTheme.typography.bodySmall)
                                    } else {
                                        Text(doc.textPreview.take(180), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (state.history.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Historial Sistema Fenix", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        state.history.forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { openArtifact(context, item.path) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.InsertDriveFile, contentDescription = null, modifier = Modifier.size(20.dp))
                                Column(Modifier.padding(start = 10.dp)) {
                                    Text(item.name)
                                    Text("${item.sizeBytes / 1024} KB · ${item.sha256.take(12)}...", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IncandescentProgress(label: String, progress: Float, isProcessing: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.LocalFireDepartment,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = label,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(18.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress.coerceIn(0.02f, 1f))
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.error,
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary
                                )
                            )
                        )
                )
            }
            Text(
                text = if (isProcessing) "FENIX esta trabajando. Podes dejar esta pantalla abierta hasta recibir el DOCX." else "Cuando termine, el archivo aparecera abajo y quedara en Archivos creados.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ArtifactCard(artifact: SistemaFenixArtifactUi, onOpen: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.InsertDriveFile, contentDescription = null, modifier = Modifier.size(32.dp))
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text("DOCX final listo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(artifact.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${artifact.sizeBytes / 1024} KB · ${artifact.sha256.take(16)}...", style = MaterialTheme.typography.labelSmall)
                Text("Toca para abrir o compartir", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun openArtifact(context: android.content.Context, path: String) {
    val file = File(path)
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(intent, "Abrir DOCX Sistema Fenix")
    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}
