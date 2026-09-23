package com.local.assistant.ui.setup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.local.assistant.llm.CalibrationProgress
import com.local.assistant.llm.LlmService
import com.local.assistant.model.InstalledModel
import com.local.assistant.model.ModelCatalog
import com.local.assistant.model.ModelManager
import com.local.assistant.model.Transfer
import com.local.assistant.model.TransferKind
import com.local.assistant.model.formatBytes
import com.local.assistant.ui.theme.AppColors
import kotlinx.coroutines.launch

/**
 * Model management: download the published file, import one already on the device, or remove
 * what is installed. Doubles as the first-run screen when nothing is installed yet.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ModelScreen(
    modelManager: ModelManager,
    llmService: LlmService,
    onBack: (() -> Unit)?,
) {
    val installed by modelManager.installed.collectAsStateWithLifecycle()
    val transfer by modelManager.transfer.collectAsStateWithLifecycle()
    val error by modelManager.error.collectAsStateWithLifecycle()
    val engineState by llmService.state.collectAsStateWithLifecycle()
    val calibration by llmService.calibrationProgress.collectAsStateWithLifecycle()
    val contextTokens by llmService.activeContextTokens.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(modelManager::importFrom) }

    Scaffold(
        containerColor = AppColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("Model", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppColors.Background,
                    titleContentColor = AppColors.TextPrimary,
                    navigationIconContentColor = AppColors.TextPrimary,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            HorizontalDivider(color = AppColors.Border)

            Text(
                text = ModelCatalog.DISPLAY_NAME,
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.TextPrimary,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(
                text = "Runs fully offline on this device. " +
                    "About ${formatBytes(ModelCatalog.SIZE_BYTES)} of storage.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )

            when {
                transfer != null -> TransferSection(
                    transfer = transfer!!,
                    onCancel = modelManager::cancel,
                )

                installed != null -> InstalledSection(
                    installed = installed!!,
                    engineState = engineState,
                    calibration = calibration,
                    contextTokens = contextTokens,
                    // Also clears a manual override, so "skip" on the startup screen is not a
                    // one-way door out of ever measuring properly.
                    onRecalibrate = { llmService.retryLoad(remeasure = true) },
                    onDelete = {
                        scope.launch {
                            llmService.unload()
                            modelManager.deleteInstalled()
                        }
                    },
                )

                else -> NotInstalledSection(
                    resumableBytes = modelManager.partialBytes(),
                    onDownload = modelManager::download,
                    onImport = { picker.launch(arrayOf("*/*")) },
                )
            }

            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.Danger,
                    modifier = Modifier.padding(top = 20.dp),
                )
                TextButton(onClick = modelManager::clearError) { Text("Dismiss") }
            }

            Text(
                text = "Importing copies the file into the app's private storage, so make sure " +
                    "there is room for a second copy while the import runs.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(top = 32.dp, bottom = 32.dp),
            )
        }
    }
}

@Composable
private fun TransferSection(transfer: Transfer, onCancel: () -> Unit) {
    Column(Modifier.padding(top = 28.dp)) {
        Text(
            text = when (transfer.kind) {
                TransferKind.DOWNLOAD -> "Downloading"
                TransferKind.IMPORT -> "Importing"
            },
            style = MaterialTheme.typography.labelLarge,
            color = AppColors.TextPrimary,
        )
        LinearProgressIndicator(
            progress = { transfer.fraction },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            color = AppColors.Accent,
            trackColor = AppColors.SurfaceMuted,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${formatBytes(transfer.bytesDone)} / ${formatBytes(transfer.bytesTotal)}",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
            )
            transfer.etaSeconds?.let {
                Text(
                    text = "${formatBytes(transfer.bytesPerSecond)}/s · ${formatDuration(it)} left",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary,
                )
            }
        }
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.padding(top = 20.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Cancel")
        }
    }
}

@Composable
private fun InstalledSection(
    installed: InstalledModel,
    engineState: LlmService.State,
    calibration: CalibrationProgress?,
    contextTokens: Int,
    onRecalibrate: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.padding(top = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (engineState is LlmService.State.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = AppColors.TextSecondary,
                )
            }
            Text(
                text = when (engineState) {
                    is LlmService.State.Ready -> "Installed · running on ${engineState.backend}"
                    is LlmService.State.Loading -> "Installed · loading…"
                    is LlmService.State.Failed -> "Installed · failed to load"
                    else -> "Installed"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextPrimary,
                modifier = Modifier.padding(start = if (engineState is LlmService.State.Loading) 8.dp else 0.dp),
            )
        }
        Text(
            text = "${formatBytes(installed.sizeBytes)} · ${installed.file.name}",
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
        ContextSection(
            calibration = calibration,
            contextTokens = contextTokens,
            enabled = engineState !is LlmService.State.Loading && calibration == null,
            onRecalibrate = onRecalibrate,
        )

        (engineState as? LlmService.State.Failed)?.let {
            Text(
                text = it.message,
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.Danger,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        var confirming by remember { mutableStateOf(false) }
        if (confirming) {
            Text(
                text = "Delete the model file? You will need to download or import it again.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextPrimary,
                modifier = Modifier.padding(top = 20.dp),
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { confirming = false; onDelete() },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Danger),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Delete") }
                OutlinedButton(
                    onClick = { confirming = false },
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Keep") }
            }
        } else {
            OutlinedButton(
                onClick = { confirming = true },
                modifier = Modifier.padding(top = 24.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Delete model", color = AppColors.Danger)
            }
        }
    }
}

/**
 * The context window is measured, not configured: nothing in the runtime reports how much this
 * device can hold, so the app finds out by trying and keeping what survives.
 */
@Composable
private fun ContextSection(
    calibration: CalibrationProgress?,
    contextTokens: Int,
    enabled: Boolean,
    onRecalibrate: () -> Unit,
) {
    Column(Modifier.padding(top = 24.dp)) {
        Text(
            text = "Context window",
            style = MaterialTheme.typography.labelLarge,
            color = AppColors.TextPrimary,
        )

        when {
            calibration != null -> {
                Row(
                    Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = AppColors.TextSecondary,
                    )
                    Text(
                        text = when (calibration) {
                            is CalibrationProgress.Trying ->
                                "Trying ${calibration.tokens} tokens " +
                                    "(${calibration.step}/${calibration.steps})…"
                            is CalibrationProgress.Confirming ->
                                "Filling ${calibration.tokens} tokens: " +
                                    "${calibration.reached} so far…"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.TextSecondary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Text(
                    text = "This fills the window for real, so it takes a while. " +
                        "The app may restart if a size turns out to be too large — that is " +
                        "expected, and the next attempt will be smaller.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            contextTokens > 0 -> Text(
                text = "$contextTokens tokens, measured on this device",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )

            else -> Text(
                text = "Not measured yet",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        OutlinedButton(
            onClick = onRecalibrate,
            enabled = enabled,
            modifier = Modifier.padding(top = 12.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Recalibrate")
        }
    }
}

@Composable
private fun NotInstalledSection(
    resumableBytes: Long,
    onDownload: () -> Unit,
    onImport: () -> Unit,
) {
    Column(Modifier.padding(top = 28.dp)) {
        if (resumableBytes > 0) {
            Text(
                text = "${formatBytes(resumableBytes)} already downloaded. " +
                    "Downloading again resumes from there.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        Button(
            onClick = onDownload,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AppColors.Accent,
                contentColor = AppColors.OnAccent,
            ),
        ) {
            Text(if (resumableBytes > 0) "Resume download" else "Download model")
        }
        OutlinedButton(
            onClick = onImport,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Load from device storage")
        }
        Text(
            text = "Pick a ${ModelCatalog.FILE_EXTENSION} file you already have, " +
                "for example one sideloaded over USB.",
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextSecondary,
            textAlign = TextAlign.Start,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private fun formatDuration(seconds: Long): String = when {
    seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds}s"
}
