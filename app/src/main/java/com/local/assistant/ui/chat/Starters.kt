package com.local.assistant.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.WavingHand
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.assistant.ui.theme.AppColors
import com.local.assistant.ui.theme.Glass
import com.local.assistant.ui.theme.GlassRoundButton
import com.local.assistant.ui.theme.fadingEdges
import com.local.assistant.ui.theme.liquidGlass
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random

/** A first message to tap on a blank chat: one of the things the assistant can do. */
data class Starter(val text: String, val kind: Kind, val needs: Needs = Needs.NOTHING) {
    enum class Kind { GREETING, REMINDER, TIMER, AGENDA, MEMORY, PHOTOS, WEB, MATHS, PHONE, WRITING, IDEAS, FOOD, LANGUAGE, TRAVEL }

    /** What must be set up first: a suggestion the assistant can't do yet isn't offered. */
    enum class Needs { NOTHING, WEB, PHOTOS }
}

object Starters {

    /**
     * Each one runs as it reads, so a tap shows off a real ability. Nothing that calls or messages
     * someone, or changes a setting that's hard to undo: a tap shouldn't do anything the user
     * would regret. A reminder or timer comes with its Undo chip.
     */
    val ALL = listOf(
        Starter("Remind me to drink water in an hour", Starter.Kind.REMINDER),
        Starter("Remind me to call mom tomorrow at 7 pm", Starter.Kind.REMINDER),
        Starter("Set a 10 minute timer for my tea", Starter.Kind.TIMER),
        Starter("What's on my schedule this week?", Starter.Kind.AGENDA),
        Starter("What do you know about me?", Starter.Kind.MEMORY),
        Starter("What did we talk about yesterday?", Starter.Kind.MEMORY),
        Starter("Show me my sunset photos", Starter.Kind.PHOTOS, Starter.Needs.PHOTOS),
        Starter("Show me my photos from last month", Starter.Kind.PHOTOS, Starter.Needs.PHOTOS),
        Starter("Find my driving licence", Starter.Kind.PHOTOS, Starter.Needs.PHOTOS),
        Starter("What's the latest tech news?", Starter.Kind.WEB, Starter.Needs.WEB),
        Starter("What's the weather like today?", Starter.Kind.WEB, Starter.Needs.WEB),
        Starter("Who won the last cricket match?", Starter.Kind.WEB, Starter.Needs.WEB),
        Starter("What's an 18% tip on ₹2,450?", Starter.Kind.MATHS),
        Starter("Split ₹3,600 between 7 friends", Starter.Kind.MATHS),
        Starter("Turn on the flashlight", Starter.Kind.PHONE),
        Starter("Help me write a leave email to my manager", Starter.Kind.WRITING),
        Starter("Write a birthday wish for my best friend", Starter.Kind.WRITING),
        Starter("Explain black holes like I'm ten", Starter.Kind.IDEAS),
        Starter("Give me three ideas for a weekend project", Starter.Kind.IDEAS),
        Starter("A quick 15-minute dinner recipe", Starter.Kind.FOOD),
        Starter("How do I say “where is the station?” in Hindi?", Starter.Kind.LANGUAGE),
        Starter("Plan a 3-day trip to Munnar", Starter.Kind.TRAVEL),
    )

    /** A hello for the time of day: the first suggestion, always. */
    fun greeting(hour: Int): Starter = Starter(
        when (hour) {
            in 5..11 -> "Hey, good morning!"
            in 12..16 -> "Good afternoon! How's your day going?"
            in 17..21 -> "Good evening!"
            else -> "Hey, still up?"
        },
        Starter.Kind.GREETING,
    )

    /** The greeting and [count] others at random, no two of a kind, only ones that are set up. */
    fun pick(random: Random, hour: Int, web: Boolean, photos: Boolean, count: Int = 3): List<Starter> {
        val possible = ALL.filter {
            when (it.needs) {
                Starter.Needs.NOTHING -> true
                Starter.Needs.WEB -> web
                Starter.Needs.PHOTOS -> photos
            }
        }
        return listOf(greeting(hour)) + possible.shuffled(random).distinctBy { it.kind }.take(count)
    }
}

/** The big hello at the top: "Good morning", by the clock. */
private fun salutation(hour: Int): String = when (hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Hello, night owl"
}

/**
 * A blank chat, from the top: the date, a big hello (the user's name rising in letter by letter,
 * then a gleam of light across it), a wide tile to say hi, and four things to try in a grid, each a tap away from being
 * asked. The tiles come in one after another. A new set each time a chat is blank, and another
 * with the shuffle button.
 */
@Composable
fun StarterPanel(
    /** Changes when another blank chat opens: a fresh set. */
    chatKey: Any?,
    /** The user's first name, from their profile; null when it isn't filled in. */
    name: String?,
    web: Boolean,
    photos: Boolean,
    enabled: Boolean,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = remember(chatKey) { LocalDateTime.now() }
    var shuffles by rememberSaveable(chatKey) { mutableIntStateOf(0) }
    val starters = remember(chatKey, shuffles, web, photos) { Starters.pick(Random.Default, now.hour, web, photos, count = 4) }

    Column(
        modifier
            // Clear of the message bar: a gap, and the tiles fade before it when scrolled.
            .padding(bottom = 12.dp)
            .fadingEdges(top = 0.dp, bottom = 20.dp)
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
    ) {
        Text(
            now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())).uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.4.sp),
            color = AppColors.TextSecondary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (name != null) "${salutation(now.hour)}," else salutation(now.hour),
            style = HelloStyle,
            color = AppColors.TextPrimary,
        )
        NameReveal(name ?: "what shall we do?", key = chatKey)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Tap one to start, or ask anything",
                style = MaterialTheme.typography.bodyLarge,
                color = AppColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            GlassRoundButton(Icons.Outlined.Refresh, "Other suggestions", { shuffles++ }, size = 40.dp, iconSize = 18.dp)
        }
        Spacer(Modifier.height(14.dp))

        // A new set comes in tile by tile.
        key(starters) {
            val greeting = starters.first()
            val others = starters.drop(1)
            StarterTile(greeting, index = 0, wide = true, enabled = enabled, onClick = { onPick(greeting.text) })
            others.chunked(2).forEachIndexed { row, pair ->
                Spacer(Modifier.height(TILE_GAP))
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                    pair.forEachIndexed { column, starter ->
                        StarterTile(
                            starter,
                            index = 1 + row * 2 + column,
                            wide = false,
                            enabled = enabled,
                            onClick = { onPick(starter.text) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * One suggestion on a glass tile: what it shows off, in a tinted bead and a small label, and the
 * words it sends. The wide one is the hello. Rises into place [index] beats after the first, and
 * swells a little under the finger.
 */
@Composable
private fun StarterTile(
    starter: Starter,
    index: Int,
    wide: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val arrive = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * 70L)
        arrive.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow))
    }
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val squeeze by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "press")
    val accent = accent(starter.kind)

    val tile = modifier
        .graphicsLayer {
            alpha = arrive.value.coerceIn(0f, 1f)
            translationY = (1f - arrive.value) * 24.dp.toPx()
            scaleX = squeeze
            scaleY = squeeze
        }
        .liquidGlass(TileShape, Glass.Pane, sheen = 0.45f)
        .clickable(interactionSource = press, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(14.dp)

    val bead: @Composable () -> Unit = {
        Box(
            Modifier.size(if (wide) 38.dp else 30.dp).clip(CircleShape).background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon(starter.kind), contentDescription = null, tint = accent, modifier = Modifier.size(if (wide) 20.dp else 17.dp))
        }
    }
    val label: @Composable () -> Unit = {
        Text(
            label(starter.kind).uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.1.sp, fontWeight = FontWeight.SemiBold),
            color = accent,
        )
    }
    val words: @Composable () -> Unit = {
        Text(
            starter.text,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = if (enabled) AppColors.TextPrimary else AppColors.TextSecondary,
        )
    }

    if (wide) {
        Row(tile.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            bead()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                label()
                Spacer(Modifier.height(2.dp))
                words()
            }
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = AppColors.TextSecondary, modifier = Modifier.size(20.dp))
        }
    } else {
        Column(tile) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                bead()
                Spacer(Modifier.width(8.dp))
                label()
            }
            Spacer(Modifier.height(8.dp))
            words()
        }
    }
}

private fun icon(kind: Starter.Kind): ImageVector = when (kind) {
    Starter.Kind.GREETING -> Icons.Outlined.WavingHand
    Starter.Kind.REMINDER -> Icons.Outlined.NotificationsActive
    Starter.Kind.TIMER -> Icons.Outlined.Timer
    Starter.Kind.AGENDA -> Icons.Outlined.Event
    Starter.Kind.MEMORY -> Icons.Outlined.Psychology
    Starter.Kind.PHOTOS -> Icons.Outlined.PhotoLibrary
    Starter.Kind.WEB -> Icons.Outlined.Public
    Starter.Kind.MATHS -> Icons.Outlined.Calculate
    Starter.Kind.PHONE -> Icons.Outlined.FlashlightOn
    Starter.Kind.WRITING -> Icons.Outlined.Edit
    Starter.Kind.IDEAS -> Icons.Outlined.Lightbulb
    Starter.Kind.FOOD -> Icons.Outlined.Restaurant
    Starter.Kind.LANGUAGE -> Icons.Outlined.Translate
    Starter.Kind.TRAVEL -> Icons.Outlined.TravelExplore
}

private fun label(kind: Starter.Kind): String = when (kind) {
    Starter.Kind.GREETING -> "Say hi"
    Starter.Kind.REMINDER -> "Reminders"
    Starter.Kind.TIMER -> "Timers"
    Starter.Kind.AGENDA -> "Your schedule"
    Starter.Kind.MEMORY -> "Memory"
    Starter.Kind.PHOTOS -> "Your photos"
    Starter.Kind.WEB -> "Web search"
    Starter.Kind.MATHS -> "Quick maths"
    Starter.Kind.PHONE -> "Phone"
    Starter.Kind.WRITING -> "Writing"
    Starter.Kind.IDEAS -> "Ideas"
    Starter.Kind.FOOD -> "Cooking"
    Starter.Kind.LANGUAGE -> "Languages"
    Starter.Kind.TRAVEL -> "Travel"
}

/** Each kind keeps its colour, so a reminder always looks like a reminder. */
private fun accent(kind: Starter.Kind): Color = Glass.Ink[kind.ordinal % Glass.Ink.size]

/**
 * The name under the hello: its letters rise into place one after another, and once they have
 * landed a band of light passes over them, as over glass. Each blank chat plays it again ([key]).
 */
@Composable
private fun NameReveal(text: String, key: Any?) {
    val shine = remember(text, key) { Animatable(-0.4f) }
    LaunchedEffect(text, key) {
        delay(LETTER_BEAT * text.length + 250L)
        shine.animateTo(1.4f, tween(durationMillis = 1300, easing = FastOutSlowInEasing))
    }
    Row(
        Modifier
            .clearAndSetSemantics { contentDescription = text }
            // The light lands only on the letters: drawn over them in their own layer, SrcAtop.
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                val x = shine.value * size.width
                val band = maxOf(size.width * 0.3f, 48.dp.toPx())
                drawRect(
                    Brush.linearGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.9f), Color.Transparent),
                        start = Offset(x - band, 0f),
                        end = Offset(x + band, size.height),
                    ),
                    blendMode = BlendMode.SrcAtop,
                )
            },
    ) {
        text.forEachIndexed { index, letter ->
            val rise = remember(text, key) { Animatable(0f) }
            LaunchedEffect(text, key) {
                delay(LETTER_BEAT * index)
                rise.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow))
            }
            Text(
                letter.toString(),
                style = HelloStyle,
                color = AppColors.TextPrimary,
                modifier = Modifier.graphicsLayer {
                    alpha = rise.value.coerceIn(0f, 1f)
                    translationY = (1f - rise.value) * 18.dp.toPx()
                },
            )
        }
    }
}

private const val LETTER_BEAT = 45L
private val HelloStyle = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp)
private val TileShape = RoundedCornerShape(24.dp)
private val TILE_GAP = 10.dp
