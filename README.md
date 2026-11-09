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

## How it works

Two kinds of session, chosen on **New**:
- **Street sweep**: ride along a street; the app photographs the row of buildings automatically. **★ Mark** (or the
  volume keys / a Bluetooth remote on the handlebar) points out a place while riding.
- **Place capture**: stop at each place, **Shoot** a few photos (front, sign, inside, menu…), then **Done with place**
  and go on to the next one. Nothing to type: the phone reads the text on the photos (ML Kit, Latin script including
  Vietnamese) and shows "Read on the sign: name · phone · category", or asks to shoot the sign again when nothing is
  readable; the agent decides the name, address and category. The place keeps the GPS position where it was started;
  **Pin & status…** is there when it is worth putting the pin on the right building or marking the place closed / moved.
  Photos are grouped by place in the export, so the agent does not have to sort them.

0. **Onboarding** (first run, about a minute): what the app does → **your email** (format checked, not verified; it
   credits and groups the contributor's sessions) and an optional display name → **permissions** (camera, precise
   location, notifications, each with its reason; a shortcut to Settings when refused) → **road data**: the app finds
   the province / city the contributor is in and downloads its road pack by itself → **ready**: facade distance, how to
   mount the phone, a summary. Everything can be changed later in **Profile**.
1. **New** (one screen): live map with the GPS state and what to do when there is no position (grant the permission,
   switch on precise location or the phone's location, how many satellites are seen), the road the contributor is on
   (recognised automatically, also offline), the distance from the camera to the shop fronts (4 / 6 / 8 / 12 m), the
   sweep mode (auto photos, manual, video), then **Start**. Photo spacing (each sign on ≥ 2 photos) and the speed
   limit follow from the facade distance and the camera's field of view; the side (right / left) is measured while
   riding.
2. **Capture**: full-screen rear camera in landscape, kept minimal. A get-ready checklist (GPS, camera, road data,
   mounting) comes first. While recording: one line of status text (GPS · road; riding: the camera direction until it
   is measured, red if it looks forward / backward), one warning at a time (too fast, camera cannot keep up, weak GPS,
   phone overheating) and **End**. Riding: speed against the limit, "● Recording", a big **★ Mark**. Places: **Shoot**
   and **Done with place** on the right, under the thumb. Capture runs in a foreground service (camera + location), keeps
   going with the screen off or another app on top, and resumes after a phone call.
3. **Sessions**: what is left to send; per session date, duration, distance, counts and a badge; **Send** (share sheet:
   the contributor picks where it goes), **Map** (track, a dot per photo, the estimated shop-front point; tap a dot to
   see the photo, **Move** it by tapping the right place or dragging the dot, **Back to GPS** to undo), save the zip or
   delete in the ⋮ menu. Sessions cut off by a crash or a dead battery can still be sent.

Safety: in a street sweep the phone is mounted and the app never needs a tap; marks can come from a handlebar remote.

## Data and services

- **Send**: the zip goes to whatever app the contributor picks (messaging, cloud drive, email…), or is saved to a file.
- **Background upload** (in the code, not in the UI: there is no server setting since 0.9; a team server can be
  provisioned again later): with an upload URL set, finished sessions are uploaded over Wi-Fi with the tus resumable
  protocol, verified by SHA-256, and the optional API shows what the agent found. Contract:
  [docs/server-api.md](docs/server-api.md).
- **Offline road data** (Profile → Offline road data): one pack per province / city (the 34 since the 2025 merger),
  built from OpenStreetMap (Geofabrik) by [tools/roadpack](tools/roadpack/README.md) and published monthly as the GitHub
  release `roadpacks`. Within a pack the road is recognised without network; elsewhere the app asks Overpass and keeps
  the answer on the phone.
- **Photo kinds** (Profile → Advanced settings): off by default, the agent tells a front from a sign by itself. A team
  can turn on its own list of kinds (shown as buttons while shooting) or serve one from its server.
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
capture modes, hardware keys, sign reading with the real ML Kit model, offline road packs).

Not yet verified on many real phones: the foreground service with camera + location types (strict on Android 14+),
capture with the screen off, camera recovery after a call, raw GNSS / IMU logging on different chipsets, and the sign
reading on real Vietnamese signboards (diacritics are not always read right: the agent re-reads the photos).

## Settings

Defaults live in `core/Session.kt` (`Settings`) and are stored per device: tile URL (any XYZ tile server), Overpass
URL, photo spacing, speed limits, GPS accuracy limit, photo size, JPEG quality, video segment length, mount offset.

## Code

| File | What |
|---|---|
| `core/Geo.kt` | distance, bearing, nearest point on a line, side model, camera bearing with mount offset |
| `core/Road.kt` | OSM ways, heading-aware road matching, grid cells for automatic road loading, Overpass queries |
| `core/FixProcessor.kt` | per-fix logic: travel bearing / speed, travel direction, U-turn, warnings, item metadata, auto-shot spacing |
| `core/Session.kt` | session model, settings, schema id |
| `core/SessionStore.kt` | per-session folders, append-only files (crash-safe) |
| `core/Exporter.kt` | streaming zip: manifest.json, track.geojson, gnss_raw.csv, imu.csv, photos, videos |
| `core/TrackStats.kt` | distance / duration of a ride, main roads of a session |
| `core/CapturePlan.kt` | photo spacing and speed limit from the facade distance and field of view |
| `core/MountEstimator.kt` | camera direction relative to travel from acceleration / braking (no compass) |
| `core/Poi.kt`, `core/PhotoRoles.kt` | session kinds (sweep / place), place records, optional photo kinds (config) |
| `core/SignText.kt`, `capture/SignOcr.kt` | on-device sign reading: ML Kit OCR, then phone / house number / category / name rules |
| `core/RoadPack.kt`, `capture/RoadPackWork.kt`, `net/RoadSource.kt` | offline road packs: catalogue, province lookup, download, cell lookup |
| `core/Upload.kt`, `capture/Uploads.kt` | tus client and resumable upload job, WorkManager worker, server read API |
| `core/Coverage.kt` | thinned tracks / places for the "already recorded" map, team coverage GeoJSON |
| `core/Onboarding.kt` | email check, display name, which province pack covers a position |
| `capture/CaptureService.kt` | foreground service: CameraX photos / segmented video, GPS, sensors, session control |
| `capture/Recorders.kt` | raw GNSS logger (full tracking), IMU logger and shake snapshot |
| `net/OverpassClient.kt` | Overpass HTTP with mirror fallback, shared cache |
| `MainActivity.kt` | app shell: splash, bottom navigation (New / Sessions / Profile), onboarding on first run, hardware keys |
| `ui/OnboardingScreen.kt` | first-run flow |
| `ui/SetupScreen.kt` | live map, GPS state and help, road, facade distance, mode, Start |
| `ui/CaptureScreen.kt` | camera preview, status, actions, get-ready checklist |
| `ui/PoiSheet.kt` | Pin & status: pin on the building, what was read, status, folded notes |
| `ui/SessionMap.kt` | session map: track, photo dots, photo panel, position correction |
| `ui/SessionsScreen.kt`, `ui/ProfileScreen.kt`, `ui/RoadPacksCard.kt`, `ui/PhotoRolesCard.kt` | sessions list, profile, road packs, photo kinds |
| `ui/Components.kt` | live location and its state, shared pills, stepper |
| `app/src/test/…` | JVM unit tests for `core/` |

`core/` has no Android dependencies: it is unit-tested on the JVM.

## License

Code: [Apache License 2.0](LICENSE) (see [NOTICE](NOTICE)). Offline road packs contain OpenStreetMap data,
© OpenStreetMap contributors, [ODbL 1.0](https://www.openstreetmap.org/copyright).
