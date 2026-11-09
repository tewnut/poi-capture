package io.github.tewnut.poicapture.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthetic rides: a phone mounted on the vehicle with a known orientation; the sensor values it would report are
 * generated from that orientation, with Android's conventions:
 * - world frame: x = forward (vehicle), y = left, z = up;
 * - device axes X/Y/Z expressed in the world frame; a world vector w reads (w·X, w·Y, w·Z) on the device;
 * - TYPE_GRAVITY points up (9.81), TYPE_LINEAR_ACCELERATION is the true acceleration, rear camera looks along −Z.
 */
class MountEstimatorTest {
    private data class V(val x: Double, val y: Double, val z: Double) {
        operator fun plus(o: V) = V(x + o.x, y + o.y, z + o.z)
        operator fun times(k: Double) = V(x * k, y * k, z * k)
        fun dot(o: V) = x * o.x + y * o.y + z * o.z
        fun cross(o: V) = V(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    }

    /** Device axes for a landscape-mounted phone whose rear camera looks `yaw` clockwise from forward, tilted down `pitchDown`. */
    private fun mount(yawDeg: Double, pitchDownDeg: Double = 0.0): Triple<V, V, V> {
        val y = Math.toRadians(yawDeg)
        val p = Math.toRadians(pitchDownDeg)
        val cam = V(cos(y) * cos(p), -sin(y) * cos(p), -sin(p))       // clockwise from forward = toward −y (right)
        val z = cam * -1.0                                              // rear camera looks along −Z
        val up = V(0.0, 0.0, 1.0)
        val x = up + z * -(up.dot(z))                                   // landscape: device X roughly vertical
        val xn = x * (1 / kotlin.math.sqrt(x.dot(x)))
        val yAxis = z.cross(xn)                                         // right-handed: X × Y = Z
        return Triple(xn, yAxis, z)
    }

    private fun read(w: V, axes: Triple<V, V, V>) = Triple(w.dot(axes.first), w.dot(axes.second), w.dot(axes.third))

    /**
     * Ride: per second one GPS fix; events = list of longitudinal accelerations (m/s², + speeding up, − braking);
     * 0 = cruising. `turnRate` rad/s around up during the second (cornering), `noise` = vibration std (m/s²).
     */
    private fun ride(est: MountEstimator, axes: Triple<V, V, V>, events: List<Double>, noise: Double = 0.0,
                     turn: List<Double> = emptyList(), lateral: List<Double> = emptyList(), rnd: Random = Random(1),
                     axesAt: (Int) -> Triple<V, V, V> = { axes }, doppler: Boolean = true, startSpeed: Double = 5.0) {
        var speed = startSpeed
        var t = 0L
        var bearing = 90.0
        est.onFix(t, speed, bearing)
        events.forEachIndexed { i, a ->
            val ax = axesAt(i)
            val yawRate = turn.getOrElse(i) { 0.0 }
            val lat = lateral.getOrElse(i) { 0.0 }
            fun n() = if (noise > 0) rnd.nextDouble(-noise, noise) * 1.7 else 0.0
            repeat(50) {
                val w = V(a + n(), lat + n(), n())
                val (lx, ly, lz) = read(w, ax)
                est.addLinear(lx.toFloat(), ly.toFloat(), lz.toFloat())
                val (gx, gy, gz) = read(V(0.0, 0.0, 9.81), ax)
                est.addGravity(gx.toFloat(), gy.toFloat(), gz.toFloat())
                val (rx, ry, rz) = read(V(0.0, 0.0, yawRate), ax)
                est.addGyro(rx.toFloat(), ry.toFloat(), rz.toFloat())
            }
            speed += a
            bearing = Geo.norm360(bearing - Math.toDegrees(yawRate))
            t += 1_000_000_000L
            est.onFix(t, if (doppler) speed else null, bearing)
        }
    }

    private val stopAndGo = listOf(1.0, 1.2, 0.0, -1.0, -0.8, 0.9, 0.0, 1.1, -1.2, 0.0, 0.8, -0.9)

    private fun estimateFor(yaw: Double, pitch: Double = 0.0, noise: Double = 0.0): MountEstimate? =
        MountEstimator().also { ride(it, mount(yaw, pitch), stopAndGo, noise) }.estimate()

    @Test fun cameraLookingRightLeftFrontBack() {
        assertEquals(90.0, estimateFor(90.0)!!.yawDeg, 0.5)
        assertEquals(CameraSide.RIGHT, estimateFor(90.0)!!.side)
        assertEquals(-90.0, estimateFor(-90.0)!!.yawDeg, 0.5)
        assertEquals(CameraSide.LEFT, estimateFor(-90.0)!!.side)
        assertEquals(0.0, estimateFor(0.0)!!.yawDeg, 0.5)
        assertEquals(CameraSide.FRONT, estimateFor(0.0)!!.side)
        assertEquals(180.0, kotlin.math.abs(estimateFor(180.0)!!.yawDeg), 0.5)
        assertEquals(CameraSide.BACK, estimateFor(180.0)!!.side)
    }

    @Test fun mountTurnedTowardTheFrontAndTiltedDown() {
        val e = estimateFor(80.0, pitch = 15.0)!!
        assertEquals(80.0, e.yawDeg, 0.5)
        assertEquals(10.0, e.offsetTowardFrontDeg, 0.5)
    }

    @Test fun motorbikeVibrationStillGivesTheSideAndAFewDegrees() {
        val e = MountEstimator().also { ride(it, mount(90.0), stopAndGo + stopAndGo, noise = 1.5, rnd = Random(7)) }.estimate()
        assertNotNull(e)
        assertEquals(CameraSide.RIGHT, e!!.side)
        assertEquals(90.0, e.yawDeg, 8.0)
    }

    @Test fun brakingOnlyGivesTheSameAnswer() {
        val e = MountEstimator().also { ride(it, mount(90.0), List(8) { -1.0 }, startSpeed = 12.0) }.estimate()
        assertEquals(90.0, e!!.yawDeg, 0.5)
    }

    @Test fun cornersAreIgnored() {
        // a right turn's centripetal acceleration points right (toward the camera) and the GPS speed also changes:
        // without the turn filter these seconds would look like "forward = camera direction"
        val est = MountEstimator()
        ride(est, mount(90.0), List(10) { 0.8 }, turn = List(10) { -0.4 }, lateral = List(10) { -3.0 })
        assertNull(est.estimate())
    }

    @Test fun handheldPhoneThatKeepsTurningGivesNoAnswer() {
        val est = MountEstimator()
        ride(est, mount(90.0), stopAndGo + stopAndGo, axesAt = { i -> mount(if (i % 2 == 0) 90.0 else -60.0) })
        assertNull(est.estimate())
    }

    @Test fun withoutDopplerSpeedNothingIsMeasured() {   // the emulator reports speed 0 / none
        val est = MountEstimator()
        ride(est, mount(90.0), stopAndGo, doppler = false)
        assertNull(est.estimate())
    }

    @Test fun measuredYawGivesTheSameBearingAsTheAssumedOne() {
        for (offset in listOf(0.0, 10.0, -15.0)) {
            for (travel in listOf(0.0, 90.0, 271.0)) {
                assertEquals(cameraBearing(travel, Side.RIGHT, offset)!!, Geo.norm360(travel + (90 - offset)), 1e-9)
                assertEquals(cameraBearing(travel, Side.LEFT, offset)!!, Geo.norm360(travel + (-90 + offset)), 1e-9)
            }
        }
    }
}
