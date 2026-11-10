package io.github.tewnut.poicapture.capture

import android.annotation.SuppressLint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssMeasurement
import android.location.GnssMeasurementRequest
import android.location.GnssMeasurementsEvent
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONObject
import io.github.tewnut.poicapture.core.MountEstimator
import java.io.BufferedWriter
import java.io.File
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * Raw GNSS measurements → gnss_raw.csv, one "Raw" row per satellite signal per epoch. Column names follow Google's
 * GnssLogger "Raw" records; the order has not been checked against GnssLogger output, so readers should use the header. Full tracking (no duty cycling, continuous
 * carrier phase) is requested on Android 12+. Counts tell whether this phone delivers carrier phase (ADR) at all.
 */
class GnssRecorder(private val lm: LocationManager, file: File) {
    private val out: BufferedWriter = file.bufferedWriter()
    val epochs = AtomicLong()
    val measurements = AtomicLong()
    val adrValid = AtomicLong()
    val l5 = AtomicLong()          // measurements on the second frequency (L5 / E5a / B2a, ~1176 MHz)
    private var started = false

    private val callback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) = write(event)
    }

    init {
        out.write("# poi-capture raw GNSS log (GnssLogger-style Raw rows)\n")
        out.write("# Raw,$COLUMNS\n")
    }

    @SuppressLint("MissingPermission")
    fun start(executor: Executor): Boolean {
        started = try {
            if (Build.VERSION.SDK_INT >= 31) {
                lm.registerGnssMeasurementsCallback(GnssMeasurementRequest.Builder().setFullTracking(true).build(), executor, callback)
            } else {
                lm.registerGnssMeasurementsCallback(executor, callback)
            }
        } catch (e: Exception) { false }
        return started
    }

    fun stop() {
        if (started) lm.unregisterGnssMeasurementsCallback(callback)
        started = false
        synchronized(out) { runCatching { out.close() } }
    }

    fun summary(): JSONObject = JSONObject()
        .put("epochs", epochs.get()).put("measurements", measurements.get()).put("adrValid", adrValid.get())
        .put("carrierPhase", adrValid.get() > 0)
        .put("l5Measurements", l5.get()).put("dualFrequency", l5.get() > 0)

    private fun write(e: GnssMeasurementsEvent) {
        val c = e.clock
        val utc = System.currentTimeMillis()
        val sb = StringBuilder()
        for (m in e.measurements) {
            if (m.accumulatedDeltaRangeState and GnssMeasurement.ADR_STATE_VALID != 0) adrValid.incrementAndGet()
            measurements.incrementAndGet()
            if (m.hasCarrierFrequencyHz() && m.carrierFrequencyHz < 1.3e9f) l5.incrementAndGet()
            val cols = listOf(
                utc, c.timeNanos,
                if (c.hasLeapSecond()) c.leapSecond else "",
                if (c.hasTimeUncertaintyNanos()) c.timeUncertaintyNanos else "",
                if (c.hasFullBiasNanos()) c.fullBiasNanos else "",
                if (c.hasBiasNanos()) c.biasNanos else "",
                if (c.hasBiasUncertaintyNanos()) c.biasUncertaintyNanos else "",
                if (c.hasDriftNanosPerSecond()) c.driftNanosPerSecond else "",
                if (c.hasDriftUncertaintyNanosPerSecond()) c.driftUncertaintyNanosPerSecond else "",
                c.hardwareClockDiscontinuityCount,
                m.svid, m.timeOffsetNanos, m.state, m.receivedSvTimeNanos, m.receivedSvTimeUncertaintyNanos,
                m.cn0DbHz, m.pseudorangeRateMetersPerSecond, m.pseudorangeRateUncertaintyMetersPerSecond,
                m.accumulatedDeltaRangeState, m.accumulatedDeltaRangeMeters, m.accumulatedDeltaRangeUncertaintyMeters,
                if (m.hasCarrierFrequencyHz()) m.carrierFrequencyHz else "",
                "", "", "",   // CarrierCycles, CarrierPhase, CarrierPhaseUncertainty (deprecated by Android)
                m.multipathIndicator,
                if (m.hasSnrInDb()) m.snrInDb else "",
                m.constellationType,
                "",           // AgcDb (deprecated in Android 13)
                if (m.hasBasebandCn0DbHz()) m.basebandCn0DbHz else "",
                if (m.hasFullInterSignalBiasNanos()) m.fullInterSignalBiasNanos else "",
                if (m.hasFullInterSignalBiasUncertaintyNanos()) m.fullInterSignalBiasUncertaintyNanos else "",
                if (m.hasSatelliteInterSignalBiasNanos()) m.satelliteInterSignalBiasNanos else "",
                if (m.hasSatelliteInterSignalBiasUncertaintyNanos()) m.satelliteInterSignalBiasUncertaintyNanos else "",
                if (m.hasCodeType()) m.codeType else "",
                if (c.hasElapsedRealtimeNanos()) c.elapsedRealtimeNanos else "",
            )
            sb.append("Raw,").append(cols.joinToString(",")).append('\n')
        }
        epochs.incrementAndGet()
        synchronized(out) { runCatching { out.write(sb.toString()); out.flush() } }
    }

    companion object {
        const val COLUMNS = "utcTimeMillis,TimeNanos,LeapSecond,TimeUncertaintyNanos,FullBiasNanos,BiasNanos," +
            "BiasUncertaintyNanos,DriftNanosPerSecond,DriftUncertaintyNanosPerSecond,HardwareClockDiscontinuityCount," +
            "Svid,TimeOffsetNanos,State,ReceivedSvTimeNanos,ReceivedSvTimeUncertaintyNanos,Cn0DbHz," +
            "PseudorangeRateMetersPerSecond,PseudorangeRateUncertaintyMetersPerSecond,AccumulatedDeltaRangeState," +
            "AccumulatedDeltaRangeMeters,AccumulatedDeltaRangeUncertaintyMeters,CarrierFrequencyHz,CarrierCycles," +
            "CarrierPhase,CarrierPhaseUncertainty,MultipathIndicator,SnrInDb,ConstellationType,AgcDb," +
            "BasebandCn0DbHz,FullInterSignalBiasNanos,FullInterSignalBiasUncertaintyNanos," +
            "SatelliteInterSignalBiasNanos,SatelliteInterSignalBiasUncertaintyNanos,CodeType,ChipsetElapsedRealtimeNanos"
    }
}

/**
 * Gyroscope, accelerometer and rotation vector at ~50 Hz → imu.csv (timestamps = elapsedRealtimeNanos, the same
 * base as location fixes, GNSS and photos). Keeps the last second in memory to describe the shake at each photo.
 */
class ImuRecorder(private val sm: SensorManager, file: File, private val mount: MountEstimator? = null) : SensorEventListener {
    // camera-direction estimator feed: virtual gravity / linear acceleration sensors when the phone has them, else a
    // ~1 s low-pass of the accelerometer as gravity and accelerometer − gravity as linear acceleration
    private val hasGravity = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) != null
    private val hasLinear = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) != null
    private val lowPass = FloatArray(3)
    private var lowPassReady = false
    private val out: BufferedWriter = file.bufferedWriter()
    private val thread = HandlerThread("imu").apply { start() }
    private val gyro = ArrayDeque<Pair<Long, Float>>()   // (t, |ω| rad/s)
    private val acc = ArrayDeque<Pair<Long, Float>>()    // (t, |a| m/s²)
    private val rot = FloatArray(5)
    private var hasRot = false
    private var lastFlush = 0L

    init { out.write("sensor,elapsedNanos,x,y,z,w\n") }

    fun start() {
        val h = Handler(thread.looper)
        for (type in listOf(Sensor.TYPE_GYROSCOPE, Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_GRAVITY, Sensor.TYPE_LINEAR_ACCELERATION)) {
            sm.getDefaultSensor(type)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, h) }
        }
    }

    fun stop() {
        sm.unregisterListener(this)
        thread.quitSafely()
        synchronized(this) { runCatching { out.close() } }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        val v = e.values
        feedMount(e)
        val tag = when (e.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> "g"
            Sensor.TYPE_ACCELEROMETER -> "a"
            Sensor.TYPE_ROTATION_VECTOR -> "r"
            else -> return          // gravity / linear acceleration only feed the estimator (derivable from the log)
        }
        synchronized(this) {
            when (tag) {
                "g" -> push(gyro, e.timestamp, sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]))
                "a" -> push(acc, e.timestamp, sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]))
                "r" -> { for (i in 0 until minOf(5, v.size)) rot[i] = v[i]; hasRot = true }
            }
            runCatching {
                out.write(String.format(Locale.ROOT, "%s,%d,%.5f,%.5f,%.5f,%s\n", tag, e.timestamp, v[0], v[1], v[2],
                    if (tag == "r" && v.size > 3) String.format(Locale.ROOT, "%.5f", v[3]) else ""))
                if (e.timestamp - lastFlush > 1_000_000_000L) { out.flush(); lastFlush = e.timestamp }
            }
        }
    }

    private fun feedMount(e: SensorEvent) {
        val m = mount ?: return
        val v = e.values
        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY -> m.addGravity(v[0], v[1], v[2])
            Sensor.TYPE_LINEAR_ACCELERATION -> m.addLinear(v[0], v[1], v[2])
            Sensor.TYPE_GYROSCOPE -> m.addGyro(v[0], v[1], v[2])
            Sensor.TYPE_ACCELEROMETER -> if (!hasGravity || !hasLinear) {
                val k = 0.02f                                   // ~1 s time constant at 50 Hz
                if (!lowPassReady) { v.copyInto(lowPass, 0, 0, 3); lowPassReady = true }
                for (i in 0..2) lowPass[i] += k * (v[i] - lowPass[i])
                if (!hasGravity) m.addGravity(lowPass[0], lowPass[1], lowPass[2])
                if (!hasLinear) m.addLinear(v[0] - lowPass[0], v[1] - lowPass[1], v[2] - lowPass[2])
            }
        }
    }

    private fun push(q: ArrayDeque<Pair<Long, Float>>, t: Long, x: Float) {
        q.addLast(t to x)
        while (q.isNotEmpty() && t - q.first().first > 1_000_000_000L) q.removeFirst()
    }

    /** Fastest turn rate (rad/s) over the 100 ms before `nowNanos`: shake right now; null without a gyroscope. */
    @Synchronized
    fun gyroNow(nowNanos: Long): Double? =
        gyro.filter { nowNanos - it.first <= 100_000_000L }.maxOfOrNull { it.second.toDouble() }

    /** Shake around `nowNanos`: max gyro rate over the last 300 ms, accel std over the last second, device orientation. */
    @Synchronized
    fun snapshot(nowNanos: Long): JSONObject {
        val o = JSONObject()
        val g = gyro.filter { nowNanos - it.first <= 300_000_000L }.map { it.second }
        if (g.isNotEmpty()) o.put("gyroMaxRadS", g.max().toDouble().r3())
        if (acc.size > 2) {
            val m = acc.map { it.second }.average()
            val sd = sqrt(acc.map { (it.second - m) * (it.second - m) }.average())
            o.put("accelStdMs2", sd.r3())
        }
        if (hasRot) {
            val rm = FloatArray(9)
            val ori = FloatArray(3)
            SensorManager.getRotationMatrixFromVector(rm, rot)
            SensorManager.getOrientation(rm, ori)
            // device frame, magnetic north: reference only (the magnetometer is disturbed on a motorbike)
            // rear camera direction in the world (east, north, up): −(device Z axis) = −(3rd column of R)
            val ce = -rm[2].toDouble()
            val cn = -rm[5].toDouble()
            if (ce * ce + cn * cn > 0.04) o.put("cameraAzimuthDeg", ((Math.toDegrees(kotlin.math.atan2(ce, cn)) + 360) % 360).r3())
            o.put("deviceAzimuthDeg", Math.toDegrees(ori[0].toDouble()).r3())
                .put("devicePitchDeg", Math.toDegrees(ori[1].toDouble()).r3())
                .put("deviceRollDeg", Math.toDegrees(ori[2].toDouble()).r3())
        }
        return o
    }

    private fun Double.r3() = Math.round(this * 1000) / 1000.0
}
