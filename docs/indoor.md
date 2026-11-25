# Indoor mode ("Trong nhà"): malls and other big buildings

Status: design for app 0.21; processing (`.claude/skills/process-sweep`, scripts/indoor.py) and web (web/src/indoor.ts)
built against it on 25 Nov. The shared contract between the three is this file.

## 1. What it is for

A walk through a mall (or market, airport, hospital…) on foot, phone in hand, to list every shop with its
**floor (level)** and **unit number**, at a position good enough to tell which unit it is. Outdoor rides use GPS
and the road map; indoors there is no usable GPS and no road, so:

| Outdoor | Indoor |
|---|---|
| GPS every second | GPS only at the doors (and outside) |
| street + house number | building + level + unit ("L2-15", "B1-03") |
| motorbike / car, mount | on foot, in the hand |
| road pack / Overpass roads | the building from OSM: footprint, name, levels, indoor features if mapped |

## 2. Position tracking (app)

**Pedestrian dead reckoning (PDR)**, not ARCore: ARCore takes the camera, and the photos must stay full-size
CameraX stills. The camera pipeline is unchanged.

- **Steps**: `Sensor.TYPE_STEP_DETECTOR` (fallback: peaks of the accelerometer magnitude). Step length from the
  rider's height if set, else 0.7 m, refined per walk: between two anchors the walked distance on the map /
  the steps counted gives the real step length.
- **Heading**: `TYPE_GAME_ROTATION_VECTOR` (gyro + accelerometer, no magnetometer: steel and escalators bend the
  compass indoors). Its zero is arbitrary, so it is **aligned** to the map: at the start from the compass outside
  the building (or the GPS course of the last outdoor metres), and again at every anchor (§3). The heading of the
  walk is the phone's yaw when held upright in front; the camera bearing (for photos) is the same yaw.
- **Floors**: `TYPE_PRESSURE`. Pressure relative to the start (~0.12 hPa per metre). A change of ≥ 2.5 m held
  for 5 s (escalator / lift / stairs) → "Lên tầng? / Xuống tầng?" with the guessed level; the walker confirms
  with one tap. The level picker is always on screen. Level names follow the building (B2, B1, G/0/1, 1, 2, …).
- **Inside the building**: the estimated position is kept inside the footprint (if it would leave, it slides
  along the wall).
- Everything raw is logged (`indoor.jsonl`, §5) so processing can recompute the whole walk better than live.

## 3. Re-positioning ("Tôi ở đây")

Dead reckoning drifts (typically 3–8 % of the distance walked). The walker fixes it whenever they know where
they are:

- **Long-press on the map** (or the ⌖ button, then tap): "I am here". Becomes an **anchor** `{lat, lon, level}`.
- **At an entrance outdoors** the GPS fix is an anchor by itself (accuracy ≤ 10 m, outside the footprint).
- **Optional second tap for direction**: drag from the anchor in the walking direction → heading re-aligned.
- Live: the position jumps to the anchor; the step length / heading offset are re-estimated from the last two
  anchors. The screen shows "đã đi 85 m từ lần định vị cuối" (distance since the last anchor) and turns amber
  above 60 m: time to re-position.
- Afterwards (processing): every stretch between two anchors is rotated and scaled so it starts and ends on its
  anchors (drift spread along the stretch), then kept inside the footprint. Photos get those positions.

## 4. The building (app, at setup)

At setup in indoor mode the app loads the building under / nearest to the walker from OpenStreetMap through the
existing Overpass client (`net/OverpassClient.kt`), and shows it on the setup and capture maps:

- the footprint (`building=*` way or multipolygon containing the point, else the nearest within 150 m),
  `name`, `building:levels`, `building:levels:underground`, `addr:*`, `level` / `min_level` / `max_level`;
- if mapped (Simple Indoor Tagging): `indoor=room|area|corridor` and `level` on them, shops (`shop`, `amenity`…
  nodes/areas) inside the footprint with `level`, `addr:unit`, `name` → drawn for the current level so the
  walker sees what is already mapped;
- the walker can pick another building on the map (tap) if the guess is wrong, or walk without one (no
  footprint constraint).
- Kept in the session (`session.building`, §5) so processing and the web have it without asking Overpass again.

## 5. Data (session export)

`session.json`:
```json
{"kind": "indoor", "target": "shops",
 "building": {"osm": "way/123456", "name": "Vincom Mega Mall Thảo Điền", "levels": 4, "underground": 2,
              "footprint": [[10.80, 106.74], ...], "outer": [[...]], "holes": [],
              "levels_seen": ["B1", "1", "2", "3"], "indoorMapped": true,
              "addr": {"addr:housenumber": "161", "addr:street": "Xa Lộ Hà Nội", "addr:city": "Thủ Đức"}},
 "indoor": {"stepLengthM": 0.71, "anchors": 7}}
```
`building.addr` = the building's own `addr:*` tags (the prefix may be left out): processing and the OSM export
take the shops' address from it, never from the phone. `outer` = every outer ring (a multipolygon), `footprint` the
largest; `holes` = inner rings (courtyards). Each photo item carries `elapsedNanos` (the same clock as `t` below):
processing places the photo on the recomputed walk by it.

`indoor.jsonl` (raw, one JSON per line, `t` = elapsedRealtimeNanos; in the export next to `items.jsonl`):
```json
{"t": 1, "type": "step"}
{"t": 2, "type": "yaw", "deg": 183.2}                      // aligned yaw, ~5 Hz
{"t": 3, "type": "pressure", "hPa": 1008.31}               // ~1 Hz
{"t": 4, "type": "anchor", "lat": 10.8, "lon": 106.7, "level": "2", "how": "tap|gps|entrance", "headingDeg": 90}
{"t": 5, "type": "level", "level": "2", "how": "confirmed|picked|guessed"}
{"t": 6, "type": "align", "offsetDeg": -12.5, "how": "compass|gps-course|drag"}
```
The live estimate is also written as the usual track (`track.jsonl` / `track.geojson` points) with
`source: "pdr"`, `level`, `sinceAnchorM`. Each photo item: `lat`, `lon` (live estimate), `level`,
`positionSource: "pdr"`, `sinceAnchorM`, `cameraBearing` (aligned yaw), `cameraBearingSource: "pdr-yaw"`.
A photo can also be a **directory board** (`trigger: "directory"`, a button on the capture screen): the mall's
list of shops per floor, read by the agent as a cross-check (§6).

What app 0.21 writes besides the above (all additive):
- Level names: the ground floor is `"1"` (OSM `level=0`), basements `"B1"`, `"B2"` (OSM -1, -2), as in Vietnam.
  Mapped features keep their OSM `level` value.
- `indoor.jsonl`: `step` lines carry `src` (`detector` | `accelerometer`); every GPS fix is logged as
  `{"t", "type": "gps", "lat", "lon", "acc", "spd", "brg"}` (indoors the GPS is **not** written to `track.jsonl`,
  which holds only the `pdr` points, ≤ 1 per second); an anchor that re-estimated the step length / heading from the
  last two anchors adds `{"type": "align", "how": "anchors", "offsetDeg", "stepLengthM"}`; the walk opens with
  `{"type": "level", "how": "picked"}` (the start level). The app writes `how: "tap"` and `"gps"` anchors only
  (`entrance` is left to processing). A drag direction is written as `align` / `drag` right after its `anchor`.
- `session.indoor` also has `start` (the setup position `{lat, lon, accuracyM, gps}`, the origin of the walk; it is
  the first anchor only when ≤ 10 m and outside the footprint), `steps`, `walkedM`, `offsetDeg`, `stepSource`,
  `barometer`.
- `session.building` also has `minLevel`, `maxLevel` and `features`: what is mapped inside
  (`[{osm, kind: shop|room|area|corridor, name, level, unit, tag: "shop=clothes", geometry: [[lat, lon], …]}]`).
  `building` is `null` when the walker went without one.
- Photo items also have `gpsLat` / `gpsLon` / `gpsAccuracyM` (the raw GPS, untrusted), `accuracyM` = the live
  uncertainty, `building` (osm id), `speedMps` from the steps, and a facade point 4 m ahead (`facadeSource:
  "indoor"`); the road fields (`wayId`, `roadName`, `travel`, `sideOfTravel`, `sideOfWay`) are null. The manifest
  has `indoorFile: "indoor.jsonl"`. An indoor walk is never cut per street at upload.

## 6. Processing and output

- Positions recomputed from `indoor.jsonl` (§3), kept inside the footprint; uncertainty grows with the distance
  from the nearest anchor (≈ 5 % of it, at least 3 m). The yaw in the log is taken as already aligned (the `align`
  events are not re-applied); a stretch is rotated / scaled only when its two anchors are ≥ 3 m apart, the scale is
  0.5–2 and it is not a loop, else translated with its end gap spread along it. Points within 15 m walked of an
  anchor outside the footprint (entrance) are not clamped. photos.json keeps the phone's estimate as
  `livePosition`. Without `indoor.jsonl` (or without an anchor in it) the live positions are used.
- places.json: each place gets `level` and `unit` ("L2-15" as read), shops seen on directory boards go into
  `directory: [{level, unit, name, photo}]`; a place matching a directory entry (same level and unit, or a similar
  name on the same level) is confirmed (a note, `place.directory`), a directory entry with no place becomes a
  "visit" task (with `level`, `unit`).
- result.json (additive to `poi-result/1`): `building` (session.building), `levels` (lowest first), `trackLevels`
  (one per track point; the track is the recomputed walk), shots `[index, lat, lon, bearing, level]`, `directory`;
  each place `indoor: true`, `level`, `levelFrom` (`read` | `walk`), `unit`, `building {osm, name, addr, address}`,
  `directory`, fields `level` (source `walk` when not read) and `unit`; no street field.
- OSM export: shop nodes with numeric `level=*` (0 = ground: a building naming a "G"/"Trệt" floor has "1" above it, otherwise "1" is the ground floor; basements B1 = -1) plus `level:ref=*` (the name as written) and `addr:unit=*` (Simple Indoor Tagging), no `addr:street` from the
  phone (the building's address applies), `name` etc. as outdoors.
- Web: the building footprint on the map, a level switcher (chips; shows one level's places, its track and
  photos; opens on the level with the most places), level and unit fields in the review panel; the CSV / GeoJSON /
  KML / Shapefile / Excel exports have `level`, `unit`, `building` columns.
