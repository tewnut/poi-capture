import AVFoundation
import Shared
import SwiftUI

// ---------------------------------------------------------------- Ghi mới: what to record

/**
 * "Ghi mới" (as the Android setup): Chế độ (Quét đường | Chụp địa điểm); for a street ride Đối tượng (Địa điểm |
 * Địa chỉ | Biển báo) and Điện thoại (Kẹp cố định | Cầm tay), the plan (spacing, speed), GPS, and Bắt đầu once the
 * GPS has a fresh fix. The last choices are kept.
 */
struct NewRideView: View {
    @EnvironmentObject var store: SessionStore
    @EnvironmentObject var profile: Profile
    @EnvironmentObject var location: LocationService
    @AppStorage("ride.kind") private var kind = "sweep"
    @AppStorage("ride.target") private var target = "shops"
    @AppStorage("ride.holding") private var holding = "mount"
    let onStart: (CaptureSession) -> Void
    let onCancel: () -> Void

    init(onStart: @escaping (CaptureSession) -> Void, onCancel: @escaping () -> Void) {
        self.onStart = onStart
        self.onCancel = onCancel
    }

    var body: some View {
        let fix = location.freshFix
        NavigationStack {
            Form {
                Section(header: Text("Mode")) {
                    Picker("Mode", selection: $kind) {
                        Text("Street ride").tag("sweep")
                        Text("Place capture").tag("poi")
                    }
                    .pickerStyle(.segmented)
                    Text(kind == "sweep" ? "Ride slowly: photos are taken by themselves." : "Stop at each shop and photograph it.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                if kind == "sweep" {
                    Section(header: Text("Target")) {
                        Picker("Target", selection: $target) {
                            Text("Places").tag("shops")
                            Text("Addresses").tag("addresses")
                            Text("Signs").tag("signs")
                        }
                        .pickerStyle(.segmented)
                        Text(targetHint).font(.footnote).foregroundStyle(.secondary)
                    }
                    Section(header: Text("Phone")) {
                        Picker("Phone", selection: $holding) {
                            Text("Fixed mount").tag("mount")
                            Text("Hand-held").tag("hand")
                        }
                        .pickerStyle(.segmented)
                        Text(holding == "hand" ? "Hold it sideways, aimed at the shops." : "Mount it firmly, camera toward the shops.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    Section {
                        Label(planText, systemImage: "speedometer")
                        Label("Keep the screen on; plug in a charger", systemImage: "bolt.batteryblock")
                        Label("Photos pause if you leave the app", systemImage: "pause.circle")
                    }
                    .font(.subheadline)
                }
                Section(header: Text("GPS")) {
                    if let f = fix {
                        Label(String(format: NSLocalizedString("GPS good · ±%d m", comment: ""), Int(f.horizontalAccuracy.rounded())),
                              systemImage: "location.fill").foregroundStyle(.green)
                    } else {
                        Label("Waiting for GPS…", systemImage: "location.slash").foregroundStyle(.orange)
                        Text("Go out under open sky.").font(.footnote).foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("New Capture")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { onCancel() } } }
            .safeAreaInset(edge: .bottom) {
                Button { start() } label: {
                    Text(fix == nil ? "Waiting for GPS…" : "Start").font(.title3.bold()).frame(maxWidth: .infinity).padding(.vertical, 8)
                }
                .buttonStyle(.borderedProminent).tint(.green).padding().disabled(fix == nil)
            }
        }
        .onAppear { location.start() }
    }

    private var targetHint: String {
        switch target {
        case "addresses": return NSLocalizedString("Shops and every house number: twice the photos, ride slower.", comment: "")
        case "signs": return NSLocalizedString("Traffic signs: point the camera AHEAD along the road. A photo every 8 m.", comment: "")
        default: return NSLocalizedString("Shop signs: the camera looks sideways at the shops.", comment: "")
        }
    }

    private var planText: String {
        let r = SweepRide(target: target, hand: holding == "hand", maxAccuracyM: 30, offRoadWarnM: 60)
        r.photoLongEdge = Int32(target == "addresses" ? 4000 : 2560)
        return String(format: NSLocalizedString("≤ %@ m per photo · ≤ %d km/h", comment: ""),
                      String(format: "%.0f", r.photoEveryM), Int(r.maxSpeedKmh))
    }

    private func start() {
        let now = nowMs()
        if kind == "poi" {
            let s = CaptureSession(id: uid("s_"), createdMs: now, startedMs: now, endedMs: nil,
                                   contributor: profile.displayName, email: profile.email, name: nil)
            store.save(s)
            onStart(s)
            return
        }
        let id = uid("s_")
        let longEdge = target == "addresses" ? 4000 : 2560
        let doc = SweepExport.shared.sessionDoc(id: id, createdMs: now, contributor: profile.displayName, holding: holding,
                                                target: target, photoLongEdge: Int32(longEdge), deviceJson: DeviceInfo.json())
        store.saveSweepDoc(id, doc)
        let s = CaptureSession(id: id, createdMs: now, startedMs: nil, endedMs: nil, contributor: profile.displayName, email: "",
                               name: nil, kind: "sweep", target: target, holding: holding, photoCount: 0)
        store.save(s)
        onStart(s)
    }
}

// ---------------------------------------------------------------- Quét đường: the capture screen

struct SweepCaptureView: View {
    @EnvironmentObject var location: LocationService
    @EnvironmentObject var uploader: Uploader
    @StateObject private var rec: RideRecorder
    let onClose: () -> Void
    @State private var confirmEnd = false
    @State private var confirmDiscard = false

    init(session: CaptureSession, store: SessionStore, onClose: @escaping () -> Void) {
        _rec = StateObject(wrappedValue: RideRecorder(sessionId: session.id, target: session.target ?? "shops",
                                                      hand: session.holding == "hand", store: store))
        self.onClose = onClose
    }

    var body: some View {
        ZStack {
            CameraPreview(session: rec.camera.session).ignoresSafeArea()
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(gpsText).font(.headline).foregroundStyle(rec.gpsFresh ? (rec.gpsGood ? .green : .yellow) : .red)
                        Text(String(format: NSLocalizedString("%d photos", comment: ""), rec.photos)).font(.subheadline.bold())
                        if let r = rec.roadName { Text(r).font(.subheadline).lineLimit(1) }
                        Text(rec.running ? "● Recording" : "Ⅱ Paused").font(.caption.bold()).foregroundStyle(rec.running ? .red : .white)
                    }
                    .foregroundStyle(.white)
                    .padding(10).background(.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 12))
                    Spacer()
                    VStack(alignment: .trailing, spacing: 2) {
                        Text(rec.speedKmh.map { "\($0)" } ?? "–").font(.system(size: 40, weight: .bold, design: .rounded))
                        Text(String(format: NSLocalizedString("km/h · max %d", comment: ""), rec.maxSpeedKmh)).font(.caption)
                    }
                    .foregroundStyle((rec.speedKmh ?? 0) > rec.maxSpeedKmh ? .orange : .white)
                    .padding(10).background(.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 12))
                }
                if rec.pausedInBackground {
                    Button { rec.dismissBackgroundNote() } label: {
                        Text("Photos paused while the app was in the background. The GPS track went on. Keep the app open.")
                            .font(.subheadline.bold()).multilineTextAlignment(.leading)
                            .padding(10).frame(maxWidth: .infinity, alignment: .leading)
                            .background(.red, in: RoundedRectangle(cornerRadius: 10)).foregroundStyle(.white)
                    }
                }
                ForEach(rec.warnings, id: \.self) { w in
                    Text(Self.warningText(w, target: rec.target)).font(.subheadline.bold()).padding(8)
                        .background(.orange, in: RoundedRectangle(cornerRadius: 10)).foregroundStyle(.white)
                }
                if let e = rec.lastError {
                    Text(e).font(.footnote).padding(8).background(.red.opacity(0.8), in: RoundedRectangle(cornerRadius: 10)).foregroundStyle(.white)
                }
                Spacer()
                if !rec.started {
                    Text("Waiting for GPS… The ride starts by itself.").font(.headline).padding(14)
                        .background(.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 12)).foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                }
                CameraNote(camera: rec.camera)
                HStack(spacing: 16) {
                    Button { if rec.running { rec.pause() } else { rec.start() } } label: {
                        Text(rec.running ? "Pause" : "Resume").font(.title3.bold()).frame(maxWidth: .infinity).padding(.vertical, 6)
                    }
                    .buttonStyle(.bordered).tint(.white).disabled(!rec.started)
                    Button { confirmEnd = true } label: {
                        Text("End").font(.title3.bold()).frame(maxWidth: .infinity).padding(.vertical, 6)
                    }
                    .buttonStyle(.borderedProminent).tint(.red)
                }
            }
            .padding(16)
        }
        .onAppear {
            location.suspendMotion()
            uploader.recording = true
            rec.open()
            rec.start()
        }
        .onDisappear {
            location.resumeMotion()
            uploader.recording = false
        }
        .onReceive(rec.$gpsFresh) { fresh in if fresh && !rec.started { rec.start() } }
        .sheet(isPresented: $confirmEnd) { endSheet }
        .alert("Discard this ride?", isPresented: $confirmDiscard) {
            Button("Discard ride", role: .destructive) { rec.finish(discard: true); onClose() }
            Button("Keep recording", role: .cancel) {}
        } message: {
            Text("It is deleted from the phone and never sent. This cannot be undone.")
        }
    }

    private var gpsText: String {
        guard let a = rec.accuracyM else { return NSLocalizedString("● Waiting for GPS…", comment: "") }
        return String(format: NSLocalizedString(rec.gpsGood ? "● GPS ±%d m" : "● Weak GPS ±%d m", comment: ""), a)
    }

    /** The end of the ride: what was recorded, then save (and upload) or keep going. */
    private var endSheet: some View {
        VStack(spacing: 18) {
            Text("End this ride?").font(.title2.bold())
            HStack(spacing: 24) {
                stat("\(rec.photos)", NSLocalizedString("photos", comment: ""))
                stat(String(format: "%.1f km", rec.distanceM / 1000), NSLocalizedString("ridden", comment: ""))
                stat("\(minutes)", NSLocalizedString("minutes", comment: ""))
            }
            if rec.photos == 0 {
                Text("No photo yet: check the GPS and the camera, or discard the ride.").font(.footnote).foregroundStyle(.orange)
                    .multilineTextAlignment(.center)
            }
            Text(uploader.configured && uploader.auto ? "Saved on the phone and sent to the server." : "Saved on the phone; see it under Rides.")
                .font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
            Button {
                confirmEnd = false
                let id = rec.sessionId
                if rec.finish(discard: false) { uploader.recording = false; uploader.rideEnded(id) }
                onClose()
            } label: { Text("End and save").bold().frame(maxWidth: .infinity).padding(.vertical, 6) }
                .buttonStyle(.borderedProminent)
            Button { confirmEnd = false } label: { Text("Keep recording").frame(maxWidth: .infinity).padding(.vertical, 6) }
                .buttonStyle(.bordered)
            Button(role: .destructive) { confirmEnd = false; confirmDiscard = true } label: { Text("Discard ride") }
        }
        .padding(24)
        .presentationDetents([.medium])
    }

    private var minutes: Int {
        guard let s = rec.store.load(rec.sessionId)?.startedMs else { return 0 }
        return Int((nowMs() - s) / 60_000)
    }

    private func stat(_ value: String, _ label: String) -> some View {
        VStack { Text(value).font(.title.bold()); Text(label).font(.caption).foregroundStyle(.secondary) }
    }

    /** The words for a warning code (shared SweepRide.warnings + the recorder's own). */
    static func warningText(_ code: String, target: String) -> String {
        switch code {
        case "fast": return NSLocalizedString("Too fast: slow down", comment: "")
        case "weakGps": return NSLocalizedString("Weak GPS", comment: "")
        case "portrait": return NSLocalizedString("Turn the phone sideways", comment: "")
        case "mountFront": return NSLocalizedString("Camera looks ahead: turn it to the shops", comment: "")
        case "mountBack": return NSLocalizedString("Camera looks back: turn it to the shops", comment: "")
        case "mountSide": return NSLocalizedString("Camera looks sideways: point it ahead for signs", comment: "")
        case "blurry": return NSLocalizedString("Blurry photos: slow down", comment: "")
        case "missed": return NSLocalizedString("Camera can't keep up: slow down", comment: "")
        case "dark": return NSLocalizedString("Too dark: signs may not show", comment: "")
        case "gpsLost": return NSLocalizedString("GPS lost: photos use the last position", comment: "")
        case "aimLow": return NSLocalizedString("Camera points at the road: raise it", comment: "")
        case "swinging": return NSLocalizedString("Hold the phone still a moment…", comment: "")
        default: return code
        }
    }
}

/** "Opening the camera…" until the ride's camera is open. */
private struct CameraNote: View {
    @ObservedObject var camera: SweepCamera

    var body: some View {
        if !camera.ready {
            Text(camera.failed == nil ? "Opening the camera…" : "Camera can't open").bold().padding(14)
                .background(.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 12)).foregroundStyle(.white)
                .frame(maxWidth: .infinity)
        }
    }
}

// ---------------------------------------------------------------- upload server settings

struct UploadSettingsView: View {
    @EnvironmentObject var uploader: Uploader
    @State private var scanning = false
    @State private var message: String?
    @State private var testing = false
    @State private var showToken = false

    var body: some View {
        Form {
            Section(footer: Text("Ask your project lead for a setup QR code.")) {
                Button { scanning = true } label: { Label("Scan the setup QR code", systemImage: "qrcode.viewfinder") }
                if !uploader.tokenName.isEmpty {
                    Text(String(format: NSLocalizedString("Set up as “%@”", comment: ""), uploader.tokenName)).foregroundStyle(.green)
                }
                if let m = message { Text(m).font(.footnote) }
            }
            Section(header: Text("Server address")) {
                TextField("https://…", text: $uploader.url).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                if uploader.endpoint == nil {
                    Text("Enter an address starting with http:// or https://").font(.footnote).foregroundStyle(.red)
                } else if uploader.url.lowercased().hasPrefix("http://") {
                    Text("http:// sends photos and the token unencrypted: use https:// outside a test network.").font(.footnote).foregroundStyle(.orange)
                }
            }
            Section(header: Text("Access token"), footer: Text("Kept encrypted on this phone, never in an export.")) {
                HStack {
                    if showToken {
                        TextField("pct_…", text: $uploader.token).textInputAutocapitalization(.never).autocorrectionDisabled()
                    } else {
                        SecureField("pct_…", text: $uploader.token)
                    }
                    Button(showToken ? "Hide" : "Show") { showToken.toggle() }.buttonStyle(.borderless)
                }
                if !uploader.configured && uploader.endpoint != nil {
                    Text("A token is needed to upload.").font(.footnote).foregroundStyle(.orange)
                }
            }
            Section(footer: Text("No upload while recording; an interrupted upload goes on by itself.")) {
                Toggle("Upload each ride when it ends", isOn: $uploader.auto)
                Toggle("Only on Wi-Fi", isOn: $uploader.wifiOnly)
                Toggle("Delete from the phone once on the server", isOn: $uploader.removeAfter)
            }
            Section {
                Button(testing ? "Testing…" : "Test the connection") {
                    testing = true
                    Task {
                        let m = await uploader.test()
                        await MainActor.run { message = m; testing = false }
                    }
                }
                .disabled(testing || uploader.endpoint == nil)
            }
        }
        .navigationTitle("Upload server")
        .sheet(isPresented: $scanning) {
            QRScannerView { text in
                scanning = false
                switch uploader.apply(setup: text) {
                case nil: message = uploader.tokenName.isEmpty ? NSLocalizedString("Set up from the QR code", comment: "")
                    : String(format: NSLocalizedString("Set up as “%@”", comment: ""), uploader.tokenName)
                case .some("noToken"): message = NSLocalizedString("This QR code has no access token. Ask for a new setup QR code.", comment: "")
                default: message = NSLocalizedString("This is not a setup QR code: no http:// or https:// server address.", comment: "")
                }
            }
            .ignoresSafeArea()
        }
    }
}

/** The back camera reading one QR code (AVFoundation metadata output). */
struct QRScannerView: UIViewControllerRepresentable {
    let onCode: (String) -> Void

    func makeUIViewController(context: Context) -> Controller {
        let c = Controller()
        c.onCode = onCode
        return c
    }

    func updateUIViewController(_ c: Controller, context: Context) {}

    final class Controller: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
        var onCode: ((String) -> Void)?
        private let session = AVCaptureSession()
        private var preview: AVCaptureVideoPreviewLayer?
        private var done = false

        override func viewDidLoad() {
            super.viewDidLoad()
            view.backgroundColor = .black
            guard let dev = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: dev),
                  session.canAddInput(input) else { return }
            session.addInput(input)
            let out = AVCaptureMetadataOutput()
            guard session.canAddOutput(out) else { return }
            session.addOutput(out)
            out.setMetadataObjectsDelegate(self, queue: .main)
            if out.availableMetadataObjectTypes.contains(.qr) { out.metadataObjectTypes = [.qr] }
            let p = AVCaptureVideoPreviewLayer(session: session)
            p.videoGravity = .resizeAspectFill
            view.layer.addSublayer(p)
            preview = p
            let s = session
            DispatchQueue.global(qos: .userInitiated).async { s.startRunning() }
        }

        override func viewDidLayoutSubviews() {
            super.viewDidLayoutSubviews()
            preview?.frame = view.bounds
        }

        override func viewWillDisappear(_ animated: Bool) {
            super.viewWillDisappear(animated)
            let s = session
            DispatchQueue.global(qos: .userInitiated).async { s.stopRunning() }
        }

        func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
            guard !done, let o = metadataObjects.first as? AVMetadataMachineReadableCodeObject, let text = o.stringValue else { return }
            done = true
            onCode?(text)
        }
    }
}
