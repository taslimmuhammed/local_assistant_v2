package com.local.assistant.away

import android.Manifest
import android.content.pm.PackageManager
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sign

/**
 * The brief's glass card: who needs the user (with replies ready to send), what's worth
 * knowing, and how much was left out. Drawn inside [BriefActivity]'s window, which is the glass.
 */
@Composable
fun BriefPanel(
    viewModel: BriefViewModel,
    blurred: Boolean,
    awayMs: Long,
    maxHeight: Dp,
    onClose: () -> Unit,
) {
    val shown by viewModel.shown.collectAsStateWithLifecycle()
    val sends by viewModel.sends.collectAsStateWithLifecycle()
    val drafts by viewModel.drafts.collectAsStateWithLifecycle()
    val listening by viewModel.listening.collectAsStateWithLifecycle()
    val reading by viewModel.reading.collectAsStateWithLifecycle()
    val speaking by viewModel.speaking.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val glass = remember(blurred) { Glass(blurred) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---- Voice: the microphone is asked for the first time it's needed ----
    var askedFor by remember { mutableStateOf<BriefItem?>(null) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val item = askedFor
        askedFor = null
        if (granted && item != null) viewModel.listen(item) else if (!granted) viewModel.onMicDenied()
    }
    val listen: (BriefItem) -> Unit = { item ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.listen(item)
        } else {
            askedFor = item
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // ---- Everything handled: a moment to see it, then it goes ----
    LaunchedEffect(shown.loaded, shown.isEmpty) {
        if (shown.loaded && shown.isEmpty) {
            delay(if (shown.hadItems) CAUGHT_UP_AFTER_DONE_MS else CAUGHT_UP_MS)
            onClose()
        }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(NOTICE_MS)
            viewModel.clearNotice()
        }
    }

    val level = listening?.level ?: 0f
    val energy = animateFloatAsState(
        targetValue = when {
            listening != null -> 0.55f + 0.45f * level.coerceIn(0f, 1f)
            reading -> 0.95f
            speaking -> 0.8f
            else -> 0.3f
        },
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "energy",
    )

    // ---- Pulled down from the top: it follows, fading, and goes past a point ----
    val pull = remember { Animatable(0f) }
    val dragToClose = Modifier.pointerInput(Unit) {
        detectVerticalDragGestures(
            onDragEnd = {
                if (pull.value > CLOSE_DRAG.toPx()) onClose() else scope.launch { pull.animateTo(0f, spring()) }
            },
            onDragCancel = { scope.launch { pull.animateTo(0f, spring()) } },
            onVerticalDrag = { change, amount ->
                change.consume()
                scope.launch { pull.snapTo((pull.value + amount).coerceAtLeast(0f)) }
            },
        )
    }

    GlassRim(energy) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                // Typing: the card ends at the keyboard, and the list scrolls above it.
                .imePadding()
                .graphicsLayer {
                    translationY = pull.value * 0.45f
                    alpha = 1f - (pull.value / (CLOSE_DRAG.toPx() * 3f)).coerceIn(0f, 0.6f)
                },
        ) {
            Header(
                modifier = dragToClose,
                awayMs = awayMs,
                needCount = shown.needs.size,
                energy = energy,
                reading = reading,
                speaking = speaking,
                canSpeak = !shown.isEmpty,
                glass = glass,
                onSpeak = viewModel::toggleSpeaking,
                onClose = onClose,
            )
            when {
                !shown.loaded -> Spacer(Modifier.height(56.dp))
                shown.isEmpty -> CaughtUp(hadItems = shown.hadItems, newOnly = viewModel.newOnly)
                else -> {
                    BriefList(
                        shown = shown,
                        sends = sends,
                        drafts = drafts,
                        listening = listening,
                        reading = reading,
                        glass = glass,
                        viewModel = viewModel,
                        onListen = listen,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
            AnimatedVisibility(visible = notice != null, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut()) {
                Notice(notice.orEmpty())
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

// ---- The glass ----

/** The tints that sit on the glass: lighter and see-through with blur, plain greys without. */
private class Glass(val blurred: Boolean) {
    val tile = if (blurred) Color.White.copy(alpha = 0.58f) else Color(0xFFF4F4F5)
    val tileRim = if (blurred) Color.White.copy(alpha = 0.95f) else Color(0xFFE4E4E7)
    val row = if (blurred) Color.White.copy(alpha = 0.4f) else Color(0xFFF7F7F8)
    val field = if (blurred) Color.White.copy(alpha = 0.82f) else Color.White
    val chip = if (blurred) Color.White.copy(alpha = 0.78f) else Color.White
    val button = if (blurred) Color.White.copy(alpha = 0.6f) else Color(0xFFF4F4F5)
}

/**
 * The card's edge, lit: a slow light runs round the rim (brighter while it reads, listens or
 * speaks), and a sheen catches the top, as on a pane of glass.
 */
@Composable
private fun GlassRim(energy: State<Float>, content: @Composable () -> Unit) {
    val transition = rememberInfiniteTransition(label = "rim")
    val angle = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 9000, easing = LinearEasing)),
        label = "angle",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .drawWithCache {
                val radius = CORNER.toPx()
                val stroke = RIM_WIDTH.toPx()
                val outer = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(radius))) }
                val inner = Path().apply {
                    addRoundRect(
                        RoundRect(
                            Rect(Offset(stroke, stroke), Size(size.width - 2 * stroke, size.height - 2 * stroke)),
                            CornerRadius(radius - stroke),
                        ),
                    )
                }
                val ring = Path().apply { op(outer, inner, PathOperation.Difference) }
                val light = Brush.sweepGradient(RimLight, size.center)
                val sheen = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.55f),
                    1f to Color.White.copy(alpha = 0f),
                    endY = SHEEN_HEIGHT.toPx(),
                )
                onDrawBehind {
                    drawPath(outer, sheen)
                    clipPath(ring) {
                        rotate(angle.value) {
                            drawCircle(light, radius = size.maxDimension, center = center, alpha = 0.25f + 0.75f * energy.value)
                        }
                    }
                }
            },
    ) {
        content()
    }
}

// ---- Header ----

@Composable
private fun Header(
    modifier: Modifier,
    awayMs: Long,
    needCount: Int,
    energy: State<Float>,
    reading: Boolean,
    speaking: Boolean,
    canSpeak: Boolean,
    glass: Glass,
    onSpeak: () -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 8.dp)
                .size(width = 36.dp, height = 4.dp)
                .clip(CircleShape)
                .background(Ink.copy(alpha = 0.16f)),
        )
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Mark(energy, reading)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("While you were away", style = TitleStyle, color = Ink)
                val away = if (awayMs > 0) "Away ${duration(awayMs)}" else "Today"
                val needs = if (needCount > 0) " · $needCount need${if (needCount == 1) "s" else ""} you" else ""
                Text(away + needs, style = SmallStyle, color = Ink3)
            }
            if (canSpeak) {
                RoundButton(
                    icon = if (speaking) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.VolumeUp,
                    label = if (speaking) "Stop reading" else "Read it to me",
                    background = glass.button,
                    onClick = onSpeak,
                )
                Spacer(Modifier.width(8.dp))
            }
            RoundButton(icon = Icons.Outlined.Close, label = "Close", background = glass.button, onClick = onClose)
        }
    }
}

/** The app's dot, breathing; while the model reads, a small light circles it. */
@Composable
private fun Mark(energy: State<Float>, reading: Boolean) {
    val transition = rememberInfiniteTransition(label = "mark")
    val breath = transition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    val orbit = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "orbit",
    )
    Canvas(Modifier.size(28.dp)) {
        val r = size.minDimension / 2
        drawCircle(Ink.copy(alpha = 0.1f + 0.1f * energy.value), radius = r * 0.78f * breath.value)
        drawCircle(Ink, radius = r * 0.32f)
        if (reading) {
            rotate(orbit.value) {
                drawArc(
                    color = Ink.copy(alpha = 0.7f),
                    startAngle = 0f,
                    sweepAngle = 70f,
                    useCenter = false,
                    topLeft = Offset(size.width / 2 - r * 0.92f, size.height / 2 - r * 0.92f),
                    size = Size(r * 1.84f, r * 1.84f),
                    style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, background: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, Ink.copy(alpha = 0.06f), CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Ink, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun Headline(headline: String?, reading: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 2.dp)) {
        AnimatedContent(
            targetState = headline,
            transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(140)) },
            label = "headline",
        ) { text ->
            if (text != null) Text(text, style = HeadlineStyle, color = Ink)
        }
        AnimatedVisibility(visible = reading) {
            ReadingLine(Modifier.padding(top = 6.dp))
        }
    }
}

/** "Reading your messages", with the dots going. */
@Composable
private fun ReadingLine(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "reading")
    val phase = transition.animateFloat(0f, 3f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "phase")
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 22.dp, height = 8.dp)) {
            repeat(3) { i ->
                val lift = (1f - abs(phase.value - i - 0.5f).coerceAtMost(1f))
                drawCircle(
                    Ink.copy(alpha = 0.3f + 0.6f * lift),
                    radius = 2.4.dp.toPx(),
                    center = Offset(4.dp.toPx() + i * 7.dp.toPx(), size.height / 2 - lift * 2.dp.toPx()),
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Text("Reading your messages and drafting replies", style = SmallStyle, color = Ink3)
    }
}

// ---- The list ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BriefList(
    shown: BriefViewModel.Shown,
    sends: Map<String, BriefViewModel.Send>,
    drafts: Map<String, String>,
    listening: BriefViewModel.Listening?,
    reading: Boolean,
    glass: Glass,
    viewModel: BriefViewModel,
    onListen: (BriefItem) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val now = remember(shown) { System.currentTimeMillis() }
    // Shown once: scrolled away and back, a tile doesn't make its entrance again.
    val appeared = remember { mutableSetOf<String>() }
    val opened = remember { mutableStateMapOf<String, Boolean>() }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Part of the list, so a long headline scrolls away rather than holding half the card.
        item(key = "#headline") { Headline(shown.headline, reading, Modifier.animateItem()) }
        if (shown.needs.isNotEmpty()) {
            item(key = "#needs") { SectionLabel("Needs you", shown.needs.size, Modifier.animateItem()) }
            itemsIndexed(shown.needs, key = { _, it -> it.key }) { index, item ->
                Appear(item.key, index, appeared, Modifier.animateItem()) {
                    NeedTile(
                        item = item,
                        now = now,
                        icon = viewModel.icon(item.packageName),
                        send = sends[item.key],
                        draft = drafts[item.key].orEmpty(),
                        listening = listening?.takeIf { it.key == item.key },
                        reading = reading,
                        opened = opened[item.key] ?: false,
                        canReply = viewModel.canReply(item),
                        canCallBack = viewModel.canCallBack(item),
                        glass = glass,
                        onToggle = { opened[item.key] = !(opened[item.key] ?: false) },
                        onDraft = { viewModel.editDraft(item.key, it) },
                        onSend = { text ->
                            if (viewModel.canReply(item)) viewModel.send(item, text) else viewModel.copyAndOpen(item, text, context)
                        },
                        onUndo = { viewModel.undo(item.key) },
                        onRetry = { viewModel.retry(item.key) },
                        onCopyAndOpen = { text -> viewModel.copyAndOpen(item, text, context) },
                        onListen = {
                            if (listening?.key == item.key) viewModel.finishListening() else onListen(item)
                        },
                        onCallBack = { if (!viewModel.callBack(item, context)) viewModel.open(item, context) },
                        onOpen = { viewModel.open(item, context) },
                        onDismiss = { viewModel.dismiss(item) },
                    )
                }
            }
        }
        if (shown.fyi.isNotEmpty()) {
            item(key = "#fyi") { SectionLabel("Worth knowing", shown.fyi.size, Modifier.animateItem()) }
            itemsIndexed(shown.fyi, key = { _, it -> it.key }) { index, item ->
                Appear(item.key, shown.needs.size + index, appeared, Modifier.animateItem()) {
                    FyiRow(
                        item = item,
                        now = now,
                        icon = viewModel.icon(item.packageName),
                        glass = glass,
                        onOpen = { viewModel.open(item, context) },
                        onDismiss = { viewModel.dismiss(item) },
                    )
                }
            }
        }
        if (shown.skipped.isNotEmpty()) {
            item(key = "#skipped") {
                LeftOut(
                    items = shown.skipped,
                    now = now,
                    glass = glass,
                    icon = viewModel::icon,
                    onOpen = { viewModel.open(it, context) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
        item(key = "#footer") {
            Row(Modifier.fillMaxWidth().animateItem().padding(top = 2.dp), horizontalArrangement = Arrangement.Center) {
                Text(
                    "Mark all as seen",
                    style = SmallStyle.copy(fontWeight = FontWeight.Medium),
                    color = Ink3,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = viewModel::dismissAll)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** A tile's entrance: up and in, one after another. */
@Composable
private fun Appear(key: String, index: Int, appeared: MutableSet<String>, modifier: Modifier, content: @Composable () -> Unit) {
    val progress = remember { Animatable(if (key in appeared) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (progress.value < 1f) {
            delay(ENTRANCE_DELAY_MS + ENTRANCE_STEP_MS * index.coerceAtMost(6))
            progress.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 240f))
        }
        appeared += key
    }
    Box(
        modifier.graphicsLayer {
            alpha = progress.value.coerceIn(0f, 1f)
            translationY = (1f - progress.value) * 22.dp.toPx()
        },
    ) {
        content()
    }
}

@Composable
private fun SectionLabel(title: String, count: Int, modifier: Modifier = Modifier) {
    Row(modifier.padding(start = 6.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), style = LabelStyle, color = Ink3)
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier.clip(CircleShape).background(Ink.copy(alpha = 0.08f)).padding(horizontal = 7.dp, vertical = 1.dp),
        ) {
            Text("$count", style = LabelStyle, color = Ink2)
        }
    }
}

// ---- Someone who needs the user ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NeedTile(
    item: BriefItem,
    now: Long,
    icon: ImageBitmap?,
    send: BriefViewModel.Send?,
    draft: String,
    listening: BriefViewModel.Listening?,
    reading: Boolean,
    opened: Boolean,
    canReply: Boolean,
    canCallBack: Boolean,
    glass: Glass,
    onToggle: () -> Unit,
    onDraft: (String) -> Unit,
    onSend: (String) -> Unit,
    onUndo: () -> Unit,
    onRetry: () -> Unit,
    onCopyAndOpen: (String) -> Unit,
    onListen: () -> Unit,
    onCallBack: () -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    SwipeAway(enabled = send == null && listening == null, onSwiped = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(TileShape)
                .background(glass.tile)
                .border(1.dp, glass.tileRim, TileShape)
                .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))
                .padding(12.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(enabled = item.kind == Kind.MESSAGE, onClickLabel = if (opened) "Show less" else "Show the messages", onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(item, icon)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            item.title,
                            style = NameStyle,
                            color = Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("${item.appLabel} · ${clock(item.latestAt, now, LocalContext.current)}", style = SmallStyle, color = Ink3, maxLines = 1)
                    }
                    Text(
                        subtitle(item),
                        style = BodyStyle,
                        color = Ink2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when (item.kind) {
                    Kind.CALL -> PillButton(
                        text = if (canCallBack) "Call back" else "Open",
                        icon = if (canCallBack) Icons.Outlined.Call else null,
                        dark = true,
                        onClick = if (canCallBack) onCallBack else onOpen,
                    )
                    Kind.OTHER -> PillButton(text = "Open", dark = false, onClick = onOpen)
                    Kind.MESSAGE -> if (item.count > 1) CountBadge(item.count)
                }
            }
            if (item.kind == Kind.MESSAGE) {
                Spacer(Modifier.height(10.dp))
                Bubbles(item, all = opened, glass = glass)
                ReplyArea(
                    item = item,
                    send = send,
                    draft = draft,
                    listening = listening,
                    reading = reading,
                    canReply = canReply,
                    glass = glass,
                    onDraft = onDraft,
                    onSend = onSend,
                    onUndo = onUndo,
                    onRetry = onRetry,
                    onCopyAndOpen = onCopyAndOpen,
                    onListen = onListen,
                )
            }
        }
    }
}

/** Under the name: the model's line on it once read; until then, which app and how many. */
private fun subtitle(item: BriefItem): String = when {
    item.kind == Kind.CALL -> item.gist
    item.kind == Kind.MESSAGE && item.read && item.gist != item.fromOthers.lastOrNull()?.text -> item.gist
    item.kind == Kind.MESSAGE -> if (item.count > 1) "${item.count} new messages" else "New message"
    else -> item.gist
}

/** What they said, as chat bubbles: the last line, or the last few when opened. */
@Composable
private fun Bubbles(item: BriefItem, all: Boolean, glass: Glass) {
    val lines = item.fromOthers.takeLast(if (all) 6 else 2)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { line ->
            Column(
                Modifier
                    .widthIn(max = 300.dp)
                    .clip(InShape)
                    .background(glass.field)
                    .border(1.dp, Ink.copy(alpha = 0.05f), InShape)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (item.isGroup && line.sender != null) Text(line.sender.orEmpty(), style = SmallStyle.copy(fontWeight = FontWeight.SemiBold), color = Ink2)
                Text(
                    line.text,
                    style = BodyStyle,
                    color = Ink,
                    maxLines = if (all) 8 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReplyArea(
    item: BriefItem,
    send: BriefViewModel.Send?,
    draft: String,
    listening: BriefViewModel.Listening?,
    reading: Boolean,
    canReply: Boolean,
    glass: Glass,
    onDraft: (String) -> Unit,
    onSend: (String) -> Unit,
    onUndo: () -> Unit,
    onRetry: () -> Unit,
    onCopyAndOpen: (String) -> Unit,
    onListen: () -> Unit,
) {
    AnimatedContent(
        targetState = send,
        contentKey = { it?.javaClass },
        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
        label = "reply",
    ) { state ->
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            when (state) {
                is BriefViewModel.Send.Countdown -> Outgoing(state.text) {
                    CountdownRing(state.endsAt)
                    Spacer(Modifier.width(6.dp))
                    Text("Sending", style = SmallStyle, color = Ink3)
                    Spacer(Modifier.width(10.dp))
                    PillButton(text = "Undo", dark = false, onClick = onUndo)
                }
                is BriefViewModel.Send.Sending -> Outgoing(state.text) {
                    Text("Sending…", style = SmallStyle, color = Ink3)
                }
                is BriefViewModel.Send.Sent -> Outgoing(state.text) {
                    SentTick()
                }
                is BriefViewModel.Send.NoButton -> Outgoing(state.text) {
                    Text("Can't send from here", style = SmallStyle, color = Ink3)
                    Spacer(Modifier.width(8.dp))
                    PillButton(text = "Copy & open", icon = Icons.Outlined.ContentCopy, dark = true, onClick = { onCopyAndOpen(state.text) })
                }
                is BriefViewModel.Send.Failed -> Outgoing(state.text) {
                    Text("Didn't go", style = SmallStyle, color = Danger)
                    Spacer(Modifier.width(8.dp))
                    PillButton(text = "Edit", dark = false, onClick = onRetry)
                    Spacer(Modifier.width(6.dp))
                    PillButton(text = "Copy & open", dark = true, onClick = { onCopyAndOpen(state.text) })
                }
                null -> {
                    when {
                        item.replies.isNotEmpty() -> FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            item.replies.forEach { reply -> Suggestion(reply, glass) { onSend(reply) } }
                        }
                        reading && !item.read -> DraftingChips()
                    }
                    Spacer(Modifier.height(8.dp))
                    Composer(
                        name = item.title,
                        appLabel = item.appLabel,
                        draft = draft,
                        listening = listening,
                        canReply = canReply,
                        glass = glass,
                        onDraft = onDraft,
                        onSend = { onSend(draft) },
                        onListen = onListen,
                    )
                }
            }
        }
    }
}

/** A drafted reply: one tap sends it (after the countdown). */
@Composable
private fun Suggestion(text: String, glass: Glass, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(ChipShape)
            .background(glass.chip)
            .border(1.dp, Ink.copy(alpha = 0.1f), ChipShape)
            .clickable(role = Role.Button, onClickLabel = "Send this reply", onClick = onClick)
            .padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = BodyStyle, color = Ink, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(6.dp))
        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null, tint = Ink3, modifier = Modifier.size(14.dp))
    }
}

/** Where drafted replies will be, shimmering while the model writes them. */
@Composable
private fun DraftingChips() {
    val transition = rememberInfiniteTransition(label = "drafting")
    val sweep = transition.animateFloat(-1f, 2f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "sweep")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(132.dp, 96.dp).forEach { width ->
            Box(
                Modifier
                    .size(width = width, height = 34.dp)
                    .clip(ChipShape)
                    .drawWithCache {
                        val base = Ink.copy(alpha = 0.06f)
                        onDrawBehind {
                            drawRect(base)
                            val x = sweep.value * size.width
                            drawRect(
                                Brush.horizontalGradient(
                                    listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.75f), Color.White.copy(alpha = 0f)),
                                    startX = x - size.width * 0.5f,
                                    endX = x + size.width * 0.5f,
                                ),
                            )
                        }
                    },
            )
        }
    }
}

/** The reply box: type, or the mic to say it, then send. */
@Composable
private fun Composer(
    name: String,
    appLabel: String,
    draft: String,
    listening: BriefViewModel.Listening?,
    canReply: Boolean,
    glass: Glass,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onListen: () -> Unit,
) {
    val hearing = listening != null && !listening.confirming
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 46.dp)
                .clip(FieldShape)
                .background(glass.field)
                .border(1.dp, if (listening != null) Ink.copy(alpha = 0.35f) else Ink.copy(alpha = 0.1f), FieldShape)
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                if (hearing) {
                    LevelBars(listening?.level ?: 0f)
                } else {
                    BasicTextField(
                        value = draft,
                        onValueChange = onDraft,
                        textStyle = BodyStyle.copy(color = Ink),
                        cursorBrush = SolidColor(Ink),
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { field ->
                            if (draft.isEmpty()) {
                                Text(
                                    if (canReply) "Reply to ${firstName(name)}" else "Reply (opens $appLabel)",
                                    style = BodyStyle,
                                    color = Ink3.copy(alpha = 0.8f),
                                    maxLines = 1,
                                )
                            }
                            field()
                        },
                    )
                }
            }
            MicButton(active = listening != null, level = listening?.level ?: 0f, onClick = onListen)
            AnimatedVisibility(visible = draft.isNotBlank() && !hearing) {
                Row {
                    Spacer(Modifier.width(4.dp))
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Ink)
                            .clickable(role = Role.Button, onClickLabel = "Send", onClick = onSend),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(17.dp))
                    }
                }
            }
        }
        AnimatedVisibility(visible = listening != null) {
            Text(
                if (hearing) "Listening… say your reply" else "Say “send” to send it, or “cancel”",
                style = SmallStyle,
                color = Ink3,
                modifier = Modifier.padding(start = 14.dp, top = 6.dp),
            )
        }
    }
}

@Composable
private fun MicButton(active: Boolean, level: Float, onClick: () -> Unit) {
    val ring by animateFloatAsState(if (active) 0.25f + 0.75f * level.coerceIn(0f, 1f) else 0f, spring(stiffness = Spring.StiffnessMedium), label = "ring")
    Box(
        Modifier
            .size(38.dp)
            .drawWithCache {
                onDrawBehind {
                    if (ring > 0f) drawCircle(Ink.copy(alpha = 0.12f), radius = size.minDimension / 2 * (0.8f + 0.35f * ring))
                }
            }
            .clip(CircleShape)
            .background(if (active) Ink else Color.Transparent)
            .clickable(role = Role.Button, onClickLabel = if (active) "Done talking" else "Say your reply", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (active) Icons.Outlined.Stop else Icons.Outlined.Mic,
            contentDescription = if (active) "Done talking" else "Say your reply",
            tint = if (active) Color.White else Ink2,
            modifier = Modifier.size(19.dp),
        )
    }
}

/** The voice coming in, as a row of bars. */
@Composable
private fun LevelBars(level: Float) {
    val transition = rememberInfiniteTransition(label = "bars")
    val phase = transition.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(900, easing = LinearEasing)), label = "phase")
    val shown by animateFloatAsState(level.coerceIn(0.08f, 1f), spring(stiffness = Spring.StiffnessMediumLow), label = "level")
    Canvas(Modifier.fillMaxWidth().height(22.dp)) {
        val bars = 24
        val gap = size.width / bars
        repeat(bars) { i ->
            val wave = (kotlin.math.sin(phase.value + i * 0.55f) + 1f) / 2f
            val h = size.height * (0.15f + 0.85f * shown * (0.35f + 0.65f * wave))
            val x = gap * i + gap / 2
            drawLine(Ink.copy(alpha = 0.75f), Offset(x, (size.height - h) / 2), Offset(x, (size.height + h) / 2), strokeWidth = 2.6.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

/** The user's reply as it goes out: a dark bubble on the right, with how it's getting on beneath. */
@Composable
private fun Outgoing(text: String, status: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Text(
            text,
            style = BodyStyle,
            color = Color.White,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(OutShape)
                .background(Brush.linearGradient(listOf(Color(0xFF3F3F46), Ink)))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) { status() }
    }
}

/** The seconds left to take a reply back, running out round a ring. */
@Composable
private fun CountdownRing(endsAt: Long) {
    val left = remember(endsAt) {
        Animatable(((endsAt - System.currentTimeMillis()).toFloat() / BriefViewModel.UNDO_MS).coerceIn(0f, 1f))
    }
    LaunchedEffect(endsAt) {
        left.animateTo(0f, tween((left.value * BriefViewModel.UNDO_MS).toInt(), easing = LinearEasing))
    }
    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(22.dp)) {
            val stroke = 2.2.dp.toPx()
            drawCircle(Ink.copy(alpha = 0.12f), radius = size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
            drawArc(
                Ink,
                startAngle = -90f,
                sweepAngle = 360f * left.value,
                useCenter = false,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Text(
            "${ceil(left.value * BriefViewModel.UNDO_MS / 1000f).toInt().coerceAtLeast(1)}",
            style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
            color = Ink,
        )
    }
}

@Composable
private fun SentTick() {
    val pop = remember { Animatable(0.4f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f)) }
    Row(Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.DoneAll, contentDescription = null, tint = Ink, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("Sent", style = SmallStyle.copy(fontWeight = FontWeight.SemiBold), color = Ink)
    }
}

// ---- Worth knowing, and what was left out ----

@Composable
private fun FyiRow(item: BriefItem, now: Long, icon: ImageBitmap?, glass: Glass, onOpen: () -> Unit, onDismiss: () -> Unit) {
    SwipeAway(enabled = true, onSwiped = onDismiss) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RowShape)
                .background(glass.row)
                .clickable(onClickLabel = "Open", onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(icon, 26.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = NameStyle.copy(fontSize = 14.sp), color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.gist, style = BodyStyle.copy(fontSize = 13.sp), color = Ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Text(clock(item.latestAt, now, LocalContext.current), style = SmallStyle, color = Ink3)
        }
    }
}

/** The noise, counted by app; tap an app to see what it was. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LeftOut(
    items: List<BriefItem>,
    now: Long,
    glass: Glass,
    icon: (String) -> ImageBitmap?,
    onOpen: (BriefItem) -> Unit,
    modifier: Modifier,
) {
    var openApp by remember { mutableStateOf<String?>(null) }
    val byApp = items.groupBy { it.packageName }
    Column(modifier.fillMaxWidth().animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))) {
        SectionLabel("Left out", items.sumOf { it.count })
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            byApp.forEach { (pkg, appItems) ->
                val selected = openApp == pkg
                Row(
                    Modifier
                        .clip(ChipShape)
                        .background(if (selected) Ink else glass.chip)
                        .border(1.dp, Ink.copy(alpha = 0.08f), ChipShape)
                        .clickable(role = Role.Button) { openApp = if (selected) null else pkg }
                        .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(icon(pkg), 20.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${appItems.first().appLabel} · ${appItems.sumOf { it.count }}",
                        style = SmallStyle.copy(fontWeight = FontWeight.Medium),
                        color = if (selected) Color.White else Ink2,
                    )
                }
            }
        }
        val open = openApp?.let { byApp[it] }
        if (open != null) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                open.take(8).forEach { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onOpen(item) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            buildString {
                                append(item.title)
                                append(": ")
                                append(item.gist)
                            },
                            style = BodyStyle.copy(fontSize = 13.sp),
                            color = Ink2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(clock(item.latestAt, now, LocalContext.current), style = SmallStyle, color = Ink3)
                    }
                }
            }
        }
    }
}

// ---- Pieces ----

/** Swiped sideways far enough, it flies off and is dealt with; not far enough, it springs back. */
@Composable
private fun SwipeAway(enabled: Boolean, onSwiped: () -> Unit, content: @Composable () -> Unit) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var width by remember { mutableIntStateOf(1) }
    Box(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (abs(offset.value) > width * SWIPE_SHARE) {
                                offset.animateTo(sign(offset.value) * width * 1.1f, tween(170))
                                onSwiped()
                            } else {
                                offset.animateTo(0f, spring(dampingRatio = 0.7f))
                            }
                        }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f, spring()) } },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        scope.launch { offset.snapTo(offset.value + amount) }
                    },
                )
            }
            .graphicsLayer {
                translationX = offset.value
                val share = (abs(offset.value) / width).coerceIn(0f, 1f)
                alpha = 1f - share * 0.85f
                rotationZ = offset.value / width * 4f
            },
    ) {
        content()
    }
}

/** A person's initials (or a phone, for a caller), with their app's icon tucked in the corner. */
@Composable
private fun Avatar(item: BriefItem, icon: ImageBitmap?) {
    Box(Modifier.size(46.dp)) {
        Box(
            Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xFF52525B), Ink))),
            contentAlignment = Alignment.Center,
        ) {
            if (item.kind == Kind.CALL) {
                Icon(Icons.Outlined.Call, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
            } else {
                Text(initials(item.title), style = NameStyle.copy(fontSize = 15.sp), color = Color.White)
            }
        }
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(19.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(1.5.dp, Color.White, CircleShape),
            )
        }
    }
}

@Composable
private fun AppIcon(icon: ImageBitmap?, size: Dp) {
    if (icon != null) {
        Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(size).clip(CircleShape))
    } else {
        Box(Modifier.size(size).clip(CircleShape).background(Ink.copy(alpha = 0.1f)))
    }
}

@Composable
private fun CountBadge(count: Int) {
    Box(
        Modifier.padding(start = 8.dp).size(24.dp).clip(CircleShape).background(Ink),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (count > 9) "9+" else "$count", style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = Color.White)
    }
}

@Composable
private fun PillButton(text: String, dark: Boolean, onClick: () -> Unit, icon: ImageVector? = null) {
    Row(
        Modifier
            .padding(start = 6.dp)
            .clip(ChipShape)
            .background(if (dark) Ink else Color.White.copy(alpha = 0.85f))
            .border(1.dp, if (dark) Ink else Ink.copy(alpha = 0.12f), ChipShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (dark) Color.White else Ink, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, style = SmallStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp), color = if (dark) Color.White else Ink)
    }
}

@Composable
private fun Notice(text: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = SmallStyle.copy(fontSize = 13.sp),
            color = Color.White,
            modifier = Modifier.clip(ChipShape).background(Ink.copy(alpha = 0.88f)).padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/** Nothing left: a tick draws itself in. */
@Composable
private fun CaughtUp(hadItems: Boolean, newOnly: Boolean) {
    val circle = remember { Animatable(0f) }
    val tick = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        circle.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 380f))
        tick.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
    }
    Column(
        Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(56.dp).graphicsLayer { scaleX = circle.value; scaleY = circle.value }) {
            drawCircle(Ink)
            val path = Path().apply {
                moveTo(size.width * 0.30f, size.height * 0.52f)
                lineTo(size.width * 0.44f, size.height * 0.66f)
                lineTo(size.width * 0.71f, size.height * 0.38f)
            }
            val measure = PathMeasure().apply { setPath(path, false) }
            val drawn = Path()
            measure.getSegment(0f, measure.length * tick.value, drawn, true)
            drawPath(drawn, Color.White, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Spacer(Modifier.height(12.dp))
        Text("You're all caught up", style = TitleStyle.copy(fontSize = 18.sp), color = Ink)
        Text(
            when {
                hadItems -> "Nothing else is waiting on you."
                newOnly -> "Nothing new came in while you were away."
                else -> "Nothing from today is waiting on you."
            },
            style = BodyStyle,
            color = Ink3,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

// ---- Words ----

private fun initials(name: String): String {
    val words = name.split(Regex("\\s+")).filter { it.firstOrNull()?.isLetter() == true }
    return when (words.size) {
        0 -> "#"
        1 -> words[0].take(1)
        else -> words[0].take(1) + words[1].take(1)
    }.uppercase()
}

private fun firstName(name: String): String = name.split(Regex("\\s+")).firstOrNull { it.isNotBlank() } ?: name

/** "now", "12m", or the time today ("4:05 pm"), "Yesterday" before that. */
private fun clock(at: Long, now: Long, context: android.content.Context): String {
    val minutes = (now - at) / 60_000
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        DateUtils.isToday(at) -> DateFormat.getTimeFormat(context).format(Date(at))
        else -> "Yesterday"
    }
}

/** "45 min", "2 h 10 min", "1 day". */
private fun duration(ms: Long): String {
    val minutes = (ms / 60_000).coerceAtLeast(1)
    return when {
        minutes < 60 -> "$minutes min"
        minutes < 24 * 60 -> if (minutes % 60 == 0L) "${minutes / 60} h" else "${minutes / 60} h ${minutes % 60} min"
        else -> if (minutes / (24 * 60) == 1L) "1 day" else "${minutes / (24 * 60)} days"
    }
}

// ---- Look ----

private val Ink = Color(0xFF18181B)
private val Ink2 = Color(0xFF3F3F46)
private val Ink3 = Color(0xFF52525B)
private val Danger = Color(0xFFDC2626)

/** Light moving round the rim: white and the app's greys. */
private val RimLight = listOf(
    Color.White,
    Color(0xFFA1A1AA),
    Color.White.copy(alpha = 0.4f),
    Color(0xFFD4D4D8),
    Color(0xFF71717A),
    Color.White,
)

private val CORNER = BriefActivity.CORNER_DP.dp
private val RIM_WIDTH = 1.5.dp
private val SHEEN_HEIGHT = 64.dp
private val CLOSE_DRAG = 90.dp
private const val SWIPE_SHARE = 0.32f
private const val ENTRANCE_DELAY_MS = 90L
private const val ENTRANCE_STEP_MS = 60L
private const val CAUGHT_UP_MS = 2_600L
private const val CAUGHT_UP_AFTER_DONE_MS = 1_400L
private const val NOTICE_MS = 4_000L

private val CardShape = RoundedCornerShape(CORNER)
private val TileShape = RoundedCornerShape(22.dp)
private val RowShape = RoundedCornerShape(16.dp)
private val ChipShape = RoundedCornerShape(50)
private val FieldShape = RoundedCornerShape(23.dp)
private val InShape = RoundedCornerShape(topStart = 6.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp)
private val OutShape = RoundedCornerShape(topStart = 18.dp, topEnd = 6.dp, bottomEnd = 18.dp, bottomStart = 18.dp)

private val TitleStyle = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
private val HeadlineStyle = TextStyle(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
private val NameStyle = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
private val BodyStyle = TextStyle(fontSize = 14.sp, lineHeight = 19.sp)
private val SmallStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
private val LabelStyle = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.1.sp)
