package com.local.assistant.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.clickable
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Mic
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
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.db.Role
import com.local.assistant.data.repo.ChatRepository
import com.local.assistant.device.PermissionBroker
import com.local.assistant.llm.LlmService
import com.local.assistant.media.RecordingState
import com.local.assistant.ui.theme.AppColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenMemory: () -> Unit,
    onOpenPhotos: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val activeChatId by viewModel.activeChatId.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val streamingText by viewModel.streamingText.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val contextUsage by viewModel.contextUsage.collectAsStateWithLifecycle()
    val supportsImages by viewModel.supportsImages.collectAsStateWithLifecycle()
    val supportsAudio by viewModel.supportsAudio.collectAsStateWithLifecycle()
    val pendingAttachment by viewModel.pendingAttachment.collectAsStateWithLifecycle()
    val recording by viewModel.recordingState.collectAsStateWithLifecycle()
    val speaking by viewModel.speaking.collectAsStateWithLifecycle()
    val droppedFromContext by viewModel.droppedFromContext.collectAsStateWithLifecycle()
    val chips by viewModel.chips.collectAsStateWithLifecycle()
    val askForNotifications by viewModel.askForNotifications.collectAsStateWithLifecycle()
    val offerExactAlarms by viewModel.offerExactAlarms.collectAsStateWithLifecycle()
    val busyElsewhere by viewModel.busyElsewhere.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    // A web search chip's top source, in the browser; only web addresses, whatever the chip says.
    val openLink: (String) -> Unit = { url ->
        if (url.startsWith("https://") || url.startsWith("http://")) runCatching { uriHandler.openUri(url) }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(viewModel::attachImage) }

    // Recording only starts once the permission is actually granted, never optimistically.
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.startRecording() }

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

    // Asked the first time a reminder is made, not at install: that is when it makes sense.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.notificationsAsked() }
    LaunchedEffect(askForNotifications) {
        if (!askForNotifications) return@LaunchedEffect
        val needed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needed) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else viewModel.notificationsAsked()
    }

    // A tool asking for a permission it needs now, like contacts for "call amma": answer it with
    // the system dialog, and the tool carries on with the result.
    var permissionRequest by remember { mutableStateOf<PermissionBroker.Request?>(null) }
    val toolPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionRequest?.answer?.complete(granted)
        permissionRequest = null
    }
    // Only while on screen: the assistant overlay answers these when it is the one in front.
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.permissionRequests.collect { request ->
                permissionRequest = request
                toolPermission.launch(request.permission)
            }
        }
    }

    LaunchedEffect(offerExactAlarms) {
        if (!offerExactAlarms || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@LaunchedEffect
        viewModel.exactAlarmsOffered()
        val result = snackbarHostState.showSnackbar(
            message = "Reminders may arrive a few minutes late.",
            actionLabel = "Allow exact",
        )
        if (result == SnackbarResult.ActionPerformed) {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
            )
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            // 85% of the screen, so the chat stays in sight behind it: the sheet's own cap
            // (360dp) is the whole width of a ~360dp phone.
            val drawerWidth = (LocalConfiguration.current.screenWidthDp * DRAWER_FRACTION).dp
            ModalDrawerSheet(
                modifier = Modifier.width(drawerWidth),
                drawerContainerColor = AppColors.Background,
                drawerContentColor = AppColors.TextPrimary,
            ) {
                ChatDrawer(
                    chats = chats,
                    activeChatId = activeChatId,
                    onClose = { scope.launch { drawerState.close() } },
                    onNewChat = {
                        viewModel.startNewChat()
                        scope.launch { drawerState.close() }
                    },
                    onSelectChat = {
                        viewModel.selectChat(it)
                        scope.launch { drawerState.close() }
                    },
                    onDeleteChat = viewModel::deleteChat,
                    onOpenMemory = {
                        scope.launch { drawerState.close() }
                        onOpenMemory()
                    },
                    onOpenPhotos = {
                        scope.launch { drawerState.close() }
                        onOpenPhotos()
                    },
                    onOpenSettings = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
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
                EngineBanner(engineState, onRetry = viewModel::retryLoad)
                val tidying by viewModel.tidying.collectAsStateWithLifecycle()
                val memoryPaused by viewModel.memoryPaused.collectAsStateWithLifecycle()
                if (tidying) QuietBanner("Tidying up…")
                if (memoryPaused) QuietBanner("Memory is paused: nothing new is remembered")
                if (busyElsewhere) QuietBanner("Finishing an answer from the assistant…")

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (messages.isEmpty() && streamingText == null) {
                        EmptyState(Modifier.align(Alignment.Center))
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 12.dp),
                        ) {
                            if (droppedFromContext > 0) {
                                item(key = TRIMMED_NOTICE_KEY) { TrimmedNotice(droppedFromContext) }
                            }
                            // While a reply streams, the chips its tool calls made belong under it,
                            // not under the question.
                            val asking = messages.lastOrNull()?.takeIf { streamingText != null && it.role == Role.USER }
                            items(messages, key = { it.id }) { message ->
                                MessageRow(message)
                                if (message != asking) {
                                    MemoryChips(
                                        chips = chips[message.id].orEmpty(),
                                        onUndo = viewModel::undo,
                                        onEditTime = viewModel::editTime,
                                        onOpenClock = viewModel::openClock,
                                        onOpenLink = openLink,
                                    )
                                }
                            }
                            streamingText?.let { partial ->
                                item(key = STREAMING_ITEM_KEY) {
                                    if (partial.isEmpty()) {
                                        // A message sent while the model is still starting waits
                                        // for it: say so, so the wait doesn't look like a hang.
                                        ThinkingIndicator(label = "Loading model…".takeIf { engineState !is LlmService.State.Ready })
                                    } else {
                                        AssistantMessage(partial, streaming = true)
                                    }
                                    asking?.let {
                                        MemoryChips(
                                            chips = chips[it.id].orEmpty(),
                                            onUndo = viewModel::undo,
                                            onEditTime = viewModel::editTime,
                                            onOpenClock = viewModel::openClock,
                                            onOpenLink = openLink,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                ContextMeter(contextUsage)

                if (speaking && recording == null) SpeakingBar(onStop = viewModel::stopSpeaking)

                Composer(
                    // While the model loads, a message can still be sent: it waits for the engine.
                    // Not while the overlay's reply is running, though: one turn at a time.
                    enabled = !busyElsewhere && (
                        engineState is LlmService.State.Ready ||
                            engineState is LlmService.State.Loading ||
                            engineState is LlmService.State.Idle
                        ),
                    isGenerating = isGenerating,
                    supportsImages = supportsImages,
                    supportsAudio = supportsAudio,
                    pendingAttachment = pendingAttachment,
                    recording = recording,
                    onSend = viewModel::send,
                    onTyping = viewModel::onTyping,
                    onStop = viewModel::stop,
                    onPickImage = {
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onDiscardAttachment = viewModel::discardPendingAttachment,
                    onStartRecording = {
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            viewModel.startRecording()
                        } else {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onStopRecording = viewModel::stopRecording,
                    onCancelRecording = viewModel::cancelRecording,
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
                    // Titled after the first message, which may have been a card number.
                    text = com.local.assistant.ui.privacy.rememberShown(title).text,
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
private fun QuietBanner(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = AppColors.TextSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.SurfaceMuted)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun EngineBanner(state: LlmService.State, onRetry: () -> Unit) {
    val failed = state is LlmService.State.Failed
    val message = when (state) {
        is LlmService.State.Loading, LlmService.State.Idle -> "Getting ready…"
        is LlmService.State.Failed -> "${state.message} Tap to try again."
        LlmService.State.NoModel -> "No model installed."
        else -> null
    } ?: return

    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = if (failed) AppColors.Danger else AppColors.TextSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (failed) Modifier.clickable(onClick = onRetry) else Modifier)
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

/**
 * Dropping old turns is silent by design, which is the one thing wrong with it — so say so
 * rather than letting the model appear to forget for no reason.
 */
@Composable
private fun TrimmedNotice(droppedCount: Int) {
    Text(
        text = "$droppedCount earlier message${if (droppedCount == 1) "" else "s"} " +
            "no longer fit the context window",
        style = MaterialTheme.typography.bodyMedium,
        color = AppColors.TextSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 10.dp),
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

/** What the round button at the end of the composer does right now. */
private enum class ComposerAction { STOP, MIC, SEND, SEND_RECORDING }

@Composable
private fun Composer(
    enabled: Boolean,
    isGenerating: Boolean,
    supportsImages: Boolean,
    supportsAudio: Boolean,
    pendingAttachment: ChatViewModel.PendingAttachment?,
    recording: RecordingState?,
    onSend: (String) -> Unit,
    onTyping: () -> Unit,
    onStop: () -> Unit,
    onPickImage: () -> Unit,
    onDiscardAttachment: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    // WhatsApp's voice messages: hold the mic to talk and let go to send, sliding left cancels;
    // a quick tap records hands-free until send or discard. A voice message goes on its own.
    var holding by remember { mutableStateOf(false) }
    var slide by remember { mutableFloatStateOf(0f) }

    Column(Modifier.navigationBarsPadding()) {
        HorizontalDivider(color = AppColors.Border)

        if (recording == null) pendingAttachment?.let { PendingAttachmentChip(it, onDiscardAttachment) }

        // WhatsApp's rule: the mic turns into send as soon as there is something to send.
        val hasContent = text.isNotBlank() || pendingAttachment != null
        val action = when {
            recording != null -> ComposerAction.SEND_RECORDING
            isGenerating -> ComposerAction.STOP
            supportsAudio && !hasContent -> ComposerAction.MIC
            else -> ComposerAction.SEND
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (recording != null) {
                RecordingInfo(recording, holding, slide, onCancelRecording, Modifier.weight(1f))
            } else {
                if (supportsImages) {
                    IconButton(
                        onClick = onPickImage,
                        enabled = enabled && !isGenerating,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Image,
                            contentDescription = "Attach image",
                            tint = if (enabled && !isGenerating) AppColors.TextSecondary else AppColors.Border,
                        )
                    }
                }

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
                        onValueChange = {
                            text = it
                            onTyping()
                        },
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
            }

            val actionEnabled = when (action) {
                ComposerAction.STOP, ComposerAction.SEND_RECORDING -> true
                ComposerAction.MIC -> enabled
                ComposerAction.SEND -> enabled && hasContent
            }
            ComposerButton(
                action = action,
                enabled = actionEnabled,
                holding = holding,
                onClick = { pressed ->
                    when (pressed) {
                        ComposerAction.STOP -> onStop()
                        // Without a hold (TalkBack, or a tap): hands-free recording.
                        ComposerAction.MIC -> onStartRecording()
                        ComposerAction.SEND_RECORDING -> onStopRecording()
                        ComposerAction.SEND -> {
                            onSend(text)
                            text = ""
                        }
                    }
                },
                onHoldStart = {
                    holding = true
                    onStartRecording()
                },
                onSlide = { slide = it },
                onHoldEnd = { result ->
                    holding = false
                    slide = 0f
                    when (result) {
                        HoldResult.SEND -> onStopRecording()
                        HoldResult.CANCEL -> onCancelRecording()
                        HoldResult.KEEP_RECORDING -> Unit
                    }
                },
            )
        }
    }
}

private enum class HoldResult { SEND, CANCEL, KEEP_RECORDING }

/**
 * The composer's round button. On the mic, pressing starts recording at once: let go after
 * [TAP_MS] and it's sent, slide [SLIDE_TO_CANCEL] left and it's dropped, let go sooner (a tap)
 * and it keeps recording hands-free. Any other action is a plain tap.
 *
 * One gesture handler for every action, so a press that starts on the mic keeps being followed
 * after the button turns into the recording's send.
 */
@Composable
private fun ComposerButton(
    action: ComposerAction,
    enabled: Boolean,
    holding: Boolean,
    onClick: (ComposerAction) -> Unit,
    onHoldStart: () -> Unit,
    onSlide: (Float) -> Unit,
    onHoldEnd: (HoldResult) -> Unit,
) {
    val currentAction by rememberUpdatedState(action)
    val currentEnabled by rememberUpdatedState(enabled)
    val click by rememberUpdatedState(onClick)
    val holdStart by rememberUpdatedState(onHoldStart)
    val slideTo by rememberUpdatedState(onSlide)
    val holdEnd by rememberUpdatedState(onHoldEnd)
    val cancelAtPx = with(LocalDensity.current) { SLIDE_TO_CANCEL.toPx() }
    val scale by animateFloatAsState(if (holding) 1.3f else 1f, label = "mic")
    val label = when (action) {
        ComposerAction.STOP -> "Stop"
        ComposerAction.MIC -> "Record voice message. Hold to talk, or tap for hands-free."
        ComposerAction.SEND_RECORDING -> "Send voice message"
        ComposerAction.SEND -> "Send"
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(if (enabled) AppColors.Accent else AppColors.Border)
            .semantics {
                role = SemanticsRole.Button
                contentDescription = label
                if (!enabled) disabled()
                onClick(label) {
                    click(currentAction)
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!currentEnabled) return@awaitEachGesture
                    val pressed = currentAction
                    if (pressed == ComposerAction.MIC) holdStart()
                    var cancelled = false
                    var released: PointerInputChange? = null
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (pressed == ComposerAction.MIC) {
                            val dx = (change.position.x - down.position.x).coerceAtMost(0f)
                            slideTo(dx)
                            if (!cancelled && dx < -cancelAtPx) cancelled = true
                            change.consume()
                        }
                        if (!change.pressed) {
                            released = change
                            break
                        }
                    }
                    if (pressed == ComposerAction.MIC) {
                        val heldMs = (released?.uptimeMillis ?: down.uptimeMillis) - down.uptimeMillis
                        holdEnd(
                            when {
                                cancelled -> HoldResult.CANCEL
                                // A tap, or a press the system took away: carry on hands-free.
                                released == null || heldMs < TAP_MS -> HoldResult.KEEP_RECORDING
                                else -> HoldResult.SEND
                            },
                        )
                    } else if (released != null) {
                        val inside = released.position.x in 0f..size.width.toFloat() &&
                            released.position.y in 0f..size.height.toFloat()
                        if (inside) click(pressed)
                    }
                }
            },
    ) {
        Icon(
            imageVector = when {
                // Held, it stays a mic, as on WhatsApp: letting go is what sends.
                holding -> Icons.Outlined.Mic
                action == ComposerAction.STOP -> Icons.Outlined.Stop
                action == ComposerAction.MIC -> Icons.Outlined.Mic
                else -> Icons.AutoMirrored.Outlined.Send
            },
            contentDescription = null,
            tint = AppColors.OnAccent,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Where the text field was, while recording: elapsed time, and either how to cancel (held) or
 * a discard button and the time left (hands-free). The cap is a hard stop, so near it the time
 * left shows either way.
 */
@Composable
private fun RecordingInfo(
    recording: RecordingState,
    holding: Boolean,
    slide: Float,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (holding) {
            Spacer(Modifier.width(8.dp))
        } else {
            IconButton(onClick = onDiscard, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = "Discard recording",
                    tint = AppColors.TextSecondary,
                )
            }
        }

        RecordingDot()

        Text(
            text = formatDuration(recording.elapsedMs),
            style = MaterialTheme.typography.bodyLarge,
            color = AppColors.TextPrimary,
        )

        when {
            recording.isNearlyOutOfTime || !holding -> Text(
                text = "${recording.remainingSeconds}s left",
                style = MaterialTheme.typography.bodyMedium,
                color = if (recording.isNearlyOutOfTime) AppColors.Danger else AppColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            else -> Text(
                text = "‹ Slide to cancel",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
                    .offset { IntOffset((slide / 2).roundToInt(), 0) },
            )
        }
    }
}

/** Shown while the reply to a voice message is read aloud. */
@Composable
private fun SpeakingBar(onStop: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.VolumeUp,
            contentDescription = null,
            tint = AppColors.TextSecondary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            "Reading the reply aloud",
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextSecondary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onStop) { Text("Stop") }
    }
}

/** Shorter than this, a press on the mic is a tap: hands-free recording rather than hold-to-talk. */
private const val TAP_MS = 400L

/** How far left a held mic has to slide to throw the recording away. */
private val SLIDE_TO_CANCEL = 96.dp

@Composable
private fun PendingAttachmentChip(
    attachment: ChatViewModel.PendingAttachment,
    onDiscard: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (attachment.kind) {
            AttachmentKind.IMAGE -> AttachedImage(attachment.path, maxHeight = 96)
            AttachmentKind.AUDIO -> AudioAttachment(attachment.path, attachment.durationMs)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onDiscard, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Remove attachment",
                tint = AppColors.TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private const val STREAMING_ITEM_KEY = "streaming"
private const val TRIMMED_NOTICE_KEY = "trimmed-notice"

/** How much of the screen the chat history drawer takes. */
private const val DRAWER_FRACTION = 0.85f
