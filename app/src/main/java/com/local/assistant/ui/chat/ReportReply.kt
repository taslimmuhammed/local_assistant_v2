package com.local.assistant.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.local.assistant.chat.ReportError
import com.local.assistant.chat.ReportReason
import com.local.assistant.ui.theme.AppColors
import kotlinx.coroutines.launch

/**
 * The flag under a reply opens this: why, an optional note, and what will be sent. It stays open
 * while sending, and says so if the report didn't go, so nothing the user wrote is lost.
 */
@Composable
fun ReportReplyDialog(
    onDismiss: () -> Unit,
    /** Sends it; throws [ReportError] when it didn't arrive. */
    onSend: suspend (ReportReason, String) -> Unit,
    onSent: () -> Unit,
) {
    var reason by remember { mutableStateOf<ReportReason?>(null) }
    var note by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("Report this reply") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "What's wrong with it?",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                for (option in ReportReason.entries) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = reason == option, enabled = !sending, role = Role.RadioButton) {
                                reason = option
                                error = null
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = reason == option, onClick = null, enabled = !sending)
                        Text(option.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(NOTE_MAX) },
                    enabled = !sending,
                    label = { Text("Anything to add (optional)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    "Sent to the app's developer: this reply, your question before it, and what you " +
                        "choose here, with card numbers and the like hidden. Nothing else from your " +
                        "chats, and nothing that says who you are.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(top = 10.dp),
                )
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = reason != null && !sending,
                onClick = {
                    val chosen = reason ?: return@TextButton
                    sending = true
                    error = null
                    scope.launch {
                        try {
                            onSend(chosen, note)
                            onSent()
                        } catch (e: ReportError) {
                            error = e.message
                        } finally {
                            sending = false
                        }
                    }
                },
            ) { Text(if (sending) "Sending…" else "Send report") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !sending) { Text("Cancel") }
        },
    )
}

private const val NOTE_MAX = 1_000
