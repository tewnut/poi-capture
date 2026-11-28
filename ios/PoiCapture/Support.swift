import Foundation
import Security

// Small building blocks of the street ride and the upload: one clock for every stream, append-only text files, a zip
// writer (the export's layout as on Android: manifest.json at the root), and the token in the Keychain.

/**
 * The elapsed-time clock every stream of a ride is stamped with (`elapsedNanos` of photos, track.jsonl `en`, imu.csv,
 * frames.csv), like Android's SystemClock.elapsedRealtimeNanos: CLOCK_MONOTONIC keeps counting while the phone
 * sleeps. Sensor and camera timestamps come on the uptime clock (which stops in sleep) and are moved onto it.
 */
enum RideClock {
    static func nanos() -> Int64 { Int64(clock_gettime_nsec_np(CLOCK_MONOTONIC)) }

    /** A timestamp in seconds on the uptime clock (CoreMotion, AVFoundation host time) → [nanos]. */
    static func fromUptime(_ seconds: TimeInterval) -> Int64 {
        let age = ProcessInfo.processInfo.systemUptime - seconds
        return nanos() - Int64(age * 1e9)
    }

    /** A wall-clock date (a CLLocation's timestamp) → [nanos]. */
    static func fromDate(_ d: Date) -> Int64 {
        nanos() - Int64(Date().timeIntervalSince(d) * 1e9)
    }
}

/** A text file written line by line on its own queue (track.jsonl, items.jsonl, imu.csv, frames.csv …). */
final class LineFile {
    let url: URL
    private let queue: DispatchQueue
    private var handle: FileHandle?
    private var buffer = Data()

    init(_ url: URL, header: String? = nil) {
        self.url = url
        queue = DispatchQueue(label: "line." + url.lastPathComponent)
        let fm = FileManager.default
        if !fm.fileExists(atPath: url.path) {
            fm.createFile(atPath: url.path, contents: header.map { Data(($0 + "\n").utf8) })
        }
        handle = try? FileHandle(forWritingTo: url)
        _ = try? handle?.seekToEnd()
    }

    func append(_ line: String) {
        queue.async {
            self.buffer.append(Data((line + "\n").utf8))
            if self.buffer.count > 64 * 1024 { self.flushNow() }
        }
    }

    func flush() { queue.sync { self.flushNow() } }

    /**
     * Rewrite the file line by line through [change] (nil keeps a line), with appends held meanwhile; the file is
     * reopened at its end afterwards, so later lines land in the new file.
     */
    func rewrite(_ change: (String) -> String?) {
        queue.sync {
            self.flushNow()
            try? self.handle?.close()
            self.handle = nil
            let lines = LineFile.lines(self.url)
            var out = ""
            var changed = false
            for l in lines {
                if let c = change(l) { out += c + "\n"; changed = true } else { out += l + "\n" }
            }
            if changed { try? out.write(to: self.url, atomically: true, encoding: .utf8) }
            self.handle = try? FileHandle(forWritingTo: self.url)
            _ = try? self.handle?.seekToEnd()
        }
    }

    func close() {
        queue.sync {
            self.flushNow()
            try? self.handle?.close()
            self.handle = nil
        }
    }

    private func flushNow() {
        guard !buffer.isEmpty, let h = handle else { return }
        h.write(buffer)
        buffer.removeAll(keepingCapacity: true)
    }

    /** Every non-empty line of a file (nothing when it does not exist). */
    static func lines(_ url: URL) -> [String] {
        guard let s = try? String(contentsOf: url, encoding: .utf8) else { return [] }
        return s.split(separator: "\n", omittingEmptySubsequences: true).map(String.init)
    }
}

/**
 * Writes a zip with every entry stored (JPEGs are already compressed; the phone's CPU is spared), as Python's zipfile
 * and the PC pipeline read it. Zip64 records are added when the archive passes 4 GB (long rides).
 */
final class ZipWriter {
    private let handle: FileHandle
    private var offset: UInt64 = 0
    private var central = Data()
    private var count = 0

    init(_ url: URL) throws {
        let fm = FileManager.default
        try? fm.removeItem(at: url)
        guard fm.createFile(atPath: url.path, contents: nil) else { throw CocoaError(.fileWriteUnknown) }
        handle = try FileHandle(forWritingTo: url)
    }

    func add(_ name: String, data: Data) throws {
        try entry(name, size: UInt64(data.count), crc: CRC32.of(data)) { h in h.write(data) }
    }

    func add(_ name: String, file: URL) throws {
        let size = (try FileManager.default.attributesOfItem(atPath: file.path)[.size] as? NSNumber)?.uint64Value ?? 0
        let crc = try CRC32.of(file: file)
        try entry(name, size: size, crc: crc) { h in
            let r = try FileHandle(forReadingFrom: file)
            defer { try? r.close() }
            while true {
                let chunk = r.readData(ofLength: 1 << 20)
                if chunk.isEmpty { break }
                h.write(chunk)
            }
        }
    }

    private func entry(_ name: String, size: UInt64, crc: UInt32, body: (FileHandle) throws -> Void) throws {
        let nameData = Data(name.utf8)
        let start = offset
        let big = size >= 0xFFFF_FFFF
        var local = Data()
        local.le32(0x0403_4b50)
        local.le16(big ? 45 : 20)        // version needed
        local.le16(0x0800)               // UTF-8 names
        local.le16(0)                    // stored
        local.le16(0); local.le16(0x21)  // time, date (1980-01-01): the manifest carries the real times
        local.le32(crc)
        local.le32(big ? 0xFFFF_FFFF : UInt32(size))
        local.le32(big ? 0xFFFF_FFFF : UInt32(size))
        local.le16(UInt16(nameData.count))
        local.le16(big ? 20 : 0)
        local.append(nameData)
        if big { local.le16(1); local.le16(16); local.le64(size); local.le64(size) }
        handle.write(local)
        offset += UInt64(local.count)
        try body(handle)
        offset += size

        let farOffset = start >= 0xFFFF_FFFF
        var extra = Data()
        if big || farOffset {
            var e = Data()
            if big { e.le64(size); e.le64(size) }
            if farOffset { e.le64(start) }
            extra.le16(1); extra.le16(UInt16(e.count)); extra.append(e)
        }
        var c = Data()
        c.le32(0x0201_4b50)
        c.le16(45); c.le16((big || farOffset) ? 45 : 20)
        c.le16(0x0800); c.le16(0); c.le16(0); c.le16(0x21)
        c.le32(crc)
        c.le32(big ? 0xFFFF_FFFF : UInt32(size))
        c.le32(big ? 0xFFFF_FFFF : UInt32(size))
        c.le16(UInt16(nameData.count)); c.le16(UInt16(extra.count)); c.le16(0)
        c.le16(0); c.le16(0); c.le32(0)
        c.le32(farOffset ? 0xFFFF_FFFF : UInt32(start))
        c.append(nameData); c.append(extra)
        central.append(c)
        count += 1
    }

    func finish() throws {
        let cdStart = offset
        handle.write(central)
        offset += UInt64(central.count)
        let zip64 = cdStart >= 0xFFFF_FFFF || count >= 0xFFFF || central.count >= 0xFFFF_FFFF
        if zip64 {
            let z64At = offset
            var z = Data()
            z.le32(0x0606_4b50); z.le64(44); z.le16(45); z.le16(45); z.le32(0); z.le32(0)
            z.le64(UInt64(count)); z.le64(UInt64(count)); z.le64(UInt64(central.count)); z.le64(cdStart)
            z.le32(0x0706_4b50); z.le32(0); z.le64(z64At); z.le32(1)
            handle.write(z)
            offset += UInt64(z.count)
        }
        var e = Data()
        e.le32(0x0605_4b50); e.le16(0); e.le16(0)
        e.le16(zip64 ? 0xFFFF : UInt16(count)); e.le16(zip64 ? 0xFFFF : UInt16(count))
        e.le32(zip64 ? 0xFFFF_FFFF : UInt32(central.count))
        e.le32(zip64 ? 0xFFFF_FFFF : UInt32(cdStart))
        e.le16(0)
        handle.write(e)
        try handle.close()
    }
}

enum CRC32 {
    private static let table: [UInt32] = (0..<256).map { i -> UInt32 in
        var c = UInt32(i)
        for _ in 0..<8 { c = (c & 1) != 0 ? (0xEDB8_8320 ^ (c >> 1)) : (c >> 1) }
        return c
    }

    static func update(_ crc: UInt32, _ data: Data) -> UInt32 {
        var c = crc
        data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            for b in raw { c = table[Int((c ^ UInt32(b)) & 0xFF)] ^ (c >> 8) }
        }
        return c
    }

    static func of(_ data: Data) -> UInt32 { update(0xFFFF_FFFF, data) ^ 0xFFFF_FFFF }

    static func of(file: URL) throws -> UInt32 {
        let r = try FileHandle(forReadingFrom: file)
        defer { try? r.close() }
        var c: UInt32 = 0xFFFF_FFFF
        while true {
            let chunk = r.readData(ofLength: 1 << 20)
            if chunk.isEmpty { break }
            c = update(c, chunk)
        }
        return c ^ 0xFFFF_FFFF
    }
}

private extension Data {
    mutating func le16(_ v: UInt16) { var x = v.littleEndian; Swift.withUnsafeBytes(of: &x) { append(contentsOf: $0) } }
    mutating func le32(_ v: UInt32) { var x = v.littleEndian; Swift.withUnsafeBytes(of: &x) { append(contentsOf: $0) } }
    mutating func le64(_ v: UInt64) { var x = v.littleEndian; Swift.withUnsafeBytes(of: &x) { append(contentsOf: $0) } }
}

/** The upload token: in the Keychain, this device only, never in UserDefaults or an export. */
enum Keychain {
    private static let service = "vn.vmaps.quetpho.upload"

    static func get(_ account: String) -> String? {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }

    static func set(_ account: String, _ value: String) {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(base as CFDictionary)
        if value.isEmpty { return }
        var add = base
        add[kSecValueData as String] = Data(value.utf8)
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }
}

/** JSON text → dictionary (nil when it is not an object). */
func jsonObject(_ s: String) -> [String: Any]? {
    guard let d = s.data(using: .utf8) else { return nil }
    return (try? JSONSerialization.jsonObject(with: d)) as? [String: Any]
}

/** A dictionary → JSON text ("{}" when it cannot be written). */
func jsonText(_ o: [String: Any]) -> String {
    guard JSONSerialization.isValidJSONObject(o), let d = try? JSONSerialization.data(withJSONObject: o) else { return "{}" }
    return String(data: d, encoding: .utf8) ?? "{}"
}
