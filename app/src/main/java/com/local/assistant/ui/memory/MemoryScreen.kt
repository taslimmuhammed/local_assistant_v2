package com.local.assistant.ui.memory

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.local.assistant.memory.core.DuplicateSuggestion
import com.local.assistant.memory.core.FactKeys
import com.local.assistant.memory.core.FactLabels
import com.local.assistant.memory.db.EventEntity
import com.local.assistant.memory.db.FactEntity
import com.local.assistant.memory.db.NoteEntity
import com.local.assistant.memory.db.TaskEntity
import com.local.assistant.ui.chat.AttachedImage
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
    val addingImage by viewModel.addingImage.collectAsStateWithLifecycle()
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::addSavedImage)
    }

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

    Scaffold(
        containerColor = AppColors.Background,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            // The same as sending a photo and saying "remember this": the model names and
            // describes it, and it is kept with what it wrote.
            if (tab == 2) {
                FloatingActionButton(
                    onClick = {
                        if (!addingImage) pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    containerColor = AppColors.Accent,
                    contentColor = AppColors.OnAccent,
                ) {
                    if (addingImage) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = AppColors.OnAccent)
                    } else {
                        Icon(Icons.Filled.Add, contentDescription = "Add an image")
                    }
                }
            }
        },
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
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Images") })
            }
            when (tab) {
                0 -> FactsTab(viewModel)
                1 -> AgendaTab(viewModel)
                else -> ImagesTab(viewModel)
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
        item { Note("Pause memory, how long chats are kept, export and forget everything are in Settings.") }
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

// ---- Saved images ----

@Composable
private fun ImagesTab(viewModel: MemoryViewModel) {
    val notes by viewModel.savedImages.collectAsStateWithLifecycle()
    val adding by viewModel.addingImage.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<NoteEntity?>(null) }
    var deleting by remember { mutableStateOf<NoteEntity?>(null) }
    // Room at the bottom so the "+" never covers the last row.
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
        item { SectionHeader("Saved images") }
        if (adding) item { AddingImageRow() }
        if (notes.isEmpty() && !adding) {
            item { Note("Nothing saved yet. Tap + to add a photo, or send one in a chat and say “remember this” — a receipt, a label, a whiteboard — and ask about it any time later.") }
        }
        items(notes, key = { "note-${it.id}" }) { note ->
            Dismissible(onDismiss = { viewModel.deleteSavedImage(note) }) {
                SavedImageRow(note, onClick = { open = note }, onDelete = { deleting = note })
            }
        }
        if (notes.isNotEmpty()) item { Note("Ask about a saved image in any chat and the assistant looks at it again. Tap one to see it; the bin (or a swipe left) deletes it.") }
    }
    open?.let { note -> SavedImageDialog(note, onDone = { open = null }, onDelete = { deleting = note }) }
    deleting?.let { note ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${note.title}”?") },
            text = {
                Text(
                    "The saved image and what was noted about it are removed, and the assistant won't recall it any more. " +
                        "If it came from a chat, it stays in that chat.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSavedImage(note)
                    deleting = null
                    open = null
                }) { Text("Delete", color = AppColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

/** Where the new image will appear, while the model reads it. */
@Composable
private fun AddingImageRow() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(AppColors.SurfaceMuted), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = AppColors.Accent)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("Looking at the image…", style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
            Text("Reading everything in it to save with it. This can take a little while.", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        }
    }
}

@Composable
private fun SavedImageRow(note: NoteEntity, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(AppColors.Background).clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(note.imagePath)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(note.title, style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(note.details, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Saved " + formatWhen(note.createdAt, true), style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete “${note.title}”", tint = AppColors.TextSecondary)
        }
    }
}

/** A small square crop, decoded small: these rows can be many. */
@Composable
private fun Thumbnail(path: String?) {
    val bitmap = remember(path) {
        path?.let {
            runCatching { BitmapFactory.decodeFile(it, BitmapFactory.Options().apply { inSampleSize = THUMB_SAMPLE })?.asImageBitmap() }.getOrNull()
        }
    }
    val modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp))
    if (bitmap == null) {
        Box(modifier.background(AppColors.SurfaceMuted))
    } else {
        Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    }
}

@Composable
private fun SavedImageDialog(note: NoteEntity, onDone: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(note.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                note.imagePath?.let { AttachedImage(it, maxHeight = 320) }
                Text(note.details, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextPrimary, modifier = Modifier.padding(top = 12.dp))
                Text("Saved " + formatWhen(note.createdAt, false), style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Close") } },
        dismissButton = { TextButton(onClick = onDelete) { Text("Delete", color = AppColors.Danger) } },
    )
}

private const val THUMB_SAMPLE = 4

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
