package com.local.assistant.ui.memory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.local.assistant.location.resized
import com.local.assistant.location.ringMetres
import com.local.assistant.location.sizedAs
import com.local.assistant.memory.db.PlaceDistance
import com.local.assistant.memory.db.TaskEntity
import com.local.assistant.memory.db.TaskPlace
import com.local.assistant.memory.tools.ReminderEdits
import com.local.assistant.ui.chat.EditTimeDialog
import com.local.assistant.ui.theme.AppColors
import com.local.assistant.ui.theme.Glass
import com.local.assistant.ui.theme.GlassIconButton
import com.local.assistant.ui.theme.GlassShapes
import com.local.assistant.ui.theme.GlassSnackbarHost
import com.local.assistant.ui.theme.glass
import com.local.assistant.ui.theme.glassFieldColors
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Every open reminder, a tap from the menu: tick one off, tap it to change it (its words, its time,
 * or how close to its place it rings),
 * or the bin to delete it. Deleting asks first, as the user wanted (5 Nov 2026).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersScreen(viewModel: MemoryViewModel, onBack: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<TaskEntity?>(null) }
    var deleting by remember { mutableStateOf<TaskEntity?>(null) }
    ShowNotices(viewModel, snackbar)

    Scaffold(
        containerColor = AppColors.Background,
        snackbarHost = { GlassSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Reminders", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    GlassIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack, Modifier.padding(start = 10.dp, end = 6.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppColors.Background,
                    titleContentColor = AppColors.TextPrimary,
                    navigationIconContentColor = AppColors.TextPrimary,
                ),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)) {
            if (tasks.isEmpty()) {
                item { Note("No reminders. Ask in any chat: “remind me to call Mum at 6”, or “remind me to buy milk near Reliance Smart”.") }
            } else {
                reminderRows(tasks, viewModel, onEdit = { editing = it }, onDelete = { deleting = it })
                item { Note("Tap a reminder to change it. Tick it when it's done; the bin, or a swipe left, deletes it.") }
            }
        }
    }
    editing?.let { task ->
        ReminderEditor(task, viewModel, onDelete = { deleting = task }, onDone = { editing = null })
    }
    deleting?.let { task -> ConfirmDelete(task, viewModel, onDone = { deleting = null }) }
}

/** What the view model reports — "Deleted “Buy milk”" — with its undo. */
@Composable
internal fun ShowNotices(viewModel: MemoryViewModel, snackbar: SnackbarHostState) {
    LaunchedEffect(viewModel) {
        viewModel.notices.collect { notice ->
            val result = snackbar.showSnackbar(
                message = notice.message,
                actionLabel = if (notice.undo != null) "Undo" else null,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.undo(notice) else viewModel.settle(notice)
        }
    }
}

/** Open reminders; [onDelete] asks before anything is deleted (a swipe springs back while it asks). */
internal fun LazyListScope.reminderRows(
    tasks: List<TaskEntity>,
    viewModel: MemoryViewModel,
    onEdit: (TaskEntity) -> Unit,
    onDelete: (TaskEntity) -> Unit,
) {
    items(tasks, key = { "task-${it.id}" }) { task ->
        Dismissible(onDismiss = { onDelete(task) }, springBack = true) {
            ReminderRow(
                task,
                onDone = { viewModel.completeTask(task) },
                onEdit = { onEdit(task) },
                onDelete = { onDelete(task) },
            )
        }
    }
}

@Composable
private fun ReminderRow(task: TaskEntity, onDone: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().glass(RowShape).clip(RowShape).clickable(onClick = onEdit).padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = false, onCheckedChange = { onDone() })
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(task.title, style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
            Text(rings(task), style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete “${task.title}”", tint = AppColors.TextSecondary)
        }
    }
}

/** "Thu 5 Nov, 6:00 PM · repeats", "Near DMart · any of the 5 nearest", "5 km before Munnar", "Near Reliance Smart · Karaparmba Road". */
private fun rings(task: TaskEntity): String {
    val place = task.place
    return when {
        task.dueAt != null -> listOfNotNull(formatWhen(task.dueAt, false), task.repeatRule?.let { "repeats" })
        place != null -> listOfNotNull(
            place.where().replaceFirstChar { it.titlecase(Locale.ROOT) },
            place.which(),
        )
        else -> listOf("No time set")
    }.joinToString(" · ")
}

/** Asks before [task] is deleted, saying which one: two reminders can share a name. */
@Composable
internal fun ConfirmDelete(task: TaskEntity, viewModel: MemoryViewModel, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Delete this reminder?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(task.title, style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
                Text(rings(task), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    viewModel.deleteTask(task)
                    onDone()
                },
            ) { Text("Delete", color = AppColors.Danger) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

/**
 * A reminder's words, and the time of one that rings at a time; for a place reminder, its pin on a
 * map and how close to it it rings. Its place stays the same place, moved on the map at most: a
 * new place is a new reminder, asked for in a chat.
 */
@Composable
internal fun ReminderEditor(task: TaskEntity, viewModel: MemoryViewModel, onDelete: () -> Unit, onDone: () -> Unit) {
    var title by remember { mutableStateOf(task.title) }
    var at by remember { mutableStateOf(task.dueAt) }
    // A place reminder's pins and circles as changed here, saved with the rest.
    var draft by remember { mutableStateOf(task.place) }
    var onMap by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Change reminder") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        problem = null
                    },
                    label = { Text("Remind me to") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = GlassShapes.Field,
                    colors = glassFieldColors(),
                )
                val place = task.place
                val shown = draft
                if (place != null && shown != null) {
                    PlacePreview(shown, onOpen = { onMap = true })
                    RingDistance(
                        original = place,
                        draft = shown,
                        onPick = {
                            draft = it
                            problem = null
                        },
                    )
                    // Geofences don't ring for a phone already inside when they're set.
                    if (shown != place) {
                        Text("Already there? It rings the next time you arrive.", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                    }
                } else {
                    OutlinedButton(onClick = { pickingTime = true }) {
                        Text(at?.let { formatWhen(it, false) } ?: "Pick a time")
                    }
                    if (task.repeatRule != null && at != null) {
                        Text("Repeats, from this time.", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                    }
                }
                problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.Danger) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    saving = true
                    problem = null
                    viewModel.editTask(task, title, at, draft.takeUnless { it == task.place }) { outcome ->
                        saving = false
                        when (outcome) {
                            is ReminderEdits.Outcome.Saved -> onDone()
                            is ReminderEdits.Outcome.Refused -> problem = outcome.why
                        }
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        onDone()
                        onDelete()
                    },
                ) { Text("Delete", color = AppColors.Danger) }
                TextButton(onClick = onDone) { Text("Cancel") }
            }
        },
    )

    val place = task.place
    val shown = draft
    if (onMap && place != null && shown != null) {
        PlaceMapEditor(
            original = place,
            start = shown,
            onDone = {
                draft = it
                onMap = false
                problem = null
            },
            onCancel = { onMap = false },
        )
    }

    if (pickingTime) {
        EditTimeDialog(
            initial = at ?: nextHour(),
            onPicked = {
                at = it
                pickingTime = false
                problem = null
            },
            onDismiss = { pickingTime = false },
        )
    }
}

/**
 * How close a place reminder rings: the circle round its pins ("within 250 m"), or for one said as
 * "5 km before Munnar", how far before arriving. [draft] is how it rings as changed so far, and
 * [original] how it rang when the editor opened.
 */
@Composable
private fun RingDistance(original: TaskPlace, draft: TaskPlace, onPick: (TaskPlace) -> Unit) {
    val before = original.distance?.beforeArriving == true
    val radii = original.spots.map { it.radiusM }
    // Branches found on the map each come with their own size: those sizes are one choice, as found.
    val asFound = original.ringMetres() == null
    val now = draft.ringMetres()
    val choices = ((if (before) BEFORE_CHOICES else CIRCLE_CHOICES) + listOfNotNull(original.ringMetres(), now)).distinct().sorted()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (before) "Rings before arriving" else "Rings within", style = MaterialTheme.typography.bodyMedium, color = AppColors.TextPrimary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (asFound) {
                DistanceChip("${metres(radii.min())}–${metres(radii.max())}", selected = now == null) { onPick(draft.sizedAs(original)) }
            }
            for (m in choices) {
                DistanceChip(metres(m), selected = m == now) { onPick(draft.resized(PlaceDistance(m, before), original)) }
            }
        }
    }
}

/** In the switches' colours, as in Settings: the choice in black, the others plainly outlined. */
@Composable
private fun DistanceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Glass.Raised,
            labelColor = AppColors.TextPrimary,
            selectedContainerColor = AppColors.Accent,
            selectedLabelColor = AppColors.OnAccent,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = AppColors.TextSecondary,
            selectedBorderColor = AppColors.Accent,
        ),
    )
}

internal fun metres(m: Int): String = PlaceDistance(m, beforeArriving = false).words()

/** 250 m is the smallest circle a map search gives a shop (`PlaceSearch.MIN_RADIUS_M`). */
private val CIRCLE_CHOICES = listOf(250, 500, 1_000, 2_000, 5_000, 10_000)

private val BEFORE_CHOICES = listOf(1_000, 2_000, 5_000, 10_000, 20_000, 50_000)

/** Where the pickers start for a reminder with no time: the top of the next hour. */
private fun nextHour(): Long =
    ZonedDateTime.now().truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
