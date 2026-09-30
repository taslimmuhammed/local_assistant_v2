package com.local.assistant.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.local.assistant.llm.LlmService
import com.local.assistant.ui.theme.AppColors

/**
 * Between the model arriving (downloaded or imported) and the first chat: the model loads into
 * memory, then reads the first chat's prompt, so the first answer comes at once. Says which of
 * the two it is on, since together they take ~15 s and a still screen would look stuck.
 */
@Composable
fun PreparingScreen(llmService: LlmService) {
    val state by llmService.state.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            color = AppColors.Accent,
            trackColor = AppColors.Border,
            strokeWidth = 3.dp,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = "Getting ready",
            style = MaterialTheme.typography.titleMedium,
            color = AppColors.TextPrimary,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            text = if (state is LlmService.State.Ready) "Reading its instructions…" else "Loading the model into memory…",
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextPrimary,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = "This happens once, with a new model, so your first answer comes right away. " +
                "It takes about 15 seconds.",
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}
