package com.fenix.ia.presentation.projects

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fenix.ia.domain.model.Chat
import com.fenix.ia.domain.model.DocumentNode
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    projectId: String,
    onNavigateToChat: (chatId: String) -> Unit,
    onBack: () -> Unit,
    viewModel: ProjectDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(projectId) {
        viewModel.loadProject(projectId)
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is ProjectDetailEffect.NavigateToChat -> onNavigateToChat(effect.chatId)
                is ProjectDetailEffect.ShowError -> { /* TODO snackbar */ }
            }
        }
    }

    // File picker — acepta PDF, DOCX, TXT, MD, XLSX, imágenes
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        uris.forEach { uri ->
            viewModel.processIntent(ProjectDetailIntent.IngestDocument(uri))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.projectName.ifBlank { "Proyecto" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                    }
                },
                actions = {
                    // Botón cargar documentos
                    IconButton(onClick = {
                        filePicker.launch(arrayOf(
                            "application/pdf",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "text/plain",
                            "text/markdown",
                            "image/jpeg",
                            "image/png"
                        ))
                    }) {
                        Icon(Icons.Default.UploadFile, contentDescription = "Cargar documento")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.processIntent(ProjectDetailIntent.CreateChat) }
            ) {
                Icon(Icons.Default.Add, contentDescription = "Nuevo chat")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            // --- Sección Documentos ---
            if (uiState.documents.isNotEmpty()) {
                item {
                    Text(
                        text = "📂 Documentos",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                items(uiState.documents, key = { it.id }) { doc ->
                    DocumentCard(
                        document = doc,
                        onToggle = { viewModel.processIntent(ProjectDetailIntent.ToggleDocumentCheckpoint(doc.id, !doc.isChecked)) },
                        onDelete = { viewModel.processIntent(ProjectDetailIntent.DeleteDocument(doc.id)) }
                    )
                }
                item { Divider(modifier = Modifier.padding(vertical = 8.dp)) }
            }

            if (uiState.isIngesting) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Procesando documento...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // --- Sección Chats ---
            item {
                Text(
                    text = "💬 Chats",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            if (uiState.chats.isEmpty()) {
                item {
                    Text(
                        text = "No hay chats. Tocá + para crear uno.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(8.dp)
                    )
                }
            } else {
                items(uiState.chats, key = { it.id }) { chat ->
                    ChatCard(
                        chat = chat,
                        onSelect = { viewModel.processIntent(ProjectDetailIntent.SelectChat(chat)) },
                        onDelete = { viewModel.processIntent(ProjectDetailIntent.DeleteChat(chat.id)) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatCard(chat: Chat, onSelect: () -> Unit, onDelete: () -> Unit) {
    Card(onClick = onSelect, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = chat.title, style = MaterialTheme.typography.bodyLarge)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Eliminar chat", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentCard(document: DocumentNode, onToggle: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = document.isChecked, onCheckedChange = { onToggle() })
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = document.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                if (document.semanticSummary.isNotBlank()) {
                    Text(
                        text = document.semanticSummary.take(80),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Eliminar", modifier = Modifier.size(18.dp))
            }
        }
    }
}
