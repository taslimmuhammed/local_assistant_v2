package com.local.assistant.data.db

import androidx.room.TypeConverter
import com.local.assistant.memory.db.FactCategory
import com.local.assistant.memory.db.FactOrigin
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.local.assistant.memory.db.NoteItem
import com.local.assistant.memory.db.NoteKind
import com.local.assistant.memory.db.SummaryStatus
import com.local.assistant.memory.db.TaskPlace
import com.local.assistant.memory.db.TaskStatus

class Converters {
    @TypeConverter
    fun roleToString(role: Role): String = role.name

    @TypeConverter
    fun stringToRole(value: String): Role = Role.valueOf(value)

    @TypeConverter
    fun attachmentKindToString(kind: AttachmentKind?): String? = kind?.name

    @TypeConverter
    fun stringToAttachmentKind(value: String?): AttachmentKind? = value?.let(AttachmentKind::valueOf)

    @TypeConverter
    fun factCategoryToString(value: FactCategory): String = value.name

    @TypeConverter
    fun stringToFactCategory(value: String): FactCategory = FactCategory.valueOf(value)

    @TypeConverter
    fun factOriginToString(value: FactOrigin): String = value.name

    @TypeConverter
    fun stringToFactOrigin(value: String): FactOrigin = FactOrigin.valueOf(value)

    @TypeConverter
    fun taskStatusToString(value: TaskStatus): String = value.name

    @TypeConverter
    fun stringToTaskStatus(value: String): TaskStatus = TaskStatus.valueOf(value)

    @TypeConverter
    fun summaryStatusToString(value: SummaryStatus): String = value.name

    @TypeConverter
    fun stringToSummaryStatus(value: String): SummaryStatus = SummaryStatus.valueOf(value)

    @TypeConverter
    fun taskPlaceToString(value: TaskPlace?): String? = value?.let(gson::toJson)

    @TypeConverter
    fun stringToTaskPlace(value: String?): TaskPlace? = value?.let { gson.fromJson(it, TaskPlace::class.java) }

    @TypeConverter
    fun noteKindToString(value: NoteKind): String = value.name

    @TypeConverter
    fun stringToNoteKind(value: String): NoteKind = NoteKind.valueOf(value)

    @TypeConverter
    fun noteItemsToString(value: List<NoteItem>): String = gson.toJson(value)

    @TypeConverter
    fun stringToNoteItems(value: String): List<NoteItem> =
        gson.fromJson<List<NoteItem>>(value, NOTE_ITEMS).orEmpty()

    private companion object {
        val gson = Gson()
        val NOTE_ITEMS = object : TypeToken<List<NoteItem>>() {}.type
    }
}
