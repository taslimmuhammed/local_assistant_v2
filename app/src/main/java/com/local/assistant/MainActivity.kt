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
import com.local.assistant.ui.setup.ModelScreen
import com.local.assistant.ui.startup.LoadingScreen
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
    var showModelScreen by remember { mutableStateOf(false) }
    var showMemoryScreen by remember { mutableStateOf(false) }
    var showProfileScreen by remember { mutableStateOf(false) }
    var profileAsked by remember { mutableStateOf(container.settings.profileAsked) }

    // First launch: a few details about the user before anything else. Skippable.
    if (!profileAsked) {
        val profileViewModel: ProfileViewModel = viewModel(factory = ProfileViewModel.factory(container))
        ProfileScreen(viewModel = profileViewModel, onboarding = true, onDone = { profileAsked = true })
        return
    }

    // With no model there is nothing to chat with, so the model screen is the whole app.
    if (installed == null || showModelScreen) {
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

    // The first load measures the context window, which takes real time. Doing it here rather
    // than behind the chat means nobody watches an idle composer wondering if it hung. Once the
    // window is known, an ordinary load takes seconds: the chat is shown straight away and a
    // message sent meanwhile simply waits for the engine.
    val engineState by container.llmService.state.collectAsStateWithLifecycle()
    val calibration by container.llmService.calibrationProgress.collectAsStateWithLifecycle()
    val windowKnown = container.settings.calibratedContextTokens > 0 ||
        container.settings.manualContextTokens > 0
    val needsLoadingScreen = engineState is LlmService.State.Failed ||
        calibration != null ||
        (engineState !is LlmService.State.Ready && !windowKnown)
    if (needsLoadingScreen) {
        LoadingScreen(
            llmService = container.llmService,
            onOpenModelSettings = { showModelScreen = true },
        )
        return
    }

    if (showProfileScreen) {
        BackHandler { showProfileScreen = false }
        val profileViewModel: ProfileViewModel = viewModel(key = "profile-edit", factory = ProfileViewModel.factory(container))
        ProfileScreen(viewModel = profileViewModel, onboarding = false, onDone = { showProfileScreen = false })
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
        onOpenModelSettings = { showModelScreen = true },
        onOpenMemory = { showMemoryScreen = true },
        onOpenProfile = { showProfileScreen = true },
    )
}
