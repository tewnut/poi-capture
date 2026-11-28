package io.github.tewnut.poicapture.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One OpenStreetMap way as the iPhone app loads it from Overpass; the same JSON as the Android app's Way (road.ways in
 * the manifest, one line of ways.jsonl).
 */
class RideWay(val id: Long, val name: String, val ref: String, val highway: String, val oneway: String,
              val geometry: List<LatLon>, val piece: Int = 0) {
    /** "ref · name", null when the way has neither (Android roadNameOf). */
    val named: String? get() = listOf(ref, name).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { null }

    /** What a photo's `roadName` says (Android roadLabel): the name, else "Unnamed road (<highway>)". */
    val label: String get() = named ?: "${SessionNaming.UNNAMED_PREFIX} ($highway)"

    val key: String get() = "$id:$piece"

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("id", id); put("name", name); put("ref", ref); put("highway", highway); put("oneway", oneway)
        if (piece != 0) put("piece", piece)
        putJsonArray("geometry") { geometry.forEach { p -> add(buildJsonObject { put("lat", p.lat); put("lon", p.lon) }) } }
    }

    fun toJson(): String = toJsonObject().toString()

    companion object {
        fun fromJson(o: JsonObject): RideWay? = runCatching {
            val g = o["geometry"]?.jsonArray ?: emptyList()
            RideWay(o["id"]!!.jsonPrimitive.longOrNull!!, o.text("name"), o.text("ref"), o.text("highway"), o.text("oneway"),
                g.mapNotNull { e -> e.jsonObject.let { p -> p.num("lat")?.let { la -> p.num("lon")?.let { lo -> LatLon(la, lo) } } } },
                o["piece"]?.jsonPrimitive?.intOrNull ?: 0)
        }.getOrNull()

        fun fromJson(s: String): RideWay? = runCatching { fromJson(Json.parseToJsonElement(s).jsonObject) }.getOrNull()
    }
}

/** A way matched to a position: as the Android app's RoadMatch. */
class RideMatch(val way: RideWay, val point: LatLon, val distanceM: Double, val segmentBearing: Double)

/**
 * Road data around the rider, as the Android app loads it in auto mode (core Road.kt: RoadCells, Overpass,
 * matchRoad): fixed grid cells of 0.005° (≈ 550 m), one Overpass query per cell, the nearest way to a fix with ways
 * along the travel direction winning over nearer cross streets.
 */
object RideRoads {
    const val CELL_DEG = 0.005
    private const val SKIP_AREA = "footway|path|steps|cycleway|pedestrian|bridleway|corridor|elevator|platform|proposed|" +
        "construction|service|track|bus_guideway|raceway|abandoned|razed"

    /** The configured server first, then public mirrors (the main instance is often busy). */
    val overpassServers: List<String> = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    )

    /** "x,y" of the cell holding a position. */
    fun cellOf(lat: Double, lon: Double): String = "${floor(lon / CELL_DEG).toInt()},${floor(lat / CELL_DEG).toInt()}"

    private fun xy(cell: String): Pair<Int, Int>? {
        val p = cell.split(',')
        if (p.size != 2) return null
        return (p[0].toIntOrNull() ?: return null) to (p[1].toIntOrNull() ?: return null)
    }

    /** The cell of the position plus neighbours closer than [marginM], nearest first (Android RoadCells.around). */
    fun cellsAround(lat: Double, lon: Double, marginM: Double): List<String> {
        val cx = floor(lon / CELL_DEG).toInt()
        val cy = floor(lat / CELL_DEG).toInt()
        val mLat = 111_320.0
        val mLon = 111_320.0 * cos(lat * PI / 180)
        val out = ArrayList<Pair<String, Double>>()
        for (dx in -1..1) for (dy in -1..1) {
            val x = cx + dx; val y = cy + dy
            val south = y * CELL_DEG; val north = (y + 1) * CELL_DEG; val west = x * CELL_DEG; val east = (x + 1) * CELL_DEG
            val ddy = maxOf(south - lat, 0.0, lat - north) * mLat
            val ddx = maxOf(west - lon, 0.0, lon - east) * mLon
            val d = sqrt(ddx * ddx + ddy * ddy)
            if (d <= marginM) out += "$x,$y" to d
        }
        return out.sortedBy { it.second }.map { it.first }
    }

    /** The Overpass query for one cell: the same text as the Android app's Overpass.cellQuery. */
    fun cellQuery(cell: String): String? {
        val (x, y) = xy(cell) ?: return null
        return "[out:json][timeout:25];way(${fixed6(y * CELL_DEG)},${fixed6(x * CELL_DEG)},${fixed6((y + 1) * CELL_DEG)}," +
            "${fixed6((x + 1) * CELL_DEG)})[highway][highway!~\"$SKIP_AREA\"];out tags geom;"
    }

    /** The ways of an Overpass answer; null when it is not one (a busy server's HTML page, a runtime error remark). */
    fun parseWays(body: String): List<RideWay>? {
        val o = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val remark = o.text("remark")
        if (remark.contains("runtime error", ignoreCase = true)) return null
        val els = runCatching { o["elements"]?.jsonArray }.getOrNull() ?: return emptyList()
        return els.mapNotNull { e ->
            val w = runCatching { e.jsonObject }.getOrNull() ?: return@mapNotNull null
            if (w.text("type") != "way") return@mapNotNull null
            val id = w["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val tags = runCatching { w["tags"]?.jsonObject }.getOrNull() ?: JsonObject(emptyMap())
            val g = runCatching { w["geometry"]?.jsonArray }.getOrNull() ?: emptyList()
            RideWay(id, tags.text("name"), tags.text("ref"), tags.text("highway"), tags.text("oneway"),
                g.mapNotNull { p -> runCatching { p.jsonObject }.getOrNull()?.let { q -> q.num("lat")?.let { la -> q.num("lon")?.let { lo -> LatLon(la, lo) } } } })
        }
    }

    /**
     * Nearest way to a position, with the segment bearing there. With a travel bearing, ways running along it (or
     * against it) within [alignWithinM] more than the nearest win over nearer cross streets (Android matchRoad).
     */
    fun match(ways: Collection<RideWay>, p: LatLon, travelBearing: Double?, alignWithinM: Double = 30.0): RideMatch? {
        val all = ways.mapNotNull { w -> Geo.nearestOnLine(w.geometry, p)?.let { RideMatch(w, it.point, it.distanceM, it.segmentBearing) } }
        val nearest = all.minByOrNull { it.distanceM } ?: return null
        if (travelBearing == null) return nearest
        val aligned = all.filter {
            it.distanceM <= nearest.distanceM + alignWithinM &&
                minOf(Geo.angleDiff(travelBearing, it.segmentBearing), Geo.angleDiff(travelBearing, it.segmentBearing + 180)) <= 30
        }
        return aligned.minByOrNull { it.distanceM } ?: nearest
    }

    /** "%.6f" as java.util.Formatter writes it with Locale.ROOT. */
    fun fixed6(d: Double): String {
        val v = round(abs(d) * 1_000_000).toLong()
        val sign = if (d < 0 && v != 0L) "-" else ""
        return sign + (v / 1_000_000) + "." + (v % 1_000_000).toString().padStart(6, '0')
    }
}

internal fun JsonObject.text(k: String): String = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull ?: ""
internal fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull
internal fun r1(v: Double) = (v * 10).roundToInt() / 10.0
internal fun r3(v: Double) = (v * 1000).roundToLong() / 1000.0
internal fun numOrNull(v: Double?): JsonElement = if (v == null || v.isNaN()) JsonNull else JsonPrimitive(v)
internal fun strOrNull(v: String?): JsonElement = if (v == null) JsonNull else JsonPrimitive(v)

/** The outcome of a sweep photo once the camera saved it ([SweepRide.completePhoto]). */
class SweepPhotoResult(
    /** the photo's line for items.jsonl (manifest `items[]`) */
    val json: String,
    val blurry: Boolean,
    /** almost no detail (a motorbike passing a metre from the lens): drop the photo, the next one comes sooner */
    val unusable: Boolean,
    /** tell the rider "blurry: slow down" ([BlurCheck.warnRider]) */
    val warnRider: Boolean,
)

/**
 * A street sweep ("Quét đường") as the iPhone app records it: the same rules and per-photo fields as the Android
 * app's CaptureService + FixProcessor (auto road mode), in one place so the iPhone writes rides the PC pipeline reads
 * exactly like Android's (checked against FixProcessor in app/src/test SweepParityTest).
 *
 * - GPS fixes ([onFix]) → speed, travel bearing, the matched road (Overpass cells loaded around the rider), the travel
 *   direction along it, the expected distance to the shop fronts from the road type, and the track.jsonl line;
 * - the camera direction: a phone on a mount gets the median of the compass over the photos ([CompassYaw] of the
 *   Android app, `cameraBearingSource: "compassMount"`), assumed right (90°) until then; held by hand each photo its
 *   own compass bearing (`"compass"`); a signs ride looks ahead (`"forward"`, yaw 0, no facade point, sideOfWay null);
 * - the photo plan (spacing, speed limit) from [CapturePlan] for the target, the facade distance and the lens;
 * - each photo's item ([photoItem], then [completePhoto] with what the camera says) and its blur verdict.
 *
 * Numbers that may be unknown are passed as NaN (not null), so the iPhone side passes plain Doubles.
 */
class SweepRide(target: String, val hand: Boolean, val maxAccuracyM: Double, val offRoadWarnM: Double) {
    val target: SweepTarget = SweepTarget.of(target)
    val signs: Boolean get() = target.byDistance

    // ---------------------------------------------------------------- road data (auto mode)

    private val ways = LinkedHashMap<String, RideWay>()
    private val loaded = HashSet<String>()
    private val loading = HashSet<String>()
    private val newWays = ArrayList<RideWay>()
    private val sources = ArrayList<String>()

    /** Where the road data came from (session.road.sources): "overpass" on the iPhone (no offline packs yet). */
    val roadSources: List<String> get() = sources.toList()
    val waysLoaded: Int get() = ways.size
    /** Some cell around the rider has been loaded (the road can be named). */
    val hasRoadData: Boolean get() = loaded.isNotEmpty()

    /** The cells around the last fix still to load (nearest first); each is then loading until [cellLoaded] / [cellFailed]. */
    fun cellsToLoad(): List<String> {
        val f = fixLatLon ?: return emptyList()
        val todo = RideRoads.cellsAround(f.lat, f.lon, 200.0).filter { it !in loaded && it !in loading }
        loading.addAll(todo)
        return todo
    }

    fun cellQuery(cell: String): String = RideRoads.cellQuery(cell) ?: ""

    /** A cell's Overpass answer arrived. Returns the number of ways in it, -1 when the answer was not usable. */
    fun cellLoaded(cell: String, body: String, source: String): Int {
        loading.remove(cell)
        val list = RideRoads.parseWays(body) ?: return -1
        loaded.add(cell)
        for (w in list) if (ways.put(w.key, w) == null) newWays.add(w)
        if (source !in sources) sources.add(source)
        fixLatLon?.let { rematch(it) }
        return list.size
    }

    fun cellFailed(cell: String) { loading.remove(cell) }

    /** The ways loaded since the last call, as ways.jsonl lines (the manifest carries them in road.ways). */
    fun drainNewWays(): List<String> { val out = newWays.map { it.toJson() }; newWays.clear(); return out }

    // ---------------------------------------------------------------- fixes (Android FixProcessor, auto mode)

    private var fixLatLon: LatLon? = null
    private var fixT = 0L
    private var fixAcc = Double.NaN
    private var fixAlt = Double.NaN
    /** elapsed-time clock of the last fix (ns), the same clock as the photos and imu.csv; -1 = none */
    var fixNanos: Long = -1L; private set
    private var prevLatLon: LatLon? = null
    private var prevT = 0L
    /** speed (m/s, Doppler when the receiver gives one, else from positions); NaN = unknown */
    var speedMps: Double = Double.NaN; private set
    /** travel bearing (degrees); NaN = unknown */
    var travelBearing: Double = Double.NaN; private set
    private var match: RideMatch? = null
    /** the session's travel direction along the first road it matched ("forward" | "backward"), null until moving */
    var travel: String? = null; private set
    /** left / right of travel the camera films: assumed right until the compass says otherwise */
    var sideOfTravel: String = "right"; private set
    /** the session changed (travel decided, camera direction measured): save session.json */
    var changed: Boolean = false

    val hasFix: Boolean get() = fixLatLon != null
    val accuracyM: Double get() = fixAcc
    val gpsGood: Boolean get() = !fixAcc.isNaN() && fixAcc <= maxAccuracyM
    val speedKmh: Double get() = speedMps * 3.6

    /** The matched road's label when the rider is on it (≤ 30 m), else null. */
    val roadName: String? get() = roadWay()?.label

    private fun roadWay(): RideWay? = match?.takeIf { it.distanceM <= 30 }?.way

    /**
     * Feed one fix. [altitudeM], [speedMps], [bearingDeg]: NaN when the receiver gives none. [elapsedNanos]: the
     * elapsed-time clock at the fix (the photos and imu.csv use the same). Returns the fix's track.jsonl line.
     */
    fun onFix(tMs: Long, lat: Double, lon: Double, accuracyM: Double, altitudeM: Double, speedMps: Double, bearingDeg: Double,
              elapsedNanos: Long): String {
        val p = LatLon(lat, lon)
        val pr = prevLatLon
        val moved = pr != null && Geo.distanceM(pr, p) >= 5
        val raw = if (speedMps.isNaN() || speedMps < 0) null else speedMps
        val derived = if (pr != null && tMs > prevT) Geo.distanceM(pr, p) / ((tMs - prevT) / 1000.0) else null
        val spd: Double? = when {
            raw != null && raw > 0.5 -> raw
            moved && derived != null -> derived
            else -> raw ?: derived
        }
        var tb: Double? = if (!bearingDeg.isNaN() && bearingDeg >= 0 && (raw ?: 0.0) > 1) bearingDeg else null
        if (tb == null && moved) tb = Geo.bearing(pr!!, p)
        if (tb == null) tb = travelBearing.takeIf { !it.isNaN() }
        if (pr == null || moved) { prevLatLon = p; prevT = tMs }
        fixLatLon = p; fixT = tMs; fixAcc = accuracyM; fixAlt = altitudeM; fixNanos = elapsedNanos
        this.speedMps = spd ?: Double.NaN
        travelBearing = tb ?: Double.NaN
        rematch(p)
        val m = match
        if (travel == null && m != null && tb != null && (spd ?: 0.0) > 1 && m.distanceM <= offRoadWarnM) {
            travel = directionOn(tb, m.segmentBearing).json
            changed = true
        }
        // another type of road (alley → main road…): the expected distance to the fronts, so the plan, follows
        val hw = roadWay()?.highway
        val d = CapturePlan.facadeForRoad(hw)
        if (d != facadeNow || hw != roadTypeNow) { roadTypeNow = hw; facadeNow = d }
        return buildJsonObject {
            put("t", tMs); put("lat", lat); put("lon", lon); put("acc", accuracyM)
            put("alt", numOrNull(altitudeM)); put("spd", numOrNull(raw)); put("hdg", numOrNull(tb)); put("en", elapsedNanos)
        }.toString()
    }

    private fun rematch(p: LatLon) {
        match = RideRoads.match(ways.values, p, travelBearing.takeIf { !it.isNaN() })
    }

    /** The travel direction along the matched way now, else the session's. */
    private fun travelNow(): String? {
        val m = match
        val tb = travelBearing
        return if (m != null && !tb.isNaN()) directionOn(tb, m.segmentBearing).json else travel
    }

    // ---------------------------------------------------------------- camera direction

    private val compass = ArrayList<Double>()
    /** measured camera yaw to travel (clockwise, right = +90): signs = 0 (forward), mount = compass median; NaN = assumed */
    private var cameraYawDeg: Double = if (this.target.byDistance) 0.0 else Double.NaN
    private var cameraYawSource: String = if (this.target.byDistance) "forward" else "assumed"
    /** session.mount, once the compass median is known (mount only); null before */
    var mountJson: String? = null; private set
    /** "left" | "right" | "front" | "back" of the mount estimate, "" before (live warnings) */
    var mountSide: String = ""; private set

    private val effectiveYawDeg: Double get() = if (!cameraYawDeg.isNaN()) cameraYawDeg else if (sideOfTravel == "right") 90.0 else -90.0

    private fun cameraBearingNow(): Double? {
        val tb = travelBearing.takeIf { !it.isNaN() } ?: return null
        return Geo.norm360(tb + effectiveYawDeg)
    }

    /** A mounted phone's compass at a photo: the median over the ride ([CompassYaw]) sets the camera direction. */
    private fun addCompass(cameraAzimuthDeg: Double?) {
        val tb = travelBearing.takeIf { !it.isNaN() } ?: return
        val spd = speedMps.takeIf { !it.isNaN() } ?: return
        if (cameraAzimuthDeg == null || spd < 2.0) return
        compass += ((cameraAzimuthDeg - tb + 540) % 360) - 180
        if (compass.size > 400) compass.removeAt(0)
        if (compass.size < 15) return
        val sx = compass.sumOf { sin(it * PI / 180) }
        val cx = compass.sumOf { cos(it * PI / 180) }
        val mean = atan2(sx, cx) * 180 / PI
        val rel = compass.map { ((it - mean + 540) % 360) - 180 }.sorted()
        val q1 = rel[rel.size / 4]; val q3 = rel[rel.size * 3 / 4]
        if (q3 - q1 > 25.0) return
        val yaw = ((mean + rel[rel.size / 2] + 540) % 360) - 180
        val side = when { yaw in 45.0..135.0 -> "right"; yaw in -135.0..-45.0 -> "left"; abs(yaw) < 45 -> "front"; else -> "back" }
        val prevYaw = cameraYawDeg
        if (cameraYawSource == "compassMount" && !prevYaw.isNaN() && side == mountSide && abs(prevYaw - yaw) < 5) return
        mountSide = side
        val consistency = (1 - (q3 - q1) / 90).coerceIn(0.0, 1.0)
        mountJson = buildJsonObject {
            put("yawToTravelDeg", r1(yaw)); put("side", side); put("consistency", round(consistency * 100) / 100.0)
            put("events", compass.size); put("source", "compassMount")
        }.toString()
        // the side whose fronts the camera sees, also turned diagonally (MountEstimate.viewSide)
        val view = when { yaw in 20.0..160.0 -> "right"; yaw in -160.0..-20.0 -> "left"; else -> null }
        if (view != null) {
            cameraYawDeg = yaw
            cameraYawSource = "compassMount"
            sideOfTravel = view
        }
        changed = true
    }

    /**
     * Items recorded before the camera direction was known are corrected (Android Remeasure.patch): returns the item
     * with the new bearing, side and facade point, or null when nothing changes (hand-held, signs, already measured).
     */
    fun remeasure(itemJson: String): String? {
        if (hand || signs || cameraYawSource != "compassMount") return null
        val o = runCatching { Json.parseToJsonElement(itemJson).jsonObject }.getOrNull() ?: return null
        if (o.text("cameraBearingSource") != "assumed") return null
        val m = o.toMutableMap()
        val tb = o.num("travelBearing")
        val cb = tb?.let { Geo.norm360(it + cameraYawDeg) }
        m["cameraBearing"] = numOrNull(cb?.let(::r1))
        m["cameraYawToTravelDeg"] = JsonPrimitive(r1(cameraYawDeg))
        m["cameraBearingSource"] = JsonPrimitive("compassMount")
        m["sideOfTravel"] = JsonPrimitive(sideOfTravel)
        val tr = o.text("travel").takeIf { it == "forward" || it == "backward" }
        m["sideOfWay"] = strOrNull(tr?.let { wayside(sideOfTravel, it) })
        val la = o.num("lat"); val lo = o.num("lon"); val fd = o.num("facadeDistanceM") ?: facadeNow
        if (la != null && lo != null && cb != null) facade(LatLon(la, lo), cb, fd).forEach { (k, v) -> m[k] = v }
        return JsonObject(m).toString()
    }

    // ---------------------------------------------------------------- plan

    /** camera → shop-front distance expected from the road being ridden (CapturePlan.facadeForRoad) */
    var facadeNow: Double = CapturePlan.UNKNOWN_ROAD_FACADE_M; private set
    private var roadTypeNow: String? = null
    private var hFovLandscape: Double = Double.NaN
    private var hFovPortrait: Double = Double.NaN
    /** the long edge photos are taken at (px) */
    var photoLongEdge: Int = 2560
    /** the phone is held upright: the photo's horizontal is the frame's short side */
    var portrait: Boolean = false

    /** The camera's horizontal fields of view (landscape frame, upright frame), degrees; NaN = unknown. */
    fun useLens(hFovLandscapeDeg: Double, hFovPortraitDeg: Double) { hFovLandscape = hFovLandscapeDeg; hFovPortrait = hFovPortraitDeg }

    /** Photo spacing and speed for the frame as held now (Android currentPlan). */
    fun plan(): CapturePlan {
        val fov = (if (portrait) hFovPortrait else hFovLandscape).takeIf { !it.isNaN() }
            ?: if (portrait) CapturePlan.DEFAULT_HFOV_PORTRAIT else null
        return CapturePlan.of(facadeNow, fov, if (portrait) photoLongEdge * 9 / 16 else photoLongEdge, target)
    }

    val maxSpeedKmh: Int get() = plan().maxSpeedKmh
    val photoEveryM: Double get() = plan().photoEveryM
    val coverageM: Double get() = plan().coverageM
    /** frame width in metres of facade per metre of distance (OverlapTrigger.floor) */
    val coveragePerM: Double get() = plan().let { if (it.facadeDistanceM > 0) it.coverageM / it.facadeDistanceM else 0.0 }

    /** session.plan (Android CapturePlan.toJson). */
    fun planJson(): String = plan().let { p ->
        buildJsonObject {
            put("facadeDistanceM", p.facadeDistanceM); put("hFovDeg", p.hFovDeg); put("coverageM", r1(p.coverageM))
            put("photoEveryM", r1(p.photoEveryM)); put("maxSpeedKmh", p.maxSpeedKmh)
            put("blurLimitKmh", p.blurLimitKmh); put("rateLimitKmh", p.rateLimitKmh)
            put("target", target.json); put("trigger", if (target.byDistance) "distance" else "slide")
        }.toString()
    }

    /**
     * Live warnings as codes (the app words them): "fast" (over the plan's speed), "weakGps", "portrait" (a sweep held
     * upright), "mountFront" / "mountBack" (a shops ride's camera looks along the road), "mountSide" (a signs ride's
     * camera looks sideways).
     */
    fun warnings(): List<String> = buildList {
        if (!speedMps.isNaN() && speedKmh > maxSpeedKmh) add("fast")
        if (!fixAcc.isNaN() && fixAcc > maxAccuracyM) add("weakGps")
        if (portrait) add("portrait")
        if (signs) {
            if (mountSide == "left" || mountSide == "right") add("mountSide")
            if (mountSide == "back") add("mountBack")
        } else {
            if (mountSide == "front") add("mountFront")
            if (mountSide == "back") add("mountBack")
        }
    }

    // ---------------------------------------------------------------- photos

    /**
     * A photo is being taken: its item with everything known before the camera answers (Android FixProcessor.itemMeta
     * + CaptureService.takePhoto). [gpsFresh]: the last fix is at most CaptureRules.GPS_MAX_AGE_MS old (else the photo
     * keeps it and says how old: gpsStale, gpsAgeS). [motionJson]: `motion` (gyroMaxRadS, accelStdMs2,
     * cameraAzimuthDeg, cameraElevationDeg), "" = none. [overlapJson]: what timed an auto photo (SweepShotTimer.shot),
     * "" = none. [targetRotationDeg]: 0 upright, 90 / 270 landscape.
     */
    fun photoItem(id: String, tMs: Long, elapsedNanos: Long, trigger: String, gpsFresh: Boolean, motionJson: String,
                  overlapJson: String, targetRotationDeg: Int): String {
        val motion = if (motionJson.isBlank()) null else runCatching { Json.parseToJsonElement(motionJson).jsonObject }.getOrNull()
        val azimuth = motion?.num("cameraAzimuthDeg")
        if (!hand && !signs) addCompass(azimuth)     // a mounted phone: the compass at each photo feeds the mount estimate
        val f = fixLatLon
        val m = match?.takeIf { it.distanceM <= offRoadWarnM }
        val tn = if (m != null) travelNow() else null
        val tb = travelBearing.takeIf { !it.isNaN() }
        var cb = cameraBearingNow()
        var yaw: Double? = effectiveYawDeg
        var source = if (!cameraYawDeg.isNaN()) cameraYawSource else "assumed"
        var side = sideOfTravel
        var wayside: String? = tn?.let { wayside(side, it) }
        val facadeD: Double? = if (signs) null else facadeNow
        if (hand && azimuth != null) {
            // held by a passenger: this photo's own direction (Android HandBearing, compass only on the iPhone)
            val b = Geo.norm360(azimuth)
            cb = b
            source = "compass"
            if (tb != null) {
                val y = ((b - tb + 540) % 360) - 180
                yaw = y
                val s = when {
                    y >= 20.0 && y <= 160.0 -> "right"
                    y <= -20.0 && y >= -160.0 -> "left"
                    else -> null
                }
                side = s ?: if (abs(y) < 90) "front" else "back"
                val travelHere = m?.let { travelNow() }
                wayside = if (s != null && travelHere != null) wayside(s, travelHere) else null
            } else yaw = null
        }
        if (signs) wayside = null                    // looking ahead: the signs on both sides and overhead
        val o = buildJsonObject {
            put("id", id); put("kind", "photo")
            put("tMs", tMs); put("t", IsoTime.format(tMs)); put("elapsedNanos", elapsedNanos)
            put("fixT", if (f != null) JsonPrimitive(fixT) else JsonNull)
            put("lat", numOrNull(f?.lat)); put("lon", numOrNull(f?.lon))
            put("accuracyM", numOrNull(if (f != null) fixAcc else null)); put("altitudeM", numOrNull(if (f != null) fixAlt else null))
            put("speedMps", numOrNull(speedMps))
            put("travelBearing", numOrNull(tb?.let(::r1)))
            put("cameraBearing", numOrNull(cb?.let(::r1)))
            put("cameraYawToTravelDeg", numOrNull(yaw?.let(::r1)))
            put("cameraBearingSource", source)
            put("wayId", if (m != null) JsonPrimitive(m.way.id) else JsonNull)
            put("roadName", strOrNull(m?.way?.label))
            put("distanceToRoadM", numOrNull(m?.distanceM?.let(::r1)))
            put("segmentBearing", if (m != null) JsonPrimitive(m.segmentBearing.roundToInt()) else JsonNull)
            put("travel", strOrNull(tn))
            put("sideOfTravel", side)
            put("sideOfWay", strOrNull(wayside))
            if (f != null && cb != null && facadeD != null) facade(f, cb, facadeD).forEach { (k, v) -> put(k, v) }
            put("trigger", trigger)
            if (!gpsFresh) {
                put("gpsStale", true)
                put("gpsAgeS", if (fixNanos >= 0) JsonPrimitive(round((elapsedNanos - fixNanos) / 1e8) / 10.0) else JsonNull)
            }
            if (motion != null) put("motion", motion)
            // orientation (Android orientationMeta)
            put("orientation", if (portrait) "portrait" else "landscape")
            put("facadeRoadType", strOrNull(roadTypeNow))
            put("targetRotationDeg", targetRotationDeg)
            put("hFovDeg", plan().hFovDeg)
            if (overlapJson.isNotBlank()) runCatching { Json.parseToJsonElement(overlapJson) }.getOrNull()?.let { put("overlap", it) }
        }
        return o.toString()
    }

    private val sharpRecent = ArrayDeque<Double>()
    private val verdicts = ArrayDeque<BlurCheck.Verdict>()

    /**
     * The camera saved the photo: its size and exposure from the image, its size on disk, and the blur check (Android
     * CaptureService.blurOf): motion smear from speed × exposure against the target's budget, and edge detail
     * ([sharpness], BlurCheck.sharpness of a small copy; NaN = not measured) against the ride's recent photos.
     * Unknown numbers: [exposureS] / [focalLengthMm] ≤ 0, [iso] ≤ 0.
     */
    fun completePhoto(itemJson: String, width: Int, height: Int, exifOrientation: Int, exposureS: Double, iso: Int,
                      focalLengthMm: Double, bytes: Long, sharpness: Double, manualShutter: Boolean): SweepPhotoResult {
        val o = runCatching { Json.parseToJsonElement(itemJson).jsonObject }.getOrElse { JsonObject(emptyMap()) }
        val w = if (width > 0) width else 2560
        val exposure = exposureS.takeIf { it > 0 }
        val speed = o.num("speedMps")
        val hFov = o.num("hFovDeg") ?: CapturePlan.DEFAULT_HFOV
        val motion = if (exposure != null && speed != null) BlurCheck.motionPx(speed, exposure, hFov, w, facadeNow) else null
        val maxMotion = BlurCheck.maxMotionPx(w, hFov, facadeNow, target)
        val sharp = sharpness.takeIf { !it.isNaN() && it >= 0 }
        val unusable = BlurCheck.unusable(sharp, sharpRecent.toList())
        val v = BlurCheck.judge(motion, maxMotion, sharp, sharpRecent.toList())
        if (sharp != null) { sharpRecent.addLast(sharp); while (sharpRecent.size > 15) sharpRecent.removeFirst() }
        verdicts.addLast(v); while (verdicts.size > BlurCheck.SOFT_RUN) verdicts.removeFirst()
        val warn = BlurCheck.warnRider(verdicts.toList())
        val smear = if (exposure != null && speed != null) BlurCheck.smearMm(speed, exposure) else null
        val m = o.toMutableMap()
        m["width"] = JsonPrimitive(width); m["height"] = JsonPrimitive(height)
        m["exifOrientation"] = JsonPrimitive(exifOrientation)
        m["exposureS"] = numOrNull(exposure)
        m["iso"] = if (iso > 0) JsonPrimitive(iso) else JsonNull
        m["focalLengthMm"] = numOrNull(focalLengthMm.takeIf { it > 0 })
        m["bytes"] = JsonPrimitive(bytes)
        m["blur"] = buildJsonObject {
            put("motionPx", numOrNull(motion?.let(::r1)))
            put("maxMotionPx", r1(maxMotion))
            put("smearMm", numOrNull(smear?.let(::r1)))
            put("cause", when { v.motion -> JsonPrimitive("motion"); v.soft -> JsonPrimitive("soft"); else -> JsonNull })
            put("sharpness", if (sharp != null) JsonPrimitive(sharp.roundToLong()) else JsonNull)
            put("manualShutter", manualShutter)
            put("blurry", v.blurry)
            put("unusable", unusable)
        }
        return SweepPhotoResult(JsonObject(m).toString(), v.blurry, unusable, warn)
    }

    private fun wayside(sideOfTravel: String, travel: String): String =
        if (travel == "forward") sideOfTravel else if (sideOfTravel == "right") "left" else "right"

    private fun facade(at: LatLon, cameraBearing: Double, distanceM: Double): Map<String, JsonElement> {
        val p = Geo.destination(at, cameraBearing, distanceM)
        return mapOf("facadeLat" to JsonPrimitive(p.lat), "facadeLon" to JsonPrimitive(p.lon),
            "facadeDistanceM" to JsonPrimitive(distanceM), "facadeSource" to JsonPrimitive("roadType"))
    }
}
