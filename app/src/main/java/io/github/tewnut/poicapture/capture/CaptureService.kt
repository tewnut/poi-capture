@file:OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)

package io.github.tewnut.poicapture.capture

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import io.github.tewnut.poicapture.AppLanguage
import io.github.tewnut.poicapture.MainActivity
import io.github.tewnut.poicapture.R
import io.github.tewnut.poicapture.core.CaptureWarning
import io.github.tewnut.poicapture.ui.UiText
import io.github.tewnut.poicapture.ui.roadText
import io.github.tewnut.poicapture.ui.wayLabel
import io.github.tewnut.poicapture.ui.toUiText
import io.github.tewnut.poicapture.core.AutoShutter
import io.github.tewnut.poicapture.core.Coverage
import io.github.tewnut.poicapture.core.CameraSide
import io.github.tewnut.poicapture.core.CapturePlan
import io.github.tewnut.poicapture.core.MountEstimate
import io.github.tewnut.poicapture.core.MountEstimator
import io.github.tewnut.poicapture.core.LatLon
import io.github.tewnut.poicapture.core.PhotoRoles
import io.github.tewnut.poicapture.core.SignReading
import io.github.tewnut.poicapture.core.SignText
import io.github.tewnut.poicapture.core.PoiStatus
import io.github.tewnut.poicapture.core.Pois
import io.github.tewnut.poicapture.core.SessionKind
import io.github.tewnut.poicapture.core.Remeasure
import io.github.tewnut.poicapture.core.Side
import io.github.tewnut.poicapture.core.Fix
import io.github.tewnut.poicapture.core.BlurCheck
import io.github.tewnut.poicapture.core.toJson
import io.github.tewnut.poicapture.core.TrackStats
import io.github.tewnut.poicapture.core.FixProcessor
import io.github.tewnut.poicapture.core.Mode
import io.github.tewnut.poicapture.core.Overpass
import io.github.tewnut.poicapture.core.Road
import io.github.tewnut.poicapture.core.RoadCells
import io.github.tewnut.poicapture.core.Way
import io.github.tewnut.poicapture.net.OverpassClient
import io.github.tewnut.poicapture.net.RoadSource
import io.github.tewnut.poicapture.core.RoadData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.tewnut.poicapture.core.Session
import io.github.tewnut.poicapture.core.SessionStore
import io.github.tewnut.poicapture.core.iso
import io.github.tewnut.poicapture.core.sideOfWay
import io.github.tewnut.poicapture.core.uid
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.atan
import kotlin.math.roundToInt

/** A photo, mark or video segment needs a GPS fix at most this old. */
const val GPS_MAX_AGE_MS = io.github.tewnut.poicapture.core.CaptureRules.GPS_MAX_AGE_MS

/** What the capture screen shows. */
data class CaptureUi(
    val camera: String = "off",          // off | on | lost (interrupted, CameraX retries) | error (could not open)
    val gps: String = "waiting",         // waiting | good | weak | error
    val gpsFresh: Boolean = false,       // a GPS fix from the last GPS_MAX_AGE_MS: without one nothing is captured
    val exposureReady: Boolean = false,  // street sweep: the camera's light level is known (dark or not)
    val tooDark: Boolean = false,        // street sweep: even the highest ISO cannot give a 1/500 s shutter
    val manualShutter: Boolean = false,  // street sweep: shutter held at 1/500 s, ISO raised instead
    val accuracyM: Int? = null,
    val speedKmh: Int? = null,
    val roadDistanceM: Int? = null,
    val photos: Int = 0,
    val videos: Int = 0,
    val trackPoints: Int = 0,
    val running: Boolean = false,
    val recording: Boolean = false,
    val warnings: List<UiText> = emptyList(),
    val error: UiText? = null,           // null = no error
    val lastPhoto: String? = null,
    val carrierPhase: Boolean? = null,   // null until raw GNSS arrives
    val hot: Boolean = false,
    val roadName: String? = null,        // road the rider is on (≤ 30 m), null = not recognised
    val roadData: String = "ok",         // auto mode: loading | ok | offline (no road data yet: capture still works)
    val simulated: Boolean = false,      // emulator / mock location: the position is not real GPS
    val maxSpeedKmh: Int = 20,
    val photoEveryM: Double = 0.0,
    val portrait: Boolean = false,       // how the phone is held now: the frame (and its field of view) follows
    val mount: String = "measuring",     // measuring | right | left | front | back
    val mountYawDeg: Double? = null,
    val mountOffsetFrontDeg: Int? = null,
    // POI mode ("Chụp địa điểm")
    val poiId: String? = null,           // place being photographed, null = next shot starts a new one
    val poiNumber: Int = 0,              // places started in this session
    val poiRoles: Map<String, Int> = emptyMap(),   // photos of the current place per role
    val poiRead: SignReading? = null,  // what the phone read on its photos (hint)
    val reading: Int = 0,                // photos waiting for / in on-device text reading
    val readNote: UiText? = null,        // e.g. a sign photo with no readable text: shoot closer
    // sweep mode: "Đánh dấu" taps
    val marks: Int = 0,
)

/**
 * The capture engine as a foreground service (camera + location), so a session keeps recording when the screen
 * turns off or another app comes on top. It must be started while the capture screen is visible (Android only lets
 * a visible app start camera foreground services).
 */
class CaptureService : LifecycleService() {

    inner class LocalBinder : Binder() { val service: CaptureService get() = this@CaptureService }

    companion object {
        private const val EXTRA_SESSION = "sessionId"
        private const val CHANNEL = "capture"
        private const val NOTIF_ID = 1

        /** Id of the session being captured, null when idle (the activity reopens the capture screen from it). */
        val activeSession = MutableStateFlow<String?>(null)

        fun start(ctx: Context, sessionId: String) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, CaptureService::class.java).putExtra(EXTRA_SESSION, sessionId))

        @Volatile private var storeInstance: SessionStore? = null

        /** One store per process: the capture service and the screens append to the same files through one lock. */
        fun store(ctx: Context): SessionStore = storeInstance ?: synchronized(this) {
            storeInstance ?: SessionStore(File(ctx.applicationContext.filesDir, "sessions")).also { storeInstance = it }
        }
    }

    private val binder = LocalBinder()
    private val _state = MutableStateFlow(CaptureUi())
    val state: StateFlow<CaptureUi> = _state

    lateinit var session: Session; private set
    private lateinit var store: SessionStore
    private lateinit var fp: FixProcessor
    private lateinit var shutter: AutoShutter
    private val io = Executors.newSingleThreadExecutor()
    private val ocr = Executors.newSingleThreadExecutor()   // on-device text reading of POI photos (~1 s each)
    private val poiReadings = HashMap<String, MutableList<SignReading>>()

    private var lm: LocationManager? = null
    private var gnss: GnssRecorder? = null
    private var imu: ImuRecorder? = null
    private var wake: PowerManager.WakeLock? = null
    private var pm: PowerManager? = null

    private var camera: Camera? = null
    private var preview: Preview? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null
    private var imageCapture: ImageCapture? = null
    private var guard: ShutterGuard? = null                 // street sweep: shutter 1/500 s or faster
    @Volatile private var lastBlurryMs = 0L                 // a recent photo came out blurry (live warning)
    private val sharpnessRecent = ArrayDeque<Double>()      // the session's recent photos, to judge a soft one
    private val blurRecent = ArrayDeque<Boolean>()
    private var facadeNow = CapturePlan.UNKNOWN_ROAD_FACADE_M   // from the type of the road being ridden
    private var roadTypeNow: String? = null
    private var orientation: OrientationEventListener? = null
    @Volatile private var targetRotation = Surface.ROTATION_90   // landscape until the sensor says otherwise
    @Volatile private var shooting = false
    @Volatile private var lastMissedShotMs = 0L   // an auto shot was due while the previous one was still being saved

    // camera direction (measured from acceleration / braking) and the capture plan derived from the facade distance
    private val mountEstimator = MountEstimator()
    private var appliedMount: MountEstimate? = null

    // auto road mode: road data loaded cell by cell around the ride, in the background; capture never waits for it.
    // Ways are kept per cell and the matcher only sees the cells around the rider, so a long ride stays fast; they
    // are appended to ways.jsonl (not session.json) off the main thread.
    private val cellWays = HashMap<RoadCells.Cell, List<Way>>()
    private val loadedCells = mutableSetOf<RoadCells.Cell>()
    private var matcherCell: RoadCells.Cell? = null
    private var roadFetching = false
    private var roadRetryAt = 0L
    private var roadBackoffMs = 30_000L

    // ---------- lifecycle ----------

    // Android 11-12: the language picked in Settings, for the notification (13+: the system applies it)
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val id = intent?.getStringExtra(EXTRA_SESSION)
        if (activeSession.value != null) return START_NOT_STICKY        // already capturing
        store = store(this)
        val s = id?.let { store.load(it) }
        if (s == null) { stopSelf(); return START_NOT_STICKY }
        session = s
        goForeground()
        activeSession.value = s.id
        val st = s.settings
        facadeNow = CapturePlan.UNKNOWN_ROAD_FACADE_M          // until the road is recognised; then from its type
        val plan = CapturePlan.of(facadeNow)                    // refined with the real field of view when the camera opens
        fp = FixProcessor(s.road.ways, s.sideOfTravel, s.travel, plan.maxSpeedKmh, st.maxAccuracyM, st.offRoadWarnM,
            st.mountOffsetDeg, fixedRoad = !s.road.auto, facadeDistanceM = facadeNow)
        applyPlan(plan)
        if (s.kind == SessionKind.POI) {
            fp.facadeDistanceM = null      // a place is pinned on its building: no facade estimate from the street
            val pois = store.pois(s.id)
            _state.update { it.copy(poiNumber = pois.size) }
        } else {
            _state.update { it.copy(marks = store.marks(s.id).size) }
        }
        _state.update { it.copy(roadData = if (s.road.auto && s.road.ways.isEmpty()) "loading" else "ok") }
        shutter = AutoShutter(plan.photoEveryM, st.maxAccuracyM)
        pm = getSystemService(POWER_SERVICE) as PowerManager
        wake = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "poicapture:capture")?.apply { acquire(12 * 3600_000L) }
        startSensors()
        watchOrientation()
        startCamera()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (activeSession.value != null) finishSession()
        io.shutdown()
        ocr.shutdown()
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel_recording), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_recording, roadText(this, session.road)))
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        ServiceCompat.startForeground(this, NOTIF_ID, n,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    // ---------- GPS, raw GNSS, IMU ----------

    private val locationListener = LocationListener { onLocation(it) }

    // ---------- sharp photos ----------

    private fun onShutter(g: ShutterGuard.State) {
        _state.update { it.copy(exposureReady = g.ready, tooDark = g.tooDark, manualShutter = g.manual) }
        if (g.tooDark) session.camera?.put("tooDarkSeen", true)
    }

    /** Recent photos blurry, or too dark for a fast shutter: say it while riding (the photos are still taken). */
    private fun lightWarnings(): List<UiText> = buildList {
        if (_state.value.tooDark && _state.value.running) add(UiText(R.string.service_too_dark))   // before: the card says it
        if (System.currentTimeMillis() - lastBlurryMs < 10_000) add(UiText(
            if (session.kind == SessionKind.POI) R.string.service_blurry_poi else R.string.service_blurry))
    }

    /**
     * How sharp a photo is (see BlurCheck): motion smear from speed × exposure, and edge detail measured on a small
     * copy of the image against the session's recent photos. 2 of the last 3 blurry → live warning.
     */
    private fun blurOf(meta: JSONObject, file: java.io.File): JSONObject {
        val w = meta.optInt("width", 0).takeIf { it > 0 } ?: 2560
        val exposure = meta.optDouble("exposureS").takeIf { !it.isNaN() }
        val speed = meta.optDouble("speedMps").takeIf { !it.isNaN() }
        val hFov = meta.optDouble("hFovDeg").takeIf { !it.isNaN() } ?: CapturePlan.DEFAULT_HFOV
        val motion = if (exposure != null && speed != null && session.kind != SessionKind.POI)
            BlurCheck.motionPx(speed, exposure, hFov, w, facadeNow) else null
        val sharp = runCatching {
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = maxOf(1, w / 480) }
            val bmp = android.graphics.BitmapFactory.decodeFile(file.path, opts) ?: return@runCatching null
            val bw = bmp.width; val bh = bmp.height
            val px = IntArray(bw * bh).also { bmp.getPixels(it, 0, bw, 0, 0, bw, bh) }
            bmp.recycle()
            val gray = IntArray(px.size) { i -> val c = px[i]; ((c shr 16 and 0xFF) * 3 + (c shr 8 and 0xFF) * 6 + (c and 0xFF)) / 10 }
            BlurCheck.sharpness(gray, bw, bh)
        }.getOrNull()
        val blurry = synchronized(sharpnessRecent) {
            val b = BlurCheck.blurry(motion, w, sharp, sharpnessRecent.toList())
            if (sharp != null) { sharpnessRecent.addLast(sharp); while (sharpnessRecent.size > 15) sharpnessRecent.removeFirst() }
            blurRecent.addLast(b); while (blurRecent.size > 3) blurRecent.removeFirst()
            if (blurRecent.count { it } >= 2 || (session.kind == SessionKind.POI && b)) lastBlurryMs = System.currentTimeMillis()
            b
        }
        return JSONObject().put("motionPx", motion?.let { Math.round(it * 10) / 10.0 } ?: JSONObject.NULL)
            .put("maxMotionPx", Math.round(BlurCheck.maxMotionPx(w) * 10) / 10.0)
            .put("sharpness", sharp?.let { Math.round(it) } ?: JSONObject.NULL)
            .put("manualShutter", _state.value.manualShutter)
            .put("blurry", blurry)
    }

    // ---------- portrait / landscape ----------

    private val portrait get() = targetRotation == Surface.ROTATION_0 || targetRotation == Surface.ROTATION_180

    /** Photo spacing and speed for the frame as held now: portrait sees a narrower strip of the street. */
    private fun currentPlan(): CapturePlan {
        val cam = session.camera
        val fov = cam?.optDouble(if (portrait) "hFovPortraitDeg" else "hFovDeg")?.takeIf { !it.isNaN() }
            ?: if (portrait) CapturePlan.DEFAULT_HFOV_PORTRAIT else null
        val long = session.settings.photoLongEdge
        return CapturePlan.of(facadeNow, fov, imageWidthPx = if (portrait) long * 9 / 16 else long)
    }

    private fun orientationMeta(o: JSONObject): JSONObject = o.put("orientation", if (portrait) "portrait" else "landscape")
        .put("facadeRoadType", roadTypeNow ?: JSONObject.NULL)   // the road type the facade distance came from
        .put("targetRotationDeg", when (targetRotation) { Surface.ROTATION_0 -> 0; Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; else -> 270 })
        .put("hFovDeg", currentPlan().hFovDeg)

    /**
     * Which way the phone is turned, from the accelerometer (works with the screen off, unlike the display rotation):
     * the camera writes upright photos and video for it, and the plan follows. 15° of hysteresis at the boundaries
     * so a mounted phone that vibrates near 45° does not flip back and forth.
     */
    private fun watchOrientation() {
        // start from the screen (the capture screen rotates with the phone); flat on a table the sensor cannot tell
        val d = (getSystemService(DISPLAY_SERVICE) as android.hardware.display.DisplayManager).getDisplay(android.view.Display.DEFAULT_DISPLAY)
        d?.rotation?.let { targetRotation = it; _state.update { s -> s.copy(portrait = portrait) } }
        orientation = object : OrientationEventListener(this) {
            override fun onOrientationChanged(deg: Int) {
                if (deg == ORIENTATION_UNKNOWN) return
                val near = listOf(45, 135, 225, 315).any { b -> Math.abs(((deg - b + 540) % 360) - 180) < 15 }
                if (near) return
                val r = when (deg) { in 45..134 -> Surface.ROTATION_270; in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90; else -> Surface.ROTATION_0 }
                if (r == targetRotation) return
                val wasPortrait = portrait
                targetRotation = r
                imageCapture?.targetRotation = r
                if (portrait != wasPortrait) {
                    applyPlan(currentPlan())
                    _state.update { it.copy(portrait = portrait) }
                }
            }
        }.also { if (it.canDetectOrientation()) it.enable() }
    }

    /** The last GPS fix when it is at most [GPS_MAX_AGE_MS] old, else null: every photo, mark and video needs one. */
    private fun freshFix(): Fix? = fp.fix?.takeIf { f ->
        val e = f.elapsedNanos ?: return@takeIf false
        (SystemClock.elapsedRealtimeNanos() - e) / 1_000_000 <= GPS_MAX_AGE_MS
    }

    /**
     * Twice a second: is the GPS still fresh? When it goes stale the capture screen asks the contributor to fix it and
     * a running video segment is closed; when it is back, video starts again by itself.
     */
    private fun watchGps() = lifecycleScope.launch {
        while (true) {
            val fresh = freshFix() != null
            if (fresh != _state.value.gpsFresh) _state.update { it.copy(gpsFresh = fresh) }
            delay(500)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startSensors() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        this.lm = lm
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            _state.update { it.copy(gps = "error", error = UiText(R.string.service_gps_permission)) }
        } else {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper())
            GpsKeeper.acquire(this)
            // the setup screen had a fix a moment ago: start from it when it is still fresh (no "waiting for GPS")
            runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
                ?.takeIf { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000 <= GPS_MAX_AGE_MS }
                ?.let { onLocation(it) }
            gnss = GnssRecorder(lm, store.gnssFile(session.id)).also { it.start(io) }
            watchGps()
        }
        imu = ImuRecorder(getSystemService(SENSOR_SERVICE) as SensorManager, store.imuFile(session.id), mountEstimator).also { it.start() }
    }

    private fun onLocation(l: Location) {
        val f = Fix(
            t = l.time, lat = l.latitude, lon = l.longitude, accuracyM = l.accuracy.toDouble(),
            altitudeM = if (l.hasAltitude()) l.altitude else null,
            speedMps = if (l.hasSpeed()) l.speed.toDouble() else null,
            bearingDeg = if (l.hasBearing()) l.bearing.toDouble() else null,
            elapsedNanos = l.elapsedRealtimeNanos,
        )
        // Doppler speed only (position-derived speeds are too noisy for acceleration)
        mountEstimator.onFix(l.elapsedRealtimeNanos, if (l.hasSpeed()) l.speed.toDouble() else null, if (l.hasBearing()) l.bearing.toDouble() else null)
        if (session.kind != SessionKind.POI) checkMount()   // on foot / between shops: not a mounted camera
        // another type of road (alley → main road…): the expected distance to the fronts, so the photo spacing, follows
        if (session.kind != SessionKind.POI) {
            val hw = fp.roadWay()?.highway
            val d = CapturePlan.facadeForRoad(hw)
            if (d != facadeNow || hw != roadTypeNow) {
                roadTypeNow = hw
                if (d != facadeNow) { facadeNow = d; fp.facadeDistanceM = d; applyPlan(currentPlan()) }
            }
        }
        if (fp.onFix(f)) {
            session.travel = fp.travel
            session.sideOfWay = fp.travel?.let { sideOfWay(session.sideOfTravel, it) }
            store.save(session)
        }
        val line = f.toTrackJson(fp.travelBearing).toString()
        io.execute { runCatching { store.trackFile(session.id).appendText(line + "\n") } }

        val hot = pm?.let { (it.getThermalHeadroom(10).takeIf { h -> !h.isNaN() } ?: 0f) >= 1f || it.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE } ?: false
        val g = gnss
        _state.update {
            it.copy(
                gps = if (fp.gpsGood) "good" else "weak",
                gpsFresh = true,
                accuracyM = f.accuracyM.roundToInt(),
                speedKmh = fp.speedKmh?.roundToInt(),
                roadDistanceM = fp.match?.distanceM?.roundToInt(),
                trackPoints = it.trackPoints + 1,
                warnings = if (session.kind == SessionKind.POI) fp.warnings().filter { w -> w is CaptureWarning.WeakGps }.map { w -> w.toUiText() } +
                    lightWarnings() + (if (hot) listOf(UiText(R.string.service_hot)) else emptyList())
                else lightWarnings() + mountWarning() + fp.warnings().map { w -> w.toUiText() } +
                    (if (System.currentTimeMillis() - lastMissedShotMs < 10_000) listOf(UiText(R.string.service_missed_shot)) else emptyList()) +
                    (if (hot) listOf(UiText(R.string.service_hot)) else emptyList()),
                error = if (it.error?.res == R.string.service_gps_permission) null else it.error,
                carrierPhase = g?.takeIf { r -> r.epochs.get() > 0 }?.let { r -> r.adrValid.get() > 0 },
                hot = hot,
            )
        }
        _state.update { it.copy(roadName = fp.roadWay()?.let { w -> wayLabel(this, w) }, simulated = it.simulated || isSimulated(l)) }
        if (session.road.auto) {
            updateMatcher(f.ll, force = false)
            loadRoadsAround(f.ll)
        }
        if (_state.value.running && session.mode == Mode.PHOTO_AUTO && shutter.due(f)) takePhoto("auto")
    }

    private fun applyPlan(plan: CapturePlan) {
        if (::shutter.isInitialized) shutter.everyM = plan.photoEveryM
        fp.maxSpeedKmh = plan.maxSpeedKmh
        session.plan = plan.toJson()
        _state.update { it.copy(maxSpeedKmh = plan.maxSpeedKmh, photoEveryM = plan.photoEveryM) }
    }

    private fun mountWarning(): List<UiText> = when (_state.value.mount) {
        "front" -> listOf(UiText(R.string.service_mount_front))
        "back" -> listOf(UiText(R.string.service_mount_back))
        else -> emptyList()
    }

    /**
     * Camera direction: apply the estimate when it first becomes confident or moves by more than 5°. Left/right:
     * measured yaw for new items, earlier items corrected in the background. Front/back: warn, keep the assumption.
     */
    private fun checkMount() {
        val est = mountEstimator.estimate() ?: return
        val prev = appliedMount
        if (prev != null && prev.side == est.side && kotlin.math.abs(prev.yawDeg - est.yawDeg) < 5) return
        appliedMount = est
        session.mount = est.toJson()
        _state.update {
            it.copy(mount = est.side.json, mountYawDeg = est.yawDeg,
                mountOffsetFrontDeg = if (est.side == CameraSide.RIGHT || est.side == CameraSide.LEFT) est.offsetTowardFrontDeg.roundToInt() else null)
        }
        val side = when (est.side) { CameraSide.RIGHT -> Side.RIGHT; CameraSide.LEFT -> Side.LEFT; else -> null }
        if (side != null) {
            fp.side = side
            fp.cameraYawDeg = est.yawDeg
            session.sideOfTravel = side
            val sid = session.id
            val d = facadeNow
            io.execute {
                runCatching {
                    // each photo keeps the distance it was taken with (it follows the road type)
                    for (item in store.items(sid)) Remeasure.patch(item, est, item.optDouble("facadeDistanceM").takeIf { !it.isNaN() } ?: d)
                        ?.let { store.patchItem(sid, item.getString("id"), it) }
                }
            }
        }
        store.save(session)
    }

    private fun isSimulated(l: Location): Boolean =
        (if (android.os.Build.VERSION.SDK_INT >= 31) l.isMock else @Suppress("DEPRECATION") l.isFromMockProvider) ||
            android.os.Build.HARDWARE in setOf("ranchu", "goldfish")

    /** Auto mode: give the matcher the ways of the 3×3 cells around p (only when the rider's cell changes or data arrives). */
    private fun updateMatcher(p: LatLon, force: Boolean) {
        val c = RoadCells.cellOf(p)
        if (!force && c == matcherCell) return
        matcherCell = c
        val near = (-1..1).flatMap { dx -> (-1..1).mapNotNull { dy -> cellWays[RoadCells.Cell(c.x + dx, c.y + dy)] } }.flatten()
        fp.setWays(near.distinctBy { it.key })
        _state.update { it.copy(roadName = fp.roadWay()?.let { w -> wayLabel(this, w) }) }
    }

    /** Auto mode: load the road data of the grid cells around p that are not loaded yet (one at a time, with backoff). */
    private fun loadRoadsAround(p: LatLon) {
        if (roadFetching || System.currentTimeMillis() < roadRetryAt) return
        val cell = RoadCells.around(p).firstOrNull { it !in loadedCells } ?: return
        roadFetching = true
        if (cellWays.isEmpty()) _state.update { it.copy(roadData = "loading") }
        lifecycleScope.launch {
            try {
                // offline pack, then disk cache, then Overpass (its answer is cached for next time)
                val hit = withContext(Dispatchers.IO) { RoadSource.local(this@CaptureService, cell) }
                    ?: OverpassClient.ways(session.settings.overpassUrl, Overpass.cellQuery(cell)).let { w ->
                        withContext(Dispatchers.IO) { RoadSource.cache(this@CaptureService).put(cell, w) }
                        RoadData.Hit(w, "overpass")
                    }
                val ways = hit.ways
                if (hit.source !in session.road.sources) {
                    session.road = session.road.copy(sources = session.road.sources + hit.source)
                    store.save(session)
                }
                cellWays[cell] = ways
                loadedCells += cell
                roadBackoffMs = 30_000L
                val sid = session.id
                io.execute { runCatching { store.appendWays(sid, ways) } }
                fp.fix?.let { updateMatcher(it.ll, force = true) }
                _state.update { it.copy(roadData = "ok") }
            } catch (e: Exception) {
                roadRetryAt = System.currentTimeMillis() + roadBackoffMs
                roadBackoffMs = minOf(roadBackoffMs * 2, 5 * 60_000L)
                if (cellWays.isEmpty()) _state.update { it.copy(roadData = "offline") }
            } finally { roadFetching = false }
        }
    }

    // ---------- camera ----------

    private fun startCamera() {
        val fut = ProcessCameraProvider.getInstance(this)
        fut.addListener({
            try {
                val provider = fut.get()
                val st = session.settings
                val pb = Preview.Builder()
                val g = if (session.kind == SessionKind.SWEEP) ShutterGuard { gs -> onShutter(gs) } else null
                guard = g
                g?.watch(pb, io)
                val pv = pb.build()
                preview = pv
                val useCases = mutableListOf<UseCase>(pv)
                g?.let { useCases += it.analysis(io) }
                run {
                    val long = st.photoLongEdge
                    val selector = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy(Size(long, long * 9 / 16), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .build()
                    imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setJpegQuality(st.jpegQuality)
                        .setResolutionSelector(selector)
                        .setTargetRotation(targetRotation)   // follows the phone (see watchOrientation)
                        .build()
                        .also { useCases += it }
                }
                provider.unbindAll()
                // some cameras cannot run preview + photos + the brightness stream together: then no shutter guard
                // (auto-exposure as before; each photo's blur check still applies)
                val cam = runCatching { provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray()) }
                    .getOrElse { e ->
                        if (g == null) throw e
                        guard = null
                        _state.update { it.copy(exposureReady = true) }
                        provider.unbindAll()
                        provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                            *useCases.filter { it !is androidx.camera.core.ImageAnalysis }.toTypedArray())
                    }
                camera = cam
                guard?.attach(cam)
                surfaceProvider?.let { pv.setSurfaceProvider(it) }
                session.camera = cameraInfo(cam).put("shutterGuard", guard != null)
                // the real field of view sets how much facade one photo covers: refine spacing / speed limit
                applyPlan(currentPlan())
                store.save(session)
                // CameraX reopens the camera by itself after a phone call / another app: follow its state
                cam.cameraInfo.cameraState.observe(this) { cs -> onCameraState(cs) }
            } catch (e: Exception) {
                _state.update { it.copy(camera = "error", error = UiText(R.string.service_camera_failed, e.message ?: e.javaClass.simpleName)) }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onCameraState(cs: CameraState) {
        val open = cs.type == CameraState.Type.OPEN
        _state.update {
            it.copy(camera = if (open) "on" else if (it.camera == "off" || it.camera == "error") it.camera else "lost",
                error = if (cs.error != null && !open) UiText(R.string.service_camera_interrupted, cs.error?.code ?: 0)
                    else if (open && it.error?.res == R.string.service_camera_interrupted) null else it.error)
        }
    }

    /** Show (or, with null, stop showing) the preview. Capture keeps running without a preview surface. */
    fun attachPreview(sp: Preview.SurfaceProvider?) {
        surfaceProvider = sp
        preview?.setSurfaceProvider(sp)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun cameraInfo(cam: Camera): JSONObject {
        val c2 = Camera2CameraInfo.from(cam.cameraInfo)
        fun <T> ch(k: CameraCharacteristics.Key<T>): T? = runCatching { c2.getCameraCharacteristic(k) }.getOrNull()
        val focal = ch(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val sensor = ch(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val o = JSONObject()
            .put("cameraId", c2.cameraId)
            .put("hardwareLevel", ch(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: JSONObject.NULL)
            .put("timestampSource", when (ch(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)) {
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "realtime"
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> "unknown"
                else -> JSONObject.NULL
            })
            .put("focalLengthMm", focal ?: JSONObject.NULL)
            .put("sensorWidthMm", sensor?.width ?: JSONObject.NULL)
            .put("sensorHeightMm", sensor?.height ?: JSONObject.NULL)
            .put("pixelArray", ch(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let { "${it.width}x${it.height}" } ?: JSONObject.NULL)
            .put("intrinsicCalibration", ch(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
            .put("lensDistortion", ch(CameraCharacteristics.LENS_DISTORTION)?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
        // horizontal FOV of the landscape frame: the sensor's long side is horizontal; a 16:9 crop of a 4:3 sensor
        // cuts top/bottom, not the sides. Held in portrait, the frame's horizontal is the 16:9 short side.
        // Zoom stays at 1x and stabilization is off. Each photo / video says which one it used.
        if (focal != null && sensor != null && focal > 0) {
            o.put("hFovDeg", Math.toDegrees(2 * atan(sensor.width / (2.0 * focal))).let { (it * 10).roundToInt() / 10.0 })
            o.put("hFovPortraitDeg", Math.toDegrees(2 * atan(sensor.width * 9.0 / 16 / (2.0 * focal))).let { (it * 10).roundToInt() / 10.0 })
            o.put("vFovDeg4x3", Math.toDegrees(2 * atan(sensor.height / (2.0 * focal))).let { (it * 10).roundToInt() / 10.0 })
        }
        return o
    }

    // ---------- photos ----------

    fun takePhoto(trigger: String, role: String? = null) {
        val ic = imageCapture ?: return
        if (_state.value.camera != "on") return
        // no GPS fix from the last 5 s: no photo (the screen is asking the contributor to fix the GPS)
        if (freshFix() == null) { _state.update { it.copy(gpsFresh = false) }; return }
        val poiMode = session.kind == SessionKind.POI
        val poiId = if (poiMode) (_state.value.poiId ?: newPoi()) else null
        if (shooting) { if (trigger == "auto") { lastMissedShotMs = System.currentTimeMillis(); return } }
        shooting = true
        val now = System.currentTimeMillis()
        val en = SystemClock.elapsedRealtimeNanos()
        val id = uid("p_", now)
        val meta = orientationMeta(fp.itemMeta(id, "photo", now, session.sideOfTravel, en).put("trigger", trigger))
        val motion = imu?.snapshot(en)
        motion?.let { meta.put("motion", it) }
        if (poiId != null) {
            meta.put("poiId", poiId)
            // the kind of photo only when the contributor is asked for it; otherwise the agent tells it from the image
            session.photoRoles?.let { pr -> meta.put("role", role ?: pr.cover.id) }
            // on foot the travel direction means nothing: the camera direction comes from the orientation sensor
            meta.put("cameraBearing", motion?.opt("cameraAzimuthDeg") ?: JSONObject.NULL).put("cameraBearingSource", "compass")
                .put("sideOfWay", JSONObject.NULL).put("travel", JSONObject.NULL)
        }
        fp.fix?.let { shutter.mark(it.ll) }
        val file = store.photoFile(session.id, id)
        ic.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), io, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                runCatching {
                    val ex = ExifInterface(file)
                    meta.put("width", ex.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0))
                        .put("height", ex.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0))
                        .put("exifOrientation", ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
                        .put("exposureS", ex.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, -1.0).takeIf { it > 0 } ?: JSONObject.NULL)
                        .put("iso", ex.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 } ?: JSONObject.NULL)
                        .put("focalLengthMm", ex.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, -1.0).takeIf { it > 0 } ?: JSONObject.NULL)
                }
                meta.put("bytes", file.length())
                runCatching { meta.put("blur", blurOf(meta, file)) }
                store.appendItem(session.id, meta)
                shooting = false
                _state.update {
                    val roles = if (poiId != null && it.poiId == poiId) {
                        val r = meta.optString("role")
                        it.poiRoles + (r to ((it.poiRoles[r] ?: 0) + 1))
                    } else it.poiRoles
                    it.copy(photos = it.photos + 1, lastPhoto = file.absolutePath, poiRoles = roles)
                }
                if (poiId != null) readSign(poiId, id, meta.optString("role"), file)
            }

            override fun onError(e: ImageCaptureException) {
                shooting = false
                _state.update { it.copy(error = UiText(R.string.service_shot_failed, e.message ?: e.javaClass.simpleName)) }
            }
        })
    }

    // ---------- POI mode ----------

    /**
     * Read the text on a place's photo on the phone (hint for the agent, stored as the item's `ocr`), and tell the
     * contributor right away when a sign photo has no readable text, while still in front of the shop.
     */
    private fun readSign(poiId: String, itemId: String, role: String, file: java.io.File) {
        _state.update { it.copy(reading = it.reading + 1) }
        ocr.execute {
            val r = runCatching { SignOcr.read(file) }.getOrNull()
            if (r != null) store.patchItem(session.id, itemId, JSONObject().put("ocr", r.toJson()))
            val merged = synchronized(poiReadings) {
                if (r != null) poiReadings.getOrPut(poiId) { mutableListOf() }.add(r)
                SignText.merge(poiReadings[poiId].orEmpty())
            }
            val textRole = role == PhotoRoles.SIGN || role == PhotoRoles.INFO
            val photosOfPlace = _state.value.poiRoles.values.sum()
            _state.update {
                if (it.poiId != poiId) it.copy(reading = (it.reading - 1).coerceAtLeast(0))
                else it.copy(reading = (it.reading - 1).coerceAtLeast(0), poiRead = merged,
                    readNote = when {
                        r == null -> null
                        textRole && r.empty -> UiText(R.string.service_no_text_photo)
                        // no kinds asked: warn when none of the place's photos has readable text (sign missing or blurred)
                        session.photoRoles == null && merged == null && photosOfPlace >= 2 ->
                            UiText(R.string.service_no_text_place)
                        else -> null
                    })
            }
        }
    }

    /** Start a new place at the current position (refined by a pin on the building when the contributor reviews it). */
    fun newPoi(): String {
        val now = System.currentTimeMillis()
        val id = uid("poi_", now)
        store.appendPoi(session.id, Pois.new(id, now, fp.fix))
        _state.update { it.copy(poiId = id, poiNumber = it.poiNumber + 1, poiRoles = emptyMap(), poiRead = null, readNote = null) }
        return id
    }

    /** Where the current place was started (the default pin), or the current position. */
    fun poiStartPosition(): LatLon? {
        val id = _state.value.poiId
        val p = id?.let { pid -> store.pois(session.id).firstOrNull { it.optString("id") == pid } }?.optJSONObject("position")
        return p?.let { LatLon(it.getDouble("lat"), it.getDouble("lon")) } ?: fp.fix?.ll
    }

    /** The contributor finished a place: pin, optional fields, status. The next shot starts a new place. */
    fun finishPoi(pin: LatLon?, pinMoved: Boolean, fields: Map<String, String>, status: PoiStatus) {
        val id = _state.value.poiId ?: return
        store.patchPoi(session.id, id, Pois.review(pin, !pinMoved, fields, status, System.currentTimeMillis()))
        _state.update { it.copy(poiId = null, poiRoles = emptyMap(), poiRead = null, readNote = null) }
    }

    // ---------- sweep marks ----------

    /** "Đánh dấu": the contributor points out a place while riding; the photos around it go with the mark. */
    fun addMark() {
        val now = System.currentTimeMillis()
        val f = freshFix() ?: run { _state.update { it.copy(gpsFresh = false) }; return }
        store.appendMark(session.id, JSONObject().put("id", uid("m_", now)).put("tMs", now).put("t", iso(now))
            .put("elapsedNanos", SystemClock.elapsedRealtimeNanos())
            .put("lat", f?.lat ?: JSONObject.NULL).put("lon", f?.lon ?: JSONObject.NULL)
            .put("accuracyM", f?.accuracyM ?: JSONObject.NULL))
        _state.update { it.copy(marks = it.marks + 1) }
    }

    // ---------- session control ----------

    fun start() {
        if (_state.value.running) return
        if (freshFix() == null) return   // the Start button waits for GPS
        if (session.startedAt == null) session.startedAt = iso(System.currentTimeMillis())
        store.save(session)
        _state.update { it.copy(running = true) }
        shutter.reset()
    }

    fun pause() {
        _state.update { it.copy(running = false) }
    }

    /** End the session: stop everything, write the summary, leave the foreground. */
    fun finishSession() {
        if (activeSession.value == null) return
        pause()
        lm?.removeUpdates(locationListener)
        GpsKeeper.release()
        orientation?.disable()
        guard?.release()
        gnss?.let { session.gnss = it.summary(); it.stop() }
        imu?.stop()
        val ui = _state.value
        session.endedAt = iso(System.currentTimeMillis())
        // saved with its name: the street it was recorded on and the day
        session.name = runCatching { TrackStats.sessionName(store.items(session.id),
            session.startedAt ?: session.createdAt, getString(R.string.session_no_street)) }.getOrNull()
        session.counts = JSONObject().put("photos", ui.photos).put("videos", ui.videos).put("trackPoints", ui.trackPoints)
        store.save(session)
        val sid = session.id
        io.execute { runCatching { Coverage.forSession(store, sid) } }   // (a full package name here would hit the `io` executor)
        runCatching { wake?.release() }
        activeSession.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
