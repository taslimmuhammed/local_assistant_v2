package com.local.assistant.ui.startup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.local.assistant.R
import com.local.assistant.llm.LlmService
import com.local.assistant.ui.theme.AppColors
import kotlinx.coroutines.delay

/**
 * Shown from launch until the model is ready, so the chat never opens on a model that isn't: a
 * message typed while it loads would only sit and wait. The model is loaded the moment the app
 * starts; this says what it is doing and how long it has been at it.
 */
@Composable
fun LoadingScreen(
    llmService: LlmService,
    onOpenSettings: () -> Unit,
) {
    val state by llmService.state.collectAsStateWithLifecycle()
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
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                color = AppColors.TextPrimary,
                modifier = Modifier.padding(bottom = 32.dp),
            )
            when (val failure = state as? LlmService.State.Failed) {
                null -> Working(backend = backend, elapsedSeconds = elapsedSeconds)
                else -> Failure(message = failure.message, onRetry = llmService::retryLoad)
            }
            TextButton(onClick = onOpenSettings, modifier = Modifier.padding(top = 24.dp)) {
                Text("Settings", color = AppColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun Working(backend: String?, elapsedSeconds: Long) {
    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp, color = AppColors.Accent)
    Text(
        text = "Starting the model",
        style = MaterialTheme.typography.titleMedium,
        color = AppColors.TextPrimary,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 24.dp),
    )
    Text(
        text = listOfNotNull(backend?.let { "On $it" }, formatElapsed(elapsedSeconds)).joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
        color = AppColors.TextSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun Failure(message: String, onRetry: () -> Unit) {
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
        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = AppColors.OnAccent),
    ) {
        Text("Try again")
    }
}

private fun formatElapsed(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    else -> "%d:%02d".format(seconds / 60, seconds % 60)
}
