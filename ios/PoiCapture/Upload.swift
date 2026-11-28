import Foundation
import Network
import Shared
import UIKit

/**
 * Sending rides to the team's server (server/README.md), as the Android app does (capture/UploadWork.kt):
 * - the server address and the access token come from the setup QR (`<endpoint>#token=…&name=…`) or are typed; the
 *   token lives in the Keychain (this device only), never in an export;
 * - each ride's zip (the same zip as Send) goes straight to R2 ("Direct uploads": the server hands out presigned
 *   part URLs, 4 parts at once, each part's ETag kept so a restart sends only what is missing, then the server joins
 *   them); a server without direct uploads (a plain tus server: 404 / 501) gets a tus 1.0 upload (POST, then PATCH
 *   in 8 MiB chunks, resumed from HEAD's Upload-Offset);
 * - the same metadata keys as Android (shared SweepExport.uploadMetadata), `X-Client` naming the app and the phone;
 * - automatically after a ride ends (on Wi-Fi only, unless the rider asked otherwise), never while recording, with
 *   retries; uploaded = sent, and the ride is removed from the phone once the server holds every byte (an option).
 * iPhone limit: uploads run while the app is open (and for the short time iOS gives an app going to the background);
 * an unfinished upload goes on the next time the app is opened.
 */
final class Uploader: ObservableObject {
    static let defaultURL = "https://poi-uploader.4hum.ai/files/"

    struct Record: Codable {
        var status = "none"           // none | queued | uploading | done | failed
        var error: String?
        var size: Int64 = 0
        var sent: Int64 = 0
        var direct: String?           // shared DirectState JSON, once the server opened a direct upload
        var tusUrl: String?           // a tus upload's address (fallback)
        var uploadedMs: Int64?
        var manual = false
        var attempts = 0
        var metadata: [String: String]?
        var location: String?
    }

    struct HttpError: Error, LocalizedError {
        let code: Int
        let what: String
        var errorDescription: String? { "\(what): HTTP \(code)" }
        /** 401 / 403: the token; 413: too large; 400 / 405: the server does not do this. */
        var permanent: Bool { [400, 401, 403, 405, 413].contains(code) }
    }

    private struct Recording: Error {}

    @Published var url: String { didSet { UserDefaults.standard.set(url, forKey: "upload.url") } }
    @Published var token: String { didSet { Keychain.set("token", token) } }
    @Published var tokenName: String { didSet { UserDefaults.standard.set(tokenName, forKey: "upload.tokenName") } }
    @Published var auto: Bool { didSet { UserDefaults.standard.set(auto, forKey: "upload.auto") } }
    @Published var wifiOnly: Bool { didSet { UserDefaults.standard.set(wifiOnly, forKey: "upload.wifiOnly") } }
    @Published var removeAfter: Bool { didSet { UserDefaults.standard.set(removeAfter, forKey: "upload.removeAfter") } }
    @Published private(set) var records: [String: Record] = [:]
    @Published private(set) var onWifi = false
    @Published private(set) var online = false

    /** A ride is being recorded: no upload meanwhile (its camera, GPS and disk come first). */
    var recording = false { didSet { if !recording { kick() } } }

    weak var store: SessionStore?
    private var working = false
    private let monitor = NWPathMonitor()
    private var bgTask: UIBackgroundTaskIdentifier = .invalid

    init() {
        let d = UserDefaults.standard
        url = d.string(forKey: "upload.url") ?? Self.defaultURL
        token = Keychain.get("token") ?? ""
        tokenName = d.string(forKey: "upload.tokenName") ?? ""
        auto = d.object(forKey: "upload.auto") as? Bool ?? true
        wifiOnly = d.object(forKey: "upload.wifiOnly") as? Bool ?? true
        removeAfter = d.object(forKey: "upload.removeAfter") as? Bool ?? true
        if let data = d.data(forKey: "upload.records"), let r = try? JSONDecoder().decode([String: Record].self, from: data) { records = r }
        // an upload cut off when the app was closed goes on
        for (id, r) in records where r.status == "uploading" { records[id]?.status = "queued" }
        monitor.pathUpdateHandler = { [weak self] path in
            DispatchQueue.main.async {
                self?.online = path.status == .satisfied
                self?.onWifi = path.status == .satisfied && !path.isExpensive
                self?.kick()
            }
        }
        monitor.start(queue: DispatchQueue(label: "upload.network"))
    }

    var configured: Bool { Cloud.shared.configured(url: url, token: token) }
    var endpoint: String? { Cloud.shared.endpoint(typed: url) }

    private var client: String {
        Cloud.shared.clientHeader(version: DeviceInfo.appVersion, system: DeviceInfo.systemVersion, model: DeviceInfo.model)
    }

    func record(_ id: String) -> Record { records[id] ?? Record() }

    private func save(_ id: String, _ change: (inout Record) -> Void) {
        var r = records[id] ?? Record()
        change(&r)
        records[id] = r
        if let data = try? JSONEncoder().encode(records) { UserDefaults.standard.set(data, forKey: "upload.records") }
    }

    /** Apply a setup QR / link. Returns nil when it was used, else what is wrong ("notHttp" | "noToken"). */
    func apply(setup text: String) -> String? {
        let s = Cloud.shared.parseSetup(text: text)
        if let p = s.problem { return p }
        url = s.endpoint
        token = s.token
        tokenName = s.name ?? ""
        kick()
        return nil
    }

    // ---------------------------------------------------------------- queue

    /** Queue a session's upload. [manual]: the rider tapped Upload, so any network will do. */
    func enqueue(_ id: String, manual: Bool) {
        guard configured, record(id).status != "done" else { return }
        save(id) { $0.status = "queued"; $0.error = nil; $0.manual = manual || $0.manual; if manual { $0.attempts = 0 } }
        kick()
    }

    /** A ride ended: upload it when the rider asked for automatic uploads. */
    func rideEnded(_ id: String) {
        if configured && auto { enqueue(id, manual: false) }
    }

    /** The session is deleted: forget its upload (what is on the server stays). */
    func forget(_ id: String) {
        records.removeValue(forKey: id)
        if let data = try? JSONEncoder().encode(records) { UserDefaults.standard.set(data, forKey: "upload.records") }
        try? FileManager.default.removeItem(at: zipURL(id))
    }

    private func zipURL(_ id: String) -> URL {
        let d = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("uploads")
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d.appendingPathComponent("\(id).zip")
    }

    private func allowed(_ r: Record) -> Bool { online && (r.manual || !wifiOnly || onWifi) }

    /** Start the worker when there is something to send and nothing stops it. */
    func kick() {
        guard !working, !recording, configured, store != nil else { return }
        guard records.contains(where: { $0.value.status == "queued" && allowed($0.value) }) else { return }
        working = true
        beginBackground()
        Task { await self.work() }
    }

    private func work() async {
        while true {
            let next: String? = await MainActor.run {
                if self.recording { return nil }
                return self.records.first(where: { $0.value.status == "queued" && self.allowed($0.value) })?.key
            }
            guard let id = next else { break }
            await upload(id)
        }
        await MainActor.run {
            self.working = false
            self.endBackground()
        }
    }

    private func beginBackground() {
        if bgTask != .invalid { return }
        bgTask = UIApplication.shared.beginBackgroundTask(withName: "upload") { [weak self] in self?.endBackground() }
    }

    private func endBackground() {
        if bgTask != .invalid { UIApplication.shared.endBackgroundTask(bgTask); bgTask = .invalid }
    }

    // ---------------------------------------------------------------- one upload

    private func upload(_ id: String) async {
        let rec = await MainActor.run { self.record(id) }
        let endpoint: String? = await MainActor.run { self.endpoint }
        guard let store = self.store, let endpoint else { return }
        guard let session = store.load(id) else { await MainActor.run { self.forget(id) }; return }
        await MainActor.run { self.save(id) { $0.status = "uploading" } }
        do {
            let zip = zipURL(id)
            var size = (try? FileManager.default.attributesOfItem(atPath: zip.path)[.size] as? NSNumber)?.int64Value ?? -1
            var meta = rec.metadata
            // the zip of an earlier attempt goes on as it is; otherwise build it (the same zip as Send)
            if size < 0 || meta == nil || size != rec.size {
                let ex = try store.exportZip(session, to: zip)
                size = (try? FileManager.default.attributesOfItem(atPath: zip.path)[.size] as? NSNumber)?.int64Value ?? 0
                let m = SweepExport.shared.uploadMetadata(manifest: ex.manifest, fileName: ex.fileName, trackLines: ex.trackLines)
                meta = m
                let s = size
                await MainActor.run { self.save(id) { $0.size = s; $0.sent = 0; $0.direct = nil; $0.tusUrl = nil; $0.metadata = m } }
            }
            let metadata = meta ?? [:]
            let current = await MainActor.run { self.record(id) }
            var location: String
            if current.tusUrl == nil, let d = try await direct(id, endpoint: endpoint, zip: zip, size: size, metadata: metadata, saved: current.direct) {
                location = d
            } else {
                location = try await tus(id, endpoint: endpoint, zip: zip, size: size, metadata: metadata)
            }
            if let u = URL(string: location, relativeTo: URL(string: endpoint)) { location = u.absoluteString }
            let loc = location
            let total = size
            await MainActor.run {
                self.save(id) { $0.status = "done"; $0.error = nil; $0.sent = total; $0.uploadedMs = nowMs(); $0.location = loc; $0.direct = nil }
                store.markUploaded(id)
            }
            try? FileManager.default.removeItem(at: zip)
            // free the phone: only once the server says it holds every byte, and never while recording
            let remove = await MainActor.run { self.removeAfter && !self.recording }
            if remove, (try? await offset(loc)) == total {
                await MainActor.run {
                    store.delete(id)
                    self.forget(id)
                }
            }
        } catch is Recording {
            await MainActor.run { self.save(id) { $0.status = "queued" } }
        } catch {
            let http = error as? HttpError
            let msg = error.localizedDescription
            let permanent = http?.permanent == true
            await MainActor.run {
                self.save(id) { r in
                    if http?.code == 404 || http?.code == 409 { r.direct = nil; r.tusUrl = nil }   // the server forgot it: start again
                    r.attempts += 1
                    r.error = msg
                    r.status = (permanent || r.attempts >= 6) ? "failed" : "queued"
                }
            }
            if !permanent {
                let attempts = await MainActor.run { self.record(id).attempts }
                if attempts < 6 {
                    let wait = min(600.0, 30.0 * pow(2.0, Double(attempts - 1)))
                    try? await Task.sleep(nanoseconds: UInt64(wait * 1e9))
                }
            }
        }
    }

    private func checkRecording() async throws {
        if await MainActor.run(body: { self.recording }) { throw Recording() }
    }

    // ---------------------------------------------------------------- HTTP

    private func request(_ url: URL, _ method: String, auth: Bool = true, timeout: TimeInterval = 30) -> URLRequest {
        var r = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: timeout)
        r.httpMethod = method
        if auth {
            r.setValue("1.0.0", forHTTPHeaderField: "Tus-Resumable")
            if let a = Cloud.shared.authHeader(token: token) { r.setValue(a, forHTTPHeaderField: "Authorization") }
            r.setValue(client, forHTTPHeaderField: "X-Client")
        }
        return r
    }

    private func postJson(_ endpoint: String, _ path: String, _ body: String, _ what: String) async throws -> (Int, Data) {
        guard let s = Cloud.shared.directUrl(endpoint: endpoint, path: path), let u = URL(string: s) else { throw HttpError(code: 400, what: what) }
        var r = request(u, "POST")
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        r.httpBody = Data(body.utf8)
        let (data, resp) = try await URLSession.shared.data(for: r)
        return ((resp as? HTTPURLResponse)?.statusCode ?? 0, data)
    }

    private func readPart(_ zip: URL, offset: Int64, length: Int64) throws -> Data {
        let h = try FileHandle(forReadingFrom: zip)
        defer { try? h.close() }
        try h.seek(toOffset: UInt64(offset))
        let d = h.readData(ofLength: Int(length))
        if Int64(d.count) != length { throw CocoaError(.fileReadCorruptFile) }
        return d
    }

    /**
     * A direct upload; returns the upload's address at the tus endpoint, or nil when the server has no direct
     * uploads (then tus is used).
     */
    private func direct(_ id: String, endpoint: String, zip: URL, size: Int64, metadata: [String: String], saved: String?) async throws -> String? {
        var state: DirectState
        var urls: [Int32: String] = [:]
        if let s = saved, let d = Direct.shared.state(json: s), d.size == size {
            state = d
        } else {
            let (code, data) = try await postJson(endpoint, "", Direct.shared.createBody(size: size, metadata: metadata), "direct")
            if code == 404 || code == 501 { return nil }
            guard (200...299).contains(code) else { throw HttpError(code: code, what: "direct") }
            let body = String(data: data, encoding: .utf8) ?? ""
            guard let d = Direct.shared.parseCreate(body: body) else { throw HttpError(code: 502, what: "direct") }
            state = d
            for u in Direct.shared.parseUrls(body: body) { urls[u.part] = u.url }
            let j = d.toJson()
            await MainActor.run { self.save(id) { $0.direct = j; $0.sent = 0 } }
        }
        // 4 parts at once; each part's ETag is kept as it lands
        while !state.done {
            try await checkRecording()
            let pending = state.pending.map { $0.int32Value }
            let missing = pending.filter { urls[$0] == nil }
            if !missing.isEmpty {
                let ask = Array(missing.prefix(200)).map { KotlinInt(value: $0) }
                let (code, data) = try await postJson(endpoint, "/\(state.id)/urls", Direct.shared.urlsBody(parts: ask), "direct urls")
                guard (200...299).contains(code) else { throw HttpError(code: code, what: "direct urls") }
                for u in Direct.shared.parseUrls(body: String(data: data, encoding: .utf8) ?? "") { urls[u.part] = u.url }
            }
            let batch = Array(pending.prefix(4))
            let snapshot = state
            let known = urls
            let results: [(Int32, String?, Int)] = try await withThrowingTaskGroup(of: (Int32, String?, Int).self) { group in
                for n in batch {
                    group.addTask {
                        guard let us = known[n], let u = URL(string: us) else { return (n, nil, 0) }
                        let data = try self.readPart(zip, offset: snapshot.start(part: n), length: snapshot.length(part: n))
                        var r = URLRequest(url: u, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 120)
                        r.httpMethod = "PUT"
                        do {
                            let (_, resp) = try await URLSession.shared.upload(for: r, from: data)
                            let http = resp as? HTTPURLResponse
                            let code = http?.statusCode ?? 0
                            let etag = http?.value(forHTTPHeaderField: "ETag")
                            return (n, (200...299).contains(code) ? etag : nil, code)
                        } catch {
                            return (n, nil, -1)
                        }
                    }
                }
                var out: [(Int32, String?, Int)] = []
                for try await r in group { out.append(r) }
                return out
            }
            var failures = 0
            for (n, etag, code) in results {
                if let e = etag, !e.isEmpty {
                    state = state.withEtag(part: n, etag: e)
                } else {
                    if code == 404 { throw HttpError(code: 404, what: "part") }       // R2 forgot the upload: start again
                    if code == 403 { urls[n] = nil }                                  // the URL expired: ask again
                    failures += 1
                }
            }
            let j = state.toJson()
            let sent = state.sent
            await MainActor.run { self.save(id) { $0.direct = j; $0.sent = sent } }
            if failures == batch.count {
                partFailures += 1
                if partFailures > 4 { partFailures = 0; throw HttpError(code: results.first?.2 ?? 0, what: "part") }
            } else {
                partFailures = 0
            }
        }
        let (code, _) = try await postJson(endpoint, "/\(state.id)/complete", state.completeBody(), "direct complete")
        guard (200...299).contains(code) else { throw HttpError(code: code, what: "direct complete") }
        return state.location
    }

    private var partFailures = 0

    /** A tus 1.0 upload (POST, then PATCH in 8 MiB chunks), resumed from the server's offset. */
    private func tus(_ id: String, endpoint: String, zip: URL, size: Int64, metadata: [String: String]) async throws -> String {
        guard let base = URL(string: endpoint) else { throw HttpError(code: 400, what: "tus") }
        var location = await MainActor.run { self.record(id).tusUrl }
        if location == nil {
            var r = request(base, "POST")
            r.setValue(String(size), forHTTPHeaderField: "Upload-Length")
            r.setValue(Cloud.shared.tusMetadata(meta: metadata), forHTTPHeaderField: "Upload-Metadata")
            let (_, resp) = try await URLSession.shared.data(for: r)
            let http = resp as? HTTPURLResponse
            guard let h = http, (200...299).contains(h.statusCode), let loc = h.value(forHTTPHeaderField: "Location"),
                  let u = URL(string: loc, relativeTo: base) else { throw HttpError(code: http?.statusCode ?? 0, what: "tus create") }
            let s = u.absoluteString
            location = s
            await MainActor.run { self.save(id) { $0.tusUrl = s } }
        }
        guard let loc = location else { throw HttpError(code: 400, what: "tus") }
        var at = try await offset(loc) ?? 0
        while at < size {
            try await checkRecording()
            guard let u = URL(string: loc) else { throw HttpError(code: 400, what: "tus") }
            let n = min(Int64(8 << 20), size - at)
            let data = try readPart(zip, offset: at, length: n)
            var r = request(u, "PATCH", timeout: 120)
            r.setValue(String(at), forHTTPHeaderField: "Upload-Offset")
            r.setValue("application/offset+octet-stream", forHTTPHeaderField: "Content-Type")
            let (_, resp) = try await URLSession.shared.upload(for: r, from: data)
            let h = resp as? HTTPURLResponse
            guard let h, (200...299).contains(h.statusCode) else { throw HttpError(code: h?.statusCode ?? 0, what: "PATCH") }
            at = Int64(h.value(forHTTPHeaderField: "Upload-Offset") ?? "") ?? (at + n)
            let sent = at
            await MainActor.run { self.save(id) { $0.sent = sent } }
        }
        return loc
    }

    /** HEAD: the server's offset for an upload; nil when it does not know it (any more). */
    private func offset(_ location: String) async throws -> Int64? {
        guard let u = URL(string: location) else { return nil }
        let (_, resp) = try await URLSession.shared.data(for: request(u, "HEAD"))
        guard let h = resp as? HTTPURLResponse else { return nil }
        if h.statusCode == 404 || h.statusCode == 410 { return nil }
        guard (200...299).contains(h.statusCode) else { throw HttpError(code: h.statusCode, what: "HEAD") }
        return Int64(h.value(forHTTPHeaderField: "Upload-Offset") ?? "")
    }

    // ---------------------------------------------------------------- connection test (Settings)

    /** GET /me with the token: who it is and whether it may send rides, in words for the settings page. */
    func test() async -> String {
        guard let e = endpoint, let me = Cloud.shared.meUrl(endpoint: e), let u = URL(string: me) else {
            return NSLocalizedString("Enter an address starting with http:// or https://", comment: "")
        }
        do {
            let (data, resp) = try await URLSession.shared.data(for: request(u, "GET", timeout: 15))
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            if code == 401 || code == 403 { return String(format: NSLocalizedString("The server refused the token (HTTP %d)", comment: ""), code) }
            guard (200...299).contains(code) else { return String(format: NSLocalizedString("The server answered HTTP %d", comment: ""), code) }
            guard let who = Cloud.shared.parseMe(body: String(data: data, encoding: .utf8)) else {
                return NSLocalizedString("This address answers, but it is not a Quét Phố server", comment: "")
            }
            if !who.canUpload { return NSLocalizedString("This token cannot send rides: ask for one with Send rides", comment: "") }
            return String(format: NSLocalizedString("Connected as %@", comment: ""), who.name)
        } catch {
            return String(format: NSLocalizedString("Could not reach the server: %@", comment: ""), error.localizedDescription)
        }
    }
}
