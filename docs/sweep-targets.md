# Sweep targets: shops, house numbers, traffic signs

Status: design (2026-11-22), implemented in app 0.17, process-sweep, web app, server (metadata only).

## 1. Analysis (BA)

A street sweep was built for one thing: the shops along a street. The same ride can also collect **house numbers**
(the address of every building, shop or not) and **traffic signs** (no parking, no entry, speed limits ...), but each
asks something different of the camera:

| | Shops (today) | Shops + house numbers | Traffic signs |
|---|---|---|---|
| What is read | shop signs, their phone / hours / address | the same, plus every house-number plate, shop or not | each traffic sign: its type (QCVN 41 code), extra plate, where it stands, which way it faces |
| Camera | sideways, at the fronts | sideways | **forward**, along the road |
| Photo trigger | the scene slid 30-45 % of a frame | slid 18-25 % (about 2x denser): a plate is small and must be sharp on 2+ photos | every 8 m of riding, from the GPS (a forward camera sees no slide) |
| Photo size | settings (2560) | the largest the camera gives (4000) | settings |
| Speed limit | from the blur budget (8 px) | blur budget 4 px: about half the speed | from the trigger rate only, capped at 30 km/h |
| Who uses it | POI review, VMAP | POI review, address layer (OSM addr:*) | road-sign layer (OSM traffic_sign=*) |

Decisions:
- **One target per ride**, chosen on the New session screen before Start. Shops and house numbers share one setup
  (the second is a denser, slower shops ride); signs need the phone turned forward, so it cannot be done on the same
  ride. The default is the last choice.
- The target sets the **minimum** capture quality; it does not restrict reading. A shops ride may still be read for
  house numbers when its photos allow.
- The target travels with the ride end to end: session.json → upload metadata → result.json → the web list and review.
- Place capture is not changed by this. Its photo guide (front, sign, info board, business plate) moves out of the
  capture screen into the instructions: the capture screen keeps only the camera and its action buttons.

User stories:
1. As a rider I choose "Shops", "Shops + house numbers" or "Traffic signs" before starting, see what it means for me
   (spacing, speed, which way to point the phone), and the app captures accordingly.
2. As a rider on a signs ride I am told when the phone points sideways (it must look ahead), and never told the
   opposite.
3. As the agent I know from the ride what to read, and write the addresses and signs as features of their own.
4. As a reviewer I see the target of each ride in the list, and in a ride I switch between Places, Addresses and Signs,
   each with its own fields, and confirm / edit / reject them like places.
5. As the publisher I get addresses and signs in the GeoJSON / CSV with their layer, and in the OSM file as
   `addr:housenumber` + `addr:street` and `traffic_sign=VN:<code>` nodes.

## 2. Data

### Phone: session.json / manifest.json (`poi-capture/3`, additive)

```json
"session": { "kind": "sweep", "target": "shops" | "addresses" | "signs", ... }
```
On a signs ride (app 0.18+) each photo may also have `"signs": [{"code", "group", "label", "score", "box": [x0, y0,
x1, y1]}]` (box as fractions of the upright photo, score >= 0.3) from the on-phone detector (ml/signs, ONNX Runtime),
and `camera.signDetector` names the model; the live count shows photos with a score >= 0.5. A hint, never a reading.
Missing `target` (older apps, Place capture) = `"shops"`. `plan` gains `"target"` and `"trigger": "slide" | "distance"`.
On a signs ride each photo has `"cameraBearingSource": "forward"` and `cameraYawToTravelDeg` 0 (the camera looks
along the travel direction); `sideOfWay` is null.

### Upload metadata (server, `raw-session`)

`target` (string, same values) next to `kind`. The server stores metadata as given (no schema), so nothing changes
there besides the docs; the web list reads it.

### Agent: places.json (input to build.py, additive)

```json
{"street": "…", "defaultFacadeDistanceM": 12, "places": [ … ], "removals": [ … ],
 "addresses": [
   {"houseNumber": "106", "street": "Nguyễn Thanh Sơn", "unit": "Hẻm 12" , "photos": [33, 36], "cover": 34,
    "sightings": [{"photo": 34, "x": 0.41}], "confidence": "high", "placeRef": "Bún Bò Huế Hương Cố Đô", "note": ""}],
 "signs": [
   {"code": "P.131a", "label": "Cấm đỗ xe", "group": "prohibitory", "extra": "6h-9h", "photos": [120, 123], "cover": 122,
    "sightings": [{"photo": 121, "x": 0.82}], "side": "right", "confidence": "high", "note": ""}]}
```
`group`: prohibitory (P), danger (W), mandatory (R), indication (I), supplementary (S) (QCVN 41:2019). `side`: left |
right | over (gantry). `placeRef`: the shop this number is on, when there is one.
An address may also have (24 Nov 2026 rides): `source` (`plate` | `sign` (a shop sign's address line) | `painted` |
`other`), `oldNumber` (an old number on the same plate / gate: "Số cũ 7/12", an old-style plate beside the new one),
`altNumbers` (other numbers on the gate when it is unclear which is current); a number read only in part keeps "…" for
the unread digits ("1…", "…01") and is always `confidence: low`. `confidence` is `high` | `medium` | `low` (build.py
refuses other values). The ride may have `"streets": [{"fromPhoto", "toPhoto", "street"}]`: the street per stretch of
photos where it changes while the phone's `roadName` stays the same (overrides roadName for places and addresses
whose cover is in the stretch).
For positions (section 4) a sign may also have, per sighting, `w` / `h` (its width / height in the photo, as a fraction
of the photo's), and `lane` (gantry: 1 = rightmost lane, 2.5 = the line between lanes 2 and 3), `sizeM` or
`sizeFactor`; the ride has `"road"` (the cross-section per stretch of photos), `"signSizeFactor"`, `"maxSnapM"`,
`"signBoxPad"`. prepare.py copies the phone's boxes into photos.json `signHints`: `{code, group, label, score, x, w, h,
box}`.

### Agent → web: result.json (`poi-result/1`, additive)

- `session.target`.
- Every entry of `places` gets `"layer": "place" | "address" | "sign"` (missing = `"place"`).
- An address: `layer: "address"`, `name` "106 Nguyễn Thanh Sơn", `category: "address"`, fields `houseNumber`,
  `street`, `other` (unit / alley), `placeRef` when it belongs to a shop, and `numbering: {"source": "plate" |
  "sign" | "painted" | "other" | null, "oldNumber": "7/12" | null, "altNumbers": ["34"], "partial": false}` (also
  said in words in `note`; GeoJSON properties `houseNumber`, `numberSource`, `oldNumber`, `altNumbers`, `partial`;
  CSV columns `house number`, `number source`, `old number`, `other numbers`). A partial number must not be
  published as `addr:housenumber`.
- A sign: `layer: "sign"`, `name` its label ("Cấm đỗ xe"), `category: "traffic_sign"`, `sign: {"code", "group",
  "facingDeg", "side"}` (`facingDeg`: the direction a driver facing the sign drives, = the travel bearing when it
  was photographed), fields `signCode` (the code, editable), `signText`, `other` (extra plate).
- No Google enrichment for addresses and signs (nothing to match).

### Web: review and publish

- The ride list shows the target ("Shops", "Shops + numbers", "Signs").
- A ride with more than one layer shows layer chips (Places · Addresses · Signs, with counts) over the list and map;
  the map draws each layer with its own marker.
- The review panel shows the layer's fields: address = house number, street, other; sign = code, label (name), sign
  text, extra plate (other); the shop-only parts (Google, website, opening hours) are hidden for them.
- Publish: GeoJSON / CSV get a `layer` property (and `signCode` for signs). OSM: an address → node with
  `addr:housenumber`, `addr:street`; a sign → node with `traffic_sign=VN:<code>` (+ `direction=<facingDeg>`), only when
  the code was confirmed.

## 3. Sign boost (app 0.19)

A forward photo every 8 m left 22 of the 60 signs of the first signs ride (Đồng Văn Cống, 24 Nov) on a single photo:
too few sightings to triangulate, and the sign's extra plate often only readable on the last frame before it left
the view. So on a signs ride the phone also looks for signs on the camera preview (the same detector, on the 640 px
analysis frame, at most every 250 ms, not while the phone is hot):

- a sign seen (score >= 0.5) in the last 2 s: the distance counts 2.6x, a photo every ~3 m instead of 8 m
  (`signBoost: 2.6` on those photos; the frames log says `distance-boost`);
- a near sign (box >= 3.5 % of the frame wide) reaching the left / right tenth or the top of the frame: one photo
  at once, `trigger: "sign"`, at most every 1.5 s (the closest sharp view);
- `camera.signScan` counts the frames with a sign and the edge photos; the capture screen says "Sign ahead · more
  photos" while it lasts.

To check after a ride: signs placed from 1 photo vs 3+ (was 22 / 38 of 60), photos per km (was ~80).

## 4. Sign positions (process-sweep, 24 Nov)

Measured on the first signs ride (Đồng Văn Cống, 60 signs, a motorbike in the right lane of a divided road): forward
rays are nearly parallel, so triangulation left the signs 20-96 m uncertain along the road (median 31 m), 22 signs on
one photo were put a fixed 15 m ahead, median signs came out 2-5 m left of the rider although the median is past the
rider's lanes, gantry signs at the roadside, and `side` was not used.

Now (build.py `locate_sign`, `snap_sign`):

1. **Range from the sign's size.** QCVN 41:2019 art. 16: circle 0.70 m, triangle 0.70 m (side), octagon (R.122)
   0.60 m at size coefficient 1 (urban roads; 1.25 ordinary roads, 1.8 dual carriageways outside towns and gantry /
   cantilever signs on urban dual carriageways). A sign facing the camera is parallel to the image plane, so its
   distance along the camera axis is `Z = width / (2 tan(hFov/2) w)`, w = its width in the photo / the photo's width.
   w comes from the reader (`w`, or `h` for a sign cut at the side) or the phone detector's box at that x (a box of
   the sign's shape: width / height 0.75-1.45; the same code preferred), less the detector's margin: its boxes were
   ~12 px of 3264 too wide (6-14 px on 6 signs measured by hand). Check: the widths of 21 signs on two photos and the
   GPS distance between them give a real width of 0.83 m with the boxes as they are and 0.71 m with the margin off.
   Rays (5 deg each: the GPS travel bearing; the road's vanishing point sat within ~2 deg of the centre) and ranges
   (12 % and 6 px each) are solved together by weighted least squares; with 3+ ranges one more than 30 % (at least
   5 m) from the others' median is dropped. Rectangles (lane boards, indication signs) have no standard size: no
   range unless `sizeM`.
2. **Road layout** (`"road"` in places.json, written by the reader per stretch of photos): the rider's lanes (count,
   kinds or widths, from the right kerb), the rider's lane, a median or not, or measured offsets from the rider's
   track. It gives the lines where signs stand: right kerb + 1 m, the median's middle, the far kerb + 1 m, each lane's
   centre.
3. **Snap.** Each sign is moved across the road (its along-road position kept, in the frame of the nearest ride
   photo) onto the line of its `side`: right → right kerb, left → median (or the far kerb), over → its `lane` (else
   the middle of the carriageway). The estimate before is kept in `position.unsnapped` (and the result.json algorithm
   pin) and the move in `position.snap`; a move over 6 m is not made but flagged in the method. A sign without a
   range gets its along-road position from where its rays (8+ deg off the line) cross that line, when that is surer.

Result on the first ride (rebuilt in "Đồng Văn Cống 2026-11-24 16h44 (v2 positions)", with the layout and 6 widths
read by hand): along-road uncertainty median 31 → 5.6 m (max 96 m: two signs across a junction, flagged); 50 of 60
signs ranged, 17 of the 22 single-photo signs with a range (5 left: rectangles and gantry boards, placed from rays on
the layout line or 15 m ahead); left (median) signs at -4.9 m and right signs at +3.2 m after the snap; ranged
estimates before it at -6.2 m (median) and +3.2 m. Two signs moved more than 15 m (24 m): the old pin was behind the
camera on photos where the sign is still in view.

Open: one size for all circles (some speed signs looked larger than the sign above them); the layout was measured
with an assumed camera height and puts the median ~1 m nearer than the ranged median signs; the boards of one
gantry are placed independently (9.6-15 m ahead of the same photo).
