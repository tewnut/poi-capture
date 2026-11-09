package io.github.tewnut.poicapture.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import org.osmdroid.views.overlay.Polyline
import io.github.tewnut.poicapture.Prefs
import io.github.tewnut.poicapture.capture.CaptureService
import io.github.tewnut.poicapture.core.Geo
import io.github.tewnut.poicapture.core.LatLon
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** XYZ tile URL template ({z}/{x}/{y}), e.g. OSM or VMAP tiles. */
internal class TemplateTileSource(private val template: String) :
    OnlineTileSourceBase("tiles-${template.hashCode()}", 0, 19, 256, "", arrayOf(template), "© OpenStreetMap contributors") {
    override fun getTileURLString(index: Long): String = template
        .replace("{z}", MapTileIndex.getZoom(index).toString())
        .replace("{x}", MapTileIndex.getX(index).toString())
        .replace("{y}", MapTileIndex.getY(index).toString())
}

/** A photo on the map: GPS position, and the contributor's correction when there is one. */
data class PhotoPin(val id: String, val gps: LatLon, val fixed: LatLon?, val tMs: Long, val roadName: String?, val accuracyM: Int?,
                    val cameraBearing: Double?, val facadeDistanceM: Double?) {
    val at: LatLon get() = fixed ?: gps
    /** estimated shop-front point, from the effective (possibly corrected) camera position */
    val facade: LatLon? get() = if (cameraBearing != null && facadeDistanceM != null) Geo.destination(at, cameraBearing, facadeDistanceM) else null
    companion object {
        fun of(o: JSONObject): PhotoPin? {
            if (o.optString("kind") != "photo" || o.isNull("lat") || !o.has("lat")) return null
            val fixed = if (!o.isNull("relocatedLat") && o.has("relocatedLat")) LatLon(o.getDouble("relocatedLat"), o.getDouble("relocatedLon")) else null
            return PhotoPin(o.getString("id"), LatLon(o.getDouble("lat"), o.getDouble("lon")), fixed, o.optLong("tMs"),
                o.optString("roadName").takeIf { it.isNotBlank() && it != "null" },
                if (o.isNull("accuracyM")) null else o.optDouble("accuracyM").toInt(),
                if (o.isNull("cameraBearing") || !o.has("cameraBearing")) null else o.getDouble("cameraBearing"),
                if (o.isNull("facadeDistanceM") || !o.has("facadeDistanceM")) null else o.getDouble("facadeDistanceM"))
        }
    }
}

/**
 * Map of one session: the track, a dot where each photo was taken (orange when moved by hand), and a panel to look at
 * a photo and put it in the right place (tap "Sửa vị trí" then the map, or drag the selected dot).
 * `live` = the session is being recorded: data is reloaded whenever `refresh` changes and the map follows the rider.
 */
@Composable
fun SessionMap(sessionId: String, refresh: Any?, live: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val store = remember { CaptureService.store(ctx) }
    val scope = rememberCoroutineScope()
    var pins by remember { mutableStateOf<List<PhotoPin>>(emptyList()) }
    var track by remember { mutableStateOf<List<LatLon>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var relocating by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(sessionId, refresh, reload) {
        val (p, t) = withContext(Dispatchers.IO) {
            store.items(sessionId).mapNotNull { PhotoPin.of(it) }.sortedBy { it.tMs } to store.trackPoints(sessionId)
        }
        pins = p
        track = t
    }

    fun move(id: String, to: LatLon?) {
        scope.launch {
            withContext(Dispatchers.IO) { store.relocate(sessionId, id, to) }
            relocating = false
            reload++
        }
    }

    val sel = pins.firstOrNull { it.id == selected }
    Box(modifier) {
        PinsMap(pins, track, selected, live,
            onSelect = { selected = it; relocating = false },
            onMapTap = { p -> val s = selected; if (relocating && s != null) move(s, p) },
            onDragged = { id, p -> move(id, p) },
            modifier = Modifier.fillMaxSize().clipToBounds())

        if (pins.isEmpty()) {
            Pill(if (live) "Chưa có ảnh. Ảnh sẽ hiện trên bản đồ ngay khi chụp." else "Phiên này không có ảnh.",
                Modifier.align(Alignment.BottomCenter).padding(16.dp))
        } else if (sel == null) {
            Pill("${pins.size} ảnh · ${pins.count { it.fixed != null }} đã sửa vị trí · chạm vào chấm để xem ảnh",
                Modifier.align(Alignment.BottomCenter).padding(16.dp), size = 13.sp)
        }

        if (sel != null) {
            val idx = pins.indexOf(sel)
            PhotoPanel(sessionId, sel, idx, pins.size, relocating,
                onPrev = { if (idx > 0) { selected = pins[idx - 1].id; relocating = false } },
                onNext = { if (idx < pins.size - 1) { selected = pins[idx + 1].id; relocating = false } },
                onRelocate = { relocating = !relocating },
                onReset = { move(sel.id, null) },
                onClose = { selected = null; relocating = false },
                modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp))
        }
    }
}

@Composable
private fun PhotoPanel(sessionId: String, p: PhotoPin, idx: Int, count: Int, relocating: Boolean,
                       onPrev: () -> Unit, onNext: () -> Unit, onRelocate: () -> Unit, onReset: () -> Unit, onClose: () -> Unit,
                       modifier: Modifier) {
    val ctx = LocalContext.current
    var thumb by remember(p.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(p.id) {
        thumb = withContext(Dispatchers.IO) {
            val f = CaptureService.store(ctx).photoFile(sessionId, p.id)
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap()
        }
    }
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Color.White, shadowElevation = 10.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(160.dp, 90.dp).border(1.dp, Color(0xFFD0D5DD), RoundedCornerShape(8.dp))) {
                    thumb?.let { Image(it, "Ảnh ${idx + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Ảnh ${idx + 1}/$count · ${time(p.tMs)}", fontWeight = FontWeight.Bold, color = Palette.Ink)
                    Text(p.roadName ?: "Chưa nhận ra đường", color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Hint(if (p.fixed != null) "Vị trí đã sửa tay (GPS gốc vẫn được giữ)" else "Vị trí GPS" + (p.accuracyM?.let { " ±$it m" } ?: ""),
                        color = if (p.fixed != null) Palette.Amber else Palette.Muted)
                    p.facadeDistanceM?.let { Hint("■ điểm mặt tiền ước tính, cách ${it.toInt()} m", color = Palette.Muted) }
                }
                TextButton(onClick = onClose) { Text("Đóng") }
            }
            if (relocating) Hint("Chạm vào đúng vị trí trên bản đồ (hoặc giữ và kéo chấm xanh).", color = Palette.Blue)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onPrev, enabled = idx > 0) { Text("‹") }
                OutlinedButton(onClick = onNext, enabled = idx < count - 1) { Text("›") }
                Button(onClick = onRelocate) { Text(if (relocating) "Huỷ sửa" else "Sửa vị trí") }
                if (p.fixed != null && !relocating) TextButton(onClick = onReset) { Text("Về vị trí GPS") }
            }
        }
    }
}

private fun time(ms: Long) = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))

private fun dot(color: Int, sizeDp: Int, density: Float) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
    setStroke((2 * density).toInt(), 0xFFFFFFFF.toInt())
    setSize((sizeDp * density).toInt(), (sizeDp * density).toInt())
}

@Composable
private fun PinsMap(pins: List<PhotoPin>, track: List<LatLon>, selected: String?, live: Boolean,
                    onSelect: (String) -> Unit, onMapTap: (LatLon) -> Unit, onDragged: (String, LatLon) -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val select by rememberUpdatedState(onSelect)
    val tap by rememberUpdatedState(onMapTap)
    val dragged by rememberUpdatedState(onDragged)
    val tileUrl = remember { Prefs.settings(ctx).tileUrl }
    val density = ctx.resources.displayMetrics.density
    val icons = remember {
        mapOf("gps" to dot(Palette.Green.toArgb(), 14, density), "fixed" to dot(0xFFF79009.toInt(), 14, density),
            "sel" to dot(Palette.Blue.toArgb(), 22, density),
            "facade" to GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFF7A5AF8.toInt())
                setStroke((1.5f * density).toInt(), 0xFFFFFFFF.toInt())
                setSize((9 * density).toInt(), (9 * density).toInt())
            })
    }
    val map = remember {
        MapView(ctx).apply {
            setTileSource(TemplateTileSource(tileUrl))
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            maxZoomLevel = 19.0          // OSM tiles stop at 19: deeper zoom only blurs them
            controller.setZoom(17.0)
            controller.setCenter(GeoPoint(21.0285, 105.8542))
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

    AndroidView({ map }, modifier) { m ->
        m.overlays.clear()
        m.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { tap(LatLon(p.latitude, p.longitude)); return true }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        }))
        if (track.size > 1) {
            m.overlays.add(Polyline(m).apply {
                setPoints(track.map { GeoPoint(it.lat, it.lon) })
                outlinePaint.color = 0xCC1570EF.toInt()
                outlinePaint.strokeWidth = 6f
            })
        }
        // estimated shop-front points (on the building side), with a line from the camera for the selected photo
        for (p in pins) {
            val fc = p.facade ?: continue
            if (p.id == selected) m.overlays.add(Polyline(m).apply {
                setPoints(listOf(GeoPoint(p.at.lat, p.at.lon), GeoPoint(fc.lat, fc.lon)))
                outlinePaint.color = 0xFF7A5AF8.toInt()
                outlinePaint.strokeWidth = 4f
            })
            m.overlays.add(Marker(m).apply {
                position = GeoPoint(fc.lat, fc.lon)
                icon = icons["facade"]
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setInfoWindow(null)
                setOnMarkerClickListener { _, _ -> select(p.id); true }
            })
        }
        // selected last so it is drawn on top
        for (p in pins.sortedBy { it.id == selected }) {
            val isSel = p.id == selected
            m.overlays.add(Marker(m).apply {
                position = GeoPoint(p.at.lat, p.at.lon)
                icon = icons[if (isSel) "sel" else if (p.fixed != null) "fixed" else "gps"]
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setInfoWindow(null)
                isDraggable = isSel
                setOnMarkerClickListener { _, _ -> select(p.id); true }
                setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                    override fun onMarkerDrag(marker: Marker) {}
                    override fun onMarkerDragStart(marker: Marker) {}
                    override fun onMarkerDragEnd(marker: Marker) = dragged(p.id, LatLon(marker.position.latitude, marker.position.longitude))
                })
            })
        }
        val all = track + pins.map { it.at }
        if (!fitted && all.size > 1) {
            fitted = true
            m.post { m.zoomToBoundingBox(BoundingBox.fromGeoPoints(all.map { GeoPoint(it.lat, it.lon) }).increaseByScale(1.3f), false, 60) }
        } else if (!fitted && all.size == 1) {
            fitted = true
            m.controller.setCenter(GeoPoint(all[0].lat, all[0].lon))
        }
        // live: keep the newest photo in view while nothing is selected
        if (live && selected == null && pins.size != lastCount && pins.isNotEmpty()) {
            m.controller.animateTo(GeoPoint(pins.last().at.lat, pins.last().at.lon))
        }
        lastCount = pins.size
        m.invalidate()
    }
}
