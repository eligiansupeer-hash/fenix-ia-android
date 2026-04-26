package com.fenix.ia.presentation.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fenix.ia.domain.model.ApiProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configuración — API Keys") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Insertar API Keys", style = MaterialTheme.typography.titleLarge)
            Text(
                "Las claves se cifran con AES-256-GCM en el hardware TEE del dispositivo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            ApiProvider.values().forEach { provider ->
                ApiKeyField(
                    provider = provider,
                    isConfigured = provider in uiState.configuredProviders,
                    onSave = { key -> viewModel.saveKey(provider, key) },
                    onDelete = { viewModel.deleteKey(provider) }
                )
            }
        }
    }
}

@Composable
private fun ApiKeyField(
    provider: ApiProvider,
    isConfigured: Boolean,
    onSave: (String) -> Unit,
    onDelete: () -> Unit
) {
    var keyInput by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (isConfigured) "${provider.name} ✓" else provider.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isConfigured) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Cerrar" else if (isConfigured) "Actualizar" else "Agregar")
                }
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text("API Key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("sk-... / AIza...") }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (isConfigured) {
                        TextButton(onClick = { onDelete(); expanded = false }) {
                            Text("Eliminar", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(
                        onClick = { if (keyInput.isNotBlank()) { onSave(keyInput); keyInput = ""; expanded = false } },
                        enabled = keyInput.isNotBlank()
                    ) {
                        Text("Guardar")
                    }
                }
            }
        }
    }
}
