# Camera 360: an external 360° camera with the phone's GPS

A 360° camera (Insta360 X3/X4/X5, Ricoh Theta, GoPro MAX, …) records the street; the phone records where it was.
The processing on the PC puts every camera frame on the phone's track by time, cuts it into ordinary views (front,
left, right) and from there the pipeline is the usual one (reading, blur, review). The camera needs no GPS of its
own; when it has one (GoPro MAX) its track is a fallback or a cross-check.

This file is the contract between the app (section 2) and the processing (section 3).

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
