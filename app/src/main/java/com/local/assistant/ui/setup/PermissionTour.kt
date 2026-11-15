package com.local.assistant.ui.setup

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.ScreenshotMonitor
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.local.assistant.AppContainer
import com.local.assistant.assist.AssistantRole
import com.local.assistant.away.MessageListener
import com.local.assistant.files.FileAccess
import com.local.assistant.gallery.PhotoLibrary
import com.local.assistant.ui.settings.openAccess
import com.local.assistant.ui.settings.openAppInfo
import com.local.assistant.ui.theme.AppColors
import com.local.assistant.ui.theme.AppSwitch
import com.local.assistant.ui.theme.Glass
import com.local.assistant.ui.theme.GlassShapes
import com.local.assistant.ui.theme.fadingEdges
import com.local.assistant.ui.theme.glass
import com.local.assistant.ui.theme.liquidGlass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * First launch, after web search: everything the app asks Android for, one page each, saying
 * why before Android's own dialog (the user, 2026-11-15: "ask all the required permission after
 * walking through why we require that permission"). Replaces the access page of 2026-11-13
 * (photos, files and messages on one page); installs from before see this once too
 * ([com.local.assistant.data.prefs.SettingsStore.accessAsked]).
 *
 * Each page can be passed by. Whatever was passed by is still asked the first time it's needed,
 * as before: contacts on "call amma", location on a place reminder, the microphone on the first
 * voice message. A page whose permission is already given is left out; one given while it's
 * open moves on by itself, unless it has one more thing to offer (location all the time, exact
 * reminders, SMS). The last page lists them all, each still allowable from there.
 *
 * Health data and "While you were away" aren't asked: both stay off until turned on in Settings
 * (the user's call, 2026-11-05), and the last page says where.
 */
@Composable
fun PermissionTour(container: AppContainer, onDone: () -> Unit) = PermissionTour(container, onDone, preview = null)

/**
 * For the debug builds' preview page: every page, as if nothing were given but [given] (names of
 * [Ask]); Allow asks nothing, and no page moves on by itself.
 */
internal class TourPreview(val given: Set<String> = emptySet(), val startPage: Int = 0)

@Composable
internal fun PermissionTour(container: AppContainer, onDone: () -> Unit, preview: TourPreview?) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val library = container.photoLibrary
    val settings = container.settings

    // Bumped whenever something may have been given: back from Android's pages, or its dialog.
    var checked by remember { mutableIntStateOf(0) }
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { checked++ } }
    val given = remember(checked) { Ask.entries.associateWith { if (preview != null) it.name in preview.given else it.given(context, library) } }
    val more = remember(checked) { Ask.entries.associateWith { if (preview != null) it in PREVIEW_MORE else it.morePending(context) } }

    var findPeople by remember { mutableStateOf(if (library.access() == PhotoLibrary.Access.NONE) true else settings.findPeople) }
    var keepMessages by remember { mutableStateOf(true) }

    // The pages to go through: what isn't given yet, or is but has more to offer. Fixed once
    // shown, so a page doesn't vanish under the finger as it's allowed.
    val pages = remember {
        Ask.entries.filter { it.applies() && (preview != null || !it.given(context, library) || it.morePending(context)) }
    }
    val count = pages.size + 1
    val pager = rememberPagerState(initialPage = (preview?.startPage ?: 0).coerceIn(0, count - 1)) { count }
    val page = pager.currentPage
    fun goTo(target: Int) = scope.launch { pager.animateScrollToPage(target.coerceIn(0, count - 1)) }

    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { checked++ }
    val photoRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        checked++
        if (library.access() == PhotoLibrary.Access.NONE) return@rememberLauncherForActivityResult
        settings.findPeople = findPeople
        // The photo model now, beside the chat model: photos are searchable sooner.
        val models = container.galleryModelManager
        if (models.installed.value == null && models.transfer.value == null) models.download()
        container.indexPhotosIfReady()
    }

    fun allow(ask: Ask) {
        if (preview != null) return
        when (ask) {
            Ask.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) runtime.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            Ask.MICROPHONE -> runtime.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            Ask.CONTACTS -> runtime.launch(arrayOf(Manifest.permission.READ_CONTACTS))
            Ask.PHOTOS -> photoRequest.launch(library.permissions + library.locationPermissions)
            Ask.LOCATION -> runtime.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            Ask.FILES -> FileAccess.permission?.let { runtime.launch(arrayOf(it)) } ?: FileAccess.openSettings(context)
            Ask.ASSISTANT -> AssistantRole.openSettings(context)
            Ask.MESSAGES -> openAccess(context)
            Ask.BATTERY -> openAppInfo(context)
        }
    }

    fun allowMore(ask: Ask) {
        if (preview != null) return
        when (ask) {
            Ask.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 31) runCatching {
                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
            }
            // A settings page from Android 11 on, chosen there: "Allow all the time".
            Ask.LOCATION -> if (Build.VERSION.SDK_INT >= 29) runtime.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            Ask.MESSAGES -> runtime.launch(arrayOf(Manifest.permission.READ_SMS))
            else -> Unit
        }
    }

    fun setFindPeople(on: Boolean) {
        findPeople = on
        // Before photos are allowed it's only the choice; it's written when they are.
        if (library.access() == PhotoLibrary.Access.NONE || on == settings.findPeople) return
        settings.findPeople = on
        scope.launch { if (on) container.indexPhotosIfReady() else container.faceIndex.forgetAll() }
    }

    fun finish() {
        if (preview != null) return onDone()
        val assistant = AssistantRole.isDefault(context)
        val sms = context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        settings.keepMessages = keepMessages && (MessageListener.allowed(context) || (assistant && sms))
        settings.accessAsked = true
        // Tools for files and messages come and go with these.
        container.conversations.prefixMayHaveChanged()
        onDone()
    }

    // Allowed while its page is open: on to the next, unless there's one more thing to offer here.
    LaunchedEffect(page, checked) {
        if (preview != null) return@LaunchedEffect
        val ask = pages.getOrNull(page) ?: return@LaunchedEffect
        if (given.getValue(ask) && !offersMore(more, ask, keepMessages)) {
            delay(ADVANCE_MS)
            if (pager.currentPage == page) goTo(page + 1)
        }
    }

    BackHandler(enabled = page > 0) { goTo(page - 1) }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (page < pages.size) "Permission ${page + 1} of ${pages.size}" else "All set",
                    style = MaterialTheme.typography.labelLarge,
                    color = AppColors.TextSecondary,
                )
                Spacer(Modifier.height(6.dp))
                Segments(pages.size, done = page, modifier = Modifier.fillMaxWidth(0.6f))
            }
            if (page < pages.size) {
                Text(
                    "Skip",
                    style = MaterialTheme.typography.labelLarge,
                    color = AppColors.TextPrimary,
                    modifier = Modifier
                        .glass(GlassShapes.Pill, Glass.Raised)
                        .clickable(role = Role.Button, onClickLabel = "Skip to the list") { goTo(pages.size) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        HorizontalPager(pager, Modifier.weight(1f), userScrollEnabled = false) { index ->
            val ask = pages.getOrNull(index)
            if (ask == null) {
                AllSet(given = given, onAllow = ::allow)
            } else {
                AskPage(
                    ask = ask,
                    given = given.getValue(ask),
                    playing = page == index,
                    offersMore = offersMore(more, ask, keepMessages),
                ) {
                    AskExtra(
                        ask = ask,
                        given = given.getValue(ask),
                        morePending = more.getValue(ask),
                        findPeople = findPeople,
                        onFindPeople = ::setFindPeople,
                        keepMessages = keepMessages,
                        onKeepMessages = { keepMessages = it },
                        onMore = { allowMore(ask) },
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val ask = pages.getOrNull(page)
            if (ask == null) {
                Spacer(Modifier.weight(1f))
                NextButton("Continue", ::finish)
            } else {
                val done = given.getValue(ask)
                // Passed by for now: asked again when it's first needed.
                if (!done) SmallAction("Not now", { goTo(page + 1) })
                Spacer(Modifier.weight(1f))
                NextButton(
                    text = if (done) "Next" else ask.action,
                    onClick = { if (done) goTo(page + 1) else allow(ask) },
                    icon = if (done) Icons.AutoMirrored.Outlined.ArrowForward else null,
                )
            }
        }
    }
}

/** How many are done, as a row of short bars: the walk through the pages, at a glance. */
@Composable
private fun Segments(count: Int, done: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(count) { index ->
            val fill by animateFloatAsState(if (index < done) 1f else if (index == done) 0.5f else 0f, label = "segment")
            Box(Modifier.weight(1f).height(4.dp).background(AppColors.TextPrimary.copy(alpha = 0.12f), CircleShape)) {
                Box(Modifier.fillMaxWidth(fill).height(4.dp).background(AppColors.TextPrimary, CircleShape))
            }
        }
    }
}

/** One permission: a picture of what it's for, then why it's asked, what it gives, and what stays private. */
@Composable
private fun AskPage(ask: Ask, given: Boolean, playing: Boolean, offersMore: Boolean, extra: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    // Given, with one more thing to offer below: brought into view, not left under the fold.
    LaunchedEffect(given, offersMore) { if (given && offersMore) scroll.animateScrollTo(scroll.maxValue) }
    Column(
        Modifier
            .fillMaxSize()
            .fadingEdges(top = 12.dp, bottom = 12.dp)
            .verticalScroll(scroll)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        AskPicture(ask, given, playing)
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Bead(ask.icon, ask.accent, size = 28.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                ask.label.uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold),
                color = ask.accent,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(ask.title, style = TitleStyle, color = AppColors.TextPrimary, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(6.dp))
        Text(ask.why, style = BodyStyle, color = AppColors.TextSecondary)
        Spacer(Modifier.height(10.dp))
        ask.points.forEachIndexed { index, (icon, text) ->
            Rise(playing, beat = 3 + index) {
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp).glass(CircleShape, Glass.Raised), contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, tint = AppColors.TextPrimary, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextPrimary)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.glass(RoundedCornerShape(14.dp), Glass.Raised).padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Lock, contentDescription = null, tint = AppColors.TextSecondary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(ask.privacy, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        }
        extra()
    }
}

/**
 * The picture: the permission's mark with rings going out from it, turning to a tick once it's
 * given, and one thing it lets you say, as the chat shows it.
 */
@Composable
private fun AskPicture(ask: Ask, given: Boolean, playing: Boolean) {
    val ripple by rememberInfiniteTransition(label = "ripple").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 3200, easing = LinearEasing)),
        label = "ripple",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(PICTURE_HEIGHT)
            .liquidGlass(SceneShape, Glass.Pane, sheen = 0.4f)
            .background(Brush.radialGradient(listOf(ask.accent.copy(alpha = 0.12f), Color.Transparent)))
            .clearAndSetSemantics { contentDescription = "${ask.label}: ${ask.example}" }
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            repeat(3) { ring ->
                val t = (ripple + ring / 3f) % 1f
                drawCircle(
                    ask.accent.copy(alpha = (1f - t) * 0.35f),
                    radius = 38.dp.toPx() + t * 56.dp.toPx(),
                    style = Stroke(1.5.dp.toPx()),
                )
            }
        }
        AnimatedContent(given, transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.6f)) togetherWith fadeOut() }, label = "mark") { done ->
            Box(
                Modifier.size(72.dp).background(if (done) ask.accent else ask.accent.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (done) Icons.Filled.Check else ask.icon,
                    contentDescription = null,
                    tint = if (done) Color.White else ask.accent,
                    modifier = Modifier.size(34.dp),
                )
            }
        }
        Rise(playing, beat = 1, Modifier.align(Alignment.TopEnd)) { Mine(ask.example) }
        Rise(playing, beat = 4, Modifier.align(Alignment.BottomStart).offset(y = (-2).dp)) {
            Chip(ask.result, ask.resultAction, ask.icon, ask.accent)
        }
    }
}

/** What a page offers beyond its permission: the face switch, all-the-time location, exact reminders, SMS. */
@Composable
private fun AskExtra(
    ask: Ask,
    given: Boolean,
    morePending: Boolean,
    findPeople: Boolean,
    onFindPeople: (Boolean) -> Unit,
    keepMessages: Boolean,
    onKeepMessages: (Boolean) -> Unit,
    onMore: () -> Unit,
) {
    val context = LocalContext.current
    when (ask) {
        Ask.PHOTOS -> ExtraCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Find people in your photos", style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
                    Text(
                        "Groups faces so you can name people. Faces are kept only on this phone; turning this off deletes them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary,
                    )
                }
                AppSwitch(checked = findPeople, onCheckedChange = onFindPeople, modifier = Modifier.padding(start = 12.dp))
            }
        }
        Ask.NOTIFICATIONS -> if (given && morePending) ExtraCard {
            Text("Ring on the minute", style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
            Text(
                "Android may hold a reminder back a few minutes to save battery. Exact alarms ring it right on time.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
            )
            SmallAction("Allow exact alarms", onMore, Modifier.padding(top = 10.dp))
        }
        Ask.LOCATION -> if (given && morePending) ExtraCard {
            Text("Also when the app is closed", style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
            Text(
                "For a place reminder to ring when you arrive, Android needs location “all the time”. On the page that opens, choose Allow all the time.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
            )
            SmallAction("Allow all the time", onMore, Modifier.padding(top = 10.dp))
        }
        Ask.FILES -> if (!given) Hint("Android opens a page: turn on Allow access to manage all files, then come back.")
        Ask.ASSISTANT -> if (!given) Hint("Android doesn't let apps pick this themselves. On the page that opens, choose Digital assistant app, then Local Assistant.")
        Ask.BATTERY -> if (!given) Hint("In App info, tap Battery, then choose Unrestricted. On some phones it's Allow background activity.")
        Ask.MESSAGES -> {
            if (!given) {
                Hint("Installed from an APK and the switch is greyed out? Open App info, tap ⋮ and choose Allow restricted settings, then try again.")
                SmallAction("Open App info", { openAppInfo(context) }, Modifier.padding(top = 8.dp))
            }
            ExtraCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Keep messages for search", style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
                    AppSwitch(checked = keepMessages, onCheckedChange = onKeepMessages, modifier = Modifier.padding(start = 12.dp))
                }
                if (keepMessages) {
                    val assistant = AssistantRole.isDefault(context)
                    Text(
                        when {
                            !morePending && assistant -> "Your SMS inbox is searched too."
                            assistant -> "Your SMS from before can be searched too."
                            else -> "To search your SMS from before too, make Local Assistant your digital assistant (the page before)."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    if (assistant && morePending) SmallAction("Allow SMS", onMore, Modifier.padding(top = 10.dp))
                }
            }
        }
        else -> Unit
    }
}

@Composable
private fun ExtraCard(content: @Composable () -> Unit) {
    Spacer(Modifier.height(12.dp))
    Column(Modifier.fillMaxWidth().glass().padding(14.dp)) { content() }
}

@Composable
private fun Hint(text: String) {
    Spacer(Modifier.height(10.dp))
    Text(text, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
}

/** The last page: each permission, given or not, with a way to give it from here; then on to setup. */
@Composable
private fun AllSet(given: Map<Ask, Boolean>, onAllow: (Ask) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .fadingEdges(top = 12.dp, bottom = 12.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text("Here's what it can use", style = TitleStyle, color = AppColors.TextPrimary, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(6.dp))
        Text(
            "Anything left off is asked again the first time you need it. You can change any of these later in Settings or in Android's App info.",
            style = BodyStyle,
            color = AppColors.TextSecondary,
        )
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().glass().padding(vertical = 4.dp)) {
            val shown = Ask.entries.filter { it.applies() }
            shown.forEachIndexed { index, ask ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Bead(ask.icon, ask.accent, size = 32.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ask.label, style = MaterialTheme.typography.titleSmall, color = AppColors.TextPrimary)
                        Text(ask.short, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                    }
                    if (given[ask] == true) {
                        Icon(Icons.Filled.Check, contentDescription = "Allowed", tint = AppColors.TextPrimary, modifier = Modifier.size(20.dp))
                    } else {
                        SmallAction("Allow", { onAllow(ask) })
                    }
                }
                if (index < shown.lastIndex) Box(Modifier.padding(start = 58.dp, end = 14.dp).fillMaxWidth().height(1.dp).background(AppColors.Border))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Health data and “While you were away” stay off until you turn them on in Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary,
            modifier = Modifier.glass(RoundedCornerShape(14.dp), Glass.Raised).padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Next: the model download (${com.local.assistant.model.formatBytes(com.local.assistant.model.ModelCatalog.SIZE_BYTES)}, best on Wi-Fi), then your photos are indexed. You'll see each step.",
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary,
        )
    }
}

/**
 * What the app asks of Android, in the order a page is shown: what someone meets first (a
 * reminder, talking to it, calling someone), then what it reads (photos, places, files,
 * messages), and last what keeps it working (the assistant role, the battery).
 */
private enum class Ask(
    val label: String,
    val icon: ImageVector,
    val accent: Color,
    val title: String,
    val why: String,
    val points: List<Pair<ImageVector, String>>,
    val privacy: String,
    val action: String,
    /** One line for the last page's list. */
    val short: String,
    /** Said in the picture, as the user would, and what comes back. */
    val example: String,
    val result: String,
    val resultAction: String,
) {
    NOTIFICATIONS(
        "Notifications", Icons.Outlined.NotificationsActive, Glass.Ink[0],
        "Let reminders reach you",
        "Reminders, alarms and timers you set by asking ring as notifications. They also show the model download and photo indexing while the app is closed.",
        listOf(
            Icons.Outlined.Schedule to "Reminders on time, and at a place",
            Icons.Outlined.Sync to "Download and indexing progress",
            Icons.Outlined.Block to "Nothing promotional, ever",
        ),
        "Made on this phone, from what you asked for",
        "Allow notifications",
        "Reminders, alarms and progress",
        "Remind me to call Amma at 6", "Call Amma · 6:00 PM", "Edit",
    ),
    MICROPHONE(
        "Microphone", Icons.Outlined.Mic, Glass.Ink[1],
        "Talk instead of typing",
        "Hold the mic to send a voice message, or say “Hey Luna” to start hands-free. What you say is written down on this phone; the recording never leaves it.",
        listOf(
            Icons.Outlined.Mic to "Voice messages in chat",
            Icons.Outlined.RecordVoiceOver to "“Hey Luna”, hands-free",
            Icons.Outlined.PowerSettingsNew to "Talk over any app with the power button",
        ),
        "Listens only when you ask it to",
        "Allow microphone",
        "Voice messages and “Hey Luna”",
        "Hey Luna, set a timer for 10 minutes", "Timer · 10 min", "Stop",
    ),
    CONTACTS(
        "Contacts", Icons.Outlined.Contacts, Glass.Ink[2],
        "Call and text people by name",
        "So “call Amma” or “text Priya I'm running late” knows who you mean. Your contacts' photos also put names to the faces in your photos.",
        listOf(
            Icons.Outlined.Call to "Calls, SMS, WhatsApp and email by name",
            Icons.Outlined.Face to "People in your photos named from contacts",
            Icons.AutoMirrored.Outlined.Chat to "Who a message is from, by name",
        ),
        "Read on this phone, never uploaded",
        "Allow contacts",
        "Calls and messages by name",
        "Call Amma", "Amma · mobile", "Calling",
    ),
    PHOTOS(
        "Photos and videos", Icons.Outlined.PhotoLibrary, Glass.Ink[3],
        "Find any photo by asking",
        "Ask for a photo by what's in it, like “my passport” or “the beach last December”, or by who's in it. Each photo is looked at once, on this phone, to make that search.",
        listOf(
            Icons.Outlined.Search to "By what's in it, where and when it was taken",
            Icons.Outlined.Description to "Ask what a ticket or a bill says",
            Icons.Outlined.VisibilityOff to "ID cards stay hidden until you unlock",
        ),
        "Your photos never leave the phone",
        "Allow photos",
        "Photo search, and people in photos",
        "Find my passport photo", "3 photos", "Open",
    ),
    LOCATION(
        "Location", Icons.Outlined.Place, Glass.Ink[4],
        "Reminders at the right place",
        "“Remind me to buy milk near DMart” rings when you get there. Your location is worked out on the phone; to find a place you name, only its name and your rough area are looked up on OpenStreetMap.",
        listOf(
            Icons.Outlined.Place to "Reminders when you arrive somewhere",
            Icons.Outlined.Alarm to "An alarm as you reach your stop",
            Icons.Outlined.Map to "The nearest branch of a shop",
        ),
        "Your location stays on the phone",
        "Allow location",
        "Reminders and alarms at a place",
        "Buy milk when I'm near DMart", "Near DMart · 500 m", "Edit",
    ),
    FILES(
        "Files", Icons.Outlined.Folder, Glass.Ink[0],
        "Find a file by its name",
        "Ask for “my resume PDF” or “the rent agreement”, and have it read when you ask: a summary, or one detail in it. Only names are searched; a file is opened only when you ask about it.",
        listOf(
            Icons.Outlined.Search to "Files found by name",
            Icons.Outlined.Description to "PDFs and documents read when you ask",
            Icons.Outlined.Block to "Nothing is changed or deleted",
        ),
        "Files are read on this phone only",
        "Allow access to files",
        "Files found by name and read",
        "Read my rent agreement", "Rent agreement.pdf", "Open",
    ),
    ASSISTANT(
        "Digital assistant", Icons.Outlined.PowerSettingsNew, Glass.Ink[1],
        "Open it from anywhere",
        "Make Local Assistant your phone's digital assistant to open it by holding the power button over any app. It can then look at the screen you're on when you ask, and your SMS can be searched too.",
        listOf(
            Icons.Outlined.PowerSettingsNew to "Hold the power button over any app",
            Icons.Outlined.ScreenshotMonitor to "“Summarise this”, about your screen",
            Icons.Outlined.Sms to "Your SMS in message search",
        ),
        "On the lock screen it says nothing about you",
        "Make it your assistant",
        "The power button, and your screen",
        "Summarise this page", "Hold power", "Ask",
    ),
    MESSAGES(
        "Messages", Icons.AutoMirrored.Outlined.Chat, Glass.Ink[2],
        "Ask what someone sent you",
        "“What did Rahul say about the trip?” Messages that arrive in your notifications are kept on this phone, as long as your chats are. Ads and one-time codes are left out.",
        listOf(
            Icons.AutoMirrored.Outlined.Chat to "WhatsApp, SMS and other chat apps",
            Icons.Outlined.Block to "No ads or one-time codes",
            Icons.Outlined.History to "Kept as long as your chats",
        ),
        "Kept on this phone only",
        "Allow notification access",
        "Search the messages you get",
        "What did Rahul say about the trip?", "WhatsApp · Rahul", "Show",
    ),
    BATTERY(
        "Battery", Icons.Outlined.BatteryChargingFull, Glass.Ink[3],
        "Keep it ready in the background",
        "Phones close apps they think are idle. Letting Local Assistant run in the background keeps reminders on time, lets photo indexing finish, and makes answers start sooner.",
        listOf(
            Icons.Outlined.Schedule to "Reminders that ring on time",
            Icons.Outlined.PhotoLibrary to "Indexing that finishes",
            Icons.Outlined.Bolt to "Answers that start sooner",
        ),
        "Works only when there's something to do",
        "Open battery settings",
        "Runs in the background",
        "Remind me at 7 tomorrow", "Rang at 7:00 AM", "Done",
    );

    /**
     * Whether this Android has it to ask. Notifications are given at install before 13, and
     * exact alarms need no asking before 12, so before 12 there's nothing on that page.
     */
    fun applies(): Boolean = this != NOTIFICATIONS || Build.VERSION.SDK_INT >= 31

    fun given(context: Context, library: PhotoLibrary): Boolean = when (this) {
        NOTIFICATIONS -> Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS)
        MICROPHONE -> granted(context, Manifest.permission.RECORD_AUDIO)
        CONTACTS -> granted(context, Manifest.permission.READ_CONTACTS)
        PHOTOS -> library.access() != PhotoLibrary.Access.NONE
        LOCATION -> granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        FILES -> FileAccess.granted(context)
        ASSISTANT -> AssistantRole.isDefault(context)
        MESSAGES -> MessageListener.allowed(context)
        BATTERY -> context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    /** Given, but with one more thing worth offering on its page. */
    fun morePending(context: Context): Boolean = when (this) {
        NOTIFICATIONS -> Build.VERSION.SDK_INT >= 31 && context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == false
        LOCATION -> Build.VERSION.SDK_INT >= 29 && !granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        MESSAGES -> AssistantRole.isDefault(context) && !granted(context, Manifest.permission.READ_SMS)
        else -> false
    }
}

/** Whether there's more to offer on [ask]'s page now: SMS only matters while messages are kept. */
private fun offersMore(more: Map<Ask, Boolean>, ask: Ask, keepMessages: Boolean): Boolean =
    more.getValue(ask) && (ask != Ask.MESSAGES || keepMessages)

private fun granted(context: Context, permission: String): Boolean =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

/** What the preview shows as having more to offer once given. */
private val PREVIEW_MORE = setOf(Ask.NOTIFICATIONS, Ask.LOCATION, Ask.MESSAGES)

/** Long enough to see the tick before the page turns. */
private const val ADVANCE_MS = 700L

private val PICTURE_HEIGHT = 160.dp
