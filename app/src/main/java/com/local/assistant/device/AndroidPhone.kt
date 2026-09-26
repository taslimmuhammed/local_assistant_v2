package com.local.assistant.device

import android.app.NotificationManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.util.Log
import com.local.assistant.memory.tools.AppTarget
import com.local.assistant.memory.tools.Handoff
import com.local.assistant.memory.tools.InstalledApp
import com.local.assistant.memory.tools.MessageApp
import com.local.assistant.memory.tools.PhoneActions
import com.local.assistant.memory.tools.RingerMode
import com.local.assistant.memory.tools.SettingsPanel
import com.local.assistant.memory.tools.VolumeChange
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The everyday tools on Android, through standard intents wherever there is one, so they work
 * with whichever dialer, messaging, maps or music app the user has.
 *
 * Opening another app's screen is only allowed while this app is in front — which it is when the
 * user has just asked — so each handoff checks that first and says so rather than failing
 * silently. Switches the system reserves for itself (Wi-Fi, Bluetooth, mobile data) open their
 * settings panel instead.
 */
class AndroidPhone(
    private val context: Context,
    private val inForeground: () -> Boolean,
) : PhoneActions {

    private val packages = context.packageManager

    override fun dial(number: String): Handoff =
        start(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))

    override fun compose(app: MessageApp, to: String, text: String?): Handoff = when (app) {
        MessageApp.SMS -> start(
            Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", to, null)).apply { text?.let { putExtra("sms_body", it) } },
        )
        MessageApp.EMAIL -> start(
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(to, "@")}" + (text?.let { "?body=" + Uri.encode(it) } ?: "")))
                .apply { text?.let { putExtra(Intent.EXTRA_TEXT, it) } },
        )
        MessageApp.WHATSAPP -> {
            val whatsapp = WHATSAPP_PACKAGES.firstOrNull { packages.getLaunchIntentForPackage(it) != null }
            val number = international(to)
            when {
                whatsapp == null -> Handoff.NO_APP
                number == null -> Handoff.FAILED
                else -> start(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://api.whatsapp.com/send?phone=$number" + (text?.let { "&text=" + Uri.encode(it) } ?: "")),
                    ).setPackage(whatsapp),
                )
            }
        }
    }

    override fun installedApps(): List<InstalledApp> =
        packages.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { InstalledApp(it.loadLabel(packages).toString(), it.activityInfo.packageName) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }

    override fun open(target: AppTarget): Handoff = when (target) {
        is AppTarget.Launch -> packages.getLaunchIntentForPackage(target.app.packageName)?.let(::start) ?: Handoff.NO_APP
        AppTarget.Camera -> start(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        AppTarget.Settings -> start(Intent(Settings.ACTION_SETTINGS))
        is AppTarget.Directions -> start(view("https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(target.place)))
        is AppTarget.Play -> start(
            Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .putExtra(SearchManager.QUERY, target.query)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                .apply { target.app?.let { setPackage(it.packageName) } },
        ).let { handoff ->
            // No music app answers a spoken search: YouTube, or the browser, always can.
            if (handoff == Handoff.NO_APP && target.app == null) open(AppTarget.Videos(target.query, null)) else handoff
        }
        is AppTarget.Videos -> start(
            view("https://www.youtube.com/results?search_query=" + Uri.encode(target.query)).apply { target.app?.let { setPackage(it.packageName) } },
        )
        is AppTarget.WebSearch -> start(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, target.query)).let { handoff ->
            if (handoff == Handoff.NO_APP) start(view("https://www.google.com/search?q=" + Uri.encode(target.query))) else handoff
        }
        is AppTarget.StoreSearch -> start(view("market://search?q=" + Uri.encode(target.query) + "&c=apps")).let { handoff ->
            if (handoff == Handoff.NO_APP) start(view("https://play.google.com/store/search?q=" + Uri.encode(target.query) + "&c=apps")) else handoff
        }
    }

    override fun torch(on: Boolean): Handoff {
        val cameras = context.getSystemService(CameraManager::class.java) ?: return Handoff.NO_APP
        return try {
            val withFlash = cameras.cameraIdList.filter { cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            val id = withFlash.firstOrNull {
                cameras.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: withFlash.firstOrNull() ?: return Handoff.NO_APP
            cameras.setTorchMode(id, on)
            Handoff.DONE
        } catch (e: CameraAccessException) {
            // The camera is in use by another app.
            Log.w(TAG, "Torch unavailable", e)
            Handoff.FAILED
        }
    }

    override fun ringer(mode: RingerMode): Handoff {
        val audio = context.getSystemService(AudioManager::class.java) ?: return Handoff.FAILED
        return try {
            audio.ringerMode = when (mode) {
                RingerMode.NORMAL -> AudioManager.RINGER_MODE_NORMAL
                RingerMode.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
                RingerMode.SILENT -> AudioManager.RINGER_MODE_SILENT
            }
            Handoff.DONE
        } catch (e: SecurityException) {
            // A change that would switch Do Not Disturb needs the user's permission for it.
            Handoff.NEEDS_ACCESS
        }
    }

    override fun volume(change: VolumeChange): Int? {
        val audio = context.getSystemService(AudioManager::class.java) ?: return null
        val stream = AudioManager.STREAM_MUSIC
        val max = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
        val step = (max / VOLUME_STEPS).coerceAtLeast(1)
        return try {
            when (change) {
                VolumeChange.Up -> audio.setStreamVolume(stream, (audio.getStreamVolume(stream) + step).coerceAtMost(max), AudioManager.FLAG_SHOW_UI)
                VolumeChange.Down -> audio.setStreamVolume(stream, (audio.getStreamVolume(stream) - step).coerceAtLeast(0), AudioManager.FLAG_SHOW_UI)
                VolumeChange.Mute -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                VolumeChange.Unmute -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                is VolumeChange.To -> audio.setStreamVolume(stream, (max * change.percent / 100.0).roundToInt(), AudioManager.FLAG_SHOW_UI)
            }
            if (audio.isStreamMute(stream)) 0 else audio.getStreamVolume(stream) * 100 / max
        } catch (e: SecurityException) {
            Log.w(TAG, "Volume change refused", e)
            null
        }
    }

    override fun doNotDisturb(on: Boolean): Handoff {
        val notifications = context.getSystemService(NotificationManager::class.java) ?: return Handoff.FAILED
        if (!notifications.isNotificationPolicyAccessGranted) {
            start(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return Handoff.NEEDS_ACCESS
        }
        notifications.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        return Handoff.DONE
    }

    override fun openPanel(panel: SettingsPanel): Handoff {
        val q = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val action = when (panel) {
            SettingsPanel.WIFI -> if (q) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            SettingsPanel.MOBILE_DATA -> if (q) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS
            SettingsPanel.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS
            SettingsPanel.AIRPLANE_MODE -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            SettingsPanel.LOCATION -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            // No public action for the hotspot screen; this one is honoured widely, else the network page.
            SettingsPanel.HOTSPOT -> TETHER_SETTINGS
        }
        val handoff = start(Intent(action))
        return if (handoff == Handoff.NO_APP && panel == SettingsPanel.HOTSPOT) start(Intent(Settings.ACTION_WIRELESS_SETTINGS)) else handoff
    }

    /** The number as WhatsApp wants it: country code and digits, no plus. */
    private fun international(number: String): String? {
        val trimmed = number.trim()
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length < MIN_DIGITS) return null
        return when {
            trimmed.startsWith("+") -> digits
            digits.startsWith("00") -> digits.drop(2)
            else -> PhoneNumberUtils.formatNumberToE164(trimmed, countryIso())?.removePrefix("+") ?: digits
        }
    }

    private fun countryIso(): String {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        return listOfNotNull(telephony?.networkCountryIso, telephony?.simCountryIso, Locale.getDefault().country)
            .firstOrNull { it.isNotBlank() }
            ?.uppercase(Locale.ROOT) ?: "IN"
    }

    private fun view(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url))

    private fun start(intent: Intent): Handoff {
        if (!inForeground()) return Handoff.IN_BACKGROUND
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Handoff.DONE
        } catch (e: ActivityNotFoundException) {
            Handoff.NO_APP
        } catch (e: SecurityException) {
            Log.w(TAG, "Refused: $intent", e)
            Handoff.FAILED
        }
    }

    private companion object {
        const val TAG = "AndroidPhone"
        const val MIN_DIGITS = 6
        /** "Volume up" moves by a fifth of the range: one notch is barely audible. */
        const val VOLUME_STEPS = 5
        const val TETHER_SETTINGS = "android.settings.TETHER_SETTINGS"
        val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")
    }
}
