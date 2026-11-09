# Capture server contract

What the POI Capture app can use on the server side. Nothing here is implemented in this repository, and none of it is
required: without a server the contributor sends each session with **Send** (share sheet) and the road packs come from
this repository's GitHub release. Since 0.9 the server settings are not shown in the app; a team that runs a server
provisions them (upload URL, access token, API URL).

## 1. Upload: tus 1.0 (background upload)

Any server implementing the [tus resumable upload protocol 1.0](https://tus.io/protocols/resumable-upload) works, e.g.
[tusd](https://github.com/tus/tusd) (the reference server, open source) in front of private object storage
(S3-compatible), with its post-finish hook triggering the agent.

- `POST {uploadUrl}` creates an upload: `Upload-Length`, `Upload-Metadata`, `Tus-Resumable: 1.0.0`,
  `Authorization: Bearer {token}` → `201` + `Location`.
- `HEAD {location}` → `Upload-Offset` (`404` / `410` when expired: the app creates a new upload).
- Chunks: the app sends `POST {location}` with **`X-HTTP-Method-Override: PATCH`** (Android's HTTP client cannot send
  PATCH), `Content-Type: application/offset+octet-stream`, `Upload-Offset`, 4 MB per request → `204` + `Upload-Offset`.
  A `409` makes the app re-read the offset. The method-override header is part of the tus recommendations; it has been
  tested against a test stub, not yet against tusd.
- `Upload-Metadata` keys (base64 values as per tus): `filename`, `filetype` (`application/zip`), `sessionId`, `schema`
  (`poi-capture/3`; `road-capture/3` before app 0.8), `kind` (`sweep` / `poi`), `contributor`, `email` (typed at
  onboarding, not verified: group sessions by it, do not trust it for access), `sha256` (hex SHA-256 of the whole zip).
- The upload is the session zip described in [handoff.md](handoff.md). **Verify `sha256` after the upload completes.**
- The same `sessionId` can be uploaded again: after the contributor edited the session (moved a photo, pinned a place)
  the app uploads a new zip. Keep the latest one per `sessionId`.
- Wi-Fi only by default; the app retries with exponential backoff (from 30 s) and resumes from the server offset.
- The zip contains people's faces and licence plates **unblurred** (`manifest.privacy.blurred = false`): store it
  privately, blur before anything is published (handoff.md, pipeline step 0).

## 2. Offline road packs: a static `index.json`

Packs are static files built by `tools/roadpack/build_roadpacks.py` from OpenStreetMap (Geofabrik); another road
network can be packed the same way later. Default hosting: the GitHub release `roadpacks` of this repository, rebuilt
monthly by `.github/workflows/roadpacks.yml`. Any HTTPS host works; the app is given the URL of the catalogue
(Profile → Offline road data):

```jsonc
{ "format": 2, "generated": "ISO", "version": "2026-11-08", "attribution": "© OpenStreetMap contributors, ODbL 1.0",
  "packs": [ { "id": "ha-noi", "name": "Hà Nội", "version": "2026-11-08", "format": 2,
               "bytes": 13449537, "sha256": "…", "url": "ha-noi.zip",      // relative to index.json, or absolute
               "bbox": [20.56, 105.29, 21.39, 106.02],
               "outline": [[[21.385, 105.722], [21.383, 105.741], …]] } ] }  // optional: rings of [lat, lon], ~1 km
```

- One pack per province / city (`tools/roadpack/regions.json`, the 34 since the 2025 merger). The app picks the pack
  whose `outline` contains the contributor's position (boxes of neighbouring provinces overlap), downloads it
  (resuming with HTTP `Range`, so the host should support it), checks `sha256`, and offers an update when `version`
  changes.
- A pack of format 2 lists the cells it covers; format 1 (a box) covers every cell of its `bbox`. The app reads both.
- A server can serve its own catalogue at any URL, e.g. `{apiUrl}/roadpacks`, in this format.

## 3. Read API (optional): `{apiUrl}`

Same bearer token. Without it the app still uploads; it just cannot show what happened next.

### `GET {apiUrl}/sessions/{sessionId}`

```json
{ "state": "received" | "processing" | "done" | "failed",
  "poisFound": 23,
  "recapture": [ { "lat": 21.0247, "lon": 105.852, "reason": "sign hidden by a tree" } ],
  "error": "…" }
```

`404` = not received yet. The app polls it every 30 s while **Sessions** is open and shows "Processed · 23 places found
· 4 to shoot again". A `failed` state is shown without the raw error (the contributor cannot act on it). `recapture` is
meant to feed a future "to shoot again" list; the app only counts it for now.

### `GET {apiUrl}/config`

The team's app config, fetched at every app start and with "Get from the server" in Profile. Today it holds the photo
kinds of **Place capture** (buttons shown while shooting, in shooting order). Photo kinds are off unless the
contributor turns them on or a server sends a list:

```json
{ "photoRoles": [
    { "id": "facade", "label": "Front", "hint": "Straight on, the whole shop (cover photo)", "cover": true, "advance": true },
    { "id": "sign",   "label": "Sign",  "hint": "Close enough to read the name and phone", "advance": true },
    { "id": "menu",   "label": "Menu" } ] }
```

- `id`: lowercase `a-z 0-9 _ -`, unique, stable (it is what the export carries as `photo.role`); `label` / `hint` are
  only shown, in the team's language. `cover`: the kind used as the place's cover photo (default: the first).
  `advance`: after one photo of this kind the app selects the next kind that has no photo yet.
- A valid config replaces the contributor's own list (read-only in Profile). `404` or an invalid document: the phone
  keeps its list (its own, or the built-in default: front, sign, side, inside, info, context).

### `GET {apiUrl}/coverage?bbox={south},{west},{north},{east}`

GeoJSON `FeatureCollection` of what the team already recorded in the box: `LineString` / `MultiLineString` for swept
streets, `Point` for photographed places (coordinates `[lon, lat]`). The app draws it in purple on **New**, next to the
phone's own sessions in green, so contributors go where nothing is recorded yet. Keep it light (thinned lines, ~10 m).
