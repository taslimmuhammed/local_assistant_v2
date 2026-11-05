package com.local.assistant.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.local.assistant.data.prefs.SettingsStore
import com.local.assistant.health.HealthConnectSource
import com.local.assistant.health.HealthMetric
import com.local.assistant.health.HealthPrivacyActivity
import com.local.assistant.health.HealthSource
import com.local.assistant.health.HealthTools
import com.local.assistant.ui.theme.AppColors
import com.local.assistant.ui.theme.Glass
import com.local.assistant.ui.theme.GlassShapes
import com.local.assistant.ui.theme.glass
import com.local.assistant.ui.theme.glassFieldColors
import kotlinx.coroutines.CancellationException
import java.util.Locale

/** Whether there's a Health Connect, and what the user has let the app read from it. */
internal data class HealthAccess(val availability: HealthSource.Availability, val granted: Set<String>) {
    val allowed: List<HealthMetric> get() = HealthMetric.entries.filter { metric -> metric.permissions.any { it in granted } }
    val missing: Set<String> get() = HealthMetric.ALL_PERMISSIONS - granted
}

/** [source]'s access, read again each time the screen comes back (from Health Connect's pages, say). */
@Composable
internal fun rememberHealthAccess(source: HealthSource, refresh: Int = 0): HealthAccess? {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var access by remember { mutableStateOf<HealthAccess?>(null) }
    LaunchedEffect(source, lifecycle, refresh) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val availability = source.availability()
            val granted = if (availability == HealthSource.Availability.AVAILABLE) {
                try {
                    source.granted()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptySet()
                }
            } else {
                emptySet()
            }
            access = HealthAccess(availability, granted)
        }
    }
    return access
}

/** How the main page sums up Health data. */
internal fun healthSummary(access: HealthAccess?): String = when {
    access == null -> "Steps, heart rate and sleep, from Health Connect"
    access.availability == HealthSource.Availability.NOT_INSTALLED -> "Needs Health Connect, which this phone doesn't have"
    access.availability == HealthSource.Availability.NEEDS_UPDATE -> "Health Connect needs an update"
    access.allowed.isEmpty() -> "Steps, heart rate and sleep · asked for the first time you ask"
    access.allowed.size == HealthMetric.entries.size -> "All allowed · read only, when you ask"
    else -> access.allowed.joinToString(", ") { it.label.lowercase() }.replaceFirstChar { it.uppercase() } + " allowed · read only"
}

/**
 * Health data: what the app may read from Health Connect, kind by kind, a button to allow the
 * rest (Health Connect's own screen, where each can still be left off), and Health Connect's page
 * for the app, where access is taken back.
 */
@Composable
internal fun HealthSettings(source: HealthSource, settings: SettingsStore) {
    val context = LocalContext.current
    var asked by remember { mutableIntStateOf(0) }
    val access = rememberHealthAccess(source, asked)
    val allow = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { asked++ }

    Text(
        "Ask about your steps, heart rate, sleep or workouts, and the assistant reads them from Health Connect, where your " +
            "phone's health apps and watch keep them. Read only, only when you ask, and nothing leaves the phone.",
        style = MaterialTheme.typography.bodyMedium,
        color = AppColors.TextSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    when (access?.availability) {
        null -> return
        HealthSource.Availability.NOT_INSTALLED -> {
            Notice(
                "Health Connect isn't on this phone",
                "It's built into Android 14 and later. On this phone it comes as an app from the Play Store.",
                "Get Health Connect",
            ) { openStore(context) }
            return
        }
        HealthSource.Availability.NEEDS_UPDATE -> {
            Notice("Health Connect needs an update", "This app needs a newer Health Connect than the phone has.", "Update Health Connect") { openStore(context) }
            return
        }
        HealthSource.Availability.AVAILABLE -> Unit
    }

    Column(Modifier.padding(vertical = 4.dp)) {
        HealthMetric.entries.forEach { metric ->
            val on = metric in access.allowed
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(metric.label, style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
                Text(
                    if (on) "Allowed" else "Not allowed",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (on) AppColors.TextPrimary else AppColors.TextSecondary,
                )
            }
        }
    }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (access.missing.isNotEmpty()) {
            OutlinedButton(onClick = { allow.launch(access.missing) }) { Text(if (access.allowed.isEmpty()) "Allow access" else "Allow the rest") }
        }
        OutlinedButton(onClick = { HealthConnectSource.openManageAccess(context) }) { Text("Manage access") }
    }
    Text(
        "Turned something off and the assistant no longer asks? Allow it here or in Health Connect.",
        style = MaterialTheme.typography.bodySmall,
        color = AppColors.TextSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    StepGoal(settings)
    NavRow("How health data is used", "Read only, kept on this phone, never searched online") {
        context.startActivity(Intent(context, HealthPrivacyActivity::class.java))
    }
}

/**
 * The daily step goal. Saved on Done, on leaving the page and when the screen pauses, not as it's typed: clearing "8000"
 * to type another passes through "800", a goal of its own, which stuck when the edit was left.
 */
@Composable
private fun StepGoal(settings: SettingsStore) {
    val focus = LocalFocusManager.current
    var typed by remember { mutableStateOf(String.format(Locale.ENGLISH, "%d", settings.stepGoal)) }
    val goal = HealthTools.parseStepGoal(typed)
    val latest by rememberUpdatedState(goal)
    // Also when the screen pauses: the overlay or another app may ask before the page is left.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val save = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) latest?.let { settings.stepGoal = it } }
        lifecycle.addObserver(save)
        onDispose {
            lifecycle.removeObserver(save)
            latest?.let { settings.stepGoal = it }
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Daily step goal", style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
        // The range up here, not as an error under the field, where the keyboard covers it.
        Text(
            String.format(
                Locale.ENGLISH,
                "Ask \"how many steps are left to my goal?\" and the assistant counts from this. %,d to %,d steps.",
                HealthTools.STEP_GOALS.first,
                HealthTools.STEP_GOALS.last,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary,
        )
        OutlinedTextField(
            value = typed,
            onValueChange = { text -> typed = text.filter(Char::isDigit).take(6) },
            suffix = { Text("steps") },
            isError = goal == null,
            singleLine = true,
            shape = GlassShapes.Field,
            colors = glassFieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                goal?.let { settings.stepGoal = it }
                focus.clearFocus()
            }),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

@Composable
private fun Notice(title: String, text: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .glass(RoundedCornerShape(18.dp), Glass.Raised)
            .padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = AppColors.TextPrimary)
        Text(text, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
        OutlinedButton(onClick = onAction, modifier = Modifier.padding(top = 10.dp)) { Text(action) }
    }
}

private fun openStore(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")))
    }
}
