import Foundation
import Shared

// The recordings on the iPhone: Place capture sessions and street rides ("Quét đường"). Each is a folder
// Documents/sessions/<id>/ with session.json (the list's record below) and photos/<photoId>.jpg.
// - Place capture: places and photos in session.json; the manifest is written by the shared Kotlin code (PlaceSession).
// - Street ride: sweep.json (session.json in the Android app's format, written by the shared SweepExport),
//   items.jsonl (one photo per line), track.jsonl, ways.jsonl, imu.csv, frames.csv: the same files as an Android ride,
//   so the export and the PC pipeline (process-sweep, prepare.py) are the same.

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
    // street rides (absent on Place capture sessions and on sessions of older versions)
    var kind: String?                 // "sweep"; nil = Place capture
    var target: String?               // "shops" | "addresses" | "signs"
    var holding: String?              // "mount" | "hand"
    var photoCount: Int?
    var distanceM: Double?
    var uploadedMs: Int64?

    var isSweep: Bool { kind == "sweep" }
    var shotCount: Int { isSweep ? (photoCount ?? 0) : photos.count }
}

func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

func uid(_ prefix: String) -> String {
    let t = String(nowMs(), radix: 36)
    let r = String((0..<5).map { _ in "0123456789abcdefghijklmnopqrstuvwxyz".randomElement()! })
    return prefix + t + r
}

/** What an export gives: the zip, its manifest (for the upload metadata) and its name. */
struct ExportedZip {
    let url: URL
    let manifest: String
    let fileName: String
    let trackLines: [String]
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

    // street ride files
    func sweepDocURL(_ s: String) -> URL { dir(s).appendingPathComponent("sweep.json") }
    func itemsURL(_ s: String) -> URL { dir(s).appendingPathComponent("items.jsonl") }
    func trackURL(_ s: String) -> URL { dir(s).appendingPathComponent("track.jsonl") }
    func waysURL(_ s: String) -> URL { dir(s).appendingPathComponent("ways.jsonl") }
    func imuURL(_ s: String) -> URL { dir(s).appendingPathComponent("imu.csv") }
    func framesURL(_ s: String) -> URL { dir(s).appendingPathComponent("frames.csv") }

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

    /** Session name "{street} {yyyy-MM-dd HHhmm}" (shared rule SessionNaming.name: the street most photos were taken on). */
    func name(for s: CaptureSession, fallback: String) -> String {
        SessionNaming.shared.name(streets: s.photos.compactMap { $0.roadName }, stamp: Self.stamp(s.startedMs ?? s.createdMs),
                                  fallback: fallback, nearby: nil)
    }

    /** The start time in a session's name, local time: "2026-11-19 14h32" (SessionNaming.STAMP). */
    static func stamp(_ ms: Int64) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd HH'h'mm"
        return f.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
    }

    // ---------- street ride: session.json in the Android format (sweep.json) ----------

    func sweepDoc(_ id: String) -> String? { try? String(contentsOf: sweepDocURL(id), encoding: .utf8) }

    func saveSweepDoc(_ id: String, _ doc: String) {
        try? FileManager.default.createDirectory(at: dir(id).appendingPathComponent("photos"), withIntermediateDirectories: true)
        try? doc.write(to: sweepDocURL(id), atomically: true, encoding: .utf8)
    }

    func markExported(_ id: String) {
        guard var s = load(id) else { return }
        s.exportedMs = nowMs()
        save(s)
    }

    func markUploaded(_ id: String) {
        guard var s = load(id) else { return }
        s.uploadedMs = nowMs()
        s.exportedMs = s.exportedMs ?? s.uploadedMs
        save(s)
    }

    /** Bytes the session takes on the phone. */
    func bytes(_ id: String) -> Int64 {
        let fm = FileManager.default
        guard let e = fm.enumerator(at: dir(id), includingPropertiesForKeys: [.fileSizeKey]) else { return 0 }
        var n: Int64 = 0
        for case let u as URL in e { n += Int64((try? u.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
        return n
    }

    // ---------- export: manifest (shared Kotlin) + files, as one zip with manifest.json at its root ----------

    func manifest(_ s: CaptureSession) -> String {
        let device: [String: String] = [
            "platform": "ios",
            "model": DeviceInfo.model,
            "system": DeviceInfo.system,
            "app": DeviceInfo.appVersion,
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

    /** The zip of a session (either kind), written to [to] (a temporary place by default), ready to share or upload. */
    func exportZip(_ s: CaptureSession, to: URL? = nil) throws -> ExportedZip {
        let fm = FileManager.default
        if s.isSweep { return try exportSweep(s, to: to) }
        let name = fileStem(s) + ".zip"
        let out = to ?? fm.temporaryDirectory.appendingPathComponent(name)
        let manifest = self.manifest(s)
        let zip = try ZipWriter(out)
        try zip.add("manifest.json", data: Data(manifest.utf8))
        for p in s.photos {
            let src = photoURL(s.id, p.id)
            if fm.fileExists(atPath: src.path) { try zip.add("photos/\(p.id).jpg", file: src) }
        }
        try zip.finish()
        return ExportedZip(url: out, manifest: manifest, fileName: name, trackLines: [])
    }

    /** A street ride's zip, as the Android Exporter writes it: manifest.json, track.geojson, imu.csv, frames.csv, photos/. */
    private func exportSweep(_ s: CaptureSession, to: URL?) throws -> ExportedZip {
        let fm = FileManager.default
        guard var doc = sweepDoc(s.id) else { throw CocoaError(.fileReadNoSuchFile) }
        let now = nowMs()
        doc = SweepExport.shared.withTime(session: doc, key: "exportedAt", ms: now)
        // photos need their file (a photo still being written when the app was closed is left out)
        let items = LineFile.lines(itemsURL(s.id)).filter { line in
            guard let o = jsonObject(line), let id = o["id"] as? String else { return false }
            let f = photoURL(s.id, id)
            return ((try? fm.attributesOfItem(atPath: f.path)[.size] as? NSNumber)?.int64Value ?? 0) > 0
        }
        let track = LineFile.lines(trackURL(s.id))
        let ways = LineFile.lines(waysURL(s.id))
        let imuSize = ((try? fm.attributesOfItem(atPath: imuURL(s.id).path)[.size] as? NSNumber)?.int64Value ?? 0)
        let framesSize = ((try? fm.attributesOfItem(atPath: framesURL(s.id).path)[.size] as? NSNumber)?.int64Value ?? 0)
        let manifest = SweepExport.shared.manifest(session: doc, items: items, ways: ways, trackPoints: Int32(track.count),
                                                   imu: imuSize > 0, frames: framesSize > 0, exportedMs: now)
        let name = SweepExport.shared.fileName(session: doc)
        let out = to ?? fm.temporaryDirectory.appendingPathComponent(name)
        let zip = try ZipWriter(out)
        try zip.add("manifest.json", data: Data(manifest.utf8))
        try zip.add("track.geojson", data: Data(SweepExport.shared.trackGeoJson(lines: track).utf8))
        if imuSize > 0 { try zip.add("imu.csv", file: imuURL(s.id)) }
        if framesSize > 0 { try zip.add("frames.csv", file: framesURL(s.id)) }
        for line in items {
            if let o = jsonObject(line), let id = o["id"] as? String { try zip.add("photos/\(id).jpg", file: photoURL(s.id, id)) }
        }
        try zip.finish()
        saveSweepDoc(s.id, doc)
        return ExportedZip(url: out, manifest: manifest, fileName: name, trackLines: track)
    }

    private func fileStem(_ s: CaptureSession) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
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
    static var systemVersion: String {
        let v = ProcessInfo.processInfo.operatingSystemVersion
        return "\(v.majorVersion).\(v.minorVersion).\(v.patchVersion)"
    }
    static var appVersion: String { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "" }

    /** session.device of a street ride (the Android app writes manufacturer, model, sdk, … : the iPhone its own). */
    static func json() -> String {
        jsonText(["platform": "ios", "manufacturer": "Apple", "model": model, "system": system,
                  "app": appVersion, "lang": Locale.preferredLanguages.first ?? "", "lidar": CameraService.hasLiDAR])
    }
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
