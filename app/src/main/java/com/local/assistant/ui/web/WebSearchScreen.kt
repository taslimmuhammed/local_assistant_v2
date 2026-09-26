package com.local.assistant.ui.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.local.assistant.AppContainer
import com.local.assistant.data.prefs.SecretStore
import com.local.assistant.memory.tools.WebSearch
import com.local.assistant.memory.tools.WebSearchError
import com.local.assistant.ui.theme.AppColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class WebSearchViewModel(
    private val secrets: SecretStore,
    private val web: WebSearch,
    /** The tools and instructions change with the key; the live conversation catches up when idle. */
    private val onChanged: () -> Unit,
) : ViewModel() {

    data class State(
        /** The end of the saved key, to recognise it by; null when there is none. */
        val savedHint: String? = null,
        val draft: String = "",
        val checking: Boolean = false,
        val message: String? = null,
        val failed: Boolean = false,
    )

    private val _state = MutableStateFlow(State(savedHint = hint(secrets.get(SecretStore.TAVILY_KEY))))
    val state: StateFlow<State> = _state.asStateFlow()

    fun edit(text: String) = _state.update { it.copy(draft = text.trim(), message = null) }

    /** Saves the key, then spends one search to check it works. */
    fun save() {
        val key = _state.value.draft.takeIf { it.isNotBlank() } ?: return
        secrets.put(SecretStore.TAVILY_KEY, key)
        onChanged()
        _state.update { it.copy(savedHint = hint(key), draft = "", message = null) }
        check()
    }

    fun check() {
        if (!web.enabled || _state.value.checking) return
        _state.update { it.copy(checking = true, message = null) }
        viewModelScope.launch {
            val (message, failed) = try {
                val found = web.search("weather today", news = false)
                "Working: found ${found.results.size} results." to false
            } catch (e: WebSearchError) {
                e.message.orEmpty() to true
            }
            _state.update { it.copy(checking = false, message = message, failed = failed) }
        }
    }

    fun remove() {
        secrets.put(SecretStore.TAVILY_KEY, null)
        onChanged()
        _state.update { State() }
    }

    private fun hint(key: String?): String? = key?.takeIf { it.isNotBlank() }?.let { "…" + it.takeLast(4) }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                WebSearchViewModel(container.secrets, container.webSearch, onChanged = container.conversations::prefixMayHaveChanged)
            }
        }
    }
}

/**
 * Web search: off until the user adds their own Tavily key. Everything else in the app stays on
 * the phone; this sends the search words the assistant writes, and nothing more.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebSearchScreen(viewModel: WebSearchViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var reveal by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = AppColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("Web search", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppColors.Background,
                    titleContentColor = AppColors.TextPrimary,
                    navigationIconContentColor = AppColors.TextPrimary,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Lets the assistant look things up online: news, weather, prices, scores. It uses Tavily with your own " +
                    "API key; the free plan has 1,000 searches a month. Only the search words the assistant writes are " +
                    "sent. Your chats and memory stay on this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
            )
            Text(
                if (state.savedHint != null) "On · key ${state.savedHint}" else "Off · no key yet",
                style = MaterialTheme.typography.titleSmall,
                color = AppColors.TextPrimary,
            )
            OutlinedTextField(
                value = state.draft,
                onValueChange = viewModel::edit,
                label = { Text(if (state.savedHint != null) "Replace the key" else "Tavily API key") },
                placeholder = { Text("tvly-…") },
                singleLine = true,
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                trailingIcon = {
                    IconButton(onClick = { reveal = !reveal }) {
                        Icon(if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = if (reveal) "Hide" else "Show")
                    }
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::save,
                    enabled = state.draft.isNotBlank() && !state.checking,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = AppColors.OnAccent),
                ) { Text("Save") }
                if (state.savedHint != null) {
                    OutlinedButton(onClick = viewModel::check, enabled = !state.checking) { Text(if (state.checking) "Checking…" else "Check") }
                    OutlinedButton(onClick = viewModel::remove, enabled = !state.checking) { Text("Remove", color = AppColors.Danger) }
                }
            }
            state.message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = if (state.failed) AppColors.Danger else AppColors.TextPrimary)
            }
            TextButton(onClick = { runCatching { uriHandler.openUri("https://app.tavily.com") } }) {
                Text("Get a free key at tavily.com")
            }
        }
    }
}
