package com.local.assistant.ui.chat

import com.google.gson.Gson
import com.local.assistant.data.db.MessageEntity
import com.local.assistant.data.db.Role
import com.local.assistant.memory.db.TaskEntity
import com.local.assistant.memory.tools.MemoryChip
import com.local.assistant.memory.tools.ToolRecord
import com.local.assistant.memory.tools.UndoToken
import org.junit.Assert.assertEquals
import org.junit.Test

class ChipsByMessageTest {

    private var nextId = 1L

    private fun message(role: Role, text: String) = MessageEntity(id = nextId++, chatId = 1, role = role, text = text, createdAt = 0)

    private fun tool(chip: MemoryChip, undo: UndoToken) =
        message(Role.TOOL, Gson().toJson(ToolRecord(tool = "add_task", arguments = emptyMap(), result = "{}", chip = chip, undo = undo)))

    @Test
    fun `a place reminder's chip knows its reminder, so the reply can show it on a map`() {
        val messages = listOf(
            message(Role.USER, "remind me to buy milk near Reliance Smart, and to call amma at 6"),
            tool(MemoryChip(MemoryChip.Kind.TASK, "Reminder", "Buy milk", place = "near Reliance Smart"), UndoToken(createdTaskId = 8)),
            tool(MemoryChip(MemoryChip.Kind.TASK, "Reminder", "Call amma", at = 1_800_000_000_000), UndoToken(createdTaskId = 9)),
            message(Role.ASSISTANT, "Done: both are set."),
        )

        val chips = chipsByMessage(messages).getValue(4)

        assertEquals(listOf(8L, null), chips.map { it.taskId })
    }

    @Test
    fun `a place reminder changed in chat is found by the reminder it changed`() {
        val before = TaskEntity(id = 8, title = "Buy milk", createdAt = 0, updatedAt = 0)
        val messages = listOf(
            message(Role.USER, "call it buy oat milk"),
            tool(MemoryChip(MemoryChip.Kind.TASK, "Changed", "Buy oat milk", place = "near Reliance Smart"), UndoToken(previousTask = before)),
            message(Role.ASSISTANT, "Renamed it."),
        )

        assertEquals(8L, chipsByMessage(messages).getValue(3).single().taskId)
    }
}
