import AVFoundation
import CoreLocation
import CoreMotion
import Foundation
import Shared
import UIKit

/**
 * A street ride ("Quét đường") being recorded: the Android CaptureService for the iPhone. GPS every second
 * (track.jsonl), the device motion at 50 Hz (imu.csv, the camera's direction and elevation, shake), road names from
 * OpenStreetMap (Overpass, loaded cell by cell around the rider, as on Android), and the photos taken by themselves
 * when the shared SweepShotTimer says so (slide of the camera frames; GPS distance on a signs ride).
 *
 * iPhone limits: the camera only runs while the app is on screen (the screen is kept on during a ride); in the
 * background photos pause and, with the location background mode, the GPS track goes on. On return the ride says so.
 */
final class RideRecorder: NSObject, ObservableObject, CLLocationManagerDelegate {
    let sessionId: String
    let target: String
    let hand: Bool
    let store: SessionStore
    let camera = SweepCamera()

    // what the capture screen shows
    @Published private(set) var running = false
    @Published private(set) var started = false
    @Published private(set) var photos = 0
    @Published private(set) var accuracyM: Int?
    @Published private(set) var gpsFresh = false
    @Published private(set) var gpsGood = false
    @Published private(set) var speedKmh: Int?
    @Published private(set) var maxSpeedKmh = 0
    @Published private(set) var photoEveryM = 0.0
    @Published private(set) var roadName: String?
    @Published private(set) var roadData = "loading"          // loading | ok | offline
    @Published private(set) var warnings: [String] = []       // codes, see SweepCaptureView.warningText
    @Published private(set) var pausedInBackground = false    // photos stopped while the app was in the background
    @Published private(set) var distanceM = 0.0
    @Published private(set) var lastError: String?

    private let ride: SweepRide
    private let timer: SweepShotTimer
    private var doc: String
    private let lm = CLLocationManager()
    private let motion = CMMotionManager()
    private let motionQueue = OperationQueue()
    private var tick: Timer?
    private var track: LineFile?
    private var items: LineFile?
    private var waysFile: LineFile?
    private var imu: LineFile?
    private var frames: LineFile?
    private var busy = false
    private var lastFixDate: Date?
    private var lastFixPoint: CLLocation?
    private var blurryUntil = Date.distantPast
    private var unusableDropped = 0
    private var inBackground = false
    private var observers: [NSObjectProtocol] = []
    private var roadFetching = false
    private var roadRetryAt = Date.distantPast
    private var roadBackoff: TimeInterval = 30
    private var overpassLast = Date.distantPast
    private var finished = false
    private var opened = false

    // motion, written on the motion queue and read on the main thread
    private let motionLock = NSLock()
    private var gyroRecent: [(Int64, Double)] = []
    private var accelRecent: [(Int64, Double)] = []
    private var azimuth: Double?
    private var elevation: Double?

    init(sessionId: String, target: String, hand: Bool, store: SessionStore) {
        self.sessionId = sessionId
        self.target = target
        self.hand = hand
        self.store = store
        ride = SweepRide(target: target, hand: hand, maxAccuracyM: 30, offRoadWarnM: 60)
        timer = SweepShotTimer(target: target, hand: hand)
        doc = store.sweepDoc(sessionId) ?? "{}"
        super.init()
        ride.photoLongEdge = Int32(target == "addresses" ? 4000 : 2560)
        maxSpeedKmh = Int(ride.maxSpeedKmh)
        photoEveryM = ride.photoEveryM
        photos = LineFile.lines(store.itemsURL(sessionId)).count
        started = store.load(sessionId)?.startedMs != nil
    }

    // ---------------------------------------------------------------- lifecycle

    func open() {
        if opened { return }
        opened = true
        let id = sessionId
        track = LineFile(store.trackURL(id))
        items = LineFile(store.itemsURL(id))
        waysFile = LineFile(store.waysURL(id))
        imu = LineFile(store.imuURL(id), header: "sensor,elapsedNanos,x,y,z,w")
        frames = LineFile(store.framesURL(id), header: timer.framesHeader())
        UIApplication.shared.isIdleTimerDisabled = true      // the camera needs the app on screen: keep it on

        lm.delegate = self
        lm.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        lm.distanceFilter = kCLDistanceFilterNone
        lm.activityType = .otherNavigation
        lm.pausesLocationUpdatesAutomatically = false
        // the GPS track goes on in the background, when the app declares it (Info.plist UIBackgroundModes: location)
        if let modes = Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String], modes.contains("location") {
            lm.allowsBackgroundLocationUpdates = true
            lm.showsBackgroundLocationIndicator = true
        }
        lm.startUpdatingLocation()
        startMotion()

        camera.onFrame = { [weak self] f in self?.onFrame(f) }
        camera.start(longEdge: Int(ride.photoLongEdge))
        tick = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in self?.onTick() }

        let nc = NotificationCenter.default
        observers.append(nc.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            guard let self else { return }
            self.inBackground = true
            if self.running { self.pausedInBackground = true }
            self.flushFiles()
        })
        observers.append(nc.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
            guard let self else { return }
            self.inBackground = false
            UIApplication.shared.isIdleTimerDisabled = true
            self.camera.resume()
        })
        observers.append(nc.addObserver(forName: UIDevice.orientationDidChangeNotification, object: nil, queue: .main) { [weak self] _ in
            self?.orientationChanged()
        })
        UIDevice.current.beginGeneratingDeviceOrientationNotifications()
        orientationChanged()
    }

    /** Start (the first time it waits for a fresh GPS fix, as on Android) or resume after Dừng. */
    func start() {
        if running { return }
        if !started && !gpsFresh { return }
        if !started {
            started = true
            let now = nowMs()
            doc = SweepExport.shared.withTime(session: doc, key: "startedAt", ms: now)
            store.saveSweepDoc(sessionId, doc)
            if var s = store.load(sessionId) { s.startedMs = now; store.save(s) }
        }
        running = true
        pausedInBackground = false
        timer.start(nowNs: RideClock.nanos())
    }

    func pause() { running = false }

    func dismissBackgroundNote() { pausedInBackground = false }

    /**
     * End the ride: stop everything and save it with its name (the street most photos were taken on and the start
     * time). [discard]: the rider throws it away (deleted, never sent). Returns whether it was kept.
     */
    @discardableResult
    func finish(discard: Bool) -> Bool {
        if finished { return !discard }
        finished = true
        running = false
        tick?.invalidate()
        lm.stopUpdatingLocation()
        lm.allowsBackgroundLocationUpdates = false
        motion.stopDeviceMotionUpdates()
        camera.stop()
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        observers.removeAll()
        UIApplication.shared.isIdleTimerDisabled = false
        flushFiles()
        [track, items, waysFile, imu, frames].forEach { $0?.close() }
        if discard || photos == 0 && LineFile.lines(store.trackURL(sessionId)).isEmpty {
            store.delete(sessionId)
            return false
        }
        let now = nowMs()
        let s = store.load(sessionId)
        let stamp = SessionStore.stamp(s?.startedMs ?? s?.createdMs ?? now)
        let name = SweepExport.shared.sessionName(items: LineFile.lines(store.itemsURL(sessionId)), ways: LineFile.lines(store.waysURL(sessionId)),
                                                  stamp: stamp, fallback: NSLocalizedString("No street name", comment: ""))
        doc = SweepExport.shared.withRide(session: doc, ride: ride)
        doc = SweepExport.shared.withTime(session: doc, key: "endedAt", ms: now)
        doc = SweepExport.shared.withText(session: doc, key: "name", value: name)
        doc = SweepExport.shared.withJson(session: doc, key: "counts",
                                          json: jsonText(["photos": photos, "videos": 0, "trackPoints": LineFile.lines(store.trackURL(sessionId)).count]))
        saveCameraInfo()
        store.saveSweepDoc(sessionId, doc)
        if var rec = s {
            rec.endedMs = now
            rec.name = name
            rec.photoCount = photos
            rec.distanceM = distanceM
            store.save(rec)
        }
        return true
    }

    private func flushFiles() { [track, items, waysFile, imu, frames].forEach { $0?.flush() } }

    private func saveCameraInfo() {
        guard !camera.infoJson.isEmpty, var info = jsonObject(camera.infoJson) else { return }
        info["unusableDropped"] = unusableDropped
        if camera.tooDark { info["tooDarkSeen"] = true }
        doc = SweepExport.shared.withJson(session: doc, key: "camera", json: jsonText(info))
    }

    // ---------------------------------------------------------------- GPS

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        for l in locations { onLocation(l) }
    }

    private func onLocation(_ l: CLLocation) {
        guard l.horizontalAccuracy > 0 else { return }
        let en = RideClock.fromDate(l.timestamp)
        let tMs = Int64(l.timestamp.timeIntervalSince1970 * 1000)
        let line = ride.onFix(tMs: tMs, lat: l.coordinate.latitude, lon: l.coordinate.longitude, accuracyM: l.horizontalAccuracy,
                              altitudeM: l.verticalAccuracy > 0 ? l.altitude : .nan,
                              speedMps: l.speed >= 0 ? l.speed : .nan,
                              bearingDeg: l.course >= 0 ? l.course : .nan, elapsedNanos: en)
        track?.append(line)
        if started, let p = lastFixPoint, l.horizontalAccuracy <= 30 {
            let d = l.distance(from: p)
            let dt = l.timestamp.timeIntervalSince(p.timestamp)
            if dt > 0 && d / dt < 40 { distanceM += d }
        }
        if l.horizontalAccuracy <= 30 { lastFixPoint = l }
        lastFixDate = l.timestamp
        if ride.changed { sessionChanged() }
        updateStatus()
        loadRoads()
        // no camera frames (none yet, or the app is in the background): count the slide from the GPS speed instead
        if !inBackground, timer.onGps(nowNs: RideClock.nanos(), speedMps: ride.speedMps, running: running, busy: busy,
                                       coverageM: ride.coverageM, nowMs: nowMs()) {
            shoot()
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {}

    private func onTick() {
        updateStatus()
        if running, !inBackground, camera.interrupted == false, !camera.ready, camera.failed == nil { camera.resume() }
    }

    private var fresh: Bool {
        guard let d = lastFixDate else { return false }
        return CaptureRules.shared.gpsFresh(fixAgeMs: max(0, Int64(Date().timeIntervalSince(d) * 1000)))
    }

    private func updateStatus() {
        gpsFresh = fresh
        gpsGood = ride.gpsGood
        accuracyM = ride.hasFix ? Int(ride.accuracyM.rounded()) : nil
        speedKmh = ride.speedMps.isNaN ? nil : Int(ride.speedKmh.rounded())
        roadName = ride.roadName
        maxSpeedKmh = Int(ride.maxSpeedKmh)
        photoEveryM = ride.photoEveryM
        var w = ride.warnings()
        if Date() < blurryUntil { w.append("blurry") }
        if timer.missedRecently(nowMs: nowMs()) { w.append("missed") }
        if camera.tooDark && running { w.append("dark") }
        if started && !gpsFresh { w.append("gpsLost") }
        if timer.aimLow && running { w.append("aimLow") }
        if timer.swinging && running { w.append("swinging") }
        if w != warnings { warnings = w }
    }

    /** The travel direction or the camera direction became known: save it; earlier photos get the new direction. */
    private func sessionChanged() {
        ride.changed = false
        doc = SweepExport.shared.withRide(session: doc, ride: ride)
        store.saveSweepDoc(sessionId, doc)
        if ride.mountJson != nil {
            let r = ride
            items?.rewrite { r.remeasure(itemJson: $0) }
        }
    }

    // ---------------------------------------------------------------- road names (OpenStreetMap, online)

    private func loadRoads() {
        if roadFetching || Date() < roadRetryAt { return }
        let cells = ride.cellsToLoad()
        guard let cell = cells.first else { return }
        for c in cells.dropFirst() { ride.cellFailed(cell: c) }      // one at a time; the rest on the next fixes
        roadFetching = true
        let query = ride.cellQuery(cell: cell)
        let wait = max(0, 1.5 - Date().timeIntervalSince(overpassLast))
        DispatchQueue.main.asyncAfter(deadline: .now() + wait) { [weak self] in
            self?.overpassLast = Date()
            RideRecorder.overpass(query, servers: RideRoads.shared.overpassServers) { [weak self] body in
                guard let self else { return }
                self.roadFetching = false
                if let body, self.ride.cellLoaded(cell: cell, body: body, source: "overpass") >= 0 {
                    self.roadBackoff = 30
                    self.roadData = "ok"
                    for w in self.ride.drainNewWays() { self.waysFile?.append(w) }
                    self.doc = SweepExport.shared.withRide(session: self.doc, ride: self.ride)
                    self.store.saveSweepDoc(self.sessionId, self.doc)
                    self.updateStatus()
                } else {
                    self.ride.cellFailed(cell: cell)
                    self.roadRetryAt = Date().addingTimeInterval(self.roadBackoff)
                    self.roadBackoff = min(self.roadBackoff * 2, 300)
                    if !self.ride.hasRoadData { self.roadData = "offline" }
                }
            }
        }
    }

    /** POST the query to each server in turn until one answers with JSON; nil when none does. Main thread callback. */
    static func overpass(_ query: String, servers: [String], done: @escaping (String?) -> Void) {
        guard let first = servers.first, let url = URL(string: first) else { DispatchQueue.main.async { done(nil) }; return }
        var req = URLRequest(url: url, timeoutInterval: 30)
        req.httpMethod = "POST"
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        req.setValue("quet-pho-ios/\(DeviceInfo.appVersion)", forHTTPHeaderField: "User-Agent")
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        req.httpBody = Data(("data=" + (query.addingPercentEncoding(withAllowedCharacters: allowed) ?? "")).utf8)
        URLSession.shared.dataTask(with: req) { data, resp, _ in
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            if code == 200, let d = data, let s = String(data: d, encoding: .utf8), RideRoads.shared.parseWays(body: s) != nil {
                DispatchQueue.main.async { done(s) }
            } else {
                RideRecorder.overpass(query, servers: Array(servers.dropFirst()), done: done)
            }
        }.resume()
    }

    // ---------------------------------------------------------------- motion (imu.csv, compass, elevation, shake)

    private func startMotion() {
        guard motion.isDeviceMotionAvailable else { return }
        motionQueue.maxConcurrentOperationCount = 1
        motion.deviceMotionUpdateInterval = 0.02
        let frame: CMAttitudeReferenceFrame = CMMotionManager.availableAttitudeReferenceFrames().contains(.xMagneticNorthZVertical)
            ? .xMagneticNorthZVertical : .xArbitraryCorrectedZVertical
        motion.startDeviceMotionUpdates(using: frame, to: motionQueue) { [weak self] m, _ in
            guard let self, let m else { return }
            let t = RideClock.fromUptime(m.timestamp)
            let r = m.rotationRate, g = m.gravity, u = m.userAcceleration
            // Android conventions: gyroscope rad/s; accelerometer m/s² including gravity, +9.81 up when lying flat
            let k = -9.80665
            let ax = (g.x + u.x) * k, ay = (g.y + u.y) * k, az = (g.z + u.z) * k
            self.imu?.append(String(format: "g,%lld,%.5f,%.5f,%.5f,", t, r.x, r.y, r.z))
            self.imu?.append(String(format: "a,%lld,%.5f,%.5f,%.5f,", t, ax, ay, az))
            let gm = (r.x * r.x + r.y * r.y + r.z * r.z).squareRoot()
            let am = (ax * ax + ay * ay + az * az).squareRoot()
            let gn = (g.x * g.x + g.y * g.y + g.z * g.z).squareRoot()
            // the rear camera looks along −Z: its elevation from gravity (0 = level, −90 = straight down)
            let elev: Double? = gn > 0.1 ? asin(max(-1, min(1, g.z / gn))) * 180 / .pi : nil
            let az0 = LocationService.cameraAzimuth(gravity: g, field: m.magneticField)
            self.motionLock.lock()
            self.gyroRecent.append((t, gm)); self.gyroRecent.removeAll { t - $0.0 > 1_000_000_000 }
            self.accelRecent.append((t, am)); self.accelRecent.removeAll { t - $0.0 > 1_000_000_000 }
            self.elevation = elev
            if let az0 { self.azimuth = az0 }
            self.motionLock.unlock()
        }
    }

    /** Fastest turn rate over the 100 ms before [t]; nil without a gyroscope. */
    private func gyroNow(_ t: Int64) -> Double? {
        motionLock.lock(); defer { motionLock.unlock() }
        return gyroRecent.filter { t - $0.0 <= 100_000_000 }.map { $0.1 }.max()
    }

    private func elevationNow() -> Double? {
        motionLock.lock(); defer { motionLock.unlock() }
        return elevation
    }

    /** `motion` of a photo (Android ImuRecorder.snapshot): shake and the camera's direction at [t]. */
    private func motionJson(_ t: Int64) -> String {
        motionLock.lock(); defer { motionLock.unlock() }
        var o: [String: Any] = [:]
        let g = gyroRecent.filter { t - $0.0 <= 300_000_000 }.map { $0.1 }
        if let mx = g.max() { o["gyroMaxRadS"] = (mx * 1000).rounded() / 1000 }
        if accelRecent.count > 2 {
            let m = accelRecent.map { $0.1 }.reduce(0, +) / Double(accelRecent.count)
            let sd = (accelRecent.map { ($0.1 - m) * ($0.1 - m) }.reduce(0, +) / Double(accelRecent.count)).squareRoot()
            o["accelStdMs2"] = (sd * 1000).rounded() / 1000
        }
        if let a = azimuth { o["cameraAzimuthDeg"] = (a * 1000).rounded() / 1000 }
        if let e = elevation { o["cameraElevationDeg"] = (e * 1000).rounded() / 1000 }
        return o.isEmpty ? "" : jsonText(o)
    }

    // ---------------------------------------------------------------- orientation

    private var deviceOrientation = UIDeviceOrientation.landscapeLeft

    private func orientationChanged() {
        let o = UIDevice.current.orientation
        guard o == .portrait || o == .portraitUpsideDown || o == .landscapeLeft || o == .landscapeRight else { return }
        deviceOrientation = o
        let up = o.isPortrait
        if up != ride.portrait {
            ride.portrait = up
            camera.portrait = up
            updateStatus()
        }
    }

    private var targetRotationDeg: Int32 {
        switch deviceOrientation {
        case .portrait: return 0
        case .portraitUpsideDown: return 180
        case .landscapeRight: return 270
        default: return 90
        }
    }

    // ---------------------------------------------------------------- photos

    private func onFrame(_ f: SweepCamera.Frame) {
        if ride.portrait != f.portrait { return }
        if !hasLens, !camera.hFovLandscape.isNaN {
            hasLens = true
            ride.useLens(hFovLandscapeDeg: camera.hFovLandscape, hFovPortraitDeg: camera.hFovPortrait)
            saveCameraInfo()
            doc = SweepExport.shared.withRide(session: doc, ride: ride)
            store.saveSweepDoc(sessionId, doc)
        }
        let speed = fresh ? ride.speedMps : Double.nan
        let due = timer.onFrame(tNs: f.tNs, shift: f.reading.shift, sharpness: f.reading.sharpness, gyroRadS: gyroNow(f.tNs) ?? .nan,
                                elevationDeg: elevationNow() ?? .nan, speedMps: speed, running: running && !inBackground, busy: busy,
                                coverageM: ride.coverageM, coveragePerM: ride.coveragePerM, nowMs: nowMs())
        if running {
            frames?.append(timer.frameLine(tNs: f.tNs, portrait: f.portrait, r: f.reading, brightness: f.brightness, speedMps: speed,
                                           bufferW: Int32(f.bufferW), bufferH: Int32(f.bufferH)))
        }
        if due { shoot() }
    }

    private var hasLens = false

    /** An auto photo (Android takePhoto): the item with what is known now, then what the camera says. */
    private func shoot() {
        guard running, !inBackground, camera.ready, !busy, ride.hasFix else { return }
        let gpsOk = fresh
        busy = true
        let id = uid("p_")
        let t = nowMs()
        let en = RideClock.nanos()
        let overlap = timer.shot()
        let item = ride.photoItem(id: id, tMs: t, elapsedNanos: en, trigger: "auto", gpsFresh: gpsOk, motionJson: motionJson(en),
                                  overlapJson: overlap, targetRotationDeg: targetRotationDeg)
        if ride.changed { sessionChanged() }
        let o = deviceOrientation
        camera.shoot(portrait: o.isPortrait, landscapeLeft: o != .landscapeRight) { [weak self] shot in
            guard let self else { return }
            self.busy = false
            guard let shot else { self.lastError = NSLocalizedString("Couldn't take the photo", comment: ""); return }
            let file = self.store.photoURL(self.sessionId, id)
            do { try shot.jpeg.write(to: file) } catch { self.lastError = error.localizedDescription; return }
            let r = self.ride.completePhoto(itemJson: item, width: Int32(shot.width), height: Int32(shot.height),
                                            exifOrientation: Int32(shot.exifOrientation), exposureS: shot.exposureS, iso: Int32(shot.iso),
                                            focalLengthMm: shot.focalLengthMm, bytes: Int64(shot.jpeg.count), sharpness: shot.sharpness,
                                            manualShutter: false)
            // a photo with almost no detail (a motorbike passing a metre away) is dropped; the next one comes sooner
            if r.unusable {
                try? FileManager.default.removeItem(at: file)
                self.timer.retake()
                self.unusableDropped += 1
                return
            }
            self.items?.append(r.json)
            self.photos += 1
            self.lastError = nil
            if r.warnRider { self.blurryUntil = Date().addingTimeInterval(10) }
            self.updateStatus()
        }
    }
}
