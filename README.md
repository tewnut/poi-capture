<img src="docs/logo.svg" width="96" alt="POI Capture logo">

# POI Capture (Android)

An Android app for field contributors who put shops and other places on a map. Ride along a street with the phone
mounted (the app photographs the row of buildings by itself), or stop at each place and photograph it. Every photo
carries GPS, the road, the camera direction and an estimated point on the shop front; raw GNSS and the IMU are logged so
positions can be refined later. Sessions stay on the phone until sent (as a zip) to a processing agent, which corrects
positions, reads the signboards and contributes the places to the target map. The zip format (`poi-capture/3`) and the
agent pipeline are in [docs/handoff.md](docs/handoff.md).

The UI is in English, with a Vietnamese translation used when the phone (or the app's language setting) is Vietnamese.

## Download

**[Latest APK](https://github.com/tewnut/poi-capture/releases/latest)** (`poi-capture-vX.Y.Z.apk`, Android 11+). Open it
on the phone and allow installing from that source. New versions install over the old one and keep the sessions.

## iPhone app (Place capture)

`ios/` holds the iPhone app: Place capture (stop at each shop, photograph it), with the same rules and export as the
Android app (GPS-grade fix at most 5 s old for every photo, "GPS lost" hold, sessions named "{street} [yymmdd]",
sent as a `poi-capture/3` zip). On iPhones with LiDAR (Pro models) each photo also records the measured distance to
the shop front (up to about 5 m, `facadeSource: "lidar"`). Street sweep stays Android only (see below).

The logic both apps share (capture rules, geometry, photo spacing, blur checks, session naming, the Place capture
manifest) lives in the Kotlin Multiplatform module `shared/`; the Android app uses it as a library, the iPhone app as
`Shared.framework`. Platform code (camera, GPS, sensors, storage, screens) is native on each side.

Build the iPhone app on a Mac with Xcode 16+ and a JDK 17+:

```
brew install xcodegen
cd ios && xcodegen generate && open PoiCapture.xcodeproj
```

A build step runs `./gradlew :shared:embedAndSignAppleFrameworkForXcode`. Set your team for signing to run it on a
phone. CI ([.github/workflows/ci.yml](.github/workflows/ci.yml)) builds both apps on every push: the shared tests on
the JVM and on the iOS simulator, the Android tests and APK, and the iPhone app for the simulator.

## Why street sweep is Android only

Placing a shop needs the phone's own measurements, unfiltered, and a capture that keeps going with the screen off.
Android gives an app both; iOS does not:

- **Raw GNSS.** Android hands apps the raw satellite measurements (pseudoranges, Doppler and, on many phones, carrier
  phase), which the agent can reprocess into a much better track. iOS only gives the position it has already
  computed.
- **GPS only, on demand.** Android can ask for the satellite fix alone, so a Wi-Fi or cell position is never taken
  for a photo position (see the 5-second rule). iOS mixes the sources and does not say which one it used.
- **Recording with the screen off.** A mounted phone records in a foreground service (camera + location) with the
  screen off or another app on top. iOS does not let an app use the camera in the background.
- **Hands-free controls.** The volume keys and Bluetooth handlebar remotes take photos; iOS reserves the volume keys.
- **Distribution and cost.** The APK installs directly, with no app store review; contributors' phones are mostly
  Android, at every price.

iOS would bring one real advantage: the LiDAR on Pro iPhones measures depth, but only up to about 5 m and not reliably
from a moving bike, so it would not replace the triangulation the agent does from several photos.

## Suggested hardware

The app refuses a phone without a GPS receiver, a gyroscope, an accelerometer or a rear camera of at least 1080p.
Beyond that, these make places land on the right building and the agent's work easier (each session records what the
phone has: `session.device.capabilities`, `session.gnss`):

| | Why |
|---|---|
| **Dual-frequency GNSS (L1 + L5)** | far fewer jumps between tall buildings (reflections); check `session.gnss.dualFrequency` after a test ride |
| **Raw GNSS with carrier phase** | lets the agent post-process the track to about 1–2 m; `session.gnss.carrierPhase` |
| **Gyroscope + accelerometer of good quality** | a steady camera direction from speeding up / braking (the compass is disturbed on a motorbike) |
| **Wide main camera (≈ 70–80° horizontal), 12 MP or more** | more shop front per photo, readable signs; avoid phones that switch to a cropped or zoomed lens |
| **Good heat handling, big battery** | full-resolution photos every few metres for an hour heat a phone; a power bank on the bike helps |
| **Rigid handlebar mount with vibration damping** | sharp photos and a stable camera direction; landscape sees more of the street |
| **Bluetooth remote (selfie button) on the handlebar** | shoot (Place capture, manual sweep) without touching the screen |

## How it works

Two kinds of session, chosen on **New**:
- **Street sweep**: ride along a street with the phone mounted **sideways (landscape)**; the app photographs the row of
  buildings automatically and needs no tap. To point out one place, stop and use Place capture.
- **Place capture**: stop at each place, **Shoot** a few photos (front, sign, inside, menu…), then **Next place**
  and go on to the next one. Nothing to type: the phone reads the text on the photos (ML Kit, Latin script including
  Vietnamese) and shows "Read on the sign: name · phone · category", or asks to shoot the sign again when nothing is
  readable; the agent decides the name, address and category. The place keeps the GPS position where it was started;
  **Pin & status…** is there when it is worth putting the pin on the right building or marking the place closed / moved.
  Photos are grouped by place in the export, so the agent does not have to sort them.

0. **Onboarding** (first run, about a minute; every step is a must): **language** (the phone's, English or Tiếng
   Việt) → what the app does → **your email** (format checked, not verified; it credits and groups the contributor's
   sessions) and an optional display name → **permissions** (camera, precise location, notifications and the phone's
   location switched on, each with its reason; Settings when Android stops asking; no "later") → **road data**: the app
   finds the province / city the contributor is in and downloads its road pack, and Continue waits until a pack is on
   the phone → **ready**: how to mount the phone, a summary. Everything can be changed later in
   **Settings**.
   **GPS is a must**: after the onboarding, the app does not open without precise location, the camera and the
   phone's location on (a "GPS is needed" screen until they are).
1. **New** (one screen): live map with the GPS state and what to do when there is no position (grant the permission,
   switch on precise location or the phone's location, how many satellites are seen). Only a satellite fix counts as
   the position: a Wi-Fi / cell fix may centre the map ("waiting for GPS") but never names the road. The road the contributor is on
   (recognised automatically, also offline), the sweep mode (auto photos or manual), then **Start**. Auto photos are timed by
   the camera itself: a small video stream measures how far the street slid across the frame, and a photo is taken
   after ~30–45 % of a frame, at the steadiest moment (sharp frame, little shake), so every sign is on ≥ 2 photos at any
   distance and speed, and nothing is shot while waiting at a light; without usable frames the GPS speed stands in. The speed
   limit follows from the type of road being ridden (expected distance to the shop fronts, on the near
   side) and the camera's field of view; the side (right / left) is measured while
   riding.
2. **Capture**: full-screen rear camera, portrait or landscape as the phone is held (the rest of the app is portrait;
   landscape sees more of the street, so portrait takes photos closer together), kept minimal. A get-ready checklist (GPS, camera, road data,
   mounting) comes first; Start waits for a GPS fix. **Every photo has a GPS fix from the last
   5 seconds**: when the GPS is lost the screen says so, nothing is recorded, and recording carries on by itself when
   the GPS is back. While recording: one line of status text (GPS · road; riding: the camera direction until it
   is measured, red if it looks forward / backward), one warning at a time (too fast, camera cannot keep up, weak GPS,
   phone overheating) and **End**. Riding: speed against the limit, "● Recording", **Pause**, and a hint to turn the phone
   sideways when it is upright. Places: **Shoot** and **Next place** under the thumb: on the right in landscape, in a
   bar at the bottom in portrait. Capture runs in a foreground service (camera + location), keeps
   going with the screen off or another app on top, and resumes after a phone call.
3. **Sessions**: what is left to send; per session date, duration, distance, counts and a badge; **Send** (share sheet:
   the contributor picks where it goes), **Map** (track and a dot per photo; swipe the photo cards; the selected photo shows where it was
   taken, with the GPS accuracy and what the camera saw, and the shop; **Move place** puts the shop pin on the right
   building, **Reset place** undoes it), save the zip or
   delete in the ⋮ menu. Sessions cut off by a crash or a dead battery can still be sent.

Safety: in a street sweep the phone is mounted and the app never needs a tap.

## Data and services

- **Send**: the zip goes to whatever app the contributor picks (messaging, cloud drive, email…), or is saved to a file.
- **Offline road data** (Settings → Offline road data): one pack per province / city (the 34 since the 2025 merger),
  built from OpenStreetMap (Geofabrik) by [tools/roadpack](tools/roadpack/README.md) and published monthly as the GitHub
  release `roadpacks`. Within a pack the road is recognised without network; elsewhere the app asks Overpass and keeps
  the answer on the phone.
- **Photo kinds** (Settings → Advanced settings): off by default, the agent tells a front from a sign by itself. A
  contributor can turn on a list of kinds (shown as buttons while shooting) and edit it.
- **Sharp photos**: in street sweep the shutter is held at 1/500 s or faster (the phone raises ISO instead: grainy
  is readable, smeared is not); when even the highest ISO is not enough, street sweep does not start ("too dark: use
  Place capture"). Every photo gets a blur check (motion smear from speed × exposure, and edge detail against the
  session's other photos); blurry photos are flagged in the data and, while riding, "Photos blurry: slow down".
- **Session names**: "{street} [yymmdd]", from the street most photos were taken on and the day.
- **Privacy**: photos leave the phone unblurred (faces, licence plates); the agent must blur before anything is
  published (`manifest.privacy`, handoff.md step 0).

## Build and run

Needs JDK 17–21 (not 25: Gradle 8.14 does not run on it) and the Android SDK (platform 36).

```
# PowerShell
$env:JAVA_HOME = "<path to a JDK 21>"
.\gradlew.bat :app:testDebugUnitTest      # JVM tests of the core logic
.\gradlew.bat :app:assembleDebug          # app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat :app:installDebug           # onto a connected phone / emulator
```

Or open the folder in Android Studio and set the Gradle JDK to a 17/21 JDK. Requires Android 11+ (minSdk 30).
`app/src/androidTest` has an instrumented test of the on-device sign reading (real ML Kit model).

## Release

Bump `versionCode` / `versionName` in `app/build.gradle.kts`, commit, then push a matching tag:

```
git tag v1.2.3
git push origin main v1.2.3
```

[.github/workflows/release.yml](.github/workflows/release.yml) runs the unit tests, builds the release APK, signs it
with the release key from the repository secrets (`RELEASE_KEYSTORE_B64`, `RELEASE_KEYSTORE_PASSWORD`,
`RELEASE_KEY_ALIAS`), checks the signature and attaches `poi-capture-vX.Y.Z.apk` (+ `.sha256`) to the release. Keep the
keystore backed up outside the repository: an APK signed with another key cannot update the installed app. A local
signed build works the same way with `RELEASE_KEYSTORE_FILE` set to the keystore path.

## Status

Verified: the APK builds, the JVM unit tests pass, and the flows were exercised on an emulator (onboarding, both
capture modes, hardware keys, sign reading with the real ML Kit model, offline road packs). On a real phone (OPPO
Reno6 Z, MediaTek, Android 13): onboarding and the "GPS is needed" gate, GPS-only position and the 5-second rule
(including the hand-over from New capture to the recording), the photo locations map and Move mode, portrait capture,
and the "too dark for street sweep" check indoors at night.

Not yet verified on many real phones: the foreground service with camera + location types (strict on Android 14+),
capture with the screen off, camera recovery after a call, raw GNSS / IMU logging on different chipsets, and the sign
reading on real Vietnamese signboards (diacritics are not always read right: the agent re-reads the photos).

## Settings

Defaults live in `core/Session.kt` (`Settings`) and are stored per device: tile URL (any XYZ tile server), Overpass
URL, GPS accuracy limit, off-road distance, photo size, JPEG quality, mount offset, road pack
catalogue URL. Photo spacing and the speed limit are not settings: the photos follow the slide measured on the camera
frames (`core/FrameShift.kt`, `capture/FrameWatcher.kt`), the speed limit the road type being ridden and the
camera's field of view (`core/CapturePlan.kt`). Photos moved by hand are checked against the roads around (too far,
on the road, across the street: warnings, `core/MoveCheck.kt`).

## Code

| File | What |
|---|---|
| `core/Geo.kt` | distance, bearing, nearest point on a line, side model, camera bearing with mount offset |
| `core/Road.kt` | OSM ways, heading-aware road matching, grid cells for automatic road loading, Overpass queries |
| `core/FixProcessor.kt` | per-fix logic: travel bearing / speed, travel direction, U-turn, warnings, item metadata |
| `core/FrameShift.kt` (shared) | scene slide between camera frames, and `OverlapTrigger`: when the next sweep photo is due |
| `core/Session.kt` | session model, settings, schema id |
| `core/SessionStore.kt` | per-session folders, append-only files (crash-safe) |
| `core/Exporter.kt` | streaming zip: manifest.json, track.geojson, gnss_raw.csv, imu.csv, photos, videos |
| `core/TrackStats.kt` | distance / duration of a ride, main roads of a session |
| `core/CapturePlan.kt` | expected widest photo spacing and speed limit from the road type (expected facade distance) and field of view |
| `core/MountEstimator.kt` | camera direction relative to travel from acceleration / braking (no compass) |
| `core/Poi.kt`, `core/PhotoRoles.kt` | session kinds (sweep / place), place records, optional photo kinds (config) |
| `core/SignText.kt`, `capture/SignOcr.kt` | on-device sign reading: ML Kit OCR, then phone / house number / category / name rules |
| `core/RoadPack.kt`, `capture/RoadPackWork.kt`, `net/RoadSource.kt` | offline road packs: catalogue, province lookup, download, cell lookup |
| `core/Coverage.kt` | thinned tracks / places for the "already recorded" map |
| `core/Onboarding.kt` | email check, display name, which province pack covers a position |
| `capture/CaptureService.kt` | foreground service: CameraX photos, GPS, sensors, session control, session name |
| `capture/ShutterGuard.kt`, `core/BlurCheck.kt` | street sweep: shutter held at 1/500 s or faster (ISO raised instead); blur check on every photo |
| `capture/Recorders.kt` | raw GNSS logger (full tracking), IMU logger and shake snapshot |
| `net/OverpassClient.kt` | Overpass HTTP with mirror fallback, shared cache |
| `MainActivity.kt` | app shell: splash, bottom navigation (New / Sessions / Settings), onboarding on first run, the "GPS is needed" gate, hardware keys |
| `ui/OnboardingScreen.kt` | first-run flow |
| `ui/SetupScreen.kt` | live map, GPS state, road, mode, Start (enabled with a fresh GPS fix), ⓘ how it works |
| `ui/CaptureScreen.kt` | camera preview, status, actions, get-ready checklist |
| `ui/PoiSheet.kt` | Pin & status: pin on the building, what was read, status, folded notes |
| `ui/SessionMap.kt` | session map: track, photo dots, photo panel, position correction |
| `ui/SessionsScreen.kt`, `ui/SettingsScreen.kt`, `ui/RoadPacksCard.kt`, `ui/PhotoRolesCard.kt` | sessions list, settings, road packs, photo kinds |
| `ui/AccessGate.kt` | "GPS is needed": precise location, camera and the phone's location before the app opens |
| `ui/Components.kt` | live location and its state, shared pills, stepper |
| `app/src/test/…` | JVM unit tests for `core/` |

`core/` has no Android dependencies: it is unit-tested on the JVM.

## License

Code: [Apache License 2.0](LICENSE) (see [NOTICE](NOTICE)). Offline road packs contain OpenStreetMap data,
© OpenStreetMap contributors, [ODbL 1.0](https://www.openstreetmap.org/copyright).
