package io.github.tewnut.poicapture.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import io.github.tewnut.poicapture.core.Onboarding
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import io.github.tewnut.poicapture.Prefs
import io.github.tewnut.poicapture.R
import io.github.tewnut.poicapture.capture.CaptureService
import io.github.tewnut.poicapture.core.CapturePlan
import io.github.tewnut.poicapture.core.Coverage
import io.github.tewnut.poicapture.capture.ServerApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.views.overlay.Polyline
import io.github.tewnut.poicapture.core.LatLon
import io.github.tewnut.poicapture.core.Mode
import io.github.tewnut.poicapture.core.Overpass
import io.github.tewnut.poicapture.core.Road
import io.github.tewnut.poicapture.core.RoadCells
import io.github.tewnut.poicapture.core.Session
import io.github.tewnut.poicapture.core.SessionKind
import io.github.tewnut.poicapture.core.Side
import io.github.tewnut.poicapture.core.Vehicle
import io.github.tewnut.poicapture.core.Way
import io.github.tewnut.poicapture.core.iso
import io.github.tewnut.poicapture.core.matchRoad
import io.github.tewnut.poicapture.core.uid
import io.github.tewnut.poicapture.net.OverpassClient
import io.github.tewnut.poicapture.net.RoadSource
import java.util.Locale
import kotlin.math.roundToInt

private fun deviceJson(ctx: Context): JSONObject {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val dm = ctx.resources.displayMetrics
    return JSONObject()
        .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
        .put("soc", if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else JSONObject.NULL)
        .put("gnssHardware", lm.gnssHardwareModelName ?: JSONObject.NULL)
        .put("gnssYear", lm.gnssYearOfHardware)
        .put("emulator", isEmulator())
        .put("screen", "${dm.widthPixels}x${dm.heightPixels}")
        .put("lang", Locale.getDefault().toLanguageTag())
        .put("app", runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: JSONObject.NULL)
}

/**
 * "Ghi mới": always the right-hand row of buildings, road recognised automatically while riding. The contributor only
 * checks GPS, picks how to record, and presses Start.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(onStart: (String) -> Unit, onOpenProfile: () -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { Prefs.settings(ctx) }
    val email = remember { Prefs.email(ctx) }
    val contributor = remember { Onboarding.displayName(Prefs.contributor(ctx), email) }
    val loc = rememberLocationState()
    val live = loc.live   // a current fix: road, suggestions; the map may show a remembered one
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { !it }) {
            Toast.makeText(ctx, ctx.getString(R.string.setup_need_permissions), Toast.LENGTH_LONG).show()
            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", ctx.packageName, null)))
        }
    }
    // "đã quét": where this phone (and, with the server API, the team) already recorded, to go where nothing is yet
    var showCoverage by remember { mutableStateOf(true) }
    var mine by remember { mutableStateOf(CoverLayer()) }
    var team by remember { mutableStateOf(CoverLayer()) }
    LaunchedEffect(Unit) {
        mine = withContext(Dispatchers.IO) {
            val store = CaptureService.store(ctx)
            val covs = store.list().filter { it.id != CaptureService.activeSession.value }.mapNotNull { runCatching { Coverage.forSession(store, it.id) }.getOrNull() }
            CoverLayer(covs.flatMap { Coverage.lines(it) }, covs.flatMap { Coverage.points(it) })
        }
    }
    val teamCell = live?.let { RoadCells.cellOf(LatLon(it.lat, it.lon)) }
    LaunchedEffect(teamCell) {
        val c = teamCell ?: return@LaunchedEffect
        val body = withContext(Dispatchers.IO) { ServerApi.coverage(ctx, c.south - 0.01, c.west - 0.01, c.north + 0.01, c.east + 0.01) } ?: return@LaunchedEffect
        runCatching { Coverage.fromGeoJson(body) }.getOrNull()?.let { (l, p) -> team = CoverLayer(l, p) }
    }
    var mode by remember { mutableStateOf(Mode.PHOTO_AUTO) }
    var kind by remember { mutableStateOf(SessionKind.SWEEP) }
    var facade by remember { mutableStateOf(settings.facadeDistanceM) }
    val plan = CapturePlan.of(facade)
    var recenter by remember { mutableIntStateOf(0) }
    var nearWays by remember { mutableStateOf<List<Way>?>(null) }
    var roadStatus by remember { mutableStateOf<Int?>(null) }   // string resource of the road data status, null = fine
    var roadSource by remember { mutableStateOf("") }
    var packSuggestion by remember { mutableStateOf<RoadSource.Entry?>(null) }
    var roadReload by remember { mutableIntStateOf(0) }   // a pack was just installed: look the road up again

    // load the road data around the rider (shared cache: the capture service reuses it) to show the current road
    val cell = live?.let { RoadCells.cellOf(LatLon(it.lat, it.lon)) }
    LaunchedEffect(cell, roadReload) {
        if (cell == null) return@LaunchedEffect
        roadSource = ""   // a new cell: the label of the previous source must not stay while this one loads
        if (nearWays == null) roadStatus = R.string.setup_roads_loading
        try {
            // offline pack (the cells around), then the disk cache, then Overpass (cached for next time)
            val hit = withContext(Dispatchers.IO) { RoadSource.local(ctx, cell) }
            if (hit != null) {
                val around = withContext(Dispatchers.IO) {
                    RoadCells.around(LatLon(live!!.lat, live.lon)).flatMap { c -> RoadSource.local(ctx, c)?.ways ?: emptyList() }
                }
                nearWays = (hit.ways + around).distinctBy { it.key }
                roadSource = hit.source
            } else {
                val w = OverpassClient.ways(settings.overpassUrl, Overpass.cellQuery(cell))
                withContext(Dispatchers.IO) { RoadSource.cache(ctx).put(cell, w) }
                nearWays = w
                roadSource = "overpass"
            }
            roadStatus = null
        } catch (e: Exception) {
            roadStatus = R.string.setup_roads_failed
        }
    }
    LaunchedEffect(cell) {
        val l = live ?: return@LaunchedEffect
        packSuggestion = withContext(Dispatchers.IO) {
            if (RoadSource.packs(ctx).forCell(RoadCells.cellOf(LatLon(l.lat, l.lon))) != null) null
            else RoadSource.suggest(RoadSource.cachedCatalogue(ctx), l.lat, l.lon)
        }
    }
    val currentRoad = live?.let { l -> nearWays?.let { ways ->
        matchRoad(ways, LatLon(l.lat, l.lon))?.takeIf { it.distanceM <= 30 }?.let { m -> ways.firstOrNull { it.id == m.wayId }?.let { wayLabel(ctx, it) } }
    } }

    val ready = Onboarding.isEmail(email)

    Column(Modifier.fillMaxSize()) {
        // ---------- map ----------
        Box(Modifier.weight(0.38f).heightIn(min = 150.dp).fillMaxWidth()) {
            LiveMap(settings.tileUrl, loc.fix, recenter, if (showCoverage) mine else CoverLayer(), if (showCoverage) team else CoverLayer(),
                Modifier.fillMaxSize().clipToBounds())
            if (mine.lines.isNotEmpty() || mine.points.isNotEmpty() || team.lines.isNotEmpty()) {
                androidx.compose.material3.FilterChip(showCoverage, { showCoverage = !showCoverage },
                    { Text(stringResource(if (team.lines.isNotEmpty()) R.string.setup_coverage_team else R.string.setup_coverage), fontSize = 12.sp) },
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                    colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(containerColor = Color.White, selectedContainerColor = Palette.GreenLight))
            }
            Column(Modifier.align(Alignment.TopStart).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (loc.status) {
                    LocStatus.NO_PERMISSION -> Pill(stringResource(R.string.setup_loc_no_permission), dot = Palette.Red)
                    LocStatus.APPROXIMATE_ONLY -> Pill(stringResource(R.string.setup_loc_approximate), dot = Palette.Red)
                    LocStatus.DISABLED -> Pill(stringResource(R.string.setup_loc_disabled), dot = Palette.Red)
                    LocStatus.SEARCHING -> Pill(if (loc.satsSeen > 0) stringResource(R.string.setup_loc_searching_sats, loc.satsUsed, loc.satsSeen)
                        else stringResource(R.string.setup_loc_searching), dot = Color(0xFFFEC84B))
                    LocStatus.OK -> live!!.let { l ->
                        if (!l.gps) Pill(stringResource(R.string.setup_loc_network, l.accuracyM.roundToInt()), dot = Color(0xFFFEC84B))
                        else if (l.accuracyM <= settings.maxAccuracyM) Pill(stringResource(R.string.setup_gps_good, l.accuracyM.roundToInt()), dot = Color(0xFF47CD89))
                        else Pill(stringResource(R.string.setup_gps_weak, l.accuracyM.roundToInt()), dot = Color(0xFFFEC84B))
                    }
                }
                if (live?.simulated == true) {
                    Pill(stringResource(R.string.setup_simulated), bg = Palette.AmberLight, fg = Palette.Amber, size = 12.sp)
                }
            }
            SmallFloatingActionButton(onClick = { recenter++ }, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                containerColor = Color.White) {
                Icon(Icons.Filled.LocationOn, stringResource(R.string.setup_recenter), tint = Palette.Blue)
            }
        }

        // ---------- what will happen + Start ----------
        Surface(Modifier.weight(0.62f), shadowElevation = 8.dp, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LocationHelp(loc)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        KindCard(stringResource(R.string.setup_sweep_title), stringResource(R.string.setup_sweep_sub),
                            kind == SessionKind.SWEEP, Modifier.weight(1f)) { kind = SessionKind.SWEEP }
                        KindCard(stringResource(R.string.setup_place_title), stringResource(R.string.setup_place_sub),
                            kind == SessionKind.POI, Modifier.weight(1f)) { kind = SessionKind.POI }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Hint(stringResource(R.string.setup_you_are_on))
                        Text(currentRoad ?: stringResource(if (live == null) R.string.setup_waiting_location else R.string.setup_road_unknown),
                            fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Palette.Ink)
                        val status = stringResource(roadStatus ?: R.string.setup_road_auto_hint)
                        Hint(when {
                            roadSource.startsWith("pack:") -> stringResource(R.string.setup_source_offline, status)
                            roadSource == "cache" -> stringResource(R.string.setup_source_cache, status)
                            else -> status
                        })
                        // outside every installed pack, but the catalogue has one here: suggest it (computed off the UI thread)
                        val suggest = packSuggestion
                        if (suggest != null) {
                            Hint(stringResource(R.string.setup_pack_suggest), color = Palette.Blue)
                            PackRow(suggest, null, anyNetwork = true, onInstalled = { packSuggestion = null; roadReload++ })
                        }
                    }
                    if (kind == SessionKind.POI) {
                        Hint(stringResource(R.string.setup_poi_hint))
                    } else {
                    Text(stringResource(R.string.setup_facade_title), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Palette.Ink)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (d in (listOf(4, 6, 8, 12) + settings.facadeDistanceM.roundToInt()).distinct().sorted()) {
                            FilterChip(facade.roundToInt() == d, { facade = d.toDouble() }, { Text(stringResource(R.string.setup_distance_m, d)) })
                        }
                    }
                    Hint(stringResource(R.string.setup_facade_hint))
                    Text(stringResource(R.string.setup_mode_title), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Palette.Ink)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(mode == Mode.PHOTO_AUTO, { mode = Mode.PHOTO_AUTO }, { Text(stringResource(R.string.setup_mode_auto)) })
                        FilterChip(mode == Mode.PHOTO_MANUAL, { mode = Mode.PHOTO_MANUAL }, { Text(stringResource(R.string.setup_mode_manual)) })
                        FilterChip(mode == Mode.VIDEO, { mode = Mode.VIDEO }, { Text(stringResource(R.string.setup_mode_video)) })
                    }
                    Hint(when (mode) {
                        Mode.PHOTO_AUTO -> stringResource(R.string.setup_mode_auto_hint, String.format(Locale.ROOT, "%.1f", plan.photoEveryM), plan.maxSpeedKmh)
                        Mode.PHOTO_MANUAL -> stringResource(R.string.setup_mode_manual_hint, plan.maxSpeedKmh)
                        Mode.VIDEO -> stringResource(R.string.setup_mode_video_hint, plan.maxSpeedKmh)
                    })
                    Hint(stringResource(R.string.setup_mount_hint))
                    }
                }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    if (!ready) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Hint(stringResource(R.string.setup_need_email), Modifier.weight(1f), Palette.Amber)
                            TextButton(onClick = onOpenProfile) { Text(stringResource(R.string.setup_enter_email)) }
                        }
                    }
                    Button(
                        enabled = ready,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        onClick = {
                            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                                permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION))
                                return@Button
                            }
                            if (CaptureService.activeSession.value != null) {
                                Toast.makeText(ctx, ctx.getString(R.string.setup_session_running), Toast.LENGTH_LONG).show()
                                return@Button
                            }
                            val now = System.currentTimeMillis()
                            val s = Session(id = uid("s_", now), createdAt = iso(now), contributor = contributor, note = "",
                                vehicle = Vehicle.UNSPECIFIED, mode = if (kind == SessionKind.POI) Mode.PHOTO_MANUAL else mode,
                                sideOfTravel = Side.RIGHT, road = Road.auto(),
                                settings = settings.copy(facadeDistanceM = facade), device = deviceJson(ctx), kind = kind, email = email,
                                photoRoles = if (kind == SessionKind.POI && Prefs.askPhotoRole(ctx)) Prefs.photoRoles(ctx) else null)
                            CaptureService.store(ctx).create(s)
                            onStart(s.id)
                        },
                    ) { Text(stringResource(R.string.setup_start), fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
private fun KindCard(title: String, sub: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedCard(
        onClick = onClick, modifier = modifier,
        border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Palette.Green else Color(0xFFD0D5DD)),
    ) {
        Column(Modifier.background(if (selected) Palette.GreenLight else Color.Transparent).padding(12.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = if (selected) Palette.Green else Palette.Ink)
            Hint(sub)
        }
    }
}

/** Already-recorded tracks and places shown on the setup map. */
data class CoverLayer(val lines: List<List<LatLon>> = emptyList(), val points: List<LatLon> = emptyList())

@Composable
fun Hint(t: String, modifier: Modifier = Modifier, color: Color = Palette.Muted) =
    Text(t, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = color)

/** Map centred on the rider: blue dot with an accuracy circle, re-centre on request. */
@Composable
private fun LiveMap(tileUrl: String, me: LiveLocation?, recenter: Int, mine: CoverLayer, team: CoverLayer, modifier: Modifier) {
    val ctx = LocalContext.current
    val map = remember {
        MapView(ctx).apply {
            setTileSource(TemplateTileSource(tileUrl))
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(6.0)
            controller.setCenter(GeoPoint(16.0, 106.5))
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
    var centered by remember { mutableStateOf(false) }
    var lastRecenter by remember { mutableIntStateOf(0) }
    val blue = Palette.Blue.toArgb()
    val density = ctx.resources.displayMetrics.density
    val meDot = remember {
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(blue)
            setStroke((3 * density).toInt(), 0xFFFFFFFF.toInt())
            setSize((20 * density).toInt(), (20 * density).toInt())
        }
    }
    AndroidView({ map }, modifier) { m ->
        m.overlays.clear()
        for ((layer, color) in listOf(team to 0xAA7A5AF8.toInt(), mine to 0xAA12B76A.toInt())) {
            for (line in layer.lines) m.overlays.add(Polyline(m).apply {
                setPoints(line.map { GeoPoint(it.lat, it.lon) })
                outlinePaint.color = color
                outlinePaint.strokeWidth = 10f
            })
            for (p in layer.points) m.overlays.add(Polygon(m).apply {
                points = Polygon.pointsAsCircle(GeoPoint(p.lat, p.lon), 4.0)
                fillPaint.color = color
                outlinePaint.color = 0xFFFFFFFF.toInt()
                outlinePaint.strokeWidth = 2f
            })
        }
        me?.let { l ->
            val here = GeoPoint(l.lat, l.lon)
            m.overlays.add(Polygon(m).apply {
                points = Polygon.pointsAsCircle(here, l.accuracyM.toDouble().coerceAtLeast(3.0))
                fillPaint.color = (blue and 0x00FFFFFF) or 0x33000000
                outlinePaint.color = (blue and 0x00FFFFFF) or 0x88000000.toInt()
                outlinePaint.strokeWidth = 2f
            })
            m.overlays.add(Marker(m).apply {
                position = here
                icon = meDot
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setInfoWindow(null)
                setOnMarkerClickListener { _, _ -> true }
            })
            if (!centered || recenter != lastRecenter) {
                centered = true
                lastRecenter = recenter
                m.controller.setZoom(17.5)
                m.controller.animateTo(here)
            }
        }
        m.invalidate()
    }
}
