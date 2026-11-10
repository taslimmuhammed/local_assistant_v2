package com.local.assistant

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import com.local.assistant.gallery.GalleryDatabase
import com.local.assistant.gallery.GalleryEmbedder
import com.local.assistant.gallery.GalleryIndex
import com.local.assistant.gallery.GalleryModel
import com.local.assistant.gallery.PhotoLibrary
import com.local.assistant.gallery.PhotoThumbnails
import com.local.assistant.gallery.PhotoReader
import com.local.assistant.gallery.PhotoRecall
import com.local.assistant.gallery.PhotoTools
import com.local.assistant.gallery.faces.FaceFinder
import com.local.assistant.gallery.faces.FaceIndex
import com.local.assistant.gallery.places.PlaceList
import com.local.assistant.data.db.AppDatabase
import com.local.assistant.data.prefs.SettingsStore
import com.local.assistant.data.repo.ChatRepository
import com.local.assistant.llm.LiteRtLmBackend
import com.local.assistant.llm.LlmBackend
import com.local.assistant.llm.LlmService
import com.local.assistant.llm.ToolCallRepair
import com.local.assistant.assist.AssistantRole
import com.local.assistant.assist.KeepReadyService
import com.local.assistant.chat.ChatSender
import com.local.assistant.media.AttachmentStore
import com.local.assistant.media.AudioRecorder
import com.local.assistant.voice.AudioFocus
import com.local.assistant.voice.Speaker
import com.local.assistant.voice.NaturalVoices
import com.local.assistant.voice.VoicePack
import com.local.assistant.voice.VoicePackInstaller
import com.local.assistant.data.prefs.SecretStore
import com.local.assistant.device.AndroidContacts
import com.local.assistant.device.AndroidPhone
import com.local.assistant.device.PermissionBroker
import com.local.assistant.memory.MemoryControls
import com.local.assistant.memory.notes.ImageDescriber
import com.local.assistant.memory.notes.SavedImages
import com.local.assistant.memory.notes.UserNotes
import com.local.assistant.memory.db.ArchiveRepository
import com.local.assistant.memory.db.MemoryRepository
import com.local.assistant.memory.db.NoteEntity
import com.local.assistant.memory.db.NoteKind
import com.local.assistant.memory.db.SessionTracker
import com.local.assistant.memory.embed.EmbedderCatalog
import com.local.assistant.memory.embed.Embedder
import com.local.assistant.memory.embed.MemoryEmbedder
import com.local.assistant.memory.embed.PhotoModelEmbedder
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
import com.local.assistant.memory.prompt.NoteTitle
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
import com.local.assistant.reminders.AlarmReminderScheduler
import com.local.assistant.reminders.ClockAlarms
import com.local.assistant.reminders.PlaceFences
import com.local.assistant.reminders.PlaceReminderReceiver
import com.local.assistant.reminders.ReminderNotifications
import com.local.assistant.location.NominatimSearch
import com.local.assistant.location.PlaceFinder
import com.local.assistant.location.SavedPlaces
import com.local.assistant.device.PhoneLocation
import com.local.assistant.ui.setup.MemorySearchControls
import com.local.assistant.web.TavilySearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import com.local.assistant.memory.work.PrecisionUpgrade
import com.local.assistant.privacy.LogText
import java.io.File
import java.time.ZonedDateTime

/**
 * Manual dependency container. The app has few enough moving parts that a DI framework would
 * cost more than it saves; swap this out if that stops being true.
 */
class AppContainer(context: Context) {

    /** Outlives any screen, so downloads and model loading survive navigation. */
    val appScope = CoroutineScope(SupervisorJob())

    /** Whether card and ID numbers, passwords and document photos are shown: not until the user authenticates. */
    val sensitiveLock = com.local.assistant.privacy.SensitiveLock(appScope)

    val settings = SettingsStore(context)

    private val appContext: Context = context.applicationContext

    /**
     * The model stays loaded with the app away ("Keep the assistant ready" in Settings) only
     * while the system runs the app as the phone's assistant: that is the only way the power
     * button reaches it, and the system's hold on [com.local.assistant.assist.AssistantInteractionService]
     * keeps the app up. Otherwise a model left loaded would only make the app the first one
     * Android kills.
     */
    val keepReady: Boolean get() = settings.keepAssistantReady && AssistantRole.serviceActive(appContext)

    /** Coming to the foreground warms the engines, so they are usually ready by the first send. */
    val appForeground: AppForeground = AppForeground(
        onForeground = {
            backgroundJobs.onForeground()
            speaker.setAway(false)
            if (settings.modelPath != null) llmService.warmUp()
            embeddingQueue.kick()
            // Started while visible, as Android requires, if the wake word or a place reminder needs it.
            KeepReadyService.update(appContext)
            // Fences dropped while location was off, or waiting for location all the time.
            appScope.launch { runCatching { PlaceReminderReceiver.watchAll(this@AppContainer) }.onFailure { Log.w("AppContainer", "Watching place reminders failed", it) } }
        },
        onBackground = {
            backgroundJobs.onBackground()
            sensitiveLock.lock()
            // Out of sight, the photos wait for the charger at night.
            appScope.launch { galleryIndex.pause() }
            // What only the app in use needs goes back, so the home screen has less to take back:
            // the natural voice once it falls quiet, memory search once its backlog is done.
            speaker.setAway(true)
            embeddingQueue.kick()
            if (keepReady) prepareWhileAway(screenOn = power.isInteractive)
        },
    )

    private val power = context.getSystemService(PowerManager::class.java)

    /** The screen went off or on: with the app away, it decides when the next new chat is made ready. */
    fun onScreen(on: Boolean) {
        if (keepReady && !appForeground.isForeground) prepareWhileAway(screenOn = on)
    }

    /**
     * Away for a while: have the next new chat's conversation ready, so the power button is
     * answered at once. Its prefill re-reads ~860 MB of the model and the phone frees ~1.5 GB for
     * it, which stutters the screen for a second or two (≈455 allocation stalls on the OnePlus,
     * 7 Nov 2026), so it waits for the screen to go off: a minute of it, so a quick look away
     * finds its chat still open. With the screen left on it goes after five minutes; until then
     * the panel makes the conversation itself as it opens, while the user talks.
     */
    private fun prepareWhileAway(screenOn: Boolean) {
        if (screenOn) prepareForNextAsk(AWAY_SCREEN_ON_MS) else prepareForNextAsk(AWAY_SCREEN_OFF_MS) { !power.isInteractive }
    }

    /** With the assistant kept ready and the app out of sight: a conversation for the next new chat. */
    private fun prepareForNextAsk(delayMs: Long, alsoWanted: () -> Boolean = { true }) {
        // Behind any other background work: the nightly job's batches each close the conversation.
        conversations.prepareFresh(delayMs, ModelScheduler.Priority.IDLE_WARM_UP) {
            keepReady && !appForeground.isForeground && alsoWanted()
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

    /** Photo search's model, EmbeddingGemma 2; its text side is memory search's too. */
    val galleryModelManager = ModelManager(context, GalleryModel.FILE, settings::galleryModelPath, appScope)

    /** One engine for photo search and memory search (see [GalleryEmbedder]). */
    val photoModel = GalleryEmbedder(modelFile = { galleryModelManager.installed.value?.file }, cacheDir = context.cacheDir)

    /** An embedding model the user imported from storage, used by memory search instead of the photo model. */
    val embedderManager = ModelManager(
        context = context,
        model = EmbedderCatalog.FILE,
        storedPath = settings::embedderPath,
        scope = appScope,
        onInstalled = { _, name ->
            settings.embedderKey = EmbedderCatalog.forImport(name ?: EmbedderCatalog.FILE.fileName).key
            appScope.launch {
                // The photo model's text side is memory search's no longer.
                embedder.unload()
                embeddingQueue.kick()
            }
        },
    )

    /** Memory search's model: the photo model's text side, or one the user imported. */
    val embedder: Embedder = MemoryEmbedder(
        imported = LiteRtEmbedder(
            installed = {
                settings.embedderPath?.let { path ->
                    // No key only from builds before keys were kept, when Granite was the download.
                    InstalledEmbedder(File(path), EmbedderCatalog.byKey(settings.embedderKey) ?: EmbedderCatalog.GRANITE)
                }
            },
            cacheDir = context.cacheDir,
        ),
        photo = PhotoModelEmbedder(photoModel),
        // Not the old EmbeddingGemma 300M, in the moment before dropOldEmbedder deletes it.
        useImported = { settings.embedderPath != null && settings.embedderKey != OLD_EMBEDDER_KEY },
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

    /** The user's notes and lists, and the parts of them recall searches. */
    val userNotes: UserNotes = UserNotes(database.userNoteDao(), transaction = { block -> memoryRepository.transaction(block) })

    private val retriever = Retriever(
        facts = memoryRepository,
        archive = archive,
        embedder = embedder,
        notes = savedImages,
        userNotes = userNotes,
        // Similarities in debug builds, to calibrate the threshold on real pairs.
        logScores = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
    )

    val llmService = LlmService(context, settings, appScope)

    val llmBackend: LlmBackend = LiteRtLmBackend(llmService, settings)

    private val summarizer = Summarizer(llmBackend, tokenEstimator)

    val modelScheduler = ModelScheduler()

    val photoLibrary = PhotoLibrary(context)

    val photoThumbnails = PhotoThumbnails(photoLibrary)

    private val galleryDatabase = GalleryDatabase.build(context)

    /** Towns, states and countries, offline, for "photos in Munnar". */
    val placeList = PlaceList(context.assets)

    /** The people in the photos; nothing is looked for until "Find people" is turned on. */
    val faceIndex = FaceIndex(
        database = galleryDatabase,
        library = photoLibrary,
        newFinder = { FaceFinder(context.assets) },
        enabled = { settings.findPeople },
        userName = { memoryRepository.coreFacts().firstOrNull { it.subject == "user" && it.attribute == "name" }?.value },
        groupedBy = { settings.faceGroupingVersion },
        setGroupedBy = { settings.faceGroupingVersion = it },
    )

    /** People named from contacts' photos, once faces are found (`gallery/faces/ContactNames`). */
    val contactNames = com.local.assistant.gallery.faces.ContactNames(
        database = galleryDatabase,
        faces = faceIndex,
        photos = com.local.assistant.gallery.faces.AndroidContactPhotos(context),
        enabled = { settings.findPeople },
    )

    val galleryIndex = GalleryIndex(
        dao = galleryDatabase.photoDao(),
        library = photoLibrary,
        embedder = photoModel,
        scheduler = modelScheduler,
        scope = appScope,
        faces = faceIndex,
        places = placeList,
        onDocuments = { photoThumbnails.documents.value = it },
    )

    init {
        // Which photos to keep hidden, from the index, before any is shown.
        appScope.launch { galleryIndex.refreshDocuments() }
    }

    /** find_photos is declared to the model only once its model is here and the photos may be read. */
    fun photoSearchReady(): Boolean =
        galleryModelManager.installed.value != null && photoLibrary.access() != PhotoLibrary.Access.NONE

    /** Phones this small give the photo model's memory back (what memory search isn't using) as soon as the Photos screen closes. */
    val isSmallDevice: Boolean = totalMemoryBytes(context) <= SMALL_DEVICE_BYTES

    /** Secrets the user pastes in, encrypted with a Keystore key. */
    val secrets = SecretStore(context)

    /** Tavily, with the user's own key; off (and undeclared to the model) until there is one. */
    val webSearch = TavilySearch(key = { secrets.get(SecretStore.TAVILY_KEY) })

    /** Health Connect, read only: steps, heart rate, sleep and workouts the phone's health apps keep. */
    val healthSource = com.local.assistant.health.HealthConnectSource(context)

    /** health_data: declared wherever there's a Health Connect, asking for access the first time each kind is asked about. */
    val healthTools = com.local.assistant.health.HealthTools(
        healthSource,
        requestAccess = { asked -> permissions.requestHealth(asked) },
        appName = { packageName ->
            runCatching {
                context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString()
            }.getOrDefault(packageName)
        },
        stepGoal = { settings.stepGoal },
    )

    val conversations = ConversationManager(
        backend = llmBackend,
        chats = chatRepository,
        memory = memoryRepository,
        settings = settings,
        scheduler = modelScheduler,
        scope = appScope,
        estimator = tokenEstimator,
        tools = { ToolCatalog.declarations(web = webSearch.enabled, photos = photoSearchReady(), health = healthTools.available()) },
        webSearch = { webSearch.enabled },
        photoSearch = ::photoSearchReady,
        healthData = healthTools::available,
        retriever = retriever,
        photos = PhotoRecall(galleryIndex, photoLibrary, ready = ::photoSearchReady, cacheDir = context.cacheDir),
        summarizer = summarizer,
        // A background summary or extraction closed it: make it ready again.
        onModelFreed = { if (keepReady && !appForeground.isForeground) prepareForNextAsk(AFTER_MODEL_WORK_MS) },
        noteIndex = { userNotes.all().map { NoteTitle(it.id, it.title, it.kind == NoteKind.LIST) } },
    )

    val embeddingQueue: EmbeddingQueue = EmbeddingQueue(
        archive = archive,
        embedder = embedder,
        scheduler = modelScheduler,
        scope = appScope,
        inForeground = { appForeground.isForeground },
        notes = database.noteDao(),
        userNotes = database.userNoteDao(),
    )

    /**
     * A note changed on the Notes screen: its new parts need vectors, and a new name or a new note
     * changes the prefix, rebuilt once the chat is idle.
     */
    fun notesChanged() {
        embeddingQueue.kick()
        conversations.prefixMayHaveChanged()
    }

    /** Geofences for place reminders: Play Services' where there are, else Android's own. */
    val placeFences = PlaceFences.forPhone(context)

    val reminderScheduler = AlarmReminderScheduler(context, placeFences)

    /** Alarms in the phone's own clock app, for "wake me up at 6". */
    val clockAlarms = ClockAlarms(context, inForeground = { appForeground.isForeground })

    /** Runtime permissions asked for from outside the UI, like contacts on the first "call amma". */
    val permissions = PermissionBroker(context)

    /**
     * Where place reminders ring: "here", home and office, and towns on the phone; shops and
     * addresses on OpenStreetMap, sent only their name and the user's rough area.
     */
    val placeFinder = PlaceFinder(
        search = NominatimSearch(),
        towns = { placeList.get() },
        location = PhoneLocation(context, permissions),
        own = SavedPlaces(
            memory = memoryRepository,
            get = { database.appStateDao().get(it) },
            put = { key, value -> database.appStateDao().put(com.local.assistant.memory.db.AppStateEntity(key, value)) },
        ),
    )

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

    /** A photo question answered from the found photo in a short conversation of its own. */
    private val photoReader = PhotoReader(llmBackend, conversations)

    val toolExecutor = ToolExecutor(
        store = memoryRepository,
        reminders = reminderScheduler,
        alarms = clockAlarms,
        log = ChatToolLog(chatRepository),
        now = ZonedDateTime::now,
        archive = ArchiveSearch { query, limit ->
            // Their own notes first, as they are now: an older conversation about a list is out of date once it changes.
            val notes = retriever.searchNotes(query, limit).map { note ->
                val kind = if (note.kind == NoteKind.LIST) "list" else "note"
                val content = if (note.kind == NoteKind.LIST) {
                    note.items.joinToString("; ") { if (it.done) "${it.text} (ticked)" else it.text }.ifEmpty { "empty" }
                } else {
                    note.body.ifBlank { "empty" }
                }
                Snippet("The user's $kind “${note.title}” ($kind ${note.id}), as it is now: $content", note.updatedAt)
            }
            val images = retriever.searchImages(query, limit).map {
                Snippet("Saved image “${it.title}”: ${it.details} (the user can ask about it and you will see it again)", it.createdAt)
            }
            (notes + images + retriever.search(query, limit).map { Snippet(it.chunk.text, it.chunk.createdAt) }).take(limit)
        },
        memoryPaused = { settings.memoryPaused },
        images = savedImages,
        onImageSaved = ::rereadSavedImage,
        device = deviceTools,
        web = WebTools(webSearch),
        photos = PhotoTools(
            galleryIndex,
            ready = ::photoSearchReady,
            faces = faceIndex,
            findPeople = { settings.findPeople },
            places = placeList,
            // Sharp enough to read a certificate's marks; one file, replaced by the next question.
            readable = { mediaId ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    photoLibrary.jpeg(mediaId, PHOTO_READ_PIXELS)?.let { bytes -> java.io.File(context.cacheDir, "photo-question.jpg").apply { writeBytes(bytes) }.absolutePath }
                }
            },
            mayRead = { context.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked != true },
            reader = { path, question -> if ((llmService.capabilities.value?.visionTokensPerImage ?: 0) > 0) photoReader.answer(path, question) else null },
        ),
        health = healthTools,
        places = placeFinder,
        notes = userNotes,
    )

    private val toolLoop = ToolLoop(
        executor = toolExecutor,
        maxRounds = MemoryBudget.SIXTEEN_K.maxToolRounds,
        isOverflow = llmBackend::isContextOverflow,
        isUnreadable = llmBackend::isUnreadableToolCall,
        repair = ToolCallRepair(ToolCatalog.declarations(web = true, photos = true, health = true))::repair,
        onNumberFixed = { wrote, source -> Log.i("ReplyGrounding", "Reply wrote ${LogText.of(wrote)}; the source has ${LogText.of(source)}") },
        groundNumbers = { !llmService.exactDigits },
        seesImages = { (llmService.capabilities.value?.visionTokensPerImage ?: 0) > 0 },
        onUnbackedClaim = { said -> Log.i("ActionClaims", "Held back, no tool ran: ${LogText.of(said.take(200))}") },
        photosAvailable = ::photoSearchReady,
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
        townAt = { placeList.get().nearest(it.lat, it.lon)?.town },
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
            if (!appForeground.isForeground && !keepReady) {
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

    /**
     * The chat model was just installed (the first download, or an import): load it and read the
     * first chat's prompt now, while the setup screen says so, instead of on the first message,
     * which would otherwise wait ~15 s (a 9 s load, then 6 s reading the prompt; AssistLatencyEvalTest).
     * Later launches load in the background, as before. Gives up after [FIRST_CHAT_READY_MS]
     * rather than keep the user on the setup screen; the chat then carries on loading.
     */
    suspend fun prepareFirstChat() {
        // The wake word listens from the first model on: the service only starts in the
        // foreground, which the app was in, with no model yet, when this launch began.
        KeepReadyService.update(appContext)
        withTimeoutOrNull(FIRST_CHAT_READY_MS) {
            // Loads the engine, or tries again if an earlier attempt failed; false if it can't.
            if (llmBackend.ensureReady()) conversations.prepareFresh().join()
        }
    }

    /** "Forget everything", including the nightly pass's place in the history. */
    suspend fun forgetEverything() = memoryControls.forgetEverything(skipExtractionTo = extractor::skipToEnd)

    val audioRecorder = AudioRecorder(appScope)

    /** Voice messages written down as they're recorded, by the phone's own recogniser. */
    val transcriber = com.local.assistant.voice.Transcriber(context, appScope)

    /** Sends messages and streams replies for the chat and the assistant overlay alike. */
    val chatSender = ChatSender(chatRepository, llmService, turnRunner, appScope)

    /** The natural voices' downloads (Settings › Voice). Once one arrives, the assistant speaks with it. */
    val voicePacks: List<VoicePackInstaller> = VoicePack.ALL.map { pack ->
        VoicePackInstaller(context, pack, appScope, onInstalled = { settings.naturalVoice = NaturalVoices.firstOf(pack).id })
    }

    fun voicePack(pack: VoicePack): VoicePackInstaller = voicePacks.first { it.pack == pack }

    /** The assistant's voice: the chosen natural voice when it's here, else the phone's text-to-speech. */
    val speaker = Speaker(context, settings, installed = { pack -> voicePack(pack).installed.value })

    val audioFocus = AudioFocus(context)

    /** The chat screen's own hold on other audio, so it and the overlay never release each other's. */
    val chatAudioFocus = AudioFocus(context)

    /** "While you were away": notifications kept for the brief, sorted by the chat model. */
    val awayBrief = com.local.assistant.away.AwayBrief(
        context = context,
        settings = settings,
        dao = com.local.assistant.away.AwayDatabase.build(context).dao(),
        replies = com.local.assistant.away.ReplyActions(),
        scheduler = modelScheduler,
        model = conversations,
        backend = llmBackend,
        modelLoaded = { llmService.state.value is LlmService.State.Ready },
        scope = appScope,
    )

    /**
     * Memory search comes with the first chat model: once one is installed (downloaded or
     * imported) where there was none, the photo model, whose text side memory search uses,
     * downloads right after it, so nobody has to go back for it. Not when it is already here or
     * on its way, not when the user has loaded their own embedder, and not for a chat model that
     * was installed before.
     */
    private fun fetchPhotoModelAfterFirstModel() {
        appScope.launch {
            var hadModel = modelManager.installed.value != null
            modelManager.installed.collect { model ->
                val arrived = model != null && !hadModel
                hadModel = model != null
                val none = galleryModelManager.installed.value == null && galleryModelManager.transfer.value == null &&
                    embedderManager.installed.value == null
                if (arrived && none) {
                    Log.i("AppContainer", "Chat model installed; fetching ${GalleryModel.DISPLAY_NAME}")
                    galleryModelManager.download()
                }
            }
        }
    }

    /**
     * EmbeddingGemma 300M was memory search's own model until the photo model took it over
     * (2026-11-07). Its file and the runtime's cache of it are deleted, about 440 MB; its
     * vectors are replaced as the archive is embedded again.
     */
    private suspend fun dropOldEmbedder() {
        if (settings.embedderKey != OLD_EMBEDDER_KEY) return
        embedderManager.deleteInstalled()
        settings.embedderKey = null
        dropEmbedderCaches()
        Log.i("AppContainer", "Deleted EmbeddingGemma 300M; memory search uses the photo model")
    }

    /** The runtime's caches of the imported embedder, named after its file. */
    private fun dropEmbedderCaches() {
        appContext.cacheDir.listFiles { file -> file.name.startsWith(EmbedderCatalog.FILE.fileName) }?.forEach { it.delete() }
    }

    fun memorySearchControls() = MemorySearchControls(
        photoModel = galleryModelManager,
        photoModelName = "EmbeddingGemma 2",
        photoModelBytes = GalleryModel.SIZE_BYTES,
        imported = embedderManager,
        importedName = { EmbedderCatalog.byKey(settings.embedderKey)?.displayName },
        remaining = embeddingQueue.remaining,
        deleteImported = {
            embedder.unload()
            embedderManager.deleteInstalled()
            settings.embedderKey = null
            dropEmbedderCaches()
            embeddingQueue.kick()
        },
    )

    init {
        fetchPhotoModelAfterFirstModel()
        appScope.launch {
            dropOldEmbedder()
            // Memory search starts once the photo model is here (downloaded later, or already).
            galleryModelManager.installed.collect { if (it != null) embeddingQueue.kick() }
        }
        backgroundJobs.scheduleNightly()
        // Exchanges from before the archive existed, or missed by a crash, then their vectors.
        appScope.launch {
            val finished = memoryControls.finishPastReminders()
            if (finished > 0) android.util.Log.i("AppContainer", "Finished $finished reminders whose time had passed")
            val dropped = archive.dropUtilityExchanges()
            if (dropped > 0) android.util.Log.i("AppContainer", "Took $dropped sums, phone commands and photo searches out of the archive")
            val archived = archive.backfill(beforeId = archive.lastMessageId() + 1)
            if (archived > 0) android.util.Log.i("AppContainer", "Archived $archived earlier exchanges")
            embeddingQueue.kick()
        }
    }

    private companion object {
        /** The longest side of a found photo the model reads a question from. */
        const val PHOTO_READ_PIXELS = 1024

        /** The longest the setup screen waits for the first chat to be ready. */
        const val FIRST_CHAT_READY_MS = 90_000L

        /** How long the screen is off, with the app away, before the next new chat's conversation is prepared. */
        const val AWAY_SCREEN_OFF_MS = 60_000L

        /** How long the app is away with the screen on before that happens anyway. */
        const val AWAY_SCREEN_ON_MS = 5 * 60_000L

        /**
         * After background model work closed the conversation, before preparing it again: long
         * enough for a chain of such jobs (the nightly extraction's batches) to finish first.
         */
        const val AFTER_MODEL_WORK_MS = 30_000L

        /** At or below this, the photo model's memory goes back as soon as the Photos screen closes. */
        const val SMALL_DEVICE_BYTES = 8L * 1024 * 1024 * 1024

        /** EmbeddingGemma 300M's key, from before the photo model did memory search too. */
        const val OLD_EMBEDDER_KEY = "embeddinggemma-300m-wi8"

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
        LogText.showsUserText = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        container = AppContainer(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(container.appForeground)
        // The screen going off hides shown details too, even with the app (or the panel) in front.
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                    val on = intent.action == android.content.Intent.ACTION_SCREEN_ON
                    if (!on) container.sensitiveLock.lock()
                    container.onScreen(on)
                }
            },
            android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF).apply { addAction(android.content.Intent.ACTION_SCREEN_ON) },
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Location back on: Play Services dropped every geofence when it went off, and they were
        // set again only when the app next came to the front. The user turns location off and on
        // during the day (13:20 off, 16:46 on, 17:07 off on 9 Nov 2026), so a reminder could go
        // unwatched for hours. Android tells this only to a running app, which the assistant
        // service, or KeepReadyService while a place reminder waits, keeps it.
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                    if (context.getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled != true) return
                    container.appScope.launch {
                        runCatching { PlaceReminderReceiver.watchAll(container) }
                            .onSuccess { Log.i("AssistantApplication", "Location back on: watching $it place reminders again") }
                            .onFailure { Log.w("AssistantApplication", "Watching place reminders again failed", it) }
                    }
                }
            },
            android.content.IntentFilter(android.location.LocationManager.MODE_CHANGED_ACTION),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
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
        // Thumbnails are quick to read again; the model is not.
        if (background) container.photoThumbnails.clear()
        // Kept ready, the model stays: that is the point of the setting (and the system's hold on
        // the assistant service keeps the app out of the cached list this is mostly about).
        val keep = container.keepReady
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
