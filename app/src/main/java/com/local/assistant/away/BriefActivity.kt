package com.local.assistant.away

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import com.local.assistant.R
import com.local.assistant.appContainer
import com.local.assistant.ui.theme.LocalAssistantTheme
import java.util.function.Consumer

/**
 * "While you were away", over whatever is on screen: a pane of frosted glass at the bottom, the
 * size of what it has to say, with the app behind it still in view.
 *
 * The window is the card. From Android 12 the system blurs what's behind it (a phone with blur
 * turned off, or in battery saver, gets a near-opaque card instead). Closes with ✕, back, a tap
 * outside it, a pull down on its top, or by going anywhere else.
 */
class BriefActivity : ComponentActivity() {

    private val viewModel: BriefViewModel by viewModels {
        BriefViewModel.factory(appContainer, application, newOnly = intent.getBooleanExtra(EXTRA_NEW_ONLY, false))
    }

    /** The system is blurring behind the card now. */
    private val blurred = mutableStateOf(false)

    private val glass = GradientDrawable()
    private val blurListener = Consumer<Boolean> { enabled -> showBlur(enabled) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        transition(opening = true)
        onBackPressedDispatcher.addCallback(this) { close() }

        val awayMs = if (viewModel.newOnly) appContainer.awayBrief.lastAwayMs else 0L
        val maxHeight = (resources.displayMetrics.heightPixels * MAX_HEIGHT_SHARE / resources.displayMetrics.density).dp
        setContent {
            LocalAssistantTheme {
                BriefPanel(
                    viewModel = viewModel,
                    blurred = blurred.value,
                    awayMs = awayMs,
                    maxHeight = maxHeight,
                    onClose = ::close,
                )
            }
        }
        // After the content: setting it lays a floating window out afresh, at wrap-content.
        placeWindow()
        dressWindow()
        // The keyboard's height reaches the panel (BriefPanel pads for it) instead of the window
        // being pushed up and left running under the keyboard (seen on the phone).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) window.setDecorFitsSystemWindows(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            windowManager.addCrossWindowBlurEnabledListener(mainExecutor, blurListener)
        }
    }

    override fun onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) windowManager.removeCrossWindowBlurEnabledListener(blurListener)
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        placeWindow()
    }

    /** A touch outside the card: close, as a dialog would. */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE || (event.actionMasked == MotionEvent.ACTION_DOWN && outside(event))) {
            close()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun onStop() {
        super.onStop()
        // Gone somewhere else (the chat it opened, a call, home): the brief has done its job.
        if (!isChangingConfigurations && !isFinishing) close()
    }

    private fun outside(event: MotionEvent): Boolean {
        val decor: View = window.decorView
        val slop = ViewConfiguration.get(this).scaledWindowTouchSlop
        return event.x < -slop || event.y < -slop || event.x > decor.width + slop || event.y > decor.height + slop
    }

    /** At the bottom, inset from the edges, above the navigation bar and the keyboard. */
    private fun placeWindow() {
        val density = resources.displayMetrics.density
        val margin = (MARGIN_DP * density).toInt()
        val screenWidth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.width()
        } else {
            resources.displayMetrics.widthPixels
        }
        val attrs = window.attributes
        attrs.width = minOf(screenWidth - 2 * margin, (MAX_WIDTH_DP * density).toInt())
        attrs.height = WindowManager.LayoutParams.WRAP_CONTENT
        attrs.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        attrs.y = margin
        attrs.dimAmount = DIM
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            attrs.fitInsetsTypes = WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout()
            attrs.fitInsetsSides = WindowInsets.Side.all()
        }
        window.attributes = attrs
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
    }

    /** The glass itself: a rounded, see-through window background, blurred behind from Android 12. */
    private fun dressWindow() {
        val density = resources.displayMetrics.density
        glass.cornerRadius = CORNER_DP * density
        glass.setStroke((1 * density).toInt().coerceAtLeast(1), RIM)
        window.setBackgroundDrawable(glass)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setBackgroundBlurRadius((BLUR_DP * density).toInt())
            showBlur(windowManager.isCrossWindowBlurEnabled)
        } else {
            showBlur(false)
        }
    }

    private fun showBlur(enabled: Boolean) {
        blurred.value = enabled
        glass.setColor(if (enabled) FROSTED else SOLID)
    }

    private fun close() {
        if (isFinishing) return
        viewModel.sendWaitingNow()
        viewModel.closed()
        finish()
        transition(opening = false)
    }

    private fun transition(opening: Boolean) {
        val enter = if (opening) R.anim.brief_enter else 0
        val exit = if (opening) 0 else R.anim.brief_exit
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(if (opening) Activity.OVERRIDE_TRANSITION_OPEN else Activity.OVERRIDE_TRANSITION_CLOSE, enter, exit)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(enter, exit)
        }
    }

    companion object {
        private const val EXTRA_NEW_ONLY = "com.local.assistant.extra.NEW_ONLY"

        private const val MARGIN_DP = 10
        private const val MAX_WIDTH_DP = 560
        const val CORNER_DP = 28
        private const val BLUR_DP = 36
        private const val MAX_HEIGHT_SHARE = 0.76f
        private const val DIM = 0.16f

        /** White over the blur: frosted, and dark text stays readable over a dark app. */
        private const val FROSTED = 0xBDFFFFFF.toInt()
        private const val SOLID = 0xF7FFFFFF.toInt()
        private const val RIM = 0xCCFFFFFF.toInt()

        /** [newOnly]: what wasn't in an earlier brief (at unlock); otherwise everything unseen today. */
        fun intent(context: Context, newOnly: Boolean): Intent =
            Intent(context, BriefActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_NEW_ONLY, newOnly)
    }
}
