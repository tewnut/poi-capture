import AVFoundation
import CoreLocation
import ImageIO
import Shared
import SwiftUI

// Quét Phố (POI Capture) for iPhone: street rides ("Quét đường": photos taken by themselves while riding, for shops,
// house numbers or traffic signs) and Place capture (stop at each shop, photograph it). Same rules and export as the
// Android app: a GPS fix at most 5 s old to start, sessions named "{street} {yyyy-MM-dd HHhmm}", sent as a
// poi-capture/3 zip by the share sheet or uploaded to the team's server.

@main
struct PoiCaptureApp: App {
    @StateObject private var store = SessionStore()
    @StateObject private var profile = Profile()
    @StateObject private var location = LocationService()
    @StateObject private var uploader = Uploader()

    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(store).environmentObject(profile).environmentObject(location).environmentObject(uploader)
                .onAppear { uploader.store = store; uploader.kick() }
        }
    }
}

struct RootView: View {
    @EnvironmentObject var profile: Profile
    @EnvironmentObject var location: LocationService
    @EnvironmentObject var uploader: Uploader
    @State private var cameraOk = AVCaptureDevice.authorizationStatus(for: .video) == .authorized

    var body: some View {
        Group {
            if !profile.emailOk { OnboardingView() }
            else if !location.allowed || !location.precise || !cameraOk { AccessGateView(cameraOk: $cameraOk) }
            else { HomeView() }
        }
        .onAppear { if location.allowed { location.start() } }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in
            cameraOk = AVCaptureDevice.authorizationStatus(for: .video) == .authorized
            uploader.kick()
        }
    }
}

// ---------------------------------------------------------------- onboarding (email, then the access gate)

struct OnboardingView: View {
    @EnvironmentObject var profile: Profile
    @State private var email = ""
    @State private var name = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Record shops and places along the street and put them on the map.").foregroundStyle(.secondary)
                }
                Section(header: Text("Your email"), footer: Text("Goes with each session you send, to credit you and group your sessions. No verification, the app sends no mail.")) {
                    TextField("Email", text: $email).keyboardType(.emailAddress).textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField("Display name (optional)", text: $name)
                }
                Button("Continue") { profile.email = email.trimmingCharacters(in: .whitespaces); profile.name = name }
                    .disabled(!Onboarding.shared.isEmail(s: email))
            }
            .navigationTitle("Quét Phố")
        }
    }
}

/** GPS is a must: precise location, the camera, then the app opens. */
struct AccessGateView: View {
    @EnvironmentObject var location: LocationService
    @Binding var cameraOk: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("GPS is needed").font(.largeTitle.bold())
            Text("Every photo needs a precise GPS position, so the app opens once all of these are on.").foregroundStyle(.secondary)
            Label { Text("Precise location") } icon: { Image(systemName: location.allowed && location.precise ? "checkmark.circle.fill" : "location.circle") }
            Label { Text("Camera") } icon: { Image(systemName: cameraOk ? "checkmark.circle.fill" : "camera.circle") }
            Spacer()
            Button {
                if location.authorization == .notDetermined { location.request() }
                else if !cameraOk && AVCaptureDevice.authorizationStatus(for: .video) == .notDetermined {
                    AVCaptureDevice.requestAccess(for: .video) { ok in DispatchQueue.main.async { cameraOk = ok } }
                } else if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) }
            } label: {
                Text(location.authorization == .notDetermined || AVCaptureDevice.authorizationStatus(for: .video) == .notDetermined ? "Allow" : "Open app settings")
                    .bold().frame(maxWidth: .infinity).padding()
            }
            .buttonStyle(.borderedProminent)
        }
        .padding(24)
    }
}

// ---------------------------------------------------------------- home: the rides

/** What covers the list: the new-ride setup, then the capture screen of the kind picked. */
enum HomeScreen: Identifiable {
    case setup
    case place(CaptureSession)
    case ride(CaptureSession)

    var id: String {
        switch self {
        case .setup: return "setup"
        case .place(let s): return "place-" + s.id
        case .ride(let s): return "ride-" + s.id
        }
    }
}

struct HomeView: View {
    @EnvironmentObject var store: SessionStore
    @EnvironmentObject var uploader: Uploader
    @State private var screen: HomeScreen?
    @State private var sharing: URL?
    @State private var sharingId: String?
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Group {
                if store.sessions.isEmpty {
                    VStack(spacing: 16) {
                        Image(systemName: "camera.viewfinder").font(.system(size: 56)).foregroundStyle(.green)
                        Text("No rides yet").font(.title2.bold())
                        Text("Ride along a street or stop at a few shops. Your recordings show up here, ready to send.")
                            .multilineTextAlignment(.center).foregroundStyle(.secondary)
                    }.padding(32)
                } else {
                    List {
                        ForEach(store.sessions) { s in
                            SessionRow(s: s) { send(s) }
                        }
                        .onDelete { idx in
                            idx.map { store.sessions[$0].id }.forEach { id in store.delete(id); uploader.forget(id) }
                        }
                    }
                }
            }
            .navigationTitle("Rides")
            .toolbar { ToolbarItem(placement: .navigationBarTrailing) { NavigationLink { SettingsView() } label: { Image(systemName: "gearshape.fill") } } }
            .safeAreaInset(edge: .bottom) {
                Button { screen = .setup } label: {
                    Label("New Capture", systemImage: "plus").font(.title3.bold()).frame(maxWidth: .infinity).padding(.vertical, 8)
                }
                .buttonStyle(.borderedProminent).tint(.green).padding()
            }
            .fullScreenCover(item: $screen) { sc in
                switch sc {
                case .setup:
                    NewRideView(onStart: { s in screen = s.isSweep ? .ride(s) : .place(s) }, onCancel: { screen = nil })
                case .place(let s):
                    CaptureView(session: s) { screen = nil; if store.load(s.id) != nil { uploader.rideEnded(s.id) } }
                case .ride(let s):
                    SweepCaptureView(session: s, store: store) { screen = nil }
                }
            }
            .sheet(isPresented: Binding(get: { sharing != nil }, set: { if !$0 { sharing = nil } })) {
                if let u = sharing { ShareSheet(items: [u]) { done in if done, let id = sharingId { store.markExported(id) } } }
            }
            .alert(error ?? "", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) { Button("OK") {} }
        }
    }

    private func send(_ s: CaptureSession) {
        do { sharing = try store.exportZip(s).url; sharingId = s.id } catch { self.error = error.localizedDescription }
    }
}

/** One ride in the list: its name, what it holds, whether it was sent or uploaded, and Send / Upload. */
private struct SessionRow: View {
    let s: CaptureSession
    let send: () -> Void
    @EnvironmentObject var store: SessionStore
    @EnvironmentObject var uploader: Uploader

    var body: some View {
        let r = uploader.record(s.id)
        VStack(alignment: .leading, spacing: 6) {
            Text(s.name ?? store.name(for: s, fallback: NSLocalizedString("No street name", comment: ""))).font(.headline)
            if s.isSweep {
                Text(String(format: NSLocalizedString("%@ · %d photos · %.1f km", comment: ""), targetLabel, s.photoCount ?? 0,
                            (s.distanceM ?? 0) / 1000))
                    .font(.subheadline).foregroundStyle(.secondary)
            } else {
                Text("\(s.places.count) places · \(s.photos.count) photos").font(.subheadline).foregroundStyle(.secondary)
            }
            HStack {
                Text(statusText(r)).font(.caption.bold()).foregroundStyle(statusColor(r)).lineLimit(2)
                Spacer()
                if uploader.configured && r.status != "done" && r.status != "uploading" && s.shotCount > 0 {
                    Button("Upload") { uploader.enqueue(s.id, manual: true) }.buttonStyle(.bordered)
                }
                Button("Send") { send() }.buttonStyle(.borderedProminent).disabled(s.shotCount == 0)
            }
        }
        .padding(.vertical, 4)
    }

    private var targetLabel: String {
        switch s.target {
        case "addresses": return NSLocalizedString("Addresses", comment: "")
        case "signs": return NSLocalizedString("Signs", comment: "")
        default: return NSLocalizedString("Places", comment: "")
        }
    }

    private func statusText(_ r: Uploader.Record) -> String {
        switch r.status {
        case "done":
            let f = DateFormatter()
            f.dateStyle = .short
            f.timeStyle = .short
            let when = Date(timeIntervalSince1970: Double(r.uploadedMs ?? nowMs()) / 1000)
            return String(format: NSLocalizedString("Uploaded %@", comment: ""), f.string(from: when))
        case "uploading":
            return String(format: NSLocalizedString("Uploading %d%%", comment: ""), r.size > 0 ? Int(r.sent * 100 / r.size) : 0)
        case "queued":
            if let e = r.error { return String(format: NSLocalizedString("Upload interrupted, will retry: %@", comment: ""), e) }
            if !r.manual && uploader.wifiOnly && !uploader.onWifi { return NSLocalizedString("Waiting for Wi-Fi to upload", comment: "") }
            return NSLocalizedString("Waiting to upload", comment: "")
        case "failed":
            return String(format: NSLocalizedString("Upload failed: %@", comment: ""), r.error ?? "")
        default:
            if s.isSweep && s.endedMs == nil { return NSLocalizedString("Not finished", comment: "") }
            return s.exportedMs == nil ? NSLocalizedString("Not sent", comment: "") : NSLocalizedString("Sent", comment: "")
        }
    }

    private func statusColor(_ r: Uploader.Record) -> Color {
        switch r.status {
        case "done": return .green
        case "failed": return .red
        case "uploading", "queued": return .blue
        default: return s.exportedMs == nil ? .orange : .green
        }
    }
}

struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    let completed: (Bool) -> Void
    func makeUIViewController(context: Context) -> UIActivityViewController {
        let c = UIActivityViewController(activityItems: items, applicationActivities: nil)
        c.completionWithItemsHandler = { _, done, _, _ in completed(done) }
        return c
    }
    func updateUIViewController(_ c: UIActivityViewController, context: Context) {}
}

struct SettingsView: View {
    @EnvironmentObject var profile: Profile
    @EnvironmentObject var uploader: Uploader
    var body: some View {
        Form {
            Section(header: Text("You")) {
                TextField("Email", text: $profile.email).keyboardType(.emailAddress).textInputAutocapitalization(.never).autocorrectionDisabled()
                TextField("Display name (optional)", text: $profile.name)
            }
            Section(header: Text("Upload server")) {
                NavigationLink {
                    UploadSettingsView()
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Upload server")
                        Text(uploader.configured ? (uploader.endpoint ?? "") : NSLocalizedString("Not set up", comment: ""))
                            .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
            }
            Section(header: Text("Language")) {
                Button("Change in iPhone Settings") { if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) } }
            }
            Section(header: Text("This iPhone")) {
                LabeledContent("LiDAR distance", value: CameraService.hasLiDAR ? NSLocalizedString("Yes", comment: "") : NSLocalizedString("No", comment: ""))
                LabeledContent("Model", value: DeviceInfo.model)
            }
        }
        .navigationTitle("Settings")
    }
}
