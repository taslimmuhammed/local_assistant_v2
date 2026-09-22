package com.local.assistant

import android.app.Application
import android.content.Context
import com.local.assistant.data.db.AppDatabase
import com.local.assistant.data.prefs.SettingsStore
import com.local.assistant.data.repo.ChatRepository
import com.local.assistant.llm.LlmService
import com.local.assistant.media.AttachmentStore
import com.local.assistant.media.AudioRecorder
import com.local.assistant.model.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency container. The app has few enough moving parts that a DI framework would
 * cost more than it saves; swap this out if that stops being true.
 */
class AppContainer(context: Context) {

    /** Outlives any screen, so downloads and model loading survive navigation. */
    val appScope = CoroutineScope(SupervisorJob())

    val settings = SettingsStore(context)

    private val database = AppDatabase.build(context)

    val chatRepository = ChatRepository(database.chatDao())

    val modelManager = ModelManager(context, settings, appScope)

    val llmService = LlmService(context, settings, appScope)

    val attachmentStore = AttachmentStore(context)

    val audioRecorder = AudioRecorder(appScope)
}

class AssistantApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as AssistantApplication).container
