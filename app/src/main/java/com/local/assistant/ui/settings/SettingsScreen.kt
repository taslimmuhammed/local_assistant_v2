package com.local.assistant.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.local.assistant.data.prefs.SettingsStore
import com.local.assistant.llm.LlmService
import com.local.assistant.model.ModelCatalog
import com.local.assistant.ui.memory.MemoryViewModel
import com.local.assistant.ui.theme.AppColors
import java.time.LocalDate

/**
 * Every setting in one place: who the user is, the model and its window, web search, and what
 * memory keeps. Things that need their own screen — the profile form, the model file, the Tavily
 * key — open from here; the rest is set right on this page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    llmService: LlmService,
    settings: SettingsStore,
    webSearchOn: Boolean,
    memory: MemoryViewModel,
    onBack: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenModel: () -> Unit,
    onOpenWebSearch: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(memory) { memory.notices.collect { snackbar.showSnackbar(it.message) } }

    Scaffold(
        containerColor = AppColors.Background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleMedium) },
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
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionHeader("You")
            NavRow("Your profile", "Name, age, work and more, kept in mind in every chat", onOpenProfile)

            SectionDivider()
            SectionHeader("Model")
            val state by llmService.state.collectAsStateWithLifecycle()
            NavRow(
                "Model and memory search",
                listOfNotNull(
                    ModelCatalog.DISPLAY_NAME,
                    (state as? LlmService.State.Ready)?.let { "running on ${it.backend}" },
                ).joinToString(" · "),
                onOpenModel,
            )
            ContextWindow(llmService, settings)

            SectionDivider()
            SectionHeader("Web search")
            NavRow("Web search", if (webSearchOn) "On · Tavily" else "Off · add a Tavily key to turn it on", onOpenWebSearch)

            SectionDivider()
            SectionHeader("Memory")
            MemorySettings(memory)
            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * The window the model runs with. 8K by default; more keeps longer chats in view, at the cost of
 * memory and a slower start. A size the phone can't hold falls back to 8K on its own.
 */
@Composable
private fun ContextWindow(llmService: LlmService, settings: SettingsStore) {
    val active by llmService.activeContextTokens.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<Int?>(null) }
    val chosen = settings.contextTokens

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Context window", style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
        Text(
            "How much of a chat the model keeps in view. Larger windows keep longer chats but use more memory and take longer to start.",
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary,
        )
        for (tokens in SettingsStore.CONTEXT_CHOICES) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = tokens == chosen, role = Role.RadioButton) { if (tokens != chosen) pending = tokens }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = tokens == chosen, onClick = null)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(
                        "${tokens / 1024}K tokens" + if (tokens == SettingsStore.DEFAULT_CONTEXT_TOKENS) " (default)" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.TextPrimary,
                    )
                    CONTEXT_NOTES[tokens]?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary) }
                }
            }
        }
        if (settings.contextFellBack) {
            Text(
                "The larger window didn't fit on this phone, so the model is running at 8K.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.Danger,
            )
        } else if (active > 0 && active != chosen) {
            Text("Running at ${active / 1024}K until the model restarts.", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        }
    }

    pending?.let { tokens ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Use a ${tokens / 1024}K window?") },
            text = {
                Text(
                    "The model restarts, which takes a few seconds, and any open chat is rebuilt. " + when {
                        tokens > SettingsStore.DEFAULT_CONTEXT_TOKENS -> "If this phone can't hold it, the app goes back to 8K."
                        tokens < SettingsStore.DEFAULT_CONTEXT_TOKENS -> "The assistant will see only the last exchange or two of a chat."
                        else -> ""
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    llmService.setContextTokens(tokens)
                }) { Text("Restart the model") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}

/** What each window size means in practice. */
private val CONTEXT_NOTES = mapOf(
    4096 to "Least memory. Keeps only the last exchange or two of a chat in view.",
    8192 to "Best for most phones.",
    12288 to "Longer chats in view. More memory.",
    16384 to "Longest chats. Most memory, slowest to start.",
)

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = AppColors.TextPrimary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(color = AppColors.Border, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = AppColors.TextSecondary)
    }
}

// ---- Memory: moved here from the memory screen ----

@Composable
private fun MemorySettings(viewModel: MemoryViewModel) {
    val paused by viewModel.memoryPaused.collectAsStateWithLifecycle()
    val retention by viewModel.retentionDays.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var retentionMenu by remember { mutableStateOf(false) }
    var pendingRetention by remember { mutableStateOf<Int?>(null) }
    var forgetStep by remember { mutableIntStateOf(0) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { viewModel.export(it, context.contentResolver) }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Pause memory", style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
                Text(
                    "Chats go on as usual, but nothing from them is remembered, archived or learned. Reminders still work.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                )
            }
            Switch(checked = paused, onCheckedChange = viewModel::setPaused)
        }
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().clickable { retentionMenu = true }, verticalAlignment = Alignment.CenterVertically) {
                Text("Keep chat history", style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
                Text(retentionLabel(retention), style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
            }
            DropdownMenu(expanded = retentionMenu, onDismissRequest = { retentionMenu = false }) {
                for (days in RETENTION_CHOICES) {
                    DropdownMenuItem(text = { Text(retentionLabel(days)) }, onClick = {
                        retentionMenu = false
                        // Shortening deletes messages, so it is confirmed; lengthening is not.
                        if (days != 0 && (retention == 0 || days < retention)) pendingRetention = days else viewModel.setRetention(days)
                    })
                }
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { exporter.launch("memory-${LocalDate.now()}.json") }) { Text("Export as JSON") }
            OutlinedButton(onClick = { forgetStep = 1 }) { Text("Forget everything", color = AppColors.Danger) }
        }
    }

    pendingRetention?.let { days ->
        AlertDialog(
            onDismissRequest = { pendingRetention = null },
            title = { Text("Keep ${retentionLabel(days).lowercase()}?") },
            text = { Text("Messages older than that are deleted now, and from then on every night. Chats left empty go too.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setRetention(days)
                    pendingRetention = null
                }) { Text("Delete older messages", color = AppColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { pendingRetention = null }) { Text("Cancel") } },
        )
    }
    if (forgetStep == 1) {
        AlertDialog(
            onDismissRequest = { forgetStep = 0 },
            title = { Text("Forget everything?") },
            text = { Text("Every fact, reminder and event, every saved image, the search index of past conversations and all summaries are deleted. Your chats stay, but nothing is learned from them again.") },
            confirmButton = { TextButton(onClick = { forgetStep = 2 }) { Text("Continue", color = AppColors.Danger) } },
            dismissButton = { TextButton(onClick = { forgetStep = 0 }) { Text("Cancel") } },
        )
    }
    if (forgetStep == 2) {
        AlertDialog(
            onDismissRequest = { forgetStep = 0 },
            title = { Text("This can't be undone") },
            text = { Text("Delete everything the assistant knows about you?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.forgetEverything()
                    forgetStep = 0
                }) { Text("Forget everything", color = AppColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { forgetStep = 0 }) { Text("Keep") } },
        )
    }
}

private fun retentionLabel(days: Int): String = when (days) {
    0 -> "Forever"
    365 -> "1 year"
    else -> "$days days"
}

private val RETENTION_CHOICES = listOf(0, 365, 90, 30)
