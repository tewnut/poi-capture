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
import io.github.tewnut.poicapture.MainActivity
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

/** What the capture screen shows. */
data class CaptureUi(
    val camera: String = "off",          // off | on | lost (interrupted, CameraX retries) | error (could not open)
    val gps: String = "waiting",         // waiting | good | weak | error
    val accuracyM: Int? = null,
    val speedKmh: Int? = null,
    val roadDistanceM: Int? = null,
    val photos: Int = 0,
    val videos: Int = 0,
    val trackPoints: Int = 0,
    val running: Boolean = false,
    val recording: Boolean = false,
    val warnings: List<String> = emptyList(),
    val error: String = "",
    val lastPhoto: String? = null,
    val carrierPhase: Boolean? = null,   // null until raw GNSS arrives
    val hot: Boolean = false,
    val roadName: String? = null,        // road the rider is on (≤ 30 m), null = not recognised
    val roadData: String = "ok",         // auto mode: loading | ok | offline (no road data yet: capture still works)
    val simulated: Boolean = false,      // emulator / mock location: the position is not real GPS
    val maxSpeedKmh: Int = 20,
    val photoEveryM: Double = 0.0,
    val mount: String = "measuring",     // measuring | right | left | front | back
    val mountYawDeg: Double? = null,
    val mountOffsetFrontDeg: Int? = null,
    // POI mode ("Chụp địa điểm")
    val poiId: String? = null,           // place being photographed, null = next shot starts a new one
    val poiNumber: Int = 0,              // places started in this session
    val poiRoles: Map<String, Int> = emptyMap(),   // photos of the current place per role
    val poiRead: SignReading? = null,  // what the phone read on its photos (hint)
    val reading: Int = 0,                // photos waiting for / in on-device text reading
    val readNote: String? = null,        // e.g. a sign photo with no readable text: shoot closer
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
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var segmentJob: Job? = null
    private var segmentIndex = 0
    private var restartVideoWhenCameraOpens = false
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
        val plan = CapturePlan.of(st.facadeDistanceM)          // refined with the real field of view when the camera opens
        fp = FixProcessor(s.road.ways, s.sideOfTravel, s.travel, plan.maxSpeedKmh, st.maxAccuracyM, st.offRoadWarnM,
            st.mountOffsetDeg, fixedRoad = !s.road.auto, facadeDistanceM = st.facadeDistanceM)
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
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Đang ghi hình", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("POI Capture")
            .setContentText("Đang ghi: ${session.road.label}")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        ServiceCompat.startForeground(this, NOTIF_ID, n,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    // ---------- GPS, raw GNSS, IMU ----------

    private val locationListener = LocationListener { onLocation(it) }

    @SuppressLint("MissingPermission")
    private fun startSensors() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        this.lm = lm
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            _state.update { it.copy(gps = "error", error = "GPS: chưa cấp quyền vị trí chính xác") }
        } else {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper())
            gnss = GnssRecorder(lm, store.gnssFile(session.id)).also { it.start(io) }
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
                accuracyM = f.accuracyM.roundToInt(),
                speedKmh = fp.speedKmh?.roundToInt(),
                roadDistanceM = fp.match?.distanceM?.roundToInt(),
                trackPoints = it.trackPoints + 1,
                warnings = if (session.kind == SessionKind.POI) fp.warnings().filter { w -> w.startsWith("GPS") } + (if (hot) listOf("Máy đang nóng: che nắng hoặc nghỉ một lúc") else emptyList())
                else mountWarning() + fp.warnings() +
                    (if (System.currentTimeMillis() - lastMissedShotMs < 10_000) listOf("Máy chụp không kịp: chạy chậm lại để không bỏ sót mặt tiền") else emptyList()) + (if (hot) listOf("Máy đang nóng: che nắng hoặc nghỉ một lúc") else emptyList()),
                error = if (it.error.startsWith("GPS")) "" else it.error,
                carrierPhase = g?.takeIf { r -> r.epochs.get() > 0 }?.let { r -> r.adrValid.get() > 0 },
                hot = hot,
            )
        }
        _state.update { it.copy(roadName = fp.roadName(), simulated = it.simulated || isSimulated(l)) }
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

    private fun mountWarning(): List<String> = when (_state.value.mount) {
        "front" -> listOf("Camera đang nhìn về phía TRƯỚC: xoay camera sang dãy nhà cần ghi")
        "back" -> listOf("Camera đang nhìn về phía SAU: xoay camera sang dãy nhà cần ghi")
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
            val d = session.settings.facadeDistanceM
            io.execute {
                runCatching {
                    for (item in store.items(sid)) Remeasure.patch(item, est, d)?.let { store.patchItem(sid, item.getString("id"), it) }
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
        _state.update { it.copy(roadName = fp.roadName()) }
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
                val pv = Preview.Builder().build()
                preview = pv
                val useCases = mutableListOf<UseCase>(pv)
                if (session.mode == Mode.VIDEO) {
                    val recorder = Recorder.Builder()
                        // 1080p preferred; weaker cameras (and the emulator) only offer 720p/480p
                        .setQualitySelector(QualitySelector.fromOrderedList(listOf(Quality.FHD, Quality.HD, Quality.SD),
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                        .build()
                    videoCapture = VideoCapture.Builder(recorder).setTargetRotation(Surface.ROTATION_90).build().also { useCases += it }
                } else {
                    val long = st.photoLongEdge
                    val selector = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy(Size(long, long * 9 / 16), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .build()
                    imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setJpegQuality(st.jpegQuality)
                        .setResolutionSelector(selector)
                        .setTargetRotation(Surface.ROTATION_90)   // the capture screen is locked to landscape
                        .build()
                        .also { useCases += it }
                }
                provider.unbindAll()
                val cam = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray())
                camera = cam
                surfaceProvider?.let { pv.setSurfaceProvider(it) }
                session.camera = cameraInfo(cam)
                // the real field of view sets how much facade one photo covers: refine spacing / speed limit
                session.camera?.optDouble("hFovDeg")?.takeIf { !it.isNaN() }?.let { applyPlan(CapturePlan.of(session.settings.facadeDistanceM, it)) }
                store.save(session)
                // CameraX reopens the camera by itself after a phone call / another app: follow its state
                cam.cameraInfo.cameraState.observe(this) { cs -> onCameraState(cs) }
            } catch (e: Exception) {
                _state.update { it.copy(camera = "error", error = "Không mở được camera: ${e.message}") }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onCameraState(cs: CameraState) {
        val open = cs.type == CameraState.Type.OPEN
        _state.update {
            it.copy(camera = if (open) "on" else if (it.camera == "off" || it.camera == "error") it.camera else "lost",
                error = if (cs.error != null && !open) "Camera bị gián đoạn (mã ${cs.error?.code}), đang chờ mở lại…" else if (open && it.error.startsWith("Camera")) "" else it.error)
        }
        if (open && restartVideoWhenCameraOpens && _state.value.running) {
            restartVideoWhenCameraOpens = false
            startSegment()
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
            .put("targetRotation", 90)
        // horizontal FOV of the landscape frame: the sensor's long side is horizontal; a 16:9 crop of a 4:3 sensor
        // cuts top/bottom, not the sides. Zoom stays at 1x and stabilization is off.
        if (focal != null && sensor != null && focal > 0) {
            o.put("hFovDeg", Math.toDegrees(2 * atan(sensor.width / (2.0 * focal))).let { (it * 10).roundToInt() / 10.0 })
            o.put("vFovDeg4x3", Math.toDegrees(2 * atan(sensor.height / (2.0 * focal))).let { (it * 10).roundToInt() / 10.0 })
        }
        return o
    }

    // ---------- photos ----------

    fun takePhoto(trigger: String, role: String? = null) {
        val ic = imageCapture ?: return
        if (_state.value.camera != "on") return
        val poiMode = session.kind == SessionKind.POI
        val poiId = if (poiMode) (_state.value.poiId ?: newPoi()) else null
        if (shooting) { if (trigger == "auto") { lastMissedShotMs = System.currentTimeMillis(); return } }
        shooting = true
        val now = System.currentTimeMillis()
        val en = SystemClock.elapsedRealtimeNanos()
        val id = uid("p_", now)
        val meta = fp.itemMeta(id, "photo", now, session.sideOfTravel, en).put("trigger", trigger)
        val motion = imu?.snapshot(en)
        motion?.let { meta.put("motion", it) }
        if (poiId != null) {
            val r = role ?: (session.photoRoles ?: PhotoRoles.DEFAULT).cover.id
            meta.put("poiId", poiId).put("role", r)
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
                _state.update { it.copy(error = "Chụp lỗi: ${e.message}") }
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
            _state.update {
                if (it.poiId != poiId) it.copy(reading = (it.reading - 1).coerceAtLeast(0))
                else it.copy(reading = (it.reading - 1).coerceAtLeast(0), poiRead = merged,
                    readNote = when {
                        r == null -> null
                        textRole && r.empty -> "Chưa đọc được chữ trên ảnh vừa chụp. Thử chụp gần hơn, thẳng hơn, không bị loá."
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
        val f = fp.fix
        store.appendMark(session.id, JSONObject().put("id", uid("m_", now)).put("tMs", now).put("t", iso(now))
            .put("elapsedNanos", SystemClock.elapsedRealtimeNanos())
            .put("lat", f?.lat ?: JSONObject.NULL).put("lon", f?.lon ?: JSONObject.NULL)
            .put("accuracyM", f?.accuracyM ?: JSONObject.NULL))
        _state.update { it.copy(marks = it.marks + 1) }
    }

    // ---------- video (segments) ----------

    private fun startSegment() {
        val vc = videoCapture ?: return
        if (_state.value.camera != "on") { restartVideoWhenCameraOpens = true; return }
        val now = System.currentTimeMillis()
        val id = uid("v_", now)
        val meta = fp.itemMeta(id, "video", now, session.sideOfTravel, SystemClock.elapsedRealtimeNanos())
            .put("mime", "video/mp4").put("segment", segmentIndex++).put("startMs", now).put("startT", iso(now))
        store.appendItem(session.id, meta)
        val file = store.videoFile(session.id, id)
        var startMs = now
        recording = vc.output.prepareRecording(this, FileOutputOptions.Builder(file).build())
            .start(ContextCompat.getMainExecutor(this)) { ev ->
                when (ev) {
                    is VideoRecordEvent.Start -> {
                        val t = System.currentTimeMillis()
                        startMs = t
                        store.patchItem(session.id, id, JSONObject().put("startMs", t).put("startT", iso(t))
                            .put("startElapsedNanos", SystemClock.elapsedRealtimeNanos()))
                        _state.update { it.copy(recording = true) }
                    }
                    is VideoRecordEvent.Finalize -> {
                        val t = System.currentTimeMillis()
                        store.patchItem(session.id, id, JSONObject().put("endMs", t).put("endT", iso(t)).put("durationMs", t - startMs)
                            .put("recordedDurationMs", ev.recordingStats.recordedDurationNanos / 1_000_000)
                            .put("finalizeError", if (ev.hasError()) ev.error else 0))
                        _state.update { it.copy(recording = false, videos = it.videos + if (file.length() > 0) 1 else 0) }
                        segmentJob?.cancel()
                        if (_state.value.running) startSegment()   // next segment (or wait for the camera to reopen)
                    }
                }
            }
        segmentJob = lifecycleScope.launch {
            delay(session.settings.videoSegmentSec * 1000L)
            recording?.stop()
        }
    }

    // ---------- session control ----------

    fun start() {
        if (_state.value.running) return
        if (session.startedAt == null) session.startedAt = iso(System.currentTimeMillis())
        store.save(session)
        _state.update { it.copy(running = true) }
        if (session.mode == Mode.VIDEO) startSegment() else shutter.reset()
    }

    fun pause() {
        _state.update { it.copy(running = false) }
        restartVideoWhenCameraOpens = false
        segmentJob?.cancel()
        recording?.stop()
        recording = null
    }

    /** End the session: stop everything, write the summary, leave the foreground. */
    fun finishSession() {
        if (activeSession.value == null) return
        pause()
        lm?.removeUpdates(locationListener)
        gnss?.let { session.gnss = it.summary(); it.stop() }
        imu?.stop()
        val ui = _state.value
        session.endedAt = iso(System.currentTimeMillis())
        session.counts = JSONObject().put("photos", ui.photos).put("videos", ui.videos).put("trackPoints", ui.trackPoints)
        store.save(session)
        val sid = session.id
        io.execute { runCatching { Coverage.forSession(store, sid) } }   // (a full package name here would hit the `io` executor)
        activeSession.value = null                      // let the upload worker take it
        Uploads.enqueue(this, sid)
        runCatching { wake?.release() }
        activeSession.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
