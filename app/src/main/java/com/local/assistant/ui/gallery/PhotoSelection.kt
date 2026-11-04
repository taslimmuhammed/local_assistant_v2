package com.local.assistant.ui.gallery

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.local.assistant.gallery.PhotoShare
import com.local.assistant.gallery.PhotoThumbnails
import com.local.assistant.ui.privacy.LocalSensitive
import com.local.assistant.ui.privacy.sensitiveRevealed
import com.local.assistant.ui.theme.AppColors
import com.local.assistant.ui.theme.Glass
import com.local.assistant.ui.theme.GlassRoundButton
import com.local.assistant.ui.theme.liquidGlass
import kotlinx.coroutines.launch
import java.text.NumberFormat

/**
 * Which of a photo card's [ids] are picked, to share. [ids] is the card's order; what's shared
 * goes in the order it's shown ([inOrder]), which the sheet can change.
 */
data class PhotoSelection(val ids: List<Long>, val picked: Set<Long> = emptySet()) {
    val count: Int get() = picked.size
    val all: Boolean get() = ids.isNotEmpty() && picked.containsAll(ids)

    fun toggle(id: Long): PhotoSelection = copy(picked = if (id in picked) picked - id else picked + id)

    fun pick(id: Long): PhotoSelection = copy(picked = picked + id)

    /** Every one, or none once every one is picked: the "Select all" / "Clear" button. */
    fun toggleAll(): PhotoSelection = copy(picked = if (all) emptySet() else ids.toSet())

    fun inOrder(order: List<Long> = ids): List<Long> = order.filter { it in picked }

    companion object {
        fun all(ids: List<Long>) = PhotoSelection(ids, ids.toSet())
    }
}

/** "Select photos", "2 of 12 selected", "All 12 selected". */
internal fun selectedLabel(count: Int, total: Int): String {
    val numbers = NumberFormat.getIntegerInstance()
    return when {
        count == 0 -> "Select photos"
        count >= total && total > 1 -> "All ${numbers.format(total)} selected"
        count >= total -> "${numbers.format(count)} selected"
        else -> "${numbers.format(count)} of ${numbers.format(total)} selected"
    }
}

/**
 * A photo that can be picked. While picking, a tap picks it or lets it go, a check in its corner
 * shows which, and a picked one sits back a little; otherwise a tap opens it. A long press starts
 * picking with it, as in the phone's own gallery.
 */
@Composable
internal fun SelectablePhoto(
    id: Long,
    thumbnails: PhotoThumbnails,
    selection: PhotoSelection?,
    shape: Shape,
    onOpen: () -> Unit,
    onSelection: (PhotoSelection) -> Unit,
    ids: List<Long>,
    modifier: Modifier = Modifier,
) {
    val picked = selection != null && id in selection.picked
    val scale by animateFloatAsState(if (picked) PICKED_SCALE else 1f, label = "picked")
    Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale }.then(modifier)) {
        PhotoThumb(
            id,
            thumbnails,
            Modifier.fillMaxSize(),
            onClick = { if (selection != null) onSelection(selection.toggle(id)) else onOpen() },
            onLongClick = { onSelection((selection ?: PhotoSelection(ids)).pick(id)) },
        )
        if (picked) Box(Modifier.matchParentSize().border(3.dp, AppColors.Accent, shape))
        if (selection != null) SelectCheck(picked, Modifier.align(Alignment.TopEnd).padding(6.dp))
    }
}

/** The ring in a photo's corner while picking: empty, or dark with a tick once picked. */
@Composable
private fun SelectCheck(picked: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (picked) AppColors.Accent else Color.Black.copy(alpha = 0.22f))
            .border(2.dp, Color.White, CircleShape)
            .semantics { contentDescription = if (picked) "Selected" else "Not selected" },
        contentAlignment = Alignment.Center,
    ) {
        if (picked) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
    }
}

/**
 * "2 of 12 selected · Select all · Share ✕", while picking photos from a card: the count, every
 * one or none, the share sheet for those picked, and back to just looking.
 */
@Composable
internal fun SelectionBar(selection: PhotoSelection, onSelection: (PhotoSelection) -> Unit, onShare: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .liquidGlass(BarShape, Glass.Raised, sheen = 0.5f)
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            selectedLabel(selection.count, selection.ids.size),
            style = MaterialTheme.typography.labelLarge,
            color = AppColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selection.ids.size > 1) {
            Text(
                if (selection.all) "Clear" else "Select all",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary,
                maxLines = 1,
                modifier = Modifier
                    .clip(BarShape)
                    .clickable(role = Role.Button) { onSelection(selection.toggleAll()) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
        ShareButton(enabled = selection.count > 0, onClick = onShare)
        GlassRoundButton(Icons.Outlined.Close, "Stop selecting", onCancel, size = 36.dp, iconSize = 16.dp)
    }
}

/** The dark pill, as "View all" on the card: what the bar is for. */
@Composable
private fun ShareButton(enabled: Boolean, onClick: () -> Unit) {
    val ink = if (enabled) AppColors.OnAccent else AppColors.TextSecondary
    Row(
        Modifier
            .clip(BarShape)
            .then(if (enabled) Modifier.background(Glass.Mine) else Modifier.background(AppColors.SurfaceMuted))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Share, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
        Text("Share", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = ink, modifier = Modifier.padding(start = 6.dp))
    }
}

/**
 * Sends photos on through the share sheet (see [PhotoShare]). A hidden document among them asks
 * for the fingerprint first, as opening one in another app does: another app would show it in
 * full. Over the assistant panel the sheet opens over the panel, which steps aside, as it does for
 * any app, once the chosen app opens.
 */
@Composable
internal fun rememberPhotoShare(thumbnails: PhotoThumbnails): (List<Long>) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sensitive = LocalSensitive.current
    val documents by thumbnails.documents.collectAsState()
    val revealed = sensitiveRevealed()
    return share@{ ids ->
        if (!revealed && ids.any { it in documents }) {
            sensitive?.reveal()
            return@share
        }
        scope.launch {
            val plan = PhotoShare.plan(thumbnails.shareTypes(ids))
            if (plan == null) {
                Toast.makeText(context, "Those photos aren't on the phone any more.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            runCatching { context.startActivity(PhotoShare.chooser(plan, thumbnails::uri)) }
        }
    }
}

private const val PICKED_SCALE = 0.9f
private val BarShape = RoundedCornerShape(50)
