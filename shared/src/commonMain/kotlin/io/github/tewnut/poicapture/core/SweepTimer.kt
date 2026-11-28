package io.github.tewnut.poicapture.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.round

/** How one analysis frame went ([FrameSlide.next]). NaN = unknown. */
class FrameReading(
    /** slide since the previous frame, fraction of the frame width (signed); NaN when the frames could not tell */
    val shift: Double,
    /** Laplacian variance of the frame (BlurCheck.sharpness) */
    val sharpness: Double,
    /** how the frame matched its reference (frames.csv); -1 / NaN when it became the first reference */
    val textured: Int,
    val votes: Int,
    val agreeing: Int,
    val bestCorrelation: Double,
)

/**
 * The slide of the scene on the camera frames (Android FrameWatcher, its pure part): give it each small grayscale
 * frame, already turned so that the photo's horizontal runs along x (about 256-320 px wide).
 */
class FrameSlide {
    private val tracker = SlideTracker()
    private var prevPortrait = false

    fun next(gray: IntArray, w: Int, h: Int, portrait: Boolean): FrameReading {
        if (w < 8 || h < FrameShift.BANDS || gray.size < w * h) return FrameReading(Double.NaN, 0.0, -1, -1, -1, Double.NaN)
        if (prevPortrait != portrait) tracker.reset()
        prevPortrait = portrait
        val shift = tracker.next(FrameShift.profiles(gray, w, h, FrameShift.BANDS))
        val m = tracker.last
        return FrameReading(shift ?: Double.NaN, BlurCheck.sharpness(gray, w, h), m?.textured ?: -1, m?.votes ?: -1,
            m?.agreeing ?: -1, m?.bestCorrelation ?: Double.NaN)
    }
}

/**
 * When to take the next street-sweep photo (Android CaptureService.onFrame / maybeShoot, one implementation for the
 * iPhone): the slide measured on the camera frames, the GPS speed where the frames cannot tell (or a signs ride, which
 * looks ahead: by distance), nothing while waiting (< 0.7 m/s), and inside the window ([OverlapTrigger]) a steady
 * moment (a frame as sharp as usual, the phone not turning faster than usual). The photo waits while the camera aims
 * at the road or, held by hand, swings round. Unknown numbers are NaN.
 */
class SweepShotTimer(target: String, val hand: Boolean) {
    val target: SweepTarget = SweepTarget.of(target)
    private val trigger = OverlapTrigger(this.target.earliest, this.target.latest)
    private val frameSharpness = RecentMedian(30)
    private val gyroRecent = RecentMedian(60)
    private var accountedNs = 0L
    /** elapsed-time clock of the last analysed frame (ns); 0 = none yet */
    var lastFrameNs: Long = 0L; private set
    private var pending: String = ""
    private var lastShotSlide = 0.0
    private var lastMissedMs = 0L

    /** how the last frame's slide was counted: "frame", "gps", "gps-late", "distance", "wait" (frames.csv `used`) */
    var used: String = ""; private set
    var steady: Boolean = false; private set
    /** the camera points at the road (mounted below −45°, by hand below −35°) */
    var aimLow: Boolean = false; private set
    /** held by hand and turning faster than 1.2 rad/s */
    var swinging: Boolean = false; private set

    /** slide since the last photo, fraction of the frame width */
    val progress: Double get() = trigger.progress

    /** Recording starts (or resumes after a pause): the first photo is due right away. */
    fun start(nowNs: Long) {
        trigger.start()
        accountedNs = nowNs
    }

    /**
     * One analysis frame at [tNs] (elapsed-time clock): [shift] its slide (NaN unknown), [sharpness], [gyroRadS] the
     * fastest turn rate of the last 100 ms, [elevationDeg] the camera's elevation, [speedMps] the GPS speed when the
     * fix is fresh. [running]: recording (not paused). [busy]: the previous photo is still being saved. [coverageM] /
     * [coveragePerM]: from the plan. Returns true when a photo is due now (then call [shot] and take it).
     */
    fun onFrame(tNs: Long, shift: Double, sharpness: Double, gyroRadS: Double, elevationDeg: Double, speedMps: Double,
                running: Boolean, busy: Boolean, coverageM: Double, coveragePerM: Double, nowMs: Long): Boolean {
        lastFrameNs = tNs
        val gyro = gyroRadS.takeIf { !it.isNaN() }
        val sharpMedian = frameSharpness.median
        val gyroMedian = gyroRecent.median
        steady = (sharpMedian == null || sharpness >= 0.9 * sharpMedian) && (gyro == null || gyroMedian == null || gyro <= gyroMedian)
        aimLow = !elevationDeg.isNaN() && elevationDeg < (if (hand) HAND_MIN_ELEVATION_DEG else MOUNT_MIN_ELEVATION_DEG)
        swinging = hand && gyro != null && gyro > HAND_SWING_RADS
        frameSharpness.add(sharpness)
        gyro?.let { gyroRecent.add(it) }
        if (!running) { accountedNs = tNs; used = ""; return false }
        val dt = (tNs - accountedNs) / 1e9
        accountedNs = tNs
        val speed = speedMps.takeIf { !it.isNaN() }
        used = when {
            speed != null && speed < 0.7 -> "wait"                 // waiting (lights, traffic): passing vehicles are not new facade
            target.byDistance -> { trigger.addSpeed(speed ?: 0.0, dt, coverageM); "distance" }
            !shift.isNaN() && dt < 0.4 -> {
                trigger.addFrameShift(shift)
                trigger.floor(shift, speed ?: 0.0, dt, coveragePerM)   // never as if the fronts were > 40 m away
                "frame"
            }
            else -> { trigger.addSpeed(speed ?: 0.0, dt, coverageM); if (!shift.isNaN()) "gps-late" else "gps" }
        }
        if (aimLow || swinging) return false   // the slide keeps counting: the photo comes as soon as the aim is back
        return due(steady, gyro, sharpness, sharpMedian, busy, nowMs)
    }

    /**
     * No camera frame for a second (no analysis stream, or it stalled): count the slide from the GPS speed instead.
     * Returns true when a photo is due now.
     */
    fun onGps(nowNs: Long, speedMps: Double, running: Boolean, busy: Boolean, coverageM: Double, nowMs: Long): Boolean {
        if (!running) { accountedNs = nowNs; return false }
        if (lastFrameNs != 0L && nowNs - lastFrameNs <= 1_000_000_000L) return false
        trigger.addSpeed(speedMps.takeIf { !it.isNaN() } ?: 0.0, (nowNs - accountedNs) / 1e9, coverageM)
        accountedNs = nowNs
        return due(false, null, null, null, busy, nowMs)
    }

    private fun due(steady: Boolean, gyro: Double?, sharp: Double?, sharpMedian: Double?, busy: Boolean, nowMs: Long): Boolean {
        if (!trigger.due(steady)) return false
        if (busy) {   // the previous photo is still being saved
            if (trigger.behind) lastMissedMs = nowMs
            return false
        }
        pending = buildJsonObject {
            put("slide", round(trigger.progress * 1000) / 1000.0)            // fraction of the frame since the last photo
            put("measured", round(trigger.measuredShare * 100) / 100.0)      // share of it measured on the frames
            put("steady", steady)
            put("gyroRadS", if (gyro != null) JsonPrimitive(round(gyro * 1000) / 1000.0) else JsonNull)
            val ratio = if (sharp != null && sharpMedian != null && sharpMedian > 0) round(sharp / sharpMedian * 100) / 100.0 else null
            put("frameSharpness", if (ratio != null) JsonPrimitive(ratio) else JsonNull)
        }.toString()
        return true
    }

    /** The photo is being taken: its `overlap` JSON; the count starts again. */
    fun shot(): String {
        val p = pending
        pending = ""
        lastShotSlide = trigger.shot()
        return p
    }

    /** The photo came out unusable (dropped): its slide still has to be covered, so the next one comes sooner. */
    fun retake() { trigger.retake(lastShotSlide) }

    /** An auto photo was due while the previous one was still being saved, in the last 10 s ("ride slower"). */
    fun missedRecently(nowMs: Long): Boolean = lastMissedMs != 0L && nowMs - lastMissedMs < 10_000

    /** One frames.csv line (Android CaptureService.logFrame). */
    fun frameLine(tNs: Long, portrait: Boolean, r: FrameReading, brightness: Double, speedMps: Double, bufferW: Int, bufferH: Int): String {
        fun f(v: Double, k: Int): String {
            if (v.isNaN()) return ""
            if (k == 0) return round(v).toLong().toString()
            var m = 1.0
            repeat(k) { m *= 10 }
            val x = round(v * m) / m
            val s = x.toString()
            val dot = s.indexOf('.')
            if (s.contains('E') || s.contains('e')) return s
            return if (dot < 0) s + "." + "0".repeat(k) else s + "0".repeat((k - (s.length - dot - 1)).coerceAtLeast(0))
        }
        fun i(v: Int) = if (v < 0) "" else v.toString()
        return "$tNs,${if (portrait) 1 else 0},${f(r.shift, 5)},${i(r.textured)},${i(r.votes)},${i(r.agreeing)}," +
            "${f(r.bestCorrelation, 3)},${f(r.sharpness, 1)},${f(brightness, 0)},${f(speedMps, 2)},$used," +
            "${f(trigger.progress, 3)},${if (steady) 1 else 0},$bufferW,$bufferH"
    }

    /** The header line of frames.csv. */
    fun framesHeader(): String = FRAMES_HEADER

    companion object {
        const val FRAMES_HEADER = "elapsedNanos,portrait,shift,textured,votes,agreeing,bestCorrelation,sharpness,brightness," +
            "speedMps,used,slide,steady,bufferW,bufferH"
        const val MOUNT_MIN_ELEVATION_DEG = -45.0
        const val HAND_MIN_ELEVATION_DEG = -35.0
        const val HAND_SWING_RADS = 1.2
    }
}
