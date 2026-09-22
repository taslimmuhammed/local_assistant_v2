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
import com.local.assistant.ui.chat.ChatViewModel
import com.local.assistant.ui.setup.ModelScreen
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
        )
        return
    }

    val viewModel: ChatViewModel = viewModel(factory = ChatViewModel.factory(container))
    ChatScreen(
        viewModel = viewModel,
        onOpenModelSettings = { showModelScreen = true },
    )
}
