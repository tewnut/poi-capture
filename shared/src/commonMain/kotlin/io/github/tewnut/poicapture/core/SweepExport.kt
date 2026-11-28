package io.github.tewnut.poicapture.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToLong

/**
 * A street sweep's files as the Android app writes them (core Session.toJson, Exporter, Tus.kt
 * sessionUploadMetadata), for the iPhone app: session.json (kept as a JSON text the app updates with [withText],
 * [withTime], [withJson], [withRide]), the export's manifest.json and track.geojson, the zip's name, and the upload
 * metadata. Checked against the Android code in app/src/test SweepParityTest.
 */
object SweepExport {
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = " " }

    private fun obj(s: String): JsonObject = runCatching { Json.parseToJsonElement(s).jsonObject }.getOrElse { JsonObject(emptyMap()) }

    /**
     * A new street sweep's session.json: auto road mode, the right-hand row of buildings until the camera direction
     * is known, photos by themselves. [holding] "mount" | "hand"; [target] "shops" | "addresses" | "signs";
     * [deviceJson]: session.device (a JSON object).
     */
    fun sessionDoc(id: String, createdMs: Long, contributor: String, holding: String, target: String, photoLongEdge: Int,
                   deviceJson: String): String {
        val t = SweepTarget.of(target)
        val plan = CapturePlan.of(CapturePlan.UNKNOWN_ROAD_FACADE_M, null, photoLongEdge, t)
        return buildJsonObject {
            put("id", id); put("schema", SCHEMA); put("kind", "sweep"); put("createdAt", IsoTime.format(createdMs))
            put("startedAt", JsonNull); put("endedAt", JsonNull)
            put("contributor", contributor); put("email", ""); put("note", ""); put("name", JsonNull)
            put("vehicle", "unspecified"); put("mode", "photo-auto"); put("holding", if (holding == "hand") "hand" else "mount")
            put("sideOfTravel", "right"); put("target", t.json)
            put("travel", JsonNull); put("sideOfWay", JsonNull)
            putJsonObject("road") {
                put("label", "Auto (GPS)"); put("name", ""); put("ref", ""); put("highway", ""); put("primaryWayId", JsonNull)
                put("mode", "auto"); putJsonArray("sources") {}; putJsonArray("ways") {}
            }
            putJsonObject("settings") {
                put("photoEveryM", r1(plan.photoEveryM)); put("maxSpeedKmh", plan.maxSpeedKmh)
                put("facadeDistanceM", CapturePlan.UNKNOWN_ROAD_FACADE_M); put("maxAccuracyM", CaptureRules.MAX_ACCURACY_M)
                put("mountOffsetDeg", 0.0); put("photoLongEdge", photoLongEdge)
                putJsonObject("all") {
                    put("tileUrl", "https://tile.openstreetmap.org/{z}/{x}/{y}.png"); put("overpassUrl", RideRoads.overpassServers[0])
                    put("maxAccuracyM", CaptureRules.MAX_ACCURACY_M); put("offRoadWarnM", 60.0); put("photoLongEdge", photoLongEdge)
                    put("jpegQuality", 92); put("mountOffsetDeg", 0.0); put("facadeDistanceM", CapturePlan.UNKNOWN_ROAD_FACADE_M)
                }
            }
            put("device", runCatching { Json.parseToJsonElement(deviceJson) }.getOrElse { JsonObject(emptyMap()) })
            put("camera", JsonNull); put("counts", JsonNull); put("gnss", JsonNull); put("exportedAt", JsonNull)
            put("plan", JsonNull); put("mount", JsonNull); put("photoRoles", JsonNull)
        }.toString()
    }

    /** session.json with [key] set to a text ([value] null = JSON null). */
    fun withText(session: String, key: String, value: String?): String =
        JsonObject(obj(session) + (key to strOrNull(value))).toString()

    /** session.json with [key] set to the ISO time of [ms] (≤ 0 = JSON null), e.g. startedAt, endedAt, exportedAt. */
    fun withTime(session: String, key: String, ms: Long): String =
        JsonObject(obj(session) + (key to (if (ms > 0) JsonPrimitive(IsoTime.format(ms)) else JsonNull))).toString()

    /** session.json with [key] set to a JSON value ([json] blank or unreadable = JSON null), e.g. camera, counts. */
    fun withJson(session: String, key: String, json: String?): String {
        val v = if (json.isNullOrBlank()) JsonNull else runCatching { Json.parseToJsonElement(json) }.getOrElse { JsonNull }
        return JsonObject(obj(session) + (key to v)).toString()
    }

    /** A text field of session.json ("" when missing or null). */
    fun readText(session: String, key: String): String = obj(session).text(key)

    /** What the ride knows now: side, travel, camera direction, plan, road data sources and the plan's settings. */
    fun withRide(session: String, ride: SweepRide): String {
        val o = obj(session).toMutableMap()
        o["sideOfTravel"] = JsonPrimitive(ride.sideOfTravel)
        o["travel"] = strOrNull(ride.travel)
        o["sideOfWay"] = strOrNull(ride.travel?.let { if (it == "forward") ride.sideOfTravel else if (ride.sideOfTravel == "right") "left" else "right" })
        val plan = Json.parseToJsonElement(ride.planJson()).jsonObject
        o["plan"] = plan
        o["mount"] = ride.mountJson?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } ?: JsonNull
        (o["settings"] as? JsonObject)?.let { st ->
            o["settings"] = JsonObject(st + mapOf("photoEveryM" to plan["photoEveryM"]!!, "maxSpeedKmh" to plan["maxSpeedKmh"]!!))
        }
        (o["road"] as? JsonObject)?.let { r ->
            o["road"] = JsonObject(r + ("sources" to JsonArray(ride.roadSources.map { JsonPrimitive(it) })))
        }
        return JsonObject(o).toString()
    }

    /**
     * The export's manifest.json (Android Exporter.manifest for a sweep): the session with the ways loaded during the
     * ride ([ways], ways.jsonl lines) in road.ways, the photo items in time order each with its `file`, no places
     * or marks, the privacy note, and which side files the zip holds.
     */
    fun manifest(session: String, items: List<String>, ways: List<String>, trackPoints: Int, imu: Boolean, frames: Boolean,
                 exportedMs: Long): String {
        val s = obj(session).toMutableMap()
        (s["road"] as? JsonObject)?.let { r ->
            val seen = HashSet<String>()
            val all = ArrayList<JsonElement>()
            val old = runCatching { r["ways"]?.jsonArray }.getOrNull() ?: JsonArray(emptyList())
            for (w in old + ways.mapNotNull { runCatching { Json.parseToJsonElement(it) }.getOrNull() }) {
                val wo = runCatching { w.jsonObject }.getOrNull() ?: continue
                val key = "${wo["id"]}:${(wo["piece"] as? JsonPrimitive)?.longOrNull ?: 0}"
                if (seen.add(key)) all.add(wo)
            }
            s["road"] = JsonObject(r + ("ways" to JsonArray(all)))
        }
        val photos = items.mapNotNull { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            .filter { it.text("kind") == "photo" && it.text("id").isNotEmpty() }
            .distinctBy { it.text("id") }
            .sortedBy { (it["tMs"] as? JsonPrimitive)?.longOrNull ?: 0L }
            .map { JsonObject(it + ("file" to JsonPrimitive("photos/${it.text("id")}.jpg"))) }
        val kind = (s["kind"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: "sweep"
        val m = buildJsonObject {
            put("schema", SCHEMA); put("kind", kind); put("exportedAt", IsoTime.format(exportedMs))
            put("session", JsonObject(s))
            put("items", JsonArray(photos))
            put("pois", JsonArray(emptyList())); put("marks", JsonArray(emptyList()))
            // nothing is blurred on the phone: faces and licence plates must be blurred before anything is published
            putJsonObject("privacy") { put("blurred", false); putJsonArray("required") { add(JsonPrimitive("faces")); add(JsonPrimitive("plates")) } }
            put("trackFile", "track.geojson")
            put("trackPoints", trackPoints)
            put("gnssRawFile", JsonNull)
            put("imuFile", if (imu) JsonPrimitive("imu.csv") else JsonNull)
            put("framesFile", if (frames) JsonPrimitive("frames.csv") else JsonNull)
            put("indoorFile", JsonNull); put("plansFile", JsonNull)
        }
        return pretty.encodeToString(JsonObject.serializer(), m)
    }

    /** track.jsonl lines → track.geojson (Android Exporter.writeTrackGeoJson): one LineString, then one Point per fix. */
    fun trackGeoJson(lines: List<String>): String {
        val fixes = lines.mapNotNull { l -> runCatching { Json.parseToJsonElement(l).jsonObject }.getOrNull() }
            .filter { it.num("lat") != null && it.num("lon") != null }
        val sb = StringBuilder()
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
        sb.append("{\"type\":\"Feature\",\"properties\":{\"kind\":\"track\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
        fixes.forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            sb.append('[').append(p["lon"].toString()).append(',').append(p["lat"].toString()).append(']')
        }
        sb.append("]}}")
        for (p in fixes) {
            val t = (p["t"] as? JsonPrimitive)?.longOrNull ?: 0L
            val f = buildJsonObject {
                put("type", "Feature")
                putJsonObject("properties") {
                    put("t", t); put("iso", IsoTime.format(t))
                    // as org.json: a key missing from the fix is left out, a null one stays null
                    for ((from, to) in listOf("acc" to "accuracyM", "spd" to "speedMps", "hdg" to "headingDeg", "alt" to "altitudeM",
                            "en" to "elapsedNanos")) p[from]?.let { put(to, it) }
                }
                putJsonObject("geometry") { put("type", "Point"); put("coordinates", buildJsonArray { add(p["lon"]!!); add(p["lat"]!!) }) }
            }
            sb.append(',').append(f.toString())
        }
        sb.append("]}")
        return sb.toString()
    }

    /**
     * The zip's name (Android Exporter.fileName): `poi-capture_auto_<side>_<yyyyMMddHHmm UTC>.zip` for a sweep,
     * `poi-capture_place_<…>.zip` for a Place capture.
     */
    fun fileName(session: String): String {
        val o = obj(session)
        val kind = o.text("kind").ifEmpty { "sweep" }
        val start = o.text("startedAt").ifEmpty { o.text("createdAt") }
        val digits = start.filter { it.isDigit() }
        val stamp = if (digits.length >= 12) digits.substring(0, 12) else "start"
        return if (kind == "poi") "poi-capture_place_$stamp.zip"
        else "poi-capture_auto_${o.text("sideOfTravel").ifEmpty { "right" }}_$stamp.zip"
    }

    /**
     * The ride's name "{street} {stamp}" (Android TrackStats.sessionName): the named street most photos were taken
     * on; with none, the nearest big named road to the photos ([ways], ways.jsonl lines) within 500 m; else [fallback].
     */
    fun sessionName(items: List<String>, ways: List<String>, stamp: String, fallback: String): String {
        val objs = items.mapNotNull { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val streets = objs.map { it.text("roadName") }
        val nearby = if (streets.none { SessionNaming.isNamed(it) }) nearestBigRoad(objs, ways.mapNotNull { RideWay.fromJson(it) }) else null
        return SessionNaming.name(streets, stamp, fallback, nearby)
    }

    private fun nearestBigRoad(items: List<JsonObject>, ways: List<RideWay>): String? {
        val pts = items.mapNotNull { i -> i.num("lat")?.let { la -> i.num("lon")?.let { lo -> LatLon(la, lo) } } }
        if (pts.isEmpty()) return null
        val c = LatLon(pts.map { it.lat }.average(), pts.map { it.lon }.average())
        return ways.mapNotNull { w ->
            val name = w.named ?: return@mapNotNull null
            val d = distanceToLineM(c, w.geometry)
            if (d > 500) null else name to d / roadWeight(w.highway)
        }.minByOrNull { it.second }?.first
    }

    private fun roadWeight(highway: String): Double = when (highway.removeSuffix("_link")) {
        "motorway", "trunk" -> 3.0
        "primary" -> 2.5
        "secondary" -> 2.0
        "tertiary" -> 1.5
        else -> 1.0
    }

    private fun distanceToLineM(p: LatLon, line: List<LatLon>): Double {
        val kx = 111_320.0 * cos(p.lat * PI / 180); val ky = 110_540.0
        var best = Double.MAX_VALUE
        for (k in line.indices) {
            val a = line[k]
            val ax = (a.lon - p.lon) * kx; val ay = (a.lat - p.lat) * ky
            if (k == line.size - 1) { best = minOf(best, hypot(ax, ay)); break }
            val b = line[k + 1]
            val bx = (b.lon - p.lon) * kx; val by = (b.lat - p.lat) * ky
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            best = minOf(best, hypot(ax + t * dx, ay + t * dy))
        }
        return best
    }

    /**
     * The upload's metadata (Android sessionUploadMetadata) from the export's [manifest] (a sweep's or a Place
     * capture's): filename, filetype, type, sessionId, name, kind, target (sweeps), createdAt, startedAt, endedAt,
     * photoCount, photos, and from the track ([trackLines], track.jsonl; none for a Place capture) distanceM and the
     * encoded polyline `track`, and `street`, the ride's main roads. Keys whose value is unknown are left out.
     */
    fun uploadMetadata(manifest: String, fileName: String, trackLines: List<String>): Map<String, String> {
        val m = obj(manifest)
        val s = (m["session"] as? JsonObject) ?: JsonObject(emptyMap())
        val items = runCatching { m["items"]?.jsonArray?.map { it.jsonObject } }.getOrNull() ?: emptyList()
        val kind = s.text("kind").ifEmpty { "sweep" }
        val photos = items.count { it.text("kind") == "photo" }
        fun t(k: String) = s.text(k).takeIf { it.isNotEmpty() }
        val out = LinkedHashMap<String, String?>()
        out["filename"] = fileName
        out["filetype"] = "application/zip"
        out["type"] = "raw-session"
        out["sessionId"] = t("id")
        out["name"] = t("name")
        out["kind"] = kind
        out["target"] = if (kind == "sweep" || kind == "indoor") SweepTarget.of(t("target")).json else null
        out["createdAt"] = t("createdAt")
        out["startedAt"] = t("startedAt")
        out["endedAt"] = t("endedAt")
        out["photoCount"] = photos.toString()
        out["photos"] = photos.toString()
        if (trackLines.isNotEmpty()) {
            out["distanceM"] = TrackLine.distanceM(trackLines, 30.0).roundToLong().toString()
            out["track"] = TrackLine.capped(TrackLine.points(trackLines, 30.0), 2000, 5.0)
        }
        out["street"] = rideStreets(items.filter { it.text("kind") == "photo" }.map { it.text("roadName") })
        @Suppress("UNCHECKED_CAST")
        return out.filterValues { it != null } as Map<String, String>
    }

    /** The ride's main street(s), most photos first, at most 3, ", "-joined, ≤ 120 characters (Android rideStreets). */
    fun rideStreets(roadNames: List<String>): String? {
        val names = roadNames.map { it.trim() }.filter { SessionNaming.isNamed(it) }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3).map { it.key }
        if (names.isEmpty()) return null
        var out = names[0].take(120)
        for (n in names.drop(1)) {
            val next = "$out, $n"
            if (next.length > 120) break
            out = next
        }
        return out
    }
}

/**
 * A ride's track as a Google encoded polyline (precision 5) small enough for the upload metadata, and its length
 * (Android core Polyline and TrackStats, on track.jsonl lines).
 */
object TrackLine {
    fun encode(points: List<LatLon>, precision: Int): String {
        var factor = 1.0
        repeat(precision) { factor *= 10 }
        val sb = StringBuilder()
        var pLat = 0L
        var pLon = 0L
        for (p in points) {
            val lat = (p.lat * factor).roundToLong()
            val lon = (p.lon * factor).roundToLong()
            encodeValue(lat - pLat, sb)
            encodeValue(lon - pLon, sb)
            pLat = lat
            pLon = lon
        }
        return sb.toString()
    }

    private fun encodeValue(v: Long, sb: StringBuilder) {
        var x = if (v < 0) (v shl 1).inv() else v shl 1
        while (x >= 0x20) {
            sb.append(((0x20 or (x and 0x1f).toInt()) + 63).toChar())
            x = x shr 5
        }
        sb.append((x.toInt() + 63).toChar())
    }

    /** Douglas–Peucker within [toleranceM] metres; keeps the first and last point. */
    fun simplify(points: List<LatLon>, toleranceM: Double): List<LatLon> {
        if (points.size < 3) return points
        val kx = 111_320.0 * cos(points.map { it.lat }.average() * PI / 180)
        val ky = 110_540.0
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            if (b - a < 2) continue
            val ax = points[a].lon * kx; val ay = points[a].lat * ky
            val dx = points[b].lon * kx - ax; val dy = points[b].lat * ky - ay
            val len2 = dx * dx + dy * dy
            var worst = -1.0
            var at = -1
            for (i in a + 1 until b) {
                val px = points[i].lon * kx - ax; val py = points[i].lat * ky - ay
                val d = if (len2 == 0.0) hypot(px, py) else {
                    val t = ((px * dx + py * dy) / len2).coerceIn(0.0, 1.0)
                    hypot(px - t * dx, py - t * dy)
                }
                if (d > worst) { worst = d; at = i }
            }
            if (worst > toleranceM) {
                keep[at] = true
                stack.addLast(a to at)
                stack.addLast(at to b)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** At most [maxChars] characters: simplified at [startToleranceM], doubled until it fits; null under 2 points. */
    fun capped(points: List<LatLon>, maxChars: Int, startToleranceM: Double): String? {
        if (points.size < 2) return null
        var tol = startToleranceM
        repeat(40) {
            val s = encode(simplify(points, tol), 5)
            if (s.length <= maxChars) return s
            tol *= 2
        }
        return encode(listOf(points.first(), points.last()), 5).takeIf { it.length <= maxChars }
    }

    /** The usable fixes of track.jsonl lines, in order (accuracy ≤ [maxAccuracyM]). */
    fun points(lines: List<String>, maxAccuracyM: Double): List<LatLon> = lines.mapNotNull { l ->
        val o = runCatching { Json.parseToJsonElement(l).jsonObject }.getOrNull() ?: return@mapNotNull null
        val lat = o.num("lat") ?: return@mapNotNull null
        val lon = o.num("lon") ?: return@mapNotNull null
        if ((o.num("acc") ?: 999.0) > maxAccuracyM) return@mapNotNull null
        if (lat.isNaN() || lon.isNaN() || abs(lat) > 90 || abs(lon) > 180) return@mapNotNull null
        LatLon(lat, lon)
    }

    /** Distance ridden (m), ignoring fixes worse than [maxAccuracyM] and jumps over 144 km/h (Android TrackStats.of). */
    fun distanceM(lines: List<String>, maxAccuracyM: Double): Double {
        var dist = 0.0
        var prev: Pair<LatLon, Long>? = null
        for (l in lines) {
            val o = runCatching { Json.parseToJsonElement(l).jsonObject }.getOrNull() ?: continue
            val lat = o.num("lat") ?: continue
            val lon = o.num("lon") ?: continue
            val t = (o["t"] as? JsonPrimitive)?.longOrNull ?: continue
            if ((o.num("acc") ?: 999.0) > maxAccuracyM) continue
            val p = LatLon(lat, lon)
            prev?.let { (pp, pt) ->
                val d = Geo.distanceM(pp, p)
                val dt = (t - pt) / 1000.0
                if (dt > 0 && d / dt < 40) dist += d
            }
            prev = p to t
        }
        return dist
    }
}
