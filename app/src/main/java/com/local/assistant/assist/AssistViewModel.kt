package com.local.assistant.assist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.local.assistant.AppContainer
import com.local.assistant.chat.ChatSender
import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.db.MessageEntity
import com.local.assistant.data.db.Role
import com.local.assistant.data.prefs.SettingsStore
import com.local.assistant.data.repo.ChatRepository
import com.local.assistant.device.PermissionBroker
import com.local.assistant.llm.LlmService
import com.local.assistant.media.AttachmentStore
import com.local.assistant.media.AudioRecorder
import com.local.assistant.media.Recording
import com.local.assistant.memory.prompt.ConversationManager
import com.local.assistant.memory.tools.MemoryChip
import com.local.assistant.memory.tools.SystemAlarms
import com.local.assistant.memory.tools.ToolExecutor
import com.local.assistant.ui.chat.ChatViewModel
import com.local.assistant.ui.chat.chipsByMessage
import com.local.assistant.voice.AudioFocus
import com.local.assistant.voice.EndOfSpeech
import com.local.assistant.voice.Speaker
import com.local.assistant.voice.SpeechChunker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The assistant overlay: listen, send, answer — out loud when asked out loud.
 *
 * Each time the overlay opens it starts a new chat, made when the first question is sent, so it
 * shows up in the app's history like any other; follow-ups while it is open go to the same chat.
 * Replies go through the app's [ChatSender], so one still coming when the overlay closes finishes
 * in that chat rather than being cut off.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AssistViewModel(
    private val sender: ChatSender,
    private val repository: ChatRepository,
    private val llm: LlmService,
    private val conversations: ConversationManager,
    private val tools: ToolExecutor,
    private val clockAlarms: SystemAlarms,
    private val settings: SettingsStore,
    private val attachments: AttachmentStore,
    private val recorder: AudioRecorder,
    private val speaker: Speaker,
    private val focus: AudioFocus,
    permissions: PermissionBroker,
) : ViewModel() {

    enum class Input { VOICE, TEXT }

    /**
     * A question sent but not yet in the chat: waiting for an earlier reply to finish, or being
     * stored. [storedId] is its message once stored; it shows until the chat does.
     */
    data class Pending(val text: String, val audioPath: String?, val durationMs: Long?, val storedId: Long? = null)

    /** A tool waiting on a permission dialog, e.g. contacts for "call amma". */
    val permissionRequests: SharedFlow<PermissionBroker.Request> = permissions.requests

    val engineState: StateFlow<LlmService.State> = llm.state

    private val _chatId = MutableStateFlow<Long?>(null)

    /** The chat this overlay writes to; null until the first question is sent. */
    val chatId: StateFlow<Long?> = _chatId.asStateFlow()

    private val stored = _chatId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repository.observeMessages(id) }

    val messages: StateFlow<List<MessageEntity>> = stored
        .map { messages -> messages.filter { it.role != Role.TOOL } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val chips: StateFlow<Map<Long, List<ChatViewModel.ChipItem>>> = stored
        .map(::chipsByMessage)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _pending = MutableStateFlow<Pending?>(null)

    /** [Pending], until the chat has it. */
    val pending: StateFlow<Pending?> = combine(_pending, messages) { pending, messages ->
        pending?.takeIf { p -> p.storedId == null || messages.none { it.id == p.storedId } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** This overlay's reply as it streams; "" before the first word. */
    val streamingText: StateFlow<String?> = sender.live
        .map { live -> live?.takeIf { it.owner === this@AssistViewModel }?.text }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private val _level = MutableStateFlow(0f)

    /** The voice's loudness while listening, 0..1, for the waveform. */
    val level: StateFlow<Float> = _level.asStateFlow()

    val speaking: StateFlow<Boolean> = speaker.speaking

    private val _input = MutableStateFlow(Input.VOICE)
    val input: StateFlow<Input> = _input.asStateFlow()

    private val _muted = MutableStateFlow(!settings.speakReplies)

    /** Replies to voice questions are not read aloud this time. Starts from the setting. */
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)

    /** Something to tell the user in the overlay: nothing was heard, the reply failed. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _askForNotifications = MutableStateFlow(false)

    /** A timed reminder was set and notifications have never been asked for. */
    val askForNotifications: StateFlow<Boolean> = _askForNotifications.asStateFlow()

    /** Cuts the streaming reply into sentences to speak; null when this reply isn't spoken. */
    private var chunker: SpeechChunker? = null

    /** A spoken reply is on its way: the other audio stays quiet until it has been said. */
    private val _awaitingSpeech = MutableStateFlow(false)

    private var endOfSpeech: Job? = null
    private var sendJob: Job? = null
    private var nothingHeard = false
    private var autoStarted = false

    /** Set once the overlay is gone; a reply still finishing must not speak or touch state. */
    @Volatile
    private var closed = false

    init {
        llm.warmUp()
        // The first message here always starts a new chat, whose conversation has to read the
        // whole system prompt first (3.4 s on the phone, 6 s just after the model loads). Do it
        // now, while the user is still talking; the first message then takes it over.
        conversations.prepareFresh()

        // Speak each sentence as soon as it is complete.
        viewModelScope.launch {
            sender.live.collect { live ->
                if (live?.owner === this@AssistViewModel) chunker?.push(live.text)?.forEach(speaker::speak)
            }
        }

        // Music pauses while the assistant listens and answers, and comes back once it is done.
        // The short grace period bridges the gaps between sentences and between turns.
        viewModelScope.launch {
            combine(_listening, speaker.speaking, _awaitingSpeech) { listening, speaking, awaiting -> listening || speaking || awaiting }
                .distinctUntilChanged()
                .collectLatest { busy ->
                    if (busy) {
                        focus.hold()
                    } else {
                        delay(FOCUS_GRACE_MS)
                        focus.release()
                    }
                }
        }
    }

    /**
     * Whether the overlay should start listening as it opens: once, and only with a model that
     * takes audio (unknown until it has loaded, and the app's model does). False means it opens
     * ready to type.
     */
    fun claimAutoStart(): Boolean {
        if (autoStarted) return false
        autoStarted = true
        if (settings.modelPath == null) return false
        val hears = hearsAudio()
        if (!hears) _input.value = Input.TEXT
        return hears
    }

    /** The model takes voice messages — or hasn't loaded yet to say, and the app's model does. */
    fun hearsAudio(): Boolean = llm.modalities.value?.audio != false

    /** Caller holds RECORD_AUDIO. */
    fun startListening() {
        if (_listening.value || recorder.isRecording || closed) return
        stopSpeaking()
        _notice.value = null
        _input.value = Input.VOICE
        nothingHeard = false
        val detector = EndOfSpeech()
        _listening.value = true
        _level.value = 0f
        recorder.start(attachments.newAudioFile()) { recording ->
            // On the recorder's thread, possibly after the overlay closed.
            if (closed) {
                recording?.let { attachments.delete(it.file.absolutePath) }
            } else {
                viewModelScope.launch(Dispatchers.Main) { onRecorded(recording) }
            }
        }
        endOfSpeech?.cancel()
        endOfSpeech = viewModelScope.launch {
            recorder.state.filterNotNull().collect { state ->
                val verdict = detector.onLevel(state.elapsedMs, state.amplitude)
                _level.value = detector.level
                when (verdict) {
                    EndOfSpeech.Verdict.DONE -> recorder.stop()
                    EndOfSpeech.Verdict.NOTHING_HEARD -> {
                        nothingHeard = true
                        recorder.cancel()
                    }
                    EndOfSpeech.Verdict.LISTENING -> Unit
                }
            }
        }
    }

    /** The mic button while listening: that's all, send it. */
    fun finishListening() {
        if (_listening.value) recorder.stop()
    }

    /** The microphone permission was refused: typing still works. */
    fun onMicDenied() {
        _input.value = Input.TEXT
        _notice.value = "Allow the microphone to talk to the assistant. You can type instead."
    }

    fun switchToText() {
        if (_listening.value) recorder.cancel()
        _notice.value = null
        _input.value = Input.TEXT
    }

    fun sendText(text: String) {
        if (text.isBlank()) return
        stopSpeaking()
        send(text.trim(), attachment = null, voice = false)
    }

    /** The stop button: the reply stops where it is, and so does the voice. */
    fun stop() {
        stopSpeaking()
        if (sender.live.value?.owner === this) sender.stop()
        // Still waiting behind another reply: it never goes.
        if (_pending.value?.storedId == null) sendJob?.cancel()
    }

    fun stopSpeaking() {
        chunker = null
        _awaitingSpeech.value = false
        speaker.stop()
    }

    fun toggleMute() {
        val mute = !_muted.value
        _muted.value = mute
        if (mute) {
            stopSpeaking()
        } else {
            // Mid-reply: carry on out loud from the next sentence.
            streamingText.value?.let { reply ->
                chunker = SpeechChunker().also { it.skip(reply) }
                _awaitingSpeech.value = true
            }
        }
    }

    fun dismissNotice() {
        _notice.value = null
    }

    fun notificationsAsked() {
        settings.askedForNotifications = true
        _askForNotifications.value = false
    }

    fun undo(recordId: Long) {
        viewModelScope.launch {
            when (tools.undo(recordId)) {
                ToolExecutor.UndoResult.UNDONE -> conversations.prefixMayHaveChanged()
                ToolExecutor.UndoResult.CHANGED_SINCE -> _notice.value = "That has changed since, so it was left as it is."
                else -> Unit
            }
        }
    }

    fun editTime(recordId: Long, at: Long) {
        viewModelScope.launch {
            if (tools.editTime(recordId, at)) conversations.prefixMayHaveChanged() else _notice.value = "That reminder no longer exists."
        }
    }

    fun openClock() = clockAlarms.openClock()

    private fun onRecorded(recording: Recording?) {
        endOfSpeech?.cancel()
        _listening.value = false
        _level.value = 0f
        if (recording == null) {
            if (nothingHeard) _notice.value = "Didn't catch that. Tap the mic and try again."
            nothingHeard = false
            return
        }
        send(
            text = "",
            attachment = ChatSender.Attachment(recording.file.absolutePath, AttachmentKind.AUDIO, recording.durationMs),
            voice = true,
        )
    }

    private fun send(text: String, attachment: ChatSender.Attachment?, voice: Boolean) {
        // Read aloud when asked aloud. The model isn't told: an "answer briefly, it will be heard"
        // line talked it out of calling tools — "set an alarm for 6am" got "I've set an alarm"
        // and no alarm (RoutingEvalTest with -e voice: 22/32 actions with the line, 32/32 without).
        val speak = voice && !_muted.value
        _notice.value = null
        _pending.value = Pending(text, attachment?.path, attachment?.durationMs)
        _awaitingSpeech.value = speak
        sendJob = viewModelScope.launch {
            var started = false
            try {
                // One model, one turn at a time: a reply still coming (from the app, or from the
                // last time the overlay was open) goes first.
                sender.live.first { it == null }
                chunker = if (speak) SpeechChunker() else null
                // Not in the chat list; stored, and learned from, like any other chat.
                started = sender.send(_chatId.value, text, attachment, owner = this@AssistViewModel, listener = listener, hidden = true)
            } finally {
                if (!started) {
                    attachment?.let { attachments.delete(it.path) }
                    _pending.value = null
                    _awaitingSpeech.value = false
                }
            }
        }
    }

    private val listener = object : ChatSender.Listener {
        override fun onChatCreated(chatId: Long) {
            if (!closed) _chatId.value = chatId
        }

        override fun onUserMessageStored(messageId: Long) {
            if (!closed) _pending.update { it?.copy(storedId = messageId) }
        }

        override fun onMemory(chip: MemoryChip) {
            if (chip.kind == MemoryChip.Kind.TASK && chip.at != null && !settings.askedForNotifications) {
                _askForNotifications.value = true
            }
        }

        override fun onFinished(reply: String, failure: String?) {
            if (closed) return
            chunker?.finish(reply)?.forEach(speaker::speak)
            chunker = null
            _awaitingSpeech.value = false
            failure?.let { _notice.value = it }
            if (_pending.value?.storedId == null) _pending.value = null
        }
    }

    override fun onCleared() {
        closed = true
        endOfSpeech?.cancel()
        if (_listening.value) recorder.cancel()
        chunker = null
        speaker.stop()
        focus.release()
    }

    companion object {
        /** How long other audio stays paused after the assistant falls quiet. */
        private const val FOCUS_GRACE_MS = 800L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AssistViewModel(
                    sender = container.chatSender,
                    repository = container.chatRepository,
                    llm = container.llmService,
                    conversations = container.conversations,
                    tools = container.toolExecutor,
                    clockAlarms = container.clockAlarms,
                    settings = container.settings,
                    attachments = container.attachmentStore,
                    recorder = container.audioRecorder,
                    speaker = container.speaker,
                    focus = container.audioFocus,
                    permissions = container.permissions,
                )
            }
        }
    }
}
