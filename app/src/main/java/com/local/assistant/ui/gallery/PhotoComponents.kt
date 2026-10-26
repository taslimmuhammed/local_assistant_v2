package com.local.assistant.ui.gallery

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.local.assistant.gallery.PhotoThumbnails
import com.local.assistant.memory.tools.PhotoSet
import com.local.assistant.ui.theme.AppColors
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What a screen needs to show the phone's photos: where they come from, and whether they must
 * stay hidden for now (the assistant panel over the lock screen) with a way to unlock.
 */
class PhotoAccess(
    val thumbnails: PhotoThumbnails,
    val hidden: () -> Boolean = { false },
    val onUnlock: () -> Unit = {},
)

/** Provided at each activity's root; null where photos aren't available. */
val LocalPhotoAccess = staticCompositionLocalOf<PhotoAccess?> { null }

/** One photo, cropped to fill its box; a grey box while it loads, or if it's gone. A video shows its length. */
@Composable
fun PhotoThumb(mediaId: Long, thumbnails: PhotoThumbnails, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val bitmap by produceState<ImageBitmap?>(null, mediaId) { value = thumbnails.thumbnail(mediaId) }
    val length by produceState<Long?>(null, mediaId) { value = thumbnails.videoLength(mediaId) }
    Box(
        modifier
            .background(AppColors.SurfaceMuted)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        length?.let { VideoBadge(it, Modifier.align(Alignment.BottomEnd).padding(4.dp)) }
    }
}

/** "▶ 1:23" in the corner of a video's thumbnail. */
@Composable
private fun VideoBadge(lengthMs: Long, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(start = 2.dp, end = 5.dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = "Video", tint = Color.White, modifier = Modifier.size(14.dp))
        Text(clock(lengthMs), style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

/** 83,000 ms as "1:23"; an hour or more as "1:02:03". */
internal fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** "30 photos", "1 video", "28 photos and 2 videos". */
internal fun itemCount(total: Int, videos: Int): String {
    val numbers = NumberFormat.getIntegerInstance()
    val photos = total - videos
    fun n(count: Int, one: String, many: String) = "${numbers.format(count)} ${if (count == 1) one else many}"
    return when {
        videos == 0 -> n(photos, "photo", "photos")
        photos == 0 -> n(videos, "video", "videos")
        else -> "${n(photos, "photo", "photos")} and ${n(videos, "video", "videos")}"
    }
}

/**
 * Full screen, over everything (a sheet included), swiping through [ids]. Back closes it. A video
 * plays where it's shown, from its moment in [moments] (the part that matched a search) if any.
 */
@Composable
fun PhotoViewer(ids: List<Long>, start: Int, thumbnails: PhotoThumbnails, onClose: () -> Unit, moments: Map<Long, Long> = emptyMap()) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val pager = rememberPagerState(initialPage = start.coerceIn(0, (ids.size - 1).coerceAtLeast(0))) { ids.size }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val maxSide = with(LocalDensity.current) { maxOf(maxWidth, maxHeight).roundToPx() }
                HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { ids[it] }) { page ->
                    val id = ids[page]
                    val bitmap by produceState<ImageBitmap?>(null, id) { value = thumbnails.full(id, maxSide) }
                    val length by produceState<Long?>(LOADING, id) { value = thumbnails.videoLength(id) }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        when (val ms = length) {
                            LOADING -> CircularProgressIndicator(color = Color.White)
                            null -> bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                                ?: CircularProgressIndicator(color = Color.White)
                            else -> VideoPage(thumbnails.uri(id), ms, moments[id] ?: 0L, poster = bitmap, current = pager.settledPage == page)
                        }
                    }
                }
            }
            // A fade behind the controls, so white icons stay readable over a bright photo.
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.White) }
                Text(
                    "${pager.currentPage + 1} of ${ids.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    val id = ids[pager.currentPage]
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW).setDataAndType(thumbnails.uri(id), thumbnails.mimeType(id)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        )
                    }
                }) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Open in another app", tint = Color.White) }
            }
        }
    }
}

/**
 * A video in the viewer: it plays while its page is the one showing, from [startMs]. Until it
 * starts, its still ([poster]) stands in, since a video view is black before its first frame.
 * Tapping pauses and resumes; the bar below shows where it is and seeks.
 */
@Composable
private fun VideoPage(uri: Uri, lengthMs: Long, startMs: Long, poster: ImageBitmap?, current: Boolean) {
    var view by remember { mutableStateOf<VideoView?>(null) }
    var prepared by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(startMs) }

    LaunchedEffect(current, prepared) {
        val video = view ?: return@LaunchedEffect
        if (!prepared) return@LaunchedEffect
        if (current) {
            video.start()
            playing = true
            started = true
        } else {
            video.pause()
            playing = false
        }
    }
    LaunchedEffect(playing) {
        while (playing) {
            view?.let { position = it.currentPosition.toLong() }
            delay(200)
        }
    }
    DisposableEffect(uri) { onDispose { view?.stopPlayback() } }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Offered the whole screen, a video view keeps the video's shape inside it, but takes its
        // top-left corner: the frame around it centres it. Offered less, it stayed at the video's
        // own pixel size, a small box mid-screen.
        AndroidView(
            factory = { context ->
                FrameLayout(context).apply {
                    val video = VideoView(context).apply {
                        setOnPreparedListener { player ->
                            // To the moment itself, not the keyframe before it (five seconds early).
                            player.seekTo(startMs, MediaPlayer.SEEK_CLOSEST)
                            prepared = true
                        }
                        setOnCompletionListener { playing = false }
                        setVideoURI(uri)
                    }
                    addView(video, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
                    view = video
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (!started) {
            poster?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
        // Taps anywhere but the bar pause and resume.
        Box(
            Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) {
                val video = view ?: return@clickable
                if (!prepared) return@clickable
                if (playing) video.pause() else video.start()
                playing = !playing
                started = true
            },
        )
        if (!playing && prepared) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Play",
                tint = Color.White,
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(36.dp)).background(Color.Black.copy(alpha = 0.4f)).padding(12.dp),
            )
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (startMs > 0) {
                Text("Starts at ${clock(startMs)}, the part that matched", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(clock(position), style = MaterialTheme.typography.labelMedium, color = Color.White)
                Slider(
                    value = position.coerceIn(0, lengthMs).toFloat(),
                    onValueChange = { value ->
                        position = value.toLong()
                        view?.seekTo(value.toInt())
                        started = true
                    },
                    valueRange = 0f..lengthMs.toFloat().coerceAtLeast(1f),
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                Text(clock(lengthMs), style = MaterialTheme.typography.labelMedium, color = Color.White)
            }
        }
    }
}

/**
 * Photos find_photos found, under the reply that asked for them, best match first.
 *
 * One or two show right there as small photos. More than that would crowd the chat, so they
 * become a card, a little fanned stack with the count, that opens them all in a sheet.
 */
@Composable
fun PhotoResults(set: PhotoSet, modifier: Modifier = Modifier) {
    val access = LocalPhotoAccess.current ?: return
    // Chips written by the build that split results in two (25 Oct 2026) have no ids; skip them.
    @Suppress("SENSELESS_COMPARISON")
    if (set.ids == null || set.ids.isEmpty()) return
    var sheetOpen by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Pair<List<Long>, Int>?>(null) }

    if (access.hidden()) {
        LockedPhotos(set, access.onUnlock, modifier)
        return
    }
    if (set.ids.size <= INLINE_MAX) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            set.ids.forEachIndexed { index, id ->
                PhotoThumb(
                    id,
                    access.thumbnails,
                    Modifier.size(INLINE_SIZE).clip(RoundedCornerShape(14.dp)),
                    onClick = { viewing = set.ids to index },
                )
            }
        }
    } else {
        StackCard(set, access.thumbnails, onOpen = { sheetOpen = true }, modifier = modifier)
    }

    if (sheetOpen) {
        PhotoSheet(set, access.thumbnails, onDismiss = { sheetOpen = false }, onOpenPhoto = { ids, index -> viewing = ids to index })
    }
    viewing?.let { (ids, start) -> PhotoViewer(ids, start, access.thumbnails, onClose = { viewing = null }, moments = set.moments.orEmpty()) }
}

/** "30 photos · Sunset · View all", beside three photos fanned like prints on a table. */
@Composable
private fun StackCard(set: PhotoSet, thumbnails: PhotoThumbnails, onOpen: () -> Unit, modifier: Modifier) {
    val videos by produceState(0, set) { value = thumbnails.videoCount(set.ids) }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(AppColors.SurfaceMuted)
            .clickable(onClick = onOpen)
            .padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FannedStack(set.ids.take(3), thumbnails)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(
                itemCount(set.ids.size, videos),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary,
            )
            Text(
                set.query.replaceFirstChar { it.titlecase(Locale.getDefault()) },
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("View all", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = AppColors.TextPrimary, modifier = Modifier.padding(start = 4.dp).size(16.dp))
            }
        }
    }
}

/** Up to three photos, the first on top, the others tilted out behind it. */
@Composable
private fun FannedStack(ids: List<Long>, thumbnails: PhotoThumbnails) {
    val tilts = listOf(0f, -12f, 11f)
    val shifts = listOf(0.dp to 4.dp, (-18).dp to (-2).dp, 18.dp to 0.dp)
    Box(Modifier.size(width = 112.dp, height = 100.dp), contentAlignment = Alignment.Center) {
        // Drawn back to front, so the best match lies on top.
        ids.indices.reversed().forEach { i ->
            PhotoThumb(
                ids[i],
                thumbnails,
                Modifier
                    .offset(shifts[i].first, shifts[i].second)
                    .rotate(tilts[i])
                    .shadow(4.dp, RoundedCornerShape(10.dp))
                    .size(72.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(BorderStroke(2.dp, Color.White), RoundedCornerShape(10.dp)),
            )
        }
    }
}

/** How the sheet orders the photos. */
private enum class PhotoSort(val label: String, val subtitle: String) {
    BEST("Best match", "best match first"),
    NEWEST("Newest", "newest first"),
    OLDEST("Oldest", "oldest first"),
}

/** One cell of the sheet's grid: a month heading (date orders only) or a photo. */
private sealed interface SheetCell {
    data class Month(val label: String, val count: Int) : SheetCell
    data class Photo(val id: Long, val index: Int) : SheetCell
}

/**
 * All of them, best match first, or by date with a heading for each month. The viewer pages
 * through them in the order shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoSheet(set: PhotoSet, thumbnails: PhotoThumbnails, onDismiss: () -> Unit, onOpenPhoto: (List<Long>, Int) -> Unit) {
    // Straight to full height: a gallery wants the room, and a half-open sheet only adds a step.
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val numbers = NumberFormat.getIntegerInstance()
    val videos by produceState(0, set) { value = thumbnails.videoCount(set.ids) }
    // A height of its own, from the window. As a share of what the sheet offered it, it chased
    // the sheet: a scroll past the end moved the sheet, which changed what it offered, which
    // resized the content, which moved the sheet again, and the sheet bobbed up and down.
    val height = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height * SHEET_SHARE).toInt().toDp() }
    var sort by rememberSaveable { mutableStateOf(PhotoSort.BEST) }
    // Read once per sheet; until they arrive, a date order shows the best-match order.
    val dates by produceState<Map<Long, Long>?>(null, set) { value = thumbnails.datesTaken(set.ids) }
    val ordered = remember(set, sort, dates) { order(set.ids, sort, dates) }
    val cells = remember(ordered, sort, dates) { cells(ordered, sort, dates) }
    val grid = rememberLazyGridState()
    LaunchedEffect(sort) { grid.scrollToItem(0) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = AppColors.Background,
        // Closed with ✕, back or a tap outside, never by a drag. A drag the grid didn't use went
        // to the sheet: past the last row it chased its own height and bobbed, and at the top
        // every pull or fling down moved it a little and it sprang back, over and over.
        sheetGesturesEnabled = false,
        dragHandle = null,
    ) {
        Column(Modifier.height(height)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, tint = AppColors.TextPrimary)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(set.query.replaceFirstChar { it.titlecase(Locale.getDefault()) }, style = MaterialTheme.typography.titleMedium, color = AppColors.TextPrimary)
                    Text("${itemCount(set.ids.size, videos)}, ${sort.subtitle}", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "Close") }
            }
            Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (option in PhotoSort.entries) {
                    // In the switches' colours, like the pace chips in Settings: the choice in black.
                    FilterChip(
                        selected = option == sort,
                        onClick = { sort = option },
                        label = { Text(option.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = AppColors.Background,
                            labelColor = AppColors.TextPrimary,
                            selectedContainerColor = AppColors.Accent,
                            selectedLabelColor = AppColors.OnAccent,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = option == sort,
                            borderColor = AppColors.TextSecondary,
                            selectedBorderColor = AppColors.Accent,
                        ),
                    )
                }
            }
            LazyVerticalGrid(
                state = grid,
                columns = GridCells.Adaptive(minSize = 104.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(
                    cells,
                    key = { cell -> if (cell is SheetCell.Month) "month-${cell.label}" else (cell as SheetCell.Photo).id },
                    span = { cell -> GridItemSpan(if (cell is SheetCell.Month) maxLineSpan else 1) },
                ) { cell ->
                    when (cell) {
                        is SheetCell.Month -> Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            Text(cell.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                            Text("  ${numbers.format(cell.count)}", style = MaterialTheme.typography.labelMedium, color = AppColors.TextSecondary)
                        }
                        is SheetCell.Photo -> PhotoThumb(cell.id, thumbnails, Modifier.aspectRatio(1f), onClick = { onOpenPhoto(ordered, cell.index) })
                    }
                }
            }
        }
    }
}

/** [ids] (best match first) in [sort]'s order. Photos with no known date go last, best first. */
private fun order(ids: List<Long>, sort: PhotoSort, dates: Map<Long, Long>?): List<Long> {
    if (sort == PhotoSort.BEST || dates == null) return ids
    val dated = ids.filter { it in dates }
    val undated = ids.filter { it !in dates }
    val byDate = if (sort == PhotoSort.NEWEST) dated.sortedByDescending { dates.getValue(it) } else dated.sortedBy { dates.getValue(it) }
    return byDate + undated
}

/** The grid's cells: just the photos for best match; a heading before each month for dates. */
private fun cells(ordered: List<Long>, sort: PhotoSort, dates: Map<Long, Long>?): List<SheetCell> {
    if (sort == PhotoSort.BEST || dates == null) return ordered.mapIndexed { i, id -> SheetCell.Photo(id, i) }
    val zone = ZoneId.systemDefault()
    fun monthOf(id: Long): String = dates[id]?.let { MONTH.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: "Date unknown"
    val counts = ordered.groupingBy(::monthOf).eachCount()
    val cells = ArrayList<SheetCell>(ordered.size + counts.size)
    var current: String? = null
    ordered.forEachIndexed { i, id ->
        val month = monthOf(id)
        if (month != current) {
            cells += SheetCell.Month(month, counts.getValue(month))
            current = month
        }
        cells += SheetCell.Photo(id, i)
    }
    return cells
}

private val MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())

/** On the lock screen: say photos were found, but show none of them until the phone is unlocked. */
@Composable
private fun LockedPhotos(set: PhotoSet, onUnlock: () -> Unit, modifier: Modifier) {
    val count = set.ids.size
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(AppColors.SurfaceMuted)
            .clickable(onClick = onUnlock)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Lock, contentDescription = null, tint = AppColors.TextPrimary)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("${NumberFormat.getIntegerInstance().format(count)} found", style = MaterialTheme.typography.titleSmall, color = AppColors.TextPrimary)
            Text("Unlock your phone to see them.", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        }
    }
}

private const val SHEET_SHARE = 0.88f

/** A page whose kind (photo or video) isn't known yet. */
private const val LOADING = -1L

private const val INLINE_MAX = 2
private val INLINE_SIZE: Dp = 132.dp
