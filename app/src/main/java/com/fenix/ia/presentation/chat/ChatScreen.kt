package com.fenix.ia.presentation.chat

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
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
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fenix.ia.domain.model.ApiProvider
import com.fenix.ia.domain.model.DocumentNode
import com.fenix.ia.domain.model.Message
import com.fenix.ia.domain.model.MessageRole
import kotlinx.coroutines.flow.collectLatest

// ── Extensión de display name para el enum ApiProvider ───────────────────────
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

    LaunchedEffect(chatId) {
        viewModel.loadChat(chatId, projectId)
    }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { errorMsg ->
            snackbarHostState.showSnackbar(
                message = errorMsg,
                actionLabel = "OK",
                duration = SnackbarDuration.Long
            )
            viewModel.processIntent(ChatIntent.DismissError)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is ChatEffect.ScrollToBottom -> {
                    if (uiState.messages.isNotEmpty()) {
                        listState.animateScrollToItem(uiState.messages.size - 1)
                    }
                }
                is ChatEffect.ShowError -> snackbarHostState.showSnackbar(effect.message)
                is ChatEffect.OpenFilePicker -> { /* handled in ProjectDetail */ }
            }
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Chat", style = MaterialTheme.typography.titleMedium)
                        uiState.activeProvider?.let { provider ->
                            Text(
                                text = "⚡ ${provider.displayName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                    }
                },
                actions = {
                    BadgedBox(
                        badge = {
                            val checkedCount = uiState.documents.count { it.isChecked }
                            if (checkedCount > 0) Badge { Text("$checkedCount") }
                        }
                    ) {
                        IconButton(onClick = { showDocPanel = !showDocPanel }) {
                            Icon(Icons.Default.AttachFile, contentDescription = "Documentos de contexto")
                        }
                    }
                }
            )
        },
        bottomBar = {
            Column {
                // Selector de proveedor — encima del input bar
                ProviderSelector(
                    providers  = uiState.availableProviders,
                    selected   = uiState.selectedProvider,
                    onSelect   = { viewModel.processIntent(ChatIntent.SelectProvider(it)) }
                )
                ChatInputBar(
                    isStreaming = uiState.isStreaming,
                    onSend     = { content -> viewModel.processIntent(ChatIntent.SendMessage(content)) },
                    onStop     = { viewModel.processIntent(ChatIntent.StopStreaming) }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (showDocPanel) {
                DocumentContextPanel(
                    documents = uiState.documents,
                    onToggle = { docId ->
                        viewModel.processIntent(ChatIntent.ToggleDocumentCheckpoint(docId))
                    }
                )
                Divider()
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (uiState.messages.isEmpty() && !uiState.isStreaming) {
                    item {
                        Box(
                            modifier = Modifier.fillParentMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
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

                    MessageBubble(
                        message = message,
                        showActions = isLastAssistant && !uiState.isStreaming,
                        onRegenerate = { viewModel.processIntent(ChatIntent.RegenerateLastMessage) },
                        modifier = Modifier.testTag(
                            if (message.role == MessageRole.ASSISTANT) "assistant_message" else "user_message"
                        )
                    )
                }

                if (uiState.isStreaming) {
                    item {
                        StreamingIndicator(
                            buffer = uiState.streamingBuffer,
                            provider = uiState.activeProvider,
                            modifier = Modifier.testTag("streaming_indicator")
                        )
                    }
                }
            }
        }
    }
}

// ── Selector de proveedor ─────────────────────────────────────────────────────

@Composable
private fun ProviderSelector(
    providers: List<ApiProvider>,
    selected: ApiProvider?,
    onSelect: (ApiProvider?) -> Unit
) {
    if (providers.isEmpty()) return

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Chip "Auto" — delega la elección al router
        item {
            FilterChip(
                selected = selected == null,
                onClick  = { onSelect(null) },
                label    = { Text("Auto", style = MaterialTheme.typography.labelSmall) }
            )
        }
        items(providers) { provider ->
            FilterChip(
                selected    = selected == provider,
                onClick     = { onSelect(provider) },
                label       = {
                    Text(provider.displayName, style = MaterialTheme.typography.labelSmall)
                },
                leadingIcon = if (provider == ApiProvider.LOCAL_ON_DEVICE) {
                    { Icon(Icons.Default.PhoneAndroid, null, modifier = Modifier.size(14.dp)) }
                } else null
            )
        }
    }
}

// ── Resto de composables ──────────────────────────────────────────────────────

@Composable
private fun DocumentContextPanel(
    documents: List<DocumentNode>,
    onToggle: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            "Contexto activo",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))
        if (documents.isEmpty()) {
            Text(
                "No hay documentos en este proyecto",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        } else {
            documents.forEach { doc ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = doc.isChecked,
                        onCheckedChange = { onToggle(doc.id) },
                        modifier = Modifier.size(36.dp)
                    )
                    Text(
                        text = doc.name,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: Message,
    showActions: Boolean = false,
    onRegenerate: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isUser = message.role == MessageRole.USER
    val clipboardManager = LocalClipboardManager.current

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
        ) {
            Card(
                modifier = Modifier.widthIn(max = 300.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isUser)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = message.content,
                    modifier = Modifier.padding(12.dp),
                    color = if (isUser) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (showActions) {
            Row(
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(
                    onClick = { clipboardManager.setText(AnnotatedString(message.content)) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copiar mensaje",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                IconButton(
                    onClick = onRegenerate,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Regenerar respuesta",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamingIndicator(
    buffer: String,
    provider: ApiProvider?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Card(
            modifier = Modifier.widthIn(max = 300.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                provider?.let {
                    Text(
                        text = "⚡ ${it.displayName}",   // displayName en lugar de .name
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (buffer.isNotEmpty()) {
                    Text(text = buffer, style = MaterialTheme.typography.bodyMedium)
                } else {
                    DotsLoadingIndicator()
                }
            }
        }
    }
}

@Composable
private fun DotsLoadingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600), repeatMode = RepeatMode.Reverse
        ), label = "dots_alpha"
    )
    Text(
        text = "● ● ●",
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
        style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun ChatInputBar(
    isStreaming: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Escribe tu mensaje...") },
                modifier = Modifier
                    .weight(1f)
                    .testTag("chat_input"),
                maxLines = 4
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (isStreaming) {
                IconButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, contentDescription = "Detener")
                }
            } else {
                IconButton(
                    onClick = {
                        if (input.isNotBlank()) {
                            onSend(input.trim())
                            input = ""
                        }
                    },
                    modifier = Modifier.testTag("send_button"),
                    enabled = input.isNotBlank()
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Enviar")
                }
            }
        }
    }
}
