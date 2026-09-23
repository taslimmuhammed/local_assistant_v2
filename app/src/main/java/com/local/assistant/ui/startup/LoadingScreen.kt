package com.local.assistant.ui.startup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.local.assistant.llm.LlmService
import com.local.assistant.llm.LoadStatus
import com.local.assistant.ui.theme.AppColors
import kotlinx.coroutines.delay

/**
 * Stands between the model being installed and the chat opening.
 *
 * The first load can genuinely take minutes, because measuring the context window means filling
 * it for real. A bare "Loading model…" is indistinguishable from a hang, so this screen always
 * says what stage it is in, how long it has been going, and offers a way out that does not
 * involve waiting.
 */
@Composable
fun LoadingScreen(
    llmService: LlmService,
    onOpenModelSettings: () -> Unit,
) {
    val state by llmService.state.collectAsStateWithLifecycle()
    val calibration by llmService.calibrationProgress.collectAsStateWithLifecycle()
    val backend by llmService.loadingBackend.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { llmService.warmUp() }

    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state is LlmService.State.Failed) {
        elapsedSeconds = 0
        while (state !is LlmService.State.Failed) {
            delay(1000)
            elapsedSeconds++
        }
    }

    Scaffold(containerColor = AppColors.Background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val failure = state as? LlmService.State.Failed) {
                null -> Working(
                    status = LoadStatus.of(state, backend, calibration),
                    elapsedSeconds = elapsedSeconds,
                    onUseSafeWindow = llmService::useSafeWindow,
                )

                else -> Failure(
                    message = failure.message,
                    onRetry = { llmService.retryLoad(remeasure = false) },
                    onRemeasure = { llmService.retryLoad(remeasure = true) },
                )
            }

            TextButton(
                onClick = onOpenModelSettings,
                modifier = Modifier.padding(top = 24.dp),
            ) {
                Text("Model settings", color = AppColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun Working(
    status: LoadStatus?,
    elapsedSeconds: Long,
    onUseSafeWindow: () -> Unit,
) {
    if (status?.fraction != null) {
        LinearProgressIndicator(
            progress = { status.fraction },
            modifier = Modifier.fillMaxWidth(),
            color = AppColors.Accent,
            trackColor = AppColors.SurfaceMuted,
        )
    } else {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            strokeWidth = 3.dp,
            color = AppColors.Accent,
        )
    }

    Text(
        text = status?.headline ?: "Starting the model",
        style = MaterialTheme.typography.titleMedium,
        color = AppColors.TextPrimary,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 24.dp),
    )

    status?.detail?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    // The single most useful signal that something is still happening.
    Text(
        text = formatElapsed(elapsedSeconds),
        style = MaterialTheme.typography.bodyMedium,
        color = AppColors.TextSecondary,
        modifier = Modifier.padding(top = 16.dp),
    )

    if (elapsedSeconds >= PATIENCE_SECONDS) {
        Text(
            text = "Measuring only happens once — after this the model starts in seconds. " +
                "You can skip it and use a smaller window instead.",
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 24.dp),
        )
        OutlinedButton(
            onClick = onUseSafeWindow,
            modifier = Modifier.padding(top = 12.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Skip — use ${LlmService.SAFE_CONTEXT_TOKENS} tokens")
        }
    }
}

@Composable
private fun Failure(
    message: String,
    onRetry: () -> Unit,
    onRemeasure: () -> Unit,
) {
    Text(
        text = "The model could not start",
        style = MaterialTheme.typography.titleMedium,
        color = AppColors.TextPrimary,
        textAlign = TextAlign.Center,
    )
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = AppColors.Danger,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 12.dp),
    )
    Button(
        onClick = onRetry,
        modifier = Modifier.padding(top = 24.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AppColors.Accent,
            contentColor = AppColors.OnAccent,
        ),
    ) {
        Text("Try again")
    }
    OutlinedButton(
        onClick = onRemeasure,
        modifier = Modifier.padding(top = 8.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text("Measure again from scratch")
    }
}

private fun formatElapsed(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    else -> "%d:%02d".format(seconds / 60, seconds % 60)
}

private const val PATIENCE_SECONDS = 20
