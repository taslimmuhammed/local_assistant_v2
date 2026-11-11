package com.local.assistant.ui.web

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextAlign
import com.local.assistant.ui.theme.GlassCard
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
import androidx.compose.ui.Alignment
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
import com.local.assistant.ui.theme.GlassShapes
import com.local.assistant.ui.theme.glassFieldColors
import com.local.assistant.ui.theme.GlassIconButton
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

    /**
     * A Tavily key in [text] (the clipboard, as the user comes back from Tavily's site or taps
     * Paste) is saved and checked; true when there was one. Anything else is left alone.
     */
    fun takeKeyFrom(text: String?): Boolean {
        val key = text?.let { TAVILY_KEY.find(it)?.value } ?: return false
        if (hint(key) == _state.value.savedHint && secrets.get(SecretStore.TAVILY_KEY) == key) return false
        _state.update { it.copy(draft = key) }
        save()
        return true
    }

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
                // The tool's wording is for the model ("the user can check it…"); this is the user.
                val said = if (e.keyRefused) KEY_REFUSED else e.message.orEmpty()
                said to true
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
 *
 * Also a step of first launch ([onboarding]), right after the profile: optional, said plainly,
 * and a way past it. [onBack] then means "done or skipped".
 *
 * Getting a key means leaving for Tavily's site, so the way there is the screen's main button
 * (it was a link under the buttons in Settings, easy to miss), with what to do there in three
 * steps. Coming back with the key copied, it is pasted, saved and checked without a long press.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebSearchScreen(viewModel: WebSearchViewModel, onBack: () -> Unit, onboarding: Boolean = false) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    var reveal by remember { mutableStateOf(false) }
    // Sent to Tavily's site: the key is looked for on the clipboard as the user comes back.
    var awayAtTavily by rememberSaveable { mutableStateOf(false) }
    var leftTheApp by rememberSaveable { mutableStateOf(false) }
    val openTavily = {
        awayAtTavily = true
        leftTheApp = false
        runCatching { uriHandler.openUri(TAVILY_SIGN_UP) }
    }
    // Android lets an app read the clipboard only with its window focused, which comes a moment
    // after it is back on screen. The window is still focused as the button is tapped, so the
    // focus has to go (the browser opened) and come back before the clipboard is read.
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused, awayAtTavily) {
        if (!awayAtTavily) return@LaunchedEffect
        if (!focused) {
            leftTheApp = true
            return@LaunchedEffect
        }
        if (!leftTheApp) return@LaunchedEffect
        awayAtTavily = false
        leftTheApp = false
        viewModel.takeKeyFrom(clipboardText(context))
    }

    Scaffold(
        containerColor = AppColors.Background,
        topBar = {
            if (!onboarding) TopAppBar(
                title = { Text("Web search", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    GlassIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack, Modifier.padding(start = 10.dp, end = 6.dp))
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
            if (onboarding) {
                Text(
                    "Web search (optional)",
                    style = MaterialTheme.typography.headlineSmall,
                    color = AppColors.TextPrimary,
                    modifier = Modifier.padding(top = 48.dp),
                )
            }
            Text(
                "Lets the assistant look things up online: news, weather, prices, scores. It uses Tavily with your own " +
                    "free API key. Only the search words the assistant writes are sent; your chats and memory stay on " +
                    "this phone." + if (onboarding) " Everything else works without it, and you can add it later in Settings." else "",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
            )
            if (!onboarding || state.savedHint != null) {
                Text(
                    if (state.savedHint != null) "On · key ${state.savedHint}" else "Off · no key yet",
                    style = MaterialTheme.typography.titleSmall,
                    color = AppColors.TextPrimary,
                )
            }
            if (state.savedHint == null) {
                GetKeyCard(onOpen = { openTavily() })
            }
            OutlinedTextField(
                value = state.draft,
                onValueChange = viewModel::edit,
                label = { Text(if (state.savedHint != null) "Replace the key" else "Paste your key here") },
                placeholder = { Text("tvly-…") },
                singleLine = true,
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                trailingIcon = {
                    Row {
                        if (state.draft.isEmpty()) {
                            IconButton(onClick = {
                                val text = clipboardText(context)
                                if (!viewModel.takeKeyFrom(text)) text?.let(viewModel::edit)
                            }) {
                                Icon(Icons.Outlined.ContentPaste, contentDescription = "Paste")
                            }
                        }
                        IconButton(onClick = { reveal = !reveal }) {
                            Icon(if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = if (reveal) "Hide" else "Show")
                        }
                    }
                },
                shape = GlassShapes.Field,
                colors = glassFieldColors(),
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
            if (state.checking) {
                Text("Checking the key…", style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
            }
            state.message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = if (state.failed) AppColors.Danger else AppColors.TextPrimary)
            }
            if (state.savedHint != null) {
                TextButton(onClick = { openTavily() }) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Your Tavily account and usage", modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (onboarding) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    if (state.savedHint == null) {
                        TextButton(onClick = onBack) { Text("Skip for now") }
                    } else {
                        Button(
                            onClick = onBack,
                            enabled = !state.checking,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = AppColors.OnAccent),
                        ) { Text("Continue") }
                    }
                }
            } else {
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** How to get a key, in three steps, with the way to Tavily as its button. */
@Composable
private fun GetKeyCard(onOpen: () -> Unit) {
    GlassCard(padding = 16.dp) {
        Text("Get a free key in about a minute", style = MaterialTheme.typography.titleSmall, color = AppColors.TextPrimary)
        listOf(
            "Sign up at Tavily. The free plan has 1,000 searches a month.",
            "Copy your API key from your Tavily dashboard. It starts with tvly-.",
            "Come back here. The key is pasted and checked for you.",
        ).forEachIndexed { index, step ->
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Top) {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    color = AppColors.OnAccent,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .size(22.dp)
                        .background(AppColors.Accent, CircleShape)
                        .wrapContentHeight(Alignment.CenterVertically),
                )
                Text(step, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 12.dp))
            }
        }
        Button(
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = AppColors.OnAccent),
        ) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Get a free key at tavily.com", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

private fun clipboardText(context: Context): String? =
    runCatching {
        context.getSystemService(ClipboardManager::class.java)?.primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
    }.getOrNull()

/** Where a Tavily key is made: sign-up, then the key is on the dashboard. */
private const val TAVILY_SIGN_UP = "https://app.tavily.com"

/** A Tavily API key as its dashboard copies it. */
private const val KEY_REFUSED =
    "Tavily didn't accept this key. Copy it again from your Tavily dashboard, all of it, and paste it here."

private val TAVILY_KEY = Regex("""tvly-[A-Za-z0-9_-]{8,}""")
