package io.github.tewnut.poicapture.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import kotlin.math.roundToInt
import androidx.compose.material.icons.filled.Place
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.tewnut.poicapture.R
import io.github.tewnut.poicapture.core.CapturePlan
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import io.github.tewnut.poicapture.Prefs
import io.github.tewnut.poicapture.capture.CaptureService
import io.github.tewnut.poicapture.core.Geo
import io.github.tewnut.poicapture.core.LatLon
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** XYZ tile URL template ({z}/{x}/{y}), e.g. OpenStreetMap or any other XYZ tile server. */
internal class TemplateTileSource(private val template: String) :
    OnlineTileSourceBase("tiles-${template.hashCode()}", 0, 19, 256, "", arrayOf(template), "© OpenStreetMap contributors") {
    override fun getTileURLString(index: Long): String = template
        .replace("{z}", MapTileIndex.getZoom(index).toString())
        .replace("{x}", MapTileIndex.getX(index).toString())
        .replace("{y}", MapTileIndex.getY(index).toString())
}

/**
 * A photo on the map: where it was taken (the camera, GPS), and where the place it shows is: put there by the
 * contributor ([placed]), else the app's estimate (camera + facade distance along the camera direction).
 */
data class PhotoPin(val id: String, val gps: LatLon, val legacyFix: LatLon?, val placed: LatLon?, val tMs: Long, val roadName: String?,
                    val accuracyM: Int?, val cameraBearing: Double?, val facadeDistanceM: Double?, val hFovDeg: Double?) {
    /** where the photo was taken (sessions before 0.12 could correct it by hand: [legacyFix]) */
    val camera: LatLon get() = legacyFix ?: gps
    val at: LatLon get() = camera
    /** camera + expected distance along the camera direction (Place capture on foot: a few metres in front) */
    val estimate: LatLon? get() = cameraBearing?.let { Geo.destination(camera, it, facadeDistanceM ?: ON_FOOT_M) }
    /** where the place is: by hand, else the estimate, else (no direction known) the camera itself */
    val place: LatLon get() = placed ?: estimate ?: camera
    val edited: Boolean get() = placed != null || legacyFix != null
    companion object {
        const val ON_FOOT_M = 5.0
        private fun JSONObject.ll(a: String, b: String) = if (has(a) && !isNull(a) && has(b) && !isNull(b)) LatLon(getDouble(a), getDouble(b)) else null
        private fun JSONObject.d(k: String) = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null
        fun of(o: JSONObject): PhotoPin? {
            if (o.optString("kind") != "photo" || o.isNull("lat") || !o.has("lat")) return null
            return PhotoPin(o.getString("id"), LatLon(o.getDouble("lat"), o.getDouble("lon")), o.ll("relocatedLat", "relocatedLon"),
                o.ll("placeLat", "placeLon"), o.optLong("tMs"),
                o.optString("roadName").takeIf { it.isNotBlank() && it != "null" },
                o.d("accuracyM")?.toInt(), o.d("cameraBearing"), o.d("facadeDistanceM"), o.d("hFovDeg"))
        }
    }
}

/**
 * Map of one session: the track, a dot where each photo was taken (orange when moved by hand), and a strip of photo
 * cards to swipe through. Swiping centres the map on that photo, tapping a dot brings its card; "Move" zooms in so
 * the dot can be put on the right doorway (tap the map or drag the dot). Tap a photo to see it full screen.
 * `live` = the session is being recorded: data is reloaded whenever `refresh` changes and the newest photo is shown.
 */
@Composable
fun SessionMap(sessionId: String, refresh: Any?, live: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val store = remember { CaptureService.store(ctx) }
    val scope = rememberCoroutineScope()
    var pins by remember { mutableStateOf<List<PhotoPin>>(emptyList()) }
    var noGps by remember { mutableIntStateOf(0) }   // photos taken before / without a GPS fix: not on the map
    var track by remember { mutableStateOf<List<LatLon>>(emptyList()) }
    var relocating by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var viewer by remember { mutableStateOf<Int?>(null) }   // full-screen photo, by index

    LaunchedEffect(sessionId, refresh, reload) {
        val (items, t) = withContext(Dispatchers.IO) { store.items(sessionId) to store.trackPoints(sessionId) }
        pins = items.mapNotNull { PhotoPin.of(it) }.sortedBy { it.tMs }
        noGps = items.count { it.optString("kind") == "photo" } - pins.size
        track = t
    }

    // roads around the photos (offline packs / saved data, no network): for the Move warnings
    var ways by remember { mutableStateOf<List<io.github.tewnut.poicapture.core.Way>>(emptyList()) }
    LaunchedEffect(pins.size) {
        if (pins.isEmpty()) return@LaunchedEffect
        ways = withContext(Dispatchers.IO) {
            pins.flatMap { io.github.tewnut.poicapture.core.RoadCells.around(it.gps, 60.0) }.distinct()
                .flatMap { c -> runCatching { io.github.tewnut.poicapture.net.RoadSource.local(ctx, c)?.ways }.getOrNull().orEmpty() }
                .distinctBy { it.key }
        }
    }
    val pager = androidx.compose.foundation.pager.rememberPagerState { pins.size }
    val selected = pins.getOrNull(pager.currentPage)?.id
    // another photo on screen: stop moving the previous one
    LaunchedEffect(pager.currentPage) { relocating = false }
    // recording: show the newest photo as it comes in
    LaunchedEffect(pins.size) { if (live && pins.isNotEmpty()) pager.animateScrollToPage(pins.size - 1) }

    fun move(id: String, to: LatLon?) {
        scope.launch {
            withContext(Dispatchers.IO) { store.placeAt(sessionId, id, to) }
            relocating = false
            reload++
        }
    }

    val density = androidx.compose.ui.platform.LocalDensity.current
    var cardsPx by remember { mutableIntStateOf(0) }   // height of the card strip: the map centres above it
    var barPx by remember { mutableIntStateOf(0) }     // height of the move bar
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var centre by remember { mutableStateOf<LatLon?>(null) }   // under the pin while moving
    val sel = pins.getOrNull(pager.currentPage)
    val insetPx = if (relocating) barPx else cardsPx
    // Move starts with the pin on the photo: 0 m until the map is dragged
    LaunchedEffect(relocating) { if (relocating) centre = sel?.place }   // Move starts with the pin on the place
    Box(modifier) {
        PinsMap(pins, track, selected, live, relocating, bottomInsetPx = insetPx, moveCentre = if (relocating) centre else null,
            onSelect = { id -> pins.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { scope.launch { pager.animateScrollToPage(it) } } },
            onMap = { mapView = it }, onCentre = { centre = it },
            modifier = Modifier.fillMaxSize().clipToBounds())

        if (relocating && sel != null) {
            // warnings only (the contributor decides): too far, on the road, across the street
            val warn = centre?.let { io.github.tewnut.poicapture.core.MoveCheck.warnings(sel.camera, sel.cameraBearing, it, ways) }.orEmpty()
            val pinColor = if (warn.isEmpty()) Color(0xFFF79009) else Palette.Red   // the shop pin; red = check it
            // edit mode: framed map, the pin stays in the middle of what is visible, the map moves under it
            Box(Modifier.fillMaxSize().border(4.dp, Palette.Blue))
            val liftDp = with(density) { (insetPx / 2).toDp() }
            Column(Modifier.align(Alignment.Center).offset(y = -liftDp - 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Place, null, tint = pinColor,
                    modifier = Modifier.size(52.dp))
            }
            Box(Modifier.align(Alignment.Center).offset(y = -liftDp).size(10.dp, 4.dp)
                .background(Color(0x55000000), RoundedCornerShape(50)))   // the pin's shadow on the spot
            Pill(stringResource(R.string.sessionmap_move_banner), Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 12.dp, end = 72.dp),
                size = 14.sp, bg = Palette.Blue, bold = true)
            MoveBar(sessionId, sel, pager.currentPage, pins.size, movedM = centre?.let { Geo.distanceM(sel.camera, it).roundToInt() }, warn = warn,
                Modifier.align(Alignment.BottomCenter).onSizeChanged { barPx = it.height },
                onCancel = { relocating = false },
                onPlace = { mapView?.let { m -> move(sel.id, pinPoint(m, insetPx)) } })
        }

        if (!relocating) Column(Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 12.dp, end = 72.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (noGps > 0) Pill(ctx.resources.getQuantityString(R.plurals.sessionmap_no_gps, noGps, noGps), size = 13.sp, dot = Palette.Red)
            // which pin is which (a selected photo shows both)
            if (sel != null) Row(Modifier.background(Color(0xE6FFFFFF), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(10.dp).background(Palette.Blue, androidx.compose.foundation.shape.CircleShape))
                Text(stringResource(R.string.sessionmap_legend_photo), fontSize = 12.sp, color = Palette.Ink)
                Box(Modifier.size(10.dp).background(Color(0xFFF79009), androidx.compose.foundation.shape.CircleShape))
                Text(stringResource(R.string.sessionmap_legend_place), fontSize = 12.sp, color = Palette.Ink)
            }
        }

        if (relocating) Unit
        else if (pins.isEmpty()) {
            Pill(stringResource(if (live) R.string.sessionmap_no_photos_live else R.string.sessionmap_no_photos),
                Modifier.align(Alignment.BottomCenter).padding(16.dp))
        } else {
            androidx.compose.foundation.pager.HorizontalPager(pager,
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 12.dp)
                    .onSizeChanged { cardsPx = it.height + with(density) { 12.dp.roundToPx() } },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp),   // the next card peeks in
                pageSpacing = 10.dp) { page ->
                val p = pins[page]
                PhotoCard(sessionId, p, page, pins.size,
                    onOpen = { viewer = page },
                    onRelocate = { if (page == pager.currentPage) relocating = true else scope.launch { pager.animateScrollToPage(page) } },
                    onReset = { move(p.id, null) })
            }
        }
    }
    viewer?.let { start -> PhotoViewer(sessionId, pins, start, onClose = { i -> viewer = null; scope.launch { pager.scrollToPage(i) } }) }
}

/** One photo in the strip: the picture (tap = full screen), where it was taken, and how to fix its place. */
@Composable
private fun PhotoCard(sessionId: String, p: PhotoPin, idx: Int, count: Int,
                      onOpen: () -> Unit, onRelocate: () -> Unit, onReset: () -> Unit) {
    val thumb = rememberPhoto(sessionId, p.id, sample = 2)
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Color.White, shadowElevation = 10.dp) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF101828)).clickable(onClick = onOpen)) {
                thumb?.let { b ->
                    // a landscape photo fills the frame; a portrait one is shown whole
                    Image(b, stringResource(R.string.sessionmap_photo_thumb_desc, idx + 1), Modifier.fillMaxSize(),
                        contentScale = if (b.width >= b.height) ContentScale.Crop else ContentScale.Fit)
                }
                Pill(stringResource(R.string.sessionmap_counter, idx + 1, count), Modifier.align(Alignment.TopStart).padding(8.dp), size = 13.sp)
                Pill(time(p.tMs), Modifier.align(Alignment.TopEnd).padding(8.dp), size = 13.sp)
            }
            Text(p.roadName ?: stringResource(R.string.sessionmap_no_road), fontWeight = FontWeight.Bold, color = Palette.Ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint(if (p.edited) stringResource(R.string.sessionmap_moved)
                    else p.accuracyM?.let { stringResource(R.string.sessionmap_gps_accuracy, it) } ?: stringResource(R.string.sessionmap_gps),
                    Modifier.weight(1f), color = if (p.edited) Palette.Amber else Palette.Muted)
                if (p.placed != null) TextButton(onClick = onReset, modifier = Modifier.height(40.dp)) {
                    Text(stringResource(R.string.sessionmap_back_gps), maxLines = 1)
                }
                Button(onClick = onRelocate, modifier = Modifier.height(40.dp)) {
                    Text(stringResource(R.string.sessionmap_move), maxLines = 1)
                }
            }
        }
    }
}

/** Move mode, at the bottom instead of the cards: which photo, how far it moved, cancel or place it under the pin. */
@Composable
private fun MoveBar(sessionId: String, p: PhotoPin, idx: Int, count: Int, movedM: Int?, warn: Set<io.github.tewnut.poicapture.core.MoveWarning>,
                    modifier: Modifier,
                    onCancel: () -> Unit, onPlace: () -> Unit) {
    val thumb = rememberPhoto(sessionId, p.id, sample = 4)
    androidx.activity.compose.BackHandler(onBack = onCancel)
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), color = Color.White, shadowElevation = 12.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(72.dp, 48.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF101828))) {
                    thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.sessionmap_move_title, idx + 1, count), fontWeight = FontWeight.Bold, color = Palette.Ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val w = warn.firstOrNull()
                    when {
                        w != null -> Hint("⚠ " + stringResource(when (w) {
                            io.github.tewnut.poicapture.core.MoveWarning.FAR -> R.string.sessionmap_warn_far
                            io.github.tewnut.poicapture.core.MoveWarning.ON_ROAD -> R.string.sessionmap_warn_road
                            io.github.tewnut.poicapture.core.MoveWarning.ACROSS -> R.string.sessionmap_warn_across
                        }, io.github.tewnut.poicapture.core.MoveCheck.MAX_M.toInt()), color = Palette.Amber)
                        movedM != null && movedM >= 1 -> Hint(stringResource(R.string.sessionmap_move_distance, movedM), color = Palette.Blue)
                        else -> Hint(stringResource(R.string.sessionmap_move_hint), color = Palette.Blue)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.height(52.dp)) { Text(stringResource(R.string.sessionmap_cancel)) }
                Button(onClick = onPlace, modifier = Modifier.weight(1f).height(52.dp)) {
                    Text(stringResource(R.string.sessionmap_place_here), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** Full screen, swipe between photos; closing returns to the photo last shown. */
@Composable
private fun PhotoViewer(sessionId: String, pins: List<PhotoPin>, start: Int, onClose: (Int) -> Unit) {
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = start) { pins.size }
    androidx.compose.ui.window.Dialog(onDismissRequest = { onClose(pager.currentPage) },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.fillMaxSize(), pageSpacing = 16.dp) { page ->
                val b = rememberPhoto(sessionId, pins[page].id, sample = 1)
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (b != null) Image(b, stringResource(R.string.sessionmap_photo_desc, page + 1), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else androidx.compose.material3.CircularProgressIndicator(color = Color.White)
                }
            }
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().safeDrawingPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(stringResource(R.string.sessionmap_counter, pager.currentPage + 1, pins.size) + " · " + time(pins[pager.currentPage].tMs),
                    size = 14.sp)
                Box(Modifier.weight(1f))
                androidx.compose.material3.IconButton(onClick = { onClose(pager.currentPage) }) {
                    Text("✕", fontSize = 22.sp, color = Color.White)
                }
            }
        }
    }
}

/** A photo from the session, decoded off the main thread ([sample] = 1 full size, 2 half, …). */
@Composable
private fun rememberPhoto(sessionId: String, id: String, sample: Int): ImageBitmap? {
    val ctx = LocalContext.current
    var b by remember(id, sample) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(id, sample) {
        b = withContext(Dispatchers.IO) {
            val f = CaptureService.store(ctx).photoFile(sessionId, id)
            runCatching { BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap() }.getOrNull()
        }
    }
    return b
}

private fun time(ms: Long) = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))

private fun dot(color: Int, sizeDp: Int, density: Float) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
    setStroke((2 * density).toInt(), 0xFFFFFFFF.toInt())
    setSize((sizeDp * density).toInt(), (sizeDp * density).toInt())
}

@Composable
private fun PinsMap(pins: List<PhotoPin>, track: List<LatLon>, selected: String?, live: Boolean, relocating: Boolean, bottomInsetPx: Int,
                    moveCentre: LatLon?, onSelect: (String) -> Unit, onMap: (MapView) -> Unit, onCentre: (LatLon) -> Unit,
                    modifier: Modifier) {
    val ctx = LocalContext.current
    val select by rememberUpdatedState(onSelect)
    val centreTo by rememberUpdatedState(onCentre)
    val inset by rememberUpdatedState(bottomInsetPx)
    val moving by rememberUpdatedState(relocating)
    val allPins by rememberUpdatedState(pins)
    val tileUrl = remember { Prefs.settings(ctx).tileUrl }
    val density = ctx.resources.displayMetrics.density
    val icons = remember {
        mapOf("gps" to dot(Palette.Green.toArgb(), 14, density), "fixed" to dot(0xFFF79009.toInt(), 14, density),
            "sel" to dot(Palette.Blue.toArgb(), 22, density),
            "origin" to GradientDrawable().apply {     // move mode: where the photo was before
                shape = GradientDrawable.OVAL
                setColor(0x331570EF)
                setStroke((2.5f * density).toInt(), Palette.Blue.toArgb())
                setSize((18 * density).toInt(), (18 * density).toInt())
            },
            "facade" to GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFF7A5AF8.toInt())
                setStroke((1.5f * density).toInt(), 0xFFFFFFFF.toInt())
                setSize((9 * density).toInt(), (9 * density).toInt())
            })
    }
    val shopPin = remember { androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.ic_shop_pin) }
    val map = remember {
        MapView(ctx).apply {
            setTileSource(TemplateTileSource(tileUrl))
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            // OSM tiles stop at 19; deeper zoom stretches them (blurry) but lets a dot go on the right doorway
            maxZoomLevel = 22.0
            // until the session's own points are fitted: where this phone last was (no fixed city)
            val here = lastKnownPosition(ctx)
            controller.setZoom(if (here != null) 17.0 else 3.0)
            controller.setCenter(here?.let { GeoPoint(it.lat, it.lon) } ?: GeoPoint(0.0, 0.0))
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) map.onResume()
            if (e == Lifecycle.Event.ON_PAUSE) map.onPause()
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); map.onDetach() }
    }
    var fitted by remember { mutableStateOf(false) }
    var lastCount by remember { mutableIntStateOf(0) }

    // the point under the pin (move mode): reported as the map is dragged
    DisposableEffect(map) {
        onMap(map)
        val l = object : org.osmdroid.events.MapListener {
            override fun onScroll(e: org.osmdroid.events.ScrollEvent?): Boolean { centreTo(pinPoint(map, inset)); return false }
            override fun onZoom(e: org.osmdroid.events.ZoomEvent?): Boolean { centreTo(pinPoint(map, inset)); return false }
        }
        map.addMapListener(l)
        onDispose { map.removeMapListener(l) }
    }
    var shownSel by remember { mutableStateOf<String?>(null) }   // the photo the map last centred on
    var shownMoving by remember { mutableStateOf(false) }
    Box(modifier) {
    AndroidView({ map }, Modifier.fillMaxSize()) { m ->
        m.overlays.clear()
        m.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                // dots are small and close together: a tap selects the nearest one within ~28 dp
                if (!moving) {
                    val at = m.projection.toPixels(p, null)
                    val near = allPins.minByOrNull { pin ->
                        val q = m.projection.toPixels(GeoPoint(pin.at.lat, pin.at.lon), null)
                        (q.x - at.x).toDouble().let { it * it } + (q.y - at.y).toDouble().let { it * it }
                    }
                    if (near != null) {
                        val q = m.projection.toPixels(GeoPoint(near.at.lat, near.at.lon), null)
                        val d = Math.hypot((q.x - at.x).toDouble(), (q.y - at.y).toDouble())
                        if (d <= 28 * density) { select(near.id); return true }
                    }
                }
                return false   // move mode: the map only pans, the pin stays in the middle
            }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        }))
        if (track.size > 1) {
            m.overlays.add(Polyline(m).apply {
                setPoints(track.map { GeoPoint(it.lat, it.lon) })
                outlinePaint.color = if (relocating) 0x4498A2B3 else 0xCC1570EF.toInt()   // faded while moving a photo
                outlinePaint.strokeWidth = 6f
            })
        }
        fun dot(p: LatLon, key: String, onTap: () -> Unit) = m.overlays.add(Marker(m).apply {
            position = GeoPoint(p.lat, p.lon)
            icon = icons[key]
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setInfoWindow(null)
            setOnMarkerClickListener { _, _ -> onTap(); true }
        })
        // the other photos: small dots where they were taken (orange = place set by hand); hidden while moving
        if (!relocating) for (p in pins) if (p.id != selected) dot(p.camera, if (p.edited) "fixed" else "gps") { select(p.id) }
        val sp = pins.firstOrNull { it.id == selected }
        if (sp != null) {
            val cam = sp.camera
            val shop = if (relocating) moveCentre ?: sp.place else sp.place
            // how sure the GPS was: a light circle around the camera
            sp.accuracyM?.takeIf { it > 0 }?.let { r ->
                m.overlays.add(Polygon(m).apply {
                    points = Polygon.pointsAsCircle(GeoPoint(cam.lat, cam.lon), r.toDouble())
                    fillPaint.color = 0x221570EF
                    outlinePaint.color = 0x661570EF
                    outlinePaint.strokeWidth = 2 * density
                    setOnClickListener { _, _, _ -> false }
                })
            }
            // what the camera saw: its field of view as a wedge, out to just past the place
            sp.cameraBearing?.let { b ->
                val half = (sp.hFovDeg ?: CapturePlan.DEFAULT_HFOV) / 2
                val len = (Geo.distanceM(cam, shop) + 4.0).coerceIn(6.0, 40.0)
                val arc = (-6..6).map { k -> Geo.destination(cam, b + half * k / 6.0, len) }
                m.overlays.add(Polygon(m).apply {
                    points = (listOf(cam) + arc + cam).map { GeoPoint(it.lat, it.lon) }
                    fillPaint.color = 0x1F12B76A
                    outlinePaint.color = 0x5512B76A
                    outlinePaint.strokeWidth = 1.5f * density
                    setOnClickListener { _, _, _ -> false }
                })
            }
            // camera → place
            if (Geo.distanceM(cam, shop) > 0.5) m.overlays.add(Polyline(m).apply {
                setPoints(listOf(GeoPoint(cam.lat, cam.lon), GeoPoint(shop.lat, shop.lon)))
                outlinePaint.color = 0xFFF79009.toInt()
                outlinePaint.strokeWidth = 3 * density
                if (relocating || sp.placed == null) outlinePaint.pathEffect =
                    android.graphics.DashPathEffect(floatArrayOf(8 * density, 6 * density), 0f)   // dashed = estimate / being moved
            })
            dot(cam, "sel") {}
            // the place pin (while moving, the fixed pin in the middle of the screen is the place)
            if (!relocating) m.overlays.add(Marker(m).apply {
                position = GeoPoint(shop.lat, shop.lon)
                icon = shopPin
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setInfoWindow(null)
                setOnMarkerClickListener { _, _ -> true }
            })
        }
        val all = track + pins.map { it.at }
        if (!fitted && pins.isNotEmpty()) {
            // open on the selected photo (its camera and place, above the cards) at a sharp zoom; zoom out for the rest
            fitted = true
            m.controller.setZoom(19.0)
        } else if (!fitted && all.size > 1) {
            fitted = true
            shownSel = selected   // the whole session is in view: no jump to photo 1
            m.post {
                m.zoomToBoundingBox(BoundingBox.fromGeoPoints(all.map { GeoPoint(it.lat, it.lon) }).increaseByScale(1.3f), false, 60)
                if (m.zoomLevelDouble > 19.0) m.controller.setZoom(19.0)   // sharp tiles first; zoom in further by hand
            }
        } else if (!fitted && all.size == 1) {
            fitted = true
            shownSel = selected   // the whole session is in view: no jump to photo 1
            m.controller.setCenter(GeoPoint(all[0].lat, all[0].lon))
        }
        // swiped to another photo (or a new one came in while recording): bring its dot to the centre;
        // "Move": zoom in on it so it can be put on the right doorway
        val sel = pins.firstOrNull { it.id == selected }
        // (move mode waits until the move bar is measured, so the photo lands exactly under the pin)
        if (sel != null && fitted && (sel.id != shownSel || (relocating && !shownMoving && bottomInsetPx > 0))) {
            val z = if (relocating) maxOf(m.zoomLevelDouble, 20.0) else m.zoomLevelDouble
            // the photo goes to the middle of what the cards / move bar leave visible (where the move pin is)
            val t = if (relocating) sel.place else LatLon((sel.camera.lat + sel.place.lat) / 2, (sel.camera.lon + sel.place.lon) / 2)
            m.post { m.controller.animateTo(GeoPoint(t.lat - shiftLat(t.lat, z, bottomInsetPx / 2), t.lon), z, 400L) }
            shownSel = sel.id
        }
        shownMoving = relocating && (shownMoving || bottomInsetPx > 0)
        lastCount = pins.size
        m.invalidate()
    }
    // one-handed zoom, above the cards
    Column(Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((label, inc) in listOf("+" to true, "−" to false)) {
            Surface(onClick = { if (inc) map.controller.zoomIn() else map.controller.zoomOut() }, modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(12.dp), color = Color.White, shadowElevation = 4.dp) {
                Box(contentAlignment = Alignment.Center) {
                    Text(label, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                }
            }
        }
    }
    }
}

/** The phone's newest remembered position from any provider, or null (no permission / never had one). */
@android.annotation.SuppressLint("MissingPermission")
internal fun lastKnownPosition(ctx: android.content.Context): LatLon? = runCatching {
    val lm = ctx.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
    lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.elapsedRealtimeNanos }?.let { LatLon(it.latitude, it.longitude) }
}.getOrNull()

/** Degrees of latitude for [px] screen pixels at this zoom (north-up map). */
private fun shiftLat(lat: Double, zoom: Double, px: Int): Double =
    px * org.osmdroid.util.TileSystem.GroundResolution(lat, zoom) / 111_320.0

/** The point under the move pin: the middle of the map area left visible above the bottom panel. */
private fun pinPoint(m: MapView, insetPx: Int): LatLon {
    val c = m.mapCenter
    return LatLon(c.latitude + shiftLat(c.latitude, m.zoomLevelDouble, insetPx / 2), c.longitude)
}
