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

### Agent → web: result.json (`poi-result/1`, additive)

- `session.target`.
- Every entry of `places` gets `"layer": "place" | "address" | "sign"` (missing = `"place"`).
- An address: `layer: "address"`, `name` "106 Nguyễn Thanh Sơn", `category: "address"`, fields `houseNumber`,
  `street`, `other` (unit / alley), `placeRef` when it belongs to a shop.
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
