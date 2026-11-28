import AVFoundation
import CoreMedia
import ImageIO
import Shared
import UIKit

/**
 * The back wide camera for a street ride, as the Android app's CameraX setup:
 * - photos 16:9 at the ride's long edge or the next size up (2560 px; house numbers: the largest), JPEG 92 %;
 * - the shutter held at 1/500 s or faster (Android ShutterGuard): auto exposure capped with activeMaxExposureDuration,
 *   the ISO rises instead; "too dark" when the ISO is at its top and the picture still under-exposed;
 * - a small analysis stream next to the photos (~10 frames/s, ~256 px along the photo's horizontal): the scene's
 *   slide (shared FrameSlide), sharpness and brightness, which time the photos (shared SweepShotTimer).
 * The camera cannot run with the app in the background: iOS interrupts it, and it comes back by itself.
 */
final class SweepCamera: NSObject, ObservableObject, AVCapturePhotoCaptureDelegate, AVCaptureVideoDataOutputSampleBufferDelegate {
    struct Shot {
        let jpeg: Data
        let width: Int
        let height: Int
        let exifOrientation: Int
        let exposureS: Double
        let iso: Int
        let focalLengthMm: Double
        let sharpness: Double
    }

    struct Frame {
        let tNs: Int64
        let reading: FrameReading
        let brightness: Double
        let bufferW: Int
        let bufferH: Int
        let portrait: Bool
    }

    let session = AVCaptureSession()
    @Published private(set) var ready = false
    @Published private(set) var failed: String?
    @Published private(set) var tooDark = false
    @Published private(set) var interrupted = false

    /** Horizontal fields of view (degrees) of the landscape and the upright frame; NaN until the camera is open. */
    private(set) var hFovLandscape = Double.nan
    private(set) var hFovPortrait = Double.nan
    /** session.camera of the ride (JSON), once the camera is open. */
    private(set) var infoJson = ""

    /** Main thread: one analysed frame. */
    var onFrame: ((Frame) -> Void)?

    private let photoOutput = AVCapturePhotoOutput()
    private let videoOutput = AVCaptureVideoDataOutput()
    private let queue = DispatchQueue(label: "sweep.camera")
    private let frameQueue = DispatchQueue(label: "sweep.frames")
    private var device: AVCaptureDevice?
    private var pending: ((Shot?) -> Void)?
    private var analysis = true
    private let slide = FrameSlide()
    private var lastFrameNs: Int64 = 0
    private var portraitNow = false
    private let portraitLock = NSLock()
    private var darkTimer: Timer?
    private var observers: [NSObjectProtocol] = []

    static let maxExposureS = 1.0 / 500

    /** The phone is held upright (frames are turned so the photo's horizontal runs along x). */
    var portrait: Bool {
        get { portraitLock.lock(); defer { portraitLock.unlock() }; return portraitNow }
        set { portraitLock.lock(); portraitNow = newValue; portraitLock.unlock() }
    }

    func start(longEdge: Int) {
        let nc = NotificationCenter.default
        observers.append(nc.addObserver(forName: .AVCaptureSessionWasInterrupted, object: session, queue: .main) { [weak self] _ in
            self?.interrupted = true
        })
        observers.append(nc.addObserver(forName: .AVCaptureSessionInterruptionEnded, object: session, queue: .main) { [weak self] _ in
            self?.interrupted = false
        })
        observers.append(nc.addObserver(forName: .AVCaptureSessionRuntimeError, object: session, queue: .main) { [weak self] _ in
            self?.queue.async { if let s = self?.session, !s.isRunning { s.startRunning() } }
        })
        queue.async { [weak self] in self?.configure(longEdge: longEdge) }
        darkTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.checkLight() }
    }

    func stop() {
        darkTimer?.invalidate()
        darkTimer = nil
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        observers.removeAll()
        queue.async { [weak self] in self?.session.stopRunning() }
    }

    /** Resume after an interruption the system did not end by itself. */
    func resume() {
        queue.async { [weak self] in if let s = self?.session, !s.isRunning, s.inputs.count > 0 { s.startRunning() } }
    }

    private func configure(longEdge: Int) {
        if session.isRunning { return }
        guard let dev = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: dev) else {
            DispatchQueue.main.async { self.failed = "camera" }
            return
        }
        session.beginConfiguration()
        session.sessionPreset = .inputPriority
        guard session.canAddInput(input), session.canAddOutput(photoOutput) else {
            session.commitConfiguration()
            DispatchQueue.main.async { self.failed = "camera" }
            return
        }
        session.addInput(input)
        session.addOutput(photoOutput)
        videoOutput.alwaysDiscardsLateVideoFrames = true
        let fullRange = kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
        let pixel = videoOutput.availableVideoPixelFormatTypes.contains(fullRange) ? fullRange : kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
        videoOutput.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: pixel]
        videoOutput.setSampleBufferDelegate(self, queue: frameQueue)
        analysis = session.canAddOutput(videoOutput)
        if analysis { session.addOutput(videoOutput) }
        device = dev

        // a 16:9 format whose photos reach the long edge (the next size up, else the largest)
        var photoSize: CMVideoDimensions?
        if let picked = Self.pickFormat(dev, longEdge: longEdge) {
            do {
                try dev.lockForConfiguration()
                dev.activeFormat = picked.0
                dev.unlockForConfiguration()
                photoSize = picked.1
            } catch {}
        } else {
            session.sessionPreset = .photo      // no 16:9 photo size: the camera's own photo format
        }
        if let d = photoSize, dev.activeFormat.supportedMaxPhotoDimensions.contains(where: { $0.width == d.width && $0.height == d.height }) {
            photoOutput.maxPhotoDimensions = d
        }
        photoOutput.maxPhotoQualityPrioritization = .balanced
        session.commitConfiguration()

        // the shutter: 1/500 s at most, the ISO rises instead (Android ShutterGuard); focus keeps following
        do {
            try dev.lockForConfiguration()
            let f = dev.activeFormat
            var cap = CMTime(seconds: Self.maxExposureS, preferredTimescale: 1_000_000)
            if CMTimeCompare(cap, f.minExposureDuration) < 0 { cap = f.minExposureDuration }
            if CMTimeCompare(cap, f.maxExposureDuration) > 0 { cap = f.maxExposureDuration }
            if dev.isExposureModeSupported(.continuousAutoExposure) { dev.exposureMode = .continuousAutoExposure }
            dev.activeMaxExposureDuration = cap
            if dev.isFocusModeSupported(.continuousAutoFocus) { dev.focusMode = .continuousAutoFocus }
            if dev.isSmoothAutoFocusSupported { dev.isSmoothAutoFocusEnabled = false }
            dev.unlockForConfiguration()
        } catch {}

        // fields of view: the format's horizontal one is the landscape frame's; upright, the 16:9 frame's short side
        let land = Double(dev.activeFormat.videoFieldOfView)
        if land > 0 {
            hFovLandscape = (land * 10).rounded() / 10
            hFovPortrait = ((2 * atan(tan(land / 2 * .pi / 180) * 9 / 16) * 180 / .pi) * 10).rounded() / 10
        }
        let dims = CMVideoFormatDescriptionGetDimensions(dev.activeFormat.formatDescription)
        let photo = photoOutput.maxPhotoDimensions
        var info: [String: Any] = [
            "platform": "ios", "deviceType": "builtInWideAngleCamera", "format": "\(dims.width)x\(dims.height)",
            "photoSize": "\(photo.width)x\(photo.height)", "shutterGuard": true, "maxExposureS": Self.maxExposureS,
            "photoTrigger": analysis ? "frameOverlap" : "gpsSpeed", "minFocusDistanceMm": dev.minimumFocusDistance,
        ]
        if land > 0 { info["hFovDeg"] = hFovLandscape; info["hFovPortraitDeg"] = hFovPortrait }
        infoJson = jsonText(info)

        session.startRunning()
        DispatchQueue.main.async { self.ready = true }
    }

    /** The 16:9 format (video 1280–1920 wide, for a light analysis stream) with the best photo size for [longEdge]. */
    static func pickFormat(_ dev: AVCaptureDevice, longEdge: Int) -> (AVCaptureDevice.Format, CMVideoDimensions)? {
        func wide(_ w: Int32, _ h: Int32) -> Bool { h > 0 && abs(Double(w) / Double(h) - 16.0 / 9.0) < 0.03 }
        var best: (AVCaptureDevice.Format, CMVideoDimensions)?
        for f in dev.formats {
            let v = CMVideoFormatDescriptionGetDimensions(f.formatDescription)
            guard wide(v.width, v.height), v.width >= 1280, v.width <= 1920 else { continue }
            let photos = f.supportedMaxPhotoDimensions.filter { wide($0.width, $0.height) }
            guard !photos.isEmpty else { continue }
            let fits = photos.filter { Int($0.width) >= longEdge }.min(by: { $0.width < $1.width })
            let pick = fits ?? photos.max(by: { $0.width < $1.width })!
            if let b = best {
                let bFits = Int(b.1.width) >= longEdge
                let pFits = Int(pick.width) >= longEdge
                // reaching the long edge first; then the smaller photo (that reaches it) or the larger one (that does not)
                let better = (pFits && !bFits) || (pFits == bFits && (pFits ? pick.width < b.1.width : pick.width > b.1.width))
                if better { best = (f, pick) }
            } else {
                best = (f, pick)
            }
        }
        return best
    }

    private func checkLight() {
        guard let d = device, ready else { return }
        let dark = d.iso >= d.activeFormat.maxISO * 0.98 && d.exposureTargetOffset < -1.0
        if dark != tooDark { tooDark = dark }
    }

    // ---------------------------------------------------------------- photos

    /** Take a photo, upright for how the phone is held. */
    func shoot(portrait: Bool, landscapeLeft: Bool, done: @escaping (Shot?) -> Void) {
        queue.async { [weak self] in
            guard let self, self.session.isRunning, self.pending == nil else { DispatchQueue.main.async { done(nil) }; return }
            self.pending = done
            if let c = self.photoOutput.connection(with: .video) {
                let angle: CGFloat = portrait ? 90 : (landscapeLeft ? 0 : 180)
                if #available(iOS 17.0, *) {
                    if c.isVideoRotationAngleSupported(angle) { c.videoRotationAngle = angle }
                } else {
                    c.videoOrientation = portrait ? .portrait : (landscapeLeft ? .landscapeRight : .landscapeLeft)
                }
            }
            let s = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg,
                                                    AVVideoCompressionPropertiesKey: [AVVideoQualityKey: 0.92]])
            s.maxPhotoDimensions = self.photoOutput.maxPhotoDimensions
            s.photoQualityPrioritization = .speed
            self.photoOutput.capturePhoto(with: s, delegate: self)
        }
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        let done = pending
        pending = nil
        guard error == nil, let data = photo.fileDataRepresentation() else { DispatchQueue.main.async { done?(nil) }; return }
        var width = 0, height = 0, orientation = 0
        var exposure = 0.0, iso = 0, focal = 0.0
        if let src = CGImageSourceCreateWithData(data as CFData, nil),
           let p = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [String: Any] {
            width = (p[kCGImagePropertyPixelWidth as String] as? NSNumber)?.intValue ?? 0
            height = (p[kCGImagePropertyPixelHeight as String] as? NSNumber)?.intValue ?? 0
            orientation = (p[kCGImagePropertyOrientation as String] as? NSNumber)?.intValue ?? 0
            if let exif = p[kCGImagePropertyExifDictionary as String] as? [String: Any] {
                exposure = (exif[kCGImagePropertyExifExposureTime as String] as? NSNumber)?.doubleValue ?? 0
                iso = (exif[kCGImagePropertyExifISOSpeedRatings as String] as? [NSNumber])?.first?.intValue ?? 0
                focal = (exif[kCGImagePropertyExifFocalLength as String] as? NSNumber)?.doubleValue ?? 0
            }
        }
        let shot = Shot(jpeg: data, width: width, height: height, exifOrientation: orientation, exposureS: exposure, iso: iso,
                        focalLengthMm: focal, sharpness: Self.sharpness(data))
        DispatchQueue.main.async { done?(shot) }
    }

    /** Edge detail of a ~480 px copy of the photo (shared BlurCheck.sharpness); NaN when it cannot be read. */
    static func sharpness(_ jpeg: Data) -> Double {
        guard let src = CGImageSourceCreateWithData(jpeg as CFData, nil) else { return .nan }
        let opts: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceThumbnailMaxPixelSize: 480,
                                     kCGImageSourceCreateThumbnailWithTransform: false]
        guard let img = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) else { return .nan }
        let w = img.width, h = img.height
        guard w >= 8, h >= 8 else { return .nan }
        var px = [UInt8](repeating: 0, count: w * h)
        let ok: Bool = px.withUnsafeMutableBytes { buf -> Bool in
            guard let ctx = CGContext(data: buf.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w,
                                      space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return false }
            ctx.draw(img, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        guard ok else { return .nan }
        let arr = KotlinIntArray(size: Int32(w * h))
        for i in 0..<(w * h) { arr.set(index: Int32(i), value: Int32(px[i])) }
        return BlurCheck.shared.sharpness(gray: arr, w: Int32(w), h: Int32(h))
    }

    // ---------------------------------------------------------------- analysis frames

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        let t = pts.isValid ? RideClock.fromUptime(CMTimeGetSeconds(pts)) : RideClock.nanos()
        if t - lastFrameNs < 90_000_000 { return }            // about 10 frames a second
        lastFrameNs = t
        guard let pb = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let up = portrait
        CVPixelBufferLockBaseAddress(pb, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pb, .readOnly) }
        guard CVPixelBufferGetPlaneCount(pb) >= 1, let base = CVPixelBufferGetBaseAddressOfPlane(pb, 0) else { return }
        let bw = CVPixelBufferGetWidthOfPlane(pb, 0), bh = CVPixelBufferGetHeightOfPlane(pb, 0)
        let stride = CVPixelBufferGetBytesPerRowOfPlane(pb, 0)
        let y = base.assumingMemoryBound(to: UInt8.self)
        // ~256 px along the photo's horizontal: landscape = the buffer's long side, upright = its short side
        let step = max(1, (up ? bh : bw) / 256)
        let gw = bw / step, gh = bh / step
        guard gw >= 8, gh >= 8 else { return }
        let w = up ? gh : gw, h = up ? gw : gh
        // each pixel the mean of a 4 x 4 grid of samples in its step x step block (picking one pixel aliases)
        let k = min(4, step)
        let sub = max(1, step / k)
        let gray = KotlinIntArray(size: Int32(w * h))
        var sum = 0, n = 0
        for r in 0..<gh {
            for c in 0..<gw {
                var s = 0
                for a in 0..<k { let row = y + (r * step + a * sub) * stride + c * step; for b in 0..<k { s += Int(row[b * sub]) } }
                let v = s / (k * k)
                let i = up ? (c * w + (gh - 1 - r)) : (r * w + c)
                gray.set(index: Int32(i), value: Int32(v))
                if r >= gh / 4 && r < gh * 3 / 4 && c >= gw / 4 && c < gw * 3 / 4 { sum += v; n += 1 }
            }
        }
        let reading = slide.next(gray: gray, w: Int32(w), h: Int32(h), portrait: up)
        let frame = Frame(tNs: t, reading: reading, brightness: n > 0 ? Double(sum) / Double(n) : 0, bufferW: bw, bufferH: bh, portrait: up)
        DispatchQueue.main.async { [weak self] in self?.onFrame?(frame) }
    }
}
