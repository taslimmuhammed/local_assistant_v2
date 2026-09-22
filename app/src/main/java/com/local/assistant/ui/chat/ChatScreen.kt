package com.local.assistant.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.local.assistant.data.repo.ChatRepository
import com.local.assistant.llm.LlmService
import com.local.assistant.ui.theme.AppColors
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenModelSettings: () -> Unit,
) {
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val activeChatId by viewModel.activeChatId.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val streamingText by viewModel.streamingText.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val contextUsage by viewModel.contextUsage.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    // Keep the newest content in view while tokens stream in.
    LaunchedEffect(messages.size, streamingText) {
        val lastIndex = messages.size + if (streamingText != null) 1 else 0
        if (lastIndex > 0) listState.animateScrollToItem(lastIndex)
    }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = AppColors.Background,
                drawerContentColor = AppColors.TextPrimary,
            ) {
                ChatDrawer(
                    chats = chats,
                    activeChatId = activeChatId,
                    onNewChat = {
                        viewModel.startNewChat()
                        scope.launch { drawerState.close() }
                    },
                    onSelectChat = {
                        viewModel.selectChat(it)
                        scope.launch { drawerState.close() }
                    },
                    onDeleteChat = viewModel::deleteChat,
                    onOpenModelSettings = {
                        scope.launch { drawerState.close() }
                        onOpenModelSettings()
                    },
                )
            }
        },
    ) {
        Scaffold(
            containerColor = AppColors.Background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                ChatTopBar(
                    title = chats.firstOrNull { it.id == activeChatId }?.title
                        ?: ChatRepository.DEFAULT_TITLE,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onNewChat = viewModel::startNewChat,
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding(),
            ) {
                EngineBanner(engineState)

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (messages.isEmpty() && streamingText == null) {
                        EmptyState(Modifier.align(Alignment.Center))
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 12.dp),
                        ) {
                            items(messages, key = { it.id }) { MessageRow(it) }
                            streamingText?.let { partial ->
                                item(key = STREAMING_ITEM_KEY) {
                                    if (partial.isEmpty()) {
                                        ThinkingIndicator()
                                    } else {
                                        AssistantMessage(partial)
                                    }
                                }
                            }
                        }
                    }
                }

                ContextMeter(contextUsage)

                Composer(
                    enabled = engineState is LlmService.State.Ready ||
                        engineState is LlmService.State.Loading,
                    isGenerating = isGenerating,
                    onSend = viewModel::send,
                    onStop = viewModel::stop,
                )
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    title: String,
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
) {
    Column {
        TopAppBar(
            title = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Outlined.Menu, contentDescription = "Chats")
                }
            },
            actions = {
                IconButton(onClick = onNewChat) {
                    Icon(Icons.Outlined.Add, contentDescription = "New chat")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = AppColors.Background,
                titleContentColor = AppColors.TextPrimary,
                navigationIconContentColor = AppColors.TextPrimary,
                actionIconContentColor = AppColors.TextPrimary,
            ),
        )
        HorizontalDivider(color = AppColors.Border)
    }
}

@Composable
private fun EngineBanner(state: LlmService.State) {
    val message = when (state) {
        is LlmService.State.Loading -> "Loading model…"
        is LlmService.State.Failed -> state.message
        LlmService.State.NoModel -> "No model installed."
        else -> null
    } ?: return

    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = if (state is LlmService.State.Failed) AppColors.Danger else AppColors.TextSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.SurfaceMuted)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * Shows how much of the context window this chat has used. Quiet until the window is nearly
 * gone, at which point it says what to do about it.
 */
@Composable
private fun ContextMeter(usage: LlmService.ContextUsage?) {
    if (usage == null) return
    Text(
        text = if (usage.isNearlyFull) {
            "Context almost full (${usage.used} / ${usage.max} tokens) — start a new chat"
        } else {
            "${usage.used} / ${usage.max} tokens"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (usage.isNearlyFull) AppColors.Danger else AppColors.TextSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Text(
        text = "Ask anything",
        style = MaterialTheme.typography.titleMedium,
        color = AppColors.TextSecondary,
        modifier = modifier,
    )
}

@Composable
private fun Composer(
    enabled: Boolean,
    isGenerating: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val canSend = enabled && !isGenerating && text.isNotBlank()

    Column(Modifier.navigationBarsPadding()) {
        HorizontalDivider(color = AppColors.Border)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(AppColors.SurfaceMuted)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = "Message",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppColors.TextSecondary,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    enabled = enabled,
                    textStyle = MaterialTheme.typography.bodyLarge.merge(
                        TextStyle(color = AppColors.TextPrimary),
                    ),
                    cursorBrush = SolidColor(AppColors.TextPrimary),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = ImeAction.Default,
                    ),
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val buttonEnabled = isGenerating || canSend
            IconButton(
                onClick = {
                    if (isGenerating) {
                        onStop()
                    } else {
                        onSend(text)
                        text = ""
                    }
                },
                enabled = buttonEnabled,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (buttonEnabled) AppColors.Accent else AppColors.Border),
            ) {
                Icon(
                    imageVector = if (isGenerating) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.Send,
                    contentDescription = if (isGenerating) "Stop" else "Send",
                    tint = AppColors.OnAccent,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private const val STREAMING_ITEM_KEY = "streaming"
