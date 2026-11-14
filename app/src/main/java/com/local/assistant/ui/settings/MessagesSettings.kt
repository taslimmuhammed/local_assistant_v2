package com.local.assistant.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.local.assistant.assist.AssistantRole
import com.local.assistant.away.MessageListener
import com.local.assistant.data.prefs.SettingsStore
import com.local.assistant.inbox.Inbox
import com.local.assistant.inbox.InboxMessage
import com.local.assistant.inbox.KeptApp
import com.local.assistant.inbox.SmsSync
import com.local.assistant.ui.theme.AppColors
import com.local.assistant.ui.theme.AppSwitch
import com.local.assistant.ui.theme.Glass
import com.local.assistant.ui.theme.glass
import kotlinx.coroutines.launch

/** How the main page sums up Messages. */
internal fun messagesSummary(settings: SettingsStore, smsSync: SmsSync, context: Context): String = when {
    !settings.keepMessages -> "Off · ask what someone sent you"
    smsSync.allowed() && MessageListener.allowed(context) -> "On · notifications and SMS"
    smsSync.allowed() -> "On · SMS only, needs notification access for chat apps"
    MessageListener.allowed(context) -> "On · from notifications"
    else -> "On · needs notification access"
}

/**
 * Messages: keeping what arrives for check_inbox, the access it needs (notification access, and
 * the SMS inbox while the app is the assistant), which apps it keeps, and deleting what's kept.
 * [onToolsChanged] is called when check_inbox comes or goes, so the next chat is told.
 */
@Composable
internal fun MessagesSettings(settings: SettingsStore, inbox: Inbox, smsSync: SmsSync, onToolsChanged: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(settings.keepMessages) }
    var notifications by remember { mutableStateOf(MessageListener.allowed(context)) }
    var isAssistant by remember { mutableStateOf(AssistantRole.isDefault(context)) }
    var sms by remember { mutableStateOf(smsGranted(context)) }
    var fromNotifications by remember { mutableIntStateOf(0) }
    var fromSms by remember { mutableIntStateOf(0) }
    var apps by remember { mutableStateOf(emptyList<KeptApp>()) }
    var refreshed by remember { mutableIntStateOf(0) }
    val retention by settings.observeHistoryRetentionDays().collectAsStateWithLifecycle(initialValue = settings.historyRetentionDays)
    val ignored by settings.observeInboxIgnoredApps().collectAsStateWithLifecycle(initialValue = settings.inboxIgnoredApps)

    // Checked again on the way back from Android's pages, where access is given.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            notifications = MessageListener.allowed(context)
            isAssistant = AssistantRole.isDefault(context)
            sms = smsGranted(context)
            if (on) onToolsChanged()
            refreshed++
        }
    }
    LaunchedEffect(refreshed) {
        fromNotifications = inbox.count(InboxMessage.Source.NOTIFICATION)
        fromSms = inbox.count(InboxMessage.Source.SMS)
        apps = inbox.apps()
    }
    val smsRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        sms = granted
        if (granted) {
            smsSync.syncSoon()
            onToolsChanged()
        }
    }

    Text(
        "Ask what someone sent you, like “what did Rahul say about the trip?”. Messages that arrive are kept on this phone " +
            "and searched there; nothing leaves it. Ads and one-time codes are left out.",
        style = MaterialTheme.typography.bodyMedium,
        color = AppColors.TextSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    SwitchRow(
        "Keep messages for search",
        if (on) "On: messages from chat apps, email and SMS are kept as long as your chats." else "Off: nothing new is kept.",
        checked = on,
        onCheckedChange = { turnOn ->
            on = turnOn
            settings.keepMessages = turnOn
            if (turnOn) smsSync.syncSoon()
            onToolsChanged()
        },
    )

    if (on && !notifications) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .glass(RoundedCornerShape(18.dp), Glass.Raised)
                .padding(16.dp),
        ) {
            Text("Allow notification access", style = MaterialTheme.typography.titleSmall, color = AppColors.TextPrimary)
            Text(
                "Messages from WhatsApp, Telegram, email and other apps are kept as their notifications arrive. Android asks you to " +
                    "give this yourself. Tap below, then turn on Local Assistant.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
            OutlinedButton(onClick = { openAccess(context) }, modifier = Modifier.padding(top = 10.dp)) { Text("Open notification access") }
            Text(
                "Installed from an APK and the switch is greyed out? Open App info, tap ⋮ and choose Allow restricted settings, then try again.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(top = 12.dp),
            )
            TextButton(onClick = { openAppInfo(context) }, contentPadding = PaddingValues(0.dp)) { Text("Open App info") }
        }
    }

    if (on) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("SMS", style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
            Text(
                when {
                    sms && isAssistant -> "Your SMS inbox is read too, including what came before."
                    isAssistant -> "Allow SMS to search the texts you got before, not only new ones."
                    sms -> "SMS are read only while Local Assistant is the phone's digital assistant. New texts are still kept from their notifications."
                    else -> "To search the texts you got before, make Local Assistant the phone's digital assistant; it then also opens with the power button. New texts are kept from their notifications."
                },
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
            )
            if (!(sms && isAssistant)) {
                OutlinedButton(
                    onClick = { if (isAssistant) smsRequest.launch(Manifest.permission.READ_SMS) else AssistantRole.openSettings(context) },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text(if (isAssistant) "Allow SMS" else "Make it your assistant") }
            }
        }
    }

    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = AppColors.Border)

    // SMS have their own switch above: the permission.
    val shownApps = (apps.filter { it.packageName != SmsSync.SMS_PACKAGE }.map { it.packageName to it.appLabel } + ignored.map { it to appLabelOf(context, it) })
        .distinctBy { it.first }
        .sortedBy { it.second.lowercase() }
    Text("Apps", style = MaterialTheme.typography.titleSmall, color = AppColors.TextPrimary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    Text(
        if (shownApps.isEmpty()) "Apps show up here once a message from them is kept." else "Turn an app off to stop keeping its messages; what was kept from it is deleted.",
        style = MaterialTheme.typography.bodySmall,
        color = AppColors.TextSecondary,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    for ((pkg, label) in shownApps) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
            AppSwitch(
                checked = pkg !in ignored,
                onCheckedChange = { include ->
                    settings.inboxIgnoredApps = if (include) ignored - pkg else ignored + pkg
                    if (!include) scope.launch {
                        inbox.deleteApp(pkg)
                        refreshed++
                    }
                },
            )
        }
    }

    var confirmingDelete by remember { mutableStateOf(false) }
    val total = fromNotifications + fromSms
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            when (total) {
                0 -> "Nothing is kept yet."
                else -> "$total kept: $fromNotifications from notifications, $fromSms SMS."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextPrimary,
        )
        if (total > 0) {
            OutlinedButton(onClick = { confirmingDelete = true }, modifier = Modifier.padding(top = 8.dp)) { Text("Delete kept messages") }
        }
        Text(
            if (retention <= 0) {
                "Kept for as long as your chats are, which is until you delete them (Memory and history). One-time codes and ads are never kept."
            } else {
                "Kept for as long as your chats are: $retention days (Memory and history). One-time codes and ads are never kept."
            },
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete $total kept messages?") },
            text = {
                Text(
                    "They're deleted from this app only; the messages stay in their own apps. " +
                        if (on) "New ones are still kept while this is on." else "",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    scope.launch {
                        inbox.deleteAll()
                        refreshed++
                    }
                }) { Text("Delete", color = AppColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
        )
    }
}

private fun smsGranted(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

private fun appLabelOf(context: Context, packageName: String): String =
    runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString() }
        .getOrDefault(packageName)
