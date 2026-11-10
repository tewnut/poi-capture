# Handoff to the POI agent (`poi-capture/3`)

A contributor sends one zip per session from the Android app (shared with **Send**, or saved to a file). The agent reads it, corrects positions, finds the places on the
photos and contributes them to the target map through that map's contribution tools. This file is the contract.

## Recent changes

### App 0.9 / 0.10
- **Place capture without typing.** The contributor shoots a few photos per place and taps **Done with place**. Name,
  address / house number, phone and category are for the agent to read from the photos. `pois[].fields` are only hints
  for what a photo cannot show (sign hidden, no sign); when present they win over the reading, and `note` is free text.
- **One-tap "Done with place"**: the place keeps the GPS position where it was started (`position.source: "gps"`:
  snap it to the building), unless the contributor opened **Pin & status…** to pin it (`"pin"`) or mark it closed /
  moved.
- **Photo kinds are optional.** By default the contributor is not asked what each photo shows: photos carry `poiId`
  but **no `role`**, `session.photoRoles` is absent and the place's `cover` is its first photo. Classify the photos
  yourself (front / sign / inside / menu / other) and pick the best cover.
  When the contributor turns kinds on (Settings → Advanced settings), `session.photoRoles` is the list offered,
  `[{id, label, hint, cover, advance}]` (the contributor's own list or the default),
  `photo.role` is one of those ids (a team can add its own, e.g. `"menu"`, `"parking"`), and the `cover` is the first
  photo of the kind with `cover: true`. Older sessions: every photo has a role from the default list below.
- **On-device reading** (place photos only): each photo item carries `ocr` = what ML Kit (Latin script, which covers
  Vietnamese) read on the phone: `{engine: "mlkit-latin", text, lines: [{text, heightPx, box: [l, t, r, b]}], name,
  phones, houseNumber, category}`. Each place carries `read` = the same merged over its photos (without `lines`).
  - `text` / `lines` are raw OCR: diacritics may be missing or wrong; use them next to your own reading of the image.
    `box` is in the pixels of the stored JPEG (before EXIF rotation).
  - `phones` (Vietnamese formats, digits only), `houseNumber` ("12", "45/3") and `category` are guesses by rules
    (`core/SignText.kt`). `category` is one of `restaurant`, `cafe`, `drinks`, `grocery`, `pharmacy`, `vehicle_repair`,
    `phone_shop`, `beauty`, `fashion`, `hotel`, `bank`, `education`, `health`, `electronics`, `hardware` (app ≤ 0.9.1
    wrote a Vietnamese label instead). `name` = the tallest line that is not a sponsor (drink or telecom brands), a
    phone or an address.
  - The agent decides: name vs brand, category, address normalisation, dedup against the map.
  - Missing `ocr` = not read on the phone (sweep photos, reading still running when the zip was made, a phone without
    Google Play services, old app).
- **Hardware keys**: in a sweep the volume keys or a Bluetooth remote on the handlebar add a **★ Mark** (`marks`), or
  shoot in manual mode; nothing changes in the format.

### App 0.8 (renamed POI Capture)
- The schema id is `poi-capture/3` (same format as `road-capture/3`; accept both). Zips are named
  `poi-capture_<road or "place">_….zip` (earlier: `road-capture_…`, `…_dia-diem_…`).
- `session.email`: the contributor's email, typed once at onboarding and **not verified**. Use it to group a person's
  sessions and to credit them, never as proof of identity. `""` in older sessions. `session.contributor` is the display
  name (the part before `@` when none was given).

### `road-capture/3` (app 0.5)
- **Two kinds of session** (`kind` at the top of the manifest and in `session.kind`):
  - `"sweep"`: the contributor rides; the app photographs the row of buildings (everything below about photos, video,
    facade points and camera direction applies). Older sessions are sweeps.
  - `"poi"`: the contributor stops at each place and photographs it. Photos are already grouped by place, so the agent
    **skips pipeline steps 3–4** (placing the frontage, grouping frames).
- **`pois`** (place sessions; empty for sweeps), one per place:
  ```jsonc
  { "id": "poi_…", "createdAt": "ISO", "tMs": 1793…,
    "position": { "lat": …, "lon": …, "source": "pin" | "gps", "accuracyM": 8 },  // pin = put on the building by hand
    "fields": { "name": "…", "category": "…", "houseNumber": "…", "phone": "…", "note": "…" },  // usually empty
    "status": "new" | "exists" | "closed" | "moved", "reviewedAt": "ISO",
    "photos": ["p_…", …],            // creation order
    "cover": "p_…",                  // see "Photo kinds" above
    "read": { "name": "…", "phones": ["0912345678"], "houseNumber": "13", "category": "restaurant", "text": "…" },
    "noPhotos": true }               // only when the place has no photo
  ```
  `position.source = "pin"`: trust it as the place's location (on its building). `"gps"`: the contributor's position
  when the place was started (in front of it): snap to the nearest building footprint within ~15 m.
- **Photo role** (place sessions with kinds on): `"facade"` (straight-on front, the cover), `"sign"`, `"side"`,
  `"inside"`, `"info"` (hours, menu, phone), `"context"`, or a team's own id. A place photo's `cameraBearing` comes
  from the orientation sensor (`cameraBearingSource: "compass"`), not from travel; no facade estimate.
- **`marks`** (sweeps): each **★ Mark**, `{id, tMs, t, elapsedNanos, lat, lon, accuracyM, photoIds}` with `photoIds` =
  photos taken within ±5 s: a place the contributor pointed out while riding. Use it as a strong hint when grouping
  frames.
- **`privacy`**: `{ "blurred": false, "required": ["faces", "plates"] }`. Nothing is blurred on the phone (step 0).
- **Road data sources** (app 0.7): `session.road.sources` lists where the road data came from: `"pack:<region>@<OSM
  extract date>"` (offline pack, one per province since app 0.10), `"cache"` (an Overpass answer stored on the phone
  earlier), `"overpass"`. Ways from a pack are **pieces**: `road.ways` can hold several entries with the same OSM `id`
  and different `piece` (0, 1, …) that together make the way; merge by (`id`, `piece`), and use `id` (= `wayId` of the
  items) as the way's identity.

### Earlier (web app `road-capture/1` → native app)
- **Videos (older sessions only: video mode was removed, its frames were too blurry)** are MP4 segments (`videos/<id>.mp4`, default 30 s each, one item per segment, `segment` = index). A
  segment cut off by a crash is still exported with `incomplete: true`; its MP4 may lack the index (moov atom) and need
  repair (e.g. `untrunc`) before decoding.
- **Files**: `gnss_raw.csv` (raw GNSS measurements, GnssLogger-style `Raw` rows; read the columns by the header line)
  and `imu.csv` (gyroscope, accelerometer, rotation vector at ~50 Hz).
- **Common clock**: `elapsedNanos` (Android `SystemClock.elapsedRealtimeNanos`) on photos, video starts, every track
  point, every IMU sample and the GNSS clock, so all streams align without trusting the wall clock.
- **Camera model** in `session.camera` (focal length, sensor size, horizontal FOV, intrinsics when the phone has them)
  and per-photo `exposureS`, `iso`, `focalLengthMm`, `exifOrientation`.
- **Per-photo `motion`**: gyro rate and vibration at the shutter (reject or down-weight blurred frames).
- **Camera bearing**: the travel bearing ± 90° turned by the mount offset (positive = toward the front of the
  vehicle), see `cameraBearingSource` and `session.settings.mountOffsetDeg`. The magnetometer is recorded for
  reference only (`motion.deviceAzimuthDeg`); it is disturbed on a motorbike.
- `session.device` has the SoC and GNSS chipset; `session.gnss` says whether the phone delivered carrier phase.
- **Road mode** (`session.road.mode`): `"picked"` = one road chosen before riding (old sessions); `"auto"` (the
  default) = the app recognises the road from GPS while riding and may follow several streets. In auto mode
  `road.primaryWayId` is null and `road.ways` holds every way the phone loaded around the ride (possibly none).
- **Per-item `roadName`** (label of the matched way, e.g. "Phố Tràng Tiền").
- **Road fields may be null**: `wayId`, `roadName`, `distanceToRoadM`, `segmentBearing`, `travel` and `sideOfWay` are
  null when no road data covered that spot. The agent then recomputes them from `sideOfTravel` + `travelBearing` + its
  own map-matching on the track.
- **Place set by hand** (app 0.12+): on the map each selected photo shows where it was taken (the camera, GPS, with
  its accuracy) and where the place it shows is (the estimate, or the contributor's pin). "Move place" puts **the
  place**, not the camera: `placeLat`, `placeLon`, `placedAt` (absent / null = not set). **It wins over the
  triangulated and the estimated position.** The camera position is never edited by hand: `lat` / `lon` stay GPS.
  Older sessions (≤ 0.11) instead corrected the **camera** position: `relocatedLat`, `relocatedLon`, `relocatedAt`;
  use that as the photo's origin, with `cameraBearing` and `sideOfTravel` still applying.
- **Measured instead of asked**:
  - `session.vehicle` is `"unspecified"` (older sessions: `"motorbike"` / `"car"`). Photo spacing and the speed limit
    come from `session.plan`, the speed from the phone.
  - The camera → shop-front distance is **not asked** (it changes from block to block): the app takes it from the type of
    road being ridden (OSM `highway`: alleys and small streets 4 m, tertiary 5 m, secondary 6 m, primary / trunk 8 m,
    unknown 4 m), on the near side so photos always overlap. Each item says which: `facadeDistanceM` and
    `facadeRoadType`. `session.settings.facadeDistanceM` is the unknown-road value (older sessions: the contributor's
    choice). `session.plan` (the last one in use) = `{facadeDistanceM, hFovDeg, coverageM, photoEveryM, maxSpeedKmh, blurLimitKmh,
    rateLimitKmh}`: one frame covers `coverageM` of facade, `photoEveryM` = 45 % of it is the widest spacing to expect,
    speed limited by motion blur and by how fast the camera can shoot.
  - **Auto photos are timed by the frames, not by metres** (from v0.13): a 640×360 analysis stream measures how far the
    scene slid sideways (`session.camera.photoTrigger = "frameOverlap"`); a photo is taken after 30 % of a frame at a
    steady moment, at 45 % at the latest, so consecutive photos overlap ≥ 55 % and each sign is on ≥ 2 photos whatever
    the real facade distance. Waiting (GPS speed < 0.7 m/s) adds nothing. Where the frames cannot tell, the GPS speed
    over the elapsed time is used (`"gpsSpeed"` when there is no analysis stream at all). Each auto photo carries
    `overlap = {slide, measured, steady, gyroRadS, frameSharpness}`: slide since the previous photo (fraction of the
    frame), share of it measured on frames, and the shake at the moment. Spacing in metres therefore varies: wider on
    far facades, tighter on close ones.
  - **Camera direction is measured** from the linear acceleration while speeding up / braking on straight stretches
    (no compass): `session.mount = {yawToTravelDeg, side, consistency, events, source: "measured"}` once confident,
    else null. `sideOfTravel` follows the measurement.
  - Per item: `cameraYawToTravelDeg` (clockwise from travel, right = +90) and `cameraBearingSource`: `"measured"`, or
    `"assumed"` (right side + manual mount offset). Items recorded before the measurement are corrected in place once it
    is known. With `"assumed"`, the agent can measure it from `imu.csv` + the track.
  - Per item **orientation**: `orientation` = `"landscape"` | `"portrait"` (how the phone was held), `targetRotationDeg`
    (0 / 90 / 180 / 270, the image is written upright for it; see also `exifOrientation`) and `hFovDeg`, the horizontal
    field of view of that frame (`session.camera.hFovDeg` in landscape, `session.camera.hFovPortraitDeg` in portrait).
    `session.plan` follows the orientation in use at the end of the session.
  - **iPhone sessions** (`session.device.platform = "ios"`): Place capture only, same manifest. No track,
    raw GNSS or IMU files (`trackFile`, `gnssRawFile`, `imuFile` null). `cameraBearing` comes from the motion sensors
    (`cameraBearingSource: "compass"`); on LiDAR iPhones `facadeDistanceM` is measured (`facadeSource: "lidar"`,
    median depth of the middle of the frame, up to about 5 m), else the on-foot default (`"onFoot"`, 5 m). iOS does
    not say whether a position is GPS or Wi-Fi: photos only use fixes with GPS-grade accuracy (≤ 20 m), at most 5 s
    old.
  - Per photo **blur**: `blur = {motionPx, maxMotionPx, sharpness, manualShutter, blurry}`. `motionPx` = speed ×
    exposure × focal length (px) ÷ facade distance (street sweep), `maxMotionPx` the budget for that photo's width;
    `sharpness` = variance of the Laplacian (scene dependent: compare within the session); `blurry` = too much motion
    or far less detail than the session's recent photos. Prefer non-blurry photos when reading signs.
    `session.camera.shutterGuard`: street sweep held the shutter at 1/500 s or faster (ISO raised instead), so photos
    may be grainy rather than smeared. `session.name` = "{street} [yymmdd]".
  - Per item **estimated facade point**: `facadeLat`, `facadeLon`, `facadeDistanceM`, `facadeSource: "roadType"`
    (older sessions: `"profile"`) = camera position + `facadeDistanceM` along `cameraBearing`. A rough prior to put the place on the building, not on the
    road: snap it to the street-facing edge of the nearest building footprint, refine by triangulating the sign over
    consecutive photos and by the house number. In older sessions with a corrected camera, recompute it from
    `relocatedLat / Lon` + `cameraBearing` + `facadeDistanceM`.
- `session.exportedAt` (last send / save by the contributor) and `session.device.emulator`.

## Zip layout

```
manifest.json        session + one entry per photo / video (schema "poi-capture/3")
track.geojson        every GPS fix (LineString + one Point per fix with t, accuracy, speed, heading, elapsedNanos)
gnss_raw.csv         raw GNSS measurements (present when the phone delivers them)
imu.csv              sensor,elapsedNanos,x,y,z,w   g = gyro rad/s, a = accel m/s², r = rotation vector
photos/<id>.jpg      photos (16:9, long edge ≈ 2560 px, JPEG q92, landscape)
videos/<id>.mp4      video segments (H.264, ≈1080p, no audio, landscape)
```

## manifest.json

```jsonc
{
  "schema": "poi-capture/3",
  "kind": "sweep" | "poi",
  "exportedAt": "2026-11-08T10:18:00Z",
  "session": {
    "id": "s_…", "schema": "poi-capture/3", "kind": "sweep" | "poi", "contributor": "…", "email": "…", "note": "…",
    "vehicle": "unspecified" | "motorbike" | "car",
    "mode": "photo-auto" | "photo-manual" | "video",
    "createdAt": "ISO", "startedAt": "ISO", "endedAt": "ISO" | null,   // null = cut off (crash, dead battery)
    "sideOfTravel": "left" | "right",   // left / right of the direction of travel
    "travel": "forward" | "backward",   // direction driven along the primary way (first moving fix)
    "sideOfWay": "left" | "right",      // the filmed side relative to the primary way's drawing direction
    "road": {
      "mode": "picked" | "auto", "label": "…", "name": "…", "ref": "…", "highway": "primary",
      "primaryWayId": 123456 | null,     // OpenStreetMap way id (null in auto mode)
      "sources": ["pack:ha-noi@2026-11-08", "overpass"],
      "ways": [ { "id": 123456, "piece": 0, "name": "…", "ref": "…", "highway": "…", "oneway": "…",
                  "geometry": [ { "lat": 21.0285, "lon": 105.804 }, … ] }, … ]
    },
    "settings": { "photoEveryM": 4.2, "maxSpeedKmh": 23, "facadeDistanceM": 6, "maxAccuracyM": 30,
                  "mountOffsetDeg": 0, "photoLongEdge": 2560, "all": { … } },
    "plan": { … }, "mount": { … } | null,
    "photoRoles": [ … ],                 // only when photo kinds were on
    "device": { "manufacturer": "Google", "model": "Pixel 9", "sdk": 35, "soc": "Google Tensor G4",
                "gnssHardware": "…", "gnssYear": 2024, "screen": "1080x2424", "lang": "vi-VN", "app": "0.10.0" },
    "camera": { "cameraId": "0", "hardwareLevel": 1, "timestampSource": "realtime" | "unknown",
                "focalLengthMm": 6.9, "sensorWidthMm": 9.8, "sensorHeightMm": 7.4, "pixelArray": "4080x3072",
                "intrinsicCalibration": [ "fx", "fy", "cx", "cy", "s" ] | null, "lensDistortion": [ … ] | null,
                "targetRotation": 90, "hFovDeg": 70.6, "vFovDeg4x3": 56.4 },
    "counts": { "photos": 812, "videos": 0, "trackPoints": 1234 },
    "gnss": { "epochs": 1200, "measurements": 38000, "adrValid": 0, "carrierPhase": false }
  },
  "items": [
    {
      "id": "p_…", "kind": "photo", "file": "photos/p_….jpg", "trigger": "auto" | "manual",
      "t": "ISO capture time", "tMs": 1793…, "elapsedNanos": 81234…, "fixT": 1793…,   // fixT = time of the GPS fix used (at most 5 s before the photo: the app records nothing without a fresh fix; older sessions may have older fixes)
      "lat": 21.02851, "lon": 105.80512, "accuracyM": 5, "altitudeM": null, "speedMps": 2.9,
      "travelBearing": 90.0,             // direction of travel, ° from north
      "cameraBearing": 180.0,            // travel bearing + cameraYawToTravelDeg
      "cameraYawToTravelDeg": 90.0,
      "cameraBearingSource": "measured" | "assumed" | "compass",
      "facadeLat": 21.02845, "facadeLon": 105.80512, "facadeDistanceM": 6, "facadeSource": "roadType", "facadeRoadType": "residential",
      "wayId": 123456, "roadName": "Phố Tràng Tiền", "distanceToRoadM": 3.2, "segmentBearing": 90,   // null without road data
      "travel": "forward", "sideOfTravel": "right", "sideOfWay": "right",   // per item: a U-turn flips the side
      "placeLat": 21.02851, "placeLon": 105.80527, "placedAt": "ISO",   // only when the place was set by hand
      "poiId": "poi_…", "role": "sign", "ocr": { … },                                // place photos only
      "width": 2560, "height": 1440, "exifOrientation": 1, "bytes": 812345,
      "exposureS": 0.002, "iso": 100, "focalLengthMm": 6.9,
      "motion": { "gyroMaxRadS": 0.12, "accelStdMs2": 0.8, "deviceAzimuthDeg": 171.2, "devicePitchDeg": -2.1, "deviceRollDeg": -88.0 }
    },
    {
      "id": "v_…", "kind": "video", "file": "videos/v_….mp4", "mime": "video/mp4", "segment": 0,
      "startT": "ISO", "startMs": 1793…, "startElapsedNanos": 81234…, "endT": "ISO", "endMs": 1793…, "durationMs": 30000,
      "recordedDurationMs": 29870,        // media length actually recorded (use this for frame timing)
      "finalizeError": 0, "bytes": 52428800,
      "incomplete": true,                // only when recording was cut off: end time = the file's last write
      "…": "same position fields as a photo, taken at the start"
    }
  ],
  "pois": [ … ], "marks": [ … ],
  "privacy": { "blurred": false, "required": ["faces", "plates"] },
  "trackFile": "track.geojson", "trackPoints": 1234,
  "gnssRawFile": "gnss_raw.csv" | null, "imuFile": "imu.csv" | null
}
```

Notes for the agent:
- **Time base**: `tMs` / `t` are the phone's wall clock (ms since epoch); `elapsedNanos` is the monotonic clock shared
  by photos, video starts, track points (`elapsedNanos` in track.geojson), IMU rows and the GNSS clock
  (`ChipsetElapsedRealtimeNanos`). Prefer it for alignment. For a video, a frame at offset `o` ms was taken at about
  `startMs + o` (±100 ms); interpolate its position from the track.
- **Side**: trust the per-item `sideOfWay` + `cameraBearing`, not only the session values: a U-turn mid-session flips the
  filmed side. Matching prefers ways running along the direction of travel, so at intersections the item is not
  attached to the street being crossed. `sideOfTravel` is always set; when `sideOfWay` is null, derive it from your own
  map-matching.
- **Camera geometry**: frames are landscape. A sign at horizontal pixel `x` in an image of width `W` is at
  `cameraBearing + atan((x − W/2) / (W/2) · tan(hFovDeg / 2))`. Prefer `intrinsicCalibration` when present.
- **Interruptions**: a session without `endedAt` was cut off. A phone call or another app taking the camera pauses
  capture; the app reopens the camera by itself and continues video as a new segment.
- **Accuracy**: fixes worse than `settings.maxAccuracyM` were flagged on screen but kept; weight them down.
- **Raw GNSS**: `session.gnss.carrierPhase = false` means code-only measurements (most phones); RTK / PPK needs
  carrier phase.
- Vietnam drives on the right: `sideOfTravel = "left"` photos look across oncoming traffic (more occlusion, longer range).

## Intended agent pipeline

0. **Privacy blur** (before anything is published or shared beyond the private store): detect and blur faces and
   licence plates on every photo / video frame that will be shown (place cover and gallery photos, imagery layers). Keep
   the originals private: reading signs needs them unblurred, and the blur must not cover signboards. Candidate
   models (check licences before use): Meta EgoBlur (faces + plates), RetinaFace / SCRFD (faces), a plate detector
   trained on Vietnamese plates. Mark outputs with `privacy.blurred = true`.
1. **Ingest**: unzip, validate `schema`, load items and track. `kind = "poi"`: go straight to step 5 per place, with
   `pois[].position` (pin, or GPS snapped to the building), the photos of the place, `read` and `fields` as hints.
2. **Map-match** each fix to `road.ways` (HMM / nearest segment with heading); the contributor's position snaps onto the
   carriageway they drove (right lane in Vietnam).
3. **Group frames**: the same signboard appears on 2–5 consecutive photos (street sweep spaces them for that: each
   point of the facade on ≥ 2 photos); detect the sign in each photo (its bounding box), and cluster detections by
   sign text (normalised, diacritics folded) + visual similarity + consistent bearings (step 4). Prefer photos with
   `blur.blurry = false`; keep the sharpest frontal frame as the cover photo.
4. **Triangulate each sign** (bearing-only intersection). Every detection is a ray from the camera towards the sign:
   - origin `pᵢ`: the camera position (`lat` / `lon`, or the post-processed track at the photo's `elapsedNanos`),
     projected to a local metric frame (east / north metres around the session);
   - bearing `θᵢ = cameraBearing + atan((x − W/2) / f)`, with `x` the sign's centre column in the upright image,
     `W` the image `width` and `f = (W/2) / tan(hFovDeg/2)` (per item: portrait and landscape frames differ);
     direction `uᵢ = (sin θᵢ, cos θᵢ)`;
   - weight `wᵢ = 1 / σᵢ²` with `σᵢ² ≈ (dᵢ · δθᵢ)² + accuracyM²`, `dᵢ` the distance to the current estimate (start
     with `facadeDistanceM`) and `δθᵢ` the bearing error: about 3° when `cameraBearingSource = "measured"`, 10° when
     `"assumed"`, 20° for `"compass"` (Place capture on foot).

   The point closest to all rays (weighted least squares) solves the 2 × 2 system
   `(Σ wᵢ Mᵢ) x = Σ wᵢ Mᵢ pᵢ` with `Mᵢ = I − uᵢ uᵢᵀ` (the projector onto each ray's normal). Do **not** average the
   pairwise crossings: nearly parallel pairs land far away and drag the mean. Then:
   - **check the geometry**: the solution must be in front of every camera (`(x − pᵢ) · uᵢ > 0`) and the rays should
     span a useful angle (≥ about 20° between the extreme bearings); with only near-parallel rays, fall back to the
     single-photo prior below;
   - **reject outliers**: drop the ray with the largest normalised residual `|Mᵢ (x − pᵢ)| / σᵢ` when it exceeds 3,
     re-solve, repeat while ≥ 2 rays remain (a mismatched sign, a reflection, a wrong bearing);
   - **report the uncertainty**: the covariance is `(Σ wᵢ Mᵢ)⁻¹`; its eigenvalues give the error ellipse (store it
     with the place, use it to decide whether to auto-contribute or ask for review);
   - **apply the map**: the place should be off the carriageway and on the `sideOfWay` side (the same checks as the
     app's Move warnings: `MoveCheck`), and on or next to a building outline when one exists; when the ellipse
     crosses a building's street edge, snap to that edge.

   What triangulation does not remove: GPS error shared by consecutive photos moves all rays together (correct the
   track first with the raw GNSS, `gnss_raw.csv`), and a constant bearing bias (mount yaw) shifts the point along
   the street on a straight ride. A sign seen on one photo only: use the **single-photo prior** = the item's
   estimated facade point (`facadeLat` / `facadeLon`: camera + `facadeDistanceM` along the bearing to the sign),
   flagged low confidence. A place the contributor set by hand (`placeLat` / `placeLon`) wins over both.
5. **Read the sign** with a vision model, on the photos of its cluster: business name, category, phone numbers,
   website / social pages, house number, opening hours. Normalise the name to the map's naming rules (e.g. all-caps signs → title case).
6. **Address**: house number from the sign if visible, street = the matched road, ward / province from the corrected
   position.
7. **Dedup against the target map** at the corrected point (an existing place with a matching name / phone nearby).
8. **Contribute** through the map's contribution API: existing record → fill empty fields and add photos; new place →
   create it with the frontage photo as cover. Whether places may be created from field photos alone, or need an
   independent confirmation first, is a policy decision of the map's operator.

Field photos are the place's own facade, taken on site, so they also provide the cover photo many places lack.
