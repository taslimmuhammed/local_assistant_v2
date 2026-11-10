package com.local.assistant.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.db.MessageEntity
import com.local.assistant.data.db.Role
import com.local.assistant.ui.privacy.HiddenDetails
import com.local.assistant.ui.privacy.rememberShown
import androidx.compose.ui.graphics.Color
import com.local.assistant.ui.theme.AppColors
import com.local.assistant.ui.theme.Glass
import com.local.assistant.ui.theme.GlassShapes
import com.local.assistant.voice.SpeechText

/**
 * User turns sit in a dark bubble on the right; model turns run full width with no bubble, on
 * the glass, which is what makes long answers readable on a phone.
 */
@Composable
fun MessageRow(
    message: MessageEntity,
    modifier: Modifier = Modifier,
    /** This reply is the one being read aloud. */
    readingAloud: Boolean = false,
    /** Gives a reply a speaker button that reads it aloud, and stops it while it is read. */
    onReadAloud: ((MessageEntity) -> Unit)? = null,
    /** Gives a reply a flag that reports it to the developer (see ReportReplyDialog). */
    onReport: ((MessageEntity) -> Unit)? = null,
) {
    when (message.role) {
        Role.USER -> UserMessage(message, modifier)
        Role.ASSISTANT -> AssistantMessage(
            text = message.text,
            incomplete = message.incomplete,
            tokensPerSecond = message.tokensPerSecond,
            timeToFirstTokenMs = message.timeToFirstTokenMs,
            readingAloud = readingAloud,
            onReadAloud = onReadAloud?.let { read -> { read(message) } },
            onReport = onReport?.let { report -> { report(message) } },
            modifier = modifier,
        )
        // Tool calls are bookkeeping, not conversation.
        Role.TOOL -> Unit
    }
}

@Composable
fun UserMessage(message: MessageEntity, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.End,
    ) {
        // A voice message written down: its words under the player, in the same bubble.
        val transcribed = message.attachmentKind == AttachmentKind.AUDIO && message.attachmentPath != null && message.text.isNotBlank()
        when (message.attachmentKind) {
            AttachmentKind.IMAGE -> message.attachmentPath?.let { AttachedImage(it) }
            AttachmentKind.AUDIO -> message.attachmentPath?.let {
                AudioAttachment(
                    it,
                    message.attachmentDurationMs,
                    mine = true,
                    below = if (transcribed) {
                        { TranscribedWords(message.text, color = Color.White) }
                    } else {
                        null
                    },
                )
            }
            null -> Unit
        }

        if (message.text.isNotBlank() && !transcribed) {
            if (message.attachmentKind != null) Spacer(Modifier.height(6.dp))
            val shown = rememberShown(message.text)
            SelectionContainer {
                Text(
                    text = shown.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .clip(GlassShapes.Mine)
                        .background(Glass.Mine)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            if (shown.hidden) HiddenDetails(Modifier.padding(top = 2.dp))
        }
    }
}

/** What was said in a voice message, with its sensitive details hidden as typed ones are. */
@Composable
fun TranscribedWords(text: String, color: Color = AppColors.TextPrimary) {
    val shown = rememberShown(text)
    Column {
        SelectionContainer {
            Text(text = shown.text, style = MaterialTheme.typography.bodyLarge, color = color)
        }
        if (shown.hidden) HiddenDetails(Modifier.padding(top = 2.dp))
    }
}

@Composable
fun AssistantMessage(
    text: String,
    /** Still being written: a number at the end is held back until it's complete. */
    streaming: Boolean = false,
    incomplete: Boolean = false,
    tokensPerSecond: Double? = null,
    timeToFirstTokenMs: Long? = null,
    readingAloud: Boolean = false,
    /** The speaker button under the reply; none while it streams, or when it has nothing to say. */
    onReadAloud: (() -> Unit)? = null,
    /** The flag beside it, to report the reply; none while it streams. */
    onReport: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        val shown = rememberShown(text, streaming)
        SelectionContainer {
            MarkdownText(shown.text)
        }
        if (shown.hidden && !streaming) HiddenDetails(Modifier.padding(top = 2.dp))

        val footnote = listOfNotNull(
            "Stopped".takeIf { incomplete },
            tokensPerSecond?.let { "%.1f tok/s".format(it) },
            timeToFirstTokenMs?.takeIf { it > 0 }?.let { "%.1fs to first token".format(it / 1000.0) },
        )
        val sayable = remember(text) { SpeechText.clean(text).any(Char::isLetterOrDigit) }
        val readButton = onReadAloud?.takeIf { !streaming && sayable }
        val reportButton = onReport?.takeIf { !streaming }
        if (readButton != null || reportButton != null) {
            // Pulled left by the button's own inset, so the first icon lines up with the reply's text.
            Row(Modifier.offset(x = -ACTION_INSET), verticalAlignment = Alignment.CenterVertically) {
                if (readButton != null) ReadAloudButton(readingAloud, readButton)
                if (reportButton != null) ReportButton(reportButton)
                if (footnote.isNotEmpty()) Footnote(footnote)
            }
        } else if (footnote.isNotEmpty()) {
            Footnote(footnote, Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun Footnote(parts: List<String>, modifier: Modifier = Modifier) {
    Text(
        text = parts.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = AppColors.TextSecondary,
        modifier = modifier,
    )
}

/** Reads the reply aloud; while it is being read, a stop button instead. */
@Composable
private fun ReadAloudButton(reading: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(ACTION_SIZE)) {
        Icon(
            if (reading) Icons.Outlined.StopCircle else Icons.AutoMirrored.Outlined.VolumeUp,
            contentDescription = if (reading) "Stop reading aloud" else "Read aloud",
            tint = if (reading) AppColors.TextPrimary else AppColors.TextSecondary,
            modifier = Modifier.size(ACTION_ICON),
        )
    }
}

@Composable
private fun ReportButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(ACTION_SIZE)) {
        Icon(
            Icons.Outlined.Flag,
            contentDescription = "Report this reply",
            tint = AppColors.TextSecondary,
            modifier = Modifier.size(ACTION_ICON),
        )
    }
}

private val ACTION_SIZE = 36.dp
private val ACTION_ICON = 18.dp
private val ACTION_INSET = (ACTION_SIZE - ACTION_ICON) / 2

/** Shown between hitting send and the first token arriving — prefill can take a few seconds. */
/**
 * The blinking dot while the reply hasn't started. With a [label] — "Loading model…" when the
 * model is still starting — it says why the wait is longer than usual.
 */
@Composable
fun ThinkingIndicator(modifier: Modifier = Modifier, label: String? = null) {
    val transition = rememberInfiniteTransition(label = "thinking")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "alpha",
    )
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .alpha(alpha)
                .size(10.dp)
                .clip(CircleShape)
                .background(AppColors.TextPrimary),
        )
        label?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}
