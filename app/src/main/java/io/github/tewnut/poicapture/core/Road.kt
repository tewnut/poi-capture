package io.github.tewnut.poicapture.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.floor

/** One OpenStreetMap way with its geometry. */
data class Way(
    val id: Long,
    val name: String,
    val ref: String,
    val highway: String,
    val oneway: String,
    val geometry: List<LatLon>,
    /** offline road packs cut long ways into pieces of about one cell: same OSM id, piece 0, 1, … (0 = whole way) */
    val piece: Int = 0,
) {
    /** identity of this geometry: (OSM id, piece) */
    val key: Pair<Long, Int> get() = id to piece

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("ref", ref).put("highway", highway).put("oneway", oneway)
        .apply { if (piece != 0) put("piece", piece) }
        .put("geometry", JSONArray().apply { geometry.forEach { put(JSONObject().put("lat", it.lat).put("lon", it.lon)) } })

    companion object {
        fun fromJson(o: JSONObject): Way {
            val g = o.optJSONArray("geometry") ?: JSONArray()
            return Way(
                id = o.getLong("id"),
                name = o.optString("name"),
                ref = o.optString("ref"),
                highway = o.optString("highway"),
                oneway = o.optString("oneway"),
                geometry = (0 until g.length()).map { g.getJSONObject(it).let { p -> LatLon(p.getDouble("lat"), p.getDouble("lon")) } },
                piece = o.optInt("piece", 0),
            )
        }
    }
}

/**
 * The road data of a session.
 * - Picked: the way the contributor tapped plus the rest of the same road nearby (OSM splits roads); primaryWayId set.
 * - Auto: label "Tự động theo GPS", primaryWayId null, ways = every way loaded around the ride.
 */
data class Road(
    val label: String,
    val name: String,
    val ref: String,
    val highway: String,
    val primaryWayId: Long?,
    val ways: List<Way>,
    /** where the road data came from: "pack:<region>@<version>", "cache", "overpass" (auto mode) */
    val sources: List<String> = emptyList(),
) {
    val auto: Boolean get() = primaryWayId == null

    fun lengthKm(): Double = ways.sumOf { Geo.lineLengthM(it.geometry) } / 1000

    fun toJson(): JSONObject = JSONObject()
        .put("label", label).put("name", name).put("ref", ref).put("highway", highway)
        .put("primaryWayId", primaryWayId ?: JSONObject.NULL)
        .put("mode", if (auto) "auto" else "picked")
        .put("sources", JSONArray(sources))
        .put("ways", JSONArray().apply { ways.forEach { put(it.toJson()) } })

    companion object {
        const val AUTO_LABEL = "Tự động theo GPS"

        fun fromJson(o: JSONObject): Road {
            val w = o.optJSONArray("ways") ?: JSONArray()
            return Road(
                label = o.optString("label"), name = o.optString("name"), ref = o.optString("ref"),
                highway = o.optString("highway"),
                primaryWayId = if (o.isNull("primaryWayId")) null else o.getLong("primaryWayId"),
                ways = (0 until w.length()).map { Way.fromJson(w.getJSONObject(it)) },
                sources = o.optJSONArray("sources")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            )
        }

        fun of(primary: Way, ways: List<Way>) =
            Road(roadLabel(primary), primary.name, primary.ref, primary.highway, primary.id, ways)

        fun auto(ways: List<Way> = emptyList(), sources: List<String> = emptyList()) = Road(AUTO_LABEL, "", "", "", null, ways, sources)
    }
}

fun roadLabel(w: Way): String =
    listOf(w.ref, w.name).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "Đường không tên (${w.highway})" }

data class RoadMatch(val wayId: Long, val point: LatLon, val distanceM: Double, val segmentBearing: Double)

/**
 * Nearest way to a fix, with the segment bearing at that point. With a travel bearing, ways running along the
 * direction of travel (or against it) win over nearer cross streets: at an intersection the rider is on the road
 * they are driving along, not on the one they are crossing.
 */
fun matchRoad(ways: List<Way>, p: LatLon, travelBearing: Double? = null, alignWithinM: Double = 30.0): RoadMatch? {
    val all = ways.mapNotNull { w -> Geo.nearestOnLine(w.geometry, p)?.let { RoadMatch(w.id, it.point, it.distanceM, it.segmentBearing) } }
    val nearest = all.minByOrNull { it.distanceM } ?: return null
    if (travelBearing == null) return nearest
    val aligned = all.filter {
        it.distanceM <= nearest.distanceM + alignWithinM &&
            minOf(Geo.angleDiff(travelBearing, it.segmentBearing), Geo.angleDiff(travelBearing, it.segmentBearing + 180)) <= 30
    }
    return aligned.minByOrNull { it.distanceM } ?: nearest
}

/**
 * Road data around the rider is loaded in fixed grid cells (0.005° ≈ 550 m), so the setup screen and the capture
 * service ask Overpass for the same cells (same query → cache hit) and a ride only loads the cells it passes through.
 */
object RoadCells {
    const val SIZE = 0.005

    data class Cell(val x: Int, val y: Int) {
        val south get() = y * SIZE
        val west get() = x * SIZE
        val north get() = (y + 1) * SIZE
        val east get() = (x + 1) * SIZE
        fun contains(p: LatLon) = p.lat >= south && p.lat < north && p.lon >= west && p.lon < east
    }

    fun cellOf(p: LatLon) = Cell(floor(p.lon / SIZE).toInt(), floor(p.lat / SIZE).toInt())

    /** The cell of p plus neighbours closer than `marginM`, nearest first: what should be loaded around p. */
    fun around(p: LatLon, marginM: Double = 200.0): List<Cell> {
        val c = cellOf(p)
        val mLat = 111_320.0
        val mLon = 111_320.0 * cos(Math.toRadians(p.lat))
        fun dist(n: Cell): Double {
            val dy = maxOf(n.south - p.lat, 0.0, p.lat - n.north) * mLat
            val dx = maxOf(n.west - p.lon, 0.0, p.lon - n.east) * mLon
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }
        return (-1..1).flatMap { dx -> (-1..1).map { dy -> Cell(c.x + dx, c.y + dy) } }
            .map { it to dist(it) }.filter { it.second <= marginM }.sortedBy { it.second }.map { it.first }
    }
}

/** Overpass (OpenStreetMap) query building and parsing. The HTTP call lives in the Android layer. */
object Overpass {
    private const val SKIP = "footway|path|steps|cycleway|pedestrian|bridleway|corridor|elevator|platform|proposed|construction"
    // for whole areas: also skip service roads / tracks (parking aisles, driveways), which multiply the data size
    /** also used by the offline road pack builder: a pack built with another filter is refused */
    const val SKIP_AREA = "$SKIP|service|track|bus_guideway|raceway|abandoned|razed"

    private fun f6(d: Double) = String.format(java.util.Locale.ROOT, "%.6f", d)
    private fun clean(s: String) = s.replace("\"", "")

    /** Drivable roads within `radius` m of a point. */
    fun nearQuery(lat: Double, lon: Double, radius: Int = 40): String =
        "[out:json][timeout:20];way(around:$radius,${f6(lat)},${f6(lon)})[highway][highway!~\"$SKIP\"];out tags geom;"

    /** Drivable roads in one grid cell. */
    fun cellQuery(c: RoadCells.Cell): String =
        "[out:json][timeout:25];way(${f6(c.south)},${f6(c.west)},${f6(c.north)},${f6(c.east)})[highway][highway!~\"$SKIP_AREA\"];out tags geom;"

    /** Ways of the same road (same ref, else same name) within `radius` m of the picked way's middle; null if unnamed. */
    fun sameRoadQuery(way: Way, radius: Int = 5000): String? {
        val mid = way.geometry.getOrNull(way.geometry.size / 2) ?: return null
        val key = when {
            way.ref.isNotBlank() -> "[\"ref\"=\"${clean(way.ref)}\"]"
            way.name.isNotBlank() -> "[\"name\"=\"${clean(way.name)}\"]"
            else -> return null
        }
        return "[out:json][timeout:25];way(around:$radius,${f6(mid.lat)},${f6(mid.lon)})[highway]$key;out tags geom;"
    }

    fun parseWays(body: String): List<Way> {
        val els = JSONObject(body).optJSONArray("elements") ?: return emptyList()
        val out = mutableListOf<Way>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            if (e.optString("type") != "way") continue
            val tags = e.optJSONObject("tags") ?: JSONObject()
            val g = e.optJSONArray("geometry") ?: JSONArray()
            out += Way(
                id = e.getLong("id"),
                name = tags.optString("name"), ref = tags.optString("ref"),
                highway = tags.optString("highway"), oneway = tags.optString("oneway"),
                geometry = (0 until g.length()).map { g.getJSONObject(it).let { p -> LatLon(p.getDouble("lat"), p.getDouble("lon")) } },
            )
        }
        return out
    }

    /** Ways sorted nearest first, each with its distance (m) to p. */
    fun rankNear(ways: List<Way>, p: LatLon): List<Pair<Way, Int>> =
        ways.map { it to (Geo.nearestOnLine(it.geometry, p)?.distanceM?.toInt() ?: Int.MAX_VALUE) }.sortedBy { it.second }

    /** Picked way first, then the others by distance of their first node from the picked way's middle. */
    fun orderSameRoad(primary: Way, ways: List<Way>): List<Way> {
        val mid = primary.geometry.getOrNull(primary.geometry.size / 2) ?: return listOf(primary)
        val others = ways.filter { it.id != primary.id && it.geometry.isNotEmpty() }
            .sortedBy { Geo.distanceM(mid, it.geometry.first()) }
        return listOf(primary) + others
    }
}
