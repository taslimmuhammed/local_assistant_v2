package com.local.assistant.ui.memory

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.local.assistant.memory.core.DuplicateSuggestion
import com.local.assistant.memory.core.FactKeys
import com.local.assistant.memory.core.FactLabels
import com.local.assistant.memory.db.EventEntity
import com.local.assistant.memory.db.FactEntity
import com.local.assistant.memory.db.TaskEntity
import com.local.assistant.ui.theme.AppColors
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "What I know about you": everything the assistant remembers, where each piece came from, and
 * the controls to change it. Deleting is immediate with an undo, never an "are you sure?" — except
 * for forgetting everything, which asks twice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(viewModel: MemoryViewModel, onBack: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }

    LaunchedEffect(viewModel) {
        viewModel.notices.collect { notice ->
            val result = snackbar.showSnackbar(
                message = notice.message,
                actionLabel = if (notice.undo != null) "Undo" else null,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.undo(notice)
        }
    }

    Scaffold(
        containerColor = AppColors.Background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("What I know about you", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppColors.Background,
                    titleContentColor = AppColors.TextPrimary,
                    navigationIconContentColor = AppColors.TextPrimary,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab, containerColor = AppColors.Background, contentColor = AppColors.TextPrimary) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("About you") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Reminders & events") })
            }
            when (tab) {
                0 -> FactsTab(viewModel)
                else -> AgendaTab(viewModel)
            }
        }
    }
}

// ---- About you ----

@Composable
private fun FactsTab(viewModel: MemoryViewModel) {
    val view by viewModel.factsView.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val duplicates by viewModel.duplicates.collectAsStateWithLifecycle()
    val paused by viewModel.memoryPaused.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<FactEntity?>(null) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::search,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                placeholder = { Text("Search") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )
        }
        if (paused) item { Note("Memory is paused. Nothing new is remembered until you turn it back on below.") }
        if (duplicates.isNotEmpty() && query.isBlank()) item { DuplicatesCard(duplicates, viewModel) }

        if (view.isEmpty) {
            item {
                Note(
                    if (query.isBlank()) {
                        "Nothing yet. Tell the assistant about yourself — “my dentist is Dr. Rao” — and it shows up here."
                    } else {
                        "Nothing matches “$query”."
                    },
                )
            }
        }

        if (view.core.isNotEmpty() || query.isBlank()) {
            item { CoreHeader(view.coreTokens, view.coreCap) }
            factRows(view.core, viewModel, onEdit = { editing = it })
        }
        for ((category, facts) in view.groups) {
            item(key = "header-$category") { SectionHeader(if (category.name == "PROFILE") "About you" else FactLabels.section(category)) }
            factRows(facts, viewModel, onEdit = { editing = it })
        }
        item { Controls(viewModel) }
        item { Spacer(Modifier.height(32.dp)) }
    }

    editing?.let { fact -> EditDialog(fact, viewModel, onDone = { editing = null }) }
}

private fun LazyListScope.factRows(facts: List<FactEntity>, viewModel: MemoryViewModel, onEdit: (FactEntity) -> Unit) {
    items(facts, key = { it.id }) { fact ->
        Dismissible(onDismiss = { viewModel.delete(fact) }) {
            FactRow(fact, onClick = { onEdit(fact) }, onPin = { viewModel.togglePin(fact) })
        }
    }
}

@Composable
private fun CoreHeader(tokens: Int, cap: Int) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Always in mind", style = MaterialTheme.typography.titleSmall, color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
            Text("%,d / %,d".format(tokens, cap), style = MaterialTheme.typography.labelMedium, color = if (tokens > cap) AppColors.Danger else AppColors.TextSecondary)
        }
        LinearProgressIndicator(
            progress = { (tokens.toFloat() / cap).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            color = if (tokens > cap) AppColors.Danger else AppColors.Accent,
            trackColor = AppColors.SurfaceMuted,
        )
        Text(
            "Sent with every message. Pin a fact to add it, unpin to take it out.",
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = AppColors.TextPrimary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun FactRow(fact: FactEntity, onClick: () -> Unit, onPin: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(AppColors.Background).clickable(onClick = onClick).padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(MemoryViewModel.label(fact), style = MaterialTheme.typography.labelMedium, color = AppColors.TextSecondary)
            Text(fact.value, style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
            Text(MemoryViewModel.provenance(fact), style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary)
        }
        if (fact.subject == FactKeys.USER) {
            IconButton(onClick = onPin) {
                Icon(
                    if (fact.core) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = if (fact.core) "Unpin from always in mind" else "Pin to always in mind",
                    tint = if (fact.core) AppColors.TextPrimary else AppColors.TextSecondary,
                )
            }
        } else {
            Spacer(Modifier.padding(end = 16.dp))
        }
    }
}

@Composable
private fun EditDialog(fact: FactEntity, viewModel: MemoryViewModel, onDone: () -> Unit) {
    var value by remember(fact.id) { mutableStateOf(fact.value) }
    var source by remember(fact.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(MemoryViewModel.label(fact)) },
        text = {
            Column {
                OutlinedTextField(value = value, onValueChange = { value = it }, modifier = Modifier.fillMaxWidth())
                Text(MemoryViewModel.provenance(fact), style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 8.dp))
                if (fact.sourceMessageId != null) {
                    TextButton(onClick = { scope.launch { source = viewModel.source(fact) ?: "That message was deleted." } }) {
                        Text("Where this came from")
                    }
                }
                source?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.TextPrimary) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                viewModel.edit(fact, value)
                onDone()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

@Composable
private fun DuplicatesCard(suggestions: List<DuplicateSuggestion>, viewModel: MemoryViewModel) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .background(AppColors.SurfaceMuted, RoundedCornerShape(12.dp)).padding(12.dp),
    ) {
        Text("Possible duplicates", style = MaterialTheme.typography.titleSmall, color = AppColors.TextPrimary)
        for (suggestion in suggestions.take(MAX_SUGGESTIONS)) {
            val a = suggestion.first.replace('_', ' ')
            val b = suggestion.second.replace('_', ' ')
            Text(
                "Are “$a” and “$b” the same?",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextPrimary,
                modifier = Modifier.padding(top = 10.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Keep the longer, more specific name.
                val into = if (suggestion.second.length >= suggestion.first.length) suggestion.second else suggestion.first
                TextButton(onClick = { viewModel.merge(suggestion, into) }) { Text("Same") }
                TextButton(onClick = { viewModel.dismiss(suggestion) }) { Text("Not the same") }
            }
        }
    }
}

@Composable
private fun Controls(viewModel: MemoryViewModel) {
    val paused by viewModel.memoryPaused.collectAsStateWithLifecycle()
    val retention by viewModel.retentionDays.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var retentionMenu by remember { mutableStateOf(false) }
    var pendingRetention by remember { mutableStateOf<Int?>(null) }
    var forgetStep by remember { mutableIntStateOf(0) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { viewModel.export(it, context.contentResolver) }
    }

    Column(Modifier.fillMaxWidth().padding(top = 28.dp)) {
        HorizontalDivider(color = AppColors.Border)
        SectionHeader("Controls")
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Pause memory", style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
                Text(
                    "Chats go on as usual, but nothing from them is remembered, archived or learned. Reminders still work.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                )
            }
            Switch(checked = paused, onCheckedChange = viewModel::setPaused)
        }
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().clickable { retentionMenu = true }, verticalAlignment = Alignment.CenterVertically) {
                Text("Keep chat history", style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
                Text(retentionLabel(retention), style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
            }
            DropdownMenu(expanded = retentionMenu, onDismissRequest = { retentionMenu = false }) {
                for (days in RETENTION_CHOICES) {
                    DropdownMenuItem(text = { Text(retentionLabel(days)) }, onClick = {
                        retentionMenu = false
                        // Shortening deletes messages, so it is confirmed; lengthening is not.
                        if (days != 0 && (retention == 0 || days < retention)) pendingRetention = days else viewModel.setRetention(days)
                    })
                }
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { exporter.launch("memory-${LocalDate.now()}.json") }) { Text("Export as JSON") }
            OutlinedButton(onClick = { forgetStep = 1 }) { Text("Forget everything", color = AppColors.Danger) }
        }
    }

    pendingRetention?.let { days ->
        AlertDialog(
            onDismissRequest = { pendingRetention = null },
            title = { Text("Keep ${retentionLabel(days).lowercase()}?") },
            text = { Text("Messages older than that are deleted now, and from then on every night. Chats left empty go too.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setRetention(days)
                    pendingRetention = null
                }) { Text("Delete older messages", color = AppColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { pendingRetention = null }) { Text("Cancel") } },
        )
    }
    if (forgetStep == 1) {
        AlertDialog(
            onDismissRequest = { forgetStep = 0 },
            title = { Text("Forget everything?") },
            text = { Text("Every fact, reminder and event, the search index of past conversations and all summaries are deleted. Your chats stay, but nothing is learned from them again.") },
            confirmButton = { TextButton(onClick = { forgetStep = 2 }) { Text("Continue", color = AppColors.Danger) } },
            dismissButton = { TextButton(onClick = { forgetStep = 0 }) { Text("Cancel") } },
        )
    }
    if (forgetStep == 2) {
        AlertDialog(
            onDismissRequest = { forgetStep = 0 },
            title = { Text("This can't be undone") },
            text = { Text("Delete everything the assistant knows about you?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.forgetEverything()
                    forgetStep = 0
                }) { Text("Forget everything", color = AppColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { forgetStep = 0 }) { Text("Keep") } },
        )
    }
}

private fun retentionLabel(days: Int): String = when (days) {
    0 -> "Forever"
    365 -> "1 year"
    else -> "$days days"
}

private val RETENTION_CHOICES = listOf(0, 365, 90, 30)

// ---- Reminders & events ----

@Composable
private fun AgendaTab(viewModel: MemoryViewModel) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionHeader("Reminders") }
        if (tasks.isEmpty()) item { Note("No open reminders.") }
        items(tasks, key = { "task-${it.id}" }) { task ->
            Dismissible(onDismiss = { viewModel.deleteTask(task) }) {
                TaskRow(task, onDone = { viewModel.completeTask(task) })
            }
        }
        item { SectionHeader("Events") }
        if (events.isEmpty()) item { Note("No upcoming events.") }
        items(events, key = { "event-${it.id}" }) { event ->
            Dismissible(onDismiss = { viewModel.deleteEvent(event) }) { EventRow(event) }
        }
        item { Note("Swipe to delete. Reminders and events can also be changed by asking in a chat.") }
    }
}

@Composable
private fun TaskRow(task: TaskEntity, onDone: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(AppColors.Background).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = false, onCheckedChange = { onDone() })
        Column(Modifier.weight(1f)) {
            Text(task.title, style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
            Text(
                listOfNotNull(task.dueAt?.let { formatWhen(it, false) } ?: "No date", task.repeatRule?.let { "repeats" }).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun EventRow(event: EventEntity) {
    Column(Modifier.fillMaxWidth().background(AppColors.Background).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(event.title, style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
        Text(
            listOfNotNull(formatWhen(event.startsAt, event.allDay), event.recurrence?.let { "repeats" }).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.TextSecondary,
        )
    }
}

// ---- Shared ----

/** Swipe left to delete; the row's own list removes it, and the snackbar offers it back. */
@Composable
private fun Dismissible(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) onDismiss() },
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(AppColors.Danger).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterEnd) {
                Text("Delete", color = AppColors.Background, fontWeight = FontWeight.SemiBold)
            }
        },
    ) { content() }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
}

private val DAY_TIME = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH)
private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

private fun formatWhen(at: Long, allDay: Boolean): String {
    val time = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    return if (allDay) DAY.format(time) else DAY_TIME.format(time)
}

private const val MAX_SUGGESTIONS = 3
