package com.fenix.ia.presentation.settings

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fenix.ia.domain.model.ApiProvider
import com.fenix.ia.updater.UpdateIntent
import com.fenix.ia.updater.UpdateViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    updateViewModel: UpdateViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val updateState by updateViewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(updateState.error) {
        updateState.error?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Long)
            updateViewModel.processIntent(UpdateIntent.DismissError)
        }
    }

    val update = updateState.updateAvailable
    if (update != null) {
        UpdateAvailableDialog(
            currentVersion = update.currentVersion,
            newVersion = update.newVersion,
            releaseNotes = update.releaseNotes,
            apkSizeMb = update.apkSizeBytes / 1_048_576f,
            onConfirm = { updateViewModel.processIntent(UpdateIntent.DownloadAndInstall) },
            onDismiss = { updateViewModel.processIntent(UpdateIntent.DismissUpdateDialog) }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configuración") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            UpdateSection(
                isChecking = updateState.isChecking,
                isDownloading = updateState.isDownloading,
                downloadProgress = updateState.downloadProgress,
                isUpToDate = updateState.isUpToDate,
                noReleases = updateState.noReleases,
                installReady = updateState.installReady,
                onCheckForUpdate = { updateViewModel.processIntent(UpdateIntent.CheckForUpdate) }
            )

            HorizontalDivider()

            // ── Panel IA Local (Fase 5) ──────────────────────────────────────
            LocalAiSection(
                isCapable = uiState.isLocalCapable,
                isDownloaded = uiState.isModelDownloaded,
                isReady = uiState.isModelReady,
                isDownloading = uiState.isDownloading,
                progress = uiState.downloadProgress,
                localError = uiState.localError,
                onDownload = viewModel::downloadModel,
                onActivate = viewModel::activateModel,
                onRelease = viewModel::releaseModel
            )

            HorizontalDivider()

            Text("API Keys", style = MaterialTheme.typography.titleLarge)
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

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ── Sección IA Local ─────────────────────────────────────────────────────────

@Composable
private fun LocalAiSection(
    isCapable: Boolean,
    isDownloaded: Boolean,
    isReady: Boolean,
    isDownloading: Boolean,
    progress: Float,
    localError: String?,
    onDownload: () -> Unit,
    onActivate: () -> Unit,
    onRelease: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    tint = if (isCapable) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("IA Local (On-Device)", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (!isCapable) {
                Text(
                    "RAM insuficiente. Requiere >= 4 GB RAM.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                return@Column
            }

            Text(
                "Llama 3.2 1B Q4  ·  ~700 MB  ·  Offline total  ·  Privacidad completa",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))

            when {
                isReady -> {
                    Text(
                        "✓ Modelo activo y listo",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = onRelease,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Liberar modelo (liberar RAM)")
                    }
                }
                isDownloaded && !isDownloading -> {
                    Text(
                        "Modelo descargado. Listo para activar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Button(
                        onClick = onActivate,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Activar modelo local")
                    }
                }
                isDownloading -> {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Descargando: ${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                else -> {
                    OutlinedButton(
                        onClick = onDownload,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Descargar modelo (~700 MB)")
                    }
                }
            }

            if (localError != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    localError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

// ── Sección OTA ──────────────────────────────────────────────────────────────

@Composable
private fun UpdateSection(
    isChecking: Boolean,
    isDownloading: Boolean,
    downloadProgress: Int,
    isUpToDate: Boolean,
    noReleases: Boolean,
    installReady: Boolean,
    onCheckForUpdate: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (installReady) Icons.Default.CheckCircle
                                      else Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = if (installReady) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("Actualización de la app", style = MaterialTheme.typography.titleSmall)
                }
                if (!isChecking && !isDownloading) {
                    IconButton(onClick = onCheckForUpdate) {
                        Icon(Icons.Default.Refresh, contentDescription = "Verificar actualización")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            AnimatedContent(
                targetState = when {
                    installReady  -> UpdateUiPhase.INSTALL_READY
                    isDownloading -> UpdateUiPhase.DOWNLOADING
                    isChecking    -> UpdateUiPhase.CHECKING
                    isUpToDate    -> UpdateUiPhase.UP_TO_DATE
                    noReleases    -> UpdateUiPhase.NO_RELEASES
                    else          -> UpdateUiPhase.IDLE
                },
                label = "update_phase"
            ) { phase ->
                when (phase) {
                    UpdateUiPhase.IDLE -> Text(
                        "Tocá el ícono para verificar si hay una versión nueva.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    UpdateUiPhase.CHECKING -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Verificando versión...", style = MaterialTheme.typography.bodySmall)
                    }
                    UpdateUiPhase.DOWNLOADING -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Descargando... $downloadProgress%",
                            style = MaterialTheme.typography.bodySmall
                        )
                        LinearProgressIndicator(
                            progress = { downloadProgress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    UpdateUiPhase.UP_TO_DATE -> Text(
                        "✓ Ya tenés la versión más reciente.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    UpdateUiPhase.NO_RELEASES -> Text(
                        "Todavía no hay releases publicados en el repositorio.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    UpdateUiPhase.INSTALL_READY -> Text(
                        "✓ Descarga completa. El instalador del sistema se abrió automáticamente.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

private enum class UpdateUiPhase { IDLE, CHECKING, DOWNLOADING, UP_TO_DATE, NO_RELEASES, INSTALL_READY }

@Composable
private fun UpdateAvailableDialog(
    currentVersion: Int,
    newVersion: Int,
    releaseNotes: String,
    apkSizeMb: Float,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
        title = { Text("Nueva versión disponible") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("v$currentVersion  →  v$newVersion   (${String.format("%.1f", apkSizeMb)} MB)")
                if (releaseNotes.isNotBlank()) {
                    HorizontalDivider()
                    Text(
                        releaseNotes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("Descargar e instalar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Ahora no") }
        }
    )
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
                        onClick = {
                            if (keyInput.isNotBlank()) {
                                onSave(keyInput)
                                keyInput = ""
                                expanded = false
                            }
                        },
                        enabled = keyInput.isNotBlank()
                    ) {
                        Text("Guardar")
                    }
                }
            }
        }
    }
}
