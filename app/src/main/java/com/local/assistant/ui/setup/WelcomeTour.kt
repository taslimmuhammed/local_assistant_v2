package com.local.assistant.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.ScreenshotMonitor
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Swipe
import androidx.compose.material.icons.outlined.ToggleOff
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WavingHand
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Mic as MicFilled
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.assistant.model.ModelCatalog
import com.local.assistant.ui.theme.AppColors
import com.local.assistant.ui.theme.Glass
import com.local.assistant.ui.theme.GlassMark
import com.local.assistant.ui.theme.GlassRoundButton
import com.local.assistant.ui.theme.GlassShapes
import com.local.assistant.ui.theme.fadingEdges
import com.local.assistant.ui.theme.glass
import com.local.assistant.ui.theme.liquidGlass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.PI
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * What the app can do, page by page, before anything is asked of a new user ([firstRun]); again
 * from Settings. Each page is a small moving picture of the thing at work, as the app shows it
 * (a message, the chip under the answer, the map), then a few lines on it and where to find it.
 * Skip leaves at any point; the last page leads on to the profile and the model download.
 */
@Composable
fun WelcomeTour(firstRun: Boolean, onDone: () -> Unit) {
    val pages = TourPages
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val page = pager.currentPage
    val last = page == pages.lastIndex
    fun goTo(target: Int) = scope.launch { pager.animateScrollToPage(target) }

    // Back turns the page back; on the first page it leaves a replay, and the app on a first run.
    BackHandler(enabled = page > 0 || !firstRun) { if (page > 0) goTo(page - 1) else onDone() }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${page + 1} of ${pages.size}",
                style = MaterialTheme.typography.labelLarge,
                color = AppColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            AnimatedVisibility(!last, enter = fadeIn(), exit = fadeOut()) {
                Text(
                    if (firstRun) "Skip" else "Close",
                    style = MaterialTheme.typography.labelLarge,
                    color = AppColors.TextPrimary,
                    modifier = Modifier
                        .glass(GlassShapes.Pill, Glass.Raised)
                        .clickable(role = Role.Button, onClick = onDone)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        HorizontalPager(pager, Modifier.weight(1f), beyondViewportPageCount = 1) { index ->
            // How far this page is from the middle of the screen, -1..1 while it slides.
            val away = (page - index) + pager.currentPageOffsetFraction
            TourPageContent(pages[index], playing = page == index, away = away, firstRun = firstRun)
        }

        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Back is there from the second page: it grows in, and is gone (not just clear) on the first.
            val back by animateFloatAsState(if (page > 0) 1f else 0f, spring(dampingRatio = 0.7f), label = "back")
            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                if (back > 0.01f) {
                    GlassRoundButton(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        "Back",
                        { goTo(page - 1) },
                        enabled = page > 0,
                        size = 52.dp,
                        modifier = Modifier.graphicsLayer {
                            alpha = back.coerceIn(0f, 1f)
                            scaleX = 0.6f + 0.4f * back
                            scaleY = 0.6f + 0.4f * back
                        },
                    )
                }
            }
            PageDots(pages.size, page, onPick = { goTo(it) }, modifier = Modifier.weight(1f))
            NextButton(
                text = when {
                    !last -> "Next"
                    firstRun -> "Get started"
                    else -> "Done"
                },
                onClick = { if (last) onDone() else goTo(page + 1) },
            )
        }
    }
}

/**
 * One page: the picture on its pane of glass, then what it is, in a few lines. The words come
 * first in size: the picture takes the height they leave (seen on a 363 × 792 dp phone: at a fixed
 * height, half of each page's points were under the fold), and its contents shrink to fit.
 * Only where even the smallest picture leaves too little room does the page scroll.
 */
@Composable
private fun TourPageContent(tour: TourPage, playing: Boolean, away: Float, firstRun: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val room = maxHeight - PAGE_PADDING * 2
        Column(
            Modifier
                .fillMaxSize()
                .fadingEdges(top = PAGE_PADDING, bottom = PAGE_PADDING)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = PAGE_PADDING),
        ) {
            PictureOverWords(
                room,
                picture = {
                    // The picture trails the swipe a little and dims as it goes, so the pages feel layered.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = away * size.width * 0.18f
                                val gone = away.absoluteValue.coerceIn(0f, 1f)
                                alpha = 1f - gone * 0.6f
                                scaleX = 1f - gone * 0.06f
                                scaleY = 1f - gone * 0.06f
                            }
                            .liquidGlass(SceneShape, Glass.Pane, sheen = 0.4f)
                            .background(Brush.radialGradient(listOf(tour.accent.copy(alpha = 0.10f), Color.Transparent)))
                            .clearAndSetSemantics { contentDescription = tour.sceneDescription }
                            .padding(SCENE_PADDING),
                    ) {
                        Box(Modifier.fillMaxSize().scaledToFit(SCENE_HEIGHT - SCENE_PADDING * 2)) { Scene(tour.scene, playing) }
                    }
                },
                words = { TourWords(tour, playing, firstRun) },
            )
        }
    }
}

/** The label, the title, a few lines, the points and where to find it. */
@Composable
private fun TourWords(tour: TourPage, playing: Boolean, firstRun: Boolean) {
    Column {
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Bead(tour.icon, tour.accent, size = 28.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                tour.label.uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold),
                color = tour.accent,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(tour.title, style = TitleStyle, color = AppColors.TextPrimary, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(6.dp))
        Text(tour.body, style = BodyStyle, color = AppColors.TextSecondary)

        Spacer(Modifier.height(10.dp))
        tour.points.forEachIndexed { index, point ->
            Rise(playing, beat = 3 + index) {
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp).glass(CircleShape, Glass.Raised), contentAlignment = Alignment.Center) {
                        Icon(point.icon, contentDescription = null, tint = AppColors.TextPrimary, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(point.text, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextPrimary)
                }
            }
        }

        val where = if (firstRun && tour.scene == SceneKind.PRIVACY) FIRST_RUN_NEXT else tour.where
        if (where != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                where,
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
                modifier = Modifier
                    .glass(RoundedCornerShape(14.dp), Glass.Raised)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

/**
 * [picture] over [words], the picture as tall as [room] less the words, between [MIN_SCENE] and
 * [SCENE_HEIGHT]. Measured here rather than by weight, since the page scrolls when even that
 * isn't enough, and a scrolling column has no height to share out.
 */
@Composable
private fun PictureOverWords(room: Dp, picture: @Composable () -> Unit, words: @Composable () -> Unit) {
    Layout(contents = listOf(picture, words)) { (pictureParts, wordParts), constraints ->
        val width = constraints.maxWidth
        val placedWords = wordParts.single().measure(Constraints(maxWidth = width))
        val pictureHeight = (room.roundToPx() - placedWords.height).coerceIn(MIN_SCENE.roundToPx(), SCENE_HEIGHT.roundToPx())
        val placedPicture = pictureParts.single().measure(Constraints.fixed(width, pictureHeight))
        layout(width, pictureHeight + placedWords.height) {
            placedPicture.place(0, 0)
            placedWords.place(0, pictureHeight)
        }
    }
}

/**
 * Lays the content out at [design] height (and as wide as fits at that scale) and draws it scaled
 * down to the height it is given: a shorter screen sees the same picture, smaller, not a cut one.
 */
private fun Modifier.scaledToFit(design: Dp): Modifier = layout { measurable, constraints ->
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val scale = minOf(1f, height / design.toPx())
    val placeable = measurable.measure(Constraints.fixed((width / scale).roundToInt(), (height / scale).roundToInt()))
    layout(width, height) {
        placeable.placeWithLayer(0, 0) {
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(0f, 0f)
        }
    }
}

/** Where the dots are, which one is this page: a longer dark bar. Each dot goes to its page. */
@Composable
private fun PageDots(count: Int, current: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { index ->
            val width by animateDpAsState(if (index == current) 22.dp else 7.dp, spring(dampingRatio = 0.7f), label = "dot")
            Box(
                Modifier
                    .clickable(role = Role.Tab, onClickLabel = "Page ${index + 1}") { onPick(index) }
                    .padding(horizontal = 3.dp, vertical = 10.dp)
                    .width(width)
                    .height(7.dp)
                    .clip(CircleShape)
                    .background(if (index == current) AppColors.TextPrimary else AppColors.TextPrimary.copy(alpha = 0.18f)),
            )
        }
    }
}

/** The way on: a dark pill, the app's main button, that swells under the finger. */
@Composable
private fun NextButton(text: String, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "press")
    Row(
        Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .height(52.dp)
            .widthIn(min = 112.dp)
            .clip(GlassShapes.Pill)
            .background(Glass.Mine)
            .border(1.dp, Color.White.copy(alpha = 0.18f), GlassShapes.Pill)
            .clickable(interactionSource = press, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        AnimatedContent(text, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "next") { words ->
            Text(words, style = MaterialTheme.typography.titleSmall, color = Color.White)
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

/** Fades [content] in and lifts it into place [beat] beats after its page comes up. */
@Composable
private fun Rise(playing: Boolean, beat: Int, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(playing) {
        if (!playing || progress.value > 0f) return@LaunchedEffect
        delay(beat * BEAT_MS)
        progress.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow))
    }
    Box(
        modifier.graphicsLayer {
            alpha = progress.value.coerceIn(0f, 1f)
            translationY = (1f - progress.value) * 18.dp.toPx()
        },
        content = content,
    )
}

/** An icon on a tinted disc, the starter tiles' bead. */
@Composable
private fun Bead(icon: ImageVector, accent: Color, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(size * 0.56f))
    }
}

// The pictures. Each is built from the pieces the app itself shows, a little smaller.

@Composable
private fun Scene(kind: SceneKind, playing: Boolean) = when (kind) {
    SceneKind.WELCOME -> WelcomeScene(playing)
    SceneKind.TALK -> TalkScene(playing)
    SceneKind.MEMORY -> MemoryScene(playing)
    SceneKind.PLAN -> PlanScene(playing)
    SceneKind.PHONE -> PhoneScene(playing)
    SceneKind.PHOTOS -> PhotosScene(playing)
    SceneKind.LOOP -> LoopScene(playing)
    SceneKind.PRIVACY -> PrivacyScene(playing)
}

/** The mark, with rings of light going out from it, and a few things to ask floating round it. */
@Composable
private fun WelcomeScene(playing: Boolean) {
    val ripple by rememberInfiniteTransition(label = "ripple").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 3200, easing = LinearEasing)),
        label = "ripple",
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            repeat(3) { ring ->
                val t = (ripple + ring / 3f) % 1f
                drawCircle(
                    Color.White.copy(alpha = (1f - t) * 0.9f),
                    radius = 56.dp.toPx() + t * 70.dp.toPx(),
                    style = Stroke(1.5.dp.toPx()),
                )
            }
        }
        GlassMark(size = 104.dp)
        Floating(playing, beat = 2, Modifier.align(Alignment.TopStart).offset(x = 4.dp, y = 6.dp)) { SceneChip("“Hey Luna”") }
        Floating(playing, beat = 4, Modifier.align(Alignment.CenterEnd).offset(y = (-52).dp)) { SceneChip("Remind me at 6") }
        Floating(playing, beat = 6, Modifier.align(Alignment.BottomStart).offset(x = 10.dp, y = (-8).dp)) { SceneChip("Find my beach photos") }
        Floating(playing, beat = 8, Modifier.align(Alignment.BottomEnd).offset(y = (-44).dp)) { SceneChip("What's 18% of 2,450?") }
    }
}

/** A suggestion that rises into place, then bobs gently, as if on water. */
@Composable
private fun Floating(playing: Boolean, beat: Int, modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    val bob by rememberInfiniteTransition(label = "bob").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 2600 + beat * 150, easing = LinearEasing)),
        label = "bob",
    )
    Rise(playing, beat, modifier.graphicsLayer { translationY = sin(bob * 2 * PI.toFloat()) * 3.dp.toPx() }, content)
}

/** The power-button panel at the foot of the screen: a voice message going in, the answer coming out. */
@Composable
private fun TalkScene(playing: Boolean) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Rise(playing, 1) { SceneChip("Hold power", Icons.Outlined.PowerSettingsNew) }
            Rise(playing, 2) { SceneChip("“Hey Luna”", Icons.Outlined.RecordVoiceOver) }
        }
        Spacer(Modifier.weight(1f))
        Rise(playing, 3) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(PanelShape)
                    .background(Color.White.copy(alpha = 0.72f))
                    .border(1.5.dp, Brush.sweepGradient(Glass.Iris), PanelShape)
                    .padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(AppColors.TextPrimary))
                    Spacer(Modifier.width(8.dp))
                    Text("Assistant", style = MaterialTheme.typography.labelLarge, color = AppColors.TextPrimary)
                }
                Spacer(Modifier.height(8.dp))
                Rise(playing, 5, Modifier.align(Alignment.End)) {
                    Row(
                        Modifier.clip(GlassShapes.Pill).background(Glass.Mine).padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Voice message · 0:03", style = SceneSmall, color = Color.White)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Rise(playing, 8) { Text("You're seeing Dr. Rao at 4 pm today.", style = SceneText, color = AppColors.TextPrimary) }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VoiceWave(Modifier.weight(1f).height(26.dp))
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.size(34.dp).clip(CircleShape).background(Glass.Mine), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.MicFilled, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
                    }
                }
            }
        }
    }
}

/** The overlay's wave: a line of the assistant's light, rolling as it speaks. */
@Composable
private fun VoiceWave(modifier: Modifier) {
    val phase by rememberInfiniteTransition(label = "wave").animateFloat(
        initialValue = 0f,
        targetValue = 2 * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(durationMillis = 1600, easing = LinearEasing)),
        label = "phase",
    )
    Canvas(modifier) {
        val mid = size.height / 2
        val path = Path()
        val steps = 48
        for (step in 0..steps) {
            val x = size.width * step / steps
            val fade = sin(PI.toFloat() * step / steps)
            val y = mid + sin(step / steps.toFloat() * 4 * PI.toFloat() + phase) * mid * 0.8f * fade
            if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Brush.horizontalGradient(Glass.Iris), style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** Told once, kept: a fact saved, then asked for again later. */
@Composable
private fun MemoryScene(playing: Boolean) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Rise(playing, 1, Modifier.align(Alignment.End)) { Mine("My dentist is Dr. Rao") }
        Rise(playing, 3) { Chip("Saved · Dentist: Dr. Rao", "Undo", Icons.Outlined.Psychology, Glass.Ink[2]) }
        Spacer(Modifier.weight(1f))
        Rise(playing, 6, Modifier.align(Alignment.End)) { Mine("Who's my dentist again?") }
        Rise(playing, 8) { Text("Dr. Rao. You told me last week.", style = SceneText, color = AppColors.TextPrimary) }
    }
}

/** A reminder for a place: the words, the chip and the circle on the map where it rings. */
@Composable
private fun PlanScene(playing: Boolean) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Rise(playing, 1, Modifier.align(Alignment.End)) { Mine("Buy milk when I'm near DMart") }
        Rise(playing, 3) { Chip("Reminder · Buy milk · near DMart", "Undo", Icons.Outlined.NotificationsActive, Glass.Ink[3]) }
        Rise(playing, 5, Modifier.weight(1f)) { MiniMap(Modifier.fillMaxSize()) }
    }
}

/** A map drawn plainly: two roads, the shop's pin and the circle round it, breathing. */
@Composable
private fun MiniMap(modifier: Modifier) {
    val breathe by rememberInfiniteTransition(label = "fence").animateFloat(
        initialValue = 0.92f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    Canvas(modifier.clip(RoundedCornerShape(16.dp)).background(MapLand)) {
        val w = size.width
        val h = size.height
        val side = Path().apply {
            moveTo(0f, h * 0.78f)
            cubicTo(w * 0.3f, h * 0.62f, w * 0.55f, h * 0.95f, w, h * 0.7f)
        }
        drawPath(side, Color.White, style = Stroke(7.dp.toPx(), cap = StrokeCap.Round))
        val main = Path().apply {
            moveTo(w * 0.18f, 0f)
            cubicTo(w * 0.32f, h * 0.4f, w * 0.62f, h * 0.35f, w * 0.78f, h)
        }
        drawPath(main, MapRoad, style = Stroke(9.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(MapWater, radius = h * 0.22f, center = Offset(w * 0.88f, h * 0.18f))
        val pin = Offset(w * 0.5f, h * 0.48f)
        val fence = h * 0.34f * breathe
        drawCircle(Glass.Ink[0].copy(alpha = 0.14f), radius = fence, center = pin)
        drawCircle(Glass.Ink[0], radius = fence, center = pin, style = Stroke(2.dp.toPx()))
        drawCircle(PinRed, radius = 8.dp.toPx(), center = pin)
        drawCircle(Color.White, radius = 3.dp.toPx(), center = pin)
    }
}

/** Two things in one message, each done and shown as its chip, then a sum. */
@Composable
private fun PhoneScene(playing: Boolean) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Rise(playing, 1, Modifier.align(Alignment.End)) { Mine("Call Mom, then turn on the flashlight") }
        Rise(playing, 3) { Chip("Dialer ready · Mom", "Call", Icons.Outlined.Call, Glass.Ink[4]) }
        Rise(playing, 4) { Chip("Flashlight on", "Undo", Icons.Outlined.FlashlightOn, Glass.Ink[3]) }
        Spacer(Modifier.weight(1f))
        Rise(playing, 6, Modifier.align(Alignment.End)) { Mine("What's 18% of 2,450?") }
        Rise(playing, 8) { Text("That's 441.", style = SceneText, color = AppColors.TextPrimary) }
    }
}

/** A search in words, the photos coming up one by one (a video among them), and the people found. */
@Composable
private fun PhotosScene(playing: Boolean) {
    Column(Modifier.fillMaxSize()) {
        Rise(playing, 1) {
            Row(
                Modifier.fillMaxWidth().glass(GlassShapes.Pill, Glass.Raised).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = AppColors.TextSecondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("beach in Goa, last December", style = SceneText, color = AppColors.TextPrimary)
            }
        }
        Spacer(Modifier.height(10.dp))
        PhotoTones.chunked(3).forEachIndexed { row, tones ->
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tones.forEachIndexed { column, tone ->
                    val index = row * 3 + column
                    Rise(playing, 3 + index, Modifier.weight(1f).aspectRatio(1.5f)) {
                        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(Brush.verticalGradient(tone)))
                        if (index == 4) {
                            Row(
                                Modifier.align(Alignment.BottomStart).padding(5.dp).clip(GlassShapes.Pill)
                                    .background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 5.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp))
                                Text("0:12", style = SceneTiny, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Rise(playing, 10) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Overlapping, as faces in a group are.
                Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                    People.forEach { (initial, tone) ->
                        Box(
                            Modifier.size(28.dp).clip(CircleShape)
                                .background(Brush.linearGradient(tone)).border(2.dp, Color.White, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Text(initial, style = SceneSmall.copy(fontWeight = FontWeight.SemiBold), color = Color.White) }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text("People, named from your contacts", style = SceneSmall, color = AppColors.TextSecondary)
            }
        }
    }
}

/** The card that waits on coming back to the phone, and today's steps going round their ring. */
@Composable
private fun LoopScene(playing: Boolean) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Rise(playing, 1) {
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), Glass.Raised).padding(12.dp)) {
                Text("While you were away", style = SceneText.copy(fontWeight = FontWeight.SemiBold), color = AppColors.TextPrimary)
                Spacer(Modifier.height(8.dp))
                AwayRow(Icons.Outlined.Call, Glass.Ink[2], "Mom", "Called twice", action = null)
                Spacer(Modifier.height(6.dp))
                AwayRow(Icons.AutoMirrored.Outlined.Chat, Glass.Ink[0], "Anjali", "Are we still on for 7?", action = "Reply")
            }
        }
        Rise(playing, 4) {
            Row(
                Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), Glass.Raised).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepRing(playing, fraction = 0.78f, Modifier.size(52.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("4,680 steps today", style = SceneText.copy(fontWeight = FontWeight.SemiBold), color = AppColors.TextPrimary)
                    Text("1,320 to go for your goal", style = SceneSmall, color = AppColors.TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun AwayRow(icon: ImageVector, accent: Color, who: String, what: String, action: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Bead(icon, accent, size = 26.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(who, style = SceneSmall.copy(fontWeight = FontWeight.SemiBold), color = AppColors.TextPrimary)
            Text(what, style = SceneSmall, color = AppColors.TextSecondary)
        }
        if (action != null) {
            Text(
                action,
                style = SceneSmall.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                modifier = Modifier.clip(GlassShapes.Pill).background(Glass.Mine).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/** A ring that fills to [fraction] once its page is up. */
@Composable
private fun StepRing(playing: Boolean, fraction: Float, modifier: Modifier) {
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(playing) {
        if (playing && sweep.value == 0f) {
            delay(6 * BEAT_MS)
            sweep.animateTo(fraction, tween(durationMillis = 1100, easing = FastOutSlowInEasing))
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val width = 5.dp.toPx()
            drawArc(AppColors.TextPrimary.copy(alpha = 0.1f), 0f, 360f, useCenter = false, style = Stroke(width))
            drawArc(Glass.Ink[4], -90f, 360f * sweep.value, useCenter = false, style = Stroke(width, cap = StrokeCap.Round))
        }
        Icon(Icons.Outlined.FavoriteBorder, contentDescription = null, tint = Glass.Ink[4], modifier = Modifier.size(18.dp))
    }
}

/** A lens with a lock, a card number kept hidden, and what the app doesn't have. */
@Composable
private fun PrivacyScene(playing: Boolean) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Rise(playing, 1) {
            Box(
                Modifier.size(84.dp).liquidGlass(CircleShape, Glass.Pane, sheen = 0.7f).padding(10.dp)
                    .liquidGlass(CircleShape, Color.White.copy(alpha = 0.9f), sheen = 0.8f),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = AppColors.TextPrimary, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Rise(playing, 3) {
            Row(
                Modifier.glass(GlassShapes.Pill, Glass.Raised).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.VisibilityOff, contentDescription = null, tint = AppColors.TextSecondary, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(8.dp))
                Text("Card  •••• •••• •••• ••••", style = SceneText, color = AppColors.TextPrimary)
                Spacer(Modifier.width(10.dp))
                Icon(Icons.Outlined.Fingerprint, contentDescription = null, tint = Glass.Ink[0], modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        // Whole chips to the next line on a narrow screen, never a word of one.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("No account", "No ads", "No tracking").forEachIndexed { index, words ->
                Rise(playing, 5 + index) { SceneChip(words, Icons.Filled.Check) }
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

/** The user's own words: the dark bubble. */
@Composable
private fun Mine(text: String) {
    Text(
        text,
        style = SceneText,
        color = Color.White,
        modifier = Modifier
            .widthIn(max = 250.dp)
            .clip(GlassShapes.Mine)
            .background(Glass.Mine)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** What was done, as the chip under an answer shows it, with its one action. */
@Composable
private fun Chip(text: String, action: String, icon: ImageVector, accent: Color) {
    Row(
        Modifier.glass(RoundedCornerShape(14.dp), Glass.Raised).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = SceneSmall, color = AppColors.TextPrimary)
        Spacer(Modifier.width(10.dp))
        Text(action, style = SceneSmall.copy(fontWeight = FontWeight.SemiBold), color = AppColors.TextPrimary)
    }
}

/** A small glass pill of words, with an icon before them if there is one. */
@Composable
private fun SceneChip(text: String, icon: ImageVector? = null) {
    Row(
        Modifier.glass(GlassShapes.Pill, Glass.Raised).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = AppColors.TextPrimary, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, style = SceneSmall, color = AppColors.TextPrimary, maxLines = 1, softWrap = false)
    }
}

private enum class SceneKind { WELCOME, TALK, MEMORY, PLAN, PHONE, PHOTOS, LOOP, PRIVACY }

private data class TourPoint(val icon: ImageVector, val text: String)

private class TourPage(
    val scene: SceneKind,
    val label: String,
    val icon: ImageVector,
    val accent: Color,
    val title: String,
    val body: String,
    val points: List<TourPoint>,
    /** Where it lives or is turned on, when that isn't the chat itself. */
    val where: String?,
    /** For screen readers, which hear the picture as one line. */
    val sceneDescription: String,
)

/**
 * Everything the app does, in eight pages, in the order someone would meet it: talking to it, what
 * it keeps, what it does, their photos, their day, and last how it keeps all that to the phone.
 */
private val TourPages = listOf(
    TourPage(
        SceneKind.WELCOME, "Welcome", Icons.Outlined.WavingHand, Glass.Ink[0],
        "Meet your private assistant",
        "Local Assistant runs Google's Gemma 4 right on this phone. It talks, listens, remembers you and gets things done, " +
            "and nothing you say leaves the phone for it to think.",
        listOf(
            TourPoint(Icons.Outlined.PhoneAndroid, "Runs on the phone, even with no signal"),
            TourPoint(Icons.Outlined.Block, "No account, no ads, no tracking"),
            TourPoint(Icons.Outlined.Swipe, "Swipe to see what it can do"),
        ),
        where = null,
        sceneDescription = "The app's mark, with things you can ask it around it.",
    ),
    TourPage(
        SceneKind.TALK, "Talk to it", Icons.Outlined.Mic, Glass.Ink[1],
        "Just ask, from any app",
        "Hold the power button or say “Hey Luna”. It listens, sends when you pause, and answers out loud in a voice you pick.",
        listOf(
            TourPoint(Icons.Outlined.PowerSettingsNew, "Hold the power button over any app"),
            TourPoint(Icons.Outlined.RecordVoiceOver, "Say “Hey Luna”, hands-free"),
            TourPoint(Icons.Outlined.ScreenshotMonitor, "Ask about the screen: “summarise this”"),
            TourPoint(Icons.Outlined.Image, "Send photos and voice messages in chat"),
        ),
        where = "Set it up in Settings → Calling the assistant",
        sceneDescription = "The assistant panel over the home screen, answering a voice message.",
    ),
    TourPage(
        SceneKind.MEMORY, "Memory", Icons.Outlined.Psychology, Glass.Ink[2],
        "It remembers you",
        "Mention something once and it keeps it in mind. Ask it to keep a photo of a bill or a card, and it finds it again.",
        listOf(
            TourPoint(Icons.Outlined.Person, "Facts you mention: “my dentist is Dr. Rao”"),
            TourPoint(Icons.Outlined.Description, "Images you ask it to keep: bills, cards, tickets"),
            TourPoint(Icons.Outlined.History, "Earlier chats, found by meaning"),
            TourPoint(Icons.Outlined.Edit, "See, change or delete any of it"),
        ),
        where = "About you, in the menu",
        sceneDescription = "Saying who your dentist is, and later asking it back.",
    ),
    TourPage(
        SceneKind.PLAN, "Reminders and lists", Icons.Outlined.NotificationsActive, Glass.Ink[3],
        "Right time, right place",
        "Set reminders, events, alarms and timers by saying so. Tie one to a place and it rings when you get there.",
        listOf(
            TourPoint(Icons.Outlined.Schedule, "“Remind me every Monday at 9”"),
            TourPoint(Icons.Outlined.Place, "“Buy milk when I'm near DMart”"),
            TourPoint(Icons.Outlined.Alarm, "Alarms and timers, in your Clock app"),
            TourPoint(Icons.Outlined.Checklist, "Lists: “add bread to my list”"),
        ),
        where = "Reminders and Notes, in the menu",
        sceneDescription = "A reminder to buy milk near a shop, with the circle on the map where it rings.",
    ),
    TourPage(
        SceneKind.PHONE, "Gets things done", Icons.Outlined.Bolt, Glass.Ink[4],
        "Runs your phone for you",
        "Calls, messages, apps and settings, several in one go. Anything for another person waits for your tap to send.",
        listOf(
            TourPoint(Icons.Outlined.Call, "Calls, SMS, WhatsApp and email"),
            TourPoint(Icons.Outlined.FlashlightOn, "Flashlight, silent, volume, Do Not Disturb"),
            TourPoint(Icons.Outlined.Apps, "Opens apps and takes screenshots"),
            TourPoint(Icons.Outlined.Calculate, "Sums and conversions: “5 miles in km”"),
        ),
        where = "Web search too, with a free key from Settings",
        sceneDescription = "One message that calls Mom and turns on the flashlight, then a sum.",
    ),
    TourPage(
        SceneKind.PHOTOS, "Your photos", Icons.Outlined.PhotoLibrary, Glass.Ink[0],
        "Find any photo by asking",
        "Search by what's in a photo, who's in it, and where and when it was taken. A video opens at the part that matched.",
        listOf(
            TourPoint(Icons.Outlined.Search, "“Beach photos from Goa last December”"),
            TourPoint(Icons.Outlined.Face, "People, named from your contacts' photos"),
            TourPoint(Icons.Outlined.Description, "Ask what a photo says: a ticket, a bill"),
            TourPoint(Icons.Outlined.VisibilityOff, "ID cards and documents stay hidden until you unlock"),
        ),
        where = "Turn it on in Photos, in the menu",
        sceneDescription = "A search for beach photos, the photos found, and the people in them.",
    ),
    TourPage(
        SceneKind.LOOP, "Your day", Icons.Outlined.WbSunny, Glass.Ink[1],
        "Keeps you in the loop",
        "Ask how you slept or how far you walked. Back at your phone, a card shows who called and who's waiting on a reply.",
        listOf(
            TourPoint(Icons.Outlined.FavoriteBorder, "Steps, sleep, heart rate and workouts"),
            TourPoint(Icons.Outlined.NotificationsNone, "Missed calls and messages, summed up"),
            TourPoint(Icons.AutoMirrored.Outlined.Reply, "Replies ready, sent once you check them"),
            TourPoint(Icons.Outlined.ToggleOff, "Both stay off until you turn them on"),
        ),
        where = "Settings → Health data (Health Connect), and While you were away",
        sceneDescription = "A card of missed calls and messages, and today's steps.",
    ),
    TourPage(
        SceneKind.PRIVACY, "Private", Icons.Outlined.Lock, Glass.Ink[2],
        "Private by design",
        "The model runs on this phone, so your chats, memories, photos and voice stay on it.",
        listOf(
            TourPoint(Icons.Outlined.Fingerprint, "Card and ID numbers hidden until your fingerprint, face or PIN"),
            TourPoint(Icons.Outlined.CloudOff, "The internet only for downloads, web search and finding places"),
            TourPoint(Icons.Outlined.Block, "No account, no ads, no tracking"),
        ),
        where = null,
        sceneDescription = "A lock, and a card number kept hidden.",
    ),
)

/** On a first run the last page says what comes next, so the wait for the download isn't a surprise. */
private val FIRST_RUN_NEXT =
    "Next: a few optional details about you, then the model download " +
        "(%.1f GB, best on Wi-Fi).".format(Locale.US, ModelCatalog.SIZE_BYTES / 1e9)

private val PhotoTones = listOf(
    listOf(Color(0xFF8EC5FC), Color(0xFFF6D5A8)),
    listOf(Color(0xFFFFB37A), Color(0xFFFF6F91)),
    listOf(Color(0xFF7FD3F7), Color(0xFF2F80ED)),
    listOf(Color(0xFFB8E994), Color(0xFF3AA27A)),
    listOf(Color(0xFF2C3E70), Color(0xFFE08E6D)),
    listOf(Color(0xFFFFE29F), Color(0xFF62C6D9)),
)

private val People = listOf(
    "A" to listOf(Color(0xFFA66CFF), Color(0xFF5B8CFF)),
    "R" to listOf(Color(0xFFFF6FB5), Color(0xFFFFA36B)),
    "M" to listOf(Color(0xFF4FD1C5), Color(0xFF3B6BF0)),
)

private val MapLand = Color(0xFFF1EEE6)
private val MapRoad = Color(0xFFFFD98E)
private val MapWater = Color(0xFFBFDDF5)
private val PinRed = Color(0xFFE5484D)

private const val BEAT_MS = 110L
/** The picture's height where there is room; it is drawn for this, and scaled down from it. */
private val SCENE_HEIGHT = 272.dp

/** The least the picture shrinks to before the page scrolls instead. */
private val MIN_SCENE = 188.dp
private val SCENE_PADDING = 16.dp
private val PAGE_PADDING = 12.dp
private val SceneShape = RoundedCornerShape(28.dp)
private val PanelShape = RoundedCornerShape(22.dp)
private val TitleStyle = TextStyle(fontSize = 26.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
private val BodyStyle = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)
private val SceneText = TextStyle(fontSize = 14.sp, lineHeight = 19.sp)
private val SceneSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
private val SceneTiny = TextStyle(fontSize = 10.sp, lineHeight = 12.sp)
