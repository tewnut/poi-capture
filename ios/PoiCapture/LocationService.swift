import CoreLocation
import CoreMotion
import Foundation
import Shared

/**
 * GPS position and camera direction, with the rules of the Android app:
 * - a photo needs a fix at most CaptureRules.GPS_MAX_AGE_MS old (5 s);
 * - iOS does not say which source a position comes from (GPS, Wi-Fi, cell), so a fix only counts as "GPS" when its
 *   accuracy is GPS-grade (≤ GPS_GRADE_M) and precise location is allowed;
 * - camera direction: the rear camera looks along the device's −Z axis; projected on the ground with gravity and the
 *   calibrated magnetic field (device frame), as on Android (no dependence on how the phone is held).
 */
final class LocationService: NSObject, ObservableObject, CLLocationManagerDelegate {
    static let GPS_GRADE_M = 20.0

    @Published private(set) var fix: CLLocation?
    @Published private(set) var authorization: CLAuthorizationStatus = .notDetermined
    @Published private(set) var precise = true
    @Published private(set) var now = Date()

    private let lm = CLLocationManager()
    private let motion = CMMotionManager()
    private var ticker: Timer?
    private(set) var cameraBearing: Double?   // degrees from north, clockwise

    override init() {
        super.init()
        lm.delegate = self
        lm.desiredAccuracy = kCLLocationAccuracyBest
        lm.distanceFilter = kCLDistanceFilterNone
        lm.activityType = .otherNavigation
        authorization = lm.authorizationStatus
        precise = lm.accuracyAuthorization == .fullAccuracy
    }

    func request() { lm.requestWhenInUseAuthorization() }

    func start() {
        lm.startUpdatingLocation()
        if motion.isDeviceMotionAvailable {
            motion.deviceMotionUpdateInterval = 0.1
            motion.startDeviceMotionUpdates(using: .xMagneticNorthZVertical, to: .main) { [weak self] m, _ in
                guard let m else { return }
                self?.cameraBearing = Self.cameraAzimuth(gravity: m.gravity, field: m.magneticField)
            }
        }
        ticker?.invalidate()
        ticker = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.now = Date() }   // re-check freshness
    }

    func stop() {
        lm.stopUpdatingLocation()
        motion.stopDeviceMotionUpdates()
        ticker?.invalidate()
    }

    /** The fix to put on a photo: GPS-grade and at most 5 s old, else nil (the capture holds). */
    var freshFix: CLLocation? {
        guard let f = fix, precise, f.horizontalAccuracy > 0, f.horizontalAccuracy <= Self.GPS_GRADE_M else { return nil }
        let ageMs = Int64(now.timeIntervalSince(f.timestamp) * 1000)
        return CaptureRules.shared.gpsFresh(fixAgeMs: max(0, ageMs)) ? f : nil
    }

    var allowed: Bool { authorization == .authorizedWhenInUse || authorization == .authorizedAlways }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        if let l = locations.last { fix = l; now = Date() }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        authorization = manager.authorizationStatus
        precise = manager.accuracyAuthorization == .fullAccuracy
        if allowed { start() }
    }

    /** Azimuth of the rear camera (−Z) from gravity and the magnetic field, both in the device frame. */
    static func cameraAzimuth(gravity g: CMAcceleration, field mf: CMCalibratedMagneticField) -> Double? {
        guard mf.accuracy != .uncalibrated else { return nil }
        let up = [-g.x, -g.y, -g.z]                                   // gravity points down
        let h = [mf.field.x, mf.field.y, mf.field.z]
        func cross(_ a: [Double], _ b: [Double]) -> [Double] { [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]] }
        func norm(_ a: [Double]) -> [Double]? { let n = (a[0] * a[0] + a[1] * a[1] + a[2] * a[2]).squareRoot(); return n < 1e-6 ? nil : a.map { $0 / n } }
        guard let u = norm(up), let east = norm(cross(h, u)) else { return nil }
        let north = cross(u, east)
        let cam = [0.0, 0.0, -1.0]
        let ce = cam[0] * east[0] + cam[1] * east[1] + cam[2] * east[2]
        let cn = cam[0] * north[0] + cam[1] * north[1] + cam[2] * north[2]
        guard ce * ce + cn * cn > 0.04 else { return nil }             // camera looking straight up / down
        let deg = atan2(ce, cn) * 180 / .pi
        return (deg + 360).truncatingRemainder(dividingBy: 360)
    }
}
