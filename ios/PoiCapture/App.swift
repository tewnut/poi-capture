import AVFoundation
import CoreLocation
import ImageIO
import SwiftUI

// POI Capture for iPhone: Place capture (stop at each shop, photograph it). Same rules and export as the Android
// app: GPS-grade fix at most 5 s old for every photo, sessions named "{street} [yymmdd]", sent as a poi-capture/3 zip.

@main
struct PoiCaptureApp: App {
    @StateObject private var store = SessionStore()
    @StateObject private var profile = Profile()
    @StateObject private var location = LocationService()

    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(store).environmentObject(profile).environmentObject(location)
        }
    }
}

struct RootView: View {
    @EnvironmentObject var profile: Profile
    @EnvironmentObject var location: LocationService
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
            .navigationTitle("POI Capture")
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

// ---------------------------------------------------------------- home: the sessions

struct HomeView: View {
    @EnvironmentObject var store: SessionStore
    @EnvironmentObject var profile: Profile
    @State private var capturing: CaptureSession?
    @State private var sharing: URL?
    @State private var sharingId: String?
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Group {
                if store.sessions.isEmpty {
                    VStack(spacing: 16) {
                        Image(systemName: "camera.viewfinder").font(.system(size: 56)).foregroundStyle(.green)
                        Text("No sessions yet").font(.title2.bold())
                        Text("Stop at a few shops and photograph them. Your recordings show up here, ready to send.")
                            .multilineTextAlignment(.center).foregroundStyle(.secondary)
                    }.padding(32)
                } else {
                    List {
                        ForEach(store.sessions) { s in
                            VStack(alignment: .leading, spacing: 6) {
                                Text(s.name ?? store.name(for: s, fallback: NSLocalizedString("No street name", comment: ""))).font(.headline)
                                Text("\(s.places.count) places · \(s.photos.count) photos").font(.subheadline).foregroundStyle(.secondary)
                                HStack {
                                    Text(s.exportedMs == nil ? "Not sent" : "Sent").font(.caption.bold())
                                        .foregroundStyle(s.exportedMs == nil ? .orange : .green)
                                    Spacer()
                                    Button("Send") { send(s) }.buttonStyle(.borderedProminent).disabled(s.photos.isEmpty)
                                }
                            }
                            .padding(.vertical, 4)
                        }
                        .onDelete { idx in idx.map { store.sessions[$0].id }.forEach(store.delete) }
                    }
                }
            }
            .navigationTitle("POI Captures")
            .toolbar { ToolbarItem(placement: .navigationBarTrailing) { NavigationLink { SettingsView() } label: { Image(systemName: "gearshape.fill") } } }
            .safeAreaInset(edge: .bottom) {
                Button { capturing = newSession() } label: {
                    Label("New Capture", systemImage: "plus").font(.title3.bold()).frame(maxWidth: .infinity).padding(.vertical, 8)
                }
                .buttonStyle(.borderedProminent).tint(.green).padding()
            }
            .fullScreenCover(item: $capturing) { s in CaptureView(session: s) { capturing = nil } }
            .sheet(isPresented: Binding(get: { sharing != nil }, set: { if !$0 { sharing = nil } })) {
                if let u = sharing { ShareSheet(items: [u]) { done in if done, let id = sharingId { store.markExported(id) } } }
            }
            .alert(error ?? "", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) { Button("OK") {} }
        }
    }

    private func newSession() -> CaptureSession {
        let s = CaptureSession(id: uid("s_"), createdMs: nowMs(), startedMs: nowMs(), endedMs: nil,
                               contributor: profile.displayName, email: profile.email, name: nil)
        store.save(s)
        return s
    }

    private func send(_ s: CaptureSession) {
        do { sharing = try store.exportZip(s); sharingId = s.id } catch { self.error = error.localizedDescription }
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
    var body: some View {
        Form {
            Section(header: Text("You")) {
                TextField("Email", text: $profile.email).keyboardType(.emailAddress).textInputAutocapitalization(.never).autocorrectionDisabled()
                TextField("Display name (optional)", text: $profile.name)
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
