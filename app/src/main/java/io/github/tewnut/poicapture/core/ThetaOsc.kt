package io.github.tewnut.poicapture.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLConnection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ricoh Theta control over the Open Spherical Camera (OSC) API (docs/camera360.md §4). Only what the OSC spec and
 * RICOH THETA API v2.1 document is used:
 * - `GET /osc/info` (manufacturer, model, serialNumber, firmwareVersion, apiLevel),
 * - `POST /osc/state` (`state.batteryLevel` 0.0–1.0, standard; `_captureStatus` is Theta's own),
 * - `POST /osc/commands/execute` with `camera.setOptions` and `camera.takePicture`,
 * - `POST /osc/commands/status` `{"id"}` until `state` is `done` (results.fileUrl) or `error`.
 *
 * Models: Theta SC2, V, Z1 and X speak API v2.1 (apiLevel 2; no session). Theta S / SC start in v2.0 (apiLevel 1,
 * commands need a sessionId and `clientVersion` 2 first): not handled here, [OscClient.connect] refuses them.
 * In direct (access point) mode the camera is at 192.168.1.1 with no HTTP authentication; client mode (the camera on
 * a router) uses digest auth and another address: not handled.
 *
 * Pure JVM (no android.*): the transport is passed in, so the unit tests run it against a local HTTP server.
 */
object ThetaOsc {
    const val CONTROL = "theta-osc"
    const val BASE_URL = "http://192.168.1.1"
    /** The camera's Wi-Fi network in direct mode: `THETAYL00105377.OSC` (V), `THETAYN…` (Z1), `THETAYR…` (X), `THETAYP…` (SC2) */
    const val SSID_PREFIX = "THETA"
    const val DEFAULT_EVERY_M = 8.0
    /** Below this the rider is standing: no distance is counted, no photo (as camera360.py's STANDING_MPS). */
    const val STANDING_MPS = 0.7

    /** The OSC `dateTimeZone` / gpsInfo `_dateTimeZone` text: `yyyy:MM:dd HH:mm:ss+07:00` in [zone]. */
    fun dateTimeZone(epochMs: Long, zone: ZoneId): String =
        DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ssxxx", Locale.ROOT).format(Instant.ofEpochMilli(epochMs).atZone(zone))

    /**
     * The `gpsInfo` option (RICOH THETA API v2.1): lat / lng in degrees (6 decimals), `_altitude` metres, `_dateTimeZone`,
     * `_datum` "WGS84". Written into the EXIF of the next photos.
     */
    fun gpsInfo(lat: Double, lon: Double, altitudeM: Double?, epochMs: Long, zone: ZoneId): JSONObject = JSONObject()
        .put("lat", round6(lat)).put("lng", round6(lon))
        .put("_altitude", Math.round((altitudeM ?: 0.0) * 100) / 100.0)
        .put("_dateTimeZone", dateTimeZone(epochMs, zone))
        .put("_datum", "WGS84")

    private fun round6(v: Double) = Math.round(v * 1e6) / 1e6

    /** The camera's Wi-Fi password in direct mode by default: the digits of its serial (`THETAYR12345678.OSC` → `12345678`). */
    fun passwordFrom(ssidOrSerial: String): String = ssidOrSerial.filter { it.isDigit() }

    /** The file name of a fileUrl (`…/100RICOH/R0010023.JPG` → `R0010023.JPG`). */
    fun fileName(fileUrl: String?): String? = fileUrl?.substringAfterLast('/')?.substringBefore('?')?.takeIf { it.isNotBlank() }

    /** Great-circle distance in metres. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_008.8
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}

data class OscResponse(val code: Int, val body: String)

/** One HTTP exchange with the camera. [path] starts with `/osc/`. */
fun interface OscTransport {
    @Throws(IOException::class)
    fun exchange(method: String, path: String, body: String?, timeoutMs: Int): OscResponse
}

/**
 * HTTP over [open]: on the phone `network::openConnection` of the camera's Wi-Fi network, so only these requests go
 * to the camera and the rest of the app keeps mobile data (no process binding).
 */
class HttpOscTransport(private val base: String = ThetaOsc.BASE_URL,
                       private val open: (URL) -> URLConnection = { it.openConnection() }) : OscTransport {
    override fun exchange(method: String, path: String, body: String?, timeoutMs: Int): OscResponse {
        val c = open(URL(base.trimEnd('/') + path)) as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.requestMethod = method
            c.useCaches = false
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("X-XSRF-Protected", "1")   // the OSC spec's examples send it; harmless where unused
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json;charset=utf-8")
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return OscResponse(code, text)
        } finally {
            c.disconnect()
        }
    }
}

/**
 * A file on the camera, by the fileUrl it gave (RICOH THETA API v2.1: a GET on the file URL; `?type=thumb` = its
 * thumbnail). At most [maxBytes] are read, then the connection is dropped; [range]: also ask for only those bytes
 * (`Range: bytes=0-…`; a camera that ignores it just sends the file, and the reading stops anyway).
 */
fun interface OscFiles {
    @Throws(IOException::class)
    fun get(url: String, maxBytes: Int, range: Boolean, timeoutMs: Int): ByteArray
}

/** [OscFiles] over HTTP through [open] (the camera's network on the phone). Only URLs on the camera's address. */
class HttpOscFiles(private val base: String = ThetaOsc.BASE_URL,
                   private val open: (URL) -> URLConnection = { it.openConnection() }) : OscFiles {
    override fun get(url: String, maxBytes: Int, range: Boolean, timeoutMs: Int): ByteArray {
        val u = URL(url)
        if (u.host != URL(base).host) throw IOException("not the camera's address: ${u.host}")
        val c = open(u) as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.requestMethod = "GET"
            c.useCaches = false
            if (range) c.setRequestProperty("Range", "bytes=0-${maxBytes - 1}")
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return c.inputStream.use { readUpTo(it, maxBytes) }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        /** The first [max] bytes of [s] (fewer if it ends before). */
        fun readUpTo(s: java.io.InputStream, max: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream(minOf(max, 64 * 1024))
            val buf = ByteArray(16 * 1024)
            while (out.size() < max) {
                val n = s.read(buf, 0, minOf(buf.size, max - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}

/** [code]: an OSC error code (`disabledCommand`, `invalidParameterValue`, …) or `timeout` / `io` / `http` / `bad-response`. */
class OscException(val code: String, message: String) : Exception(message) {
    /** Short, for the screen and the shot record. */
    fun short() = if (message.isNullOrBlank() || message == code) code else "$code: $message"
}

data class OscInfo(val manufacturer: String?, val model: String?, val serial: String?, val firmware: String?, val apiLevels: List<Int>) {
    /** `session.camera360.camera` */
    fun toJson(): JSONObject = JSONObject().put("model", model ?: JSONObject.NULL).put("serial", serial ?: JSONObject.NULL)
        .put("firmware", firmware ?: JSONObject.NULL)
}

data class OscState(val batteryLevel: Double?, val captureStatus: String?)

/** A command's state: `inProgress` (with its [id]), `done` (with [results]) or `error` (thrown as [OscException]). */
data class OscCommand(val id: String?, val state: String, val results: JSONObject?)

class OscClient(private val transport: OscTransport, private val timeoutMs: Int = 5_000) {

    fun info(): OscInfo {
        val o = call("GET", "/osc/info", null)
        val levels = o.optJSONArray("apiLevel")?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: emptyList()
        return OscInfo(o.optStr("manufacturer"), o.optStr("model"), o.optStr("serialNumber"), o.optStr("firmwareVersion"), levels)
    }

    fun state(): OscState {
        val s = call("POST", "/osc/state", null).optJSONObject("state") ?: JSONObject()
        val b = s.optDouble("batteryLevel")
        return OscState(if (b.isNaN()) null else b, s.optStr("_captureStatus"))
    }

    fun setOptions(options: JSONObject) {
        command("camera.setOptions", JSONObject().put("options", options))
    }

    /** `camera.takePicture`: usually `inProgress` with an id (poll [status]); `done` right away is accepted too. */
    fun takePicture(): OscCommand = command("camera.takePicture", null)

    /**
     * `camera.delete` `{"fileUrls": [...]}` (RICOH THETA API v2.1; up to 128 URLs; one URL that does not exist or
     * cannot be deleted fails the whole command). Used by the camera check to remove its test photo.
     */
    fun delete(fileUrls: List<String>) {
        command("camera.delete", JSONObject().put("fileUrls", JSONArray(fileUrls)))
    }

    fun status(id: String): OscCommand = parseCommand(call("POST", "/osc/commands/status", JSONObject().put("id", id).toString()))

    /**
     * Take a picture and wait for its file: polls `/osc/commands/status` every [pollMs] until done, at most [maxMs].
     * [onSent] is called right after the takePicture request was answered (its id, when inProgress).
     * -> (command id, fileUrl).
     */
    fun takePictureAndWait(pollMs: Long = 300, maxMs: Long = 20_000, sleep: (Long) -> Unit = { Thread.sleep(it) },
                           clock: () -> Long = System::currentTimeMillis): Pair<String?, String?> {
        val start = clock()
        var c = takePicture()
        val id = c.id
        while (c.state == "inProgress") {
            if (id == null) throw OscException("bad-response", "inProgress without an id")
            if (clock() - start > maxMs) throw OscException("timeout", "no file after ${maxMs / 1000} s")
            sleep(pollMs)
            c = status(id)
        }
        return id to c.results?.optStr("fileUrl")
    }

    /**
     * At connect: the info, then the camera's clock from the phone ([nowMs] in [zone]) and best-effort options for a
     * ride (still image mode, no self-timer, no sleep / power-off: each its own call, a refusal only noted).
     * Throws when the camera does not answer or speaks only API v2.0 (Theta S / SC).
     */
    fun connect(nowMs: Long, zone: ZoneId): ConnectResult {
        val info = info()
        if (info.apiLevels.isNotEmpty() && 2 !in info.apiLevels)
            throw OscException("api-level", "API v2.1 needed (Theta SC2 / V / Z1 / X); this camera: ${info.model ?: "?"}")
        val notes = mutableListOf<String>()
        var clockSet = true
        try { setOptions(JSONObject().put("dateTimeZone", ThetaOsc.dateTimeZone(nowMs, zone))) }
        catch (e: OscException) { clockSet = false; notes += "dateTimeZone: ${e.short()}" }
        for ((k, v) in RIDE_OPTIONS) {
            try { setOptions(JSONObject().put(k, v)) } catch (e: OscException) { notes += "$k: ${e.short()}" }
        }
        // a Theta X writes the phone's gpsInfo into its photos only with its own GPS tagging on (API v2.1, gpsInfo *2)
        if (Camera360Check.isThetaX(info.model)) {
            try { setOptions(JSONObject().put("_gpsTagRecording", "on")) } catch (e: OscException) { notes += "_gpsTagRecording: ${e.short()}" }
        }
        val st = runCatching { state() }.getOrNull()
        return ConnectResult(info, st?.batteryLevel, clockSet, notes)
    }

    data class ConnectResult(val info: OscInfo, val batteryLevel: Double?, val clockSet: Boolean, val notes: List<String>)

    private fun command(name: String, parameters: JSONObject?): OscCommand {
        val body = JSONObject().put("name", name).also { if (parameters != null) it.put("parameters", parameters) }
        return parseCommand(call("POST", "/osc/commands/execute", body.toString()))
    }

    private fun parseCommand(o: JSONObject): OscCommand {
        val state = o.optStr("state") ?: throw OscException("bad-response", "no state")
        if (state == "error") throw errorOf(o, "error")
        return OscCommand(o.optStr("id"), state, o.optJSONObject("results"))
    }

    private fun errorOf(o: JSONObject?, fallback: String): OscException {
        val e = o?.optJSONObject("error")
        return OscException(e?.optStr("code") ?: fallback, e?.optStr("message") ?: "")
    }

    private fun call(method: String, path: String, body: String?): JSONObject {
        val r = try {
            transport.exchange(method, path, body, timeoutMs)
        } catch (e: SocketTimeoutException) {
            throw OscException("timeout", "$path: no answer in ${timeoutMs / 1000} s")
        } catch (e: IOException) {
            throw OscException("io", "$path: ${e.message ?: e.javaClass.simpleName}")
        }
        val o = runCatching { JSONObject(r.body) }.getOrNull()
        if (r.code !in 200..299) throw errorOf(o, "http").let { if (it.code == "http") OscException("http", "$path: HTTP ${r.code}") else it }
        return o ?: throw OscException("bad-response", "$path: not JSON")
    }

    companion object {
        /**
         * Options set once at connect, best effort. All are OSC / Theta API v2.1 options; what each model accepts
         * differs (e.g. the allowed offDelay / sleepDelay values, see `…Support`): a refusal is only noted.
         * 65535 = never for sleepDelay / offDelay (OSC spec).
         */
        val RIDE_OPTIONS: List<Pair<String, Any>> = listOf(
            "captureMode" to "image",
            "exposureDelay" to 0,
            "sleepDelay" to 65535,
            "offDelay" to 65535,
        )
    }
}

/** One photo the phone asked for (`session.camera360.shots[]`). */
data class ThetaShot(
    val tMs: Long, val lat: Double, val lon: Double, val accuracyM: Double?, val bearing: Double?, val speedMps: Double?,
    val cmdId: String?, val fileUrl: String?, val ok: Boolean, val error: String? = null,
) {
    val fileName: String? get() = ThetaOsc.fileName(fileUrl)

    fun toJson(): JSONObject = JSONObject().put("tMs", tMs).put("lat", lat).put("lon", lon)
        .put("accuracyM", accuracyM.orNull()).put("bearing", bearing.orNull()).put("speedMps", speedMps.orNull())
        .put("cmdId", cmdId ?: JSONObject.NULL).put("fileUrl", fileUrl ?: JSONObject.NULL)
        .put("fileName", fileName ?: JSONObject.NULL).put("ok", ok)
        .also { if (error != null) it.put("error", error) }

    private fun Double?.orNull(): Any = this ?: JSONObject.NULL
}

/** A GPS fix as the shooter needs it. [tMs]: the fix's time (any monotonic ms base; only differences are used). */
data class ShotFix(val tMs: Long, val lat: Double, val lon: Double, val accuracyM: Double?, val altitudeM: Double?,
                   val speedMps: Double?, val bearing: Double?)

/**
 * A 360 photo every [everyM] metres along the GPS track (none while standing), one at a time: before each, gpsInfo is
 * set to the fix so the photo's EXIF carries the phone's position. A trigger while the previous shot is still being
 * taken (or the camera is not connected) is skipped and counted. [exec] runs the shots (one thread).
 */
class ThetaShooter(
    private val client: () -> OscClient?,
    @Volatile var everyM: Double,
    private val exec: Executor,
    private val onShot: (ThetaShot) -> Unit,
    private val onStats: (Stats) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val pollMs: Long = 300,
    private val maxShotMs: Long = 20_000,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    data class Stats(val shots: Int = 0, val failed: Int = 0, val skippedBusy: Int = 0, val skippedOffline: Int = 0,
                     val busy: Boolean = false, val lastError: String? = null, val lastFile: String? = null) {
        val skipped get() = skippedBusy + skippedOffline
    }

    private val busy = AtomicBoolean(false)
    private var prev: ShotFix? = null
    private var sinceLastM = Double.MAX_VALUE   // the first moving fix shoots
    @Volatile var stats = Stats(); private set

    private fun update(f: (Stats) -> Stats) { synchronized(this) { stats = f(stats) }; onStats(stats) }

    /** Restore counts after a restart of the ride (the shots already in the session, the skips saved with it). */
    fun restore(shots: Int, failed: Int, skippedBusy: Int = 0, skippedOffline: Int = 0) =
        update { it.copy(shots = shots, failed = failed, skippedBusy = skippedBusy, skippedOffline = skippedOffline) }

    /**
     * A new fix while the ride is running. Distance counts only moving segments (speed ≥ [ThetaOsc.STANDING_MPS],
     * the fix's Doppler speed, else from the two fixes). -> true when a shot was started.
     */
    fun onFix(f: ShotFix): Boolean {
        val p = prev
        prev = f
        if (p == null) return false
        val d = ThetaOsc.distanceM(p.lat, p.lon, f.lat, f.lon)
        val dt = (f.tMs - p.tMs) / 1000.0
        val v = f.speedMps ?: if (dt > 0) d / dt else 0.0
        if (v < ThetaOsc.STANDING_MPS) return false
        if (sinceLastM != Double.MAX_VALUE) sinceLastM += d
        if (sinceLastM < everyM) return false
        sinceLastM = 0.0
        val c = client()
        if (c == null) { update { it.copy(skippedOffline = it.skippedOffline + 1) }; return false }
        if (!busy.compareAndSet(false, true)) { update { it.copy(skippedBusy = it.skippedBusy + 1) }; return false }
        update { it.copy(busy = true) }
        try {
            exec.execute { shoot(c, f) }
        } catch (e: Exception) {
            busy.set(false)
            update { it.copy(busy = false) }
            return false
        }
        return true
    }

    /** The ride was paused / the fix lost: the next moving fix starts counting again (no distance across the gap). */
    fun reset() { prev = null }

    private fun shoot(c: OscClient, f: ShotFix) {
        var shot: ThetaShot
        var gpsError: String? = null
        try {
            try { c.setOptions(JSONObject().put("gpsInfo", ThetaOsc.gpsInfo(f.lat, f.lon, f.altitudeM, now(), zone))) }
            catch (e: OscException) { gpsError = "gpsInfo: ${e.short()}" }
            val t = now()
            shot = try {
                val (id, url) = c.takePictureAndWait(pollMs, maxShotMs, sleep, now)
                if (url == null) ThetaShot(t, f.lat, f.lon, f.accuracyM, f.bearing, f.speedMps, id, null, false, "no fileUrl")
                else ThetaShot(t, f.lat, f.lon, f.accuracyM, f.bearing, f.speedMps, id, url, true, gpsError)
            } catch (e: OscException) {
                ThetaShot(t, f.lat, f.lon, f.accuracyM, f.bearing, f.speedMps, null, null, false, e.short())
            }
        } catch (e: Exception) {
            shot = ThetaShot(now(), f.lat, f.lon, f.accuracyM, f.bearing, f.speedMps, null, null, false, e.toString())
        }
        busy.set(false)
        update {
            if (shot.ok) it.copy(shots = it.shots + 1, busy = false, lastFile = shot.fileName, lastError = shot.error ?: it.lastError)
            else it.copy(failed = it.failed + 1, busy = false, lastError = shot.error)
        }
        onShot(shot)
    }
}

/** `session.camera360` fields of a phone-controlled camera (docs/camera360.md §4). */
object ThetaRide {
    fun control(c: JSONObject?): String? = c?.optStr("control")
    fun isTheta(c: JSONObject?) = control(c) == ThetaOsc.CONTROL
    fun everyM(c: JSONObject?): Double = c?.optDouble("everyM")?.takeIf { !it.isNaN() && it > 0 } ?: ThetaOsc.DEFAULT_EVERY_M

    /** A fresh `session.camera360` for a Theta ride. */
    fun start(everyM: Double): JSONObject = Camera360.empty().put("control", ThetaOsc.CONTROL).put("everyM", everyM)
        .put("shots", JSONArray())

    fun withCamera(c: JSONObject, info: OscInfo): JSONObject = c.put("camera", info.toJson())

    fun withShot(c: JSONObject, shot: ThetaShot): JSONObject {
        val a = c.optJSONArray("shots") ?: JSONArray().also { c.put("shots", it) }
        a.put(shot.toJson())
        return c
    }

    /** Skipped triggers are no shot: kept as counts (busy, offline) so a restarted ride goes on from them. */
    fun withSkipped(c: JSONObject, busy: Int, offline: Int): JSONObject = c.put("skippedBusy", busy).put("skippedOffline", offline)

    /** (busy, offline) skipped triggers saved so far. */
    fun skipped(c: JSONObject?): Pair<Int, Int> = (c?.optInt("skippedBusy", 0) ?: 0) to (c?.optInt("skippedOffline", 0) ?: 0)

    /** (ok, failed) shots so far. */
    fun counts(c: JSONObject?): Pair<Int, Int> {
        val a = c?.optJSONArray("shots") ?: return 0 to 0
        val ok = (0 until a.length()).count { a.optJSONObject(it)?.optBoolean("ok") == true }
        return ok to a.length() - ok
    }
}
