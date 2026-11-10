import AVFoundation
import CoreMedia
import ImageIO
import UIKit

/**
 * Rear camera for Place capture. On iPhones with LiDAR (Pro models) the LiDAR depth camera is used: each photo comes
 * with a depth map, and the distance to the shop front is the median depth of the middle of the frame (LiDAR reaches
 * about 5 m: farther fronts get no distance, the on-foot default is used). Elsewhere: the wide camera, no depth.
 */
final class CameraService: NSObject, ObservableObject, AVCapturePhotoCaptureDelegate {
    struct Shot {
        let jpeg: Data
        let widthPx: Int
        let heightPx: Int
        let distanceM: Double?
        let hFovDeg: Double?
        let orientation: String
        let exposureS: Double?
        let iso: Int?
    }

    static var hasLiDAR: Bool { lidarDevice() != nil }
    private static func lidarDevice() -> AVCaptureDevice? {
        if #available(iOS 15.4, *) {
            return AVCaptureDevice.DiscoverySession(deviceTypes: [.builtInLiDARDepthCamera], mediaType: .video, position: .back).devices.first
        }
        return nil
    }

    let session = AVCaptureSession()
    @Published private(set) var ready = false
    @Published private(set) var failed: String?
    private let output = AVCapturePhotoOutput()
    private let queue = DispatchQueue(label: "camera")
    private var device: AVCaptureDevice?
    private var pending: ((Shot?) -> Void)?
    private var orientation = "portrait"

    func start() {
        queue.async { [weak self] in self?.configure() }
    }

    func stop() {
        queue.async { [weak self] in self?.session.stopRunning() }
    }

    private func configure() {
        if session.isRunning { return }
        if !session.inputs.isEmpty { session.startRunning(); return }
        session.beginConfiguration()
        session.sessionPreset = .photo
        let dev = Self.lidarDevice() ?? AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back)
        guard let dev, let input = try? AVCaptureDeviceInput(device: dev), session.canAddInput(input), session.canAddOutput(output) else {
            session.commitConfiguration()
            DispatchQueue.main.async { self.failed = "camera" }
            return
        }
        session.addInput(input)
        session.addOutput(output)
        device = dev
        // LiDAR: a format that delivers depth, and depth with every photo
        if Self.lidarDevice() != nil, let f = dev.formats.last(where: { !$0.supportedDepthDataFormats.isEmpty }) {
            try? dev.lockForConfiguration()
            dev.activeFormat = f
            if let d = f.supportedDepthDataFormats.last(where: { CMFormatDescriptionGetMediaSubType($0.formatDescription) == kCVPixelFormatType_DepthFloat32 }) {
                dev.activeDepthDataFormat = d
            }
            dev.unlockForConfiguration()
        }
        output.isDepthDataDeliveryEnabled = output.isDepthDataDeliverySupported
        output.maxPhotoQualityPrioritization = .balanced
        session.commitConfiguration()
        session.startRunning()
        DispatchQueue.main.async { self.ready = true }
    }

    /** Take a photo, upright for how the phone is held (portrait / landscape from gravity). */
    func shoot(portrait: Bool, landscapeLeft: Bool, done: @escaping (Shot?) -> Void) {
        queue.async { [weak self] in
            guard let self, self.ready else { DispatchQueue.main.async { done(nil) }; return }
            self.pending = done
            self.orientation = portrait ? "portrait" : "landscape"
            if let c = self.output.connection(with: .video) {
                let angle: CGFloat = portrait ? 90 : (landscapeLeft ? 0 : 180)
                if #available(iOS 17.0, *) {
                    if c.isVideoRotationAngleSupported(angle) { c.videoRotationAngle = angle }
                } else {
                    c.videoOrientation = portrait ? .portrait : (landscapeLeft ? .landscapeRight : .landscapeLeft)
                }
            }
            let s = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg])
            s.isDepthDataDeliveryEnabled = self.output.isDepthDataDeliveryEnabled
            s.photoQualityPrioritization = .balanced
            self.output.capturePhoto(with: s, delegate: self)
        }
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        let done = pending
        pending = nil
        guard error == nil, let data = photo.fileDataRepresentation() else { DispatchQueue.main.async { done?(nil) }; return }
        let img = UIImage(data: data)
        let w = Int((img?.size.width ?? 0) * (img?.scale ?? 1)), h = Int((img?.size.height ?? 0) * (img?.scale ?? 1))
        let exif = photo.metadata[kCGImagePropertyExifDictionary as String] as? [String: Any]
        let exposure = exif?[kCGImagePropertyExifExposureTime as String] as? Double
        let iso = (exif?[kCGImagePropertyExifISOSpeedRatings as String] as? [Int])?.first
        // horizontal field of view of this frame: the sensor's (landscape) one, or the narrower side in portrait
        var hFov: Double?
        if let f = device?.activeFormat.videoFieldOfView, f > 0 {
            let land = Double(f)
            hFov = orientation == "portrait" ? 2 * atan(tan(land / 2 * .pi / 180) * 3 / 4) * 180 / .pi : land
        }
        let shot = Shot(jpeg: data, widthPx: w, heightPx: h, distanceM: Self.frontDistance(photo.depthData), hFovDeg: hFov,
                        orientation: orientation, exposureS: exposure, iso: iso)
        DispatchQueue.main.async { done?(shot) }
    }

    /** Median depth (metres) of the middle fifth of the depth map; nil when there is none or nothing in range. */
    static func frontDistance(_ depth: AVDepthData?) -> Double? {
        guard let depth else { return nil }
        let d = depth.depthDataType == kCVPixelFormatType_DepthFloat32 ? depth : depth.converting(toDepthDataType: kCVPixelFormatType_DepthFloat32)
        let map = d.depthDataMap
        CVPixelBufferLockBaseAddress(map, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(map, .readOnly) }
        let w = CVPixelBufferGetWidth(map), h = CVPixelBufferGetHeight(map)
        guard let base = CVPixelBufferGetBaseAddress(map) else { return nil }
        let stride = CVPixelBufferGetBytesPerRow(map) / MemoryLayout<Float32>.size
        let p = base.assumingMemoryBound(to: Float32.self)
        var vals: [Float32] = []
        for y in (h * 2 / 5)..<(h * 3 / 5) {
            for x in (w * 2 / 5)..<(w * 3 / 5) {
                let v = p[y * stride + x]
                if v.isFinite && v > 0.2 && v < 10 { vals.append(v) }
            }
        }
        guard vals.count > 20 else { return nil }
        vals.sort()
        return Double(vals[vals.count / 2])
    }
}
