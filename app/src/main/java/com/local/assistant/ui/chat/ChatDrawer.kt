package com.local.assistant.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.local.assistant.data.db.ChatEntity
import com.local.assistant.ui.theme.AppColors

@Composable
fun ChatDrawer(
    chats: List<ChatEntity>,
    activeChatId: Long?,
    onClose: () -> Unit,
    onNewChat: () -> Unit,
    onSelectChat: (Long) -> Unit,
    onDeleteChat: (Long) -> Unit,
    onOpenMemory: () -> Unit,
    onOpenSettings: () -> Unit,
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
                    "“${chat.title}” and its messages will be deleted for good. What the assistant learned " +
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // A way back that doesn't depend on knowing to swipe or tap outside.
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Close chat history", tint = AppColors.TextPrimary)
            }
            Text("Chats", style = MaterialTheme.typography.titleMedium, color = AppColors.TextPrimary)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onNewChat)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, tint = AppColors.TextPrimary)
            Text(
                text = "New chat",
                style = MaterialTheme.typography.labelLarge,
                color = AppColors.TextPrimary,
                modifier = Modifier.padding(start = 12.dp),
            )
        }

        HorizontalDivider(color = AppColors.Border)

        if (chats.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No chats yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp,
                    vertical = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(chats, key = { it.id }) { chat ->
                    ChatRow(
                        chat = chat,
                        selected = chat.id == activeChatId,
                        onClick = { onSelectChat(chat.id) },
                        onDelete = { deleting = chat },
                    )
                }
            }
        }

        HorizontalDivider(color = AppColors.Border)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenMemory)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "What I know about you",
                style = MaterialTheme.typography.labelLarge,
                color = AppColors.TextSecondary,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenSettings)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Settings, contentDescription = null, tint = AppColors.TextSecondary)
            Text(
                text = "Settings",
                style = MaterialTheme.typography.labelLarge,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun ChatRow(
    chat: ChatEntity,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) AppColors.SurfaceMuted else AppColors.Background)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = chat.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = AppColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Outlined.DeleteOutline,
                contentDescription = "Delete chat",
                tint = AppColors.TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
