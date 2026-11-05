package com.local.assistant.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.local.assistant.device.PermissionBroker
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * A tool asking for a permission it needs now (contacts for "call amma", health data for "how
 * many steps today?"): answered with the system's dialog, or Health Connect's own screen, and the
 * tool carries on with the result, after the app's own reason when the request has one (a place
 * reminder's location). Only while this screen is started, and not when [elsewhere] says another
 * screen in front answers it. [onAsking] is true while a dialog is up.
 */
@Composable
fun ToolPermissionRequests(
    requests: SharedFlow<PermissionBroker.Request>,
    elsewhere: () -> Boolean = { false },
    onAsking: (Boolean) -> Unit = {},
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val asking by rememberUpdatedState(onAsking)
    var pending by remember { mutableStateOf<PermissionBroker.Request?>(null) }
    fun answer(granted: Boolean) {
        pending?.answer?.complete(granted)
        pending = null
    }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { answer(it) }
    val several = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        answer(granted.isNotEmpty() && granted.values.all { it })
    }
    val health = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        answer(granted.containsAll(pending?.permissions.orEmpty()))
    }
    // A request with a reason is explained first; Android's dialog follows on Continue.
    var explaining by remember { mutableStateOf<PermissionBroker.Request?>(null) }
    fun launch(request: PermissionBroker.Request) = when {
        request.health -> health.launch(request.permissions)
        request.permissions.size > 1 -> several.launch(request.permissions.toTypedArray())
        else -> runtime.launch(request.permission)
    }
    LaunchedEffect(requests, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            requests.collect { request ->
                if (elsewhere() || !request.claim()) return@collect
                pending = request
                asking(true)
                // Answered here, or given up on by the broker: either way the dialog is done with.
                scope.launch {
                    request.answer.join()
                    if (explaining === request) explaining = null
                    asking(false)
                }
                if (request.rationale != null) explaining = request else launch(request)
            }
        }
    }
    explaining?.let { request ->
        val rationale = request.rationale ?: return@let
        AlertDialog(
            onDismissRequest = {
                explaining = null
                answer(false)
            },
            title = { Text(rationale.title) },
            text = { Text(rationale.text) },
            confirmButton = {
                TextButton(
                    onClick = {
                        explaining = null
                        launch(request)
                    },
                ) { Text("Continue") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        explaining = null
                        answer(false)
                    },
                ) { Text("Not now") }
            },
        )
    }
}
