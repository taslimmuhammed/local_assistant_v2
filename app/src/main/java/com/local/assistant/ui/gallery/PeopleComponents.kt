package com.local.assistant.ui.gallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.local.assistant.gallery.FaceEntity
import com.local.assistant.gallery.PhotoThumbnails
import com.local.assistant.gallery.faces.FaceIndex
import com.local.assistant.ui.theme.AppColors
import java.text.NumberFormat

/**
 * People on the Photos screen: off until turned on, with what that means; then the people named
 * so far and the groups of faces still to name, as a row of round faces.
 */
@Composable
fun PeopleSection(viewModel: GalleryViewModel, onOpen: (PeopleTarget) -> Unit) {
    val on by viewModel.findPeople.collectAsStateWithLifecycle()
    val status by viewModel.faceStatus.collectAsStateWithLifecycle()
    val people by viewModel.people.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    var confirmingOff by remember { mutableStateOf(false) }
    val numbers = NumberFormat.getIntegerInstance()

    if (!on) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(AppColors.SurfaceMuted)
                .padding(16.dp),
        ) {
            Text("Find people in your photos", style = MaterialTheme.typography.titleSmall, color = AppColors.TextPrimary)
            Text(
                "Groups the faces in your photos so you can name people and ask for “photos of me” or “photos of Amma”. " +
                    "Faces are read and kept only on this phone. Turning it off deletes them.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
            Button(
                onClick = { viewModel.setFindPeople(true) },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = AppColors.OnAccent),
                modifier = Modifier.padding(top = 12.dp),
            ) { Text("Turn on") }
        }
        return
    }

    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("People", style = MaterialTheme.typography.titleSmall, color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
            TextButton(onClick = { confirmingOff = true }) { Text("Turn off", color = AppColors.TextSecondary) }
        }
        if (status.photosLeft > 0) {
            Text(
                "Finding faces: ${numbers.format(status.photosDone)} of ${numbers.format(status.photosDone + status.photosLeft)} photos",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
            )
        }
        if (people.isEmpty() && groups.isEmpty()) {
            Text(
                if (status.photosLeft > 0) "People appear here as their faces are found." else "No one found yet.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(people, key = { "p${it.id}" }) { person ->
                    Avatar(person.cover, label = person.name, sub = if (person.isMe) "You" else null, onClick = { onOpen(PeopleTarget.Named(person)) })
                }
                items(groups, key = { "g${it.id}" }) { group ->
                    Avatar(group.cover, label = "Add a name", sub = "${numbers.format(group.photos)} photos", faint = true, onClick = { onOpen(PeopleTarget.Unnamed(group)) })
                }
            }
        }
    }

    if (confirmingOff) {
        AlertDialog(
            onDismissRequest = { confirmingOff = false },
            title = { Text("Turn off finding people?") },
            text = { Text("Every face found and every name you gave is deleted. Your photos aren't touched, and photo search keeps working.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingOff = false
                    viewModel.setFindPeople(false)
                }) { Text("Turn off", color = AppColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmingOff = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun Avatar(face: FaceEntity?, label: String, sub: String?, faint: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.width(76.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        FaceCircle(face, 64.dp)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (faint) AppColors.TextSecondary else AppColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
        sub?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary, maxLines = 1) }
    }
}

@Composable
private fun FaceCircle(face: FaceEntity?, size: Dp) {
    val thumbnails = LocalPhotoAccess.current?.thumbnails
    val bitmap by produceState<ImageBitmap?>(null, face?.id) { value = face?.let { thumbnails?.face(it) } }
    Box(
        Modifier.size(size).clip(CircleShape).background(AppColors.SurfaceMuted).border(1.dp, AppColors.Border, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            ?: Icon(Icons.Outlined.Face, contentDescription = null, tint = AppColors.TextSecondary)
    }
}

/**
 * One person's photos, and what can be done about them: a name for a group; for a named person,
 * renaming, saying it's the user, joining them with someone of the same name, or forgetting them.
 * A long press on a photo says the face in it isn't them.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun PersonSheet(target: PeopleTarget, viewModel: GalleryViewModel, onDismiss: () -> Unit) {
    val thumbnails = viewModel.thumbnails
    val people by viewModel.people.collectAsStateWithLifecycle()
    val status by viewModel.faceStatus.collectAsStateWithLifecycle()
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val height = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height * 0.88f).toInt().toDp() }
    val numbers = NumberFormat.getIntegerInstance()
    // The person as they are now: renamed, made "me", or merged away.
    val person = (target as? PeopleTarget.Named)?.let { named -> people.firstOrNull { it.id == named.person.id } }
    val photos by produceState(emptyList<Long>(), target, status.version) { value = viewModel.photosOf(target) }
    var naming by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf(false) }
    var rejecting by remember { mutableStateOf<Long?>(null) }
    var viewing by remember { mutableStateOf<Int?>(null) }

    // Merged into someone else, or forgotten: nothing left to show.
    LaunchedEffect(target, person) { if (target is PeopleTarget.Named && person == null) onDismiss() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = AppColors.Background) {
        Column(Modifier.height(height)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                FaceCircle(person?.cover ?: (target as? PeopleTarget.Unnamed)?.group?.cover, 48.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(person?.name ?: "Who is this?", style = MaterialTheme.typography.titleMedium, color = AppColors.TextPrimary)
                    Text(
                        listOfNotNull("${numbers.format(photos.size)} photos", "You".takeIf { person?.isMe == true }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary,
                    )
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "Close") }
            }
            FlowRow(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (person == null) {
                    PrimaryAction("Add a name") { naming = true }
                } else {
                    SecondaryAction("Rename") { renaming = true }
                    if (!person.isMe) SecondaryAction("This is me") { viewModel.setMe(person) }
                    if (people.size > 1) SecondaryAction("Same person as…") { merging = true }
                    SecondaryAction("Forget", danger = true) { forgetting = true }
                }
            }
            if (person != null) {
                Text(
                    "Long-press a photo if it isn't ${person.name}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                modifier = Modifier.fillMaxSize().nestedScroll(KeepScrollPastEnd),
            ) {
                itemsIndexed(photos, key = { _, id -> id }) { index, id ->
                    PhotoThumb(
                        id,
                        thumbnails,
                        Modifier
                            .aspectRatio(1f)
                            .combinedClickable(onClick = { viewing = index }, onLongClick = { if (person != null) rejecting = id }),
                    )
                }
            }
        }
    }

    viewing?.let { start -> PhotoViewer(photos, start, thumbnails, onClose = { viewing = null }) }

    if (naming && target is PeopleTarget.Unnamed) {
        NameDialog(
            people = people,
            onDismiss = { naming = false },
            onDone = { name, isMe, existing ->
                naming = false
                viewModel.name(target.group, name, isMe, existing)
                onDismiss()
            },
        )
    }
    if (renaming && person != null) {
        NameDialog(
            people = emptyList(),
            initial = person.name,
            title = "Rename",
            askMe = false,
            onDismiss = { renaming = false },
            onDone = { name, _, _ ->
                renaming = false
                viewModel.rename(person, name)
            },
        )
    }
    if (merging && person != null) {
        AlertDialog(
            onDismissRequest = { merging = false },
            title = { Text("Same person as…") },
            text = {
                Column {
                    Text("${person.name}'s photos move to the person you pick.", style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
                    for (other in people.filter { it.id != person.id }) {
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                merging = false
                                viewModel.merge(person, other)
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FaceCircle(other.cover, 36.dp)
                            Text(other.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { merging = false }) { Text("Cancel") } },
        )
    }
    if (forgetting && person != null) {
        AlertDialog(
            onDismissRequest = { forgetting = false },
            title = { Text("Forget ${person.name}?") },
            text = { Text("Their name goes, and their faces go back to being unnamed. Their photos aren't touched.") },
            confirmButton = {
                TextButton(onClick = {
                    forgetting = false
                    viewModel.forget(person)
                }) { Text("Forget", color = AppColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { forgetting = false }) { Text("Keep") } },
        )
    }
    rejecting?.let { mediaId ->
        if (person == null) return@let
        AlertDialog(
            onDismissRequest = { rejecting = null },
            title = { Text("Not ${person.name}?") },
            text = { Text("This photo leaves ${person.name}, and that face won't be matched to them again.") },
            confirmButton = {
                TextButton(onClick = {
                    rejecting = null
                    viewModel.notThem(person, mediaId)
                }) { Text("Not ${person.name}") }
            },
            dismissButton = { TextButton(onClick = { rejecting = null }) { Text("Cancel") } },
        )
    }
}

/**
 * A name for a group of faces: typed, or one of the people already named (which joins the two),
 * and whether it's the user.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NameDialog(
    people: List<FaceIndex.Person>,
    onDismiss: () -> Unit,
    onDone: (name: String, isMe: Boolean, existing: Long?) -> Unit,
    initial: String = "",
    title: String = "Who is this?",
    askMe: Boolean = true,
) {
    var name by remember { mutableStateOf(initial) }
    var isMe by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (askMe) {
                    Row(
                        Modifier.fillMaxWidth().clickable { isMe = !isMe }.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = isMe, onCheckedChange = { isMe = it }, colors = CheckboxDefaults.colors(checkedColor = AppColors.Accent))
                        Text("This is me", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (people.isNotEmpty()) {
                    Text(
                        "Or someone already named:",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (person in people) {
                            SecondaryAction(person.name) { onDone(person.name, person.isMe, person.id) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(name.trim().ifEmpty { if (isMe) "Me" else "" }, isMe, null) }, enabled = name.isNotBlank() || isMe) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PrimaryAction(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = AppColors.OnAccent),
    ) { Text(label) }
}

@Composable
private fun SecondaryAction(label: String, danger: Boolean = false, onClick: () -> Unit) {
    FilterChip(
        selected = false,
        onClick = onClick,
        label = { Text(label, color = if (danger) AppColors.Danger else AppColors.TextPrimary) },
        colors = FilterChipDefaults.filterChipColors(containerColor = AppColors.Background),
        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = false, borderColor = AppColors.TextSecondary),
    )
}
