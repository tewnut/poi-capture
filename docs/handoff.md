# Handoff to the POI agent (`road-capture/3`)

A contributor exports one zip per session from the Android app (uploaded in the background to the capture server, see
[server-api.md](server-api.md), or shared by hand). The agent reads it, corrects positions, finds the
places on the photos, and contributes them to the target map (VMAP) through its contribution tools. This file is the
contract.

## Changes in `road-capture/3` (app 0.5)

- **Two kinds of session** (`kind` at the top of the manifest and in `session.kind`):
  - `"sweep"` — "Quét đường": the contributor rides, the app photographs the row of buildings (everything below
    about photos, video, facade points and camera direction applies). Older sessions are sweeps.
  - `"poi"` — "Chụp địa điểm": the contributor stops at each place and photographs it on purpose. Photos are already
    grouped by place, so the agent **skips pipeline steps 3–4** (placing the frontage, grouping frames).
- **`pois`** (POI sessions; empty for sweeps), one per place:
  ```jsonc
  { "id": "poi_…", "createdAt": "ISO", "tMs": 1793…,
    "position": { "lat": …, "lon": …, "source": "pin" | "gps", "accuracyM": 8 },  // pin = put on the building by hand
    "fields": { "name": "…", "category": "…", "houseNumber": "…", "phone": "…", "note": "…" },  // optional, typed on site
    "status": "new" | "exists" | "closed" | "moved", "reviewedAt": "ISO",
    "photos": ["p_…", …],            // creation order
    "cover": "p_…",                  // first "facade" photo, else first "sign", else first photo
    "noPhotos": true }               // only when the place has no photo
  ```
  `position.source = "pin"`: trust it as the place's location (on its building). `"gps"`: the contributor's position
  when the place was started (in front of it): snap to the nearest building footprint within ~15 m.
- **Photo role** (POI sessions): items carry `poiId` and `role`: `"facade"` (straight-on front, the cover), `"sign"`,
  `"side"`, `"inside"`, `"info"` (hours, menu, phone), `"context"`. Their `cameraBearing` comes from the orientation
  sensor (`cameraBearingSource: "compass"`), not from travel; no facade estimate.
- **`marks`** (sweeps): each "★ Đánh dấu" tap, `{id, tMs, t, elapsedNanos, lat, lon, accuracyM, photoIds}` with
  `photoIds` = photos taken within ±5 s: a place the contributor pointed out while riding. Use it as a strong hint
  when grouping frames.
- **`privacy`**: `{ "blurred": false, "required": ["faces", "plates"] }`. Nothing is blurred on the phone (see step 0).
- **Road data sources (app 0.7)**: `session.road.sources` lists where the road data came from: `"pack:<region>@<OSM
  extract date>"` (offline pack), `"cache"` (Overpass answer stored on the phone earlier), `"overpass"`. Ways from a
  pack are **pieces**: `road.ways` can hold several entries with the same OSM `id` and different `piece` (0, 1, …) that
  together make the way; merge by (`id`, `piece`), and use `id` (= `wayId` of the items) as the way's identity.

## Changes from `road-capture/1` (web app)

- **Videos are MP4 segments** (`videos/<id>.mp4`, default 30 s each, one item per segment, `segment` = index) instead
  of one WebM/MP4 assembled from 3 s chunks. A segment cut off by a crash is still exported with `incomplete: true`;
  its MP4 may lack the index (moov atom) and need repair (e.g. `untrunc`) before decoding.
- **New files**: `gnss_raw.csv` (raw GNSS measurements, GnssLogger-style `Raw` rows; column names follow GnssLogger,
  the column order has not been checked against GnssLogger output: read it by the header line) and `imu.csv` (gyroscope,
  accelerometer, rotation vector at ~50 Hz).
- **Common clock**: `elapsedNanos` (Android `SystemClock.elapsedRealtimeNanos`) on photos, video starts, every track
  point, every IMU sample and the GNSS clock, so all streams align without trusting the wall clock.
- **Camera model** in `session.camera` (focal length, sensor size, horizontal FOV, intrinsics when the phone has them)
  and per-photo `exposureS`, `iso`, `focalLengthMm`, `exifOrientation`.
- **Per-photo `motion`**: gyro rate and vibration at the shutter (reject or down-weight blurred frames).
- **`cameraBearingSource`** and `session.settings.mountOffsetDeg`: the camera bearing is the travel bearing ± 90°
  turned by the mount offset (positive = toward the front of the vehicle). The magnetometer is recorded for reference
  only (`motion.deviceAzimuthDeg`); it is disturbed on a motorbike.
- `session.device` adds the SoC and GNSS chipset; `session.gnss` says whether the phone delivered carrier phase.
- **Road mode** (`session.road.mode`): `"picked"` = the contributor chose one road before riding (as in v1);
  `"auto"` (the default since app 0.2) = no road chosen, the app recognises the road from GPS while riding and may
  follow several streets. In auto mode `road.label` is "Tự động theo GPS", `road.primaryWayId` is null and
  `road.ways` holds every way the phone managed to load around the ride (possibly none, when offline).
- **Per-item `roadName`** (label of the matched way, e.g. "Phố Tràng Tiền").
- **Road fields may be null**: `wayId`, `roadName`, `distanceToRoadM`, `segmentBearing`, `travel` and `sideOfWay` are
  null when no road data covered that spot (offline, data not loaded yet, or > 60 m from any known way). The agent
  then recomputes them from `sideOfTravel` + `travelBearing` + its own map-matching on the track.
- **Manual position correction** (app 0.3): the contributor sees every photo as a dot on the map (during and after the
  ride) and can move a wrong one. The GPS position stays in `lat`/`lon`; the correction is added as `relocatedLat`,
  `relocatedLon`, `relocatedAt` (absent/null = not corrected). **Prefer the corrected position** for where the photo
  was taken; `cameraBearing` and `sideOfTravel` still apply.
- **Always the right side** (app 0.3): contributors film the right-hand row of buildings (near side in Vietnam).
- **App 0.4: what is measured instead of asked**
  - `session.vehicle` is `"unspecified"` (older sessions: `"motorbike"` / `"car"`). Photo spacing and the speed limit
    come from `session.plan` (below), the speed from the phone.
  - `session.settings.facadeDistanceM`: expected camera -> shop-front distance chosen by the contributor (profile
    default, 4/6/8/12 m per session). `session.plan` = `{facadeDistanceM, hFovDeg, coverageM, photoEveryM,
    maxSpeedKmh, blurLimitKmh, rateLimitKmh}`: one frame covers `coverageM` of facade, photos every `coverageM/2` so
    each sign is on >= 2 photos (triangulation), speed limited by motion blur and by how fast the camera can shoot.
  - **Camera direction is measured**: from the linear acceleration while speeding up / braking on straight stretches
    (no compass). `session.mount = {yawToTravelDeg, side, consistency, events, source: "measured"}` once confident,
    else null. `sideOfTravel` then follows the measurement (a phone mounted to the left gives `"left"`).
  - Per item: `cameraYawToTravelDeg` (clockwise from travel, right = +90) and `cameraBearingSource`: `"measured"`, or
    `"assumed"` (right side + manual mount offset) when not measured. Items recorded before the measurement are
    corrected in place once it is known. With `"assumed"`, the agent can measure it itself from `imu.csv` + the track.
  - Per item **estimated facade point**: `facadeLat`, `facadeLon`, `facadeDistanceM`, `facadeSource: "profile"` =
    camera position + `facadeDistanceM` along `cameraBearing`. It is a prior to put the POI on the building, not on
    the road: snap it to the street-facing edge of the nearest building footprint (OSM / Microsoft / Google), refine
    by triangulating the sign over consecutive photos and by the house number. When the photo was relocated by hand,
    recompute it from `relocatedLat/Lon` + `cameraBearing` + `facadeDistanceM`.
- `session.exportedAt` (last share/save by the contributor) and `session.device.emulator`.
- All v1 field names are unchanged.

## Zip layout

```
manifest.json        session + one entry per photo/video (schema "road-capture/2")
track.geojson        every GPS fix (LineString + one Point per fix with t, accuracy, speed, heading, elapsedNanos)
gnss_raw.csv         raw GNSS measurements (present when the phone delivers them)
imu.csv              sensor,elapsedNanos,x,y,z,w   g = gyro rad/s, a = accel m/s², r = rotation vector
photos/<id>.jpg      auto or manual photos (16:9, long edge ≈ 2560 px, JPEG q92, landscape)
videos/<id>.mp4      video segments (H.264, ≈1080p, no audio, landscape)
```

## manifest.json

```jsonc
{
  "schema": "road-capture/2",
  "exportedAt": "2026-11-08T10:18:00Z",
  "session": {
    "id": "s_…", "schema": "road-capture/2", "contributor": "…", "note": "…",
    "vehicle": "motorbike" | "car",
    "mode": "photo-auto" | "photo-manual" | "video",
    "createdAt": "ISO", "startedAt": "ISO", "endedAt": "ISO" | null,   // null = cut off (crash, dead battery)
    "sideOfTravel": "left" | "right",   // what the contributor chose: left/right of the direction they drive
    "travel": "forward" | "backward",   // direction actually driven along the primary way (first moving fix)
    "sideOfWay": "left" | "right",      // the filmed side relative to the primary way's drawing direction
    "road": {
      "mode": "picked" | "auto",
      "label": "QL1A · Quốc lộ 1A" | "Tự động theo GPS", "name": "…", "ref": "…", "highway": "primary",
      "primaryWayId": 123456 | null,     // OpenStreetMap way id the contributor picked (null in auto mode)
      "ways": [ { "id": 123456, "name": "…", "ref": "…", "highway": "…", "oneway": "…",
                  "geometry": [ { "lat": 21.0285, "lon": 105.804 }, … ] }, … ]   // picked: same road within 5 km; auto: ways loaded around the ride
    },
    "settings": { "photoEveryM": 10, "maxSpeedKmh": 20, "maxAccuracyM": 30, "videoSegmentSec": 30,
                  "mountOffsetDeg": 0, "photoLongEdge": 2560, "all": { … } },
    "device": { "manufacturer": "Google", "model": "Pixel 9", "sdk": 35, "soc": "Google Tensor G4",
                "gnssHardware": "…", "gnssYear": 2024, "screen": "1080x2424", "lang": "vi-VN", "app": "0.1.0" },
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
      "t": "ISO capture time", "tMs": 1793…, "elapsedNanos": 81234…, "fixT": 1793…,   // fixT = time of the GPS fix used
      "lat": 21.02851, "lon": 105.80512, "accuracyM": 5, "altitudeM": null, "speedMps": 2.9,
      "travelBearing": 90.0,             // direction of travel, ° from north
      "cameraBearing": 180.0,            // travel bearing + cameraYawToTravelDeg
      "cameraYawToTravelDeg": 90.0,      // measured (or assumed: right side ± manual mount offset)
      "cameraBearingSource": "measured" | "assumed",
      "facadeLat": 21.02845, "facadeLon": 105.80512, "facadeDistanceM": 6, "facadeSource": "profile",
      "wayId": 123456, "roadName": "Phố Tràng Tiền", "distanceToRoadM": 3.2, "segmentBearing": 90,   // null without road data
      "travel": "forward", "sideOfTravel": "right", "sideOfWay": "right",   // per item: a U-turn flips the side
      "relocatedLat": 21.02853, "relocatedLon": 105.80519, "relocatedAt": "ISO",   // only when moved by hand on the map
      "width": 2560, "height": 1440, "exifOrientation": 1, "bytes": 812345,
      "exposureS": 0.002, "iso": 100, "focalLengthMm": 6.9,
      "motion": { "gyroMaxRadS": 0.12, "accelStdMs2": 0.8, "deviceAzimuthDeg": 171.2, "devicePitchDeg": -2.1, "deviceRollDeg": -88.0 }
    },
    {
      "id": "v_…", "kind": "video", "file": "videos/v_….mp4", "mime": "video/mp4", "segment": 0,
      "startT": "ISO", "startMs": 1793…, "startElapsedNanos": 81234…, "endT": "ISO", "endMs": 1793…, "durationMs": 30000,
      "recordedDurationMs": 29870,        // media length actually recorded (use this, not durationMs, for frame timing)
      "finalizeError": 0, "bytes": 52428800,
      "incomplete": true,                // only when recording was cut off: end time = the file's last write
      "…": "same position fields as a photo, taken at the start"
    }
  ],
  "trackFile": "track.geojson", "trackPoints": 1234,
  "gnssRawFile": "gnss_raw.csv" | null, "imuFile": "imu.csv" | null
}
```

Notes for the agent:
- **Time base**: `tMs` / `t` are the phone's wall clock (ms since epoch); `elapsedNanos` is the monotonic clock shared
  by photos, video starts, track points (`elapsedNanos` in track.geojson), IMU rows and the GNSS clock
  (`ChipsetElapsedRealtimeNanos`). Prefer it for alignment. For a video, a frame at offset `o` ms was taken at about
  `startMs + o` (start = when the recorder reported it started, ±100 ms); interpolate its position from the track.
- **Side**: trust the per-item `sideOfWay` + `cameraBearing`, not only the session values: a U-turn mid-session flips the
  filmed side. `travel` is decided from GPS heading vs the matched segment; matching prefers ways running along the
  direction of travel, so at intersections the item is not attached to the street being crossed. `sideOfTravel` is
  always set; when `sideOfWay` is null, derive it from your own map-matching.
- **Camera geometry**: frames are landscape. A sign at horizontal pixel `x` in an image of width `W` is at
  `cameraBearing + atan((x − W/2)/(W/2) · tan(hFovDeg/2))`. Prefer `intrinsicCalibration` when present.
- **Interruptions**: a session without `endedAt` was cut off. A phone call or another app taking the camera pauses
  capture; the app is designed to reopen the camera by itself and continue video as a new segment (not yet verified on
  a device).
- **Accuracy**: fixes worse than `settings.maxAccuracyM` were flagged on screen but kept; weight them down.
- **Raw GNSS**: `session.gnss.carrierPhase = false` means code-only measurements (most Snapdragon phones); RTK/PPK
  needs carrier phase.
- Vietnam drives on the right: `sideOfTravel = "left"` photos look across oncoming traffic (more occlusion, longer range).

## Intended agent pipeline (follow-up work)

0. **Privacy blur** (before anything is published or shared beyond the private store): detect and blur faces and
   licence plates on every photo / video frame that will be shown (POI cover and gallery photos, imagery layers). Keep
   the originals private: reading signs needs them unblurred, and the blur must not cover signboards. Candidate
   models (licences to be checked before use): Meta EgoBlur (faces + plates), RetinaFace / SCRFD (faces), a plate
   detector trained on Vietnamese plates. Mark outputs with `privacy.blurred = true`.
1. **Ingest**: unzip, validate `schema`, load items and track. `kind = "poi"`: go straight to step 5 per place, with
   `pois[].position` (pin, or GPS snapped to the building), `cover` as the facade photo and `fields` as hints.
2. **Map-match** each fix to `road.ways` (HMM / nearest-segment with heading); the contributor's position snaps onto the
   carriageway they drove (right lane in Vietnam).
3. **Place the frontage**: a POI seen on a photo lies on the `sideOfWay` side, roughly 10–40 m from the snapped
   position along `cameraBearing`. Offset the point from the road centre line toward that side; use consecutive
   frames (parallax) to refine distance when the same sign is seen twice.
4. **Group frames**: the same signboard appears on 2–5 consecutive photos (10–15 m spacing) or many video frames; cluster by
   position + visual similarity, keep the sharpest frontal frame as the facade/cover photo.
5. **Read the sign** with a vision model: business name, category words, phone numbers, website/Facebook, house number,
   opening hours. Normalise the name to the map's naming rules, e.g. "TRẠM DỪNG CHÂN …" → "Trạm Dừng Chân …".
6. **Address**: house number from the sign if visible, street = the session road (`road.name` / `road.ref`), ward/province
   from the corrected position.
7. **Dedup against the target map** at the corrected point (existing place with a matching name / phone nearby).
8. **Contribute** through the map's contribution API: existing record → fill empty fields and add photos; new place →
   create it with the frontage photo as cover. Whether places may be created from field photos alone, or need an
   independent confirmation first, is a policy decision of the map's operator.

Field photos are the place's own facade, taken on site, so they also provide the cover photo many places lack.
