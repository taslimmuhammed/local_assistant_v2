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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.local.assistant.memory.embed.BundledEmbedder
import com.local.assistant.model.ModelManager
import com.local.assistant.model.formatBytes
import com.local.assistant.ui.theme.AppColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the memory search section needs from the app. */
class MemorySearchControls(
    val manager: ModelManager,
    /** Archived exchanges still waiting for a vector. */
    val remaining: StateFlow<Int?>,
    /** Name of what is installed, or of what a download would fetch. */
    val installedName: () -> String?,
    /** The embedder that comes with the app from Play, used when nothing else is installed. */
    val bundled: StateFlow<BundledEmbedder.State> = MutableStateFlow(BundledEmbedder.State.Unavailable),
    val bundledName: String = "",
    val downloadName: String,
    val downloadBytes: Long,
    /** The full name of what the download installs, as [installedName] then reports it. */
    val downloadModelName: String = "",
    /** Unloads and deletes the embedder. */
    val delete: suspend () -> Unit,
)

/**
 * What the Gemma Terms of Use ask of anyone passing on a Gemma model: the notice, and the terms
 * and the use policy to hand. Links, not copies: the app is installed from GitHub, not a store.
 */
@Composable
private fun GemmaNotice() {
    val uriHandler = LocalUriHandler.current
    Column(Modifier.padding(top = 12.dp)) {
        Text(
            text = "EmbeddingGemma: Gemma is provided under and subject to the Gemma Terms of Use found at " +
                "ai.google.dev/gemma/terms. This copy was converted to LiteRT-LM's format with 8-bit weights. " +
                "Its use is subject to the Gemma Prohibited Use Policy.",
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { runCatching { uriHandler.openUri(GEMMA_TERMS) } }) { Text("Terms of Use") }
            TextButton(onClick = { runCatching { uriHandler.openUri(GEMMA_POLICY) } }) { Text("Prohibited Use Policy") }
        }
    }
}

private const val GEMMA_TERMS = "https://ai.google.dev/gemma/terms"
private const val GEMMA_POLICY = "https://ai.google.dev/gemma/prohibited_use_policy"

/**
 * The embedding model, optional: without it recall still works on exact words (names, numbers,
 * keywords); with it, it also finds things said in other words.
 */
@Composable
fun MemorySearchSection(controls: MemorySearchControls) {
    val manager = controls.manager
    val installed by manager.installed.collectAsStateWithLifecycle()
    val transfer by manager.transfer.collectAsStateWithLifecycle()
    val error by manager.error.collectAsStateWithLifecycle()
    val remaining by controls.remaining.collectAsStateWithLifecycle()
    val bundled by controls.bundled.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(manager::importFrom)
    }

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

        val current = installed
        when {
            transfer != null -> TransferSection(transfer = transfer!!, onCancel = manager::cancel)

            current != null -> {
                Text(
                    text = "Installed · ${controls.installedName() ?: current.file.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextPrimary,
                    modifier = Modifier.padding(top = 20.dp),
                )
                Text(
                    text = formatBytes(current.sizeBytes) + (
                        remaining?.takeIf { it > 0 }?.let { " · indexing $it earlier messages" } ?: ""
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                var confirming by remember { mutableStateOf(false) }
                if (confirming) {
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                confirming = false
                                scope.launch { controls.delete() }
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
                    ) { Text("Delete search model", color = AppColors.Danger) }
                }
            }

            bundled is BundledEmbedder.State.Ready -> {
                Text(
                    text = "Included with the app · ${controls.bundledName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextPrimary,
                    modifier = Modifier.padding(top = 20.dp),
                )
                Text(
                    text = formatBytes((bundled as BundledEmbedder.State.Ready).file.length()) + (
                        remaining?.takeIf { it > 0 }?.let { " · indexing $it earlier messages" } ?: ""
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            bundled is BundledEmbedder.State.Downloading || bundled is BundledEmbedder.State.WaitingForWifi -> {
                val progress = (bundled as? BundledEmbedder.State.Downloading)
                    ?.takeIf { it.totalBytes > 0 }
                    ?.let { " · ${formatBytes(it.downloadedBytes)} of ${formatBytes(it.totalBytes)}" }
                    .orEmpty()
                Text(
                    text = if (bundled is BundledEmbedder.State.WaitingForWifi) {
                        "${controls.bundledName} comes with the app and will download on Wi-Fi."
                    } else {
                        "${controls.bundledName} is downloading with the app$progress"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(top = 20.dp),
                )
            }

            else -> Column(Modifier.padding(top = 20.dp)) {
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Load from device storage") }
                OutlinedButton(
                    onClick = manager::download,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    val resumable = manager.partialBytes()
                    Text(
                        if (resumable > 0) {
                            "Resume ${controls.downloadName} (${formatBytes(controls.downloadBytes - resumable)} left)"
                        } else {
                            "Download ${controls.downloadName} (${formatBytes(controls.downloadBytes)})"
                        },
                    )
                }
                Text(
                    text = "It downloads by itself after the chat model, from this app's GitHub release, " +
                        "with no account. Changing model re-indexes past conversations.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        // Offered, on its way or installed, EmbeddingGemma comes with its licence's notice.
        val gemma = transfer != null || current == null || controls.installedName() == controls.downloadModelName
        if (gemma && bundled !is BundledEmbedder.State.Ready) GemmaNotice()

        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.Danger,
                modifier = Modifier.padding(top = 12.dp),
            )
            TextButton(onClick = manager::clearError) { Text("Dismiss") }
        }
    }
}
