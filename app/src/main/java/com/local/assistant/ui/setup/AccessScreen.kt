package com.local.assistant.ui.setup

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.local.assistant.AppContainer
import com.local.assistant.assist.AssistantRole
import com.local.assistant.away.MessageListener
import com.local.assistant.files.FileAccess
import com.local.assistant.gallery.PhotoLibrary
import com.local.assistant.ui.settings.openAccess
import com.local.assistant.ui.settings.openAppInfo
import com.local.assistant.ui.theme.AppColors
import com.local.assistant.ui.theme.GlassCard
import kotlinx.coroutines.launch

/**
 * First launch, after web search: what the assistant may look through on the phone, asked
 * together so search works from the first chat (the user, 2026-11-13: "ask all files access
 * when the user first installs the app"). Three cards, each optional and each changeable later:
 *
 *  - Photos and people: the photo permission, and whether faces are grouped (on by default here;
 *    they stay on the phone). Allowing it starts the photo model's download, and the photos and
 *    their faces are indexed as soon as it's here, while the chat model is still downloading.
 *  - Files: Android's All files access, so files are found by name and read when asked about.
 *  - Messages: notification access, so messages that arrive are kept for search; and, while the
 *    app is the phone's digital assistant, the SMS inbox (Play allows SMS only then).
 *
 * Installs from before this page see it once too ([com.local.assistant.data.prefs.SettingsStore.accessAsked]).
 */
@Composable
fun AccessScreen(container: AppContainer, onDone: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val library = container.photoLibrary
    val settings = container.settings

    var photos by remember { mutableStateOf(library.access()) }
    var findPeople by remember { mutableStateOf(if (library.access() == PhotoLibrary.Access.NONE) true else settings.findPeople) }
    var files by remember { mutableStateOf(FileAccess.granted(context)) }
    var notifications by remember { mutableStateOf(MessageListener.allowed(context)) }
    var isAssistant by remember { mutableStateOf(AssistantRole.isDefault(context)) }
    var sms by remember { mutableStateOf(smsGranted(context)) }
    var keepMessages by remember { mutableStateOf(true) }
    // Checked again on the way back from Android's pages, where these are given.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            photos = library.access()
            files = FileAccess.granted(context)
            notifications = MessageListener.allowed(context)
            isAssistant = AssistantRole.isDefault(context)
            sms = smsGranted(context)
        }
    }

    val photoRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        photos = library.access()
        if (photos == PhotoLibrary.Access.NONE) return@rememberLauncherForActivityResult
        settings.findPeople = findPeople
        // The photo model now, beside the chat model: photos are searchable sooner.
        val models = container.galleryModelManager
        if (models.installed.value == null && models.transfer.value == null) models.download()
        container.indexPhotosIfReady()
    }
    val filesRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { files = FileAccess.granted(context) }
    val smsRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { sms = smsGranted(context) }

    fun setFindPeople(on: Boolean) {
        findPeople = on
        // Before photos are allowed it's only the choice; it's written when they are.
        if (photos == PhotoLibrary.Access.NONE || on == settings.findPeople) return
        settings.findPeople = on
        scope.launch { if (on) container.indexPhotosIfReady() else container.faceIndex.forgetAll() }
    }

    Scaffold(containerColor = AppColors.Background) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "What can I look through?",
                style = MaterialTheme.typography.headlineSmall,
                color = AppColors.TextPrimary,
                modifier = Modifier.padding(top = 48.dp),
            )
            Text(
                "So you can ask for a photo, a file or a message the way you'd ask a person. Everything is read on this " +
                    "phone and stays on it. Each is optional, and you can change them later in Settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
            )

            AccessCard(
                title = "Photos and people",
                body = "Ask for a photo by what's in it, like “my passport” or “the beach last December”, or by who's in it.",
                allowed = when (photos) {
                    PhotoLibrary.Access.ALL -> "Allowed"
                    PhotoLibrary.Access.SOME -> "Allowed for the photos you picked"
                    PhotoLibrary.Access.NONE -> null
                },
                action = "Allow photos",
                onAllow = { photoRequest.launch(library.permissions + library.locationPermissions) },
            ) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Find people in your photos", style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
                        Text(
                            "Groups faces so you can name people. Faces are kept only on this phone; turning this off deletes them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.TextSecondary,
                        )
                    }
                    Switch(checked = findPeople, onCheckedChange = ::setFindPeople, modifier = Modifier.padding(start = 12.dp))
                }
            }

            AccessCard(
                title = "Files",
                body = "Find a file by its name, like “my resume PDF” or “the rent agreement”, and have it read when you ask: " +
                    "a summary, or one detail in it. Only names are searched; a file is opened only when you ask about it.",
                allowed = if (files) "Allowed" else null,
                action = "Allow access to files",
                onAllow = { FileAccess.permission?.let(filesRequest::launch) ?: FileAccess.openSettings(context) },
            )

            AccessCard(
                title = "Messages",
                body = "Ask what someone sent you, like “what did Rahul say about the trip?”. Messages that arrive in your " +
                    "notifications are kept on this phone as long as your chats. Ads and one-time codes are left out.",
                allowed = if (notifications) "Notification access allowed" else null,
                action = "Allow notification access",
                onAllow = { openAccess(context) },
            ) {
                if (!notifications) {
                    Text(
                        "Installed from an APK and the switch is greyed out? Open App info, tap ⋮ and choose Allow restricted settings, then try again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    TextButton(onClick = { openAppInfo(context) }, contentPadding = PaddingValues(0.dp)) { Text("Open App info") }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Keep messages for search", style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
                    Switch(checked = keepMessages, onCheckedChange = { keepMessages = it }, modifier = Modifier.padding(start = 12.dp))
                }
                if (keepMessages) Text(
                    when {
                        sms -> "Your SMS inbox is searched too."
                        isAssistant -> "Your SMS from before can be searched too."
                        else -> "To search your SMS from before too, make Local Assistant your phone's digital assistant. " +
                            "It then also opens with the power button."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(top = 10.dp),
                )
                if (keepMessages && !sms) {
                    OutlinedButton(
                        onClick = { if (isAssistant) smsRequest.launch(Manifest.permission.READ_SMS) else AssistantRole.openSettings(context) },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text(if (isAssistant) "Allow SMS" else "Make it your assistant") }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                val anything = photos != PhotoLibrary.Access.NONE || files || notifications
                val finish = {
                    settings.keepMessages = keepMessages && (MessageListener.allowed(context) || (isAssistant && smsGranted(context)))
                    settings.accessAsked = true
                    // Tools for files and messages come and go with these.
                    container.conversations.prefixMayHaveChanged()
                    onDone()
                }
                if (anything) {
                    Button(
                        onClick = finish,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = AppColors.OnAccent),
                    ) { Text("Continue") }
                } else {
                    TextButton(onClick = finish) { Text("Skip for now") }
                }
            }
        }
    }
}

/** One thing to allow: what it's for, then its button, or that it's allowed. */
@Composable
private fun AccessCard(
    title: String,
    body: String,
    allowed: String?,
    action: String,
    onAllow: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    GlassCard(padding = 16.dp) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = AppColors.TextPrimary)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
        if (allowed != null) {
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(18.dp))
                Text(allowed, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextPrimary, modifier = Modifier.padding(start = 8.dp))
            }
        } else {
            Button(
                onClick = onAllow,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = AppColors.OnAccent),
            ) { Text(action) }
        }
        extra()
    }
}

private fun smsGranted(context: android.content.Context): Boolean =
    context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
