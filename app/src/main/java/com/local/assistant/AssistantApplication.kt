package com.local.assistant

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import com.local.assistant.data.db.AppDatabase
import com.local.assistant.data.prefs.SettingsStore
import com.local.assistant.data.repo.ChatRepository
import com.local.assistant.llm.LiteRtLmBackend
import com.local.assistant.llm.LlmBackend
import com.local.assistant.llm.LlmService
import com.local.assistant.llm.ToolCallRepair
import com.local.assistant.assist.KeepReadyService
import com.local.assistant.chat.ChatSender
import com.local.assistant.media.AttachmentStore
import com.local.assistant.media.AudioRecorder
import com.local.assistant.voice.AudioFocus
import com.local.assistant.voice.Speaker
import com.local.assistant.data.prefs.SecretStore
import com.local.assistant.device.AndroidContacts
import com.local.assistant.device.AndroidPhone
import com.local.assistant.device.PermissionBroker
import com.local.assistant.memory.MemoryControls
import com.local.assistant.memory.notes.ImageDescriber
import com.local.assistant.memory.notes.SavedImages
import com.local.assistant.memory.db.ArchiveRepository
import com.local.assistant.memory.db.MemoryRepository
import com.local.assistant.memory.db.NoteEntity
import com.local.assistant.memory.db.SessionTracker
import com.local.assistant.memory.embed.EmbedderCatalog
import com.local.assistant.memory.embed.InstalledEmbedder
import com.local.assistant.memory.embed.LiteRtEmbedder
import com.local.assistant.memory.prompt.Snippet
import com.local.assistant.memory.retrieval.KotlinVectorIndex
import com.local.assistant.memory.retrieval.Retriever
import com.local.assistant.memory.retrieval.SqliteVec
import com.local.assistant.memory.retrieval.SqliteVecIndex
import com.local.assistant.memory.retrieval.VectorIndex
import com.local.assistant.memory.tools.ArchiveSearch
import com.local.assistant.memory.prompt.ConversationManager
import com.local.assistant.memory.prompt.MeasuredTokenEstimator
import com.local.assistant.memory.prompt.MemoryBudget
import com.local.assistant.memory.prompt.TurnRunner
import com.local.assistant.memory.tools.ChatToolLog
import com.local.assistant.memory.tools.DeviceTools
import com.local.assistant.memory.tools.ToolCatalog
import com.local.assistant.memory.tools.ToolExecutor
import com.local.assistant.memory.tools.ToolLoop
import com.local.assistant.memory.tools.WebTools
import com.local.assistant.memory.extract.ExtractionRouter
import com.local.assistant.memory.extract.Extractor
import com.local.assistant.memory.summary.SessionSummaries
import com.local.assistant.memory.summary.Summarizer
import com.local.assistant.memory.work.AppForeground
import com.local.assistant.memory.work.BackgroundJobs
import com.local.assistant.memory.work.Consolidation
import com.local.assistant.memory.work.EmbeddingQueue
import com.local.assistant.memory.work.ModelScheduler
import com.local.assistant.model.ModelCatalog
import com.local.assistant.model.ModelManager
import com.local.assistant.model.TransferKind
import com.local.assistant.reminders.AlarmReminderScheduler
import com.local.assistant.reminders.ClockAlarms
import com.local.assistant.reminders.ReminderNotifications
import com.local.assistant.ui.setup.MemorySearchControls
import com.local.assistant.web.TavilySearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.local.assistant.memory.work.PrecisionUpgrade
import java.io.File
import java.time.ZonedDateTime

/**
 * Manual dependency container. The app has few enough moving parts that a DI framework would
 * cost more than it saves; swap this out if that stops being true.
 */
class AppContainer(context: Context) {

    /** Outlives any screen, so downloads and model loading survive navigation. */
    val appScope = CoroutineScope(SupervisorJob())

    val settings = SettingsStore(context)

    private val appContext: Context = context.applicationContext

    /** Coming to the foreground warms the engines, so they are usually ready by the first send. */
    val appForeground: AppForeground = AppForeground(
        onForeground = {
            backgroundJobs.onForeground()
            if (settings.modelPath != null) llmService.warmUp()
            embeddingQueue.kick()
            // Started while visible, as Android requires; from then on it keeps the app alive.
            if (settings.keepAssistantReady && settings.modelPath != null) KeepReadyService.start(appContext)
        },
        onBackground = {
            backgroundJobs.onBackground()
            // Away for a while: have the next new chat's conversation ready, so the power button
            // is answered at once. Not straight away: a quick trip out and back finds its chat
            // still open.
            if (settings.keepAssistantReady) prepareForNextAsk(AWAY_BEFORE_PREPARING_MS)
        },
    )

    /** With the assistant kept ready and the app out of sight: a conversation for the next new chat. */
    private fun prepareForNextAsk(delayMs: Long) {
        // Behind any other background work: the nightly job's batches each close the conversation.
        conversations.prepareFresh(delayMs, ModelScheduler.Priority.IDLE_WARM_UP) {
            settings.keepAssistantReady && !appForeground.isForeground
        }
    }

    val backgroundJobs = BackgroundJobs(context)

    /** Whether sqlite-vec loads here; if not, the archive's vectors are searched in Kotlin. */
    val sqliteVecVersion: String? = SqliteVec.probe()

    private val database = AppDatabase.build(context, withVectors = sqliteVecVersion != null)

    /** Learns this model's real Latin rate from the runtime's counts; shared by every estimate. */
    val tokenEstimator = MeasuredTokenEstimator(settings.tokenRates) { settings.modelPath }

    val memoryRepository = MemoryRepository(database)

    val chatRepository = ChatRepository(
        dao = database.chatDao(),
        sessions = SessionTracker(database.sessionDao(), appForeground),
        estimator = tokenEstimator,
        onChatsDeleted = { archive.onChatsDeleted() },
        memoryPaused = { settings.memoryPaused },
    )

    val modelManager = ModelManager(context, ModelCatalog.FILE, settings::modelPath, appScope)

    /** The embedding model: downloaded, or imported from storage. */
    val embedderManager = ModelManager(
        context = context,
        model = EmbedderCatalog.FILE,
        storedPath = settings::embedderPath,
        scope = appScope,
        onInstalled = { kind, name ->
            settings.embedderKey = when (kind) {
                TransferKind.DOWNLOAD -> EmbedderCatalog.DEFAULT.key
                TransferKind.IMPORT -> EmbedderCatalog.forImport(name ?: EmbedderCatalog.FILE.fileName).key
            }
            embeddingQueue.kick()
        },
    )

    val embedder = LiteRtEmbedder(
        installed = {
            settings.embedderPath?.let { path ->
                InstalledEmbedder(File(path), EmbedderCatalog.byKey(settings.embedderKey) ?: EmbedderCatalog.DEFAULT)
            }
        },
        cacheDir = context.cacheDir,
    )

    private val vectorIndex: VectorIndex =
        if (sqliteVecVersion != null) {
            SqliteVecIndex(database)
        } else {
            KotlinVectorIndex {
                embedder.modelId?.let { id -> database.chunkDao().vectors(id).map { it.id to it.embedding } }.orEmpty()
            }
        }

    /** Every exchange, keyword-indexed and embedded, for recall across chats. */
    val archive: ArchiveRepository = ArchiveRepository(database, vectorIndex)

    /** Images the user asked to have remembered, kept outside their chats. */
    val savedImages: SavedImages = SavedImages(context.filesDir, database.noteDao(), chatRepository)

    private val retriever = Retriever(
        facts = memoryRepository,
        archive = archive,
        embedder = embedder,
        notes = savedImages,
        // Similarities in debug builds, to calibrate the threshold on real pairs.
        logScores = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
    )

    val llmService = LlmService(context, settings, appScope)

    val llmBackend: LlmBackend = LiteRtLmBackend(llmService, settings)

    private val summarizer = Summarizer(llmBackend, tokenEstimator)

    val modelScheduler = ModelScheduler()

    /** Secrets the user pastes in, encrypted with a Keystore key. */
    val secrets = SecretStore(context)

    /** Tavily, with the user's own key; off (and undeclared to the model) until there is one. */
    val webSearch = TavilySearch(key = { secrets.get(SecretStore.TAVILY_KEY) })

    val conversations = ConversationManager(
        backend = llmBackend,
        chats = chatRepository,
        memory = memoryRepository,
        settings = settings,
        scheduler = modelScheduler,
        scope = appScope,
        estimator = tokenEstimator,
        tools = { ToolCatalog.declarations(web = webSearch.enabled) },
        webSearch = { webSearch.enabled },
        retriever = retriever,
        summarizer = summarizer,
        // A background summary or extraction closed it: make it ready again.
        onModelFreed = { if (settings.keepAssistantReady && !appForeground.isForeground) prepareForNextAsk(AFTER_MODEL_WORK_MS) },
    )

    val embeddingQueue: EmbeddingQueue = EmbeddingQueue(
        archive = archive,
        embedder = embedder,
        scheduler = modelScheduler,
        scope = appScope,
        inForeground = { appForeground.isForeground },
        unloadWhenDrained = totalMemoryBytes(context) <= SMALL_DEVICE_BYTES,
        notes = database.noteDao(),
    )

    val reminderScheduler = AlarmReminderScheduler(context)

    /** Alarms in the phone's own clock app, for "wake me up at 6". */
    val clockAlarms = ClockAlarms(context, inForeground = { appForeground.isForeground })

    /** Runtime permissions asked for from outside the UI, like contacts on the first "call amma". */
    val permissions = PermissionBroker(context)

    /** Calls, messages, timers, apps, phone settings and arithmetic. */
    private val deviceTools = DeviceTools(
        phone = AndroidPhone(context, inForeground = { appForeground.isForeground }),
        contacts = AndroidContacts(context, permissions),
        people = memoryRepository,
        alarms = clockAlarms,
        now = ZonedDateTime::now,
    )

    private val imageDescriber = ImageDescriber(llmBackend, modelScheduler, conversations)

    /** Puts right, once, what the model wrote before its text decoder ran in fp32. */
    internal val precisionUpgrade = PrecisionUpgrade(
        database = database,
        archive = archive,
        images = savedImages,
        // At idle, like the other background model work: the user's messages come first.
        reread = { path -> modelScheduler.runBackground(ModelScheduler.Priority.COMPACTION) { imageDescriber.read(path) } },
        onChanged = {
            conversations.prefixMayHaveChanged()
            embeddingQueue.kick()
        },
    )

    init {
        appScope.launch {
            llmService.state.first { it is LlmService.State.Ready }
            if (llmService.exactDigits) {
                try {
                    precisionUpgrade.run()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Nothing is marked done, so the next start tries again.
                    Log.w("AppContainer", "Putting right the fp16 era failed", e)
                }
            }
        }
    }

    /** The latest [rereadSavedImage], for evals to wait on. */
    @Volatile
    internal var imageReread: Job? = null

    /**
     * For a model whose text decoder is still fp16 ([com.local.assistant.llm.ModelPrecision]
     * couldn't set it): a "remember this" in a chat saves what the model wrote in its tool call,
     * past token 2,048, where fp16 positions garble digits — a PIN 4417 was saved as "44417",
     * 1800-209-4455 as "18000-2094555". So once the turn is over, the image is read again in a
     * conversation of its own, short enough to copy every digit (as the Images tab's "+" does),
     * and that replaces the details. It waits behind the turn and goes ahead of the next message,
     * which then finds the live conversation closed and rebuilds it.
     */
    private fun rereadSavedImage(noteId: Long, said: String) {
        // With the text decoder in fp32 the model's own details are exact: nothing to redo.
        if (llmService.exactDigits) return
        imageReread = appScope.launch {
            try {
                // The model's slot first: it frees once the turn is over, and by then the note —
                // saved inside the tool's transaction, still open as this is called — is written.
                val description = modelScheduler.runUser {
                    database.noteDao().byId(noteId)?.imagePath?.let { imageDescriber.read(it, said) }
                } ?: return@launch
                if (savedImages.rewriteDetails(noteId, description.details) != null) embeddingQueue.kick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("AppContainer", "Could not read saved image $noteId again", e)
            }
        }
    }

    val toolExecutor = ToolExecutor(
        store = memoryRepository,
        reminders = reminderScheduler,
        alarms = clockAlarms,
        log = ChatToolLog(chatRepository),
        now = ZonedDateTime::now,
        archive = ArchiveSearch { query, limit ->
            val images = retriever.searchImages(query, limit).map {
                Snippet("Saved image “${it.title}”: ${it.details} (the user can ask about it and you will see it again)", it.createdAt)
            }
            (images + retriever.search(query, limit).map { Snippet(it.chunk.text, it.chunk.createdAt) }).take(limit)
        },
        memoryPaused = { settings.memoryPaused },
        images = savedImages,
        onImageSaved = ::rereadSavedImage,
        device = deviceTools,
        web = WebTools(webSearch),
    )

    private val toolLoop = ToolLoop(
        executor = toolExecutor,
        maxRounds = MemoryBudget.SIXTEEN_K.maxToolRounds,
        isOverflow = llmBackend::isContextOverflow,
        isUnreadable = llmBackend::isUnreadableToolCall,
        repair = ToolCallRepair(ToolCatalog.declarations(web = true))::repair,
        onNumberFixed = { wrote, source -> Log.i("ReplyGrounding", "Reply wrote $wrote; the source has $source") },
        groundNumbers = { !llmService.exactDigits },
    )

    val turnRunner = TurnRunner(conversations, modelScheduler, toolLoop, onExchangeStored = { userMessageId ->
        // A saved image from this turn needs a vector too, so kick either way.
        archive.recordExchange(userMessageId)
        embeddingQueue.kick()
    })

    val attachmentStore = AttachmentStore(context)

    val sessionSummaries = SessionSummaries(database.sessionDao(), summarizer, llmBackend, conversations, modelScheduler)

    private val extractor = Extractor(
        maintenance = database.maintenanceDao(),
        state = database.appStateDao(),
        backend = llmBackend,
        router = ExtractionRouter(memoryRepository, reminderScheduler),
        conversations = conversations,
        scheduler = modelScheduler,
        estimator = tokenEstimator,
    )

    /** What the memory screen can see and change. */
    val memoryControls = MemoryControls(
        database = database,
        memory = memoryRepository,
        archive = archive,
        chats = chatRepository,
        reminders = reminderScheduler,
        onChanged = { conversations.prefixMayHaveChanged() },
        images = savedImages,
    )

    val consolidation = Consolidation(
        embeddings = embeddingQueue,
        sessions = sessionSummaries,
        extractor = extractor,
        memory = memoryRepository,
        database = database,
        retentionDays = { settings.historyRetentionDays },
        applyRetention = { days ->
            memoryControls.applyRetention(days).also {
                if (it > 0) attachmentStore.pruneExcept(chatRepository.attachmentPaths())
            }
        },
        releaseModel = {
            if (!appForeground.isForeground && !settings.keepAssistantReady) {
                llmService.unload()
                embedder.unload()
            }
        },
        pruneSavedImages = savedImages::prune,
        finishPastReminders = { memoryControls.finishPastReminders() },
    )

    /**
     * The Images tab's "+": the picked image goes through the model like "remember this" in a
     * chat, and is kept with the title and details it wrote. Null when the model is unavailable.
     */
    suspend fun addSavedImage(uri: Uri): NoteEntity? {
        val picked = attachmentStore.importImage(uri)
        try {
            val description = imageDescriber.describe(picked.absolutePath) ?: return null
            return savedImages.saveUpload(description.title, description.details, picked.absolutePath)
                .also { embeddingQueue.kick() }
        } finally {
            attachmentStore.delete(picked.absolutePath)
        }
    }

    /** "Forget everything", including the nightly pass's place in the history. */
    suspend fun forgetEverything() = memoryControls.forgetEverything(skipExtractionTo = extractor::skipToEnd)

    val audioRecorder = AudioRecorder(appScope)

    /** Sends messages and streams replies for the chat and the assistant overlay alike. */
    val chatSender = ChatSender(chatRepository, llmService, turnRunner, appScope)

    /** The assistant's voice: the phone's text-to-speech, bound the first time it speaks. */
    val speaker = Speaker(context, settings)

    val audioFocus = AudioFocus(context)

    fun memorySearchControls() = MemorySearchControls(
        manager = embedderManager,
        remaining = embeddingQueue.remaining,
        installedName = { EmbedderCatalog.byKey(settings.embedderKey)?.displayName },
        downloadName = "Granite",
        downloadBytes = EmbedderCatalog.DEFAULT.sizeBytes,
        delete = {
            embedder.unload()
            embedderManager.deleteInstalled()
            settings.embedderKey = null
        },
    )

    init {
        backgroundJobs.scheduleNightly()
        // Exchanges from before the archive existed, or missed by a crash, then their vectors.
        appScope.launch {
            val finished = memoryControls.finishPastReminders()
            if (finished > 0) android.util.Log.i("AppContainer", "Finished $finished reminders whose time had passed")
            val dropped = archive.dropUtilityExchanges()
            if (dropped > 0) android.util.Log.i("AppContainer", "Took $dropped sums and phone commands out of the archive")
            val archived = archive.backfill(beforeId = archive.lastMessageId() + 1)
            if (archived > 0) android.util.Log.i("AppContainer", "Archived $archived earlier exchanges")
            embeddingQueue.kick()
        }
    }

    private companion object {
        /** How long the app is away before the next new chat's conversation is prepared. */
        const val AWAY_BEFORE_PREPARING_MS = 60_000L

        /**
         * After background model work closed the conversation, before preparing it again: long
         * enough for a chain of such jobs (the nightly extraction's batches) to finish first.
         */
        const val AFTER_MODEL_WORK_MS = 30_000L

        /** At or below this, the embedder's ~0.5 GB is given back whenever it is idle. */
        const val SMALL_DEVICE_BYTES = 8L * 1024 * 1024 * 1024

        fun totalMemoryBytes(context: Context): Long {
            val info = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
            return info.totalMem
        }
    }
}

class AssistantApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(container.appForeground)
        ReminderNotifications.createChannel(this)
    }

    /**
     * The engine holds gigabytes. While the app is in use it stays loaded; once the app is in
     * the background and the system asks for memory back, it goes — the next visit to the
     * foreground loads it again, and anything sent meanwhile waits for it. Unless the assistant
     * is kept ready (Settings): then it stays, since reloading costs ~9 s and re-reading the
     * prompt ~6 s more before the power button's first answer.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val background = level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        // Kept ready, the model stays: that is the point of the setting (and its foreground
        // service keeps the app out of the cached list this is mostly about).
        val keep = container.settings.keepAssistantReady
        if (background && !keep && !container.appForeground.isForeground && !container.llmService.isGenerating) {
            container.speaker.release()
            container.appScope.launch {
                container.llmService.unload()
                container.embedder.unload()
            }
        }
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as AssistantApplication).container
