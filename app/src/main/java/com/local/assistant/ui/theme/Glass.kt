package com.local.assistant.ui.theme

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import com.local.assistant.R
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.function.Consumer

/**
 * The app's glass, the look of the "While you were away" card. The screen is a pane of frosted
 * glass over the wallpaper (the system blurs it, from Android 12); what sits on it, cards, bars
 * and the message box, is lighter glass with a bright rim; the user's own words are dark.
 */
object Glass {
    /** Cards and grouped rows. */
    val Pane = Color.White.copy(alpha = 0.58f)

    /** What is pressed or typed into: the message box, chips, round buttons. */
    val Raised = Color.White.copy(alpha = 0.82f)

    /**
     * Bottom sheets, over the app's own content: they can't blur it, so solid, the backdrop's
     * light grey. See-through (0.88, even 0.96), the chat's bubbles and words read through a
     * sheet's header.
     */
    val Sheet = Color(0xFFF7F7F9)

    /** The bright edge of every pane. */
    val Rim = Color.White.copy(alpha = 0.95f)

    /** The user's own messages, and the main button. */
    val Mine = Brush.linearGradient(listOf(Color(0xFF3F3F46), Color(0xFF18181B)))

    /**
     * The assistant's light: blue through violet, pink, peach and teal, back to blue so a sweep
     * has no seam. The overlay's edge, the app's mark. Otherwise the app is greys.
     */
    val Iris = listOf(
        Color(0xFF5B8CFF),
        Color(0xFFA66CFF),
        Color(0xFFFF6FB5),
        Color(0xFFFFA36B),
        Color(0xFF4FD1C5),
        Color(0xFF5B8CFF),
    )

    /** The same colours deepened to read as text and icons on the white glass. */
    val Ink = listOf(
        Color(0xFF3B6BF0),
        Color(0xFF8B4FEF),
        Color(0xFFD9408A),
        Color(0xFFD9701F),
        Color(0xFF0F9488),
    )

    /** Behind dialogs and the drawer: light enough to keep the glass in view. */
    val Scrim = Color(0xFF18181B).copy(alpha = 0.18f)

    /** White over the blurred wallpaper: frosted, and dark text stays readable over a dark wallpaper. */
    internal const val WASH = 0xBDFFFFFF.toInt()

    /** Where the system won't blur: a plain light backdrop rather than a sharp wallpaper behind the text. */
    internal val SOLID = intArrayOf(0xFFF4F4F6.toInt(), 0xFFE6E6EB.toInt())

    /** Softer than the brief's: long answers are read over it. */
    internal const val BLUR_DP = 56
}

object GlassShapes {
    val Card = RoundedCornerShape(22.dp)
    val Field = RoundedCornerShape(23.dp)
    val Pill = RoundedCornerShape(50)
    val Mine = RoundedCornerShape(topStart = 20.dp, topEnd = 6.dp, bottomEnd = 20.dp, bottomStart = 20.dp)
}

/** A pane of glass: [fill] inside a bright rim. */
fun Modifier.glass(shape: Shape = GlassShapes.Card, fill: Color = Glass.Pane): Modifier =
    clip(shape).background(fill).border(1.dp, Glass.Rim, shape)

/**
 * Glass lit from above, after Apple's Liquid Glass: a sheen over the top half and an edge that is
 * bright at the top and fades down the sides. For the pieces of the message bar.
 */
fun Modifier.liquidGlass(shape: Shape, fill: Color = Glass.Raised, sheen: Float = 0.55f): Modifier =
    clip(shape)
        .background(fill)
        .drawWithCache {
            val light = Brush.verticalGradient(0f to Color.White.copy(alpha = sheen), 0.5f to Color.White.copy(alpha = 0f))
            onDrawBehind { drawRect(light) }
        }
        .border(1.dp, Brush.verticalGradient(listOf(Color.White, Color.White.copy(alpha = 0.35f))), shape)

/**
 * A round button of [liquidGlass] that swells a little under the finger and springs back: the
 * message bar's photo button, the overlay's.
 */
@Composable
fun GlassRoundButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 48.dp,
    iconSize: Dp = 22.dp,
    tint: Color = AppColors.TextPrimary,
) {
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 1.12f else 1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "press")
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .liquidGlass(CircleShape)
            .clickable(interactionSource = press, indication = null, enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
    ) {
        Icon(icon, contentDescription = label, tint = if (enabled) tint else AppColors.Border, modifier = Modifier.size(iconSize))
    }
}

/**
 * The app's mark: the orb (the launcher icon's foreground, in its colours) on a lens of glass,
 * a clear outer ring around a brighter disc, lifted off the page by a soft shadow, with a glint
 * of light slowly travelling round its rim.
 * The launch screen's drawable (splash_mark) is the same, still; here, while the model gets
 * ready and atop the chat list.
 */
@Composable
fun GlassMark(modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val turn by rememberInfiniteTransition(label = "mark").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 6000, easing = LinearEasing)),
        label = "turn",
    )
    val glint = Brush.sweepGradient(
        0f to Color.White.copy(alpha = 0f),
        0.06f to Color.White,
        0.16f to Color.White.copy(alpha = 0f),
        1f to Color.White.copy(alpha = 0f),
    )
    Box(
        modifier
            .size(size)
            .shadow(size * 0.14f, CircleShape, ambientColor = MarkShadow, spotColor = MarkShadow)
            .liquidGlass(CircleShape, Glass.Pane, sheen = 0.7f)
            .drawWithContent {
                drawContent()
                val width = this.size.minDimension * 0.045f
                rotate(turn) { drawCircle(glint, radius = this.size.minDimension / 2 - width, style = Stroke(width)) }
            }
            .padding(size * 0.11f)
            .liquidGlass(CircleShape, Color.White.copy(alpha = 0.9f), sheen = 0.8f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            tint = Color.Unspecified,
            // The orb fills less of its 108-unit box than the bubble did, so it's drawn larger.
            modifier = Modifier.requiredSize(size * 1.4f),
        )
    }
}

private val MarkShadow = Color(0xFF18181B).copy(alpha = 0.35f)

/** A card of glass, padded, for a group of rows or a block of settings. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    padding: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().glass().padding(padding), content = content)
}

/** A round glass button, the brief's: for back, close and the bar's actions. */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
) {
    Box(
        modifier
            .size(size)
            .glass(CircleShape, Glass.Raised)
            .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = AppColors.TextPrimary, modifier = Modifier.size(20.dp))
    }
}

/** A text field on the glass: a raised pane in a bright rim, with a dark edge while typing. */
@Composable
fun glassFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Glass.Raised,
    unfocusedContainerColor = Glass.Raised,
    disabledContainerColor = Glass.Pane,
    errorContainerColor = Glass.Raised,
    focusedBorderColor = AppColors.TextPrimary,
    unfocusedBorderColor = Glass.Rim,
)

/** Messages as a dark pill, the brief's notice, instead of Material's grey bar. */
@Composable
fun GlassSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        Snackbar(
            snackbarData = data,
            shape = GlassShapes.Field,
            containerColor = AppColors.TextPrimary.copy(alpha = 0.92f),
            contentColor = Color.White,
            actionColor = Color.White,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

/** A short line on the glass: a status ("Getting ready…"), in a pill rather than a grey strip. */
@Composable
fun GlassNotice(text: String, color: Color = AppColors.TextSecondary, onClick: (() -> Unit)? = null) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = color,
            modifier = Modifier
                .glass(GlassShapes.Pill, Glass.Raised)
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/**
 * Content that scrolls under the glass fades out at the edges rather than being cut off at a
 * line: [top] and [bottom] are how far the fade reaches.
 */
fun Modifier.fadingEdges(top: Dp = 16.dp, bottom: Dp = 16.dp): Modifier =
    graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
        drawContent()
        val topPx = top.toPx()
        val bottomPx = bottom.toPx()
        if (topPx > 0f) {
            drawRect(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, endY = topPx), blendMode = BlendMode.DstIn)
        }
        if (bottomPx > 0f) {
            drawRect(
                Brush.verticalGradient(0f to Color.Black, 1f to Color.Transparent, startY = size.height - bottomPx, endY = size.height),
                blendMode = BlendMode.DstIn,
            )
        }
    }

/**
 * Blurs everything behind [activity]'s window by [radius], from Android 12 and while the system
 * allows it (battery saver and a developer option turn window blurs off). [onChange] hears whether
 * it is blurring, at once and whenever that changes.
 */
fun blurBehind(activity: Activity, radius: Dp, onChange: (Boolean) -> Unit = {}) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        onChange(false)
        return
    }
    val window = activity.window
    val px = (radius.value * activity.resources.displayMetrics.density).toInt()
    val listener = Consumer<Boolean> { enabled ->
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = px }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        }
        onChange(enabled)
    }
    // Called at once with the current state when added.
    val watch = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = activity.windowManager.addCrossWindowBlurEnabledListener(activity.mainExecutor, listener)
        override fun onViewDetachedFromWindow(v: View) = activity.windowManager.removeCrossWindowBlurEnabledListener(listener)
    }
    window.decorView.addOnAttachStateChangeListener(watch)
    if (window.decorView.isAttachedToWindow) watch.onViewAttachedToWindow(window.decorView)
    listener.accept(activity.windowManager.isCrossWindowBlurEnabled)
}

/**
 * Makes [activity]'s window the frosted backdrop: the wallpaper behind it, blurred and washed
 * white. Where the system won't blur, the window gets a plain light backdrop instead rather than
 * a sharp wallpaper behind the text.
 *
 * Blur behind, not the window background blur the brief uses: that one only blurs behind a
 * translucent window (seen on the phone: a sharp wallpaper), and a translucent main screen would
 * keep the app it was opened from alive and showing behind it.
 */
fun installGlassBackdrop(activity: Activity) {
    blurBehind(activity, Glass.BLUR_DP.dp) { blurred ->
        val window = activity.window
        if (blurred) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(ColorDrawable(Glass.WASH))
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, Glass.SOLID))
        }
    }
}
