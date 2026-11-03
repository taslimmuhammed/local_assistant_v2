package com.local.assistant.away

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.local.assistant.AppContainer
import com.local.assistant.media.AttachmentStore
import com.local.assistant.media.AudioRecorder
import com.local.assistant.voice.EndOfSpeech
import com.local.assistant.voice.Speaker
import com.local.assistant.voice.Transcriber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The brief on screen: what it shows, and what the user does from it — send a reply (after a
 * few seconds in which it can be taken back), call back, open the chat, or swipe it away.
 */
class BriefViewModel(
    private val app: Application,
    private val awayBrief: AwayBrief,
    private val recorder: AudioRecorder,
    private val transcriber: Transcriber?,
    private val attachments: AttachmentStore,
    private val speaker: Speaker,
    val newOnly: Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    /** Where a reply is: being written, about to go (and can be taken back), gone, or stuck. */
    sealed interface Send {
        data class Countdown(val text: String, val endsAt: Long) : Send
        data class Sending(val text: String) : Send
        data class Sent(val text: String) : Send
        /** The chat's notification can't take a reply (gone, or never had a Reply button). */
        data class NoButton(val text: String) : Send
        data class Failed(val text: String, val reason: String) : Send
    }

    /** What the brief shows, after the user's own changes to it. */
    data class Shown(
        val brief: Brief,
        /** The model's headline, until the user deals with something it may name; then what's left, counted. */
        val headline: String?,
        val needs: List<BriefItem>,
        val fyi: List<BriefItem>,
        val skipped: List<BriefItem>,
        /** Shown at least once with something in it: an empty brief now means "all done". */
        val hadItems: Boolean,
        val loaded: Boolean,
    ) {
        val isEmpty: Boolean get() = needs.isEmpty() && fyi.isEmpty() && skipped.isEmpty()
    }

    /** Listening for a reply to [key]. [confirming]: the draft is written; waiting for "send". */
    data class Listening(val key: String, val confirming: Boolean, val level: Float = 0f)

    private val _sends = MutableStateFlow<Map<String, Send>>(emptyMap())
    val sends: StateFlow<Map<String, Send>> = _sends.asStateFlow()

    private val _drafts = MutableStateFlow<Map<String, String>>(emptyMap())
    val drafts: StateFlow<Map<String, String>> = _drafts.asStateFlow()

    private val _listening = MutableStateFlow<Listening?>(null)
    val listening: StateFlow<Listening?> = _listening.asStateFlow()

    /** A line under the brief for a moment: "Copied — paste it in WhatsApp". */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    val reading: StateFlow<Boolean> = awayBrief.reading
    val speaking: StateFlow<Boolean> = speaker.speaking

    /** Swiped away, replied to or called back: gone from the brief, whatever the database says yet. */
    private val hidden = MutableStateFlow<Set<String>>(emptySet())

    /** Items mid-send, held where they were while the database catches up. */
    private val pinned = MutableStateFlow<Map<String, Pinned>>(emptyMap())

    private data class Pinned(val item: BriefItem, val index: Int)

    private var hadItems = false
    private var lastBrief = Brief.EMPTY

    val shown: StateFlow<Shown> = combine(awayBrief.observe(newOnly), hidden, pinned) { brief, hidden, pinned ->
        lastBrief = brief
        val needs = brief.needs.filter { it.key !in hidden || it.key in pinned }.toMutableList()
        // Sent: the chat app's own notification now ends with the reply, which makes it "answered"
        // and moves it out of Needs; it stays put until its tick has been seen.
        pinned.values.sortedBy { it.index }.forEach { pin ->
            if (needs.none { it.key == pin.item.key }) needs.add(pin.index.coerceAtMost(needs.size), pin.item)
        }
        val keys = needs.map { it.key }.toSet()
        val fyi = brief.fyi.filter { it.key !in hidden && it.key !in keys }
        val skipped = brief.skipped.filter { it.key !in hidden && it.key !in keys }
        if (needs.isNotEmpty() || fyi.isNotEmpty() || skipped.isNotEmpty()) hadItems = true
        val headline = if (hidden.isEmpty() && pinned.isEmpty()) brief.headline else BriefBuilder.ruleHeadline(needs.filter { it.key !in pinned } + fyi + skipped)
        Shown(brief, headline, needs, fyi, skipped, hadItems, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Shown(Brief.EMPTY, null, emptyList(), emptyList(), emptyList(), hadItems = false, loaded = false))

    private val countdowns = ConcurrentHashMap<String, Job>()
    private var listenJob: Job? = null
    private var words: Transcriber.Session? = null
    private val icons = ConcurrentHashMap<String, ImageBitmap?>()

    init {
        // Whatever the model hasn't read yet, it reads now: drafted replies arrive as it goes.
        viewModelScope.launch { awayBrief.triage(user = true) }
    }

    // ---- Replies ----

    fun draft(key: String): String = _drafts.value[key].orEmpty()

    fun editDraft(key: String, text: String) {
        _drafts.update { it + (key to text) }
    }

    /** Sends [text] to [item] after [UNDO_MS], unless taken back. */
    fun send(item: BriefItem, text: String) {
        val reply = text.trim()
        if (reply.isEmpty() || _sends.value[item.key] is Send.Sending || _sends.value[item.key] is Send.Sent) return
        stopListening()
        pin(item)
        _drafts.update { it + (item.key to reply) }
        _sends.update { it + (item.key to Send.Countdown(reply, clock() + UNDO_MS)) }
        countdowns.remove(item.key)?.cancel()
        countdowns[item.key] = viewModelScope.launch {
            delay(UNDO_MS)
            deliver(item, reply)
        }
    }

    /** Taken back during the countdown: the text goes back into the box. */
    fun undo(key: String) {
        countdowns.remove(key)?.cancel() ?: return
        _sends.update { it - key }
        unpin(key)
    }

    /** Back to writing, after a reply couldn't go. */
    fun retry(key: String) {
        _sends.update { it - key }
        unpin(key)
    }

    private suspend fun deliver(item: BriefItem, text: String) {
        countdowns.remove(item.key)
        _sends.update { it + (item.key to Send.Sending(text)) }
        val outcome = withContext(Dispatchers.Default) { awayBrief.replies.reply(app, item.key, text) }
        when (outcome) {
            ReplyActions.Outcome.Sent -> {
                Log.i(TAG, "Reply sent from the brief")
                _sends.update { it + (item.key to Send.Sent(text)) }
                delay(SENT_SHOWN_MS)
                done(item.key)
            }
            ReplyActions.Outcome.NoButton -> _sends.update { it + (item.key to Send.NoButton(text)) }
            is ReplyActions.Outcome.Failed -> _sends.update { it + (item.key to Send.Failed(text, outcome.reason)) }
        }
    }

    /**
     * The brief is closing with replies still counting down: the user pressed Send and didn't
     * take it back, so they go now rather than being lost with the screen.
     */
    fun sendWaitingNow() {
        val waiting = _sends.value.filterValues { it is Send.Countdown }
        waiting.forEach { (key, send) ->
            countdowns.remove(key)?.cancel()
            val text = (send as Send.Countdown).text
            if (awayBrief.replies.reply(app, key, text) == ReplyActions.Outcome.Sent) awayBrief.markSeen(listOf(key))
        }
    }

    /** The chat can't take a reply from here: the reply goes on the clipboard, and the chat opens. */
    fun copyAndOpen(item: BriefItem, text: String, context: Context) {
        app.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Reply", text))
        val opened = open(item, context)
        _notice.value = if (opened) "Reply copied. Paste it in ${item.appLabel}." else "Reply copied. Open ${item.appLabel} to paste it."
    }

    // ---- Other actions ----

    /** Presses the missed call's Call back, from the screen in front. */
    fun callBack(item: BriefItem, context: Context): Boolean {
        val pressed = awayBrief.replies.callBack(context, item.key)
        if (pressed) done(item.key, now = true)
        return pressed
    }

    /** Opens the chat or the app, as tapping its notification would. */
    fun open(item: BriefItem, context: Context): Boolean {
        val opened = awayBrief.replies.open(context, item.key) || launchApp(item.packageName, context)
        if (opened) done(item.key, now = true)
        return opened
    }

    fun canReply(item: BriefItem): Boolean = awayBrief.replies.canReply(item.key)

    fun canCallBack(item: BriefItem): Boolean = awayBrief.replies.canCallBack(item.key)

    /** Swiped away: seen, and out of every brief. */
    fun dismiss(item: BriefItem) {
        stopListening()
        done(item.key, now = true)
    }

    /** "Mark all seen". */
    fun dismissAll() {
        val keys = shown.value.let { it.needs + it.fyi + it.skipped }.map { it.key }
        hidden.update { it + keys }
        awayBrief.markSeen(keys)
    }

    /** The brief closed: what it showed won't open the next one. */
    fun closed() {
        stopListening()
        speaker.stop()
        awayBrief.markBriefed(lastBrief.lineIds)
    }

    private fun done(key: String, now: Boolean = false) {
        hidden.update { it + key }
        unpin(key)
        _sends.update { it - key }
        awayBrief.markSeen(listOf(key))
        if (now) countdowns.remove(key)?.cancel()
    }

    private fun pin(item: BriefItem) {
        val index = shown.value.needs.indexOfFirst { it.key == item.key }.coerceAtLeast(0)
        pinned.update { it + (item.key to Pinned(item, index)) }
    }

    private fun unpin(key: String) {
        pinned.update { it - key }
    }

    private fun launchApp(packageName: String, context: Context): Boolean {
        val launch = app.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    fun clearNotice() {
        _notice.value = null
    }

    /** The app's icon, for the badge on each person. */
    fun icon(packageName: String): ImageBitmap? = icons.getOrPut(packageName) {
        runCatching {
            app.packageManager.getApplicationIcon(packageName).toBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888).asImageBitmap()
        }.getOrNull()
    }

    // ---- Voice ----

    /**
     * Listens for a reply to [item] (the caller holds RECORD_AUDIO). Said words go in the box;
     * then it listens once more for "send", "the second one" or "cancel".
     */
    fun listen(item: BriefItem, confirming: Boolean = false) {
        speaker.stop()
        listenJob?.cancel()
        words?.cancel()
        words = null
        if (recorder.isRecording) recorder.cancel()
        _listening.value = Listening(item.key, confirming)
        listenJob = viewModelScope.launch {
            // A recording just stopped may still be closing its file.
            while (recorder.isRecording) delay(25)
            val session = transcriber?.start(hints = VoiceReply.HINTS)
            if (session == null) {
                _listening.value = null
                _notice.value = "Voice replies need the phone's on-device speech recognition."
                return@launch
            }
            words = session
            val detector = EndOfSpeech()
            recorder.start(attachments.newAudioFile(), onAudio = session::feed) { recording ->
                // On the recorder's thread. Only the words are wanted; the audio goes.
                recording?.let { attachments.delete(it.file.absolutePath) }
                viewModelScope.launch(Dispatchers.Main) {
                    val said = if (recording == null) null else session.finish()
                    if (recording == null) session.cancel()
                    if (words === session) words = null
                    heard(item, confirming, said)
                }
            }
            recorder.state.filterNotNull().collect { state ->
                val verdict = detector.onLevel(state.elapsedMs, state.amplitude)
                _listening.update { it?.copy(level = detector.level) }
                when (verdict) {
                    EndOfSpeech.Verdict.DONE -> recorder.stop()
                    EndOfSpeech.Verdict.NOTHING_HEARD -> recorder.cancel()
                    EndOfSpeech.Verdict.LISTENING -> Unit
                }
            }
        }
    }

    fun onMicDenied() {
        _notice.value = "Allow the microphone to reply by voice."
    }

    /** The mic while listening: done talking. */
    fun finishListening() {
        if (recorder.isRecording) recorder.stop()
    }

    fun stopListening() {
        if (_listening.value == null) return
        listenJob?.cancel()
        _listening.value = null
        if (recorder.isRecording) recorder.cancel()
    }

    private fun heard(item: BriefItem, confirming: Boolean, said: String?) {
        if (_listening.value?.key != item.key) return
        listenJob?.cancel()
        _listening.value = null
        if (said.isNullOrBlank()) return
        val draft = draft(item.key)
        when (VoiceReply.meaning(said)) {
            VoiceReply.Meaning.SEND -> {
                val reply = draft.ifBlank { item.replies.firstOrNull().orEmpty() }
                // "Yes" with nothing written or drafted is the answer itself.
                if (reply.isNotBlank()) send(item, reply) else heardReply(item, confirming, said)
            }
            VoiceReply.Meaning.SEND_SECOND -> item.replies.getOrNull(1)?.let { send(item, it) }
            VoiceReply.Meaning.CANCEL -> if (confirming) editDraft(item.key, "")
            VoiceReply.Meaning.REPLY -> heardReply(item, confirming, said)
        }
    }

    private fun heardReply(item: BriefItem, confirming: Boolean, said: String) {
        editDraft(item.key, said)
        // Written down: once more, for "send" (only once, so it doesn't listen forever).
        if (!confirming) listen(item, confirming = true)
    }

    // ---- Reading aloud ----

    fun toggleSpeaking() {
        if (speaker.speaking.value) {
            speaker.stop()
            return
        }
        stopListening()
        val shown = shown.value
        val parts = buildList {
            shown.brief.headline?.let { add(it.trimEnd('.') + ".") }
            shown.needs.take(4).forEach { item ->
                add(spokenGist(item))
                item.replies.firstOrNull()?.let { add("You could say: $it.") }
            }
            if (shown.fyi.isNotEmpty()) add("Also ${shown.fyi.size} other ${if (shown.fyi.size == 1) "thing" else "things"} worth a look.")
        }
        if (parts.isNotEmpty()) speaker.speak(parts.joinToString(" "))
    }

    private fun spokenGist(item: BriefItem): String = when (item.kind) {
        Kind.CALL -> "${item.title}: ${item.gist.lowercase()}."
        else -> "${item.title} on ${item.appLabel}: ${item.gist.trimEnd('.')}."
    }

    override fun onCleared() {
        stopListening()
        words?.cancel()
        super.onCleared()
    }

    companion object {
        private const val TAG = "BriefViewModel"

        /** How long a reply waits, with Undo, before it goes. */
        const val UNDO_MS = 3_000L
        private const val SENT_SHOWN_MS = 1_600L
        private const val ICON_PX = 96

        fun factory(container: AppContainer, app: Application, newOnly: Boolean): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                BriefViewModel(
                    app = app,
                    awayBrief = container.awayBrief,
                    recorder = container.audioRecorder,
                    transcriber = container.transcriber,
                    attachments = container.attachmentStore,
                    speaker = container.speaker,
                    newOnly = newOnly,
                )
            }
        }
    }
}
