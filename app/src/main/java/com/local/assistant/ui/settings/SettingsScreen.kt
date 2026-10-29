package com.local.assistant.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.local.assistant.assist.AssistantRole
import com.local.assistant.assist.KeepReadyService
import com.local.assistant.assist.LockScreenNotice
import com.local.assistant.data.prefs.SettingsStore
import com.local.assistant.llm.LlmService
import com.local.assistant.model.ModelCatalog
import com.local.assistant.model.formatBytes
import com.local.assistant.ui.memory.MemoryViewModel
import com.local.assistant.ui.theme.AppColors
import com.local.assistant.ui.theme.AppSwitch
import com.local.assistant.voice.Speaker
import com.local.assistant.voice.NaturalVoices
import com.local.assistant.voice.VoiceOption
import com.local.assistant.voice.VoicePack
import com.local.assistant.voice.VoicePackInstaller
import kotlinx.coroutines.launch
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
    speaker: Speaker,
    voicePacks: List<VoicePackInstaller>,
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
            SectionHeader("Assistant")
            AssistantSettings(settings, speaker, voicePacks, llmService)

            SectionDivider()
            SectionHeader("Web search")
            NavRow("Web search", if (webSearchOn) "On · Tavily" else "Off · add a Tavily key to turn it on", onOpenWebSearch)

            SectionDivider()
            SectionHeader("Memory")
            MemorySettings(memory)

            SectionDivider()
            SectionHeader("Models and licences")
            ModelLicences()
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

/**
 * The power-button assistant and its voice. Being the phone's assistant is the system's to
 * grant, so this only says whether it is and opens the page where it's chosen.
 */
@Composable
private fun AssistantSettings(settings: SettingsStore, speaker: Speaker, voicePacks: List<VoicePackInstaller>, llmService: LlmService) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var isAssistant by remember { mutableStateOf(AssistantRole.isDefault(context)) }
    // Checked again on the way back from the system's page, where it is changed.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { isAssistant = AssistantRole.isDefault(context) }
    }
    NavRow(
        "Power button",
        if (isAssistant) {
            "On. Press and hold the power button to talk."
        } else {
            "Off. Tap, then choose Local Assistant as the digital assistant app."
        },
    ) { AssistantRole.openSettings(context) }
    if (!isAssistant) {
        Text(
            "If holding the power button still opens the power menu, switch it to the voice assistant in your phone's power button settings. You can also long-press the app icon and pick Talk.",
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }

    var keepReady by remember { mutableStateOf(settings.keepAssistantReady) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text("Keep the assistant ready", style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
            Text(
                "Keeps the model loaded so the power button answers in about a second, instead of about 15 seconds after the phone has put the app away. " +
                    "Holds about $KEEP_READY_MEMORY of memory and shows a silent notification.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
            )
        }
        AppSwitch(checked = keepReady, onCheckedChange = {
            keepReady = it
            settings.keepAssistantReady = it
            if (it) {
                KeepReadyService.start(context)
                llmService.warmUp()
            } else {
                // The model goes the next time Android asks for memory back, as before.
                KeepReadyService.stop(context)
            }
        })
    }

    // Off by default: whoever holds the locked phone would get the assistant, memory and all.
    var onLockScreen by remember { mutableStateOf(settings.assistantOnLockScreen) }
    var confirmingLockScreen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text("Use on the lock screen", style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
            Text(
                if (onLockScreen) {
                    "On: the power button opens the assistant without unlocking. ${LockScreenNotice.RISK}"
                } else {
                    "Off: on the lock screen the assistant asks you to unlock first."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (onLockScreen) AppColors.Danger else AppColors.TextSecondary,
            )
        }
        AppSwitch(checked = onLockScreen, onCheckedChange = { on ->
            if (on) {
                confirmingLockScreen = true
            } else {
                onLockScreen = false
                settings.assistantOnLockScreen = false
            }
        })
    }
    if (confirmingLockScreen) {
        AlertDialog(
            onDismissRequest = { confirmingLockScreen = false },
            title = { Text("Use the assistant without unlocking?") },
            text = {
                Text(
                    "Holding the power button on the lock screen will open the assistant with everything it has in the app. " +
                        "${LockScreenNotice.RISK} It can also set alarms, reminders and phone settings. " +
                        "Calls, messages and apps it opens still show only after you unlock.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onLockScreen = true
                    settings.assistantOnLockScreen = true
                    confirmingLockScreen = false
                }) { Text("Turn on", color = AppColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmingLockScreen = false }) { Text("Keep it off") } },
        )
    }

    var speak by remember { mutableStateOf(settings.speakReplies) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text("Answer out loud", style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
            Text(
                "When you ask by voice, the answer is read aloud as well as shown.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
            )
        }
        AppSwitch(checked = speak, onCheckedChange = {
            speak = it
            settings.speakReplies = it
        })
    }

    // The engine lists its voices once it has started, which takes a moment the first time.
    var voices by remember { mutableStateOf<List<VoiceOption>?>(null) }
    var current by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(speaker) {
        voices = speaker.voiceOptions()
        current = speaker.currentVoice()
    }
    val installedPacks = voicePacks.map { it.installed.collectAsStateWithLifecycle().value }
    var naturalId by remember { mutableStateOf(settings.naturalVoice) }
    // A download that finishes switches to its voice (AppContainer.voicePacks).
    LaunchedEffect(installedPacks) { naturalId = settings.naturalVoice }
    val natural = NaturalVoices.byId(naturalId)
        ?.takeIf { voice -> installedPacks[voicePacks.indexOfFirst { it.pack == voice.pack }] != null }
    val options = voices
    when {
        natural != null -> NavRow("Voice", natural.label) { picking = true }
        options == null -> NavRow("Voice", "Loading the phone's voices…") {}
        options.isEmpty() -> NavRow("Voice", "No phone voices work offline. Tap to download a natural voice.") { picking = true }
        else -> NavRow("Voice", options.firstOrNull { it.name == current }?.label ?: options.first().label) { picking = true }
    }

    var rate by remember { mutableFloatStateOf(settings.speechRate) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Pace", style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((value, label) in SettingsStore.SPEECH_RATES) {
                // In the switches' colours: the choice in black, the others plainly outlined.
                FilterChip(
                    selected = value == rate,
                    onClick = {
                        rate = value
                        settings.speechRate = value
                        speaker.preview()
                    },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = AppColors.Background,
                        labelColor = AppColors.TextPrimary,
                        selectedContainerColor = AppColors.Accent,
                        selectedLabelColor = AppColors.OnAccent,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = value == rate,
                        borderColor = AppColors.TextSecondary,
                        selectedBorderColor = AppColors.Accent,
                    ),
                )
            }
        }
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = {
                picking = false
                speaker.stop()
            },
            title = { Text("Voice") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    for (installer in voicePacks) {
                        VoicePackChoices(
                            installer = installer,
                            selected = natural,
                            onSelect = { voice ->
                                naturalId = voice.id
                                settings.naturalVoice = voice.id
                                speaker.preview()
                            },
                            onRemoved = {
                                if (natural?.pack == installer.pack) {
                                    naturalId = null
                                    settings.naturalVoice = null
                                }
                            },
                        )
                    }
                    Text(
                        "Phone voices",
                        style = MaterialTheme.typography.labelLarge,
                        color = AppColors.TextPrimary,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                    if (options.isNullOrEmpty()) {
                        Text(
                            "None on this phone work offline.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.TextSecondary,
                        )
                        TextButton(onClick = { openTextToSpeechSettings(context) }, contentPadding = PaddingValues(0.dp)) {
                            Text("Add one in the text-to-speech settings")
                        }
                    } else {
                        Text(
                            "Tap one to hear it. Only voices that run on the phone are listed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.TextSecondary,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        for (option in options) {
                            val chosen = natural == null && option.name == current
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(selected = chosen, role = Role.RadioButton) {
                                        current = option.name
                                        naturalId = null
                                        settings.naturalVoice = null
                                        settings.assistantVoice = option.name
                                        speaker.preview()
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = chosen, onClick = null)
                                Text(option.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    picking = false
                    speaker.stop()
                }) { Text("Done") }
            },
        )
    }
}

/**
 * One natural voice download at the top of the voice list: what it is and costs, then its
 * voices to choose from once it's here, or its download and progress until then.
 */
@Composable
private fun VoicePackChoices(
    installer: VoicePackInstaller,
    selected: NaturalVoices.Voice?,
    onSelect: (NaturalVoices.Voice) -> Unit,
    onRemoved: () -> Unit,
) {
    val pack = installer.pack
    val installed by installer.installed.collectAsStateWithLifecycle()
    val progress by installer.progress.collectAsStateWithLifecycle()
    val error by installer.error.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(bottom = 12.dp)) {
        Text(pack.title, style = MaterialTheme.typography.labelLarge, color = AppColors.TextPrimary)
        Text(
            PACK_NOTES[pack.name].orEmpty() +
                if (installed == null) " A ${formatBytes(pack.archiveBytes)} download." else "",
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary,
        )
        if (installed != null) {
            for (voice in NaturalVoices.ALL.filter { it.pack == pack }) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = voice == selected, role = Role.RadioButton) { onSelect(voice) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = voice == selected, onClick = null)
                    Text(voice.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                }
            }
            TextButton(
                onClick = {
                    scope.launch {
                        installer.delete()
                        onRemoved()
                    }
                },
                contentPadding = PaddingValues(0.dp),
            ) { Text("Remove ${pack.title} (frees ${formatBytes(pack.installedBytes)})") }
            return@Column
        }
        when (val now = progress) {
            is VoicePackInstaller.Progress.Downloading -> {
                LinearProgressIndicator(
                    progress = { now.transfer.fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    color = AppColors.Accent,
                    trackColor = AppColors.SurfaceMuted,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Downloading ${formatBytes(now.transfer.bytesDone)} of ${formatBytes(now.transfer.bytesTotal)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = installer::cancel) { Text("Cancel") }
                }
            }
            is VoicePackInstaller.Progress.Unpacking -> {
                LinearProgressIndicator(
                    progress = { now.fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    color = AppColors.Accent,
                    trackColor = AppColors.SurfaceMuted,
                )
                Text(
                    "Unpacking ${(now.fraction * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            null -> {
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.Danger, modifier = Modifier.padding(top = 4.dp)) }
                val partial = installer.partialBytes()
                OutlinedButton(onClick = installer::download, modifier = Modifier.padding(top = 8.dp)) {
                    Text(if (partial > 0) "Resume download (${formatBytes(pack.archiveBytes - partial)} left)" else "Download ${pack.title}")
                }
            }
        }
    }
}

/**
 * What each natural voice download is like. Memory is the app's PSS growth on the OnePlus
 * (NaturalVoiceEvalTest): Kokoro +481 MB loaded, +613 MB at peak. The word slips are from
 * eight typical replies run through a speech recogniser (Mac comparison, 2026-10-28).
 */
private val PACK_NOTES = mapOf(
    VoicePack.KOKORO.name to "Sounds like a person, and read every word right in tests. Uses about 600 MB of memory while it talks.",
)

/** What keeping the model loaded costs: the app's PSS on the test phone (fp32 decoder, 8K), AssistLatencyEvalTest. */
private const val KEEP_READY_MEMORY = "1.8 GB"

/** The system's text-to-speech page, where engines and voice data are installed. */
/**
 * What the models are and the terms they come under. EmbeddingGemma is a Gemma model: shipping
 * it means passing on the Gemma Terms of Use and its Prohibited Use Policy, and saying it was
 * modified (converted). Gemma 4 E4B and Granite are Apache 2.0.
 */
@Composable
private fun ModelLicences() {
    val uri = LocalUriHandler.current
    @Composable
    fun Entry(title: String, body: String, links: List<Pair<String, String>>) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
            Text(body, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            for ((label, link) in links) {
                TextButton(onClick = { runCatching { uri.openUri(link) } }, contentPadding = PaddingValues(0.dp)) { Text(label) }
            }
        }
    }
    Entry(
        "Gemma 4 E4B (the assistant)",
        "By Google, in LiteRT-LM format. Apache License 2.0.",
        listOf("Apache License 2.0" to "https://www.apache.org/licenses/LICENSE-2.0"),
    )
    Entry(
        "EmbeddingGemma 300M (memory search)",
        "Gemma is provided under and subject to the Gemma Terms of Use found at ai.google.dev/gemma/terms. " +
            "This copy is modified: converted to LiteRT-LM format with 8-bit weights. " +
            "Using it means agreeing not to use it for anything in the Gemma Prohibited Use Policy.",
        listOf(
            "Gemma Terms of Use" to "https://ai.google.dev/gemma/terms",
            "Gemma Prohibited Use Policy" to "https://ai.google.dev/gemma/prohibited_use_policy",
        ),
    )
    Entry(
        "Granite Embedding 311M (if imported)",
        "By IBM, in LiteRT-LM format. Apache License 2.0.",
        listOf("Apache License 2.0" to "https://www.apache.org/licenses/LICENSE-2.0"),
    )
    Entry(
        "Kokoro-82M (a natural voice, if downloaded)",
        "By hexgrad, in ONNX format from the sherpa-onnx project, run by sherpa-onnx (Apache License 2.0). " +
            "Apache License 2.0. Pronunciation by eSpeak NG, GNU General Public License v3.",
        listOf(
            "Apache License 2.0" to "https://www.apache.org/licenses/LICENSE-2.0",
            "eSpeak NG" to "https://github.com/espeak-ng/espeak-ng",
        ),
    )
}

private fun openTextToSpeechSettings(context: android.content.Context) {
    val screens = listOf(Intent("com.android.settings.TTS_SETTINGS"), Intent(Settings.ACTION_SETTINGS))
    for (screen in screens) {
        try {
            context.startActivity(screen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: ActivityNotFoundException) {
            continue
        }
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
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text("Pause memory", style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
                Text(
                    "Chats go on as usual, but nothing from them is remembered, archived or learned. Reminders still work.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                )
            }
            AppSwitch(checked = paused, onCheckedChange = viewModel::setPaused)
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
            OutlinedButton(onClick = { exporter.launch("memory-${LocalDate.now()}.json") }) { Text("Export as JSON", color = AppColors.TextPrimary) }
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
