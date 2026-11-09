import Foundation
import Shared

// A Place capture session on the iPhone: places, their photos, and how it is sent. Stored as
// Documents/sessions/<id>/session.json + photos/<photoId>.jpg. The manifest of the export is written by the shared
// Kotlin code (PlaceSession), so the agent reads it exactly like an Android session.

struct PhotoRecord: Codable, Identifiable {
    var id: String
    var tMs: Int64
    var placeId: String
    var lat: Double
    var lon: Double
    var accuracyM: Double
    var fixMs: Int64
    var altitudeM: Double?
    var cameraBearing: Double?
    var distanceM: Double?            // LiDAR, when the phone has it
    var widthPx: Int
    var heightPx: Int
    var hFovDeg: Double?
    var orientation: String
    var exposureS: Double?
    var iso: Int?
    var roadName: String?
}

struct PlaceRec: Codable, Identifiable {
    var id: String
    var createdMs: Int64
    var lat: Double?
    var lon: Double?
    var accuracyM: Double?
    var status: String = "new"
}

struct CaptureSession: Codable, Identifiable {
    var id: String
    var createdMs: Int64
    var startedMs: Int64?
    var endedMs: Int64?
    var contributor: String
    var email: String
    var name: String?
    var exportedMs: Int64?
    var places: [PlaceRec] = []
    var photos: [PhotoRecord] = []
}

func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

func uid(_ prefix: String) -> String {
    let t = String(nowMs(), radix: 36)
    let r = String((0..<5).map { _ in "0123456789abcdefghijklmnopqrstuvwxyz".randomElement()! })
    return prefix + t + r
}

final class SessionStore: ObservableObject {
    @Published private(set) var sessions: [CaptureSession] = []

    private let root: URL = {
        let d = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("sessions")
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }()

    init() { reload() }

    func dir(_ id: String) -> URL { root.appendingPathComponent(id) }
    func photoURL(_ s: String, _ p: String) -> URL { dir(s).appendingPathComponent("photos/\(p).jpg") }

    func reload() {
        let ids = (try? FileManager.default.contentsOfDirectory(atPath: root.path)) ?? []
        sessions = ids.compactMap { load($0) }.sorted { $0.createdMs > $1.createdMs }
    }

    func load(_ id: String) -> CaptureSession? {
        guard let data = try? Data(contentsOf: dir(id).appendingPathComponent("session.json")) else { return nil }
        return try? JSONDecoder().decode(CaptureSession.self, from: data)
    }

    func save(_ s: CaptureSession) {
        let d = dir(s.id)
        try? FileManager.default.createDirectory(at: d.appendingPathComponent("photos"), withIntermediateDirectories: true)
        if let data = try? JSONEncoder().encode(s) {
            try? data.write(to: d.appendingPathComponent("session.json"), options: .atomic)
        }
        reload()
    }

    func delete(_ id: String) {
        try? FileManager.default.removeItem(at: dir(id))
        reload()
    }

    /** Session name "{street} [yymmdd]" (shared rule: the street most photos were taken on). */
    func name(for s: CaptureSession, fallback: String) -> String {
        let f = DateFormatter()
        f.dateFormat = "yyMMdd"
        let day = f.string(from: Date(timeIntervalSince1970: Double(s.startedMs ?? s.createdMs) / 1000))
        return SessionNaming.shared.name(streets: s.photos.compactMap { $0.roadName }, yymmdd: day, fallback: fallback)
    }

    // ---------- export: manifest (shared Kotlin) + photos, zipped by the system ----------

    func manifest(_ s: CaptureSession) -> String {
        let device: [String: String] = [
            "platform": "ios",
            "model": DeviceInfo.model,
            "system": DeviceInfo.system,
            "app": Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "",
            "lidar": CameraService.hasLiDAR ? "true" : "false",
        ]
        let info = PlaceSessionInfo(id: s.id, createdMs: s.createdMs, startedMs: s.startedMs.map { KotlinLong(value: $0) },
                                    endedMs: s.endedMs.map { KotlinLong(value: $0) }, contributor: s.contributor, email: s.email,
                                    name: s.name, device: device, exportedAt: nil)
        let photos = s.photos.map { p in
            PlacePhoto(id: p.id, tMs: p.tMs, placeId: p.placeId, lat: p.lat, lon: p.lon, accuracyM: p.accuracyM, fixMs: p.fixMs,
                       altitudeM: p.altitudeM.map { KotlinDouble(value: $0) },
                       cameraBearing: p.cameraBearing.map { KotlinDouble(value: $0) }, bearingSource: "compass",
                       distanceM: p.distanceM.map { KotlinDouble(value: $0) }, distanceSource: p.distanceM == nil ? nil : "lidar",
                       widthPx: Int32(p.widthPx), heightPx: Int32(p.heightPx), hFovDeg: p.hFovDeg.map { KotlinDouble(value: $0) },
                       orientation: p.orientation, exposureS: p.exposureS.map { KotlinDouble(value: $0) },
                       iso: p.iso.map { KotlinInt(value: Int32($0)) }, roadName: p.roadName)
        }
        let places = s.places.map { r in
            PlaceRecord(id: r.id, createdMs: r.createdMs, lat: r.lat.map { KotlinDouble(value: $0) }, lon: r.lon.map { KotlinDouble(value: $0) },
                        accuracyM: r.accuracyM.map { KotlinDouble(value: $0) }, status: r.status, fields: [:], pinLat: nil, pinLon: nil)
        }
        return PlaceSession.shared.manifest(s: info, photos: photos, places: places, exportedMs: nowMs())
    }

    /** A zip of the session (manifest.json + photos/), ready for the share sheet. */
    func exportZip(_ s: CaptureSession) throws -> URL {
        let fm = FileManager.default
        let base = fm.temporaryDirectory.appendingPathComponent("export-\(s.id)")
        try? fm.removeItem(at: base)
        let folder = base.appendingPathComponent(fileStem(s))
        try fm.createDirectory(at: folder.appendingPathComponent("photos"), withIntermediateDirectories: true)
        try manifest(s).write(to: folder.appendingPathComponent("manifest.json"), atomically: true, encoding: .utf8)
        for p in s.photos {
            let src = photoURL(s.id, p.id)
            if fm.fileExists(atPath: src.path) { try fm.copyItem(at: src, to: folder.appendingPathComponent("photos/\(p.id).jpg")) }
        }
        // reading a folder "for uploading" gives a zip of it (system API, no library)
        var zipped: URL?
        var coordError: NSError?
        var copyError: Error?
        NSFileCoordinator().coordinate(readingItemAt: folder, options: .forUploading, error: &coordError) { tmp in
            let out = base.appendingPathComponent("\(fileStem(s)).zip")
            do { try fm.copyItem(at: tmp, to: out); zipped = out } catch { copyError = error }
        }
        if let e = coordError { throw e }
        if let e = copyError { throw e }
        guard let z = zipped else { throw CocoaError(.fileWriteUnknown) }
        return z
    }

    func markExported(_ id: String) {
        guard var s = load(id) else { return }
        s.exportedMs = nowMs()
        save(s)
    }

    private func fileStem(_ s: CaptureSession) -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyyMMddHHmm"
        let safe = (s.name ?? "places").components(separatedBy: CharacterSet.alphanumerics.inverted).filter { !$0.isEmpty }.joined(separator: "-")
        return "poi-capture_\(safe)_\(f.string(from: Date(timeIntervalSince1970: Double(s.createdMs) / 1000)))"
    }
}

enum DeviceInfo {
    static var model: String {
        var u = utsname()
        uname(&u)
        return withUnsafeBytes(of: &u.machine) { raw in String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self) }
    }
    static var system: String { "iOS " + ProcessInfo.processInfo.operatingSystemVersionString }
}

/** Contributor (set once, like the Android onboarding). */
final class Profile: ObservableObject {
    @Published var email: String { didSet { UserDefaults.standard.set(email, forKey: "email") } }
    @Published var name: String { didSet { UserDefaults.standard.set(name, forKey: "name") } }
    var emailOk: Bool { Onboarding.shared.isEmail(s: email) }
    var displayName: String { Onboarding.shared.displayName(name: name, email: email) }

    init() {
        email = UserDefaults.standard.string(forKey: "email") ?? ""
        name = UserDefaults.standard.string(forKey: "name") ?? ""
    }
}
