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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.db.MessageEntity
import com.local.assistant.data.db.Role
import com.local.assistant.ui.theme.AppColors

/**
 * User turns sit in a grey bubble on the right; model turns run full width with no bubble,
 * which is what makes long answers readable on a phone.
 */
@Composable
fun MessageRow(message: MessageEntity, modifier: Modifier = Modifier) {
    when (message.role) {
        Role.USER -> UserMessage(message, modifier)
        Role.ASSISTANT -> AssistantMessage(
            text = message.text,
            incomplete = message.incomplete,
            tokensPerSecond = message.tokensPerSecond,
            timeToFirstTokenMs = message.timeToFirstTokenMs,
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
        when (message.attachmentKind) {
            AttachmentKind.IMAGE -> message.attachmentPath?.let { AttachedImage(it) }
            AttachmentKind.AUDIO -> message.attachmentPath?.let {
                AudioAttachment(it, message.attachmentDurationMs)
            }
            null -> Unit
        }

        if (message.text.isNotBlank()) {
            if (message.attachmentKind != null) Spacer(Modifier.height(6.dp))
            SelectionContainer {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppColors.TextPrimary,
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(AppColors.SurfaceMuted)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
fun AssistantMessage(
    text: String,
    incomplete: Boolean = false,
    tokensPerSecond: Double? = null,
    timeToFirstTokenMs: Long? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        SelectionContainer {
            MarkdownText(text)
        }

        val footnote = listOfNotNull(
            "Stopped".takeIf { incomplete },
            tokensPerSecond?.let { "%.1f tok/s".format(it) },
            timeToFirstTokenMs?.takeIf { it > 0 }?.let { "%.1fs to first token".format(it / 1000.0) },
        )
        if (footnote.isNotEmpty()) {
            Text(
                text = footnote.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Shown between hitting send and the first token arriving — prefill can take a few seconds. */
@Composable
fun ThinkingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "thinking")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .alpha(alpha)
                .size(10.dp)
                .clip(CircleShape)
                .background(AppColors.TextPrimary),
        )
    }
}
