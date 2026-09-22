package com.local.assistant.ui.chat

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.local.assistant.ui.theme.AppColors
import java.io.File

/** Decodes at a bounded size: these are already downscaled on import, but never trust that. */
@Composable
fun AttachedImage(path: String, modifier: Modifier = Modifier, maxHeight: Int = 220) {
    val bitmap = remember(path) {
        runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
    }
    if (bitmap == null) {
        MissingAttachment("Image unavailable", modifier)
        return
    }
    Image(
        bitmap = bitmap,
        contentDescription = "Attached image",
        contentScale = ContentScale.Fit,
        modifier = modifier
            .heightIn(max = maxHeight.dp)
            .widthIn(max = 260.dp)
            .clip(RoundedCornerShape(14.dp)),
    )
}

/** A voice note with inline playback, so a recorded message is not write-only. */
@Composable
fun AudioAttachment(path: String, durationMs: Long?, modifier: Modifier = Modifier) {
    if (!remember(path) { File(path).isFile }) {
        MissingAttachment("Voice message unavailable", modifier)
        return
    }

    var player by remember(path) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(path) { mutableStateOf(false) }

    DisposableEffect(path) {
        onDispose {
            player?.release()
            player = null
        }
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(AppColors.SurfaceMuted)
            .padding(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IconButton(
            onClick = {
                if (playing) {
                    player?.release()
                    player = null
                    playing = false
                } else {
                    runCatching {
                        MediaPlayer().apply {
                            setDataSource(path)
                            setOnCompletionListener {
                                release()
                                player = null
                                playing = false
                            }
                            prepare()
                            start()
                        }
                    }.onSuccess {
                        player = it
                        playing = true
                    }
                }
            },
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Stop" else "Play",
                tint = AppColors.TextPrimary,
                modifier = Modifier.size(20.dp),
            )
        }
        Icon(
            Icons.Outlined.GraphicEq,
            contentDescription = null,
            tint = AppColors.TextSecondary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = formatDuration(durationMs),
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun MissingAttachment(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.SurfaceMuted)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
    }
}

/** Small round dot used for the recording indicator. */
@Composable
fun RecordingDot(modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).clip(CircleShape).background(AppColors.Danger))
}

fun formatDuration(millis: Long?): String {
    val totalSeconds = ((millis ?: 0L) / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
