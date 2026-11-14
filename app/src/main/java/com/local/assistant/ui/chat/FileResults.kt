package com.local.assistant.ui.chat

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.local.assistant.files.DocumentFiles
import com.local.assistant.files.FileNames
import com.local.assistant.files.FileTools
import com.local.assistant.media.AttachmentStore
import com.local.assistant.memory.tools.FileRef
import com.local.assistant.memory.tools.FileSet
import com.local.assistant.ui.theme.AppColors
import com.local.assistant.ui.theme.Glass
import com.local.assistant.ui.theme.glass
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The files or folders read_file found, under the reply: a row each, tapped to open the file in
 * the phone's app for it, or the folder in the Files app. The one a reply was read from is marked.
 */
@Composable
fun FileResults(files: FileSet, modifier: Modifier = Modifier) {
    var all by remember(files) { mutableStateOf(files.files.size <= SHOWN) }
    val shown = if (all) files.files else files.files.take(SHOWN)
    Column(modifier.widthIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        shown.forEach { FileRow(it, read = files.wasRead(it)) }
        if (!all) {
            Text(
                "Show ${files.files.size - SHOWN} more",
                style = MaterialTheme.typography.labelMedium,
                color = AppColors.Accent,
                modifier = Modifier.clickable { all = true }.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun FileRow(file: FileRef, read: Boolean) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(14.dp), Glass.Raised)
            .clickable {
                val opened = if (file.folder) {
                    DocumentFiles.openFolder(context, file.where)
                } else {
                    DocumentFiles.open(context, file.path, file.name, file.mime, file.mediaId)
                }
                if (!opened) {
                    val why = if (file.folder) "Couldn't open that folder" else "No app on this phone opens ${file.name.substringAfterLast('.', "this file")} files, or it was moved"
                    Toast.makeText(context, why, Toast.LENGTH_SHORT).show()
                }
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(iconOf(file), contentDescription = null, tint = AppColors.TextSecondary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodySmall, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(
                detailOf(file, read),
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A document attached to a message ([com.local.assistant.data.db.AttachmentKind.DOCUMENT]), in
 * its bubble or above the composer: its name, kind and size, tapped to open it.
 */
@Composable
fun DocumentAttachment(path: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val name = remember(path) { AttachmentStore.documentName(path) }
    val size = remember(path) { File(path).length() }
    val ref = FileRef(name, "", path, sizeBytes = size)
    Row(
        modifier = modifier
            .widthIn(max = 280.dp)
            .glass(RoundedCornerShape(14.dp), Glass.Raised)
            .clickable {
                if (!DocumentFiles.open(context, path, name, null, null)) {
                    Toast.makeText(context, "No app on this phone opens ${name.substringAfterLast('.', "this file")} files", Toast.LENGTH_SHORT).show()
                }
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(iconOf(ref), contentDescription = null, tint = AppColors.TextSecondary, modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(name, style = MaterialTheme.typography.bodySmall, color = AppColors.TextPrimary, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
            val kind = FileNames.extensionOf(name).uppercase(Locale.ROOT).ifEmpty { "Document" }
            Text(
                listOfNotNull(kind, size.takeIf { it > 0 }?.let(FileTools::sizeOf)).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextSecondary,
                maxLines = 1,
            )
        }
    }
}

private fun detailOf(file: FileRef, read: Boolean): String {
    if (file.folder) return listOf(file.where.trimEnd('/'), "${file.count} file${if (file.count == 1) "" else "s"}").joinToString(" · ")
    val parts = listOfNotNull(
        "Read for this reply".takeIf { read },
        file.where.trimEnd('/').substringAfterLast('/').ifEmpty { null },
        file.modifiedMs.takeIf { it > 0 }?.let { DATE.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) },
        file.sizeBytes.takeIf { it > 0 }?.let(FileTools::sizeOf),
    )
    return parts.joinToString(" · ")
}

private fun iconOf(file: FileRef): ImageVector = when {
    file.folder -> Icons.Outlined.Folder
    FileNames.Type.PDF.allows(file.name) -> Icons.Outlined.PictureAsPdf
    FileNames.Type.SPREADSHEET.allows(file.name) -> Icons.Outlined.TableChart
    FileNames.Type.SLIDES.allows(file.name) -> Icons.Outlined.Slideshow
    FileNames.Type.WORD.allows(file.name) || FileNames.Type.TEXT.allows(file.name) -> Icons.Outlined.Description
    FileNames.Type.AUDIO.allows(file.name) -> Icons.Outlined.AudioFile
    FileNames.Type.ARCHIVE.allows(file.name) -> Icons.Outlined.FolderZip
    else -> Icons.Outlined.InsertDriveFile
}

private const val SHOWN = 4

private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
