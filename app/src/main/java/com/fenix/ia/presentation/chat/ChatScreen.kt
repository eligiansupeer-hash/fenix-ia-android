package com.fenix.ia.presentation.chat

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fenix.ia.domain.model.ApiProvider
import com.fenix.ia.domain.model.Message
import com.fenix.ia.domain.model.MessageRole
import kotlinx.coroutines.flow.collectLatest

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

    LaunchedEffect(chatId) {
        viewModel.loadChat(chatId, projectId)
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is ChatEffect.ScrollToBottom -> {
                    if (uiState.messages.isNotEmpty()) {
                        listState.animateScrollToItem(uiState.messages.size - 1)
                    }
                }
                is ChatEffect.ShowError -> { /* Mostrar snackbar */ }
                is ChatEffect.OpenFilePicker -> { /* Abrir selector */ }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Chat")
                        uiState.activeProvider?.let { provider ->
                            Text(
                                text = "⚡ ${provider.name}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            ChatInputBar(
                isStreaming = uiState.isStreaming,
                onSend = { content -> viewModel.processIntent(ChatIntent.SendMessage(content)) },
                onStop = { viewModel.processIntent(ChatIntent.StopStreaming) }
            )
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(uiState.messages, key = { it.id }) { message ->
                MessageBubble(
                    message = message,
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

@Composable
private fun MessageBubble(message: Message, modifier: Modifier = Modifier) {
    val isUser = message.role == MessageRole.USER
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier.widthIn(max = 300.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.surface
            )
        ) {
            Text(
                text = message.content,
                modifier = Modifier.padding(12.dp),
                color = if (isUser) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun StreamingIndicator(
    buffer: String,
    provider: ApiProvider?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(8.dp)) {
        provider?.let {
            Text(
                text = "⚡ Generando con $it...",
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

@Composable
private fun DotsLoadingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
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
