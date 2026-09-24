package com.local.assistant.data.db

import androidx.room.TypeConverter
import com.local.assistant.memory.db.FactCategory
import com.local.assistant.memory.db.FactOrigin
import com.local.assistant.memory.db.SummaryStatus
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
}
