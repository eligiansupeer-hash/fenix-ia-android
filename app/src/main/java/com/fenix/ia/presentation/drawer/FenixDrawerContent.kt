package com.fenix.ia.presentation.drawer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fenix.ia.domain.model.Chat
import com.fenix.ia.domain.model.DocumentNode
import com.fenix.ia.domain.model.Project
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FenixDrawerContent(
    state: FenixDrawerState,
    onProjectSelected: (Project) -> Unit,
    onChatSelected: (Chat) -> Unit,
    onNewChatSelected: () -> Unit,
    onCreateProjectSelected: () -> Unit,
    onCreatedFileSelected: (CreatedFileItem) -> Unit,
    onUploadedDocumentSelected: (DocumentNode) -> Unit,
    onSistemaFenixSelected: () -> Unit,
    onSettingsSelected: () -> Unit,
    modifier: Modifier = Modifier
) {
    ModalDrawerSheet(modifier = modifier.fillMaxHeight().width(328.dp)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 16.dp)
        ) {
            Text(
                text = "FENIX IA",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Centro de trabajo",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = onNewChatSelected,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "Nuevo chat" }
                ) {
                    Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Nuevo chat")
                }
                TextButton(
                    onClick = onCreateProjectSelected,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "Crear proyecto" }
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Crear proyecto")
                }
            }
            Spacer(Modifier.height(4.dp))

            DrawerSection(
                title = "Proyectos",
                icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                emptyText = "No hay proyectos creados",
                count = state.projects.size
            ) {
                state.projects.forEach { project ->
                    DrawerLeaf(
                        title = project.name,
                        subtitle = formatDate(project.updatedAt),
                        onClick = { onProjectSelected(project) }
                    )
                }
            }

            DrawerSection(
                title = "Chats recientes",
                icon = { Icon(Icons.Default.History, contentDescription = null) },
                emptyText = "Todavia no hay chats",
                count = state.recentChats.size
            ) {
                state.recentChats.forEach { chat ->
                    DrawerLeaf(
                        title = chat.title,
                        subtitle = if (chat.projectId.isBlank()) "Chat general" else "Proyecto",
                        onClick = { onChatSelected(chat) }
                    )
                }
            }

            DrawerSection(
                title = "Archivos creados",
                icon = { Icon(Icons.Default.InsertDriveFile, contentDescription = null) },
                emptyText = "FENIX IA aun no creo archivos",
                count = state.createdFiles.size
            ) {
                state.createdFiles.forEach { file ->
                    DrawerLeaf(
                        title = file.name,
                        subtitle = "${file.sizeBytes / 1024} KB",
                        onClick = { onCreatedFileSelected(file) }
                    )
                }
            }

            DrawerSection(
                title = "Documentos subidos",
                icon = { Icon(Icons.Default.Description, contentDescription = null) },
                emptyText = "No hay documentos subidos",
                count = state.uploadedDocuments.size
            ) {
                state.uploadedDocuments.forEach { doc ->
                    DrawerLeaf(
                        title = doc.name,
                        subtitle = when (doc.status) {
                            "processing" -> "Procesando..."
                            "indexed" -> "Indexado"
                            "no_text" -> "Sin texto"
                            "error" -> doc.errorMessage.ifBlank { "Error" }
                            else -> "Pendiente"
                        },
                        onClick = { onUploadedDocumentSelected(doc) }
                    )
                }
            }

            Divider(Modifier.padding(vertical = 8.dp))
            NavigationDrawerItem(
                label = { Text("Sistema Fenix") },
                selected = false,
                icon = { Icon(Icons.Default.LocalFireDepartment, contentDescription = null) },
                onClick = onSistemaFenixSelected,
                modifier = Modifier.semantics { contentDescription = "Sistema Fenix" }
            )
            NavigationDrawerItem(
                label = { Text("Configuracion") },
                selected = false,
                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                onClick = onSettingsSelected,
                modifier = Modifier.semantics { contentDescription = "Configuracion" }
            )
        }
    }
}

@Composable
private fun DrawerSection(
    title: String,
    count: Int,
    emptyText: String,
    icon: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(12.dp))
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Text("$count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
    }
    AnimatedVisibility(expanded) {
        Column(Modifier.padding(start = 20.dp, bottom = 8.dp)) {
            if (count == 0) {
                Text(
                    text = emptyText,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                content()
            }
        }
    }
}

@Composable
private fun DrawerLeaf(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Text(
            subtitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatDate(millis: Long): String =
    SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(millis))
