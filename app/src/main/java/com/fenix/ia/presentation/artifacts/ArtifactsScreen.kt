package com.fenix.ia.presentation.artifacts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtifactsScreen(
    projectId: String,
    onBack: () -> Unit,
    vm: ArtifactsViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsState()

    LaunchedEffect(projectId) { vm.load(projectId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Artefactos (${state.artifacts.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                },
                actions = {
                    if (state.selected.isNotEmpty()) {
                        IconButton(onClick = vm::exportSelected) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = "Exportar ${state.selected.size} archivos"
                            )
                        }
                    }
                }
            )
        }
    ) { pad ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        if (state.artifacts.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text(
                    "No hay artefactos generados aún",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Filtros por extensión
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(state.filters) { f ->
                            FilterChip(
                                selected = state.activeFilter == f,
                                onClick = { vm.setFilter(f) },
                                label = { Text(f) }
                            )
                        }
                    }
                }

                items(state.filtered, key = { it.path }) { art ->
                    ArtifactCard(
                        artifact = art,
                        isSelected = art in state.selected,
                        onSelect = { vm.toggleSelect(art) },
                        onExport = { vm.exportOne(art) },
                        onDelete = { vm.delete(art) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArtifactCard(
    artifact: ArtifactFile,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    val sdf = remember { SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault()) }
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        colors = if (isSelected)
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(artifact.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${artifact.sizeBytes / 1024} KB  ·  ${sdf.format(Date(artifact.createdAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
            IconButton(onClick = onExport) {
                Icon(Icons.Default.Download, contentDescription = "Exportar", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Eliminar", modifier = Modifier.size(18.dp))
            }
        }
    }
}
