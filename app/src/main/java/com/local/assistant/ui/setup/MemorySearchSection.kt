package com.local.assistant.ui.setup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.local.assistant.model.ModelManager
import com.local.assistant.model.formatBytes
import com.local.assistant.ui.theme.AppColors
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the memory search section needs from the app. */
class MemorySearchControls(
    /** The photo model, whose text side memory search uses unless the user loaded their own. */
    val photoModel: ModelManager,
    val photoModelName: String,
    val photoModelBytes: Long,
    /** An embedder the user loaded from storage instead. */
    val imported: ModelManager,
    /** Name of the one loaded from storage. */
    val importedName: () -> String?,
    /** Archived exchanges still waiting for a vector. */
    val remaining: StateFlow<Int?>,
    /** Unloads and deletes the one loaded from storage; memory search goes back to the photo model. */
    val deleteImported: suspend () -> Unit,
)

/**
 * The embedding model, optional: without it recall still works on exact words (names, numbers,
 * keywords); with it, it also finds things said in other words. It is the photo model's text
 * side, so photo search and memory search share one download and one copy in memory.
 */
@Composable
fun MemorySearchSection(controls: MemorySearchControls) {
    val photo = controls.photoModel
    val photoInstalled by photo.installed.collectAsStateWithLifecycle()
    val photoTransfer by photo.transfer.collectAsStateWithLifecycle()
    val photoError by photo.error.collectAsStateWithLifecycle()
    val imported = controls.imported
    val importedInstalled by imported.installed.collectAsStateWithLifecycle()
    val importTransfer by imported.transfer.collectAsStateWithLifecycle()
    val importError by imported.error.collectAsStateWithLifecycle()
    val remaining by controls.remaining.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(imported::importFrom)
    }
    val indexing = remaining?.takeIf { it > 0 }?.let { " · indexing $it earlier messages" }.orEmpty()

    Column(Modifier.padding(top = 32.dp)) {
        HorizontalDivider(color = AppColors.Border)
        Text(
            text = "Memory search",
            style = MaterialTheme.typography.titleMedium,
            color = AppColors.TextPrimary,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            text = "Optional. Lets the assistant find earlier conversations by meaning, not only by " +
                "exact words. Runs offline on the CPU.",
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(top = 6.dp),
        )

        val own = importedInstalled
        val shared = photoInstalled
        when {
            importTransfer != null -> TransferSection(transfer = importTransfer!!, onCancel = imported::cancel)

            own != null -> {
                Text(
                    text = "Your own · ${controls.importedName() ?: own.file.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextPrimary,
                    modifier = Modifier.padding(top = 20.dp),
                )
                Text(
                    text = formatBytes(own.sizeBytes) + indexing,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                var confirming by remember { mutableStateOf(false) }
                if (confirming) {
                    Text(
                        text = "Memory search goes back to ${controls.photoModelName}, and past conversations are indexed again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.TextSecondary,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                confirming = false
                                scope.launch { controls.deleteImported() }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Danger),
                            shape = RoundedCornerShape(12.dp),
                        ) { Text("Delete") }
                        OutlinedButton(onClick = { confirming = false }, shape = RoundedCornerShape(12.dp)) { Text("Keep") }
                    }
                } else {
                    OutlinedButton(
                        onClick = { confirming = true },
                        modifier = Modifier.padding(top = 16.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("Delete your model", color = AppColors.Danger) }
                }
            }

            photoTransfer != null -> TransferSection(transfer = photoTransfer!!, onCancel = photo::cancel)

            shared != null -> {
                Text(
                    text = "On · ${controls.photoModelName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextPrimary,
                    modifier = Modifier.padding(top = 20.dp),
                )
                Text(
                    text = "The photo search model, shared: no extra download or memory" + indexing,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                TextButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Use your own model instead")
                }
            }

            else -> Column(Modifier.padding(top = 20.dp)) {
                Button(
                    onClick = photo::download,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    val resumable = photo.partialBytes()
                    val size = controls.photoModelBytes
                    Text(
                        if (resumable > 0) {
                            "Resume ${controls.photoModelName} (${formatBytes(size - resumable)} left)"
                        } else {
                            "Download ${controls.photoModelName} (${formatBytes(size)})"
                        },
                    )
                }
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Load your own from device storage") }
                Text(
                    text = "The same model searches your photos. It downloads by itself after the chat " +
                        "model, with no account. Changing model re-indexes past conversations.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        listOfNotNull(importError?.let { it to imported }, photoError?.let { it to photo }).forEach { (message, manager) ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.Danger,
                modifier = Modifier.padding(top = 12.dp),
            )
            TextButton(onClick = manager::clearError) { Text("Dismiss") }
        }
    }
}
