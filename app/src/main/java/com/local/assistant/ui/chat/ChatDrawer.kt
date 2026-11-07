package com.local.assistant.ui.chat

import android.text.format.DateFormat
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.assistant.data.db.ChatEntity
import com.local.assistant.ui.theme.AppColors
import com.local.assistant.ui.theme.Glass
import com.local.assistant.ui.theme.GlassMark
import com.local.assistant.ui.theme.GlassRoundButton
import com.local.assistant.ui.theme.fadingEdges
import com.local.assistant.ui.theme.liquidGlass
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

/**
 * The chat history, on a sheet of glass: the app's mark and a count, a dark "New chat" button,
 * the chats under Today, Yesterday and further back with when each was last used, and the rest
 * of the app (photos, notes, reminders, memory, settings) as tiles in two rows at the foot.
 */
@Composable
fun ChatDrawer(
    chats: List<ChatEntity>,
    activeChatId: Long?,
    onClose: () -> Unit,
    onNewChat: () -> Unit,
    onSelectChat: (Long) -> Unit,
    onDeleteChat: (Long) -> Unit,
    onOpenMemory: () -> Unit,
    onOpenReminders: () -> Unit,
    onOpenPhotos: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNotes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A chat and its messages can't be brought back, so a tap on the bin asks first.
    var deleting by remember { mutableStateOf<ChatEntity?>(null) }
    deleting?.let { chat ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this chat?") },
            text = {
                Text(
                    "“${com.local.assistant.ui.privacy.rememberShown(chat.title).text}” and its messages will be deleted for good. What the assistant learned " +
                        "from it — facts, reminders, saved images — stays, under What I know about you.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onDeleteChat(chat.id)
                }) { Text("Delete", color = AppColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    val zone = remember { ZoneId.systemDefault() }
    val today = remember(chats) { LocalDate.now(zone) }
    val sections = remember(chats, today) { chats.groupBy { HistoryPeriod.of(it.updatedAt, today, zone) } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassMark(size = 46.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Chats", style = TitleStyle, color = AppColors.TextPrimary)
                Text(
                    when (chats.size) {
                        0 -> "Nothing here yet"
                        1 -> "1 conversation"
                        else -> "${chats.size} conversations"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                )
            }
            // A way back that doesn't depend on knowing to swipe or tap outside.
            GlassRoundButton(Icons.Outlined.Close, "Close chat history", onClose, size = 40.dp, iconSize = 18.dp)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .height(52.dp)
                .clip(PillShape)
                .background(Glass.Mine)
                .liquidGlass(PillShape, Color.Transparent, sheen = 0.22f)
                .clickable(role = Role.Button, onClick = onNewChat),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, tint = AppColors.OnAccent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("New chat", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = AppColors.OnAccent)
        }

        if (chats.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Your chats will show up here",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fadingEdges(top = 12.dp, bottom = 16.dp),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                sections.forEach { (period, inPeriod) ->
                    item(key = "period-${period.name}") {
                        Text(
                            period.label.uppercase(Locale.getDefault()),
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold),
                            color = AppColors.TextSecondary,
                            modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 6.dp),
                        )
                    }
                    items(inPeriod, key = { it.id }) { chat ->
                        ChatRow(
                            chat = chat,
                            selected = chat.id == activeChatId,
                            time = whenUsed(chat.updatedAt, period, zone),
                            onClick = { onSelectChat(chat.id) },
                            onDelete = { deleting = chat },
                        )
                    }
                }
            }
        }

        // Two rows: five tiles in one cut "Reminders" and "About you" short on the phone (its text
        // is set large). What the user keeps on top, what's about them and the app below.
        Column(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DockTile(Icons.Outlined.PhotoLibrary, "Photos", Glass.Ink[0], onOpenPhotos, Modifier.weight(1f))
                DockTile(Icons.Outlined.Checklist, "Notes", Glass.Ink[2], onOpenNotes, Modifier.weight(1f))
                DockTile(Icons.Outlined.NotificationsActive, "Reminders", Glass.Ink[3], onOpenReminders, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // A brain: what the assistant knows about the user.
                DockTile(Icons.Outlined.Psychology, "About you", Glass.Ink[1], onOpenMemory, Modifier.weight(1f))
                DockTile(Icons.Outlined.Settings, "Settings", Glass.Ink[4], onOpenSettings, Modifier.weight(1f))
            }
        }
    }
}

/** When a chat was last used, said as briefly as its section allows: "08:12", "Mon", "28 Oct". */
@Composable
private fun whenUsed(at: Long, period: HistoryPeriod, zone: ZoneId): String {
    val context = LocalContext.current
    return when (period) {
        HistoryPeriod.TODAY, HistoryPeriod.YESTERDAY -> DateFormat.getTimeFormat(context).format(Date(at))
        HistoryPeriod.WEEK -> DayFormat.format(Instant.ofEpochMilli(at).atZone(zone))
        else -> DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMMM"), at).toString()
    }
}

/** The chat list's sections, by the day a chat was last used. */
internal enum class HistoryPeriod(val label: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    WEEK("Previous 7 days"),
    MONTH("Previous 30 days"),
    OLDER("Older");

    companion object {
        fun of(at: Long, today: LocalDate, zone: ZoneId): HistoryPeriod {
            val days = ChronoUnit.DAYS.between(Instant.ofEpochMilli(at).atZone(zone).toLocalDate(), today)
            return when {
                days <= 0 -> TODAY
                days == 1L -> YESTERDAY
                days < 7 -> WEEK
                days < 30 -> MONTH
                else -> OLDER
            }
        }
    }
}

@Composable
private fun ChatRow(
    chat: ChatEntity,
    selected: Boolean,
    time: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.liquidGlass(RowShape, Glass.Raised, sheen = 0.4f) else Modifier.clip(RowShape))
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.ChatBubbleOutline,
            contentDescription = null,
            tint = if (selected) Glass.Ink[0] else AppColors.TextSecondary,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = com.local.assistant.ui.privacy.rememberShown(chat.title).text,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = AppColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(time, style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary)
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Outlined.DeleteOutline,
                contentDescription = "Delete chat",
                tint = AppColors.TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** One of the ways on from the drawer: a tile of glass with a tinted icon. */
@Composable
private fun DockTile(icon: ImageVector, label: String, accent: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .liquidGlass(TileShape, Glass.Pane, sheen = 0.45f)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = AppColors.TextPrimary, maxLines = 1)
    }
}

private val DayFormat = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
private val TitleStyle = TextStyle(fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp)
private val PillShape = RoundedCornerShape(26.dp)
private val RowShape = RoundedCornerShape(16.dp)
private val TileShape = RoundedCornerShape(20.dp)
