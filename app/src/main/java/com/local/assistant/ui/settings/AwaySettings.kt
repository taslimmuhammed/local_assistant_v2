package com.local.assistant.ui.settings

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import com.local.assistant.away.AwayBrief
import com.local.assistant.away.BriefActivity
import com.local.assistant.away.MessageListener
import com.local.assistant.data.prefs.SettingsStore
import com.local.assistant.ui.theme.AppColors
import com.local.assistant.ui.theme.glass
import com.local.assistant.ui.theme.Glass
import com.local.assistant.ui.theme.AppSwitch
import kotlinx.coroutines.launch

/** How the main page sums up "While you were away". */
internal fun awaySummary(settings: SettingsStore, context: Context): String = when {
    !settings.awayBrief -> "Off · who called and who's waiting, when you're back"
    !MessageListener.allowed(context) -> "On · needs notification access"
    else -> "On · after ${minutesLabel(settings.awayMinutes)} away"
}

/**
 * "While you were away": the switch, the notification access Android asks the user to give
 * themselves, when it opens, which apps it reads, and deleting what it has kept.
 */
@Composable
internal fun AwaySettings(settings: SettingsStore, awayBrief: AwayBrief) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(settings.awayBrief) }
    var allowed by remember { mutableStateOf(MessageListener.allowed(context)) }
    var isAssistant by remember { mutableStateOf(AssistantRole.isDefault(context)) }
    // Checked again on the way back from Android's pages, where access is given.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            allowed = MessageListener.allowed(context)
            isAssistant = AssistantRole.isDefault(context)
        }
    }

    Text(
        "When you come back to your phone, a small card shows who called, who's waiting on a reply and what's worth knowing, " +
            "with replies ready to send. The model on your phone reads the notifications; nothing leaves it.",
        style = MaterialTheme.typography.bodyMedium,
        color = AppColors.TextSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    SwitchRow(
        "While you were away",
        if (on) "On: reads incoming notifications and keeps them two days." else "Off: nothing is read, and nothing is kept.",
        checked = on,
        onCheckedChange = { turnOn ->
            on = turnOn
            settings.awayBrief = turnOn
            // Off means off: what was kept goes too.
            if (!turnOn) scope.launch { awayBrief.forgetAll() }
        },
    )
    if (!on) return

    if (!allowed) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .glass(RoundedCornerShape(18.dp), Glass.Raised)
                .padding(16.dp),
        ) {
            Text("Allow notification access", style = MaterialTheme.typography.titleSmall, color = AppColors.TextPrimary)
            Text(
                "Android asks you to give this yourself. Tap below, then turn on Local Assistant.",
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
            TextButton(onClick = { openAppInfo(context) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("Open App info")
            }
        }
    }

    var popUp by remember { mutableStateOf(settings.awayPopUp) }
    SwitchRow(
        "Open it when I unlock",
        when {
            !popUp -> "Off: it waits in a quiet notification."
            isAssistant -> "On: it opens by itself when someone is waiting on you."
            else -> "On, once Local Assistant is the phone's assistant (Calling the assistant → Power button). Until then, a quiet notification."
        },
        checked = popUp,
        onCheckedChange = {
            popUp = it
            settings.awayPopUp = it
        },
    )

    var minutes by remember { mutableIntStateOf(settings.awayMinutes) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Away for at least", style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
        Text("Shorter than this, unlocking doesn't bring up the card.", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (choice in AwayBrief.AWAY_CHOICES) {
                FilterChip(
                    selected = choice == minutes,
                    onClick = {
                        minutes = choice
                        settings.awayMinutes = choice
                    },
                    label = { Text(minutesLabel(choice)) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Glass.Raised,
                        labelColor = AppColors.TextPrimary,
                        selectedContainerColor = AppColors.Accent,
                        selectedLabelColor = AppColors.OnAccent,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = choice == minutes,
                        borderColor = AppColors.TextSecondary,
                        selectedBorderColor = AppColors.Accent,
                    ),
                )
            }
        }
    }

    NavRow("See it now", "Everything from today that you haven't dealt with yet.") {
        context.startActivity(BriefActivity.intent(context, newOnly = false))
    }

    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = AppColors.Border)

    val apps by awayBrief.apps.collectAsStateWithLifecycle(initialValue = emptyList())
    val ignored by settings.observeAwayIgnoredApps().collectAsStateWithLifecycle(initialValue = settings.awayIgnoredApps)
    val shownApps = (apps.map { it.packageName to it.appLabel } + ignored.map { it to appLabel(context, it) })
        .distinctBy { it.first }
        .sortedBy { it.second.lowercase() }
    Text("Apps", style = MaterialTheme.typography.titleSmall, color = AppColors.TextPrimary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    Text(
        if (shownApps.isEmpty()) "Apps show up here once they've sent something." else "Turn an app off to leave it out; what it sent is deleted.",
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
                    settings.awayIgnoredApps = if (include) ignored - pkg else ignored + pkg
                    if (!include) scope.launch { awayBrief.forgetApp(pkg) }
                },
            )
        }
    }

    var deleted by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        OutlinedButton(onClick = {
            scope.launch {
                awayBrief.forgetAll()
                deleted = true
            }
        }) { Text(if (deleted) "Deleted" else "Delete what's been kept") }
        Text(
            "Messages are kept for two days, then deleted. One-time codes are never kept.",
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private fun minutesLabel(minutes: Int): String = if (minutes < 60) "$minutes min" else "${minutes / 60} h"

private fun appLabel(context: Context, packageName: String): String =
    runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString() }
        .getOrDefault(packageName)

/** Android's page for this app's notification access, or the list of all apps with it. */
private fun openAccess(context: Context) {
    val component = ComponentName(context, MessageListener::class.java)
    val detail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
    } else {
        null
    }
    val list = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    for (intent in listOfNotNull(detail, list)) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
            // The next one.
        }
    }
}

private fun openAppInfo(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
