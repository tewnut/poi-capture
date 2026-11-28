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
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.SegmentedButton
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
import io.github.tewnut.poicapture.core.SweepTarget
import io.github.tewnut.poicapture.core.Coverage
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
import io.github.tewnut.poicapture.core.Building
import io.github.tewnut.poicapture.core.BuildingQuery
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
        .put("capabilities", io.github.tewnut.poicapture.capture.DeviceCheck.capabilities(ctx))
        .put("screen", "${dm.widthPixels}x${dm.heightPixels}")
        .put("lang", Locale.getDefault().toLanguageTag())
        .put("app", runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: JSONObject.NULL)
}

/**
 * "Ghi mới": always the right-hand row of buildings, road recognised automatically while riding. The contributor only
 * checks GPS, picks how to record, and presses Start.
 */
@OptIn(ExperimentalLayoutApi::class)

/** Indoor walks are hidden from the setup for now (2026-11-27: the step count drops while photographing, the mall's
 * outline is missed from just outside it; docs/indoor.md). Rides already recorded indoors still open and upload. */
const val INDOOR_ENABLED = false

@Composable
fun SetupScreen(onStart: (String) -> Unit, onOpenSettings: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { Prefs.settings(ctx) }
    val contributor = remember { Prefs.contributorId(ctx) }   // optional: "" = none
    val loc = rememberLocationState()
    val live = loc.live   // a current fix: road, suggestions; the map may show a remembered one
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { !it }) {
            Toast.makeText(ctx, ctx.getString(R.string.setup_need_permissions), Toast.LENGTH_LONG).show()
            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", ctx.packageName, null)))
        }
    }
    // "đã quét": where this phone already recorded, to go where nothing is yet
    var showCoverage by remember { mutableStateOf(true) }
    var mine by remember { mutableStateOf(CoverLayer()) }
    LaunchedEffect(Unit) {
        mine = withContext(Dispatchers.IO) {
            val store = CaptureService.store(ctx)
            val covs = store.list().filter { it.id != CaptureService.activeSession.value }.mapNotNull { runCatching { Coverage.forSession(store, it.id) }.getOrNull() }
            CoverLayer(covs.flatMap { Coverage.lines(it) }, covs.flatMap { Coverage.points(it) })
        }
    }
    // work to do on site, from the reviewer (Settings -> Tasks)
    var tasks by remember { mutableStateOf<List<io.github.tewnut.poicapture.core.FieldTask>>(emptyList()) }
    var tasksTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tasksTick) { tasks = withContext(Dispatchers.IO) { CaptureService.tasks(ctx).all() } }
    var tasksOpen by remember { mutableStateOf(false) }
    var taskFocus by remember { mutableStateOf<String?>(null) }
    var kind by remember { mutableStateOf(SessionKind.SWEEP) }
    var holding by remember { mutableStateOf(Prefs.holding(ctx)) }
    var target by remember { mutableStateOf(Prefs.target(ctx)) }
    var nearWays by remember { mutableStateOf<List<Way>?>(null) }
    var roadStatus by remember { mutableStateOf<Int?>(null) }   // string resource of the road data status, null = fine
    var roadSource by remember { mutableStateOf("") }
    var packSuggestion by remember { mutableStateOf<RoadSource.Entry?>(null) }
    var roadReload by remember { mutableIntStateOf(0) }   // a pack was just installed: look the road up again
    var askedSteps by remember { mutableStateOf(false) }
    // the step permission answered (either way): start
    val startRef = remember { arrayOfNulls<() -> Unit>(1) }
    val stepsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { startRef[0]?.invoke() }

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
    val currentWay = live?.let { l -> nearWays?.let { ways ->
        matchRoad(ways, LatLon(l.lat, l.lon))?.takeIf { it.distanceM <= 30 }?.let { m -> ways.firstOrNull { it.id == m.wayId } }
    } }
    val currentRoad = currentWay?.let { wayLabel(ctx, it) }
    // spacing and speed as the capture will start: from the type of this road (see CapturePlan.facadeForRoad)
    val plan = CapturePlan.of(CapturePlan.facadeForRoad(currentWay?.highway), target = target)

    // ---------- indoor: the building under / nearest to the walker (docs/indoor.md §4) ----------
    val indoor = kind == SessionKind.INDOOR
    val anyFix = loc.fix
    // no fix yet (location just switched on, first seconds): the phone's last known position, however old
    val lastKnown = remember(loc.status) { lastKnownFix(ctx) }
    val mapCenter = remember { arrayOfNulls<LatLon>(1) }
    val lookup = rememberBuildingLookup(ctx, settings.overpassUrl, indoor, anyFix ?: lastKnown)
    val building = lookup.building
    val noBuilding = lookup.none
    var searching by remember { mutableStateOf(false) }

    // the same rule as the capture: Start only    // the same rule as the capture: Start only with a GPS fix from the last 5 s; indoors a position or a building
    val ready = if (indoor) anyFix != null || building != null else live != null

    androidx.activity.compose.BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Palette.Canvas).safeDrawingPadding()) {
        var info by remember { mutableStateOf(false) }
        ScreenHeader(stringResource(R.string.home_new_session), onBack = onBack) {
            androidx.compose.material3.IconButton(onClick = { info = true }) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.Info, stringResource(R.string.setup_info_title), tint = Palette.Ink)
            }
        }
        if (info) SetupInfo(plan.photoEveryM, plan.maxSpeedKmh) { info = false }
        if (searching) BuildingSearchSheet(settings.overpassUrl,
            (anyFix ?: lastKnown)?.let { LatLon(it.lat, it.lon) } ?: mapCenter[0],
            onPick = { h -> searching = false; lookup.pick(h) }, onLog = { lookup.log(it) }, onClose = { searching = false })
        if (tasksOpen) TasksSheet(loc.fix?.let { LatLon(it.lat, it.lon) }, taskFocus, onChanged = { tasksTick++ }) { tasksOpen = false; taskFocus = null }
        // ---------- map ----------
        Box(Modifier.weight(0.38f).heightIn(min = 150.dp).fillMaxWidth()) {
            LiveMap(settings.tileUrl, loc.fix, if (showCoverage) mine else CoverLayer(), tasks,
                { taskFocus = it; tasksOpen = true }, Modifier.fillMaxSize().clipToBounds(),
                building = if (indoor && !noBuilding) building else null,
                // indoor: a tap picks the building there (the guess was wrong)
                onTap = if (indoor) { p -> lookup.at(p, "tap") } else null, center = mapCenter)
            if (indoor && lookup.pickOnMap) Pill(stringResource(R.string.setup_building_tap_now), bg = Palette.Black, fg = Palette.Lime,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp))
            val openTasks = tasks.count { it.open }
            if (openTasks > 0) androidx.compose.material3.AssistChip(onClick = { taskFocus = null; tasksOpen = true },
                label = { Text("📋 " + stringResource(R.string.setup_tasks_chip, openTasks), fontSize = 12.sp) },
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(containerColor = Color.White))
            if (mine.lines.isNotEmpty() || mine.points.isNotEmpty()) {
                androidx.compose.material3.FilterChip(showCoverage, { showCoverage = !showCoverage },
                    { Text(stringResource(R.string.setup_coverage), fontSize = 12.sp) },
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                    colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(containerColor = Color.White, selectedContainerColor = Palette.GreenLight))
            }
            Column(Modifier.align(Alignment.TopStart).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (loc.status) {
                    LocStatus.NO_PERMISSION -> Pill(stringResource(R.string.setup_loc_no_permission), dot = Palette.Red)
                    LocStatus.APPROXIMATE_ONLY -> Pill(stringResource(R.string.setup_loc_approximate), dot = Palette.Red)
                    LocStatus.DISABLED -> Pill(stringResource(R.string.setup_loc_disabled), dot = Palette.Red)
                    LocStatus.SEARCHING -> Pill(when {
                            loc.satsSeen > 0 -> stringResource(R.string.setup_loc_searching_sats, loc.satsUsed, loc.satsSeen)
                            loc.approx != null -> stringResource(R.string.setup_loc_network, loc.approx!!.accuracyM.roundToInt())
                            else -> stringResource(R.string.setup_loc_searching)
                        }, dot = Color(0xFFFEC84B))
                    LocStatus.OK -> live!!.let { l ->
                        if (l.accuracyM <= settings.maxAccuracyM) Pill(stringResource(R.string.setup_gps_good, l.accuracyM.roundToInt()), dot = Color(0xFF47CD89))
                        else Pill(stringResource(R.string.setup_gps_weak, l.accuracyM.roundToInt()), dot = Color(0xFFFEC84B))
                    }
                }
                if (live?.simulated == true) {
                    Pill(stringResource(R.string.setup_simulated), bg = Palette.AmberLight, fg = Palette.Amber, size = 12.sp)
                }
            }
        }

        // ---------- what will happen + Start ----------
        Surface(Modifier.weight(0.62f), shadowElevation = 8.dp, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // only when there is something to do (permission, location off); the map shows the satellites
                    if (loc.status != LocStatus.SEARCHING) LocationHelp(loc)
                    // three quick choices, short labels only: what each means is behind the (i) button
                    Choice(stringResource(R.string.setup_kind_label), listOfNotNull(
                        SessionKind.SWEEP to stringResource(R.string.setup_sweep_short),
                        SessionKind.POI to stringResource(R.string.setup_place_short),
                        if (INDOOR_ENABLED) SessionKind.INDOOR to stringResource(R.string.setup_indoor_short) else null), kind) { kind = it }
                    if (kind == SessionKind.SWEEP) {
                        Choice(stringResource(R.string.setup_target_label), listOf(
                            SweepTarget.SHOPS to "🏪 " + stringResource(R.string.setup_target_shops_short),
                            SweepTarget.ADDRESSES to "🔢 " + stringResource(R.string.setup_target_addresses_short),
                            SweepTarget.SIGNS to "🚸 " + stringResource(R.string.setup_target_signs_short)), target) { target = it; Prefs.saveTarget(ctx, it) }
                        Choice(stringResource(R.string.setup_holding_title), listOf(
                            io.github.tewnut.poicapture.core.Holding.MOUNT to stringResource(R.string.setup_holding_mount_short),
                            io.github.tewnut.poicapture.core.Holding.HAND to stringResource(R.string.setup_holding_hand)), holding) { holding = it; Prefs.saveHolding(ctx, it) }
                    }
                    // indoor: shops only, in the hand: no target or holding to pick; the building instead of the road
                    if (indoor) BuildingCard(lookup, onSearch = { searching = true })
                    if (!indoor) Column {
                        Hint(stringResource(R.string.setup_you_are_on))
                        Text(currentRoad ?: stringResource(if (live == null) R.string.setup_waiting_location else R.string.setup_road_unknown),
                            fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Palette.Ink, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        // outside every installed pack, but the catalogue has one here: suggest it (computed off the UI thread)
                        val suggest = packSuggestion
                        if (suggest != null) {
                            Hint(stringResource(R.string.setup_pack_suggest), color = Palette.Blue)
                            PackRow(suggest, null, anyNetwork = true, onInstalled = { packSuggestion = null; roadReload++ })
                        }
                    }
                    Spacer(Modifier.height(8.dp))   // the last line scrolls clear of the bottom bar
                }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    val onStartClick: () -> Unit = start@{
                            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                                permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION))
                                return@start
                            }
                            if (CaptureService.activeSession.value != null) {
                                Toast.makeText(ctx, ctx.getString(R.string.setup_session_running), Toast.LENGTH_LONG).show()
                                return@start
                            }
                            // indoor: steps from the step detector need this permission (asked once; refused = accelerometer)
                            if (indoor && !askedSteps && ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
                                askedSteps = true
                                stepsLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                                return@start
                            }
                            val now = System.currentTimeMillis()
                            val s = Session(id = uid("s_", now), createdAt = iso(now), contributor = contributor, note = "",
                                // a sweep always shoots by itself (no hand on the phone while riding), a place is shot by hand
                                // indoor: on foot, auto photos like a sweep
                                vehicle = Vehicle.UNSPECIFIED, mode = if (kind == SessionKind.POI) Mode.PHOTO_MANUAL else Mode.PHOTO_AUTO,
                                sideOfTravel = Side.RIGHT, road = Road.auto(),
                                settings = settings.copy(facadeDistanceM = CapturePlan.UNKNOWN_ROAD_FACADE_M,
                                    // house numbers are small print: the largest photo the camera gives
                                    photoLongEdge = if (kind == SessionKind.SWEEP && target == SweepTarget.ADDRESSES)
                                        maxOf(settings.photoLongEdge, SweepTarget.ADDRESSES_LONG_EDGE) else settings.photoLongEdge), device = deviceJson(ctx), kind = kind, email = "",
                                photoRoles = if (kind == SessionKind.POI && Prefs.askPhotoRole(ctx)) Prefs.photoRoles(ctx) else null,
                                holding = when (kind) { SessionKind.SWEEP -> holding; SessionKind.INDOOR -> io.github.tewnut.poicapture.core.Holding.HAND
                                    else -> io.github.tewnut.poicapture.core.Holding.MOUNT },
                                target = if (kind == SessionKind.SWEEP) target else SweepTarget.SHOPS,
                                building = if (indoor && !noBuilding) building else null,
                                indoor = if (!indoor) null else JSONObject().put("stepLengthM", io.github.tewnut.poicapture.core.Pdr.DEFAULT_STEP_M)
                                    .put("start", anyFix?.let { f -> JSONObject().put("lat", f.lat).put("lon", f.lon)
                                        .put("accuracyM", f.accuracyM.toDouble()).put("gps", f.gps && f.freshGps()) } ?: JSONObject.NULL))
                            CaptureService.store(ctx).create(s)
                            // how the building was found (or not): the walk's first indoor.jsonl lines (docs/indoor.md §5)
                            lookup.flushTo(CaptureService.store(ctx).indoorFile(s.id))
                            onStart(s.id)
                        }
                    startRef[0] = onStartClick
                    Button(enabled = ready, modifier = Modifier.fillMaxWidth().height(56.dp), onClick = onStartClick) {
                        // waiting: said on the button itself (a line above it shrank the page and hid the street)
                        Text(stringResource(if (ready) R.string.setup_start else R.string.setup_need_gps), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** One setup choice: a short label and a row of segments, the selected one filled. */
@Composable
private fun <T> Choice(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Palette.Muted)
        androidx.compose.material3.SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (value, text) ->
                SegmentedButton(
                    selected = value == selected, onClick = { onSelect(value) },
                    shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(i, options.size),
                    icon = {},   // no check mark: the fill shows the choice and leaves room for the words
                    colors = androidx.compose.material3.SegmentedButtonDefaults.colors(
                        activeContainerColor = Palette.GreenLight, activeContentColor = Palette.Ink, activeBorderColor = Palette.Green),
                ) { Text(text, maxLines = 1, fontSize = 14.sp, fontWeight = if (value == selected) FontWeight.Bold else FontWeight.Normal,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            }
        }
    }
}

/** After the contributor touches the setup map, it stops following them for this long. */
private const val FOLLOW_PAUSE_MS = 8_000L

/** Already-recorded tracks and places shown on the setup map. */
data class CoverLayer(val lines: List<List<LatLon>> = emptyList(), val points: List<LatLon> = emptyList())

@Composable
fun Hint(t: String, modifier: Modifier = Modifier, color: Color = Palette.Muted) =
    Text(t, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = color)

/** Map centred on the rider: blue dot with an accuracy circle, re-centre on request. */
@Composable
private fun LiveMap(tileUrl: String, me: LiveLocation?, mine: CoverLayer,
                    tasks: List<io.github.tewnut.poicapture.core.FieldTask>, onTask: (String) -> Unit, modifier: Modifier,
                    building: Building? = null, onTap: ((LatLon) -> Unit)? = null, center: Array<LatLon?>? = null) {
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
    // the map follows the rider by itself; a finger on the map (looking at a task, the coverage) pauses that a while
    val touchedAt = remember { longArrayOf(0L) }
    DisposableEffect(map) {
        @Suppress("ClickableViewAccessibility")
        map.setOnTouchListener { _, _ -> touchedAt[0] = SystemClock.elapsedRealtime(); false }
        onDispose { map.setOnTouchListener(null) }
    }
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
    val tap = androidx.compose.runtime.rememberUpdatedState(onTap)
    // the map's centre, for the name search when there is no position at all
    DisposableEffect(map, center) {
        if (center == null) return@DisposableEffect onDispose {}
        val l = object : org.osmdroid.events.MapListener {
            override fun onScroll(e: org.osmdroid.events.ScrollEvent?): Boolean { center[0] = map.mapCenter.let { LatLon(it.latitude, it.longitude) }; return false }
            override fun onZoom(e: org.osmdroid.events.ZoomEvent?): Boolean = false
        }
        map.addMapListener(l)
        onDispose { map.removeMapListener(l) }
    }
    AndroidView({ map }, modifier) { m ->
        m.overlays.clear()
        if (onTap != null) m.overlays.add(org.osmdroid.views.overlay.MapEventsOverlay(object : org.osmdroid.events.MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { tap.value?.invoke(LatLon(p.latitude, p.longitude)); return tap.value != null }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        }))
        building?.let { b -> drawBuilding(m, b) }
        for ((layer, color) in listOf(mine to 0xAA12B76A.toInt())) {
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
        // tasks: open ones larger, on top of the coverage; tap -> the task in the list
        for (t in tasks.sortedBy { it.open }) m.overlays.add(Marker(m).apply {
            position = GeoPoint(t.at.lat, t.at.lon)
            icon = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(taskColor(t).toArgb())
                setStroke((2 * density).toInt(), 0xFFFFFFFF.toInt())
                val px = ((if (t.open) 18 else 11) * density).toInt(); setSize(px, px)
            }
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setInfoWindow(null)
            setOnMarkerClickListener { _, _ -> onTask(t.id); true }
        })
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
            if (!centered) {
                centered = true
                m.controller.setZoom(17.5)
                m.controller.animateTo(here)
            } else if (SystemClock.elapsedRealtime() - touchedAt[0] > FOLLOW_PAUSE_MS && m.width > 0) {
                // glide the map once the dot leaves the middle half of the view, so it never runs off the edge
                val px = m.projection.toPixels(here, null)
                val dx = px.x - m.width / 2f; val dy = px.y - m.height / 2f
                if (kotlin.math.abs(dx) > m.width / 4f || kotlin.math.abs(dy) > m.height / 4f) m.controller.animateTo(here)
            }
        }
        m.invalidate()
    }
}

/** ⓘ on New session: everything the main screen leaves out to stay short. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SetupInfo(photoEveryM: Double, maxSpeedKmh: Int, onClose: () -> Unit) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onClose, containerColor = Color.White) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.setup_info_title), style = ScreenTitle.copy(fontSize = 24.sp))
            @Composable fun Section(title: String) = Text(title.uppercase(Locale.getDefault()), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp, color = Palette.Green, modifier = Modifier.padding(top = 6.dp))
            @Composable fun Item(icon: String, title: String, text: String) = Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(40.dp).background(Palette.GreenLight, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                    Text(icon, fontSize = 19.sp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Palette.Ink)
                    Text(text, color = Palette.Muted, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
            Section(stringResource(R.string.setup_sweep_title))
            Item("🛵", stringResource(R.string.setup_holding_mount), stringResource(R.string.setup_mount_hint))
            Item("🙋", stringResource(R.string.setup_holding_hand), stringResource(R.string.setup_hand_info))
            Item("📸", stringResource(R.string.setup_mode_auto), stringResource(R.string.setup_mode_auto_hint, String.format(Locale.ROOT, "%.1f", photoEveryM), maxSpeedKmh))
            Item("📏", stringResource(R.string.setup_facade_title), stringResource(R.string.setup_facade_hint))
            Item("🏪", stringResource(R.string.setup_target_shops), stringResource(R.string.setup_target_shops_info))
            Item("🔢", stringResource(R.string.setup_target_addresses), stringResource(R.string.setup_target_addresses_info))
            Item("🚸", stringResource(R.string.setup_target_signs), stringResource(R.string.setup_target_signs_info))
            Section(stringResource(R.string.setup_place_title))
            Item("🏪", stringResource(R.string.setup_place_title), stringResource(R.string.setup_poi_hint))
            Text(stringResource(R.string.guide_intro), color = Palette.Muted, fontSize = 14.sp, lineHeight = 20.sp)
            PlaceGuideList()
            if (INDOOR_ENABLED) {
                Section(stringResource(R.string.setup_indoor_title))
                Item("🏬", stringResource(R.string.setup_indoor_title), stringResource(R.string.setup_indoor_info))
            }
            Section(stringResource(R.string.setup_info_both))
            Item("🛣️", stringResource(R.string.setup_you_are_on), stringResource(R.string.setup_road_auto_hint))
            Item("🛰️", stringResource(R.string.setup_info_gps_title), stringResource(R.string.setup_info_gps))
        }
    }
}
