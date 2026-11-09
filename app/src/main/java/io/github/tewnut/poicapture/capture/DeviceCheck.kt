package io.github.tewnut.poicapture.capture

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.os.Build
import androidx.annotation.StringRes
import io.github.tewnut.poicapture.R
import org.json.JSONObject

/**
 * What a phone must have to record, and what it has beyond that.
 * - [missing]: without these the photos cannot be placed (GPS, gyroscope + accelerometer for the camera direction,
 *   a rear camera good enough to read signs). The app does not open past a "this phone can't record" screen.
 * - [capabilities]: recorded with every session so the agent knows how much to trust the phone (raw GNSS, compass,
 *   camera, focus distance quality…). Nothing here blocks: a better phone gives better positions.
 */
object DeviceCheck {
    enum class Need(@StringRes val title: Int, @StringRes val why: Int) {
        GPS(R.string.device_need_gps, R.string.device_need_gps_why),
        GYROSCOPE(R.string.device_need_gyro, R.string.device_need_gyro_why),
        ACCELEROMETER(R.string.device_need_accel, R.string.device_need_accel_why),
        CAMERA(R.string.device_need_camera, R.string.device_need_camera_why),
    }

    /** Smallest photo the capture accepts: 1080p (16:9), enough to read a signboard from the street. */
    private const val MIN_PIXELS = 1920L * 1080L

    fun missing(ctx: Context): List<Need> {
        val pm = ctx.packageManager
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        return buildList {
            if (!pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)) add(Need.GPS)
            if (sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) == null) add(Need.GYROSCOPE)
            if (sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) == null) add(Need.ACCELEROMETER)
            if ((backCamera(ctx)?.second ?: 0L) < MIN_PIXELS) add(Need.CAMERA)
        }
    }

    /** The back camera's id and its largest JPEG size in pixels, or null when the phone has no back camera. */
    private fun backCamera(ctx: Context): Pair<String, Long>? = runCatching {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cm.cameraIdList.firstNotNullOfOrNull { id ->
            val c = cm.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) return@firstNotNullOfOrNull null
            val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.JPEG).orEmpty()
            id to (sizes.maxOfOrNull { it.width.toLong() * it.height } ?: 0L)
        }
    }.getOrNull()

    fun capabilities(ctx: Context): JSONObject {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        fun has(type: Int) = sm.getDefaultSensor(type) != null
        val o = JSONObject()
            .put("gyroscope", has(Sensor.TYPE_GYROSCOPE))
            .put("accelerometer", has(Sensor.TYPE_ACCELEROMETER))
            .put("magnetometer", has(Sensor.TYPE_MAGNETIC_FIELD))      // the compass of Place capture on foot
            .put("rotationVector", has(Sensor.TYPE_ROTATION_VECTOR))
            .put("barometer", has(Sensor.TYPE_PRESSURE))
            .put("gnssHardware", lm.gnssHardwareModelName ?: JSONObject.NULL)
            .put("gnssYear", lm.gnssYearOfHardware)
            // raw GNSS (pseudoranges, carrier phase) lets the agent improve the track afterwards; dual frequency (L5)
            // is only known from real signals: see session.gnss.dualFrequency
            .put("rawGnss", if (Build.VERSION.SDK_INT >= 31) lm.gnssCapabilities.hasMeasurements() else JSONObject.NULL)
        backCamera(ctx)?.let { (id, px) ->
            o.put("cameraMaxPhotoMp", Math.round(px / 100_000.0) / 10.0)
            runCatching {
                val c = (ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager).getCameraCharacteristics(id)
                // UNCALIBRATED (most phones): the lens position says nothing about the distance to the shop fronts
                o.put("focusDistanceCalibration", when (c.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)) {
                    CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED -> "calibrated"
                    CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE -> "approximate"
                    else -> "uncalibrated"
                })
                c.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE)?.takeIf { it > 0 }
                    ?.let { o.put("hyperfocalM", Math.round(10.0 / it) / 10.0) }   // beyond this everything is sharp
            }
        }
        return o
    }
}
