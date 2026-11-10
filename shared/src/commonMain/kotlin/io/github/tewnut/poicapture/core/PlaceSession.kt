package io.github.tewnut.poicapture.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A Place capture session as the iPhone app records it, and its `poi-capture/3` manifest (docs/handoff.md): the
 * same fields the Android app writes for `kind = "poi"`, so the agent reads both the same way. Platform code only
 * fills these records; the JSON is written here.
 */
data class PlacePhoto(
    val id: String,
    val tMs: Long,
    val placeId: String,
    val lat: Double,
    val lon: Double,
    val accuracyM: Double,
    val fixMs: Long,                  // time of the GPS fix used (at most GPS_MAX_AGE_MS before tMs)
    val altitudeM: Double? = null,
    val cameraBearing: Double? = null,
    val bearingSource: String = "compass",   // iPhone: device motion relative to magnetic / true north
    val distanceM: Double? = null,           // camera → shop front, measured (LiDAR) when available
    val distanceSource: String? = null,      // "lidar" | null (then the on-foot default is used)
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val hFovDeg: Double? = null,
    val orientation: String = "portrait",
    val exposureS: Double? = null,
    val iso: Int? = null,
    val roadName: String? = null,
)

data class PlaceRecord(
    val id: String,
    val createdMs: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracyM: Double? = null,
    val status: String = "new",               // new | exists | closed | moved
    val fields: Map<String, String> = emptyMap(),
    val pinLat: Double? = null,               // set by the contributor on the map
    val pinLon: Double? = null,
)

data class PlaceSessionInfo(
    val id: String,
    val createdMs: Long,
    val startedMs: Long?,
    val endedMs: Long?,
    val contributor: String,
    val email: String,
    val name: String?,
    val device: Map<String, String>,
    val exportedAt: String? = null,
)

object PlaceSession {
    private val json = Json { prettyPrint = true; prettyPrintIndent = " " }

    private fun n(v: Double?): JsonElement = v?.let { JsonPrimitive(it) } ?: JsonNull
    private fun n(v: Int?): JsonElement = v?.let { JsonPrimitive(it) } ?: JsonNull
    private fun n(v: String?): JsonElement = v?.let { JsonPrimitive(it) } ?: JsonNull
    private fun r1(v: Double) = kotlin.math.round(v * 10) / 10.0

    /** One photo item (manifest `items[]`). */
    fun item(p: PlacePhoto): JsonObject = buildJsonObject {
        put("id", p.id); put("kind", "photo"); put("file", "photos/${p.id}.jpg")
        put("tMs", p.tMs); put("t", IsoTime.format(p.tMs)); put("fixT", p.fixMs)
        put("lat", p.lat); put("lon", p.lon); put("accuracyM", r1(p.accuracyM)); put("altitudeM", n(p.altitudeM))
        put("poiId", p.placeId)
        put("cameraBearing", n(p.cameraBearing?.let(::r1))); put("cameraBearingSource", if (p.cameraBearing != null) p.bearingSource else "none")
        put("sideOfWay", JsonNull); put("travel", JsonNull)
        put("roadName", n(p.roadName))
        put("width", p.widthPx); put("height", p.heightPx); put("orientation", p.orientation)
        put("hFovDeg", n(p.hFovDeg?.let(::r1)))
        put("exposureS", n(p.exposureS)); put("iso", n(p.iso))
        // where the place is likely: in front of the camera, at the measured distance (LiDAR) or the on-foot default
        val d = p.distanceM ?: CaptureRules.ON_FOOT_FACADE_M
        val est = p.cameraBearing?.let { Geo.destination(LatLon(p.lat, p.lon), it, d) }
        put("facadeDistanceM", r1(d))
        put("facadeSource", if (p.distanceM != null) (p.distanceSource ?: "measured") else "onFoot")
        put("facadeLat", n(est?.lat)); put("facadeLon", n(est?.lon))
    }

    /** One place (manifest `pois[]`) with its photos in shooting order; the first photo is the cover. */
    fun place(r: PlaceRecord, photos: List<PlacePhoto>): JsonObject = buildJsonObject {
        put("id", r.id); put("createdAt", IsoTime.format(r.createdMs)); put("tMs", r.createdMs)
        val pinned = r.pinLat != null && r.pinLon != null
        put("position", when {
            pinned -> buildJsonObject { put("lat", r.pinLat); put("lon", r.pinLon); put("source", "pin") }
            r.lat != null && r.lon != null -> buildJsonObject {
                put("lat", r.lat); put("lon", r.lon); put("accuracyM", n(r.accuracyM)); put("source", "gps")
            }
            else -> JsonNull
        })
        putJsonObject("fields") { r.fields.filterValues { it.isNotBlank() }.forEach { (k, v) -> put(k, v.trim()) } }
        put("status", r.status)
        val mine = photos.filter { it.placeId == r.id }.sortedBy { it.tMs }
        putJsonArray("photos") { mine.forEach { add(JsonPrimitive(it.id)) } }
        put("cover", n(mine.firstOrNull()?.id))
        if (mine.isEmpty()) put("noPhotos", true)
    }

    /** The whole manifest.json of a Place capture session, pretty printed. */
    fun manifest(s: PlaceSessionInfo, photos: List<PlacePhoto>, places: List<PlaceRecord>, exportedMs: Long): String {
        val sorted = photos.sortedBy { it.tMs }
        val m = buildJsonObject {
            put("schema", SCHEMA); put("kind", "poi"); put("exportedAt", IsoTime.format(exportedMs))
            putJsonObject("session") {
                put("id", s.id); put("schema", SCHEMA); put("kind", "poi")
                put("createdAt", IsoTime.format(s.createdMs))
                put("startedAt", n(s.startedMs?.let(IsoTime::format))); put("endedAt", n(s.endedMs?.let(IsoTime::format)))
                put("contributor", s.contributor); put("email", s.email); put("note", ""); put("name", n(s.name))
                put("vehicle", "unspecified"); put("mode", "photo-manual"); put("sideOfTravel", "right")
                put("travel", JsonNull); put("sideOfWay", JsonNull)
                putJsonObject("road") { put("auto", true); put("ways", JsonArray(emptyList())) }
                putJsonObject("settings") {
                    put("facadeDistanceM", CaptureRules.ON_FOOT_FACADE_M); put("maxAccuracyM", CaptureRules.MAX_ACCURACY_M)
                }
                putJsonObject("device") { s.device.forEach { (k, v) -> put(k, v) } }
                put("exportedAt", n(s.exportedAt))
                putJsonObject("counts") { put("photos", sorted.size); put("places", places.size) }
            }
            put("items", buildJsonArray { sorted.forEach { add(item(it)) } })
            put("pois", buildJsonArray { places.sortedBy { it.createdMs }.forEach { add(place(it, sorted)) } })
            put("marks", JsonArray(emptyList()))
            // nothing is blurred on the phone: faces and licence plates must be blurred before anything is published
            putJsonObject("privacy") { put("blurred", false); putJsonArray("required") { add(JsonPrimitive("faces")); add(JsonPrimitive("plates")) } }
            put("trackFile", JsonNull); put("trackPoints", 0); put("gnssRawFile", JsonNull); put("imuFile", JsonNull)
        }
        return json.encodeToString(JsonObject.serializer(), m)
    }

    /** "{street} [yymmdd]" for a Place capture session (see SessionNaming). */
    fun name(photos: List<PlacePhoto>, yymmdd: String?, fallback: String): String =
        SessionNaming.name(photos.mapNotNull { it.roadName }, yymmdd, fallback)
}
