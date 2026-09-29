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
 */
class AssistActivity : ComponentActivity() {

    private val viewModel: AssistViewModel by viewModels { AssistViewModel.factory(appContainer) }

    /** False while the phone is locked and the unlock prompt is up. */
    private val unlocked = mutableStateOf(true)
    private var awaitingUnlock = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // The panel animates itself in; the window shouldn't as well.
        skipTransition(opening = true)
        unlockFirst()

        setContent {
            LocalAssistantTheme {
                AssistOverlay(
                    viewModel = viewModel,
                    unlocked = unlocked.value,
                    onClosed = ::close,
                    onOpenApp = ::openApp,
                )
            }
        }
    }

    /** Held again while open: listen again. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (unlocked.value && viewModel.input.value == AssistViewModel.Input.VOICE && micGranted()) viewModel.startListening()
    }

    override fun onStop() {
        super.onStop()
        // Out of sight, out of the way: an overlay left behind would sit in its own task.
        if (!isChangingConfigurations && !awaitingUnlock && !isFinishing) close()
    }

    /**
     * On the lock screen, the unlock comes first: the assistant can read what it remembers about
     * the user, make calls and send messages. The panel shows meanwhile, but doesn't listen.
     */
    private fun unlockFirst() {
        val keyguard = getSystemService(KeyguardManager::class.java) ?: return
        if (!keyguard.isKeyguardLocked) return
        showWhenLocked(true)
        unlocked.value = false
        awaitingUnlock = true
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    awaitingUnlock = false
                    // Unlocked now; if the phone locks again, the overlay goes behind it.
                    showWhenLocked(false)
                    unlocked.value = true
                }

                override fun onDismissCancelled() {
                    awaitingUnlock = false
                    close()
                }

                override fun onDismissError() {
                    awaitingUnlock = false
                    close()
                }
            },
        )
    }

    private fun showWhenLocked(show: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(show)
        } else {
            @Suppress("DEPRECATION")
            if (show) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            }
        }
    }

    private fun micGranted(): Boolean =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** The full app, at this overlay's chat if it has one. */
    private fun openApp(chatId: Long?) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply { chatId?.let { putExtra(MainActivity.EXTRA_CHAT_ID, it) } },
        )
        close()
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
