package io.github.tewnut.poicapture.core

import org.json.JSONObject
import java.time.Instant
import kotlin.random.Random

const val SCHEMA = "poi-capture/3"   // "road-capture/N" before app 0.8, same format

/** Public catalogue of offline road packs (needs the repository to be public: release assets of private repos need a login). */
const val DEFAULT_ROADPACK_INDEX = "https://github.com/tewnut/poi-capture/releases/download/roadpacks/index.json"

/** Recorded by app <= 0.3. Since 0.4 the vehicle is not asked any more ("unspecified"): spacing and speed come from the
 *  distance to the shop fronts and the measured speed. */
enum class Vehicle(val json: String) {
    MOTORBIKE("motorbike"), CAR("car"), UNSPECIFIED("unspecified");

    companion object {
        fun of(s: String?) = entries.firstOrNull { it.json == s } ?: UNSPECIFIED
    }
}

enum class Mode(val json: String) {
    PHOTO_AUTO("photo-auto"), PHOTO_MANUAL("photo-manual"), VIDEO("video");

    companion object {
        fun of(s: String?) = entries.firstOrNull { it.json == s } ?: PHOTO_AUTO
    }
}

/** App settings (per device). Defaults match the former web app. */
data class Settings(
    val tileUrl: String = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
    val overpassUrl: String = "https://overpass-api.de/api/interpreter",
    val maxAccuracyM: Double = 30.0,
    val offRoadWarnM: Double = 60.0,
    val photoLongEdge: Int = 2560,      // requested photo size (16:9, long edge); the camera picks the closest
    val jpegQuality: Int = 92,
    val mountOffsetDeg: Double = 0.0,   // manual fallback when the app could not measure the camera direction
    val facadeDistanceM: Double = 6.0,  // expected camera -> shop front distance (profile default, adjustable per session)
    // catalogue (index.json) of offline road packs: the GitHub release built by .github/workflows/roadpacks.yml
    val roadPackIndexUrl: String = DEFAULT_ROADPACK_INDEX,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("tileUrl", tileUrl).put("overpassUrl", overpassUrl)
        .put("maxAccuracyM", maxAccuracyM).put("offRoadWarnM", offRoadWarnM)
        .put("photoLongEdge", photoLongEdge).put("jpegQuality", jpegQuality)
.put("mountOffsetDeg", mountOffsetDeg)
        .put("facadeDistanceM", facadeDistanceM)
        .put("roadPackIndexUrl", roadPackIndexUrl)

    companion object {
        fun fromJson(o: JSONObject?): Settings {
            val d = Settings()
            if (o == null) return d
            return Settings(
                tileUrl = o.optString("tileUrl", d.tileUrl),
                overpassUrl = o.optString("overpassUrl", d.overpassUrl),
                maxAccuracyM = o.optDouble("maxAccuracyM", d.maxAccuracyM),
                offRoadWarnM = o.optDouble("offRoadWarnM", d.offRoadWarnM),
                photoLongEdge = o.optInt("photoLongEdge", d.photoLongEdge),
                jpegQuality = o.optInt("jpegQuality", d.jpegQuality),
                mountOffsetDeg = o.optDouble("mountOffsetDeg", d.mountOffsetDeg),
                facadeDistanceM = o.optDouble("facadeDistanceM", d.facadeDistanceM),
                roadPackIndexUrl = o.optString("roadPackIndexUrl", d.roadPackIndexUrl),
            )
        }
    }
}

/** One capture session (one road, one side). Persisted as session.json. */
class Session(
    val id: String,
    val createdAt: String,
    var startedAt: String? = null,
    var endedAt: String? = null,
    val contributor: String,
    val note: String,
    val vehicle: Vehicle,
    val mode: Mode,
    var sideOfTravel: Side,          // assumed right until the camera direction is measured
    var travel: Travel? = null,      // decided on the first moving fix
    var sideOfWay: Side? = null,
    var road: Road,
    val settings: Settings,
    val device: JSONObject = JSONObject(),
    var camera: JSONObject? = null,  // intrinsics / FOV, filled when the camera opens
    var counts: JSONObject? = null,
    var gnss: JSONObject? = null,    // raw GNSS summary (measurement and carrier-phase counts)
    var exportedAt: String? = null,  // last time the contributor shared / saved the zip
    var plan: JSONObject? = null,    // CapturePlan actually used (photo spacing, speed limit)
    var mount: JSONObject? = null,   // measured camera direction (MountEstimate), null = not measured
    val kind: SessionKind = SessionKind.SWEEP,
    val email: String = "",          // typed at onboarding, not verified; "" before app 0.8
    var photoRoles: PhotoRoles? = null,  // POI mode: the photo kinds offered (config at the start of the session)
    var name: String? = null,        // "{street} [yymmdd]", given when the session is saved
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("schema", SCHEMA).put("kind", kind.json).put("createdAt", createdAt)
        .put("startedAt", startedAt.orNull()).put("endedAt", endedAt.orNull())
        .put("contributor", contributor).put("email", email).put("note", note).put("name", name.orNull())
        .put("vehicle", vehicle.json).put("mode", mode.json)
        .put("sideOfTravel", sideOfTravel.json)
        .put("travel", travel?.json.orNull()).put("sideOfWay", sideOfWay?.json.orNull())
        .put("road", road.toJson())
        .put("settings", JSONObject()
            .put("photoEveryM", plan?.optDouble("photoEveryM") ?: legacyPhotoEveryM(vehicle))
            .put("maxSpeedKmh", plan?.optInt("maxSpeedKmh") ?: legacyMaxSpeedKmh(vehicle))
            .put("facadeDistanceM", settings.facadeDistanceM)
            .put("maxAccuracyM", settings.maxAccuracyM)
            .put("mountOffsetDeg", settings.mountOffsetDeg)
            .put("photoLongEdge", settings.photoLongEdge)
            .put("all", settings.toJson()))
        .put("device", device)
        .put("camera", camera.orNull())
        .put("counts", counts.orNull())
        .put("gnss", gnss.orNull())
        .put("exportedAt", exportedAt.orNull())
        .put("plan", plan.orNull())
        .put("mount", mount.orNull())
        .put("photoRoles", photoRoles?.toJson()?.getJSONArray("photoRoles").orNull())

    companion object {
        fun fromJson(o: JSONObject): Session = Session(
            id = o.getString("id"),
            createdAt = o.optString("createdAt"),
            startedAt = o.optStr("startedAt"),
            endedAt = o.optStr("endedAt"),
            contributor = o.optString("contributor"),
            note = o.optString("note"),
            name = o.optStr("name"),
            vehicle = Vehicle.of(o.optString("vehicle")),
            mode = Mode.of(o.optString("mode")),
            sideOfTravel = Side.of(o.optString("sideOfTravel")) ?: Side.RIGHT,
            travel = Travel.of(o.optStr("travel")),
            sideOfWay = Side.of(o.optStr("sideOfWay")),
            road = Road.fromJson(o.getJSONObject("road")),
            settings = Settings.fromJson(o.optJSONObject("settings")?.optJSONObject("all")),
            device = o.optJSONObject("device") ?: JSONObject(),
            camera = o.optJSONObject("camera"),
            counts = o.optJSONObject("counts"),
            gnss = o.optJSONObject("gnss"),
            exportedAt = o.optStr("exportedAt"),
            plan = o.optJSONObject("plan"),
            mount = o.optJSONObject("mount"),
            kind = SessionKind.of(o.optStr("kind")),
            email = o.optString("email"),
            photoRoles = PhotoRoles.fromJson(o.optJSONArray("photoRoles"), "session"),
        )
    }
}

/** Sessions of app <= 0.3 have no plan: their photos were spaced (and the speed capped) per vehicle. */
private fun legacyPhotoEveryM(v: Vehicle) = if (v == Vehicle.CAR) 15.0 else 10.0
private fun legacyMaxSpeedKmh(v: Vehicle) = if (v == Vehicle.CAR) 25 else 20

fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

fun uid(prefix: String, nowMs: Long = System.currentTimeMillis(), rnd: Random = Random.Default): String =
    prefix + nowMs.toString(36) + (1..5).map { "0123456789abcdefghijklmnopqrstuvwxyz"[rnd.nextInt(36)] }.joinToString("")

internal fun Any?.orNull(): Any = this ?: JSONObject.NULL

/** optString that maps JSON null / missing to Kotlin null. */
internal fun JSONObject.optStr(k: String): String? = if (isNull(k)) null else optString(k)
