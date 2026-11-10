package com.local.assistant.reminders

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.assistant.ui.theme.AppColors
import com.local.assistant.ui.theme.Glass
import com.local.assistant.ui.theme.GlassShapes
import com.local.assistant.ui.theme.LocalAssistantTheme
import com.local.assistant.ui.theme.blurBehind
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * A place alarm on a locked or sleeping phone: the whole screen, as the clock's alarms are, not
 * one more line among the notifications (the user, 8 Nov 2026). Opened by the alarm's
 * notification ([ReminderNotifications.showPlace]), which plays the tone; Dismiss or Next time
 * answers it and stops the tone. The lock screen stays locked behind it, frosted.
 */
class PlaceAlarmActivity : ComponentActivity() {

    private data class Alarm(val taskId: Long, val title: String, val text: String)

    private val alarm = mutableStateOf<Alarm?>(null)

    /** The system is blurring what's behind: the lock screen, or the app in use. */
    private val blurred = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        alarm.value = alarmIn(intent) ?: return finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val time = LocalTime.now().format(TIME)
        setContent {
            LocalAssistantTheme {
                alarm.value?.let { shown ->
                    PlaceAlarm(
                        title = shown.title,
                        text = shown.text,
                        time = time,
                        blurred = blurred.value,
                        onDismiss = { answer(shown, PlaceReminderReceiver.ACTION_DISMISS) },
                        onNextTime = { answer(shown, PlaceReminderReceiver.ACTION_NEXT_TIME) },
                    )
                }
            }
        }
        blurBehind(this, BLUR) { blurred.value = it }
    }

    /** Another place alarm while this one is up: the newer one. The older still rings in the shade. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        alarmIn(intent)?.let { alarm.value = it }
    }

    /** The answer goes where the notification's own buttons send theirs. */
    private fun answer(shown: Alarm, action: String) {
        sendBroadcast(
            Intent(this, PlaceReminderReceiver::class.java).setAction(action).putExtra(ReminderReceiver.EXTRA_TASK_ID, shown.taskId),
        )
        finish()
    }

    private fun alarmIn(intent: Intent): Alarm? {
        val taskId = intent.getLongExtra(ReminderReceiver.EXTRA_TASK_ID, -1L).takeIf { it > 0 } ?: return null
        return Alarm(taskId, intent.getStringExtra(EXTRA_TITLE).orEmpty(), intent.getStringExtra(EXTRA_TEXT).orEmpty())
    }

    companion object {
        private const val EXTRA_TITLE = "com.local.assistant.extra.ALARM_TITLE"
        private const val EXTRA_TEXT = "com.local.assistant.extra.ALARM_TEXT"
        private val BLUR = 40.dp
        private val TIME = DateTimeFormatter.ofPattern("h:mm a")

        fun intent(context: Context, taskId: Long, title: String, text: String): Intent =
            Intent(context, PlaceAlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(ReminderReceiver.EXTRA_TASK_ID, taskId)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text)
    }
}

@Composable
private fun PlaceAlarm(
    title: String,
    text: String,
    time: String,
    blurred: Boolean,
    onDismiss: () -> Unit,
    onNextTime: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            // Frosted where the system blurs; where it won't, the glass's own solid grey rather
            // than a sharp lock screen behind the words.
            .background(if (blurred) Color.White.copy(alpha = 0.5f) else Glass.Sheet),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Pin()
            Spacer(Modifier.height(28.dp))
            Text(time, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium), color = AppColors.TextSecondary)
            Spacer(Modifier.height(6.dp))
            Text(
                title,
                style = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
                color = AppColors.TextPrimary,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text,
                style = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
                color = AppColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1.3f))
            Column(
                Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AlarmButton("Dismiss", dark = true, onClick = onDismiss)
                AlarmButton("Remind me next time", dark = false, onClick = onNextTime)
            }
        }
    }
}

/** The place pin, in the assistant's colours, a ring breathing out from it while it rings. */
@Composable
private fun Pin() {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val grow by pulse.animateFloat(1f, 1.35f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "grow")
    val fade by pulse.animateFloat(0.55f, 0f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "fade")
    val iris = Brush.sweepGradient(Glass.Iris)
    Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(96.dp)
                .scale(grow)
                .graphicsLayer { alpha = fade }
                .border(3.dp, iris, CircleShape),
        )
        Box(
            Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(Glass.Raised)
                .border(2.dp, iris, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.LocationOn, contentDescription = null, tint = AppColors.TextPrimary, modifier = Modifier.size(44.dp))
        }
    }
}

@Composable
private fun AlarmButton(text: String, dark: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(GlassShapes.Pill)
            .then(if (dark) Modifier.background(Glass.Mine) else Modifier.background(Glass.Raised).border(1.dp, Glass.Rim, GlassShapes.Pill))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
            color = if (dark) AppColors.OnAccent else AppColors.TextPrimary,
        )
    }
}
