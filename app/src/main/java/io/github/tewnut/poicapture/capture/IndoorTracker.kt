package io.github.tewnut.poicapture.capture

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import io.github.tewnut.poicapture.core.Building
import io.github.tewnut.poicapture.core.Fix
import io.github.tewnut.poicapture.core.FloorDetector
import io.github.tewnut.poicapture.core.Geo
import io.github.tewnut.poicapture.core.LatLon
import io.github.tewnut.poicapture.core.Levels
import io.github.tewnut.poicapture.core.Pdr
import io.github.tewnut.poicapture.core.StepDetector
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Indoor mode's sensors (docs/indoor.md §2-3): steps (step detector, else peaks of the accelerometer), the yaw from the
 * game rotation vector (gyro + accelerometer, no compass), the barometer for floors, feeding [Pdr] and [FloorDetector];
 * everything raw goes to indoor.jsonl so processing can redo the walk. Sensor callbacks run on their own thread; the
 * capture service and the screen call in from the main thread (all state behind this object's lock).
 */
class IndoorTracker(
    private val sm: SensorManager,
    file: File,
    val pdr: Pdr,
    val building: Building?,
    startLevel: Int,
    /** step detector events are delivered only with the activity-recognition permission (Android 10+) */
    private val stepPermission: Boolean,
    /** called on the sensor thread after each step that moved the position */
    private val onMove: () -> Unit,
    /** called on the sensor thread when the barometer suggests a change of level (the new level) */
    private val onFloor: (Int) -> Unit,
) : SensorEventListener {
    private val out: BufferedWriter = file.also { it.parentFile?.mkdirs() }.let { java.io.FileWriter(it, true).buffered() }
    private val thread = HandlerThread("indoor").apply { start() }
    private val floor = FloorDetector()
    private val accelSteps = StepDetector()
    private var useDetector = false
    private var detectorSteps = 0
    private var accelCount = 0
    private var lastFlushNs = 0L
    private var lastYawLogNs = 0L
    private var lastPressureNs = 0L
    private var compassDiffs = ArrayDeque<Double>()   // compass − game yaw, for the alignment at the start
    var aligned = false; private set
    private var lastGpsAnchorNs = 0L
    private var lastCourseNs = 0L

    @Volatile var level: Int = startLevel; private set
    /** "detector" | "accelerometer" */
    @Volatile var stepSource: String = "accelerometer"; private set
    val hasBarometer = sm.getDefaultSensor(Sensor.TYPE_PRESSURE) != null
    private val hasGame = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null

    fun start() {
        val h = Handler(thread.looper)
        val det = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        useDetector = det != null && stepPermission
        stepSource = if (useDetector) "detector" else "accelerometer"
        if (useDetector) sm.registerListener(this, det, SensorManager.SENSOR_DELAY_FASTEST, h)
        for (t in listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR))
            sm.getDefaultSensor(t)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, h) }
        sm.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, h) }
        // no game rotation vector: the plain one (with compass) is the yaw, already pointing north
        if (!hasGame) aligned = true
        log(JSONObject().put("t", now()).put("type", "level").put("level", Levels.name(level)).put("how", "picked"))
    }

    fun stop() {
        sm.unregisterListener(this)
        thread.quitSafely()
        synchronized(this) { runCatching { out.close() } }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> { detectorSteps++; step(e.timestamp, "detector") }
            Sensor.TYPE_ACCELEROMETER -> {
                val v = e.values
                val t = accelSteps.add(e.timestamp, sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble())) ?: return
                accelCount++
                // the step detector says nothing while the accelerometer counts steps: some phones batch or withhold it
                if (useDetector && detectorSteps == 0 && accelCount >= FALLBACK_STEPS) {
                    useDetector = false; stepSource = "accelerometer"
                }
                if (!useDetector) step(t, "accelerometer")
            }
            Sensor.TYPE_GAME_ROTATION_VECTOR -> yaw(e.values)?.let { y ->
                synchronized(this) {
                    pdr.onYaw(y)
                    if (e.timestamp - lastYawLogNs >= 200_000_000L) {   // ~5 Hz
                        lastYawLogNs = e.timestamp
                        pdr.headingDeg?.let { log(JSONObject().put("t", e.timestamp).put("type", "yaw").put("deg", r1(it))) }
                    }
                }
            }
            Sensor.TYPE_ROTATION_VECTOR -> yaw(e.values)?.let { c ->
                synchronized(this) {
                    if (!hasGame) {   // no gyro-only yaw: the compass one
                        pdr.onYaw(c); return@synchronized
                    }
                    if (aligned) return@synchronized
                    val raw = pdr.rawYawDeg ?: return@synchronized
                    compassDiffs.addLast(Pdr.norm180(c - raw))
                    // ~2 s of the compass at the start (outside the building or at its door)
                    if (compassDiffs.size >= 100) {
                        val off = circularMedian(compassDiffs.toList())
                        pdr.alignTo(Geo.norm360(raw + off), raw)
                        aligned = true
                        log(JSONObject().put("t", e.timestamp).put("type", "align").put("offsetDeg", r1(pdr.offsetDeg)).put("how", "compass"))
                    }
                }
            }
            Sensor.TYPE_PRESSURE -> {
                if (e.timestamp - lastPressureNs < 1_000_000_000L) return   // ~1 Hz
                lastPressureNs = e.timestamp
                val p = e.values[0].toDouble()
                val suggest = synchronized(this) {
                    log(JSONObject().put("t", e.timestamp).put("type", "pressure").put("hPa", Math.round(p * 100) / 100.0))
                    floor.add(e.timestamp, p)
                }
                if (suggest != null) onFloor(level + suggest)
            }
        }
        if (e.timestamp - lastFlushNs > 1_000_000_000L) synchronized(this) { lastFlushNs = e.timestamp; runCatching { out.flush() } }
    }

    private fun step(t: Long, src: String) {
        val moved = synchronized(this) {
            log(JSONObject().put("t", t).put("type", "step").put("src", src))
            pdr.onStep()
        }
        if (moved) onMove()
    }

    /**
     * "I am here": a tap on the map (how = "tap") or a GPS fix outdoors ("gps"); with a direction ([headingDeg], a
     * second tap toward where the walker goes) the yaw is re-aligned too.
     */
    @Synchronized
    fun anchor(at: LatLon, lvl: Int, how: String, headingDeg: Double? = null): Pdr.AnchorResult {
        val t = now()
        if (lvl != level) setLevel(lvl, "picked")
        log(JSONObject().put("t", t).put("type", "anchor").put("lat", at.lat).put("lon", at.lon).put("level", Levels.name(lvl))
            .put("how", how).put("headingDeg", headingDeg?.let { r1(it) } ?: JSONObject.NULL))
        val r = pdr.anchor(at, null)
        if (r.rotationDeg != null || r.stepLengthM != null) log(JSONObject().put("t", t).put("type", "align")
            .put("offsetDeg", r1(pdr.offsetDeg)).put("how", "anchors").put("stepLengthM", Math.round(pdr.stepLengthM * 1000) / 1000.0))
        if (headingDeg != null) setHeading(headingDeg, "drag")
        return r
    }

    /** The walking direction now is [deg] (a drag on the map, the GPS course outside). */
    @Synchronized
    fun setHeading(deg: Double, how: String) {
        if (pdr.alignTo(deg) == null) return
        aligned = true
        log(JSONObject().put("t", now()).put("type", "align").put("offsetDeg", r1(pdr.offsetDeg)).put("how", how))
    }

    @Synchronized
    fun setLevel(l: Int, how: String) {
        level = l
        floor.rebase()
        log(JSONObject().put("t", now()).put("type", "level").put("level", Levels.name(l)).put("how", how))
    }

    /** The barometer's suggestion was turned down: the pressure now is this level's. */
    @Synchronized fun keepLevel() = floor.rebase()

    /**
     * A GPS fix: logged; outdoors (≤ [GPS_ANCHOR_ACC_M], outside the footprint) it is an anchor by itself, at most
     * every few seconds, and while walking its course aligns the yaw. Inside, or without a building once anchored, the
     * GPS is not trusted. Returns true when it became an anchor.
     */
    @Synchronized
    fun onFix(f: Fix): Boolean {
        val t = f.elapsedNanos ?: now()
        log(JSONObject().put("t", t).put("type", "gps").put("lat", f.lat).put("lon", f.lon).put("acc", r1(f.accuracyM))
            .put("spd", f.speedMps ?: JSONObject.NULL).put("brg", f.bearingDeg?.let { r1(it) } ?: JSONObject.NULL))
        if (f.accuracyM > GPS_ANCHOR_ACC_M) return false
        val outside = building?.let { !it.contains(f.ll) } ?: !pdr.anchored
        if (!outside) return false
        val b = f.bearingDeg
        if (b != null && (f.speedMps ?: 0.0) >= 0.8 && t - lastCourseNs >= 10_000_000_000L && pdr.rawYawDeg != null) {
            lastCourseNs = t
            setHeading(b, "gps-course")
        }
        if (t - lastGpsAnchorNs < 5_000_000_000L && pdr.anchored) return false
        lastGpsAnchorNs = t
        anchor(f.ll, level, "gps")
        return true
    }

    private fun log(o: JSONObject) { runCatching { out.write(o.toString()); out.write("\n") } }

    private fun now() = android.os.SystemClock.elapsedRealtimeNanos()

    /**
     * The walk's direction from a rotation vector: the camera's (−Z) when the phone is held up (the camera within 60°
     * of level), else the top edge's (+Y) when it lies flat in the hand (reading the map). Degrees, the vector's zero.
     */
    private fun yaw(v: FloatArray): Double? {
        val rm = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rm, v)
        val elev = Math.toDegrees(asin((-rm[8].toDouble()).coerceIn(-1.0, 1.0)))
        val (e, n) = if (kotlin.math.abs(elev) <= 60) -rm[2].toDouble() to -rm[5].toDouble() else rm[1].toDouble() to rm[4].toDouble()
        if (e * e + n * n < 0.04) return null
        return Geo.norm360(Math.toDegrees(atan2(e, n)))
    }

    private fun circularMedian(d: List<Double>): Double {
        val ref = d.first()
        return d.map { ref + Pdr.norm180(it - ref) }.sorted()[d.size / 2]
    }

    private fun r1(d: Double) = Math.round(d * 10) / 10.0

    companion object {
        const val GPS_ANCHOR_ACC_M = 10.0
        /** accelerometer steps with no step-detector event before switching to the accelerometer */
        const val FALLBACK_STEPS = 12
    }
}
