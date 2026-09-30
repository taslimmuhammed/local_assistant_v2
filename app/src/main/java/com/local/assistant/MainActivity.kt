package com.local.assistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.local.assistant.ui.chat.ChatScreen
import com.local.assistant.ui.memory.MemoryScreen
import com.local.assistant.ui.memory.MemoryViewModel
import com.local.assistant.ui.profile.ProfileScreen
import com.local.assistant.ui.profile.ProfileViewModel
import androidx.activity.compose.BackHandler
import com.local.assistant.ui.chat.ChatViewModel
import com.local.assistant.ui.settings.SettingsScreen
import com.local.assistant.ui.setup.ModelScreen
import com.local.assistant.ui.setup.PreparingScreen
import com.local.assistant.ui.web.WebSearchScreen
import com.local.assistant.ui.web.WebSearchViewModel
import com.local.assistant.ui.theme.LocalAssistantTheme

class MainActivity : ComponentActivity() {

    /** A chat to show, from the assistant overlay's "Open in app". */
    private val chatToOpen = mutableStateOf<Long?>(null)

    /** Settings asked for from outside: the lock-screen notice. */
    private val settingsToOpen = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            chatToOpen.value = chatIdIn(intent)
            settingsToOpen.value = settingsIn(intent)
        }
        val container = appContainer
        setContent {
            LocalAssistantTheme {
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
    var showModelScreen by remember { mutableStateOf(false) }
    var showMemoryScreen by remember { mutableStateOf(false) }
    var showProfileScreen by remember { mutableStateOf(false) }
    var showWebSearchScreen by remember { mutableStateOf(false) }
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
        showProfileScreen = false
        showWebSearchScreen = false
    }
    // The lock-screen notice: straight to Settings, where talking on the lock screen is turned on.
    LaunchedEffect(settingsToOpen) {
        if (!settingsToOpen) return@LaunchedEffect
        showModelScreen = false
        showMemoryScreen = false
        showProfileScreen = false
        showWebSearchScreen = false
        showSettings = true
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
        BackHandler { showSettings = false }
        val memoryViewModel: MemoryViewModel = viewModel(factory = MemoryViewModel.factory(container))
        SettingsScreen(
            llmService = container.llmService,
            settings = container.settings,
            speaker = container.speaker,
            webSearchOn = container.webSearch.enabled,
            memory = memoryViewModel,
            onBack = { showSettings = false },
            onOpenProfile = { showProfileScreen = true },
            onOpenModel = { showModelScreen = true },
            onOpenWebSearch = { showWebSearchScreen = true },
        )
        return
    }

    if (showMemoryScreen) {
        BackHandler { showMemoryScreen = false }
        val memoryViewModel: MemoryViewModel = viewModel(factory = MemoryViewModel.factory(container))
        MemoryScreen(viewModel = memoryViewModel, onBack = { showMemoryScreen = false })
        return
    }

    val viewModel: ChatViewModel = viewModel(factory = ChatViewModel.factory(container))
    LaunchedEffect(chatToOpen) {
        chatToOpen?.let {
            viewModel.openChat(it)
            onChatOpened()
        }
    }
    ChatScreen(
        viewModel = viewModel,
        onOpenMemory = { showMemoryScreen = true },
        onOpenSettings = { showSettings = true },
    )
}
