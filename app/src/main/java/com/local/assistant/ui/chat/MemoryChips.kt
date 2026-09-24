package com.local.assistant.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.local.assistant.memory.tools.MemoryChip
import com.local.assistant.ui.theme.AppColors
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * What the assistant just did with memory, under the reply that did it:
 * "Saved · Dentist: Dr. Rao · Undo", "Reminder · Call the CA · Tue 22 Sep, 11:00 AM · Edit".
 * Act first, undo after — nothing here asks "are you sure?".
 */
@Composable
fun MemoryChips(
    chips: List<ChatViewModel.ChipItem>,
    onUndo: (Long) -> Unit,
    onEditTime: (Long, Long) -> Unit,
    onOpenClock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (chips.isEmpty()) return
    var editing by remember { mutableStateOf<ChatViewModel.ChipItem?>(null) }

    Column(modifier.padding(horizontal = 16.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEach { item ->
            MemoryChipRow(
                item = item,
                onUndo = { onUndo(item.recordId) },
                onEdit = { editing = item },
                onOpenClock = onOpenClock,
            )
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
private fun MemoryChipRow(item: ChatViewModel.ChipItem, onUndo: () -> Unit, onEdit: () -> Unit, onOpenClock: () -> Unit) {
    val chip = item.chip
    val text = listOfNotNull(chip.label, chip.detail, chip.at?.let { formatWhen(it, chip.allDay) })
        .filter { it.isNotBlank() }
        .joinToString(" · ")
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.SurfaceMuted)
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
            // A clock alarm is the clock app's once set: that is where it is changed or deleted.
            if (chip.kind == MemoryChip.Kind.ALARM) ChipAction("Open clock", onOpenClock)
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
private fun EditTimeDialog(initial: Long, onPicked: (Long) -> Unit, onDismiss: () -> Unit) {
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
