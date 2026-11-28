package io.github.tewnut.poicapture.ui

import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.tewnut.poicapture.R
import io.github.tewnut.poicapture.capture.IndoorUi
import io.github.tewnut.poicapture.core.Building
import io.github.tewnut.poicapture.core.LatLon
import io.github.tewnut.poicapture.core.Levels
import io.github.tewnut.poicapture.core.Pdr
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

// Indoor mode ("Trong nhà", docs/indoor.md): the building on the setup map, and on the capture screen the map of the
// walk (footprint, the shops already mapped on this level, the walked track, the estimate), "Tôi ở đây", the level.

private fun pts(r: List<LatLon>) = r.map { GeoPoint(it.lat, it.lon) }

/** The building's outline and, for [level], what is mapped inside it (rooms faint, shops as dots with their names). */
fun drawBuilding(m: MapView, b: Building, level: Int? = null) {
    for (ring in b.outer) m.overlays.add(Polygon(m).apply {
        points = pts(ring)
        holes = b.holes.map { pts(it) }
        fillPaint.color = 0x223D6B00
        outlinePaint.color = 0xFF3D6B00.toInt()
        outlinePaint.strokeWidth = 5f
        setOnClickListener { _, _, _ -> false }
    })
    if (level != null) drawBuildingFeatures(m, b, level)
}

/** What is mapped inside the building on [level] (drawn over a floor plan). */
fun drawBuildingFeatures(m: MapView, b: Building, level: Int) {
    val density = m.context.resources.displayMetrics.density
    for (f in b.features) {
        if (f.level != null && !f.onLevel(level)) continue
        if (f.kind != "shop" && f.geometry.size >= 3) m.overlays.add(Polygon(m).apply {
            points = pts(f.geometry)
            fillPaint.color = 0x11101828
            outlinePaint.color = 0x55101828
            outlinePaint.strokeWidth = 2f
            setOnClickListener { _, _, _ -> false }
        })
        if (f.kind == "shop") m.overlays.add(Marker(m).apply {
            position = f.center.let { GeoPoint(it.lat, it.lon) }
            icon = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                // mapped without a level: shown on every level, greyed
                setColor(if (f.level == null) 0xFF98A2B3.toInt() else 0xFF7BCF00.toInt())
                setStroke((2 * density).toInt(), 0xFF0A0A0A.toInt())
                setSize((12 * density).toInt(), (12 * density).toInt())
            }
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = listOfNotNull(f.unit, f.name).joinToString(" · ").ifEmpty { f.tag ?: "" }
        })
    }
}

/**
 * Capture, indoor: the map of the walk. Long-press = "I am here" ([onLongPress]); [onTap] is used by the ⌖ mode and the
 * direction tap. Follows the estimate unless the walker moved the map a moment ago.
 */
@Composable
fun IndoorMap(tileUrl: String, building: Building?, ui: IndoorUi, track: List<List<LatLon>>, pending: LatLon?,
              onLongPress: (LatLon) -> Unit, onTap: (LatLon) -> Unit, modifier: Modifier,
              planDir: java.io.File? = null, plan: io.github.tewnut.poicapture.core.FloorPlanEntry? = null, placing: PlanPlacing? = null,
              onUnit: ((io.github.tewnut.poicapture.core.PlanUnit, LatLon) -> Unit)? = null) {
    val ctx = LocalContext.current
    val map = remember {
        MapView(ctx).apply {
            setTileSource(TemplateTileSource(tileUrl))
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            maxZoomLevel = 22.0
            controller.setZoom(19.0)
            (ui.position ?: building?.center)?.let { controller.setCenter(GeoPoint(it.lat, it.lon)) }
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
    val touchedAt = remember { longArrayOf(0L) }
    DisposableEffect(map) {
        @Suppress("ClickableViewAccessibility")
        map.setOnTouchListener { _, _ -> touchedAt[0] = android.os.SystemClock.elapsedRealtime(); false }
        onDispose { map.setOnTouchListener(null) }
    }
    val long = rememberUpdatedState(onLongPress)
    val tap = rememberUpdatedState(onTap)
    val events = remember {
        MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { tap.value(LatLon(p.latitude, p.longitude)); return true }
            override fun longPressHelper(p: GeoPoint): Boolean { long.value(LatLon(p.latitude, p.longitude)); return true }
        })
    }
    val blue = Palette.Blue.toArgb()
    val density = ctx.resources.displayMetrics.density
    val far = ui.sinceAnchorM > Pdr.REPOSITION_M || !ui.anchored
    // the level's floor plan, under the walk (docs/indoor.md §7)
    val planOverlay = remember { PlanOverlay(ctx) }
    val unitTap = rememberUpdatedState(onUnit)
    planOverlay.onUnit = { u, p -> unitTap.value?.invoke(u, p) }
    // placing: show the plan where it is being put
    androidx.compose.runtime.LaunchedEffect(placing) {
        placing?.let { pl -> pl.toMap.toMap(pl.entry.width / 2.0, pl.entry.height / 2.0).let { map.controller.animateTo(GeoPoint(it.lat, it.lon)) } }
    }
    AndroidView({ map }, modifier) { m ->
        m.overlays.clear()
        m.overlays.add(events)
        building?.let { drawBuilding(m, it, null) }
        val shown = placing?.entry ?: plan
        if (planDir != null && shown != null) {
            planOverlay.set(planDir, shown, shown.toMap, placing)
            m.overlays.add(planOverlay)
        }
        building?.let { drawBuildingFeatures(m, it, ui.level) }
        for (seg in track) if (seg.size >= 2) m.overlays.add(Polyline(m).apply {
            setPoints(pts(seg))
            outlinePaint.color = 0xCC1570EF.toInt()
            outlinePaint.strokeWidth = 6f
        })
        ui.position?.let { p ->
            val here = GeoPoint(p.lat, p.lon)
            val c = if (far) 0xB54708 else (blue and 0x00FFFFFF)
            m.overlays.add(Polygon(m).apply {
                points = Polygon.pointsAsCircle(here, ui.uncertaintyM.coerceAtLeast(2.0))
                fillPaint.color = c or 0x22000000
                outlinePaint.color = c or 0x88000000.toInt()
                outlinePaint.strokeWidth = 2f
                setOnClickListener { _, _, _ -> false }
            })
            m.overlays.add(Marker(m).apply {
                position = here
                icon = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c or 0xFF000000.toInt())
                    setStroke((3 * density).toInt(), 0xFFFFFFFF.toInt())
                    setSize((18 * density).toInt(), (18 * density).toInt())
                }
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setInfoWindow(null)
                setOnMarkerClickListener { _, _ -> true }
            })
            // walking direction: a short line ahead of the dot
            ui.headingDeg?.let { h ->
                val ahead = io.github.tewnut.poicapture.core.Geo.destination(p, h, ui.uncertaintyM.coerceIn(4.0, 12.0))
                m.overlays.add(Polyline(m).apply {
                    setPoints(listOf(here, GeoPoint(ahead.lat, ahead.lon)))
                    outlinePaint.color = c or 0xFF000000.toInt()
                    outlinePaint.strokeWidth = 5f
                })
            }
            if (android.os.SystemClock.elapsedRealtime() - touchedAt[0] > 8_000 && m.width > 0) {
                val px = m.projection.toPixels(here, null)
                if (kotlin.math.abs(px.x - m.width / 2f) > m.width / 4f || kotlin.math.abs(px.y - m.height / 2f) > m.height / 4f)
                    m.controller.animateTo(here)
            }
        }
        pending?.let { a ->   // the anchor being confirmed
            m.overlays.add(Marker(m).apply {
                position = GeoPoint(a.lat, a.lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setInfoWindow(null)
            })
        }
        m.invalidate()
    }
}

/** "Tầng 2" with ▲ ▼: always on screen; a tap on a level is "picked" in indoor.jsonl. */
@Composable
fun LevelPicker(ui: IndoorUi, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.background(Color(0xCC000000), RoundedCornerShape(14.dp)).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        val up = ui.level + 1
        val down = ui.level - 1
        TextButton(onClick = { onPick(up) }, modifier = Modifier.size(52.dp, 40.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text("▲", color = Color.White, fontSize = 18.sp)
        }
        Text(stringResource(R.string.capture_indoor_level), color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
        Text(Levels.name(ui.level), color = Palette.Lime, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        TextButton(onClick = { onPick(down) }, modifier = Modifier.size(52.dp, 40.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text("▼", color = Color.White, fontSize = 18.sp)
        }
    }
}

/** The barometer saw a floor change: "Lên tầng 2?" — one tap to confirm. */
@Composable
fun FloorPrompt(current: Int, suggested: Int, onAnswer: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.widthIn(max = 420.dp), shape = RoundedCornerShape(16.dp), color = Color.White, shadowElevation = 10.dp) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(if (suggested > current) R.string.capture_floor_up else R.string.capture_floor_down, Levels.name(suggested)),
                fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Palette.Ink, modifier = Modifier.weight(1f))
            TextButton(onClick = { onAnswer(false) }) { Text(stringResource(R.string.capture_floor_no), color = Palette.Muted) }
            Button(onClick = { onAnswer(true) }, colors = ButtonDefaults.buttonColors(containerColor = Palette.Black, contentColor = Palette.Lime)) {
                Text(stringResource(R.string.capture_floor_yes), fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** "Tôi ở đây": confirm the point and its level (± on the level), then optionally the walking direction. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HereSheet(level: Int, levels: IntRange, onConfirm: (Int, Boolean) -> Unit, onCancel: () -> Unit, unit: String? = null) {
    var lvl by remember { mutableStateOf(level) }
    var direction by remember { mutableStateOf(false) }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onCancel, containerColor = Color.White) {
        Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.capture_here_title), style = ScreenTitle.copy(fontSize = 24.sp))
            Text(if (unit != null) stringResource(R.string.capture_here_unit, unit) else stringResource(R.string.capture_here_text),
                color = Palette.Muted, fontSize = 14.sp)
            // the building's levels, and one more each way (the map may not know them all)
            Stepper(stringResource(R.string.capture_indoor_level_name, Levels.name(lvl)), onMinus = { lvl-- }, onPlus = { lvl++ },
                minusEnabled = lvl > minOf(levels.first, level) - 1, plusEnabled = lvl < maxOf(levels.last, level) + 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Checkbox(direction, { direction = it })
                Text(stringResource(R.string.capture_here_add_direction), color = Palette.Ink, fontSize = 15.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).height(52.dp)) { Text(stringResource(R.string.capture_here_cancel)) }
                Button(onClick = { onConfirm(lvl, direction) }, modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Black, contentColor = Palette.Lime)) {
                    Text(stringResource(R.string.capture_here_confirm), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** "đã đi 85 m từ lần định vị cuối", amber above [Pdr.REPOSITION_M]; before any anchor: say where you are. */
@Composable
fun SinceAnchorLine(ui: IndoorUi, modifier: Modifier = Modifier) {
    val far = ui.sinceAnchorM > Pdr.REPOSITION_M
    val text = if (!ui.anchored) stringResource(R.string.capture_indoor_not_anchored)
        else stringResource(R.string.capture_indoor_since_anchor, ui.sinceAnchorM.toInt())
    Text(text, color = if (far || !ui.anchored) Palette.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = modifier.clip(RoundedCornerShape(10.dp))
            .background(if (far || !ui.anchored) Color(0xFFFEC84B) else Color(0xB3000000)).padding(horizontal = 10.dp, vertical = 5.dp))
}

/** Map panel frame over the camera picture (small, or the whole screen). */
@Composable
fun MapFrame(expanded: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(if (expanded) 0.dp else 14.dp))
        .border(if (expanded) 0.dp else 2.dp, Color.White, RoundedCornerShape(if (expanded) 0.dp else 14.dp))) { content() }
}
