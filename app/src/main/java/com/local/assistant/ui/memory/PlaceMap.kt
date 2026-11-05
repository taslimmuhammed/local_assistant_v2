package com.local.assistant.ui.memory

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.local.assistant.location.GeoPoint
import com.local.assistant.location.circle
import com.local.assistant.location.movedTo
import com.local.assistant.location.onlyAt
import com.local.assistant.location.resized
import com.local.assistant.location.ringMetres
import com.local.assistant.location.zoomToShow
import com.local.assistant.memory.db.PlaceDistance
import com.local.assistant.memory.db.TaskPlace
import com.local.assistant.ui.theme.AppColors
import com.local.assistant.ui.theme.Glass
import com.local.assistant.ui.theme.GlassIconButton
import com.local.assistant.ui.theme.glass
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.hypot
import kotlin.math.roundToInt

// A place reminder on a map: a small picture of where it rings in its editor, and the whole map
// to move a pin and size its circle, the way a pin is dropped in a maps app. The user asked for
// both (5 Nov 2026).
//
// The map is OpenStreetMap's, drawn by MapLibre from OpenFreeMap's free tiles: no key and no
// account. Showing it asks the tile server for the area round the reminder's place, as the map
// search did when the reminder was made; nothing else is sent.

/**
 * Where [place] rings, as a picture: its circles, and a pin on the first of its spots. A tap calls
 * [onOpen], which [action] names: the map to move the pin, or in a chat reply the whole reminder.
 * A picture, not a live map: a map view in a dialog draws over the dialog's rounded corners, and
 * nothing here needs to move.
 */
@Composable
internal fun PlacePreview(
    place: TaskPlace,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    action: String = "Move pin",
    actionIcon: ImageVector = Icons.Outlined.OpenWith,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MAP_PAPER)
            .clickable(onClickLabel = action, onClick = onOpen),
    ) {
        val widthDp = maxWidth.value.roundToInt()
        val heightDp = maxHeight.value.roundToInt()
        val key = "$place $widthDp×$heightDp"
        var picture by remember { mutableStateOf(pictures[key]?.asImageBitmap()) }
        var failed by remember { mutableStateOf(false) }
        LaunchedEffect(key) {
            pictures[key]?.let {
                picture = it.asImageBitmap()
                return@LaunchedEffect
            }
            val shot = try {
                snapshot(context, place, widthDp, heightDp, density)
            } catch (e: IOException) {
                null
            }
            failed = shot == null
            if (shot != null) {
                pictures.put(key, shot)
                picture = shot.asImageBitmap()
            }
        }
        picture?.let { Image(it, contentDescription = "Map of where it rings", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (failed && picture == null) {
            Text(
                "The map needs the internet.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
            )
        }
        Pin(Modifier.align(Alignment.Center))
        Credit(Modifier.align(Alignment.BottomStart), short = true)
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .glass(RoundedCornerShape(50), Glass.Raised)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(actionIcon, contentDescription = null, tint = AppColors.TextPrimary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(action, style = MaterialTheme.typography.labelMedium, color = AppColors.TextPrimary)
        }
    }
}

/**
 * The map, full screen, to put a reminder's pin where it should ring: the pin stays in the middle
 * and the map moves under it, as when dropping a pin in a maps app. With several pins (a chain's
 * nearest branches), a tap on another's dot picks that one, and "Ring only here" drops the rest.
 * The slider sizes the circles; sizes that come back to [original]'s keep its wording. [onDone]
 * gets the place as it now is: nothing is saved until the editor's Save.
 */
@Composable
internal fun PlaceMapEditor(original: TaskPlace, start: TaskPlace, onDone: (TaskPlace) -> Unit, onCancel: () -> Unit) {
    var place by remember { mutableStateOf(start) }
    var selected by remember { mutableIntStateOf(0) }
    var refit by remember { mutableIntStateOf(0) }
    val before = start.distance?.beforeArriving == true
    val now = place.ringMetres()
    // Fixed while the map is open, with the size it started at among them: stops that came and
    // went with the slider would move under the thumb.
    val steps = remember { ((if (before) BEFORE_STEPS else CIRCLE_STEPS) + listOfNotNull(start.ringMetres())).distinct().sorted() }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Glass.Sheet)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LiveMap(
                    place = place,
                    selected = selected,
                    refit = refit,
                    onMoved = { place = place.movedTo(selected, it) },
                    onPicked = { selected = it },
                    modifier = Modifier.fillMaxSize(),
                )
                Pin(Modifier.align(Alignment.Center))
                Credit(Modifier.align(Alignment.BottomStart))
                GlassIconButton(
                    Icons.Outlined.Close,
                    contentDescription = "Close the map",
                    onClick = onCancel,
                    modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp),
                )
            }
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    if (place.spots.size > 1) {
                        "Drag the map to put the pin where it should ring. Tap another dot to move that one, or keep this one alone."
                    } else {
                        "Drag the map to put the pin where it should ring."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                )
                Text(
                    when {
                        now == null -> "Rings within each place's own size"
                        before -> "Rings ${metres(now)} before arriving"
                        else -> "Rings within ${metres(now)}"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.TextPrimary,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Slider(
                    value = (now?.let { steps.indexOf(it) } ?: 0).toFloat(),
                    onValueChange = { place = place.resized(PlaceDistance(steps[it.roundToInt()], before), original) },
                    onValueChangeFinished = { refit++ },
                    valueRange = 0f..(steps.size - 1).toFloat(),
                    steps = (steps.size - 2).coerceAtLeast(0),
                    colors = SliderDefaults.colors(
                        thumbColor = AppColors.Accent,
                        activeTrackColor = AppColors.Accent,
                        inactiveTrackColor = AppColors.Border,
                        activeTickColor = Color.Transparent,
                        inactiveTickColor = Color.Transparent,
                    ),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (place.spots.size > 1) {
                        TextButton(
                            onClick = {
                                place = place.onlyAt(selected)
                                selected = 0
                                refit++
                            },
                        ) { Text("Ring only here") }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onDone(place) },
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = AppColors.OnAccent),
                    ) { Text("Done") }
                }
            }
        }
    }
}

/**
 * The live map under the pin. Only a drag by hand moves the pin: the map gliding to another
 * branch, or to fit a resized circle, doesn't. Zooming is about the middle, so it doesn't either.
 */
@Composable
private fun LiveMap(
    place: TaskPlace,
    selected: Int,
    /** Changes when the circle has been resized: the map zooms to fit it. */
    refit: Int,
    onMoved: (GeoPoint) -> Unit,
    onPicked: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val mapView = rememberMapView()
    val density = LocalDensity.current.density
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var failed by remember { mutableStateOf(false) }
    val latestPlace by rememberUpdatedState(place)
    val latestSelected by rememberUpdatedState(selected)
    val moved by rememberUpdatedState(onMoved)
    val picked by rememberUpdatedState(onPicked)

    BoxWithConstraints(modifier) {
        val sizeDp = minOf(maxWidth.value, maxHeight.value)
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        if (failed) {
            Text(
                "The map needs the internet.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 72.dp),
            )
        }

        LaunchedEffect(mapView) {
            mapView.addOnDidFailLoadingMapListener { failed = true }
            val ready = mapView.awaitMap()
            ready.uiSettings.apply {
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                isCompassEnabled = false
                isLogoEnabled = false
                isAttributionEnabled = false
            }
            mapView.doOnLayout { ready.uiSettings.focalPoint = PointF(it.width / 2f, it.height / 2f) }
            var byHand = false
            ready.addOnCameraMoveStartedListener { reason ->
                byHand = reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
            }
            ready.addOnCameraMoveListener {
                val target = ready.cameraPosition.target
                if (byHand && target != null) moved(GeoPoint(target.latitude, target.longitude))
            }
            ready.addOnMapClickListener { at ->
                val tap = ready.projection.toScreenLocation(at)
                val spots = latestPlace.spots
                val nearest = spots.indices.minByOrNull { i ->
                    val dot = ready.projection.toScreenLocation(LatLng(spots[i].lat, spots[i].lon))
                    hypot(dot.x - tap.x, dot.y - tap.y)
                }
                val dot = nearest?.let { ready.projection.toScreenLocation(LatLng(spots[it].lat, spots[it].lon)) }
                if (nearest != null && nearest != latestSelected && dot != null && hypot(dot.x - tap.x, dot.y - tap.y) <= TAP_SLOP_DP * density) {
                    picked(nearest)
                    true
                } else {
                    false
                }
            }
            ready.moveCamera(CameraUpdateFactory.newCameraPosition(cameraOn(latestPlace, latestSelected, sizeDp)))
            map = ready
            style = ready.awaitStyle(styled(latestPlace, latestSelected))
        }
        LaunchedEffect(place, selected, style) {
            val drawn = style ?: return@LaunchedEffect
            drawn.getSourceAs<GeoJsonSource>(RINGS)?.setGeoJson(rings(place))
            drawn.getSourceAs<GeoJsonSource>(DOTS)?.setGeoJson(dots(place, selected))
        }
        // Another branch picked, or the circle resized: the map goes to it, the pin with it.
        LaunchedEffect(selected, refit, map) {
            map?.animateCamera(CameraUpdateFactory.newCameraPosition(cameraOn(latestPlace, selected, sizeDp)))
        }
    }
}

/** A map view that starts, stops and is let go of with the screen it's on. */
@Composable
private fun rememberMapView(): MapView {
    val context = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).apply { onCreate(null) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        var at = Lifecycle.State.CREATED
        fun goTo(state: Lifecycle.State) {
            if (at < Lifecycle.State.STARTED && state >= Lifecycle.State.STARTED) mapView.onStart()
            if (at < Lifecycle.State.RESUMED && state >= Lifecycle.State.RESUMED) mapView.onResume()
            if (at >= Lifecycle.State.RESUMED && state < Lifecycle.State.RESUMED) mapView.onPause()
            if (at >= Lifecycle.State.STARTED && state < Lifecycle.State.STARTED) mapView.onStop()
            at = state
        }
        val observer = LifecycleEventObserver { _, event -> goTo(event.targetState) }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            goTo(Lifecycle.State.CREATED)
            mapView.onDestroy()
        }
    }
    return mapView
}

/** A red pin whose point is at the middle of what it's put on. */
@Composable
private fun Pin(modifier: Modifier = Modifier) {
    Icon(
        Icons.Filled.Place,
        contentDescription = "Pin",
        tint = PIN,
        // The icon's tip is 22/24 of the way down: 17 dp below its middle at this size.
        modifier = modifier.size(40.dp).offset(y = (-17).dp),
    )
}

/**
 * What OpenFreeMap asks to be shown with its maps. The small map shows the part it must: the
 * OpenFreeMap name is asked for, not required.
 */
@Composable
private fun Credit(modifier: Modifier = Modifier, short: Boolean = false) {
    Text(
        if (short) "© OpenMapTiles © OpenStreetMap" else "OpenFreeMap © OpenMapTiles Data from OpenStreetMap",
        style = MaterialTheme.typography.labelSmall,
        color = AppColors.TextSecondary,
        modifier = modifier
            .padding(6.dp)
            .background(Color.White.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/** The map centred on spot [selected], zoomed so its circle fits a box [sizeDp] across. */
private fun cameraOn(place: TaskPlace, selected: Int, sizeDp: Float): CameraPosition {
    val spot = place.spots[selected.coerceIn(place.spots.indices)]
    val at = GeoPoint(spot.lat, spot.lon)
    return CameraPosition.Builder().target(LatLng(spot.lat, spot.lon)).zoom(at.zoomToShow(spot.radiusM.toDouble(), sizeDp)).build()
}

/** The picture in the editor: drawn off screen, once per change. */
private suspend fun snapshot(context: Context, place: TaskPlace, widthDp: Int, heightDp: Int, density: Float): Bitmap =
    suspendCancellableCoroutine { done ->
        MapLibre.getInstance(context.applicationContext)
        val options = MapSnapshotter.Options(widthDp, heightDp)
            .withPixelRatio(density)
            .withStyleBuilder(styled(place, selected = 0))
            .withCameraPosition(cameraOn(place, 0, minOf(widthDp, heightDp).toFloat()))
            .withLogo(false)
            .withAttribution(false)
        val snapshotter = MapSnapshotter(context, options)
        snapshotter.start(
            object : MapSnapshotter.SnapshotReadyCallback {
                override fun onSnapshotReady(snapshot: MapSnapshot) = done.resume(snapshot.bitmap)
            },
            object : MapSnapshotter.ErrorHandler {
                override fun onError(error: String) = done.resumeWithException(IOException(error))
            },
        )
        done.invokeOnCancellation { snapshotter.cancel() }
    }

private suspend fun MapView.awaitMap(): MapLibreMap = suspendCancellableCoroutine { done -> getMapAsync { done.resume(it) } }

private suspend fun MapLibreMap.awaitStyle(builder: Style.Builder): Style = suspendCancellableCoroutine { done -> setStyle(builder) { done.resume(it) } }

/** OpenFreeMap's map, with [place]'s circles on it and a dot on each spot but [selected], which has the pin. */
private fun styled(place: TaskPlace, selected: Int): Style.Builder =
    Style.Builder().fromUri(STYLE)
        .withSource(GeoJsonSource(RINGS, rings(place)))
        .withSource(GeoJsonSource(DOTS, dots(place, selected)))
        .withLayer(FillLayer("$RINGS-fill", RINGS).withProperties(fillColor(RING_HEX), fillOpacity(0.16f)))
        .withLayer(LineLayer("$RINGS-edge", RINGS).withProperties(lineColor(RING_HEX), lineWidth(2f)))
        .withLayer(
            CircleLayer(DOTS, DOTS).withProperties(circleRadius(7f), circleColor(PIN_HEX), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f)),
        )

private fun rings(place: TaskPlace): FeatureCollection = FeatureCollection.fromFeatures(
    place.spots.map { spot ->
        val edge = GeoPoint(spot.lat, spot.lon).circle(spot.radiusM.toDouble()).map { Point.fromLngLat(it.lon, it.lat) }
        Feature.fromGeometry(Polygon.fromLngLats(listOf(edge)))
    },
)

private fun dots(place: TaskPlace, selected: Int): FeatureCollection = FeatureCollection.fromFeatures(
    place.spots.filterIndexed { i, _ -> i != selected }.map { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) },
)

/**
 * Pictures already drawn, by place and size: a chat scrolled back past a reminder's map shows it
 * again at once, without drawing it anew. One across the chat is about 2.4 MB on a 1272 px wide
 * phone, so 12 MB keeps the last few.
 */
private val pictures = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

private const val STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val RINGS = "reminder-rings"
private const val DOTS = "reminder-dots"
private const val RING_HEX = "#1A73E8"
private const val PIN_HEX = "#E53935"
private val PIN = Color(0xFFE53935)

/** The map's own paper colour, behind it while it's drawn. */
private val MAP_PAPER = Color(0xFFF8F4F0)

/** How near a tap has to be to a dot to pick it. */
private const val TAP_SLOP_DP = 32

/** The slider's stops: from a shop's door to a town's edge, and for "before arriving" up to 50 km out. */
private val CIRCLE_STEPS = listOf(100, 150, 200, 250, 300, 400, 500, 750, 1_000, 1_500, 2_000, 3_000, 5_000, 7_500, 10_000)
private val BEFORE_STEPS = listOf(500, 1_000, 2_000, 3_000, 5_000, 7_500, 10_000, 15_000, 20_000, 30_000, 50_000)
