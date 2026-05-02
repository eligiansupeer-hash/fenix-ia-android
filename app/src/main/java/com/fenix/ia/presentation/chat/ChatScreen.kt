package com.fenix.ia.presentation.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fenix.ia.domain.model.ApiProvider
import com.fenix.ia.domain.model.DocumentNode
import com.fenix.ia.domain.model.Message
import com.fenix.ia.domain.model.MessageRole
import kotlinx.coroutines.flow.collectLatest

val ApiProvider.displayName: String
    get() = when (this) {
        ApiProvider.GEMINI          -> "Gemini"
        ApiProvider.GROQ            -> "Groq"
        ApiProvider.MISTRAL         -> "Mistral"
        ApiProvider.OPENROUTER      -> "OpenRouter"
        ApiProvider.GITHUB_MODELS   -> "GitHub AI"
        ApiProvider.LOCAL_ON_DEVICE -> "IA Local"
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatId: String,
    projectId: String,
    onBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDocPanel by remember { mutableStateOf(false) }
    var showToolSheet by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        uris.forEach { uri ->
            viewModel.processIntent(ChatIntent.AddAttachmentUri(uri.toString()))
        }
    }

    LaunchedEffect(chatId) { viewModel.loadChat(chatId, projectId) }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty())
            listState.animateScrollToItem(uiState.messages.size - 1)
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { errorMsg ->
            snackbarHostState.showSnackbar(errorMsg, actionLabel = "OK", duration = SnackbarDuration.Long)
            viewModel.processIntent(ChatIntent.DismissError)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is ChatEffect.ShowError      -> snackbarHostState.showSnackbar(effect.message)
                is ChatEffect.OpenFilePicker ->
                    filePickerLauncher.launch(effect.allowedMimeTypes.toTypedArray().ifEmpty { arrayOf("*/*") })
            }
        }
    }

    if (showToolSheet) {
        ChatToolSelectorSheet(
            allTools       = uiState.allTools,
            enabledToolIds = uiState.enabledToolIds,
            onToggle       = { viewModel.processIntent(ChatIntent.ToggleTool(it)) },
            onDismiss      = { showToolSheet = false }
        )
    }

    Scaffold(
        modifier     = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Chat", style = MaterialTheme.typography.titleMedium)
                        uiState.activeProvider?.let {
                            Text("⚡ ${it.displayName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                    }
                },
                actions = {
                    BadgedBox(badge = {
                        if (uiState.enabledToolIds.isNotEmpty())
                            Badge { Text("${uiState.enabledToolIds.size}") }
                    }) {
                        IconButton(onClick = { showToolSheet = true }) {
                            Icon(Icons.Default.Build, contentDescription = "Herramientas")
                        }
                    }
                    if (projectId.isNotBlank()) {
                        BadgedBox(badge = {
                            val n = uiState.documents.count { it.isChecked }
                            if (n > 0) Badge { Text("$n") }
                        }) {
                            IconButton(onClick = { showDocPanel = !showDocPanel }) {
                                Icon(Icons.Default.AttachFile, contentDescription = "Documentos")
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            Column {
                ProviderSelector(
                    providers = uiState.availableProviders,
                    selected  = uiState.selectedProvider,
                    onSelect  = { viewModel.processIntent(ChatIntent.SelectProvider(it)) }
                )
                if (uiState.pendingAttachmentUris.isNotEmpty()) {
                    PendingAttachmentsBar(
                        uris    = uiState.pendingAttachmentUris,
                        onClear = { viewModel.processIntent(ChatIntent.ClearPendingAttachments) }
                    )
                }
                ChatInputBar(
                    isStreaming = uiState.isStreaming,
                    isSending  = uiState.isSending,
                    onSend     = { viewModel.processIntent(ChatIntent.SendMessage(it)) },
                    onStop     = { viewModel.processIntent(ChatIntent.StopStreaming) },
                    onAttach   = { filePickerLauncher.launch(arrayOf("application/pdf","image/*","text/plain")) }
                )
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            if (showDocPanel && projectId.isNotBlank()) {
                DocumentContextPanel(
                    documents = uiState.documents,
                    onToggle  = { viewModel.processIntent(ChatIntent.ToggleDocumentCheckpoint(it)) }
                )
                Divider()
            }

            LazyColumn(
                state               = listState,
                modifier            = Modifier.weight(1f),
                contentPadding      = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (uiState.messages.isEmpty() && !uiState.isStreaming) {
                    item {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "Enviá un mensaje para comenzar",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }

                items(uiState.messages, key = { it.id }) { message ->
                    val isLastAssistant = message.role == MessageRole.ASSISTANT &&
                        message.id == uiState.messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.id
                    val showRetry = message.role == MessageRole.USER && !uiState.isStreaming

                    MessageBubble(
                        message      = message,
                        showActions  = isLastAssistant && !uiState.isStreaming,
                        showRetry    = showRetry,
                        onRegenerate = { viewModel.processIntent(ChatIntent.RegenerateLastMessage) },
                        onRetry      = { viewModel.processIntent(ChatIntent.RetryFromMessage(message.id)) },
                        modifier     = Modifier.testTag(
                            if (message.role == MessageRole.ASSISTANT) "assistant_message" else "user_message"
                        )
                    )
                }

                if (uiState.isStreaming) {
                    item {
                        StreamingIndicator(
                            buffer   = uiState.streamingBuffer,
                            provider = uiState.activeProvider,
                            modifier = Modifier.testTag("streaming_indicator")
                        )
                    }
                }
            }
        }
    }
}

// ── Barra de adjuntos pendientes ──────────────────────────────────────────────
@Composable
private fun PendingAttachmentsBar(uris: List<String>, onClear: () -> Unit) {
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.AttachFile, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text("${uris.size} archivo(s) adjunto(s)", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onClear, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("Limpiar", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ── Selector de proveedor ─────────────────────────────────────────────────────
@Composable
private fun ProviderSelector(providers: List<ApiProvider>, selected: ApiProvider?, onSelect: (ApiProvider?) -> Unit) {
    if (providers.isEmpty()) return
    LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            FilterChip(selected == null, { onSelect(null) }, label = { Text("Auto", style = MaterialTheme.typography.labelSmall) })
        }
        items(providers) { p ->
            FilterChip(
                selected    = selected == p,
                onClick     = { onSelect(p) },
                label       = { Text(p.displayName, style = MaterialTheme.typography.labelSmall) },
                leadingIcon = if (p == ApiProvider.LOCAL_ON_DEVICE) {{ Icon(Icons.Default.PhoneAndroid, null, Modifier.size(14.dp)) }} else null
            )
        }
    }
}

// ── Panel de documentos ───────────────────────────────────────────────────────
@Composable
private fun DocumentContextPanel(documents: List<DocumentNode>, onToggle: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text("Contexto activo", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        if (documents.isEmpty()) {
            Text("No hay documentos en este proyecto", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        } else {
            documents.forEach { doc ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(doc.isChecked, { onToggle(doc.id) }, Modifier.size(36.dp))
                    Text(doc.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ── Burbuja de mensaje ────────────────────────────────────────────────────────
@Composable
private fun MessageBubble(
    message: Message,
    showActions: Boolean = false,
    showRetry: Boolean = false,
    onRegenerate: () -> Unit = {},
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isUser = message.role == MessageRole.USER
    val clipboardManager = LocalClipboardManager.current

    Column(modifier = modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
            Card(
                modifier = Modifier.widthIn(max = 300.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isUser) MaterialTheme.colorScheme.primary
                                     else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        text  = message.content,
                        color = if (isUser) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (message.attachmentUris.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "📎 ${message.attachmentUris.size} adjunto(s)",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        if (showRetry && isUser) {
            Row(Modifier.fillMaxWidth().padding(end = 4.dp, top = 2.dp), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick        = onRetry,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier       = Modifier.height(24.dp)
                ) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Reintentar", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                }
            }
        }

        if (showActions && !isUser) {
            Row(Modifier.padding(start = 4.dp, top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton({ clipboardManager.setText(AnnotatedString(message.content)) }, Modifier.size(28.dp)) {
                    Icon(Icons.Default.ContentCopy, "Copiar", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
                IconButton(onRegenerate, Modifier.size(28.dp)) {
                    Icon(Icons.Default.Refresh, "Regenerar", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                }
            }
        }
    }
}

// ── Indicador de streaming ────────────────────────────────────────────────────
@Composable
private fun StreamingIndicator(buffer: String, provider: ApiProvider?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Card(Modifier.widthIn(max = 300.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(12.dp)) {
                provider?.let {
                    Text("⚡ ${it.displayName}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                if (buffer.isNotEmpty()) Text(buffer, style = MaterialTheme.typography.bodyMedium)
                else DotsLoadingIndicator()
            }
        }
    }
}

@Composable
private fun DotsLoadingIndicator() {
    val transition = rememberInfiniteTransition(label = "dots")
    val alpha by transition.animateFloat(0.3f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "dots_alpha")
    Text("● ● ●", color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha), style = MaterialTheme.typography.bodyMedium)
}

// ── Input bar ─────────────────────────────────────────────────────────────────
@Composable
private fun ChatInputBar(
    isStreaming: Boolean,
    isSending: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    val busy = isStreaming || isSending

    Surface(tonalElevation = 3.dp) {
        Row(
            // ↓ bottom = 24.dp sube la caja el equivalente a un emoji por encima de los botones virtuales
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onAttach, enabled = !busy, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.AttachFile, "Adjuntar", Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (busy) 0.3f else 0.7f))
            }
            OutlinedTextField(
                value         = input,
                onValueChange = { input = it },
                placeholder   = { Text("Escribe tu mensaje...") },
                modifier      = Modifier.weight(1f).testTag("chat_input"),
                maxLines      = 4,
                enabled       = !busy
            )
            Spacer(Modifier.width(4.dp))
            if (isStreaming) {
                IconButton(onClick = onStop) { Icon(Icons.Default.Stop, "Detener") }
            } else {
                IconButton(
                    onClick  = { if (input.isNotBlank()) { onSend(input.trim()); input = "" } },
                    modifier = Modifier.testTag("send_button"),
                    enabled  = input.isNotBlank() && !isSending
                ) {
                    Icon(Icons.Default.Send, "Enviar")
                }
            }
        }
    }
}
