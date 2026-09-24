package com.local.assistant

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import com.local.assistant.data.db.AppDatabase
import com.local.assistant.data.prefs.SettingsStore
import com.local.assistant.data.repo.ChatRepository
import com.local.assistant.llm.LiteRtLmBackend
import com.local.assistant.llm.LlmBackend
import com.local.assistant.llm.LlmService
import com.local.assistant.media.AttachmentStore
import com.local.assistant.media.AudioRecorder
import com.local.assistant.memory.db.MemoryRepository
import com.local.assistant.memory.db.SessionTracker
import com.local.assistant.memory.prompt.ConversationManager
import com.local.assistant.memory.prompt.MeasuredTokenEstimator
import com.local.assistant.memory.prompt.TurnRunner
import com.local.assistant.memory.work.AppForeground
import com.local.assistant.memory.work.ModelScheduler
import com.local.assistant.model.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual dependency container. The app has few enough moving parts that a DI framework would
 * cost more than it saves; swap this out if that stops being true.
 */
class AppContainer(context: Context) {

    /** Outlives any screen, so downloads and model loading survive navigation. */
    val appScope = CoroutineScope(SupervisorJob())

    val settings = SettingsStore(context)

    /** Coming to the foreground warms the engine, so it is usually ready by the first send. */
    val appForeground = AppForeground(onForeground = { if (settings.modelPath != null) llmService.warmUp() })

    private val database = AppDatabase.build(context)

    /** Learns this model's real Latin rate from the runtime's counts; shared by every estimate. */
    val tokenEstimator = MeasuredTokenEstimator(settings.tokenRates) { settings.modelPath }

    val memoryRepository = MemoryRepository(database)

    val chatRepository = ChatRepository(
        dao = database.chatDao(),
        sessions = SessionTracker(database.sessionDao(), appForeground),
        estimator = tokenEstimator,
    )

    val modelManager = ModelManager(context, settings, appScope)

    val llmService = LlmService(context, settings, appScope)

    val llmBackend: LlmBackend = LiteRtLmBackend(llmService, settings)

    val modelScheduler = ModelScheduler()

    val conversations = ConversationManager(
        backend = llmBackend,
        chats = chatRepository,
        memory = memoryRepository,
        settings = settings,
        scheduler = modelScheduler,
        scope = appScope,
        estimator = tokenEstimator,
    )

    val turnRunner = TurnRunner(conversations, modelScheduler, llmBackend)

    val attachmentStore = AttachmentStore(context)

    val audioRecorder = AudioRecorder(appScope)
}

class AssistantApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(container.appForeground)
    }

    /**
     * The engine holds gigabytes. While the app is in use it stays loaded; once the app is in
     * the background and the system asks for memory back, it goes — the next visit to the
     * foreground loads it again, and anything sent meanwhile waits for it.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val background = level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        if (background && !container.appForeground.isForeground && !container.llmService.isGenerating) {
            container.appScope.launch { container.llmService.unload() }
        }
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as AssistantApplication).container
