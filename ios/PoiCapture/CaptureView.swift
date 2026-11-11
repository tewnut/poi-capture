import AVFoundation
import CoreLocation
import SwiftUI
import Shared

/**
 * Place capture: camera full screen. Every photo needs a GPS-grade fix from the last 5 s (else "GPS lost" and the
 * shutter waits). Photos go to the current place; "Next place" starts the next one. "End" saves the session with
 * its name "{street} [yymmdd]" (street from reverse geocoding of the photos).
 */
struct CaptureView: View {
    @EnvironmentObject var store: SessionStore
    @EnvironmentObject var location: LocationService
    @StateObject private var camera = CameraService()
    @State var session: CaptureSession
    let onClose: () -> Void

    init(session: CaptureSession, onClose: @escaping () -> Void) {
        _session = State(initialValue: session)
        self.onClose = onClose
    }

    @State private var placeId: String?
    @State private var busy = false
    @State private var flash = false
    @State private var confirmEnd = false
    @State private var note: String?
    private let geocoder = CLGeocoder()

    var body: some View {
        let fresh = location.freshFix
        ZStack {
            CameraPreview(session: camera.session).ignoresSafeArea()
            if flash { Color.white.opacity(0.5).ignoresSafeArea() }

            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(fresh.map { String(format: NSLocalizedString("GPS ±%d m", comment: ""), Int($0.horizontalAccuracy.rounded())) }
                             ?? NSLocalizedString("Waiting for GPS…", comment: ""))
                            .font(.headline).foregroundStyle(.white).shadow(radius: 3)
                        Text(String(format: NSLocalizedString("Place %d · %d photos", comment: ""), max(session.places.count, 1), currentPhotos))
                            .font(.subheadline).foregroundStyle(.white).shadow(radius: 3)
                        if let note { Text(note).font(.subheadline.bold()).padding(8).background(.orange, in: RoundedRectangle(cornerRadius: 10)).foregroundStyle(.white) }
                    }
                    Spacer()
                    Button("End") { if session.photos.isEmpty { discard() } else { confirmEnd = true } }
                        .buttonStyle(.bordered).tint(.white)
                }
                Spacer()
                HStack(spacing: 16) {
                    Button { donePlace() } label: { Text("Next place").bold().padding(.horizontal, 6) }
                        .buttonStyle(.borderedProminent).tint(.blue).disabled(currentPhotos == 0)
                    Spacer()
                    Button { shoot() } label: {
                        Circle().strokeBorder(.white, lineWidth: 5).background(Circle().fill(.white.opacity(fresh == nil ? 0.3 : 0.9)))
                            .frame(width: 78, height: 78)
                    }
                    .disabled(fresh == nil || busy || !camera.ready)
                    .accessibilityLabel(Text("Shoot"))
                }
            }
            .padding(20)

            if camera.ready && fresh == nil { gpsLost }
            if !camera.ready {
                Text(camera.failed == nil ? "Opening the camera…" : "Camera can't open").bold().padding(14)
                    .background(.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 12)).foregroundStyle(.white)
            }
        }
        .onAppear { camera.start(); location.start(); UIDevice.current.beginGeneratingDeviceOrientationNotifications(); UIApplication.shared.isIdleTimerDisabled = true }
        .onDisappear { camera.stop(); UIApplication.shared.isIdleTimerDisabled = false }
        .confirmationDialog("End this session?", isPresented: $confirmEnd, titleVisibility: .visible) {
            Button("End and save") { end() }
            Button("Keep recording", role: .cancel) {}
        }
    }

    private var currentPhotos: Int { session.photos.filter { $0.placeId == placeId }.count }

    /** GPS lost (or not yet): nothing is recorded until it is back. */
    private var gpsLost: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("GPS lost: recording on hold").font(.title3.bold()).foregroundStyle(.red)
            Text("Every photo needs a GPS position from the last 5 seconds. Nothing is recorded until the GPS is back.")
            Text("• Move to open sky, away from tall buildings, trees and roofs.")
            Text("• Keep the phone's Location on, with precise location for this app.")
            Text("Recording carries on by itself as soon as the GPS is back.").font(.footnote).foregroundStyle(.secondary)
        }
        .padding(20).background(.white, in: RoundedRectangle(cornerRadius: 20)).padding(24)
    }

    private func shoot() {
        guard let fix = location.freshFix, !busy else { return }
        busy = true
        let o = UIDevice.current.orientation
        let portrait = !(o == .landscapeLeft || o == .landscapeRight)
        let bearing = location.cameraBearing
        let pid = placeId ?? newPlace(at: fix)
        camera.shoot(portrait: portrait, landscapeLeft: o == .landscapeLeft) { shot in
            busy = false
            guard let shot else { note = NSLocalizedString("Couldn't take the photo", comment: ""); return }
            let id = uid("p_")
            try? FileManager.default.createDirectory(at: store.dir(session.id).appendingPathComponent("photos"), withIntermediateDirectories: true)
            try? shot.jpeg.write(to: store.photoURL(session.id, id))
            let t = nowMs()
            session.photos.append(PhotoRecord(id: id, tMs: t, placeId: pid, lat: fix.coordinate.latitude, lon: fix.coordinate.longitude,
                                              accuracyM: fix.horizontalAccuracy, fixMs: Int64(fix.timestamp.timeIntervalSince1970 * 1000),
                                              altitudeM: fix.verticalAccuracy > 0 ? fix.altitude : nil, cameraBearing: bearing,
                                              distanceM: shot.distanceM, widthPx: shot.widthPx, heightPx: shot.heightPx, hFovDeg: shot.hFovDeg,
                                              orientation: shot.orientation, exposureS: shot.exposureS, iso: shot.iso, roadName: nil))
            store.save(session)
            note = nil
            withAnimation(.easeOut(duration: 0.12)) { flash = true }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.12) { flash = false }
            nameStreet(photoId: id, at: fix)
        }
    }

    private func newPlace(at fix: CLLocation) -> String {
        let id = uid("q_")
        session.places.append(PlaceRec(id: id, createdMs: nowMs(), lat: fix.coordinate.latitude, lon: fix.coordinate.longitude,
                                       accuracyM: fix.horizontalAccuracy))
        placeId = id
        return id
    }

    private func donePlace() {
        placeId = nil
        note = String(format: NSLocalizedString("Place %d saved", comment: ""), session.places.count)
    }

    /** The street of a photo, from the system's reverse geocoding (needs network; skipped when offline). */
    private func nameStreet(photoId: String, at fix: CLLocation) {
        if session.photos.filter({ $0.roadName != nil }).count > 20 { return }   // enough to name the session
        geocoder.reverseGeocodeLocation(fix) { marks, _ in
            guard let street = marks?.first?.thoroughfare, let i = session.photos.firstIndex(where: { $0.id == photoId }) else { return }
            session.photos[i].roadName = street
            store.save(session)
        }
    }

    private func end() {
        session.endedMs = nowMs()
        session.name = store.name(for: session, fallback: NSLocalizedString("No street name", comment: ""))
        store.save(session)
        onClose()
    }

    private func discard() {
        store.delete(session.id)
        onClose()
    }
}

struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    func makeUIView(context: Context) -> PreviewView {
        let v = PreviewView()
        v.layer.session = session
        v.layer.videoGravity = .resizeAspectFill
        return v
    }
    func updateUIView(_ v: PreviewView, context: Context) {}

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        override var layer: AVCaptureVideoPreviewLayer { super.layer as! AVCaptureVideoPreviewLayer }
    }
}
