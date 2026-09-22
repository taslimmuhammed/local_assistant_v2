package com.local.assistant.data.db

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun roleToString(role: Role): String = role.name

    @TypeConverter
    fun stringToRole(value: String): Role = Role.valueOf(value)

    @TypeConverter
    fun attachmentKindToString(kind: AttachmentKind?): String? = kind?.name

    @TypeConverter
    fun stringToAttachmentKind(value: String?): AttachmentKind? = value?.let(AttachmentKind::valueOf)
}
