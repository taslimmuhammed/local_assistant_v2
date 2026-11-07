package com.local.assistant.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.border
import androidx.compose.ui.draw.blur
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
import androidx.compose.runtime.DisposableEffect
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
import com.local.assistant.llm.LlmService
import com.local.assistant.media.RecordingState
import com.local.assistant.ui.theme.AppColors
import com.local.assistant.ui.theme.liquidGlass
import com.local.assistant.ui.theme.GlassRoundButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.heightIn
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.local.assistant.ui.theme.Glass
import com.local.assistant.ui.theme.GlassIconButton
import com.local.assistant.ui.theme.GlassNotice
import com.local.assistant.ui.theme.GlassSnackbarHost
import com.local.assistant.ui.theme.fadingEdges
import com.local.assistant.ui.theme.glass
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.local.assistant.ui.memory.ShowNotices
import com.local.assistant.ui.memory.ReminderEditor
import com.local.assistant.ui.memory.MemoryViewModel
import com.local.assistant.ui.memory.ConfirmDelete
import com.local.assistant.memory.db.TaskEntity
import com.local.assistant.memory.db.UserNoteEntity
import com.local.assistant.ui.notes.NotesViewModel

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenMemory: () -> Unit,
    onOpenReminders: () -> Unit,
    onOpenPhotos: () -> Unit,
    onOpenSettings: () -> Unit,
    /** Which starter suggestions can run: the web, photo and health ones need setting up first. */
    canSearchWeb: Boolean = false,
    canFindPhotos: Boolean = false,
    canReadHealth: Boolean = false,
    /** The user's first name, for the hello on a blank chat. */
    userName: String? = null,
    /** Reminders as the Reminders screen has them: a place reminder's map in a reply opens its editor here. */
    reminders: MemoryViewModel? = null,
    /** The user's notes: the Notes tile, and a note or list shown under the reply that changed it. */
    notes: NotesViewModel? = null,
    onOpenNotes: () -> Unit = {},
    /** Opens one note on the Notes screen. */
    onOpenNote: (UserNoteEntity) -> Unit = {},
) {
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val activeChatId by viewModel.activeChatId.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val streamingText by viewModel.streamingText.collectAsStateWithLifecycle()
    val streamingStep by viewModel.streamingStep.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val contextUsage by viewModel.contextUsage.collectAsStateWithLifecycle()
    val supportsImages by viewModel.supportsImages.collectAsStateWithLifecycle()
    val supportsAudio by viewModel.supportsAudio.collectAsStateWithLifecycle()
    val pendingAttachment by viewModel.pendingAttachment.collectAsStateWithLifecycle()
    val recording by viewModel.recordingState.collectAsStateWithLifecycle()
    val speaking by viewModel.speaking.collectAsStateWithLifecycle()
    val readingAloud by viewModel.readingAloud.collectAsStateWithLifecycle()
    val droppedFromContext by viewModel.droppedFromContext.collectAsStateWithLifecycle()
    val chips by viewModel.chips.collectAsStateWithLifecycle()
    val askForNotifications by viewModel.askForNotifications.collectAsStateWithLifecycle()
    val offerExactAlarms by viewModel.offerExactAlarms.collectAsStateWithLifecycle()
    val busyElsewhere by viewModel.busyElsewhere.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val placeReminders = reminders?.let { memory ->
        val tasks by memory.tasks.collectAsStateWithLifecycle()
        remember(tasks) { tasks.filter { it.place != null }.associateBy { it.id } }
    }.orEmpty()
    var editingReminder by remember { mutableStateOf<TaskEntity?>(null) }
    var deletingReminder by remember { mutableStateOf<TaskEntity?>(null) }
    val openReminder: ((TaskEntity) -> Unit)? = reminders?.let { { task -> editingReminder = task } }
    val notesById = notes?.let { vm ->
        val all by vm.all.collectAsStateWithLifecycle()
        remember(all) { all.associateBy { it.id } }
    }.orEmpty()
    // Each note under the latest reply that made or changed it, not under every one.
    val notePreviews = remember(chips) {
        chips.values.flatten().filter { it.chip.noteId != null && !it.undone }
            .groupBy { it.chip.noteId }.values.mapTo(HashSet()) { items -> items.maxOf { it.recordId } }
    }
    val openNote: ((UserNoteEntity) -> Unit)? = notes?.let { { note -> onOpenNote(note) } }
    val toggleNoteItem: (Long, Int) -> Unit = { id, index -> notes?.toggle(id, index) }

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

    // A reply read from its button stops when this screen gives way to Settings, Photos or Memory.
    DisposableEffect(viewModel) {
        onDispose { viewModel.stopReadingAloud() }
    }

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
    // the system dialog, and the tool carries on with the result. Only while on screen, and not
    // while the assistant overlay is over it: the overlay answers them then.
    ToolPermissionRequests(viewModel.permissionRequests, elsewhere = { com.local.assistant.assist.AssistActivity.inView })

    // A place reminder opened from its map in a reply: the Reminders screen's editor, and its Undo.
    reminders?.let { memory ->
        ShowNotices(memory, snackbarHostState)
        editingReminder?.let { task ->
            ReminderEditor(task, memory, onDelete = { deletingReminder = task }, onDone = { editingReminder = null })
        }
        deletingReminder?.let { task -> ConfirmDelete(task, memory, onDone = { deletingReminder = null }) }
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

    // The chat behind the drawer blurs as it opens, so the drawer reads as glass over it.
    val behindDrawer by animateDpAsState(if (drawerState.targetValue == DrawerValue.Open) DRAWER_BLUR else 0.dp, label = "drawer blur")
    ModalNavigationDrawer(
        drawerState = drawerState,
        scrimColor = Glass.Scrim,
        drawerContent = {
            // 85% of the screen, so the chat stays in sight behind it: the sheet's own cap
            // (360dp) is the whole width of a ~360dp phone.
            val drawerWidth = (LocalConfiguration.current.screenWidthDp * DRAWER_FRACTION).dp
            ModalDrawerSheet(
                // Glass over the blurred chat: clearer than a sheet, lit from above.
                modifier = Modifier.width(drawerWidth).liquidGlass(DrawerShape, DrawerGlass, sheen = 0.5f),
                drawerShape = DrawerShape,
                drawerContainerColor = Color.Transparent,
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
                    onOpenReminders = {
                        scope.launch { drawerState.close() }
                        onOpenReminders()
                    },
                    onOpenPhotos = {
                        scope.launch { drawerState.close() }
                        onOpenPhotos()
                    },
                    onOpenSettings = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
                    },
                    onOpenNotes = {
                        scope.launch { drawerState.close() }
                        onOpenNotes()
                    },
                )
            }
        },
    ) {
        Scaffold(
            modifier = Modifier.blur(behindDrawer),
            containerColor = AppColors.Background,
            snackbarHost = { GlassSnackbarHost(snackbarHostState) },
            topBar = {
                ChatTopBar(
                    title = chats.firstOrNull { it.id == activeChatId }?.title
                        ?: ChatRepository.DEFAULT_TITLE,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    // A blank chat is already a new one: "+" comes once this one has begun.
                    showNewChat = messages.isNotEmpty() || streamingText != null,
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

                // While the model loads, a message can still be sent: it waits for the engine.
                // Not while the overlay's reply is running, though: one turn at a time.
                val canSend = !busyElsewhere && (
                    engineState is LlmService.State.Ready ||
                        engineState is LlmService.State.Loading ||
                        engineState is LlmService.State.Idle
                    )

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (messages.isEmpty() && streamingText == null) {
                        StarterPanel(
                            chatKey = activeChatId,
                            name = userName,
                            web = canSearchWeb,
                            photos = canFindPhotos,
                            health = canReadHealth,
                            enabled = canSend && !isGenerating,
                            onPick = viewModel::send,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().fadingEdges(top = 20.dp, bottom = 12.dp),
                            contentPadding = PaddingValues(vertical = 12.dp),
                        ) {
                            if (droppedFromContext > 0) {
                                item(key = TRIMMED_NOTICE_KEY) { TrimmedNotice(droppedFromContext) }
                            }
                            // While a reply streams, the chips its tool calls made belong under it,
                            // not under the question.
                            val asking = messages.lastOrNull()?.takeIf { streamingText != null && it.role == Role.USER }
                            items(messages, key = { it.id }) { message ->
                                MessageRow(message, readingAloud = message.id == readingAloud, onReadAloud = viewModel::readAloud)
                                if (message != asking) {
                                    MemoryChips(
                                        chips = chips[message.id].orEmpty(),
                                        onUndo = viewModel::undo,
                                        onEditTime = viewModel::editTime,
                                        onOpenClock = viewModel::openClock,
                                        onOpenLink = openLink,
                                        placeReminders = placeReminders,
                                        onOpenReminder = openReminder,
                                        notes = notesById,
                                        notePreviews = notePreviews,
                                        onOpenNote = openNote,
                                        onToggleNoteItem = toggleNoteItem,
                                    )
                                }
                            }
                            streamingText?.let { partial ->
                                item(key = STREAMING_ITEM_KEY) {
                                    if (partial.isEmpty()) {
                                        // A message sent while the model is still starting waits
                                        // for it: say so, so the wait doesn't look like a hang. Then
                                        // what is being done: "Checking your memories…", a tool.
                                        ThinkingIndicator(label = if (engineState !is LlmService.State.Ready) "Loading model…" else streamingStep)
                                    } else {
                                        AssistantMessage(partial, streaming = true)
                                        // A tool partway through the reply.
                                        streamingStep?.let { ThinkingIndicator(label = it) }
                                    }
                                    asking?.let {
                                        MemoryChips(
                                            chips = chips[it.id].orEmpty(),
                                            onUndo = viewModel::undo,
                                            onEditTime = viewModel::editTime,
                                            onOpenClock = viewModel::openClock,
                                            onOpenLink = openLink,
                                            placeReminders = placeReminders,
                                            onOpenReminder = openReminder,
                                            notes = notesById,
                                            notePreviews = notePreviews,
                                            onOpenNote = openNote,
                                            onToggleNoteItem = toggleNoteItem,
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
                    enabled = canSend,
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
    showNewChat: Boolean,
    onNewChat: () -> Unit,
) {
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
            GlassIconButton(Icons.Outlined.Menu, "Chats", onOpenDrawer, Modifier.padding(start = 10.dp, end = 6.dp))
        },
        actions = {
            AnimatedVisibility(showNewChat, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                GlassIconButton(Icons.Outlined.Add, "New chat", onNewChat, Modifier.padding(end = 10.dp))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = AppColors.Background,
            titleContentColor = AppColors.TextPrimary,
            navigationIconContentColor = AppColors.TextPrimary,
            actionIconContentColor = AppColors.TextPrimary,
        ),
    )
}

@Composable
private fun QuietBanner(text: String) {
    GlassNotice(text)
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

    GlassNotice(message, color = if (failed) AppColors.Danger else AppColors.TextSecondary, onClick = if (failed) onRetry else null)
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
        if (recording == null) pendingAttachment?.let { PendingAttachmentChip(it, onDiscardAttachment) }

        // WhatsApp's rule: the mic turns into send as soon as there is something to send.
        val hasContent = text.isNotBlank() || pendingAttachment != null
        val action = when {
            recording != null -> ComposerAction.SEND_RECORDING
            isGenerating -> ComposerAction.STOP
            supportsAudio && !hasContent -> ComposerAction.MIC
            else -> ComposerAction.SEND
        }

        // Three pieces of glass, as in Apple's Liquid Glass: photos, the message, and the voice
        // button (send, once there is something to send).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (recording == null && supportsImages) {
                GlassRoundButton(Icons.Outlined.Image, "Attach image", onPickImage, enabled = enabled && !isGenerating, size = COMPOSER_HEIGHT)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = COMPOSER_HEIGHT)
                    .liquidGlass(ComposerShape),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (recording != null) {
                    RecordingInfo(recording, holding, slide, onCancelRecording, Modifier.fillMaxWidth().padding(end = 12.dp))
                } else {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
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
            .size(COMPOSER_HEIGHT)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .then(if (enabled) Modifier.background(Glass.Mine).liquidGlass(CircleShape, Color.Transparent, sheen = 0.22f) else Modifier.liquidGlass(CircleShape))
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
            tint = if (enabled) AppColors.OnAccent else AppColors.TextSecondary,
            modifier = Modifier.size(22.dp),
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

/** How much the chat behind the open drawer is blurred. */
private val DRAWER_BLUR = 20.dp

private val DrawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)
private val DrawerGlass = Color.White.copy(alpha = 0.76f)
private val ComposerShape = RoundedCornerShape(24.dp)

/** The message bar's height, and the size of its round buttons. */
private val COMPOSER_HEIGHT = 48.dp
