package com.local.assistant.assist

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.db.MessageEntity
import com.local.assistant.data.db.Role
import com.local.assistant.device.PermissionBroker
import com.local.assistant.llm.LlmService
import com.local.assistant.ui.chat.MarkdownText
import com.local.assistant.ui.chat.MemoryChips
import com.local.assistant.ui.chat.ThinkingIndicator
import com.local.assistant.ui.theme.AppColors
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The overlay's whole screen: a dimmed view of whatever was open, and the assistant's panel
 * rising from the bottom. The panel's edge carries a slow sweep of light, brighter while the
 * assistant listens, thinks or speaks; a waveform follows the voice; the orb in the middle is
 * the one button that matters (talk, send, stop).
 */
@Composable
fun AssistOverlay(
    viewModel: AssistViewModel,
    unlocked: Boolean,
    onClosed: () -> Unit,
    onOpenApp: (chatId: Long?) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()

    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val chips by viewModel.chips.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val streaming by viewModel.streamingText.collectAsStateWithLifecycle()
    val listening by viewModel.listening.collectAsStateWithLifecycle()
    val level by viewModel.level.collectAsStateWithLifecycle()
    val speaking by viewModel.speaking.collectAsStateWithLifecycle()
    val input by viewModel.input.collectAsStateWithLifecycle()
    val muted by viewModel.muted.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val engine by viewModel.engineState.collectAsStateWithLifecycle()
    val chatId by viewModel.chatId.collectAsStateWithLifecycle()
    val askForNotifications by viewModel.askForNotifications.collectAsStateWithLifecycle()

    // ---- Permissions: the microphone, a tool's (contacts), and notifications for a reminder ----

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startListening() else viewModel.onMicDenied()
    }
    val listen: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.startListening() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    // Straight into listening as the panel rises — once unlocked, if the phone was locked.
    LaunchedEffect(unlocked) {
        if (unlocked && viewModel.claimAutoStart()) listen()
    }

    var permissionRequest by remember { mutableStateOf<PermissionBroker.Request?>(null) }
    val toolPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionRequest?.answer?.complete(granted)
        permissionRequest = null
    }
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.permissionRequests.collect { request ->
                permissionRequest = request
                toolPermission.launch(request.permission)
            }
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.notificationsAsked()
    }
    LaunchedEffect(askForNotifications) {
        if (!askForNotifications) return@LaunchedEffect
        val needed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needed) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else viewModel.notificationsAsked()
    }

    // ---- In and out ----

    val appear = remember { Animatable(0f) }
    val drag = remember { Animatable(0f) }
    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 300f))
    }
    val dismiss: () -> Unit = {
        if (!leaving) {
            leaving = true
            scope.launch {
                appear.animateTo(0f, tween(durationMillis = 220, easing = FastOutLinearInEasing))
                onClosed()
            }
        }
    }
    BackHandler(onBack = dismiss)

    // ---- What the assistant is doing, for the light, the orb and the waveform ----

    val noModel = engine is LlmService.State.NoModel
    val failed = engine as? LlmService.State.Failed
    val waiting = pending != null && streaming == null
    val busy = streaming != null || pending != null
    val orb = when {
        listening -> OrbMode.LISTENING
        busy || speaking -> OrbMode.BUSY
        else -> OrbMode.IDLE
    }
    val shownLevel = displayLevel(level)
    val energy = animateFloatAsState(
        targetValue = when {
            !unlocked -> 0.15f
            listening -> 0.55f + 0.45f * shownLevel
            speaking -> 0.85f
            busy -> 0.7f
            else -> 0.2f
        },
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "energy",
    )

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = appear.value.coerceIn(0f, 1f) }
                .background(Scrim)
                .pointerInput(Unit) { detectTapGestures { dismiss() } },
        )

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 10.dp, end = 10.dp, top = 48.dp, bottom = 10.dp)
                .graphicsLayer {
                    val progress = appear.value
                    // A spring past 1 lifts the panel a touch before it settles: the "pop".
                    translationY = (1f - progress) * (size.height + 64.dp.toPx()) + drag.value
                    val scale = 0.92f + 0.08f * progress.coerceAtMost(1f)
                    scaleX = scale
                    scaleY = scale
                    // No fade: a translucent layer would clip the glow to the panel's edge.
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
                // A tap on the panel stays on the panel, rather than reaching the scrim and closing it.
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            GlowPanel(energy) {
                // The handle and the header drag the panel down to close it.
                val grab = Modifier.pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (drag.value > DISMISS_DRAG.toPx()) dismiss() else drag.animateTo(0f, spring())
                            }
                        },
                        onDragCancel = { scope.launch { drag.animateTo(0f, spring()) } },
                        onVerticalDrag = { change, amount ->
                            change.consume()
                            scope.launch { drag.snapTo((drag.value + amount).coerceAtLeast(0f)) }
                        },
                    )
                }
                Column(grab.fillMaxWidth()) {
                    Box(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 10.dp)
                            .size(width = 36.dp, height = 4.dp)
                            .clip(CircleShape)
                            .background(AppColors.Border),
                    )
                    Header(
                        energy = energy,
                        muted = muted,
                        showMute = unlocked && !noModel,
                        onToggleMute = viewModel::toggleMute,
                        onOpenApp = { onOpenApp(chatId) },
                    )
                }

                val hasTranscript = messages.isNotEmpty() || pending != null || streaming != null
                when {
                    !unlocked -> Headline("Unlock to continue", "The assistant opens once your phone is unlocked.")
                    noModel -> NeedsApp("The assistant needs its model first. Open the app to download or import it.", onOpenApp = { onOpenApp(null) })
                    failed != null && !hasTranscript -> NeedsApp("${failed.message} Open the app to try again.", onOpenApp = { onOpenApp(null) })
                    hasTranscript -> Transcript(
                        messages = messages,
                        chips = chips,
                        pending = pending,
                        streaming = streaming,
                        waiting = waiting,
                        modelLoading = engine !is LlmService.State.Ready,
                        viewModel = viewModel,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    listening -> Headline("Listening…", "Ask anything. It sends when you pause.")
                    input == AssistViewModel.Input.TEXT -> Headline("How can I help?", null)
                    else -> Headline("How can I help?", "Tap the mic and ask.")
                }

                if (unlocked && !noModel) {
                    if (listening || speaking) {
                        VoiceWave(
                            level = shownLevel,
                            speaking = speaking && !listening,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    if (!hasTranscript && engine !is LlmService.State.Ready && failed == null) {
                        Caption("Waking up the model. It answers as soon as it's ready.")
                    }
                    notice?.let { Caption(it, onClick = viewModel::dismissNotice) }

                    if (input == AssistViewModel.Input.VOICE) {
                        VoiceControls(
                            orb = orb,
                            level = shownLevel,
                            onOrb = {
                                when (orb) {
                                    OrbMode.LISTENING -> viewModel.finishListening()
                                    OrbMode.BUSY -> viewModel.stop()
                                    OrbMode.IDLE -> listen()
                                }
                            },
                            onKeyboard = viewModel::switchToText,
                            onClose = dismiss,
                        )
                    } else {
                        TextControls(
                            busy = busy,
                            canTalk = engine !is LlmService.State.Ready || viewModel.hearsAudio(),
                            onSend = viewModel::sendText,
                            onStop = viewModel::stop,
                            onTalk = listen,
                        )
                    }
                } else {
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

private enum class OrbMode { LISTENING, BUSY, IDLE }

/** The panel, and the light that runs around its edge (and, from Android 12, glows past it). */
@Composable
private fun GlowPanel(energy: State<Float>, content: @Composable ColumnScope.() -> Unit) {
    val transition = rememberInfiniteTransition(label = "glow")
    val angle = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 4200, easing = LinearEasing)),
        label = "angle",
    )
    Box {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = 0.1f + 0.45f * energy.value }
                    .blur(24.dp, BlurredEdgeTreatment.Unbounded)
                    .drawWithCache {
                        val outline = PanelShape.createOutline(size, layoutDirection, this)
                        val path = Path().apply { addOutline(outline) }
                        val brush = Brush.sweepGradient(Sheen, size.center)
                        onDrawBehind {
                            clipPath(path) {
                                rotate(angle.value) { drawCircle(brush, radius = size.maxDimension, center = center) }
                            }
                        }
                    },
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(PanelShape)
                .background(AppColors.Border)
                .drawWithCache {
                    val brush = Brush.sweepGradient(Sheen, size.center)
                    onDrawBehind {
                        rotate(angle.value) {
                            drawCircle(brush, radius = size.maxDimension, center = center, alpha = 0.35f + 0.65f * energy.value)
                        }
                    }
                }
                .padding(RING_WIDTH)
                .clip(InnerShape)
                .background(AppColors.Background)
                .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)),
            content = content,
        )
    }
}

@Composable
private fun Header(
    energy: State<Float>,
    muted: Boolean,
    showMute: Boolean,
    onToggleMute: () -> Unit,
    onOpenApp: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistantMark(energy)
        Spacer(Modifier.width(10.dp))
        Text("Assistant", style = MaterialTheme.typography.titleMedium, color = AppColors.TextPrimary)
        Spacer(Modifier.weight(1f))
        if (showMute) {
            IconButton(onClick = onToggleMute) {
                Icon(
                    if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                    contentDescription = if (muted) "Read answers aloud" else "Don't read answers aloud",
                    tint = AppColors.TextSecondary,
                )
            }
        }
        IconButton(onClick = onOpenApp) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Open in app", tint = AppColors.TextSecondary)
        }
    }
}

/** The app's dot, alive: it breathes, and brightens a ring around itself with the light. */
@Composable
private fun AssistantMark(energy: State<Float>) {
    val transition = rememberInfiniteTransition(label = "mark")
    val breath = transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    Canvas(Modifier.size(18.dp)) {
        val r = size.minDimension / 2
        drawCircle(AppColors.TextPrimary.copy(alpha = 0.12f * energy.value), radius = r * breath.value)
        drawCircle(AppColors.TextPrimary, radius = r * 0.45f)
    }
}

@Composable
private fun Headline(title: String, subtitle: String?) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
        Text(title, style = HeadlineStyle, color = AppColors.TextPrimary)
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun NeedsApp(message: String, onOpenApp: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
        OutlinedButton(onClick = onOpenApp, modifier = Modifier.padding(top = 12.dp)) { Text("Open app") }
    }
}

@Composable
private fun Caption(text: String, onClick: (() -> Unit)? = null) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = AppColors.TextSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/** This overlay's exchanges, newest at the bottom and kept in view as the reply streams. */
@Composable
private fun Transcript(
    messages: List<MessageEntity>,
    chips: Map<Long, List<com.local.assistant.ui.chat.ChatViewModel.ChipItem>>,
    pending: AssistViewModel.Pending?,
    streaming: String?,
    waiting: Boolean,
    modelLoading: Boolean,
    viewModel: AssistViewModel,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val uriHandler = LocalUriHandler.current
    val openLink: (String) -> Unit = { url ->
        if (url.startsWith("https://") || url.startsWith("http://")) runCatching { uriHandler.openUri(url) }
    }
    LaunchedEffect(scroll.maxValue) { scroll.animateScrollTo(scroll.maxValue) }

    Column(modifier.fillMaxWidth().verticalScroll(scroll).padding(top = 6.dp, bottom = 4.dp)) {
        val asking = messages.lastOrNull()?.takeIf { streaming != null && it.role == Role.USER }
        messages.forEach { message ->
            when (message.role) {
                Role.USER -> Question(message.text, message.attachmentKind == AttachmentKind.AUDIO, message.attachmentDurationMs)
                Role.ASSISTANT -> Answer(message.text)
                Role.TOOL -> Unit
            }
            if (message != asking) {
                MemoryChips(
                    chips = chips[message.id].orEmpty(),
                    onUndo = viewModel::undo,
                    onEditTime = viewModel::editTime,
                    onOpenClock = viewModel::openClock,
                    onOpenLink = openLink,
                )
            }
        }
        pending?.let { Question(it.text, it.audioPath != null, it.durationMs) }
        when {
            waiting || streaming?.isEmpty() == true ->
                ThinkingIndicator(label = if (modelLoading) "Loading model…" else "Thinking…")
            streaming != null -> Answer(streaming)
        }
        asking?.let {
            MemoryChips(
                chips = chips[it.id].orEmpty(),
                onUndo = viewModel::undo,
                onEditTime = viewModel::editTime,
                onOpenClock = viewModel::openClock,
                onOpenLink = openLink,
            )
        }
    }
}

/** What the user asked: their words, or their voice message, in the app's grey bubble. */
@Composable
private fun Question(text: String, voice: Boolean, durationMs: Long?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalAlignment = Alignment.End) {
        if (voice) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(AppColors.SurfaceMuted)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Mic, contentDescription = null, tint = AppColors.TextPrimary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    listOfNotNull("Voice message", durationMs?.let(::formatSeconds)).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextPrimary,
                )
            }
        }
        if (text.isNotBlank()) {
            SelectionContainer {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppColors.TextPrimary,
                    modifier = Modifier
                        .padding(top = if (voice) 4.dp else 0.dp)
                        .widthIn(max = 280.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(AppColors.SurfaceMuted)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Answer(text: String) {
    SelectionContainer(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        MarkdownText(text)
    }
}

/**
 * Three sine lines under one envelope, like a voice seen from far off. Their height follows the
 * microphone while listening, and a steady cadence while the assistant speaks.
 */
@Composable
private fun VoiceWave(level: Float, speaking: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "wave")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(durationMillis = 2400, easing = LinearEasing)),
        label = "phase",
    )
    val cadence = transition.animateFloat(
        initialValue = 0.28f,
        targetValue = 0.62f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 380, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "cadence",
    )
    val height = animateFloatAsState(
        targetValue = if (speaking) 0.45f else level,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "height",
    )
    Canvas(modifier.fillMaxWidth().height(56.dp)) {
        val amplitude = if (speaking) cadence.value else height.value
        val middle = size.height / 2
        val step = 3.dp.toPx()
        for (wave in Waves) {
            val path = Path()
            var x = 0f
            while (x <= size.width) {
                val t = x / size.width
                val envelope = sin(PI * t).toFloat().let { it * it }
                val angle = t * wave.cycles * 2 * PI + phase.value * wave.speed + wave.offset
                val y = middle + sin(angle).toFloat() * envelope * amplitude * middle * wave.scale
                if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                x += step
            }
            drawPath(
                path = path,
                color = AppColors.TextPrimary.copy(alpha = wave.alpha),
                style = Stroke(width = wave.width.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun VoiceControls(
    orb: OrbMode,
    level: Float,
    onOrb: () -> Unit,
    onKeyboard: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundAction(Icons.Outlined.Keyboard, "Type instead", onKeyboard)
        Orb(orb, level, onOrb)
        RoundAction(Icons.Outlined.Close, "Close", onClose)
    }
}

/**
 * The one button: a mic when idle, send while listening (it swells with the voice), stop while
 * the assistant thinks or speaks. Rings ripple out of it whenever it is doing something, and a
 * light circles it while an answer is on its way.
 */
@Composable
private fun Orb(mode: OrbMode, level: Float, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "orb")
    val ripple = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1800, easing = LinearEasing)),
        label = "ripple",
    )
    val breath = transition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    val spin = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1300, easing = LinearEasing)),
        label = "spin",
    )
    val swell = animateFloatAsState(
        targetValue = if (mode == OrbMode.LISTENING) 1f + 0.28f * level else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "swell",
    )

    Box(Modifier.size(ORB_AREA), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(ORB_AREA)) {
            val core = ORB_SIZE.toPx() / 2
            val reach = size.minDimension / 2
            if (mode != OrbMode.IDLE) {
                for (i in 0 until 2) {
                    val t = (ripple.value + i * 0.5f) % 1f
                    drawCircle(
                        color = AppColors.TextPrimary.copy(alpha = (1f - t) * 0.22f),
                        radius = core + (reach - core) * t,
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
            }
            if (mode == OrbMode.BUSY) {
                rotate(spin.value) {
                    drawCircle(
                        brush = Brush.sweepGradient(listOf(Color.Transparent, AppColors.TextSecondary, AppColors.TextPrimary), center),
                        radius = core + 5.dp.toPx(),
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
        }
        Box(
            Modifier
                .size(ORB_SIZE)
                .graphicsLayer {
                    val scale = if (mode == OrbMode.IDLE) breath.value else swell.value
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFF3F3F46), AppColors.Accent), radius = 90f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            val (icon, label) = when (mode) {
                OrbMode.LISTENING -> Icons.Outlined.ArrowUpward to "Send"
                OrbMode.BUSY -> Icons.Outlined.Stop to "Stop"
                OrbMode.IDLE -> Icons.Outlined.Mic to "Talk"
            }
            Icon(icon, contentDescription = label, tint = AppColors.OnAccent, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(AppColors.SurfaceMuted)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = AppColors.TextPrimary, modifier = Modifier.size(22.dp))
    }
}

/** Typing instead: the app's composer, with the mic to go back to talking. */
@Composable
private fun TextControls(
    busy: Boolean,
    canTalk: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onTalk: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    val submit = {
        if (text.isNotBlank() && !busy) {
            onSend(text)
            text = ""
        }
    }

    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (canTalk) RoundAction(Icons.Outlined.Mic, "Talk instead", onTalk)
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(AppColors.SurfaceMuted)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (text.isEmpty()) {
                Text("Ask anything", style = MaterialTheme.typography.bodyLarge, color = AppColors.TextSecondary)
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = MaterialTheme.typography.bodyLarge.merge(TextStyle(color = AppColors.TextPrimary)),
                cursorBrush = SolidColor(AppColors.TextPrimary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                maxLines = 4,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        val enabled = busy || text.isNotBlank()
        IconButton(
            onClick = { if (busy) onStop() else submit() },
            enabled = enabled,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (enabled) AppColors.Accent else AppColors.Border),
        ) {
            Icon(
                if (busy) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.Send,
                contentDescription = if (busy) "Stop" else "Send",
                tint = AppColors.OnAccent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** The recorder's peak level, on a curve the eye reads as loudness, with a floor so the line never goes flat. */
private fun displayLevel(peak: Float): Float = (sqrt(peak.coerceAtLeast(0f)) * 1.5f).coerceIn(0.08f, 1f)

private fun formatSeconds(ms: Long): String {
    val seconds = (ms + 500) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private class Wave(
    val cycles: Float,
    /** Whole numbers, so the lines loop without a jump. */
    val speed: Int,
    val offset: Float,
    val scale: Float,
    val alpha: Float,
    val width: Float,
)

private val Waves = listOf(
    Wave(cycles = 1.6f, speed = 1, offset = 0f, scale = 1f, alpha = 0.9f, width = 2.5f),
    Wave(cycles = 2.3f, speed = -2, offset = 1.2f, scale = 0.7f, alpha = 0.4f, width = 1.8f),
    Wave(cycles = 3.1f, speed = 3, offset = 2.4f, scale = 0.45f, alpha = 0.2f, width = 1.2f),
)

/** Graphite to white and back: the app's own greys, as light moving over metal. */
private val Sheen = listOf(
    Color(0xFF18181B),
    Color(0xFF52525B),
    Color(0xFFD4D4D8),
    Color(0xFFFFFFFF),
    Color(0xFFA1A1AA),
    Color(0xFF27272A),
    Color(0xFF18181B),
)

private val Scrim = Color(0x47000000)
private val RING_WIDTH = 2.dp
private val PanelShape = RoundedCornerShape(30.dp)
private val InnerShape = RoundedCornerShape(28.dp)
private val ORB_SIZE = 64.dp
private val ORB_AREA = 104.dp
private val DISMISS_DRAG = 110.dp
private val HeadlineStyle = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
