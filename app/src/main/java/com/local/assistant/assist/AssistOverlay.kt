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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
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
import com.local.assistant.ui.chat.ToolPermissionRequests
import com.local.assistant.ui.chat.ThinkingIndicator
import com.local.assistant.ui.privacy.HiddenDetails
import com.local.assistant.ui.privacy.rememberShown
import com.local.assistant.ui.theme.AppColors
import com.local.assistant.ui.theme.GlassRoundButton
import com.local.assistant.ui.theme.liquidGlass
import com.local.assistant.ui.theme.GlassShapes
import com.local.assistant.ui.theme.Glass
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
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
    /** The system blurs what's behind: the panel can be frosted glass rather than near-solid. */
    blurred: Boolean,
    /** On the lock screen with talking there turned off: the panel only says how to turn it on. */
    lockedOut: Boolean,
    /** Goes up each time opening the app waited for an unlock that was cancelled. */
    openCancelled: Int,
    onClosed: () -> Unit,
    onOpenApp: (chatId: Long?) -> Unit,
    onOpenSettings: () -> Unit,
    /** A tool's permission screen is up (Health Connect's covers the panel), or has gone. */
    onAskingPermission: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()

    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val chips by viewModel.chips.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val streaming by viewModel.streamingText.collectAsStateWithLifecycle()
    val step by viewModel.streamingStep.collectAsStateWithLifecycle()
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
    // Straight into listening as the panel rises.
    LaunchedEffect(lockedOut) {
        if (!lockedOut && viewModel.claimAutoStart()) listen()
    }

    // Health Connect's screen covers the panel: it stays up meanwhile ([onAskingPermission]).
    ToolPermissionRequests(viewModel.permissionRequests, onAsking = onAskingPermission)

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
    var leaving by remember { mutableStateOf(false) }

    // The handle: pulled down it closes the panel; pulled up it stretches the panel towards the
    // top, and let go far enough it fills the screen and becomes the app, at this chat.
    val pull = remember { Animatable(0f) } // px: down is positive, up negative
    var areaHeight by remember { mutableIntStateOf(0) } // where the panel may be
    var naturalHeight by remember { mutableIntStateOf(0) } // the panel, unstretched
    val room = (areaHeight - naturalHeight).coerceAtLeast(0)
    val stretch = (-pull.value).coerceIn(0f, room.toFloat())
    val appliedStretch by rememberUpdatedState(stretch)
    val density = LocalDensity.current
    val openAt = with(density) { minOf(room * OPEN_FRACTION, OPEN_DRAG.toPx()) }
    val openFully: () -> Unit = {
        if (!leaving) {
            leaving = true
            scope.launch {
                pull.animateTo(-room.toFloat(), tween(durationMillis = 240, easing = FastOutSlowInEasing))
                onOpenApp(chatId)
            }
        }
    }
    // Opening the app waited for an unlock, which was cancelled: back to how it was.
    LaunchedEffect(openCancelled) {
        if (openCancelled == 0) return@LaunchedEffect
        leaving = false
        pull.animateTo(0f, spring())
    }
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
            lockedOut -> 0.15f
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
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 10.dp, end = 10.dp, top = 48.dp, bottom = 10.dp)
                .onSizeChanged { areaHeight = it.height },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { naturalHeight = it.height - appliedStretch.toInt() }
                    .graphicsLayer {
                        val progress = appear.value
                        // A spring past 1 lifts the panel a touch before it settles: the "pop".
                        translationY = (1f - progress) * (size.height + 64.dp.toPx()) + pull.value.coerceAtLeast(0f)
                        val scale = 0.92f + 0.08f * progress.coerceAtMost(1f)
                        scaleX = scale
                        scaleY = scale
                        // No fade: a translucent layer would clip the glow to the panel's edge.
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    // A tap on the panel stays on the panel, rather than reaching the scrim and closing it.
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                GlowPanel(energy, blurred) {
                    // The handle and the header: down to close, up to open the app.
                    val grab = Modifier.pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                when {
                                    pull.value > DISMISS_DRAG.toPx() -> dismiss()
                                    -pull.value >= openAt && room > 0 -> openFully()
                                    else -> scope.launch { pull.animateTo(0f, spring()) }
                                }
                            },
                            onDragCancel = { scope.launch { pull.animateTo(0f, spring()) } },
                            onVerticalDrag = { change, amount ->
                                change.consume()
                                if (!leaving) scope.launch { pull.snapTo((pull.value + amount).coerceAtLeast(-room.toFloat())) }
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
                                .background(if (stretch > 0f) AppColors.TextSecondary else AppColors.Border),
                        )
                        Header(
                            energy = energy,
                            muted = muted,
                            showMute = !lockedOut && !noModel,
                            onToggleMute = viewModel::toggleMute,
                            onOpenApp = openFully,
                        )
                        // The room the pull makes, with what letting go will do.
                        val stretchDp = with(density) { stretch.toDp() }
                        Box(Modifier.fillMaxWidth().height(stretchDp), contentAlignment = Alignment.Center) {
                            if (stretchDp > 40.dp) {
                                Text(
                                    when {
                                        leaving -> "Opening the app…"
                                        -pull.value >= openAt -> "Release to open the app"
                                        else -> "Pull up to open the app"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = AppColors.TextSecondary,
                                )
                            }
                        }
                    }

                    val hasTranscript = messages.isNotEmpty() || pending != null || streaming != null
                    when {
                        lockedOut -> LockedOut(onOpenSettings)
                        noModel -> NeedsApp("The assistant needs its model first. Open the app to download or import it.", onOpenApp = openFully)
                        failed != null && !hasTranscript -> NeedsApp("${failed.message} Open the app to try again.", onOpenApp = openFully)
                        hasTranscript -> Transcript(
                            messages = messages,
                            chips = chips,
                            pending = pending,
                            streaming = streaming,
                            step = step,
                            waiting = waiting,
                            modelLoading = engine !is LlmService.State.Ready,
                            viewModel = viewModel,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        listening -> Headline("Listening…", "Ask anything. It sends when you pause.")
                        input == AssistViewModel.Input.TEXT -> Headline("How can I help?", null)
                        else -> Headline("How can I help?", "Tap the mic and ask.")
                    }

                    if (!lockedOut && !noModel) {
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
}

private enum class OrbMode { LISTENING, BUSY, IDLE }

/**
 * The panel: frosted glass, lit from above, with a bright inner rim, and a band of colour that
 * runs around its edge (and, from Android 12, glows past it). Behind the controls, soft colour
 * drifts like light through the glass; it brightens while the assistant listens, thinks or speaks.
 */
@Composable
private fun GlowPanel(energy: State<Float>, blurred: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val transition = rememberInfiniteTransition(label = "glow")
    val angle = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 6000, easing = LinearEasing)),
        label = "angle",
    )
    Box {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = 0.2f + 0.6f * energy.value }
                    .blur(22.dp, BlurredEdgeTreatment.Unbounded)
                    .drawWithCache {
                        val outline = PanelShape.createOutline(size, layoutDirection, this)
                        val light = TurningSweep(Iris, size.center)
                        val stroke = Stroke(width = 8.dp.toPx())
                        onDrawBehind {
                            light.turn(angle.value)
                            drawOutline(outline, light.brush, style = stroke)
                        }
                    },
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(PanelShape)
                .background(if (blurred) Frosted else Solid)
                .drawWithCache {
                    val outline = PanelShape.createOutline(size, layoutDirection, this)
                    val inset = RING_WIDTH.toPx()
                    val inner = InnerShape.createOutline(Size(size.width - 2 * inset, size.height - 2 * inset), layoutDirection, this)
                    val light = TurningSweep(Iris, size.center)
                    // Half of the stroke falls outside the clip: twice the ring's width shows one.
                    val ring = Stroke(width = 2 * inset)
                    val rim = Stroke(width = 1.dp.toPx())
                    val rimLight = Brush.verticalGradient(listOf(Color.White, Color.White.copy(alpha = 0.2f)))
                    val sheen = Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.6f),
                        0.4f to Color.White.copy(alpha = 0f),
                        endY = minOf(size.height, SHEEN_DEPTH.toPx()),
                    )
                    onDrawBehind {
                        aurora(angle.value, energy.value)
                        drawRect(sheen)
                        translate(inset, inset) { drawOutline(inner, rimLight, style = rim) }
                        light.turn(angle.value)
                        drawOutline(outline, light.brush, style = ring, alpha = 0.45f + 0.55f * energy.value)
                    }
                }
                .padding(RING_WIDTH)
                .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)),
            content = content,
        )
    }
}

/**
 * Three soft pools of colour low in the panel, drifting slowly round, where the orb and the
 * voice are: the glass catching light. Stronger with [energy].
 */
private fun DrawScope.aurora(degrees: Float, energy: Float) {
    val turn = Math.toRadians(degrees.toDouble())
    val radius = size.width * 0.5f
    val strength = 0.1f + 0.22f * energy
    for (i in 0 until 3) {
        val phase = turn + i * 2.1
        val center = Offset(
            x = size.width * (0.2f + 0.3f * i) + (sin(phase) * size.width * 0.08).toFloat(),
            y = size.height - radius * 0.3f + (cos(phase) * radius * 0.12).toFloat(),
        )
        drawCircle(
            Brush.radialGradient(listOf(Iris[i * 2].copy(alpha = strength), Color.Transparent), center, radius),
            radius = radius,
            center = center,
        )
    }
}

/** A sweep of [colors] around [center] that turns without being rebuilt: one shader, its matrix moved. */
private class TurningSweep(colors: List<Color>, private val center: Offset) {
    private val shader = android.graphics.SweepGradient(center.x, center.y, colors.map { it.toArgb() }.toIntArray(), null)
    private val matrix = android.graphics.Matrix()
    val brush = ShaderBrush(shader)

    fun turn(degrees: Float) {
        matrix.setRotate(degrees, center.x, center.y)
        shader.setLocalMatrix(matrix)
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
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AssistantMark(energy)
        Text("Assistant", style = MaterialTheme.typography.titleMedium, color = AppColors.TextPrimary)
        Spacer(Modifier.weight(1f))
        if (showMute) {
            GlassRoundButton(
                if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                if (muted) "Read answers aloud" else "Don't read answers aloud",
                onToggleMute,
                size = HEADER_BUTTON,
                iconSize = 18.dp,
            )
        }
        GlassRoundButton(Icons.AutoMirrored.Outlined.OpenInNew, "Open in app", onOpenApp, size = HEADER_BUTTON, iconSize = 18.dp)
    }
}

/** The app's dot, alive: a dark glass bead that breathes, haloed while the assistant works. */
@Composable
private fun AssistantMark(energy: State<Float>) {
    val transition = rememberInfiniteTransition(label = "mark")
    val breath = transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    Canvas(Modifier.size(20.dp)) {
        val r = size.minDimension / 2
        drawCircle(AppColors.TextPrimary.copy(alpha = 0.08f + 0.14f * energy.value), radius = r * breath.value)
        drawCircle(Brush.radialGradient(listOf(Color(0xFF52525B), AppColors.TextPrimary), center = center - Offset(r * 0.15f, r * 0.2f), radius = r * 0.6f), radius = r * 0.5f)
        drawCircle(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.55f), Color.Transparent), startY = center.y - r * 0.5f, endY = center.y), radius = r * 0.5f)
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

/**
 * On the lock screen with talking there turned off. The same words are in the notification left
 * for later (LockScreenNotice), since this panel goes as soon as the phone is put down.
 */
@Composable
private fun LockedOut(onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)) {
        Text("Unlock to talk", style = HeadlineStyle, color = AppColors.TextPrimary)
        Text(
            "Talking to the assistant on the lock screen is off. You can turn it on in Settings \u2192 Calling the assistant; " +
                "a notification about it is waiting for you.",
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            LockScreenNotice.RISK,
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.Danger,
            modifier = Modifier.padding(top = 8.dp),
        )
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.padding(top = 12.dp)) { Text("Unlock and open Settings") }
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
    /** What the reply is waiting on: recall, a tool. */
    step: String?,
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
                ThinkingIndicator(label = if (modelLoading) "Loading model…" else step ?: "Thinking…")
            streaming != null -> {
                Answer(streaming, streaming = true)
                step?.let { ThinkingIndicator(label = it) }
            }
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

/**
 * What the user asked: their words, or their voice message, in the app's grey bubble. A voice
 * message written down has its words under it, in the same bubble.
 */
@Composable
private fun Question(text: String, voice: Boolean, durationMs: Long?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalAlignment = Alignment.End) {
        if (voice) {
            Column(
                Modifier
                    .widthIn(max = 280.dp)
                    .clip(GlassShapes.Mine)
                    .background(Glass.Mine)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        listOfNotNull("Voice message", durationMs?.let(::formatSeconds)).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
                if (text.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    com.local.assistant.ui.chat.TranscribedWords(text, color = Color.White)
                }
            }
        }
        if (text.isNotBlank() && !voice) {
            val shown = rememberShown(text)
            SelectionContainer {
                Text(
                    text = shown.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    modifier = Modifier
                        .padding(top = if (voice) 4.dp else 0.dp)
                        .widthIn(max = 280.dp)
                        .clip(GlassShapes.Mine)
                        .background(Glass.Mine)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            if (shown.hidden) HiddenDetails(Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun Answer(text: String, streaming: Boolean = false) {
    val shown = rememberShown(text, streaming)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        SelectionContainer {
            MarkdownText(shown.text)
        }
        if (shown.hidden && !streaming) HiddenDetails(Modifier.padding(top = 2.dp))
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
                brush = WaveInk,
                alpha = wave.alpha,
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
                        brush = Brush.sweepGradient(Iris, center),
                        alpha = (1f - t) * 0.5f,
                        radius = core + (reach - core) * t,
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
            }
            if (mode == OrbMode.BUSY) {
                rotate(spin.value) {
                    drawCircle(
                        brush = Brush.sweepGradient(listOf(Color.Transparent) + Iris.take(4), center),
                        radius = core + 5.dp.toPx(),
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
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
                // Dark glass: lit from above, a bright rim.
                .liquidGlass(CircleShape, Color.Transparent, sheen = 0.3f)
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
    GlassRoundButton(icon, label, onClick, size = 52.dp)
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
                .heightIn(min = 52.dp)
                .liquidGlass(RoundedCornerShape(26.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp),
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
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .then(
                    if (enabled) Modifier.background(Glass.Mine).liquidGlass(CircleShape, Color.Transparent, sheen = 0.22f)
                    else Modifier.liquidGlass(CircleShape),
                )
                .clickable(enabled = enabled) { if (busy) onStop() else submit() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (busy) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.Send,
                contentDescription = if (busy) "Stop" else "Send",
                tint = if (enabled) AppColors.OnAccent else AppColors.TextSecondary,
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

private val Iris = Glass.Iris

/** The voice's lines: the same colours, deepened to read on the white glass. */
private val WaveInk = Brush.horizontalGradient(listOf(Color(0xFF3B6BF0), Color(0xFF8B4FEF), Color(0xFFE5468F), Color(0xFF3B6BF0)))

/** Lighter than before: the blurred screen behind is part of the glass's look, not a shadow. */
private val Scrim = Color(0x2E000000)

/** The panel's glass: white over the blurred screen, bright at the top; near-solid where nothing is blurred. */
private val Frosted = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.74f), Color.White.copy(alpha = 0.6f)))
private val Solid = SolidColor(Color.White.copy(alpha = 0.97f))
private val SHEEN_DEPTH = 220.dp
private val HEADER_BUTTON = 38.dp
private val RING_WIDTH = 2.dp
private val PanelShape = RoundedCornerShape(30.dp)
private val InnerShape = RoundedCornerShape(28.dp)
private val ORB_SIZE = 64.dp
private val ORB_AREA = 104.dp
private val DISMISS_DRAG = 110.dp

/** Pulled up this far (or this share of the way to the top, if less), letting go opens the app. */
private val OPEN_DRAG = 220.dp
private const val OPEN_FRACTION = 0.4f
private val HeadlineStyle = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
