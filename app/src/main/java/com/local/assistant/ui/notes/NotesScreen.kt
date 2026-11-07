package com.local.assistant.ui.notes

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.local.assistant.memory.db.NoteItem
import com.local.assistant.memory.db.NoteKind
import com.local.assistant.memory.db.UserNoteEntity
import com.local.assistant.memory.notes.NoteEdits
import com.local.assistant.ui.memory.Note
import com.local.assistant.ui.theme.AppColors
import com.local.assistant.ui.theme.Glass
import com.local.assistant.ui.theme.GlassIconButton
import com.local.assistant.ui.theme.GlassShapes
import com.local.assistant.ui.theme.GlassSnackbarHost
import com.local.assistant.ui.theme.glass
import com.local.assistant.ui.theme.glassFieldColors
import com.local.assistant.ui.theme.liquidGlass
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The user's notes and lists, a tap from the menu: everything made in chat ("make a shopping list
 * with milk and eggs") or here. Lists tick off on their cards; a tap opens one to change anything
 * in it. Deleting asks first, as everywhere in the app, and can be undone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    viewModel: NotesViewModel,
    onBack: () -> Unit,
    /** Opened on one note from the chat: leaving that note goes back to the chat, not to the list. */
    backToChat: Boolean = false,
) {
    val notes by viewModel.all.collectAsStateWithLifecycle()
    val open by viewModel.open.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var deleting by remember { mutableStateOf<UserNoteEntity?>(null) }
    ShowNoteNotices(viewModel, snackbar)

    val editing = open
    if (editing != null) {
        NoteEditor(
            editing,
            viewModel,
            snackbar,
            onClose = {
                viewModel.close()
                if (backToChat) onBack()
            },
            onDelete = { deleting = it },
        )
    } else {
        NoteList(notes, viewModel, snackbar, onBack)
    }
    deleting?.let { note ->
        ConfirmDeleteNote(note, onConfirm = { viewModel.delete(note) }, onDone = { deleting = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteList(notes: List<UserNoteEntity>, viewModel: NotesViewModel, snackbar: SnackbarHostState, onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf<NoteKind?>(null) }
    val shown = remember(notes, query, filter) { notes.filter { (filter == null || it.kind == filter) && matches(it, query) } }

    Scaffold(
        containerColor = AppColors.Background,
        snackbarHost = { GlassSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Notes", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    GlassIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack, Modifier.padding(start = 10.dp, end = 6.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppColors.Background,
                    titleContentColor = AppColors.TextPrimary,
                    navigationIconContentColor = AppColors.TextPrimary,
                ),
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                NewButton(Icons.Outlined.Checklist, "New list", Modifier.weight(1f)) { viewModel.create(NoteKind.LIST) }
                NewButton(Icons.AutoMirrored.Outlined.StickyNote2, "New note", Modifier.weight(1f)) { viewModel.create(NoteKind.NOTE) }
            }
        },
    ) { padding ->
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
            verticalItemSpacing = 10.dp,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (notes.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine, key = "search") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search notes") },
                            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    Icon(Icons.Outlined.Close, contentDescription = "Clear", modifier = Modifier.clip(CircleShape).clickable { query = "" })
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = GlassShapes.Field,
                            colors = glassFieldColors(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            KindChip("All", filter == null) { filter = null }
                            KindChip("Lists", filter == NoteKind.LIST) { filter = NoteKind.LIST }
                            KindChip("Notes", filter == NoteKind.NOTE) { filter = NoteKind.NOTE }
                        }
                    }
                }
            }
            if (shown.isEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine, key = "empty") {
                    Note(
                        if (notes.isEmpty()) {
                            "No notes yet. Ask in any chat: “make a shopping list with milk, eggs and bread”, “add butter to my shopping list”, " +
                                "or “note down that the gate code is 4417”. Or start one below."
                        } else {
                            "Nothing matches."
                        },
                    )
                }
            }
            items(shown, key = { it.id }) { note ->
                NoteCard(note, onOpen = { viewModel.open(note) }, onToggle = { index -> viewModel.toggle(note.id, index) })
            }
        }
    }
}

/** "New list", "New note": glass pills at the foot of the list. */
@Composable
private fun NewButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.liquidGlass(GlassShapes.Pill, Glass.Raised).clickable(onClick = onClick).padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = AppColors.TextPrimary, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = AppColors.TextPrimary)
    }
}

/** In the switches' colours, as the Reminders screen's chips: the choice in black. */
@Composable
private fun KindChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Glass.Raised,
            labelColor = AppColors.TextPrimary,
            selectedContainerColor = AppColors.Accent,
            selectedLabelColor = AppColors.OnAccent,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = AppColors.TextSecondary,
            selectedBorderColor = AppColors.Accent,
        ),
    )
}

/** A note on the list: its name, then the first of its items (each ticks off here) or lines. */
@Composable
private fun NoteCard(note: UserNoteEntity, onOpen: () -> Unit, onToggle: (Int) -> Unit) {
    Column(
        Modifier.fillMaxWidth().glass(CardShape).clickable(onClick = onOpen).padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(kindIcon(note.kind), contentDescription = null, tint = KindTint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                note.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (note.pinned) Icon(Icons.Filled.PushPin, contentDescription = "Pinned", tint = AppColors.TextSecondary, modifier = Modifier.size(14.dp))
        }
        when (note.kind) {
            NoteKind.LIST -> {
                val open = note.items.withIndex().filter { !it.value.done }
                open.take(CARD_ITEMS).forEach { (index, item) ->
                    CheckRow(item, onToggle = { onToggle(index) }, compact = true)
                }
                val more = open.size - CARD_ITEMS
                val ticked = note.items.size - open.size
                listOfNotNull(
                    "+$more more".takeIf { more > 0 },
                    "$ticked ticked".takeIf { ticked > 0 },
                    "Empty list".takeIf { note.items.isEmpty() },
                ).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 2.dp))
                }
            }
            NoteKind.NOTE -> Text(
                note.body.ifBlank { "Empty note" },
                style = MaterialTheme.typography.bodySmall,
                color = if (note.body.isBlank()) AppColors.TextSecondary else AppColors.TextPrimary,
                maxLines = CARD_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(edited(note.updatedAt), style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary)
    }
}

/** One item with its box: a tap on the box ticks it or puts it back. */
@Composable
internal fun CheckRow(item: NoteItem, onToggle: (() -> Unit)?, compact: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (item.done) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
            contentDescription = if (item.done) "Untick ${item.text}" else "Tick ${item.text}",
            tint = if (item.done) AppColors.TextSecondary else AppColors.TextPrimary,
            modifier = Modifier
                .clip(CircleShape)
                .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
                .padding(if (compact) 3.dp else 6.dp)
                .size(if (compact) 17.dp else 21.dp),
        )
        Spacer(Modifier.width(if (compact) 4.dp else 6.dp))
        Text(
            item.text,
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            color = if (item.done) AppColors.TextSecondary else AppColors.TextPrimary,
            textDecoration = if (item.done) TextDecoration.LineThrough else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * One note open: its name, then its text or its items, all editable where they are; saved as it's
 * typed. The ticked items of a list sit below the rest, struck through.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteEditor(
    start: UserNoteEntity,
    viewModel: NotesViewModel,
    snackbar: SnackbarHostState,
    onClose: () -> Unit,
    onDelete: (UserNoteEntity) -> Unit,
) {
    var draft by remember(start.id, start.createdAt) { mutableStateOf(start) }
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    fun update(next: UserNoteEntity) {
        draft = next
        viewModel.change(next)
    }
    BackHandler(onBack = onClose)

    Scaffold(
        containerColor = AppColors.Background,
        snackbarHost = { GlassSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    GlassIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onClose, Modifier.padding(start = 10.dp, end = 6.dp))
                },
                actions = {
                    GlassIconButton(
                        if (draft.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        if (draft.pinned) "Unpin" else "Pin",
                        { update(draft.copy(pinned = !draft.pinned)) },
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.padding(end = 10.dp)) {
                        GlassIconButton(Icons.Outlined.MoreVert, "More", { menu = true })
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(if (draft.kind == NoteKind.LIST) "Turn into a note" else "Turn into a list") },
                                leadingIcon = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null) },
                                onClick = {
                                    menu = false
                                    update(NoteEdits.converted(draft, System.currentTimeMillis()))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Share") },
                                leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                                onClick = {
                                    menu = false
                                    share(context, draft)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", color = AppColors.Danger) },
                                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = AppColors.Danger) },
                                onClick = {
                                    menu = false
                                    onDelete(draft)
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.Background),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            PlainField(
                value = draft.title,
                onValueChange = { update(draft.copy(title = it)) },
                placeholder = "Title",
                style = TitleStyle,
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            when (draft.kind) {
                NoteKind.NOTE -> PlainField(
                    value = draft.body,
                    onValueChange = { update(draft.copy(body = it)) },
                    placeholder = "Note",
                    style = MaterialTheme.typography.bodyLarge.copy(color = AppColors.TextPrimary),
                    modifier = Modifier.defaultMinSize(minHeight = 280.dp),
                )
                NoteKind.LIST -> ListEditor(draft.items) { update(draft.copy(items = it)) }
            }
            Spacer(Modifier.height(20.dp))
            if (start.id != 0L) {
                Text("Edited " + edited(draft.updatedAt), style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** A list's items to tick, retype or remove; a field below adds more (several at once, pasted one per line). */
@Composable
private fun ListEditor(items: List<NoteItem>, onChange: (List<NoteItem>) -> Unit) {
    var adding by remember { mutableStateOf("") }
    var showTicked by rememberSaveable { mutableStateOf(true) }
    val addFocus = remember { FocusRequester() }
    fun add() {
        val new = adding.lines().map(NoteEdits::tidyItem).filter { it.isNotEmpty() }
        if (new.isNotEmpty()) onChange(items + new.map { NoteItem(it) })
        adding = ""
    }

    items.forEachIndexed { index, item ->
        if (!item.done) key(index) {
            ItemRow(
                item = item,
                onToggle = { onChange(items.toMutableList().apply { set(index, item.copy(done = true)) }) },
                onText = { text -> onChange(items.toMutableList().apply { set(index, item.copy(text = text)) }) },
                onRemove = { onChange(items.filterIndexed { i, _ -> i != index }) },
                onNext = { addFocus.requestFocus() },
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Icon(Icons.Outlined.Add, contentDescription = null, tint = AppColors.TextSecondary, modifier = Modifier.padding(6.dp).size(21.dp))
        Spacer(Modifier.width(6.dp))
        PlainField(
            value = adding,
            onValueChange = { text ->
                // Enter, or a pasted list: what's before the line break is an item.
                if ('\n' in text) {
                    adding = text
                    add()
                } else {
                    adding = text
                }
            },
            placeholder = "Add item",
            style = MaterialTheme.typography.bodyMedium.copy(color = AppColors.TextPrimary),
            singleLine = true,
            imeAction = ImeAction.Next,
            onImeAction = ::add,
            modifier = Modifier.weight(1f).focusRequester(addFocus),
        )
    }

    val ticked = items.withIndex().filter { it.value.done }
    if (ticked.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp).clip(RoundedCornerShape(8.dp)).clickable { showTicked = !showTicked }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (showTicked) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null, tint = AppColors.TextSecondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text("${ticked.size} ticked", style = MaterialTheme.typography.labelLarge, color = AppColors.TextSecondary, modifier = Modifier.weight(1f))
            Text(
                "Clear",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onChange(items.filter { !it.done }) }.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (showTicked) {
            ticked.forEach { (index, item) ->
                key("ticked-$index") {
                    ItemRow(
                        item = item,
                        onToggle = { onChange(items.toMutableList().apply { set(index, item.copy(done = false)) }) },
                        onText = { text -> onChange(items.toMutableList().apply { set(index, item.copy(text = text)) }) },
                        onRemove = { onChange(items.filterIndexed { i, _ -> i != index }) },
                        onNext = { addFocus.requestFocus() },
                    )
                }
            }
        }
    }
}

@Composable
private fun ItemRow(item: NoteItem, onToggle: () -> Unit, onText: (String) -> Unit, onRemove: () -> Unit, onNext: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
        Icon(
            if (item.done) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
            contentDescription = if (item.done) "Untick ${item.text}" else "Tick ${item.text}",
            tint = if (item.done) AppColors.TextSecondary else AppColors.TextPrimary,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onToggle).padding(6.dp).size(21.dp),
        )
        Spacer(Modifier.width(6.dp))
        PlainField(
            value = item.text,
            onValueChange = { text -> if ('\n' in text) onNext() else onText(text) },
            placeholder = "Item",
            style = MaterialTheme.typography.bodyMedium.copy(
                color = if (item.done) AppColors.TextSecondary else AppColors.TextPrimary,
                textDecoration = if (item.done) TextDecoration.LineThrough else null,
            ),
            singleLine = true,
            imeAction = ImeAction.Next,
            onImeAction = onNext,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Outlined.Close,
            contentDescription = "Remove ${item.text}",
            tint = AppColors.TextSecondary,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onRemove).padding(8.dp).size(18.dp),
        )
    }
}

/** A text field with no box: the words sit on the glass, with a grey hint while empty. */
@Composable
private fun PlainField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: (() -> Unit)? = null,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = style,
        singleLine = singleLine,
        cursorBrush = SolidColor(AppColors.TextPrimary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = { onImeAction?.invoke() }),
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, style = style.copy(color = AppColors.TextSecondary.copy(alpha = 0.7f), textDecoration = null))
                inner()
            }
        },
    )
}

/** Asks before a note is deleted, naming it. */
@Composable
private fun ConfirmDeleteNote(note: UserNoteEntity, onConfirm: () -> Unit, onDone: () -> Unit) {
    val kind = if (note.kind == NoteKind.LIST) "list" else "note"
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Delete this $kind?") },
        text = { Text(NotesViewModel.titled(note), style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary) },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDone()
                },
            ) { Text("Delete", color = AppColors.Danger) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

/** "Deleted “Shopping list”", with its undo. */
@Composable
private fun ShowNoteNotices(viewModel: NotesViewModel, snackbar: SnackbarHostState) {
    LaunchedEffect(viewModel) {
        viewModel.notices.collect { notice ->
            val result = snackbar.showSnackbar(
                message = notice.message,
                actionLabel = if (notice.undo != null) "Undo" else null,
                duration = androidx.compose.material3.SnackbarDuration.Long,
            )
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) viewModel.undo(notice) else viewModel.settle(notice)
        }
    }
}

/** The note as text, to another app: a list as boxes ticked or not. */
private fun share(context: Context, note: UserNoteEntity) {
    val text = buildString {
        append(NotesViewModel.titled(note))
        append("\n\n")
        when (note.kind) {
            NoteKind.LIST -> note.items.forEach { append(if (it.done) "☑ " else "☐ ").append(it.text).append('\n') }
            NoteKind.NOTE -> append(note.body)
        }
    }.trimEnd()
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, note.title)
    context.startActivity(Intent.createChooser(send, null))
}

private fun matches(note: UserNoteEntity, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return note.title.contains(q, ignoreCase = true) || note.body.contains(q, ignoreCase = true) ||
        note.items.any { it.text.contains(q, ignoreCase = true) }
}

internal fun kindIcon(kind: NoteKind): ImageVector = when (kind) {
    NoteKind.LIST -> Icons.Outlined.Checklist
    NoteKind.NOTE -> Icons.AutoMirrored.Outlined.StickyNote2
}

/** "9:41 PM" today, "Tue 3 Nov" this year, "3 Nov 2025" before. */
internal fun edited(at: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val time = Instant.ofEpochMilli(at).atZone(zone)
    val today = LocalDate.now(zone)
    return when {
        time.toLocalDate() == today -> TIME.format(time)
        time.year == today.year -> DAY.format(time)
        else -> DATE.format(time)
    }
}

/** The notes' colour, from the drawer's tile. */
internal val KindTint = Glass.Ink[2]

private val CardShape = RoundedCornerShape(20.dp)
private val TitleStyle = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
private const val CARD_ITEMS = 6
private const val CARD_LINES = 10
private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
