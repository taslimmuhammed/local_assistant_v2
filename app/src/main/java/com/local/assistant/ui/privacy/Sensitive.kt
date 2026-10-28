package com.local.assistant.ui.privacy

import android.app.KeyguardManager
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.local.assistant.privacy.SensitiveLock
import com.local.assistant.privacy.SensitiveText
import com.local.assistant.ui.theme.AppColors

/**
 * What a screen needs to hide sensitive details and show them on the user's say-so: the app's
 * [SensitiveLock], and the activity that can put up the fingerprint prompt.
 */
class SensitiveAccess(val lock: SensitiveLock, private val activity: FragmentActivity) {

    /** Over the lock screen nothing hidden is shown: whoever holds the phone hasn't unlocked it. */
    val onLockScreen: Boolean
        get() = activity.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    /** Asks for the fingerprint, face or PIN, and shows hidden details if it's given. */
    fun reveal() {
        if (onLockScreen) return
        val allowed = if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_WEAK or DEVICE_CREDENTIAL else BIOMETRIC_WEAK
        // A phone with no screen lock at all has nothing to ask for, and nothing a prompt would protect.
        if (BiometricManager.from(activity).canAuthenticate(allowed) == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED &&
            activity.getSystemService(KeyguardManager::class.java)?.isDeviceSecure != true
        ) {
            lock.reveal()
            return
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = lock.reveal()
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Show hidden details")
            .setSubtitle("Card, account and ID numbers, passwords and document photos")
            .apply {
                if (Build.VERSION.SDK_INT >= 30) {
                    setAllowedAuthenticators(allowed)
                } else {
                    @Suppress("DEPRECATION")
                    setDeviceCredentialAllowed(true)
                }
            }
            .build()
        prompt.authenticate(info)
    }
}

/** Provided at each activity's root; with none, details stay hidden and can't be shown. */
val LocalSensitive = staticCompositionLocalOf<SensitiveAccess?> { null }

/** Whether hidden details are being shown right now. */
@Composable
fun sensitiveRevealed(): Boolean {
    val access = LocalSensitive.current ?: return false
    val revealed by access.lock.revealed.collectAsState()
    return revealed
}

/** [text] as it may be shown, and whether anything in it is hidden. */
class Shown(val text: String, val hidden: Boolean)

/**
 * [text] with its sensitive details hidden unless they're being shown. A reply still
 * [streaming] also holds back a trailing number until it's complete, so a card number never
 * shows its first digits on the way.
 */
@Composable
fun rememberShown(text: String, streaming: Boolean = false): Shown {
    val revealed = sensitiveRevealed()
    return remember(text, streaming, revealed) {
        val settled = if (streaming && !revealed) SensitiveText.settled(text) else text
        val masked = if (revealed) settled else SensitiveText.masked(settled)
        Shown(masked, hidden = masked != settled)
    }
}

/** "Hidden details · Tap to show", under something with details hidden; asks for the fingerprint. */
@Composable
fun HiddenDetails(modifier: Modifier = Modifier) {
    val access = LocalSensitive.current
    val onLockScreen = access?.onLockScreen ?: true
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .then(if (access != null && !onLockScreen) Modifier.clickable { access.reveal() } else Modifier)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Lock, contentDescription = null, tint = AppColors.TextSecondary, modifier = Modifier.size(14.dp))
        Text(
            if (onLockScreen) "Hidden details · Unlock your phone to see them" else "Hidden details · Tap to show",
            style = MaterialTheme.typography.labelMedium,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/**
 * [photo] made unreadable: shrunk to a few pixels and stretched back, so it's a smear of its
 * colours. Works on every Android version, unlike a blur effect (Android 12 and up), and what's
 * drawn is the smear itself, never the photo under a filter.
 */
fun unreadable(photo: android.graphics.Bitmap): android.graphics.Bitmap {
    val tiny = android.graphics.Bitmap.createScaledBitmap(photo, UNREADABLE_PIXELS, UNREADABLE_PIXELS, true)
    return android.graphics.Bitmap.createScaledBitmap(tiny, photo.width, photo.height, true).also { if (tiny !== it) tiny.recycle() }
}

private const val UNREADABLE_PIXELS = 8
