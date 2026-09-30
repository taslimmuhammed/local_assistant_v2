package com.local.assistant.assist

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.local.assistant.MainActivity
import com.local.assistant.appContainer
import com.local.assistant.ui.theme.LocalAssistantTheme

/**
 * The assistant overlay: what opens when the power button is held (once this app is the phone's
 * digital assistant, see [AssistantRole]), or from the app icon's "Talk to assistant" shortcut.
 *
 * A translucent window over whatever was on screen, with a panel that rises from the bottom and
 * starts listening. It lives only while it is in view: going home, switching apps, or a tool
 * opening the dialer closes it, and a reply still coming finishes in its chat.
 *
 * On the lock screen it works without unlocking only when the user has turned that on (Settings →
 * Assistant, off by default, since whoever holds the phone then gets the assistant's memory and
 * its calls and messages). Otherwise it says how to turn it on, and leaves a notification.
 */
class AssistActivity : ComponentActivity() {

    private val viewModel: AssistViewModel by viewModels { AssistViewModel.factory(appContainer) }

    /** Opened on the lock screen with talking there turned off: it only says how to turn it on. */
    private val lockedOut = mutableStateOf(false)

    /** Counts unlocks asked for by "Open in app" and then cancelled, so the panel can settle back. */
    private val openCancelled = mutableIntStateOf(0)

    /** The unlock prompt is up: the overlay stays while it is. */
    private var awaitingUnlock = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // The panel animates itself in; the window shouldn't as well.
        skipTransition(opening = true)
        // Asking and answering take a while; on the lock screen, the screen timing out would take
        // the panel with it.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (isLocked()) {
            // Over the lock screen either way: to talk, or to say how talking there is turned on.
            showWhenLocked()
            if (!appContainer.settings.assistantOnLockScreen) {
                lockedOut.value = true
                LockScreenNotice.post(this)
            }
        }

        setContent {
            LocalAssistantTheme {
                AssistOverlay(
                    viewModel = viewModel,
                    lockedOut = lockedOut.value,
                    openCancelled = openCancelled.intValue,
                    onClosed = ::close,
                    onOpenApp = ::openApp,
                    onOpenSettings = ::openSettings,
                )
            }
        }
    }

    /** Held again while open: listen again. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!lockedOut.value && viewModel.input.value == AssistViewModel.Input.VOICE && micGranted()) viewModel.startListening()
    }

    override fun onStop() {
        super.onStop()
        // Out of sight, out of the way: an overlay left behind would sit in its own task.
        if (!isChangingConfigurations && !awaitingUnlock && !isFinishing) close()
    }

    private fun isLocked(): Boolean = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    private fun showWhenLocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
    }

    private fun micGranted(): Boolean =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** The full app, at this overlay's chat if it has one. */
    private fun openApp(chatId: Long?) = afterUnlock {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply { chatId?.let { putExtra(MainActivity.EXTRA_CHAT_ID, it) } },
        )
        close()
    }

    /** The app's settings, where talking on the lock screen is turned on. */
    private fun openSettings() = afterUnlock {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true),
        )
        close()
    }

    /**
     * The app itself doesn't show over the lock screen, so it waits for the unlock. Asked for
     * from a tap, with the panel in view: asked for while the panel was still being created, the
     * prompt never came on the test phone and the panel sat there.
     */
    private fun afterUnlock(then: () -> Unit) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isKeyguardLocked) {
            then()
            return
        }
        awaitingUnlock = true
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    awaitingUnlock = false
                    then()
                }

                override fun onDismissCancelled() {
                    awaitingUnlock = false
                    openCancelled.intValue++
                }

                // No prompt to be had: open anyway, and the app waits behind the lock screen.
                override fun onDismissError() {
                    awaitingUnlock = false
                    then()
                }
            },
        )
    }

    private fun close() {
        if (isFinishing) return
        finish()
        skipTransition(opening = false)
    }

    private fun skipTransition(opening: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                if (opening) Activity.OVERRIDE_TRANSITION_OPEN else Activity.OVERRIDE_TRANSITION_CLOSE,
                0,
                0,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
