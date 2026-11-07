package com.local.assistant.ui.chat

import com.local.assistant.ui.gallery.PhotoResults
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.local.assistant.memory.db.TaskEntity
import com.local.assistant.memory.db.UserNoteEntity
import com.local.assistant.memory.tools.MemoryChip
import com.local.assistant.ui.memory.PlacePreview
import com.local.assistant.ui.notes.NotePreview
import com.local.assistant.ui.theme.AppColors
import com.local.assistant.ui.theme.Glass
import com.local.assistant.ui.theme.glass
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the assistant just did with memory, under the reply that did it:
 * "Saved · Dentist: Dr. Rao · Undo", "Reminder · Call the CA · Tue 22 Sep, 11:00 AM · Edit".
 * Act first, undo after — nothing here asks "are you sure?".
 *
 * A place reminder still open is shown as it is now, on a small map; a tap opens it to move its
 * pin, resize its circle, rename or delete it, as on the Reminders screen (the user asked for the
 * map in the reply, 5 Nov 2026).
 *
 * A note or list the reply made or changed is shown the same way, as it is now, with its items to
 * tick off: under the latest reply about it only.
 */
@Composable
fun MemoryChips(
    chips: List<ChatViewModel.ChipItem>,
    onUndo: (Long) -> Unit,
    onEditTime: (Long, Long) -> Unit,
    onOpenClock: () -> Unit,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Open reminders that ring at a place, by id. */
    placeReminders: Map<Long, TaskEntity> = emptyMap(),
    /** Opens a place reminder's editor from its map; null where there is no map (the overlay). */
    onOpenReminder: ((TaskEntity) -> Unit)? = null,
    /** The user's notes as they are now, by id. */
    notes: Map<Long, UserNoteEntity> = emptyMap(),
    /** The chips (by record id) under which their note is shown: each note's latest. */
    notePreviews: Set<Long> = emptySet(),
    /** Opens a note on the Notes screen; null where notes aren't shown (the overlay). */
    onOpenNote: ((UserNoteEntity) -> Unit)? = null,
    /** Ticks or unticks a list's item: the note's id and the item's place in it. */
    onToggleNoteItem: (Long, Int) -> Unit = { _, _ -> },
) {
    if (chips.isEmpty()) return
    var editing by remember { mutableStateOf<ChatViewModel.ChipItem?>(null) }

    Column(modifier.padding(horizontal = 16.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEach { item ->
            val photos = item.chip.photos
            if (item.chip.kind == MemoryChip.Kind.PHOTOS && photos != null) {
                PhotoResults(photos)
                return@forEach
            }
            val reminder = item.taskId?.takeIf { !item.undone && onOpenReminder != null }?.let(placeReminders::get)
            val place = reminder?.place
            // As it is now: renamed, its circle changed or a pin dropped, since the reply.
            val shown = if (reminder != null && place != null) {
                val now = item.chip.copy(detail = reminder.title, place = place.where(), note = place.which()?.replaceFirstChar { it.titlecase(Locale.ROOT) })
                item.copy(chip = now)
            } else {
                item
            }
            MemoryChipRow(
                item = shown,
                onUndo = { onUndo(item.recordId) },
                onEdit = { editing = item },
                onOpenClock = onOpenClock,
                onOpenLink = onOpenLink,
            )
            if (reminder != null && place != null && onOpenReminder != null) {
                PlacePreview(
                    place,
                    onOpen = { onOpenReminder(reminder) },
                    // As wide as a photo sent in the chat, from the left like the reply.
                    modifier = Modifier.widthIn(max = 260.dp),
                    action = "Edit",
                    actionIcon = Icons.Outlined.Edit,
                )
            }
            val note = item.chip.noteId?.takeIf { onOpenNote != null && item.recordId in notePreviews }?.let(notes::get)
            if (note != null && onOpenNote != null) {
                NotePreview(
                    note,
                    onOpen = { onOpenNote(note) },
                    onToggle = { index -> onToggleNoteItem(note.id, index) },
                    modifier = Modifier.widthIn(max = 260.dp),
                )
            }
        }
    }

    editing?.let { item ->
        EditTimeDialog(
            initial = item.chip.at ?: System.currentTimeMillis(),
            onPicked = { at ->
                editing = null
                onEditTime(item.recordId, at)
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun MemoryChipRow(
    item: ChatViewModel.ChipItem,
    onUndo: () -> Unit,
    onEdit: () -> Unit,
    onOpenClock: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val chip = item.chip
    val text = listOfNotNull(chip.label, chip.detail, chip.at?.let { formatWhen(it, chip.allDay) } ?: chip.place)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
    Row(
        modifier = Modifier
            .glass(RoundedCornerShape(14.dp), Glass.Raised)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = if (item.undone) AppColors.TextSecondary else AppColors.TextPrimary,
                textDecoration = if (item.undone) TextDecoration.LineThrough else null,
            )
            chip.note?.takeIf { !item.undone }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary)
            }
        }
        if (item.undone) {
            Text("Undone", style = MaterialTheme.typography.labelMedium, color = AppColors.TextSecondary)
        } else {
            if (chip.editable) ChipAction("Edit", onEdit)
            if (item.canUndo) ChipAction("Undo", onUndo)
            // A clock alarm or timer is the clock app's once set: that is where it is changed or stopped.
            if (chip.kind == MemoryChip.Kind.ALARM || chip.kind == MemoryChip.Kind.TIMER) ChipAction("Open clock", onOpenClock)
            chip.link?.let { link -> ChipAction("Open") { onOpenLink(link) } }
        }
    }
}

@Composable
private fun ChipAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = AppColors.TextPrimary,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(4.dp),
    )
}

/** Pick a date, then a time; together they are the new moment. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditTimeDialog(initial: Long, onPicked: (Long) -> Unit, onDismiss: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(initial).atZone(zone)
    // The date picker works in UTC midnights.
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = start.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    val timeState = rememberTimePickerState(initialHour = start.hour, initialMinute = start.minute)
    var pickingTime by remember { mutableStateOf(false) }

    if (!pickingTime) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = { pickingTime = true }) { Text("Next") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        ) {
            DatePicker(state = dateState)
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    onClick = {
                        val date = dateState.selectedDateMillis
                            ?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                            ?: LocalDate.now(zone)
                        onPicked(date.atTime(LocalTime.of(timeState.hour, timeState.minute)).atZone(zone).toInstant().toEpochMilli())
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            text = { TimePicker(state = timeState, modifier = Modifier.fillMaxWidth()) },
        )
    }
}

private val DAY_TIME = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a")
private val DAY = DateTimeFormatter.ofPattern("EEE d MMM")

private fun formatWhen(at: Long, allDay: Boolean): String {
    val time = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    return if (allDay) DAY.format(time) else DAY_TIME.format(time)
}
