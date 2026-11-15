package com.local.assistant.debug

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * The "Night evals" page ([NightEvals]): start or continue the run, and how each eval went. While
 * it runs the screen stays on, dimmed, so the phone never locks (a locked phone freezes the app).
 */
class NightEvalsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NightEvals.load(applicationContext)
        // A short check from adb. The task keeps this intent, and the page reopened after the app
        // was closed got it again and started the check over a stopped run: only a fresh one counts.
        intent.getStringExtra("only")
            ?.takeIf { savedInstanceState == null && abs(System.currentTimeMillis() - intent.getLongExtra("at", 0)) < FRESH_MS }
            ?.let { only -> NightEvals.start(applicationContext, carryOn = false, only = only.split(',').toSet()) }
        setContent {
            val state by NightEvals.state.collectAsState()
            LaunchedEffect(state.running, state.paused) { keepAwake(state.running && !state.paused) }
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Night evals", style = MaterialTheme.typography.headlineSmall)
                        Text(state.status, style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when {
                                state.running && !state.paused -> Button(onClick = NightEvals::pause) { Text("Pause") }
                                state.running -> Button(onClick = { NightEvals.resume(applicationContext) }) { Text("Resume") }
                                state.canContinue -> {
                                    Button(onClick = { NightEvals.resume(applicationContext) }) { Text("Resume") }
                                    OutlinedButton(onClick = { NightEvals.start(applicationContext, carryOn = false) }) { Text("Start again") }
                                }
                                else -> Button(onClick = { NightEvals.start(applicationContext, carryOn = false) }) {
                                    Text(if (state.folder == null) "Start" else "Start again")
                                }
                            }
                        }
                        Text(
                            "Leave the phone on this page, unused: about 4 hours of evals in all. The screen stays on, " +
                                "dimmed, while they run. Pause stops the eval under way at once, and Resume runs it " +
                                "again from its start, also after the app has closed. Below ${NightEvals.LOW_BATTERY}% battery, " +
                                "not charging, it pauses by itself. Results: Android/data/$packageName/files/night/" +
                                (state.folder?.name?.let { "$it/" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        for (row in state.rows) {
                            Text("${MARK.getValue(row.outcome)}  ${row.spec.name}  ${row.detail}", style = MaterialTheme.typography.bodyMedium)
                            for (line in row.lines) Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        NightEvals.onScreen = true
    }

    override fun onPause() {
        NightEvals.onScreen = false
        super.onPause()
    }

    private fun keepAwake(running: Boolean) {
        if (running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.also {
            it.screenBrightness = if (running) DIM else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    private companion object {
        const val DIM = 0.02f
        const val FRESH_MS = 60_000L
        val MARK = mapOf(
            NightEvals.Outcome.WAITING to "·",
            NightEvals.Outcome.RUNNING to "▶",
            NightEvals.Outcome.PASSED to "✓",
            NightEvals.Outcome.FAILED to "✗",
            NightEvals.Outcome.SKIPPED to "–",
            NightEvals.Outcome.CLOSED_THE_APP to "✗ closed the app:",
        )
    }
}
