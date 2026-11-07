package com.local.assistant.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.local.assistant.memory.db.NoteKind
import com.local.assistant.memory.db.UserNoteEntity
import com.local.assistant.ui.theme.AppColors
import com.local.assistant.ui.theme.Glass
import com.local.assistant.ui.theme.glass

/**
 * A note as it is now, under the reply that last changed it: "Shopping list" with its items to
 * tick off right there, and "Open" for the rest. Later edits, in chat or on the Notes screen, show
 * here too; only the latest reply about a note shows it, so a long chat isn't a stack of copies.
 */
@Composable
fun NotePreview(note: UserNoteEntity, onOpen: () -> Unit, onToggle: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), Glass.Pane).clickable(onClick = onOpen).padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(kindIcon(note.kind), contentDescription = null, tint = KindTint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                note.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row(
                Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onOpen).padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Open", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                Spacer(Modifier.width(3.dp))
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, tint = AppColors.TextPrimary, modifier = Modifier.size(14.dp))
            }
        }
        when (note.kind) {
            NoteKind.LIST -> {
                val open = note.items.withIndex().filter { !it.value.done }
                open.take(PREVIEW_ITEMS).forEach { (index, item) -> CheckRow(item, onToggle = { onToggle(index) }, compact = true) }
                val more = open.size - PREVIEW_ITEMS
                val ticked = note.items.size - open.size
                listOfNotNull(
                    "+$more more".takeIf { more > 0 },
                    (if (open.isEmpty() && ticked > 0) "All $ticked ticked" else "$ticked ticked").takeIf { ticked > 0 },
                    "Empty list".takeIf { note.items.isEmpty() },
                ).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 2.dp))
                }
            }
            NoteKind.NOTE -> Text(
                note.body.ifBlank { "Empty note" },
                style = MaterialTheme.typography.bodySmall,
                color = if (note.body.isBlank()) AppColors.TextSecondary else AppColors.TextPrimary,
                maxLines = PREVIEW_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val PREVIEW_ITEMS = 8
private const val PREVIEW_LINES = 6
