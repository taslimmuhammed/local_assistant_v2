package com.local.assistant

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.local.assistant.assist.AssistActivity
import com.local.assistant.ui.chat.ChatScreen
import com.local.assistant.ui.chat.ToolPermissionRequests
import com.local.assistant.ui.gallery.GalleryScreen
import com.local.assistant.ui.gallery.LocalPhotoAccess
import com.local.assistant.ui.gallery.PhotoAccess
import com.local.assistant.ui.gallery.GalleryViewModel
import com.local.assistant.ui.memory.MemoryScreen
import com.local.assistant.ui.memory.RemindersScreen
import com.local.assistant.ui.notes.NotesScreen
import com.local.assistant.ui.notes.NotesViewModel
import com.local.assistant.ui.memory.MemoryViewModel
import com.local.assistant.ui.profile.ProfileScreen
import com.local.assistant.ui.profile.ProfileViewModel
import androidx.activity.compose.BackHandler
import com.local.assistant.ui.chat.ChatViewModel
import com.local.assistant.ui.settings.SettingsScreen
import com.local.assistant.ui.settings.SettingsSection
import com.local.assistant.ui.setup.ModelScreen
import com.local.assistant.ui.setup.PreparingScreen
import com.local.assistant.ui.web.WebSearchScreen
import com.local.assistant.ui.web.WebSearchViewModel
import com.local.assistant.ui.theme.LocalAssistantTheme
import com.local.assistant.ui.theme.installGlassBackdrop

/** A [FragmentActivity] (a ComponentActivity underneath) for the fingerprint prompt. */
class MainActivity : FragmentActivity() {

    /** A chat to show, from the assistant overlay's "Open in app". */
    private val chatToOpen = mutableStateOf<Long?>(null)

    /** Settings asked for from outside: the lock-screen notice. */
    private val settingsToOpen = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The launch screen doesn't cut to the app: the mark swells a little as it fades away.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            splashScreen.setOnExitAnimationListener { splash ->
                splash.iconView?.animate()?.scaleX(1.18f)?.scaleY(1.18f)?.setDuration(SPLASH_EXIT_MS)?.start()
                splash.animate().alpha(0f).setDuration(SPLASH_EXIT_MS).withEndAction { splash.remove() }.start()
            }
        }
        if (savedInstanceState == null) {
            chatToOpen.value = chatIdIn(intent)
            settingsToOpen.value = settingsIn(intent)
        }
        val container = appContainer
        val photos = PhotoAccess(container.photoThumbnails)
        val sensitive = com.local.assistant.ui.privacy.SensitiveAccess(container.sensitiveLock, this)
        setContent {
            LocalAssistantTheme {
                CompositionLocalProvider(LocalPhotoAccess provides photos, com.local.assistant.ui.privacy.LocalSensitive provides sensitive) {
                    AppRoot(
                        container,
                        chatToOpen = chatToOpen.value,
                        onChatOpened = { chatToOpen.value = null },
                        settingsToOpen = settingsToOpen.value,
                        onSettingsOpened = { settingsToOpen.value = false },
                    )
                }
            }
        }
        installGlassBackdrop(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        chatIdIn(intent)?.let { chatToOpen.value = it }
        if (settingsIn(intent)) settingsToOpen.value = true
    }

    private fun settingsIn(intent: Intent?): Boolean = intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true

    private fun chatIdIn(intent: Intent?): Long? =
        intent?.getLongExtra(EXTRA_CHAT_ID, -1L)?.takeIf { it > 0 }

    companion object {
        const val EXTRA_CHAT_ID = "com.local.assistant.extra.CHAT_ID"
        const val EXTRA_OPEN_SETTINGS = "com.local.assistant.extra.OPEN_SETTINGS"
    }
}

@Composable
private fun AppRoot(
    container: AppContainer,
    chatToOpen: Long?,
    onChatOpened: () -> Unit,
    settingsToOpen: Boolean,
    onSettingsOpened: () -> Unit,
) {
    val installed by container.modelManager.installed.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    // The settings page open: kept here, so returning from the model, profile or web search
    // screen comes back to it.
    var settingsSection by rememberSaveable { mutableStateOf<SettingsSection?>(null) }
    var showModelScreen by remember { mutableStateOf(false) }
    var showMemoryScreen by remember { mutableStateOf(false) }
    var showRemindersScreen by remember { mutableStateOf(false) }
    var showNotesScreen by remember { mutableStateOf(false) }
    var notesFromChat by remember { mutableStateOf(false) }
    var showProfileScreen by remember { mutableStateOf(false) }
    var showWebSearchScreen by remember { mutableStateOf(false) }
    var showPhotosScreen by remember { mutableStateOf(false) }
    var profileAsked by remember { mutableStateOf(container.settings.profileAsked) }
    var webSearchAsked by remember { mutableStateOf(container.settings.webSearchAsked || container.webSearch.enabled) }

    // A model installed in this run (the first download, or an import) is loaded, and the first
    // chat's prompt read, before the chat opens: otherwise the first message waits ~15 s. A model
    // that was already there at launch loads in the background, as before.
    var hadModel by rememberSaveable { mutableStateOf(installed != null) }
    var preparing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(installed != null) {
        if (installed != null && !hadModel) {
            preparing = true
            try {
                container.prepareFirstChat()
            } finally {
                preparing = false
            }
        }
        hadModel = installed != null
    }

    // The overlay's "Open in app": to the chat, from whichever screen the app was left on.
    LaunchedEffect(chatToOpen) {
        if (chatToOpen == null) return@LaunchedEffect
        showSettings = false
        showModelScreen = false
        showMemoryScreen = false
        showRemindersScreen = false
        showNotesScreen = false
        showProfileScreen = false
        showWebSearchScreen = false
        showPhotosScreen = false
    }
    // The lock-screen notice: straight to Settings, where talking on the lock screen is turned on.
    LaunchedEffect(settingsToOpen) {
        if (!settingsToOpen) return@LaunchedEffect
        showModelScreen = false
        showMemoryScreen = false
        showRemindersScreen = false
        showNotesScreen = false
        showProfileScreen = false
        showWebSearchScreen = false
        showPhotosScreen = false
        showSettings = true
        settingsSection = SettingsSection.CALLING
        onSettingsOpened()
    }

    // First launch: a few details about the user before anything else. Skippable.
    if (!profileAsked) {
        val profileViewModel: ProfileViewModel = viewModel(factory = ProfileViewModel.factory(container))
        ProfileScreen(viewModel = profileViewModel, onboarding = true, onDone = { profileAsked = true })
        return
    }

    // Then web search, optional: a Tavily key, or skip. Asked once.
    if (!webSearchAsked) {
        val webViewModel: WebSearchViewModel = viewModel(key = "web-onboarding", factory = WebSearchViewModel.factory(container))
        WebSearchScreen(
            viewModel = webViewModel,
            onboarding = true,
            onBack = {
                container.settings.webSearchAsked = true
                webSearchAsked = true
            },
        )
        return
    }

    if (preparing) {
        PreparingScreen(container.llmService)
        return
    }

    // With no model there is nothing to chat with, so the model screen is the whole app.
    if (installed == null || showModelScreen) {
        if (installed != null) BackHandler { showModelScreen = false }
        ModelScreen(
            modelManager = container.modelManager,
            llmService = container.llmService,
            onBack = if (installed != null) {
                { showModelScreen = false }
            } else {
                null
            },
            memorySearch = remember(container) { container.memorySearchControls() },
        )
        return
    }

    // Sub-screens of Settings, each back to Settings.
    if (showProfileScreen) {
        BackHandler { showProfileScreen = false }
        val profileViewModel: ProfileViewModel = viewModel(key = "profile-edit", factory = ProfileViewModel.factory(container))
        ProfileScreen(viewModel = profileViewModel, onboarding = false, onDone = { showProfileScreen = false })
        return
    }
    if (showWebSearchScreen) {
        BackHandler { showWebSearchScreen = false }
        val webViewModel: WebSearchViewModel = viewModel(factory = WebSearchViewModel.factory(container))
        WebSearchScreen(viewModel = webViewModel, onBack = { showWebSearchScreen = false })
        return
    }
    if (showSettings) {
        BackHandler {
            showSettings = false
            settingsSection = null
        }
        val memoryViewModel: MemoryViewModel = viewModel(factory = MemoryViewModel.factory(container))
        SettingsScreen(
            llmService = container.llmService,
            settings = container.settings,
            speaker = container.speaker,
            voicePacks = container.voicePacks,
            webSearchOn = container.webSearch.enabled,
            memory = memoryViewModel,
            awayBrief = container.awayBrief,
            health = container.healthSource,
            section = settingsSection,
            onSection = { settingsSection = it },
            onBack = {
                showSettings = false
                settingsSection = null
            },
            onOpenProfile = { showProfileScreen = true },
            onOpenModel = { showModelScreen = true },
            onOpenWebSearch = { showWebSearchScreen = true },
        )
        return
    }

    if (showPhotosScreen) {
        BackHandler { showPhotosScreen = false }
        val galleryViewModel: GalleryViewModel = viewModel(factory = GalleryViewModel.factory(container))
        GalleryScreen(viewModel = galleryViewModel, onBack = { showPhotosScreen = false })
        return
    }

    if (showMemoryScreen) {
        BackHandler { showMemoryScreen = false }
        val memoryViewModel: MemoryViewModel = viewModel(factory = MemoryViewModel.factory(container))
        MemoryScreen(viewModel = memoryViewModel, onBack = { showMemoryScreen = false })
        // A reminder changed to a place may need location allowed.
        ToolPermissionRequests(container.permissions.requests, elsewhere = { AssistActivity.inView })
        return
    }

    if (showRemindersScreen) {
        BackHandler { showRemindersScreen = false }
        val memoryViewModel: MemoryViewModel = viewModel(factory = MemoryViewModel.factory(container))
        RemindersScreen(viewModel = memoryViewModel, onBack = { showRemindersScreen = false })
        ToolPermissionRequests(container.permissions.requests, elsewhere = { AssistActivity.inView })
        return
    }

    // The Notes screen's own, kept by the activity: a list under a reply opens in it, and ticks there.
    val notesViewModel: NotesViewModel = viewModel(factory = NotesViewModel.factory(container))
    if (showNotesScreen) {
        BackHandler {
            notesViewModel.close()
            showNotesScreen = false
        }
        NotesScreen(viewModel = notesViewModel, onBack = { showNotesScreen = false }, backToChat = notesFromChat)
        return
    }

    // For the blank chat's hello; read again on coming back from Settings, where it is edited.
    val userName by produceState<String?>(null, showSettings) {
        value = container.memoryControls.profile()["name"]?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() }
    }
    val viewModel: ChatViewModel = viewModel(factory = ChatViewModel.factory(container))
    // The Reminders screen's own, kept by the activity: a place reminder's map in a reply opens its editor.
    val memoryViewModel: MemoryViewModel = viewModel(factory = MemoryViewModel.factory(container))
    LaunchedEffect(chatToOpen) {
        chatToOpen?.let {
            viewModel.openChat(it)
            onChatOpened()
        }
    }
    ChatScreen(
        viewModel = viewModel,
        onOpenMemory = { showMemoryScreen = true },
        onOpenReminders = { showRemindersScreen = true },
        onOpenPhotos = { showPhotosScreen = true },
        onOpenSettings = { showSettings = true },
        // Read again each time a screen closes: a key added or photos allowed shows up here.
        canSearchWeb = container.webSearch.enabled,
        canFindPhotos = container.photoSearchReady(),
        userName = userName,
        reminders = memoryViewModel,
        notes = notesViewModel,
        onOpenNotes = {
            notesViewModel.close()
            notesFromChat = false
            showNotesScreen = true
        },
        onOpenNote = { note ->
            notesViewModel.open(note)
            notesFromChat = true
            showNotesScreen = true
        },
    )
}

private const val SPLASH_EXIT_MS = 280L
