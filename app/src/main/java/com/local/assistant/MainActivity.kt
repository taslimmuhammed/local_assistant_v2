package com.local.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.local.assistant.llm.LlmService
import com.local.assistant.ui.settings.SettingsScreen
import com.local.assistant.ui.setup.ModelScreen
import com.local.assistant.ui.startup.LoadingScreen
import com.local.assistant.ui.web.WebSearchScreen
import com.local.assistant.ui.web.WebSearchViewModel
import com.local.assistant.ui.theme.LocalAssistantTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = appContainer
        setContent {
            LocalAssistantTheme {
                AppRoot(container)
            }
        }
    }
}

@Composable
private fun AppRoot(container: AppContainer) {
    val installed by container.modelManager.installed.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var showModelScreen by remember { mutableStateOf(false) }
    var showMemoryScreen by remember { mutableStateOf(false) }
    var showProfileScreen by remember { mutableStateOf(false) }
    var showWebSearchScreen by remember { mutableStateOf(false) }
    var profileAsked by remember { mutableStateOf(container.settings.profileAsked) }
    var webSearchAsked by remember { mutableStateOf(container.settings.webSearchAsked || container.webSearch.enabled) }

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
            webSearchOn = container.webSearch.enabled,
            memory = memoryViewModel,
            onBack = { showSettings = false },
            onOpenProfile = { showProfileScreen = true },
            onOpenModel = { showModelScreen = true },
            onOpenWebSearch = { showWebSearchScreen = true },
        )
        return
    }

    // The model is loaded as the app starts, and the chat waits for it: a message typed while it
    // loads would only sit there. Also shown while it restarts after a settings change.
    val engineState by container.llmService.state.collectAsStateWithLifecycle()
    if (engineState !is LlmService.State.Ready) {
        LoadingScreen(llmService = container.llmService, onOpenSettings = { showSettings = true })
        return
    }

    if (showMemoryScreen) {
        BackHandler { showMemoryScreen = false }
        val memoryViewModel: MemoryViewModel = viewModel(factory = MemoryViewModel.factory(container))
        MemoryScreen(viewModel = memoryViewModel, onBack = { showMemoryScreen = false })
        return
    }

    val viewModel: ChatViewModel = viewModel(factory = ChatViewModel.factory(container))
    ChatScreen(
        viewModel = viewModel,
        onOpenMemory = { showMemoryScreen = true },
        onOpenSettings = { showSettings = true },
    )
}
