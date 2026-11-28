# Camera 360: an external 360° camera with the phone's GPS

A 360° camera (Insta360 X3/X4/X5, Ricoh Theta, GoPro MAX, …) records the street; the phone records where it was.
The processing on the PC puts every camera frame on the phone's track by time, cuts it into ordinary views (front,
left, right) and from there the pipeline is the usual one (reading, blur, review). The camera needs no GPS of its
own; when it has one (GoPro MAX) its track is a fallback or a cross-check.

This file is the contract between the app (section 2) and the processing (section 3). Section 4: a Ricoh Theta the
phone controls (it triggers the photos and writes its GPS into them; no sync code needed).

## 1. Capture (what the rider does)

1. Mount the camera (helmet or a pole on the motorbike) and start it: 360 video, or 360 time-lapse photos.
2. In Quét Phố: Ghi mới → **Camera 360** → Bắt đầu. The phone records GPS only (no photos, the phone's camera
   stays off) and may go in a pocket or on the handlebar, screen off.
3. **Sync:** right after Bắt đầu the phone shows a full-screen **sync QR code**; hold it in front of the 360
   camera for 2–3 seconds (any side of the camera: it sees all around). Again at the end (Kết thúc shows it once
   more) so a clock drift during a long ride is measured too. "Đồng bộ lại" shows it at any time.
4. Ride. End the ride on the phone, stop the camera.
5. Upload the ride from the phone as usual; copy the camera's files to the PC.

## 2. App: the ride (session kind `camera360`)

- `session.kind` = `"camera360"`. Everything else as a sweep: `track.jsonl` (≤ 1 fix a second or as recorded for
  sweeps), `imu.csv`, raw GNSS when available, the manifest, upload. **No photos.** No camera is opened.
- The sync QR text: `QPSYNC1 <sessionId> <epochMs>` — `epochMs` = the phone's wall clock
  (`System.currentTimeMillis()`, the same clock as the track's time fields) at the moment the code is drawn,
  redrawn every 100 ms. Large, black on white, quiet zone, error correction M; nothing else on the screen but a
  short instruction and a Done button.
- `session.camera360.syncs`: every time the QR screen is shown, `{"fromMs", "toMs", "when": "start" | "end" |
  "manual"}` (epoch ms). The processing uses them to know when to look for the code.
- The setup screen's Chế độ gets a third choice **Camera 360**; no target / holding choice for it. While recording,
  the capture screen shows GPS, speed, distance, time and the "Đồng bộ lại" button (no camera preview).

### App notes (as built)

- The ride starts on the first fresh GPS fix after Bắt đầu, then the QR screen comes up (`when: "start"`). Kết thúc
  shows it again (`"end"`) before the end dialog; "Ghi tiếp" there keeps recording (the end window stays in
  `syncs`). A ride ended before any GPS fix is dropped, like an empty sweep.
- `fromMs` = when the QR screen opened, `toMs` = when Xong was tapped (or Back). The QR content is the only clock that
  matters; the window only says where to look.
- Each window may carry `gpsMinusPhoneMs`: the last fresh fix's time (`t` in `track.jsonl`, the GNSS time Android
  gives with the fix) minus the phone's wall clock at that fix. Usually a few hundred ms or less; when it is large the
  phone clock is off, and the processing should add it to `epochMs` from the code before comparing with track times.
  Missing when there was no fresh fix.
- QR: byte mode, ECC M, 4-module quiet zone, one bitmap pixel per module scaled up without filtering, full
  brightness; 29 × 29 modules (version 3) plus the quiet zone with today's session ids, the same size on every redraw.
- The session: `mode` `"photo-manual"` (no automatic photos), `target` null, `items` empty, no `mount` (the phone
  rides in a pocket: no camera direction is measured). `imu.csv`, `gnss_raw.csv`, `track.jsonl` as in a sweep.
  The ride's `name` is "Camera 360 <stamp>". The zip is `poi-capture_camera360_<yyyyMMddHHmm>.zip`; upload
  metadata `kind` = `camera360`, `photos` = 0.
- The foreground service is of type location only; the phone's camera permission is not asked for this kind.
- The screen is not kept on while recording (only while the QR is shown): the phone may go in a pocket.

## 3. Processing (PC): `camera360.py`

`python camera360.py <ride folder or zip from the phone> <camera files…> <work> [--views front,left,right]
[--every-m 5] [--offset-s X]`:

1. **Camera frames and their camera-clock times**: 360 photos (equirectangular JPG; time from EXIF
   DateTimeOriginal + SubSecTimeOriginal + OffsetTimeOriginal) or an equirectangular 360 video (MP4 exported
   from the camera's app; time = the file's creation time + each frame's timestamp, read with ffprobe / ffmpeg).
   A raw `.360` (GoPro) or `.insv` (Insta360) file is refused with what to do: export it as an equirectangular
   360 video first.
2. **Clock offset**: find the sync QR in the frames around each `syncs` window (OpenCV's QR detector, on the
   equirectangular frame and on a few cut views since the code may sit across the seam); offset = `epochMs` from
   the code − the frame's camera time. Start and end codes: a linear drift between them. No code found:
   `--offset-s`, else the camera's own clock as is (said loudly in the printout and the report: positions may be
   off by the camera's clock error × speed).
3. **Positions**: each frame's phone time → the position interpolated on `track.jsonl` (as prepare.py does between
   fixes), travel bearing from the track. Frames are kept every `--every-m` metres along the track (photos: the
   nearest one); none while standing.
4. **Views**: each kept frame is cut into perspective views (default front, left, right; 90° wide, 4:3, about the
   phone photos' size) by an equirectangular → rectilinear remap. Which direction of the 360 image is "front" is
   found from the motion (the direction the scene flows out of), else `--front-deg`.
5. **Output**: a sweep folder like the phone's own (`manifest.json` with `kind: "sweep"`, `source: "camera360"`,
   `photos/` with one photo per view, each with `cameraBearing` = travel bearing + the view's angle, `hFovDeg` 90,
   `bearingSource: "camera360"`, the position, `positionFrom: "phone track"`), so `prepare.py` and the rest run
   unchanged. Front views are the signs' views; left and right the shops'.

### Processing notes (as built)

- Script: `.claude/skills/process-sweep/scripts/camera360.py` (tests: `scripts/tests/test_camera360.py`, on
  synthetic 360 material). Output: `<work>/sweep/` (run `prepare.py <work>/sweep <work>`) and
  `<work>/camera360.json` (clock, front, frames, warnings). Extra options: `--front-deg`, `--size` (default
  1600x1200), `--hfov` (90), `--sync-search-s` (90: how far from each window to look, camera clock error),
  `--scan-s` (180).
- Clock: offset = track time − camera time, where track time is the GPS fix time `t` of `track.jsonl` /
  `track.geojson`: the code's `epochMs` (the phone's wall clock) + the window's `gpsMinusPhoneMs` (the windows'
  median for a code outside every window; 0 when none has it) + 50 ms (a code is 0–100 ms old when seen). Each
  window's codes give a median offset; two windows or more a straight line (the drift; over 1000 ppm is flagged).
  Codes of another session id are ignored and counted. `--offset-s` means the same (track − camera, seconds).
- Where to look: a camera whose clock is whole hours off (another time zone, local time written as UTC: common in
  MP4 `creation_time`) is first shifted by the whole hours that make its span overlap the ride's; the windows ±
  `--sync-search-s` are scanned outwards from their middle at 2 frames a second until a code is read, then every
  100 ms around it. Nothing there: the first and last `--scan-s` seconds of the camera material. A photo without
  `OffsetTimeOriginal` is read in the PC's time zone (warned); a video without `creation_time` starts at the file
  time − its duration (warned). MP4 `creation_time` keeps whole seconds only: the code's offset absorbs the lost
fraction, `--offset-s` alone cannot. Video frames are read with OpenCV; a seek is checked against the frame's
timestamp (ffprobe packet times) and falls back to decoding from the start. QR detection: the frame (≤ 4096 px wide), then 13 cuts of 90° (8 around the
  horizon, 4 at −30°, one at −70°), OpenCV's QRCodeDetector then QRCodeDetectorAruco.
- Positions: `prepare.place_in_time` on the track keyed by `t` (fixes up to 5 s apart). Distance ridden counts
  only segments at ≥ 0.7 m/s; for every `--every-m` mark the nearest moving frame within half a step is kept.
  Travel bearing: from the position 2 s before to 2 s after. Each item also gets `elapsedNanos` interpolated from
  the track, so prepare.py places it again by the same interpolation (`positionFrom` in photos.json is then
  prepare's `interpolated`; the manifest item says `phone track`).
- Front: focus of expansion of the horizontal optical flow (Farneback, 1024×512, ±20° band, fit
  `a + k·sin(yaw − foe)`) on up to 30 frame pairs spread over the ride: a video frame and the one 0.2 s later, a
  photo and the next photo within 3 s. It needs the two frames ≲ 2–3 m apart (street depth): a sparse time-lapse
  at speed gives no estimate (use `--front-deg`). The median is used; when the estimates vary by more than 10°
  (median deviation: helmet mount, or an export that keeps north up) each frame takes the median of its 3 nearest.
- Output items: ids `c<frame>_<view>`, one block per view in riding order (front, then left, then right),
  `cameraBearingSource` and `bearingSource` "camera360" (build.py applies no compass correction), `sideOfTravel`
  left / right for the side views, `blur.sharpness`, `camera360 {view, frontDeg, file, frameIndex, cameraMs, s}`.
  `session.target` null becomes `shops`; `session.rideKind` keeps `camera360`.
- Not verified without a real camera file: real Insta360 / GoPro / Theta exports (their `creation_time`
  semantics, EXIF tags, HEVC decoding by OpenCV, rotation metadata), the code's readability at real distance,
  motion blur and lighting, the front estimate on real streets and helmet mounts, and the speed on a long 5.7K
  video (frames are decoded sequentially).

## 4. Ricoh Theta: điều khiển từ điện thoại / phone-controlled

With a Ricoh Theta (SC2, V, Z1, X) the phone does not only record GPS next to the camera: it **triggers** a 360
photo every N metres over the camera's own Wi-Fi and, before each one, writes its GPS fix into the camera
(`gpsInfo`), so every photo carries the phone's position in its EXIF, and the phone keeps a record of each shot. The
camera's clock is set from the phone at connect: **no sync QR is needed** ("Đồng bộ lại" stays as a fallback).

### 4.1 Người lái làm gì / the rider's steps

1. Bật Theta và Wi-Fi của camera (chế độ trực tiếp: the camera is the access point). Gắn camera (mũ bảo hiểm hoặc
   gậy trên xe).
2. Quét Phố: Ghi mới → Chế độ **Camera 360** → Điều khiển camera **Ricoh Theta**.
3. Nhập **số serial** (8 số, ở đáy camera) hoặc tên Wi-Fi (`THETAYR12345678.OSC`); Mật khẩu để trống = số serial
   (mặc định của Theta). Chọn **Chụp mỗi** 5 / 8 / 12 / 20 m (mặc định 8 m).
4. **Kết nối** → Android hiện hộp thoại kết nối thiết bị: chọn Wi-Fi THETA. Xong: "Đã kết nối RICOH THETA X · pin
   80%". Mobile data keeps working for the map and the upload.
5. **Bắt đầu**. Không có mã QR. Đi xe: điện thoại tự chụp mỗi N m (không chụp khi đứng yên). Màn hình: trạng thái kết
   nối, Ảnh, Bỏ qua (camera còn bận với ảnh trước, hoặc chưa kết nối), lỗi cuối. Mất Wi-Fi camera: chuyến đi GPS vẫn
   tiếp tục; điện thoại tự kết nối lại sau 15 giây (có thể hiện lại hộp thoại) hoặc bấm **Kết nối lại**.
6. **Kết thúc** → Lưu. Điện thoại rời Wi-Fi camera. Gửi chuyến đi như thường.
7. On the PC: copy the camera's `DCIM/100RICOH` folder over USB (the phone does not download the photos: 5–12 MB
   each), then `camera360.py <ride> <that folder> <work>` (section 3; matching by file name, 4.5).

### 4.2 App: networking (as built)

- `ConnectivityManager.requestNetwork` with a `WifiNetworkSpecifier` (Android 10+; the app's minimum is 11): the exact
  SSID when the rider typed one (`THETA….OSC`), else the pattern `THETA.*<digits>.*` (no digits: the prefix
  `THETA`); WPA2 passphrase = the typed password, else the serial digits. The request drops
  `NET_CAPABILITY_INTERNET` (the camera has none). Android shows its own device-connect dialog; after 60 s without a
  network: "Không kết nối được: Wi-Fi".
- The process is **not** bound to that network: only the OSC requests go over it (`Network.openConnection`), so the
  default network (mobile data) keeps serving map tiles, road data and uploads.
- Cleartext HTTP is allowed for `192.168.1.1` only (`res/xml/network_security_config.xml`). Permissions added:
  `CHANGE_NETWORK_STATE`, `ACCESS_WIFI_STATE` (both normal).
- Lost (`onLost`): shown as "Mất Wi-Fi camera"; during a ride the request is made again after 15 s (also after a
  refusal / timeout), "Kết nối lại" asks at once. A trigger while not connected is counted as skipped. The ride's
  end (or leaving the setup without starting) releases the network.
- Code: `core/ThetaOsc.kt` (OSC client behind the `OscTransport` interface, the distance trigger `ThetaShooter`, the
  `ThetaRide` JSON helpers; pure JVM, unit-tested against a fake OSC server in `ThetaOscTest`),
  `capture/ThetaLink.kt` (the Wi-Fi link), `capture/CaptureService.kt` (`startTheta`, shots into the session),
  `ui/ThetaViews.kt` (setup card, ride panel).

### 4.3 App: the OSC calls (RICOH THETA API v2.1, OSC API level 2)

- At connect: `GET /osc/info` (model, serialNumber, firmwareVersion; an `apiLevel` without 2 = a Theta S / SC in API
  v2.0: refused, sessions are not handled), then `camera.setOptions` `{"dateTimeZone": "2026:11:28 10:00:00+07:00"}`
  (the phone's clock and zone), then best effort, each its own call, a refusal only noted: `captureMode` "image",
  `exposureDelay` 0 (no self-timer), `sleepDelay` 65535 and `offDelay` 65535 (the OSC spec's "never"). Then
  `POST /osc/state` for `batteryLevel` (0–1), again every 60 s while connected.
- Each shot: `camera.setOptions` `{"gpsInfo": {"lat": 10.776942, "lng": 106.700925, "_altitude": 12.3,
  "_dateTimeZone": "2026:11:28 10:15:02+07:00", "_datum": "WGS84"}}` (the fix that triggered it, 6 decimals), then
  `camera.takePicture` → `inProgress` with an `id` → `POST /osc/commands/status {"id": …}` every 300 ms until `done`
  (`results.fileUrl`) or `error`, at most 20 s. A refused gpsInfo still takes the photo (noted in the shot's `error`).
- Trigger: distance along the GPS track since the last trigger, counting only moving segments (Doppler speed, else
  from the two fixes, ≥ 0.7 m/s); the first moving fix shoots. One shot at a time: a trigger while the previous shot
  is not done is **skipped** and counted, and the distance starts again from there.
- Every request: `Accept: application/json`, `X-XSRF-Protected: 1`, POSTs `Content-Type:
  application/json;charset=utf-8`; 5 s connect / read timeout.

### 4.4 The session

`session.camera360` of a Theta ride (`kind` `camera360`, everything else as in section 2):

```json
{
  "syncs": [],
  "control": "theta-osc",
  "everyM": 8,
  "camera": {"model": "RICOH THETA X", "serial": "12345678", "firmware": "2.10.1"},
  "shots": [
    {"tMs": 1795000002000, "lat": 10.7769421, "lon": 106.7009245, "accuracyM": 3.5, "bearing": 92.4,
     "speedMps": 5.6, "cmdId": "23",
     "fileUrl": "http://192.168.1.1/files/150100525831424d42079d18e0b6c300/100RICOH/R0010023.JPG",
     "fileName": "R0010023.JPG", "ok": true},
    {"tMs": 1795000003600, "lat": 10.776941, "lon": 106.7010061, "accuracyM": 3.5, "bearing": 92.1,
     "speedMps": 5.5, "cmdId": null, "fileUrl": null, "fileName": null, "ok": false,
     "error": "timeout: no file after 20 s"}
  ]
}
```

- `tMs`: the phone's wall clock (ms) right before `takePicture` was sent (the clock of the sync code's `epochMs`).
  `lat` / `lon` / `accuracyM` / `speedMps`: the GPS fix that triggered the shot (also written to `gpsInfo`; it had
  just arrived, ≲ 1 s old). `bearing`: the travel bearing (the app's track heading, else the fix's bearing), degrees
  from north, may be null.
- `ok` false: `error` says why (the OSC error code and message, `timeout`, `io`: the Wi-Fi gone). `error` on an ok
  shot: the photo was taken but gpsInfo was refused.
- `camera`: from `/osc/info` (when the ride starts, or with the first shot if connected later). `syncs`: only when
  "Đồng bộ lại" was used. Shots are saved as they finish; a shot still running when the ride ends is not recorded.

### 4.5 Processing (`camera360.py`, as built)

- A ride with `control` `"theta-osc"`: each camera photo whose file name equals a shot's `fileName` (else the last
  part of its `fileUrl`), case-insensitive, is a **shot photo**: position, time (`tMs` + the syncs' median
  `gpsMinusPhoneMs`, 0 without), travel bearing and speed come from the shot; `positionFrom` "theta shot", no
  `elapsedNanos` (so prepare.py keeps that position: "gps fix" in photos.json). Shot photos are all kept (the phone
  spaced them); `--every-m` applies to the other frames. The photo's EXIF GPS, when present, is compared: over 30 m
  from the shot → a warning listing the files (the shot's position is used). A shot photo needs no EXIF time.
- Clock: no sync code is looked for; `clock.source` "theta shots", offset = median of (shot time − EXIF time) over the
  shot photos (absorbs the shutter delay and an EXIF time read in the wrong zone), used for the files without a shot
  (time matching as in section 3). No shot matches a file: a warning, then section 3 unchanged (the camera's clock
  as is is then usually close: the phone set it).
- `camera360.json` `theta`: `shots`, `shotsWithFile`, `matched`, `failedShots`, `shotsWithoutFile`,
  `filesWithoutShot`, `exifGpsChecked`, `exifGpsFar`. Items: `camera360.shot` {`tMs`, `cmdId`, `exifGpsM`}.
- Tests: `scripts/tests/test_camera360_theta.py` (synthetic shots and equirectangular `R00100NN.JPG`).

### 4.6 Models, and what is not verified

- Built from the OSC spec and RICOH THETA API v2.1 only: **no Theta was available**. Verified: the client against a
  fake OSC server (request bodies, polling, errors, timeouts), the trigger logic, the processing on synthetic files,
  the Android build. Not verified on a real camera / phone: the Wi-Fi specifier dialog and reconnects on real
  phones (some vendors may drop a local-only network in the background), the shot-to-shot time of each model,
  whether each model writes gpsInfo into the EXIF exactly as set, which best-effort options each model accepts.
- **Shot-to-shot time**: a 360 still takes the camera a few seconds to process (longer on the X at full resolution,
  and with RAW on the Z1). At 8 m and 30 km/h (≈ 1 s per trigger) most triggers are skipped: ride slower or set a
  larger spacing; the Bỏ qua count shows it.
- Theta S / SC: API v2.0 (sessions): refused. SC2 / V / Z1 / X: API v2.1. Client mode (camera on a router, digest
  auth) and Bluetooth control: not handled. Theta X: its access point must be on in its own menu. An owner may have
  changed the Wi-Fi password: type it in the Mật khẩu field.
- `offDelay` / `sleepDelay` 65535 is the OSC spec's "never"; a model may accept only its own list of values
  (`offDelaySupport` / `sleepDelaySupport`): a refusal is noted at connect, and the camera may then sleep at a long
  stop.
- Front: the Theta's equirectangular centre is the camera's own reference direction; from photos 8 m apart the
  motion estimate may fail: use `--front-deg` (SKILL.md).
- "Tải ảnh từ camera" (downloading the photos over the camera's Wi-Fi after the ride) is **not built**: the photos
  are imported from the camera over USB and matched by name.
