package com.local.assistant.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.net.Uri
import com.local.assistant.AppContainer
import com.local.assistant.chat.ChatSender
import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.db.ChatEntity
import com.local.assistant.data.db.MessageEntity
import com.local.assistant.data.db.Role
import com.local.assistant.data.prefs.SettingsStore
import com.local.assistant.data.repo.ChatRepository
import com.local.assistant.device.PermissionBroker
import com.local.assistant.llm.LlmService
import com.local.assistant.media.AttachmentStore
import com.local.assistant.media.AudioRecorder
import com.local.assistant.media.RecordingState
import com.local.assistant.memory.prompt.ConversationManager
import com.local.assistant.memory.prompt.TurnRunner
import com.local.assistant.memory.tools.ChatToolLog
import com.local.assistant.memory.tools.MemoryChip
import com.local.assistant.memory.tools.SystemAlarms
import com.local.assistant.memory.tools.ToolExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(
    private val repository: ChatRepository,
    private val llm: LlmService,
    private val conversations: ConversationManager,
    private val turns: TurnRunner,
    private val sender: ChatSender,
    private val tools: ToolExecutor,
    private val clockAlarms: SystemAlarms,
    private val settings: SettingsStore,
    private val attachments: AttachmentStore,
    private val recorder: AudioRecorder,
    permissions: PermissionBroker,
) : ViewModel() {

    /** A tool waiting on the user's answer to a permission dialog, e.g. contacts for "call amma". */
    val permissionRequests: SharedFlow<PermissionBroker.Request> = permissions.requests

    /** A memory chip as the chat shows it. [recordId] is the TOOL row it came from. */
    data class ChipItem(val recordId: Long, val chip: MemoryChip, val undone: Boolean, val canUndo: Boolean)

    /** A file staged for the next send, shown as a chip above the composer. */
    data class PendingAttachment(
        val path: String,
        val kind: AttachmentKind,
        val durationMs: Long? = null,
    )

    val chats: StateFlow<List<ChatEntity>> = repository.observeChats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Null means "a new, not-yet-created chat". The row is only written once the first message
     * is sent, so tapping "New chat" repeatedly does not litter the sidebar.
     */
    private val _activeChatId = MutableStateFlow<Long?>(null)
    val activeChatId: StateFlow<Long?> = _activeChatId.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val storedMessages = _activeChatId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repository.observeMessages(id) }

    val messages: StateFlow<List<MessageEntity>> = storedMessages
        // Tool calls are stored for the record but are not part of the conversation shown.
        .map { messages -> messages.filter { it.role != Role.TOOL } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Chips by the message they sit under (see [chipsByMessage]). */
    val chips: StateFlow<Map<Long, List<ChipItem>>> = storedMessages
        .map(::chipsByMessage)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Set when a timed reminder has been made and notifications have never been asked for. */
    private val _askForNotifications = MutableStateFlow(false)
    val askForNotifications: StateFlow<Boolean> = _askForNotifications.asStateFlow()

    /** Set once, the first time a reminder could only be scheduled inexactly. */
    private val _offerExactAlarms = MutableStateFlow(false)
    val offerExactAlarms: StateFlow<Boolean> = _offerExactAlarms.asStateFlow()

    /**
     * The reply being streamed into the chat on screen, or null when it has none in flight. The
     * reply may be one the assistant overlay asked for, opened here while it streams.
     */
    val streamingText: StateFlow<String?> = combine(sender.live, _activeChatId) { live, chatId ->
        live?.takeIf { isHere(it, chatId) }?.text
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The chat on screen is generating a reply. */
    val isGenerating: StateFlow<Boolean> = streamingText
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Another chat's reply is in flight — the overlay's, asked from the power button — and there is
     * only one model, so a send here waits until it is done.
     */
    val busyElsewhere: StateFlow<Boolean> = combine(sender.live, _activeChatId) { live, chatId ->
        live != null && !isHere(live, chatId)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** A new chat's stream has no chat yet: it is this screen's only if this screen sent it. */
    private fun isHere(live: ChatSender.Live, chatId: Long?): Boolean =
        live.chatId == chatId && (chatId != null || live.owner === this)

    private fun generatingHere(): Boolean = sender.live.value?.let { isHere(it, _activeChatId.value) } == true

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val engineState: StateFlow<LlmService.State> = llm.state

    init {
        // The chat opens at once and the model loads behind it; a message sent before it is ready
        // waits for it. Also covers a model installed just now, which the app's start didn't see.
        llm.warmUp()
    }

    /** After the model failed to start: from the banner that says so. */
    fun retryLoad() = llm.retryLoad()

    /** Real context consumption reported by the runtime, for the indicator above the composer. */
    val contextUsage: StateFlow<LlmService.ContextUsage?> = combine(conversations.contextUsage, _activeChatId) { usage, chatId ->
        usage?.takeIf { it.chatId == null || it.chatId == chatId }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** A send is waiting while older turns are summarised to make room. */
    val tidying: StateFlow<Boolean> = conversations.tidying

    val memoryPaused: StateFlow<Boolean> = settings.observeMemoryPaused()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.memoryPaused)

    /** How many older turns fell out of the context window for the active chat. */
    val droppedFromContext: StateFlow<Int> = conversations.droppedFromContext

    /** Drives whether the composer offers the image and mic buttons at all. */
    val supportsImages: StateFlow<Boolean> = llm.modalities
        .map { it?.vision == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val supportsAudio: StateFlow<Boolean> = llm.modalities
        .map { it?.audio == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _pendingAttachment = MutableStateFlow<PendingAttachment?>(null)
    val pendingAttachment: StateFlow<PendingAttachment?> = _pendingAttachment.asStateFlow()

    val recordingState: StateFlow<RecordingState?> = recorder.state

    fun startNewChat() {
        if (generatingHere()) return
        _activeChatId.value = null
        conversations.onChatSelected(null)
    }

    fun selectChat(chatId: Long) {
        if (generatingHere() || _activeChatId.value == chatId) return
        _activeChatId.value = chatId
        conversations.onChatSelected(chatId)
    }

    /**
     * From the assistant overlay's "Open in app": show that chat, even over one generating here.
     * Its reply, if still streaming, shows as it would have had it been sent from this screen.
     */
    fun openChat(chatId: Long) {
        if (_activeChatId.value == chatId) return
        _activeChatId.value = chatId
        conversations.onChatSelected(chatId)
    }

    /** Every keystroke: background model work yields to the person typing. */
    fun onTyping() = turns.onUserActivity()

    fun deleteChat(chatId: Long) {
        viewModelScope.launch {
            repository.deleteChat(chatId)
            conversations.forget(chatId)
            // The rows are gone, so any file they referenced is now orphaned.
            attachments.pruneExcept(repository.attachmentPaths())
            if (_activeChatId.value == chatId) _activeChatId.value = null
        }
    }

    fun dismissError() {
        _error.value = null
    }

    fun attachImage(uri: Uri) {
        viewModelScope.launch {
            discardPendingAttachment()
            runCatching { attachments.importImage(uri) }
                .onSuccess {
                    _pendingAttachment.value = PendingAttachment(it.absolutePath, AttachmentKind.IMAGE)
                }
                .onFailure { _error.value = it.message ?: "Could not attach that image" }
        }
    }

    fun discardPendingAttachment() {
        _pendingAttachment.value?.let { attachments.delete(it.path) }
        _pendingAttachment.value = null
    }

    /** Caller must already hold RECORD_AUDIO permission. */
    fun startRecording() {
        if (generatingHere() || recorder.isRecording) return
        discardPendingAttachment()
        val destination = attachments.newAudioFile()
        recorder.start(destination) { recording ->
            _pendingAttachment.value = recording?.let {
                PendingAttachment(it.file.absolutePath, AttachmentKind.AUDIO, it.durationMs)
            }
        }
    }

    fun stopRecording() = recorder.stop()

    fun cancelRecording() = recorder.cancel()

    fun send(text: String) {
        val attachment = _pendingAttachment.value
        val started = sender.send(
            chatId = _activeChatId.value,
            text = text,
            attachment = attachment?.let { ChatSender.Attachment(it.path, it.kind, it.durationMs) },
            owner = this,
            listener = object : ChatSender.Listener {
                override fun onChatCreated(chatId: Long) {
                    _activeChatId.value = chatId
                }

                override fun onMemory(chip: MemoryChip) = noticeReminder(chip)

                override fun onFinished(reply: String, failure: String?) {
                    _error.value = failure
                }
            },
        )
        if (started) _pendingAttachment.value = null
    }

    /** Puts back whatever the chip's tool call changed. */
    fun undo(recordId: Long) {
        viewModelScope.launch {
            when (tools.undo(recordId)) {
                ToolExecutor.UndoResult.UNDONE -> conversations.prefixMayHaveChanged()
                ToolExecutor.UndoResult.CHANGED_SINCE -> _error.value = "That has changed since, so it was left as it is."
                else -> Unit
            }
        }
    }

    /** Moves the chip's reminder or event to [at]. */
    fun editTime(recordId: Long, at: Long) {
        viewModelScope.launch {
            if (tools.editTime(recordId, at)) {
                conversations.prefixMayHaveChanged()
            } else {
                _error.value = "That reminder no longer exists."
            }
        }
    }

    fun openClock() = clockAlarms.openClock()

    fun notificationsAsked() {
        settings.askedForNotifications = true
        _askForNotifications.value = false
    }

    fun exactAlarmsOffered() {
        settings.offeredExactAlarms = true
        _offerExactAlarms.value = false
    }

    private fun noticeReminder(chip: MemoryChip) {
        if (chip.kind != MemoryChip.Kind.TASK || chip.at == null) return
        if (!settings.askedForNotifications) _askForNotifications.value = true
        if (chip.note != null && !settings.offeredExactAlarms) _offerExactAlarms.value = true
    }

    fun stop() {
        if (generatingHere()) sender.stop()
    }

    override fun onCleared() {
        // As when this screen ran its own turns: gone for good, it takes its reply with it.
        if (sender.live.value?.owner === this) sender.stop()
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ChatViewModel(
                    repository = container.chatRepository,
                    llm = container.llmService,
                    conversations = container.conversations,
                    turns = container.turnRunner,
                    sender = container.chatSender,
                    tools = container.toolExecutor,
                    clockAlarms = container.clockAlarms,
                    settings = container.settings,
                    attachments = container.attachmentStore,
                    recorder = container.audioRecorder,
                    permissions = container.permissions,
                )
            }
        }
    }
}

/**
 * Chips by the message they sit under: the reply that followed the tool calls, or — while that
 * reply is still streaming, or if there never was one — the user message that asked.
 */
internal fun chipsByMessage(messages: List<MessageEntity>): Map<Long, List<ChatViewModel.ChipItem>> {
    val byMessage = mutableMapOf<Long, List<ChatViewModel.ChipItem>>()
    var asker: Long? = null
    var waiting = mutableListOf<ChatViewModel.ChipItem>()
    for (message in messages) {
        when (message.role) {
            Role.USER -> {
                asker?.let { if (waiting.isNotEmpty()) byMessage[it] = waiting }
                asker = message.id
                waiting = mutableListOf()
            }
            Role.TOOL -> ChatToolLog.parse(message.text)?.let { record ->
                record.chip?.let { waiting += ChatViewModel.ChipItem(message.id, it, record.undone, record.undo != null) }
            }
            Role.ASSISTANT -> {
                if (waiting.isNotEmpty()) byMessage[message.id] = waiting
                asker = null
                waiting = mutableListOf()
            }
        }
    }
    asker?.let { if (waiting.isNotEmpty()) byMessage[it] = waiting }
    return byMessage
}
