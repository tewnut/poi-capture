# Capture server contract (app 0.6)

What the POI Capture app expects from the VMAP side. Nothing here is implemented in this repository: the app only
needs a tus endpoint to upload, and optionally a small read API. Configure it on the phone in **Hồ sơ → Máy chủ nhận
dữ liệu** (upload URL, access token, API URL, "cho phép tải bằng 4G").

## 1. Upload: tus 1.0 (required for background upload)

Any server implementing the [tus resumable upload protocol 1.0](https://tus.io/protocols/resumable-upload) works.
Recommended: [tusd](https://github.com/tus/tusd) (the reference server, open source) in front of private object
storage (S3-compatible), with its post-finish hook triggering the agent.

- `POST {uploadUrl}` creates an upload: `Upload-Length`, `Upload-Metadata`, `Tus-Resumable: 1.0.0`,
  `Authorization: Bearer {token}` → `201` + `Location`.
- `HEAD {location}` → `Upload-Offset` (`404`/`410` when expired: the app creates a new upload).
- Chunks: the app sends `POST {location}` with **`X-HTTP-Method-Override: PATCH`** (Android's HTTP client cannot send
  PATCH), `Content-Type: application/offset+octet-stream`, `Upload-Offset`, 4 MB per request → `204` + `Upload-Offset`.
  A `409` makes the app re-read the offset. **To check with the real server:** the method-override header is part of
  the tus protocol's recommendations, but it has only been tested here against a test stub, not against tusd.
- `Upload-Metadata` keys (base64 values as per tus): `filename`, `filetype` (`application/zip`), `sessionId`,
  `schema` (`poi-capture/3`; `road-capture/3` before app 0.8), `kind` (`sweep` / `poi`), `contributor`, `email`
  (typed at onboarding, not verified: group sessions by it, do not trust it for access), `sha256` (hex SHA-256 of the
  whole zip).
- The upload is the session zip described in [handoff.md](handoff.md). **Verify `sha256` after the upload completes.**
- The same `sessionId` can be uploaded again: after the contributor edited the session (moved a photo, reviewed a place)
  the app uploads a new zip. Keep the latest one per `sessionId`.
- Wi-Fi only by default; the app retries with exponential backoff (from 30 s) and resumes from the server offset.
- The zip contains people's faces and licence plates **unblurred** (`manifest.privacy.blurred = false`): store it
  privately, blur before anything is published (handoff.md, pipeline step 0).

## 2. Offline road packs: a static `index.json` (optional)

Packs are static files built by `tools/roadpack/build_roadpacks.py` (OpenStreetMap / Geofabrik today, VMAP's road
network later). Default hosting: the GitHub release `roadpacks` of this repository, rebuilt monthly by
`.github/workflows/roadpacks.yml`. Any HTTPS host works; the app is given the URL of the catalogue ("Nguồn gói" in Hồ sơ):

```jsonc
{ "format": 1, "generated": "ISO", "version": "2026-11-08", "attribution": "© OpenStreetMap contributors, ODbL 1.0",
  "packs": [ { "id": "ha-noi-noi-thanh", "name": "Hà Nội – nội thành", "version": "2026-11-08",
               "bytes": 3456789, "sha256": "…", "url": "ha-noi-noi-thanh.zip",      // relative to index.json, or absolute
               "bbox": [20.95, 105.75, 21.10, 105.90] } ] }
```

The app downloads over Wi-Fi (resuming with HTTP `Range`, so the host should support it), checks `sha256`, and offers
"Cập nhật" when `version` changes. When VMAP serves its own packs, `{apiUrl}/roadpacks` can simply return this document.

## 3. Read API (optional): `{apiUrl}`

Same bearer token. Without it the app still uploads; it just cannot show what happened next.

### `GET {apiUrl}/sessions/{sessionId}`

```json
{ "state": "received" | "processing" | "done" | "failed",
  "poisFound": 23,
  "recapture": [ { "lat": 21.0247, "lon": 105.852, "reason": "biển hiệu bị che" } ],
  "error": "…" }
```

`404` = not received yet. The app polls it every 30 s while "Đã ghi" is open and shows "Agent đã xử lý · 23 địa điểm ·
4 cần chụp lại". (`recapture` is meant to feed a future "cần chụp lại" list in the POI mode; the app only counts it.)

### `GET {apiUrl}/coverage?bbox={south},{west},{north},{east}`

GeoJSON `FeatureCollection` of what the team already recorded in the box: `LineString` / `MultiLineString` for swept
streets, `Point` for photographed places (coordinates `[lon, lat]`). The app draws it in purple on "Ghi mới", next to the
phone's own sessions in green, so contributors go where nothing is recorded yet. Keep it light (thinned lines, ~10 m).
