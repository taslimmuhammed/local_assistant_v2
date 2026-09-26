package com.local.assistant.memory.tools

/** What handing something to the phone came to. */
enum class Handoff {
    DONE,

    /** No app on the phone takes it (no WhatsApp, no maps app…). */
    NO_APP,

    /** Another app's screen can only be opened while this app is in front. */
    IN_BACKGROUND,

    /** The phone wants the user to allow it first; the screen to do that is open. */
    NEEDS_ACCESS,
    FAILED,
}

enum class MessageApp { SMS, WHATSAPP, EMAIL }

enum class RingerMode { NORMAL, VIBRATE, SILENT }

/** Settings an app may not switch itself on Android 10+; it can only open their panel. */
enum class SettingsPanel(val label: String) {
    WIFI("Wi-Fi"),
    BLUETOOTH("Bluetooth"),
    MOBILE_DATA("mobile data"),
    AIRPLANE_MODE("airplane mode"),
    LOCATION("location"),
    HOTSPOT("hotspot"),
}

sealed interface VolumeChange {
    data object Up : VolumeChange
    data object Down : VolumeChange
    data object Mute : VolumeChange
    data object Unmute : VolumeChange
    data class To(val percent: Int) : VolumeChange
}

/** An app with a launcher icon. */
data class InstalledApp(val label: String, val packageName: String)

/** What open_app ends up opening. */
sealed interface AppTarget {
    data class Launch(val app: InstalledApp) : AppTarget
    data object Camera : AppTarget
    data object Settings : AppTarget
    data class Directions(val place: String) : AppTarget
    /** Songs to play, in [app] or whichever music app answers. */
    data class Play(val query: String, val app: InstalledApp?) : AppTarget
    data class Videos(val query: String, val app: InstalledApp?) : AppTarget
    data class WebSearch(val query: String) : AppTarget
    data class StoreSearch(val query: String) : AppTarget
}

/**
 * The phone's own apps and switches, for the everyday tools. Anything that sends or calls is
 * only prepared here — the dialer opens with the number, the message app with the text — and
 * the user presses the button. The Android implementation is `device/AndroidPhone`.
 */
interface PhoneActions {
    fun dial(number: String): Handoff

    fun compose(app: MessageApp, to: String, text: String?): Handoff

    fun installedApps(): List<InstalledApp>

    fun open(target: AppTarget): Handoff

    fun torch(on: Boolean): Handoff

    fun ringer(mode: RingerMode): Handoff

    /** Media volume. The new level in percent, or null if it could not be changed. */
    fun volume(change: VolumeChange): Int?

    fun doNotDisturb(on: Boolean): Handoff

    fun openPanel(panel: SettingsPanel): Handoff
}

data class ContactPhone(val number: String, val primary: Boolean = false, val mobile: Boolean = false)

data class Contact(val name: String, val phones: List<ContactPhone>, val emails: List<String> = emptyList())

/** The phone's contacts. */
fun interface ContactBook {
    /** Everyone with a number or an email; null when the user has not allowed access. */
    suspend fun all(): List<Contact>?
}

/** What memory knows that helps find someone: names they go by, and a number or email saved for them. */
data class PersonHints(val names: List<String> = emptyList(), val phone: String? = null, val email: String? = null) {
    companion object {
        val NONE = PersonHints()
    }
}

fun interface PeopleMemory {
    suspend fun hints(who: String): PersonHints
}

/** No phone to hand things to: every device tool says so. */
object NoPhone : PhoneActions {
    override fun dial(number: String) = Handoff.NO_APP
    override fun compose(app: MessageApp, to: String, text: String?) = Handoff.NO_APP
    override fun installedApps(): List<InstalledApp> = emptyList()
    override fun open(target: AppTarget) = Handoff.NO_APP
    override fun torch(on: Boolean) = Handoff.NO_APP
    override fun ringer(mode: RingerMode) = Handoff.NO_APP
    override fun volume(change: VolumeChange): Int? = null
    override fun doNotDisturb(on: Boolean) = Handoff.NO_APP
    override fun openPanel(panel: SettingsPanel) = Handoff.NO_APP
}
