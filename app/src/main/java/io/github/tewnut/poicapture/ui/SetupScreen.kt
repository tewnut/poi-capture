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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
import io.github.tewnut.poicapture.core.SetupSummary
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
    // a street ride with a 360 camera: an option of Quét đường (the ride's kind is camera360, docs/camera360.md)
    var use360 by remember { mutableStateOf(Prefs.use360(ctx)) }
    val rideKind = if (kind == SessionKind.SWEEP && use360) SessionKind.CAMERA360 else kind
    var holding by remember { mutableStateOf(Prefs.holding(ctx)) }
    var target by remember { mutableStateOf(Prefs.target(ctx)) }
    // Camera 360: the camera chosen in Settings → Camera 360; with a Ricoh Theta the phone controls it (docs/camera360.md §4)
    var cam360Model by remember { mutableStateOf(Prefs.camera360Model(ctx)) }
    val thetaOn = cam360Model == io.github.tewnut.poicapture.core.Camera360Model.THETA
    var thetaEveryM by remember { mutableStateOf(Prefs.thetaEveryM(ctx)) }
    var cam360Page by remember { mutableStateOf(false) }
    // leaving the setup without starting a Theta ride lets the camera's Wi-Fi go
    DisposableEffect(Unit) { onDispose { if (!io.github.tewnut.poicapture.capture.ThetaLink.forRide) io.github.tewnut.poicapture.capture.ThetaLink.release() } }
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
    // the choices: open the first time, one line with Đổi once a ride was started (not kept: folded again next time)
    var expanded by remember { mutableStateOf(!Prefs.startedRide(ctx)) }
    // searching for 20 s (not blocked by a permission or the location switch: LocationHelp says what to do then)
    val gpsHint = rememberGpsWaitHint(!indoor && live == null && loc.status == LocStatus.SEARCHING)

    androidx.activity.compose.BackHandler(onBack = onBack)
    // Thu gọn folds the choices again only once a ride was started; the first time they stay open
    val canFold = remember { Prefs.startedRide(ctx) }

    // ---------- the sheet over the map (like Google Maps): heights in px, measured ----------
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var headerH by remember { mutableIntStateOf(0) }    // the top bar, status bar included
    var fullH by remember { mutableIntStateOf(0) }      // from under the top bar to the bottom (or the keyboard)
    var choicesH by remember { mutableIntStateOf(0) }   // the choices' natural height
    var barH by remember { mutableIntStateOf(0) }       // street, GPS, Start, navigation bar included
    val handleH = with(density) { SHEET_HANDLE.roundToPx() }
    val measured = fullH > 0 && barH > 0
    // never lower than the street and Start; at most the choices' own height (or the screen)
    val minPx = (handleH + barH).toFloat()
    val maxPx = maxOf(minPx, minOf(fullH, handleH + choicesH + barH).toFloat())
    val partialPx = (fullH * SHEET_PARTIAL).coerceIn(minPx, maxPx)
    fun px(a: SheetAnchor) = when (a) { SheetAnchor.MIN -> minPx; SheetAnchor.PARTIAL -> partialPx; SheetAnchor.MAX -> maxPx }
    var anchor by remember { mutableStateOf(SheetAnchor.MAX) }
    var sheetH by remember { mutableFloatStateOf(-1f) }   // -1: not measured yet (the sheet fills the screen once)
    var dragging by remember { mutableStateOf(false) }
    val anim = remember { arrayOfNulls<Job>(1) }
    fun animateTo(target: Float) {
        anim[0]?.cancel()
        if (sheetH < 0f) { sheetH = target; return }
        anim[0] = scope.launch {
            animate(sheetH, target, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { v, _ -> sheetH = v }
        }
    }
    // a finger moved dy (down > 0): the sheet follows it between its lowest and highest; returns the part used
    val moveSheet: (Float) -> Float = { dy ->
        if (sheetH < 0f) 0f else {
            val before = sheetH
            val after = (before - dy).coerceIn(minPx, maxPx)
            if (after != before) { anim[0]?.cancel(); dragging = true; sheetH = after }
            before - after
        }
    }
    // the finger lifted: the next height in a quick throw's direction, else the nearest
    val settle: (Float) -> Unit = { v ->
        dragging = false
        val fast = with(density) { SHEET_FLING.toPx() }
        val h = sheetH
        val order = SheetAnchor.entries.sortedBy { px(it) }
        val pick = when {
            v < -fast -> order.firstOrNull { px(it) > h + 1f } ?: order.last()
            v > fast -> order.lastOrNull { px(it) < h - 1f } ?: order.first()
            else -> order.minBy { kotlin.math.abs(px(it) - h) }
        }
        anchor = pick
        animateTo(px(pick))
    }
    val moveRef = rememberUpdatedState(moveSheet)
    val settleRef = rememberUpdatedState(settle)
    // the choices scroll inside the sheet: pulling up grows the sheet first, pulling down at their top lowers it
    val sheetScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (source != NestedScrollSource.UserInput || available.y >= 0f) Offset.Zero
                else Offset(0f, moveRef.value(available.y))
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (source != NestedScrollSource.UserInput || available.y <= 0f) Offset.Zero
                else Offset(0f, moveRef.value(available.y))
            override suspend fun onPreFling(available: Velocity): Velocity =
                if (dragging) { settleRef.value(available.y); available } else Velocity.Zero
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (dragging) settleRef.value(0f)
                return Velocity.Zero
            }
        }
    }
    val sheetTarget = px(anchor)
    // the content changed height (choices opened, a card, the GPS hint): keep the sheet at the same place, re-measured
    LaunchedEffect(sheetTarget, measured) { if (measured && !dragging) animateTo(sheetTarget) }
    // Đổi / Thu gọn: the sheet to the choices' height (all of them, or the one line)
    LaunchedEffect(expanded) { anchor = SheetAnchor.MAX }
    // no GPS for 20 s: lower a tall sheet so the map and the accuracy circle show next to the hint
    LaunchedEffect(gpsHint) { if (gpsHint && anchor == SheetAnchor.MAX && maxPx > partialPx + 1f) anchor = SheetAnchor.PARTIAL }
    // a tap on the map: the sheet down to its content (choices folded), or half the screen the first time
    val onMapTap: (LatLon) -> Unit = { p ->
        if (anchor != SheetAnchor.MIN) {
            if (canFold && expanded) expanded = false
            else if (anchor == SheetAnchor.MAX && maxPx > partialPx + 1f) anchor = SheetAnchor.PARTIAL
        }
        // indoor: a tap picks the building there (the guess was wrong)
        if (indoor) lookup.at(p, "tap")
    }
    val headerDp = with(density) { headerH.toDp() }
    // the map centres the rider in the part the sheet leaves (the settled height, at most half the screen)
    val coveredPx = minOf(sheetTarget, fullH * SHEET_PARTIAL).roundToInt()
    var info by remember { mutableStateOf(false) }
    val sideInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)

    Box(Modifier.fillMaxSize().background(Palette.Canvas)) {
        if (info) SetupInfo(plan.photoEveryM, plan.maxSpeedKmh) { info = false }
        if (searching) BuildingSearchSheet(settings.overpassUrl,
            (anyFix ?: lastKnown)?.let { LatLon(it.lat, it.lon) } ?: mapCenter[0],
            onPick = { h -> searching = false; lookup.pick(h) }, onLog = { lookup.log(it) }, onClose = { searching = false })
        if (tasksOpen) TasksSheet(loc.fix?.let { LatLon(it.lat, it.lon) }, taskFocus, onChanged = { tasksTick++ }) { tasksOpen = false; taskFocus = null }
        // ---------- map: the whole screen, behind ----------
        LiveMap(settings.tileUrl, loc.fix, if (showCoverage) mine else CoverLayer(), tasks,
            { taskFocus = it; tasksOpen = true }, Modifier.fillMaxSize().clipToBounds(),
            building = if (indoor && !noBuilding) building else null,
            onTap = onMapTap, center = mapCenter, insetTop = headerH, insetBottom = coveredPx)
        // the map's chips, in the part of the map in view (no touch handling of its own: drags reach the map)
        // (the sheet's height is read while laying out only: a drag must not recompose the screen and redraw the map)
        Box(Modifier.fillMaxSize().windowInsetsPadding(sideInsets).padding(top = headerDp).layout { m, c ->
            val h = (c.maxHeight - sheetH.coerceAtLeast(0f).roundToInt()).coerceAtLeast(0)
            val p = m.measure(c.copy(minHeight = h, maxHeight = h))
            layout(p.width, c.maxHeight) { p.place(0, 0) }
        }) {
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
        }
        // ---------- top bar, over the map ----------
        Surface(Modifier.fillMaxWidth().onSizeChanged { headerH = it.height }, color = Palette.Canvas, shadowElevation = 4.dp) {
            Box(Modifier.statusBarsPadding().windowInsetsPadding(sideInsets)) {
                ScreenHeader(stringResource(R.string.home_new_session), onBack = onBack) {
                    androidx.compose.material3.IconButton(onClick = { info = true }) {
                        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.Info, stringResource(R.string.setup_info_title), tint = Palette.Ink)
                    }
                }
            }
        }

        // ---------- the sheet: what will happen + Start ----------
        Box(Modifier.fillMaxSize().padding(top = headerDp).imePadding().windowInsetsPadding(sideInsets).onSizeChanged { fullH = it.height }) {
            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().layout { m, c ->
                    // not measured yet: the whole height once, to measure the choices and the bottom part
                    val h = if (sheetH < 0f) c.maxHeight else sheetH.roundToInt().coerceIn(0, c.maxHeight)
                    val p = m.measure(c.copy(minHeight = h, maxHeight = h))
                    layout(p.width, h) { p.place(0, 0) }
                },
                shadowElevation = 8.dp, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
                Column(Modifier.fillMaxSize().draggable(rememberDraggableState { moveSheet(it) }, Orientation.Vertical,
                    onDragStopped = { v -> settle(v) })) {
                    // the handle: drag it (or the street / Start part) to see the map
                    Box(Modifier.fillMaxWidth().height(SHEET_HANDLE), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(width = 36.dp, height = 4.dp).background(Palette.Muted.copy(alpha = 0.45f), RoundedCornerShape(2.dp)))
                    }
                Column(Modifier.weight(1f).nestedScroll(sheetScroll).verticalScroll(rememberScrollState())) {
                Column(Modifier.fillMaxWidth().onSizeChanged { choicesH = it.height }.padding(start = 16.dp, end = 16.dp, top = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // only when there is something to do (permission, location off); the map shows the satellites
                    if (loc.status != LocStatus.SEARCHING) LocationHelp(loc)
                    // after the first ride the choices fold into one line ("Quét đường · Biển báo · Kẹp cố định") with Đổi;
                    // the first time they are all open. Folding is only a view: the ride is set up the same way.
                    if (!expanded) ChoiceSummary(io.github.tewnut.poicapture.core.SetupSummary.parts(kind, target, use360, thetaOn, holding)) { expanded = true }
                    // opened with Đổi: Thu gọn folds them again
                    if (expanded && canFold) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { expanded = false }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                            Text(stringResource(R.string.setup_collapse), fontWeight = FontWeight.Bold)
                        }
                    }
                    // three quick choices, short labels only: what each means is behind the (i) button
                    if (expanded) Choice(stringResource(R.string.setup_kind_label), listOfNotNull(
                        SessionKind.SWEEP to stringResource(R.string.setup_sweep_short),
                        SessionKind.POI to stringResource(R.string.setup_place_short),
                        if (INDOOR_ENABLED) SessionKind.INDOOR to stringResource(R.string.setup_indoor_short) else null), kind) { kind = it }
                    if (expanded && kind == SessionKind.SWEEP) {
                        Choice(stringResource(R.string.setup_target_label), listOf(
                            SweepTarget.SHOPS to "🏪 " + stringResource(R.string.setup_target_shops_short),
                            SweepTarget.ADDRESSES to "🔢 " + stringResource(R.string.setup_target_addresses_short),
                            SweepTarget.SIGNS to "🚸 " + stringResource(R.string.setup_target_signs_short)), target) { target = it; Prefs.saveTarget(ctx, it) }
                        Choice(stringResource(R.string.setup_camera_label), listOf(false to stringResource(R.string.setup_camera_phone),
                            true to stringResource(R.string.setup_camera360_choice)), use360) { use360 = it; Prefs.saveUse360(ctx, it) }
                        if (!use360) Choice(stringResource(R.string.setup_holding_title), listOf(
                            io.github.tewnut.poicapture.core.Holding.MOUNT to stringResource(R.string.setup_holding_mount_short),
                            io.github.tewnut.poicapture.core.Holding.HAND to stringResource(R.string.setup_holding_hand)), holding) { holding = it; Prefs.saveHolding(ctx, it) }
                    }
                    // indoor: shops only, in the hand: no target or holding to pick; the building instead of the road
                    if (indoor) BuildingCard(lookup, onSearch = { searching = true })
                    // Camera 360: the phone only records where it went (docs/camera360.md §1)
                    if (expanded && rideKind == SessionKind.CAMERA360) {
                        Hint(stringResource(R.string.setup_camera360_hint))
                        // the camera and its state in one line; Đổi / Chọn camera opens Settings → Camera 360 over the sheet
                        Camera360SetupLine(cam360Model, thetaEveryM) { cam360Page = true }
                        if (cam360Page) Camera360PageDialog(releaseOnLeave = false, onClose = {
                            cam360Page = false
                            cam360Model = Prefs.camera360Model(ctx)
                            thetaEveryM = Prefs.thetaEveryM(ctx)
                        })
                        // how to record, per camera (GoPro MAX / Ricoh Theta): the tab of the chosen control first
                        var guide by remember { mutableStateOf(false) }
                        androidx.compose.material3.TextButton(onClick = { guide = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                            Text("📖 " + stringResource(R.string.guide360_open), fontWeight = FontWeight.Bold)
                        }
                        if (guide) Camera360Guide(initial = if (thetaOn) 1 else 0) { guide = false }
                    }
                    // outside every installed pack, but the catalogue has one here: suggest it (computed off the UI thread)
                    val suggest = packSuggestion
                    if (!indoor && suggest != null) Column {
                        Hint(stringResource(R.string.setup_pack_suggest), color = Palette.Blue)
                        PackRow(suggest, null, anyNetwork = true, onInstalled = { packSuggestion = null; roadReload++ })
                    }
                    Spacer(Modifier.height(8.dp))   // the last line scrolls clear of the bottom bar
                }
                }   // the choices
                // pinned at the bottom of the sheet whatever its height (measured as a whole: the sheet's lowest point)
                Column(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).onSizeChanged { barH = it.height }
                    .navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // where the ride starts and the GPS, always in view right above Start
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Hint(if (indoor) "" else stringResource(R.string.setup_you_are_on), Modifier.padding(end = 8.dp))
                        GpsState(loc, live, settings.maxAccuracyM, Modifier.weight(1f, fill = false))
                    }
                    if (!indoor) Text(currentRoad ?: stringResource(if (live == null) R.string.setup_waiting_location else R.string.setup_road_unknown),
                        fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Palette.Ink, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    // no usable fix for 20 s: where to stand
                    if (gpsHint) GpsSkyHint()
                    Spacer(Modifier.height(6.dp))
                    val onStartClick: () -> Unit = start@{
                            // Camera 360: the phone's camera is never opened, location is enough
                            val cam360 = rideKind == SessionKind.CAMERA360
                            if ((!cam360 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) ||
                                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                                permLauncher.launch(listOfNotNull(Manifest.permission.CAMERA.takeIf { !cam360 }, Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION).toTypedArray())
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
                                vehicle = Vehicle.UNSPECIFIED, mode = if (kind == SessionKind.POI || cam360) Mode.PHOTO_MANUAL else Mode.PHOTO_AUTO,
                                sideOfTravel = Side.RIGHT, road = Road.auto(),
                                settings = settings.copy(facadeDistanceM = CapturePlan.UNKNOWN_ROAD_FACADE_M,
                                    // house numbers are small print: the largest photo the camera gives
                                    photoLongEdge = if (kind == SessionKind.SWEEP && target == SweepTarget.ADDRESSES)
                                        maxOf(settings.photoLongEdge, SweepTarget.ADDRESSES_LONG_EDGE) else settings.photoLongEdge), device = deviceJson(ctx), kind = rideKind, email = "",
                                photoRoles = if (kind == SessionKind.POI && Prefs.askPhotoRole(ctx)) Prefs.photoRoles(ctx) else null,
                                holding = when (kind) { SessionKind.SWEEP -> holding; SessionKind.INDOOR -> io.github.tewnut.poicapture.core.Holding.HAND
                                    else -> io.github.tewnut.poicapture.core.Holding.MOUNT },
                                target = if (kind == SessionKind.SWEEP) target else SweepTarget.SHOPS,
                                building = if (indoor && !noBuilding) building else null,
                                indoor = if (!indoor) null else JSONObject().put("stepLengthM", io.github.tewnut.poicapture.core.Pdr.DEFAULT_STEP_M)
                                    .put("start", anyFix?.let { f -> JSONObject().put("lat", f.lat).put("lon", f.lon)
                                        .put("accuracyM", f.accuracyM.toDouble()).put("gps", f.gps && f.freshGps()) } ?: JSONObject.NULL),
                                camera360 = if (!cam360) null
                                    else if (thetaOn) io.github.tewnut.poicapture.core.ThetaRide.start(thetaEveryM)
                                    else io.github.tewnut.poicapture.core.Camera360.empty())
                            // the camera's Wi-Fi stays for the ride (released when it ends); not connected yet: the ride
                            // screen connects
                            io.github.tewnut.poicapture.capture.ThetaLink.forRide = cam360 && thetaOn
                            CaptureService.store(ctx).create(s)
                            Prefs.setStartedRide(ctx)   // next time the choices come folded into one line
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
                }   // the sheet's column
            }
        }
    }
}

/** The sheet's resting heights: the street and Start only, half the screen, the choices' own height (or the screen). */
private enum class SheetAnchor { MIN, PARTIAL, MAX }
private val SHEET_HANDLE = 22.dp
private const val SHEET_PARTIAL = 0.5f
/** A throw faster than this goes to the next height in its direction. */
private val SHEET_FLING = 400.dp

/** The choices folded: "Quét đường · Biển báo · Kẹp cố định" and Đổi, which opens them. */
@Composable
private fun ChoiceSummary(parts: List<SetupSummary.Part>, onChange: () -> Unit) {
    val label = parts.map { p ->
        stringResource(when (p) {
            SetupSummary.Part.SWEEP -> R.string.setup_sweep_short
            SetupSummary.Part.PLACE -> R.string.setup_place_short
            SetupSummary.Part.INDOOR -> R.string.setup_indoor_short
            SetupSummary.Part.SHOPS -> R.string.setup_target_shops_short
            SetupSummary.Part.ADDRESSES -> R.string.setup_target_addresses_short
            SetupSummary.Part.SIGNS -> R.string.setup_target_signs_short
            SetupSummary.Part.MOUNT -> R.string.setup_holding_mount_short
            SetupSummary.Part.HAND -> R.string.setup_holding_hand
            SetupSummary.Part.CAM360 -> R.string.setup_camera360_choice
            SetupSummary.Part.THETA -> R.string.theta_name
        })
    }.joinToString(" · ")
    Row(Modifier.fillMaxWidth().background(Palette.GreenLight.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
        .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Palette.Ink, maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        TextButton(onClick = onChange) { Text(stringResource(R.string.setup_change), fontWeight = FontWeight.Bold) }
    }
}

/** GPS on one short line with a coloured dot (was a pill on the map): above Start, next to the street. */
@Composable
private fun GpsState(loc: LocationState, live: LiveLocation?, maxAccuracyM: Double, modifier: Modifier = Modifier) {
    val amber = Color(0xFFFEC84B)
    val green = Color(0xFF47CD89)
    val (text, dot) = when (loc.status) {
        LocStatus.NO_PERMISSION -> stringResource(R.string.setup_loc_no_permission) to Palette.Red
        LocStatus.APPROXIMATE_ONLY -> stringResource(R.string.setup_loc_approximate) to Palette.Red
        LocStatus.DISABLED -> stringResource(R.string.setup_loc_disabled) to Palette.Red
        LocStatus.SEARCHING -> when {
            loc.satsSeen > 0 -> stringResource(R.string.setup_loc_searching_sats, loc.satsUsed, loc.satsSeen)
            loc.approx != null -> stringResource(R.string.setup_loc_network, loc.approx!!.accuracyM.roundToInt())
            else -> stringResource(R.string.setup_loc_searching)
        } to amber
        LocStatus.OK -> {
            val acc = live?.accuracyM ?: 0f
            if (acc <= maxAccuracyM) stringResource(R.string.setup_gps_good, acc.roundToInt()) to green
            else stringResource(R.string.setup_gps_weak, acc.roundToInt()) to amber
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(9.dp).background(dot, androidx.compose.foundation.shape.CircleShape))
        Text(text + (if (live?.simulated == true) " · " + stringResource(R.string.setup_simulated) else ""),
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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

/**
 * Map centred on the rider: blue dot with an accuracy circle, re-centre on request. [insetTop] / [insetBottom]: px of the
 * view hidden under the top bar / the sheet; the rider is kept in the middle of the part in between.
 */
@Composable
private fun LiveMap(tileUrl: String, me: LiveLocation?, mine: CoverLayer,
                    tasks: List<io.github.tewnut.poicapture.core.FieldTask>, onTask: (String) -> Unit, modifier: Modifier,
                    building: Building? = null, onTap: ((LatLon) -> Unit)? = null, center: Array<LatLon?>? = null,
                    insetTop: Int = 0, insetBottom: Int = 0) {
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
        // the map's centre drawn in the middle of the part in view, not of the whole view
        val offY = (insetTop - insetBottom) / 2
        if (m.mapCenterOffsetY != offY) m.setMapCenterOffset(0, offY)
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
                val dx = px.x - m.width / 2f; val dy = px.y - (m.height / 2f + offY)
                val inView = (m.height - insetTop - insetBottom).coerceAtLeast(m.height / 4)
                if (kotlin.math.abs(dx) > m.width / 4f || kotlin.math.abs(dy) > inView / 4f) m.controller.animateTo(here)
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
            Section(stringResource(R.string.setup_camera360_short))
            Item("📷", stringResource(R.string.setup_camera360_short), stringResource(R.string.setup_camera360_info))
            Section(stringResource(R.string.setup_info_both))
            Item("🛣️", stringResource(R.string.setup_you_are_on), stringResource(R.string.setup_road_auto_hint))
            Item("🛰️", stringResource(R.string.setup_info_gps_title), stringResource(R.string.setup_info_gps))
        }
    }
}
