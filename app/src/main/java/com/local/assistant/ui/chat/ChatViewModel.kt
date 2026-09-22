package com.local.assistant.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.local.assistant.AppContainer
import com.local.assistant.data.db.ChatEntity
import com.local.assistant.data.db.MessageEntity
import com.local.assistant.data.db.Role
import com.local.assistant.data.repo.ChatRepository
import com.local.assistant.llm.LlmService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(
    private val repository: ChatRepository,
    private val llm: LlmService,
) : ViewModel() {

    val chats: StateFlow<List<ChatEntity>> = repository.observeChats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Null means "a new, not-yet-created chat". The row is only written once the first message
     * is sent, so tapping "New chat" repeatedly does not litter the sidebar.
     */
    private val _activeChatId = MutableStateFlow<Long?>(null)
    val activeChatId: StateFlow<Long?> = _activeChatId.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<MessageEntity>> = _activeChatId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repository.observeMessages(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The reply being streamed right now, or null when nothing is generating. */
    private val _streamingText = MutableStateFlow<String?>(null)
    val streamingText: StateFlow<String?> = _streamingText.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val engineState: StateFlow<LlmService.State> = llm.state

    /** Real context consumption reported by the runtime, for the indicator above the composer. */
    val contextUsage: StateFlow<LlmService.ContextUsage?> = llm.contextUsage

    private var generationJob: Job? = null
    private var stopRequested = false

    init {
        llm.warmUp()
    }

    fun startNewChat() {
        if (_isGenerating.value) return
        _activeChatId.value = null
    }

    fun selectChat(chatId: Long) {
        if (_isGenerating.value || _activeChatId.value == chatId) return
        _activeChatId.value = chatId
    }

    fun deleteChat(chatId: Long) {
        viewModelScope.launch {
            repository.deleteChat(chatId)
            llm.forget(chatId)
            if (_activeChatId.value == chatId) _activeChatId.value = null
        }
    }

    fun dismissError() {
        _error.value = null
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _isGenerating.value) return

        stopRequested = false
        _isGenerating.value = true
        _streamingText.value = ""

        generationJob = viewModelScope.launch {
            val chatId = _activeChatId.value
                ?: repository.createChat().also { _activeChatId.value = it }

            // Snapshot the history before the new turn is stored: the engine needs the prior
            // turns as context and the prompt separately.
            val history = repository.messagesFor(chatId)
            repository.addMessage(chatId, Role.USER, prompt)
            repository.titleFromFirstMessage(chatId, prompt)

            val reply = StringBuilder()
            var failure: String? = null
            try {
                llm.generate(chatId, history, prompt).collect { chunk ->
                    reply.append(chunk)
                    _streamingText.value = reply.toString()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e.message ?: "Generation failed"
            } finally {
                // Runs even when stop() cancelled us, so a partial reply is never lost.
                withContext(NonCancellable) {
                    val incomplete = stopRequested || failure != null
                    if (reply.isNotEmpty()) {
                        repository.addMessage(chatId, Role.ASSISTANT, reply.toString(), incomplete)
                    }
                    _error.value = failure
                    _streamingText.value = null
                    _isGenerating.value = false
                }
            }
        }
    }

    fun stop() {
        if (!_isGenerating.value) return
        stopRequested = true
        // Ask the runtime to stop decoding, then unwind the collector.
        llm.stop()
        generationJob?.cancel()
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { ChatViewModel(container.chatRepository, container.llmService) }
        }
    }
}
