---
name: process-sweep
description: Process a street sweep received from the POI Capture app (exported zip, unzipped folder, or a session pulled from the phone) into one folder per place, with photos, place.json, Google Maps review links and a review page, then enrich each place from Google Maps (address, phone, opening hours, website). Use when the user asks to process, extract places / POIs from, review or enrich a sweep or session zip.
---

# Process a received street sweep

Turns a sweep into reviewable places: the agent pipeline of `docs/handoff.md` (steps 3–5, a simple step 4), done with
your own vision on contact sheets, plus a Google Maps lookup for what the road cannot see.

Scripts are in `scripts/` next to this file (`<skill>` below). They need Python 3 with Pillow (and opencv-python for
step 4b). Run them as **`python -E -P -X utf8 <skill>/scripts/<script>.py …`** (written `python …` below): `-E` ignores `PYTHON*` variables,
`-P` keeps the current folder and the script's folder off the import path (a `json.py` planted in a downloaded ride
is never imported; each script adds its own folder itself), and `-X utf8` prints Vietnamese on a cp1252 console.
Not `python -I`: it also drops the user site-packages, where Pillow and OpenCV usually live, so the scripts cannot
start. Your own helper scripts: in a folder of their own (never next to downloaded data), run the same way, data
paths as arguments. Keep the work folder out of the repo, e.g. in the scratchpad. Long runs (enrich_browser.py,
cloud.py push of a big ride) go in the background with the tool's background option, not with `&` (a shell job
dies with its shell, silently).
From Git Bash on Windows, a path argument with `[...]` in it can reach Python unconverted (`/c/Users/...`): the
scripts turn `/c/...` into `C:/...` and refuse any other `/...` path (it would land on the drive root). Tests:
`python -m unittest discover -s <skill>/scripts/tests`.

## 1. Prepare

```
python <skill>/scripts/prepare.py <session.zip | folder> <work>
```

Prints the session, the photo count and the roads, and how far the photo positions moved: the app stamps each photo
with the latest GPS fix (up to ~1 s old, ~6 m at 25 km/h), so prepare.py places it by time between the fixes in
`track.jsonl` (the phone's elapsed-time clock, which both carry; `positionFrom` and `fixLat`/`fixLon` in photos.json). `kind` must be `sweep`; for `poi` (Place capture) the places are
already grouped: say so and stop unless the user wants the enrichment only. On a house-number ride it also makes
the plate crop sheets `<work>/crops/plates_NN.jpg` (see "Reading by target"; `--no-crops` skips them).

## 2. Read the contact sheets

Read every `<work>/sheets/sheet_NNN.jpg` (4 photos each, labelled `#idx`, in riding order). Do not skip sheets; a
sign is usually on 3–14 consecutive photos. What to read depends on the ride's target (prepare.py prints it; see
"Reading by target" below). For each **business** (shop, cafe, restaurant, office, showroom; not traffic signs, which
are never businesses (a signs ride lists them in `"signs"`), nor building names alone, banners, parking signs) note:

- `name`: the business's own name as written on the sign, the brand part only (keep Vietnamese diacritics; mark
  unreadable parts with "…" and say why in `note`): "Rửa Xe ĐT MotorBike", not "Rửa Xe ĐT MotorBike - Rửa Xe, Thay
  Nhớt, Vá Vỏ"; "Nội Thất Zecchino", not "… - Công ty TNHH SX & TM Zecchino". The services, the legal company name
  and slogans go in `signText` / `fields.other`: a long name falls under the Google name match (0.5) against the
  same shop a few metres away,
- `category` (cafe, restaurant, pharmacy, grocery, fashion, bank, office, …),
- `photos`: `[first, last]` index it appears on, `cover`: the photo where it is clearest and most central,
- `sightings`: its sign centre `x` (0 = left edge, 1 = right) on 2–3 photos spread over its range (first part, cover,
  last part): these give the position by triangulation; one sighting only = rough position,
- `confidence`: `high` (name fully readable), `medium` (all of it read, but one letter or digit is doubtful: a
  plate washed out, a word half behind a pole) or `low` (part of it not read, "…"), and `signText` (the main text
  as read). build.py refuses any other value. `medium` is kept as written and shown to the reviewer; only `low` makes
  a visit task ("name only partly read"),
- `status`: `open`, or `closed` / `for_rent` ("cho thuê", "sang nhượng") / `under_construction` / `gone` when the
  photos show it; these become removal tasks,
- `fields`: **everything readable** about it, each `{"value", "photo", "sure": high|medium|low}`: `phone`,
  `houseNumber`, `street`, `openingHours`, `website`, `signText`, `other` (services, prices, slogans). Crop the
  full-size photo (`file` in `<work>/photos.json`) for small print: addresses and phones are rarely readable on the
  sheets. Only what is written: the reading never fills from Google (result.py merges Google afterwards, marked).
- `best`: **1 to N** photos to keep, each `{"photo", "why"}`; the cover first: the sign whole, sharp, frontal, not cut
  by the frame edge or hidden by a tree, pole, umbrella, vehicle or person. Add a photo only if it adds something (the
  entrance, the other end of a long frontage, a second sign with hours or phone); one is fine when the rest repeat
  it. Avoid blurry photos and faces or number plates close to the camera. Left out: the script keeps 2 (sign nearest
  the middle, sharpest, 2 photos apart); for a `low` place choose by hand.
- `agentPin`: your suggested position, from the photos' evidence: `{"snap": "facade", "why"}` keeps the algorithm's
  position along the street and puts the pin on the shopfront line (`"distanceM"` to override the street's facade
  distance, e.g. 8 for a cart on the pavement), or `{"lat", "lon", "why"}`. Never copy Google's pin. On Nguyễn Thanh
  Sơn the facade snap halved the distance to Google's pins on 20 of 25 places (median 11.6 → 8.5 m).

Group sightings into places: the same sign sliding across consecutive photos is one place; a shop seen again after
others were passed (approach and pass-by, or the other side when hand-held) joins its first sightings. Two places
with the same words on the sign within ~30 m are probably one; the words alone never decide (the phone's OCR is
not used for sweeps).

Places no longer there go in `"removals"` at the top level: `{"name", "status": closed|for_rent|gone, "photo", "x",
"why", "inView": true|false}` (`inView` false when the facade was hidden: it becomes a verify task, not a removal).

Estimate `defaultFacadeDistanceM` (camera to shopfronts) from the photos: ~8 m on a narrow street with shops on the
pavement, 20–35 m behind a service road or garden. Write `<work>/places.json`:

```json
{"street": "Nguyễn Thanh Sơn", "defaultFacadeDistanceM": 12, "removals": [], "places": [
  {"name": "Bún Bò Huế Hương Cố Đô", "category": "restaurant", "status": "open", "confidence": "high",
   "photos": [33, 39], "cover": 38,
   "sightings": [{"photo": 36, "x": 0.43}, {"photo": 38, "x": 0.64}, {"photo": 39, "x": 0.52}],
   "best": [{"photo": 38, "why": "sign whole and the dining room"}, {"photo": 37, "why": "address and phone readable"}],
   "fields": {"phone": {"value": "0886673868", "photo": 37, "sure": "high"},
              "houseNumber": {"value": "106", "photo": 37, "sure": "high"},
              "street": {"value": "Nguyễn Thanh Sơn, P. Cát Lái, TP.HCM", "photo": 37, "sure": "high"}},
   "agentPin": {"snap": "facade", "why": "pin on the shopfront line"}, "note": ""}]}
```

### Reading by target

`session.target` (prepare.py prints it; missing = `shops`) says what the ride was captured for (docs/sweep-targets.md).
On a house-number ride the phone reads each photo for numbers (ML Kit): `plates` in photos.json lists what it found
("106", "45/2A"), `plateHints` the same with each one's box. Use them to find the plates, not as the answer: on the
24 Nov rides about half were wrong (prices "45K", vehicle plates "51B", phone fragments, dates, the "18" of "Đường
số 18" on every sign, pole codes "T76", white numbers painted on street trees, a T-shirt) and the small gate plates
(brass, dark, on pillars) were mostly missed; read every number yourself.
**Crop sheets** (`<skill>/scripts/crops.py`; the contact sheets show a plate ~10 px high, too small to read):
prepare.py already makes `<work>/crops/plates_NN.jpg` on a house-number ride: a numbered full-resolution tile around
each hint (`plates.json`: tile -> photo, box, hint). For what the phone missed, `crops.py band <work> [--from N --to
M] [--y 0.40,0.80]` shows the door / gate / kerb band of each photo at 1400 px wide with rulers in photo fractions
(read `x` for sightings off them), and `crops.py boxes <work> 38:0.55,0.40,0.80,0.60 …` crops any box (fractions of
the photo) at full resolution, many in one montage: no hand-cropping.
On a traffic-signs ride (app 0.18+) the phone runs a sign detector on each photo (ml/signs): `signHints` in photos.json
lists `{code, group, label, score, x, w, h, box}` (x = box centre, w / h = its width / height as fractions of the
photo's; build.py ranges a sign from w). Start with those photos, but read every sign yourself: about half the boxes are shop
letters, logos or flags, the code is right on only about a third of the real signs, and it has no class for the blue
pedestrian crossing (I.423), keep left, give way or most information signs. It also misses signs cut by the frame.

It sets the least to read, not the most: a shops ride whose photos show plates may still get `"addresses"`.

- **shops**: the businesses, as above.
- **addresses** (shops + house numbers; photos about twice as dense): the businesses, plus **every readable
  house-number plate**, shop or not, in `"addresses"`: `houseNumber` as written ("106", "12/3", "45A"), `street`
  (from the plate; leave it out when the plate has none: the ride's road is used, marked for the reviewer), `unit`
  (alley / lane / unit: "Hẻm 12", "Kiệt 5"), `photos`, `cover`, `sightings` (the plate's centre, 2–3 photos),
  `confidence`, `placeRef` (the `name` of the place in `"places"` it is on, when there is one), `note`, and:
  - `source`: where the number was read: `plate` (a house-number plate), `sign` (the address line of a shop sign or
    awning: weaker, the sign may be old or reused), `painted` (painted on a wall, pillar or gate), `other`. A number
    on a shop sign is listed too (also as the shop's `fields.houseNumber`): on many streets most numbers come only
    from signs. Numbers painted on trees and poles are not house numbers.
  - read only in part: write what was read with "…" for the rest ("1…", "…01", "19…") and `confidence: low` (build.py
    lowers it, and the reviewer sees "read only in part"); a number not read at all is not written; nothing guessed.
  - two numbers on one gate or plate: `houseNumber` is the current one (the blue plate of the new numbering, the
    number not marked "số cũ"), `oldNumber` the old one ("Số cũ 7/12", a black old-style plate next to it);
    `altNumbers: ["34"]` when it is not clear which is current (say why in `note`).
  - `confidence`: `high`, `medium` (one digit doubtful) or `low`, as for places.
  `agentPin` with the facade snap works as for places.
- **`"streets"`** (ride level, any target): where the street changes along the ride while the phone's `roadName`
  stays the same (the ride turns into another street; one road changes name: Thích Mật Thể becomes "Đường 16" after
  no. 13): `[{"fromPhoto": 0, "toPhoto": 30, "street": "Đường Số 7"}, {"fromPhoto": 31, "toPhoto": 41, "street":
  "Thích Mật Thể"}]`. A place or address whose cover photo is in a stretch gets that street (its `street` from a
  plate or sign still wins); `streetFrom` says "streets in places.json".
- **signs** (the camera looked **forward**, along the road): every **traffic sign** in `"signs"`: `code` (QCVN
  41:2019: `P.xxx` prohibitory, `W.xxx` danger, `R.xxx` mandatory, `I.xxx` indication, `S.xxx` supplementary plate,
  e.g. P.131a, P.127, W.207a, R.302a, I.423b), `label` (its name in Vietnamese: "Cấm đỗ xe", "Tốc độ tối đa cho
  phép"), `group` (prohibitory | danger | mandatory | indication | supplementary; from the code letter when left out),
  `text` (words or numbers on the sign itself, e.g. "40"), `extra` (the plate under it: "6h-9h", "Trừ xe buýt"),
  `side` (`left` | `right` | `over` a gantry), `photos`, `cover`, `sightings`: its centre `x` on 2–3 photos as the rider
  approaches (it slides outwards and grows), `confidence`, `note`. `x` is measured from the forward view: the script
  turns it into an angle off the travel bearing. A supplementary plate is the `extra` of its sign, not a sign of its
  own. Shops are not required on a signs ride (the camera did not look at them).
  - **Range** (how far ahead): a circle, triangle or octagon sign has a standard size, so its width in the photo gives
    its distance. build.py takes it from the phone's box at the sighting's x; give `w` on a sighting yourself (the
    sign's width / the photo's width, measured on the full-size photo: crop it) when there is no box there, the box
    is wrong (two signs in one box, a lane board, a sign cut by the frame) or it gave a position that does not fit
    the photos; `h` (its height / the photo's height) instead when the sign is cut at the side. One sighting with a
    width is enough; give it on the nearest sharp photo. Sizes (QCVN 41:2019, coefficient 1, urban roads): circle
    0.70 m, triangle 0.70 m (side), octagon 0.60 m; a gantry sign 1.8x. `"signSizeFactor"` for the ride (1.25 on an
    ordinary road outside towns), `sizeFactor` or `sizeM` (its real width in metres) per sign; a rectangle (lane
    board R.4xx, indication I.xxx) has no standard size: no range unless `sizeM` is given.
  - `lane` (gantry signs): the lane it hangs over, 1 = the rightmost of the rider's direction; 2.5 = over the line
    between lanes 2 and 3.
  - **`"road"`** (ride level): the cross-section, per stretch of photos, from looking at the road: count the lanes of
    the rider's direction, which one the rider is in, whether a median divides the road. build.py moves each sign
    across the road onto the line of its `side` (right kerb; median, or the far kerb without one; the centre of its
    `lane`), keeping its along-road position, and keeps the estimate before that in `position.unsnapped`. A move over
    `"maxSnapM"` (default 6 m) is not made but flagged in the method ("NOT snapped ... check": wrong side, or a sign
    on a cross street). A sign without a range (a lane board) also gets its along-road position from where its
    rays cross that line. Leave junctions, turns and side roads out of the stretches (no snap there).
    `lanes`: a count (3.5 m car lanes) or a list from the right kerb of kinds (`"car"` 3.5, `"moto"` 2.75) or widths in
    metres; `riderLane` (1 = rightmost, default 1); `divider` (true: a median on the left); `medianWidthM` (default
    1; median signs stand in its middle); `oppositeLanes` (no divider: the other direction's lanes, default as many);
    `signSetbackM` (a roadside pole outside the kerb, default 1). Measured distances from the rider's track win over
    the lanes: `rightKerbOffsetM`, `medianOffsetM` (to the median's kerb), `leftKerbOffsetM`. Measure them on a
    photo when you can (where the edge line, a lane line and the median meet the bottom rows: depth = camera
    height x focal / rows below the horizon, the camera ~1.3 m up on a motorbike) and say how in `note`.

```json
"addresses": [{"houseNumber": "106", "street": "Nguyễn Thanh Sơn", "unit": "", "photos": [33, 36], "cover": 34,
               "sightings": [{"photo": 34, "x": 0.41}, {"photo": 36, "x": 0.12}], "confidence": "high",
               "source": "sign", "placeRef": "Bún Bò Huế Hương Cố Đô", "note": ""},
              {"houseNumber": "103", "oldNumber": "7/12", "source": "plate", "photos": [30, 31], "cover": 31,
               "sightings": [{"photo": 31, "x": 0.55}], "confidence": "high", "note": "plate: 103, số cũ 7/12"},
              {"houseNumber": "1…", "source": "plate", "photos": [40, 40], "cover": 40,
               "sightings": [{"photo": 40, "x": 0.97}], "confidence": "low", "note": "cut by the frame edge"}],
"streets": [{"fromPhoto": 0, "toPhoto": 30, "street": "Đường Số 7"}, {"fromPhoto": 31, "toPhoto": 41, "street": "Thích Mật Thể"}],
"signs": [{"code": "P.131a", "label": "Cấm đỗ xe", "group": "prohibitory", "extra": "6h-9h", "photos": [120, 123],
           "cover": 122, "sightings": [{"photo": 120, "x": 0.66}, {"photo": 122, "x": 0.82, "w": 0.045}], "side": "right",
           "confidence": "high", "note": ""},
          {"code": "R.412", "label": "Làn đường dành riêng cho xe ô tô", "photos": [227, 233], "cover": 233,
           "sightings": [{"photo": 233, "x": 0.07}], "side": "over", "lane": 4, "confidence": "high", "note": ""}],
"road": [{"fromPhoto": 4, "toPhoto": 140, "lanes": [3.2, 3.2], "riderLane": 1, "divider": true,
          "rightKerbOffsetM": 2.2, "medianOffsetM": 4.4, "note": "measured on #11: edge line 2.0 m right, barrier 4.4 m left"},
         {"fromPhoto": 222, "toPhoto": 244, "lanes": ["moto", "car", "car", "car"], "riderLane": 1, "divider": true}]
```

build.py makes a folder for each (`NN_addr_…`, `NN_sign_…`, numbered after the places) with its best photos, and
result.json entries with `layer` `address` / `sign` (an address: `numbering {source, oldNumber, altNumbers,
partial}`, also said in words in its `note`, and in places.geojson / places.csv; a sign: `sign {code, group, facingDeg, side}`, facing = the
travel bearing at the cover; its position from the ranges and rays together, then snapped onto the road layout; a
sign with neither a range nor two rays is put `"defaultSignDistanceM"` (default 15) ahead along its ray, so give two
or three sightings and a width). The Google and website lookups skip them, and they get no visit tasks.

### Long rides: several readers

A house-number ride is read on full-size crops and takes about 3x the context of a shops ride: beyond ~100 photos,
split the reading across reader subagents by photo range, with a few photos of overlap so a sign or plate at a
boundary is whole in one of them (255 photos: 0-87, 84-173, 170-254). Give each reader this section of the skill,
the ride's target, `<work>` (photos.json, sheets, crops) and its range; it writes `<work>/parts/places_<from>-<to>.json`
in the places.json format (ride-level `street` / `defaultFacadeDistanceM` / `road` / `streets` for its range). A
reader lists everything in its range, also what is cut at its edges, and follows a place that starts in its range to
its last photo even past the range end; the overlap is read twice on purpose. Then merge:

```
python -E -P -X utf8 <skill>/scripts/merge_places.py <work>/places.json <work>/parts/places_0-87.json <work>/parts/places_84-173.json …
```

It joins the parts and merges the same place (same name, one inside the other, photo ranges overlapping or within 3
photos), the same address (same number) and the same sign (same code) read by two readers: one entry over both
ranges with every sighting, the surer field readings, both notes, the lower confidence. It prints each merge and the
pairs to check by hand (the same name far apart: a chain twice, or approach and pass-by; similar names on the same
photos): fix those in places.json, then build. `--dry-run` prints without writing.

## 3. Build the place folders

```
python <skill>/scripts/build.py <work> <out> [--best N] [--keep-all]
```

`<out>` (ask where if the user has not said; default `~/Documents/poi-sweep-places/<session name>`) is emptied and
rebuilt; a rebuild keeps each place's Google Maps result and website block (with its `WEB_*` images), matched by
folder and name, but not hand edits to place.json (put those in places.json). One folder per place: only its best photos (`COVER_#NN_…`, `BEST2_#NN_…`;
`#NN` = position in the sweep; `--keep-all` also copies the others into `more/`), and `place.json` (name, category, position with
method and uncertainty, `googleMaps` review links for the place and the cover camera, `review.status = "todo"`, each
kept photo's camera position / bearing / speed / blur, `photoSelection` (how they were chosen, how many the place was
seen on) and `otherPhotos` (ids of the photos not kept)), plus `index.html` (review page: cover, thumbnails, map links),
`places.csv`, `places.geojson`, `README.txt`. It prints the camera-bearing correction first: sweeps whose bearing is
`"assumed"` (90° to travel) are corrected by the median of the phone's compass over the photos (first ride: −32°,
which took the median error against Google Maps from 40 to 27 m); `"bearingOffsetDeg"` in places.json overrides it
and is then added to **every** photo's bearing, compass-measured ones too (for a ride whose compass read a few
degrees off). Each place's `street` is the road it was photographed from (the phone's `roadName`, OSM road within
30 m: the cover photo's, else the most common over its photos; `streetFrom` says which); the ride-level `"street"`
of places.json is only a last fallback, never when it names several roads ("A / B").
If it says the compass was too scattered, positions are as weak as before: say so. Check the printed positions look sane (all on the shop side of the road,
in riding order); a triangulated point far from its neighbours usually means a wrong `x` or photo index.

The folder also gets `result.json` (schema `poi-result/1`: places with their fields, the three pins
algorithm / Google / agent, photos, and the task list: visits for Place capture, removals), `sweep.json` (the ride
for the maps) and `removals.json`; `index.html` has a map, and the parent folder an `index.html` over all
sessions. `--doors <place capture session>` adds the doors captured on site for comparison. The web app (`web/`,
docs/pipeline.md) opens it from the server for review and publishing: push it with `cloud.py push` (below).

## 4. Enrich from Google Maps

**Default: Google Maps in a browser** (needs the `agent-browser` CLI; no key):

```
python <skill>/scripts/enrich_browser.py <out> [--force] [--only 06_Starbucks,16_Highlands_Coffee]
```

For each place it opens Google Maps at the estimated position (zoom 18), searches the name read on the sign there,
takes the nearby results, picks the one whose name contains most of the sign's words (`nameMatch` >= 0.5) and is
within 300 m, opens it and reads the address, phone, website, plus code, opening hours, rating, open / closed and its
exact position, then checks the 300 m limit again with that position (a list entry can point at another branch:
rejected ones stay in `candidates`, marked). When no name matches but the photos show a phone, a result within 100 m
with that phone is taken (`phoneMatch`: its list card, else the 2 nearest opened). Phones are compared in today's
form (+84 and the 2018 renumbering of 11-digit mobiles undone: 0168 → 038, 0122 → 077, …). The other results are
kept as `candidates` for review. Each line it prints says whether the result will be merged and why not. One search at a
time, 2–4 s apart, in its own agent-browser session (`AGENT_BROWSER_SESSION`, default `poi-<output folder>-<hash>`),
so runs on two rides in parallel do not share a tab.

Check the results before reporting them:
- a match for a `low` confidence place (name partly read) is a guess, possibly a good one ("CAF… ANH" → "CAT ANH TEA
  COFFEE" 57 m away): say it needs a look at the photo;
- `distanceM` over ~100 m: maybe another branch or another unit of the same building;
- result.py already refuses (does not merge, and says why in `google.merge` of result.json and in the visit task) a
  match whose house number is another than the one the photos show surely ("553" on the sign, Google "644 Nguyễn Thị
  Định"), and a match for a generic name (every word a kind of shop: "Mắt Kính", "Hớt Tóc Nam", "Phở", "Cơm Tấm")
  without the same phone or house number; it accepts a weak name match with the same phone. Over ~100 m with a
  generic name is almost always another shop;
- a match that is plainly another shop all the same: set `"notThisPlace": true` and `"note"` on the place.json's
  `googleMaps`, then `python <skill>/scripts/result.py <out>`; one refused above that you checked is this place
  (the shop moved, the sign keeps an old number): `"thisPlace": true` and `"note"`;
- `not found nearby`: look at `candidates` (a branch 900 m away is not this one). Places Google does not have nearby
  are the most valuable finds: list them.
- Google often lists only today's opening hours (`openingHours` one line, `hoursSummary` "closes at …").

**With a Places API key** (`GOOGLE_MAPS_API_KEY`, Places API (New) enabled), the same through the API:

```
python <skill>/scripts/enrich.py <out>
```

Without the browser or a key: for each place, search the web (WebSearch: `"<name>" <street> <district/city>`, Vietnamese and
English) and open the best result (Google Maps place page, the brand's store locator, Foody, the shop's Facebook
page). Record only what a source states, never guesses; write `<work>/manual.json` keyed by folder name, with the
source URL, and merge it:

```json
{"16_Highlands_Coffee": {"name": "Highlands Coffee Sun Avenue", "address": "…", "phone": "…",
  "openingHours": ["Thứ Hai: 07:00–22:00", "…"], "website": "…", "url": "<google maps or source link>",
  "lat": 10.785, "lon": 106.747, "source": "web search: <url>"}}
```
```
python <skill>/scripts/enrich.py <out> --from <work>/manual.json
```

Both rewrite `index.html`, `places.csv` and `README.txt` with the results. A result whose `distanceM` is over ~100 m
or `nameMatch` under ~0.5 is probably another branch or another shop: say so.

## 4b. Privacy: blur faces and number plates

Before the folder leaves the PC (cloud push, a zip for the web app, a publish), blur the people and vehicle plates in
its photos. Needs `opencv-python` (`pip install --user opencv-python`); the three models (OpenCV model zoo, MIT /
Apache 2.0) download once to `~/.cache/poi-capture/models`. `<check>` is a private folder **outside** `<out>` (it
keeps the unblurred originals and crops), e.g. `<work>/blurcheck`:

```
python <skill>/scripts/blur.py detect <out> <check>
```

Faces (YuNet) and plates on a detected vehicle with a high score are blurred without asking. The rest worth a look go
on crop sheets `<check>/plates_NN.jpg` (box outlined in red, labelled `bNNNN`): **look at every sheet** and write in
`<check>/decisions.json` `"keep"` the ids that are not plates (a shop sign, a house number, a phone number, a facade).
Everything else is blurred: when unsure, blur. Then:

```
python <skill>/scripts/blur.py apply <out> <check>
```

It pixelates from the originals (so it can be run again), writes `privacy` into each place.json and
`<check>/after_NN.jpg` (the blurred photos, 3 x 3) and `<check>/zoom_NN.jpg`: the kerb band (40-95 % of the height,
`--zoom-y 0.30,0.95` to change it) of every sweep photo once, 1400 px wide, 2 per sheet, with rulers in photo
fractions. Look over the after-sheets for faces and **every zoom sheet** for a readable plate it missed: a rear
plate of a motorbike parked at the kerb (~3 % of the photo wide) is readable there and invisible on the after-sheets
(on the 24 Nov rides 86 such misses were found only at full size). Add each to `"add"` (`{"file", "box": [x0, y0,
x1, y1] as fractions of the photo, "why"}`, read off the rulers), then apply again. An `add` applies to every copy of
that sweep photo (a photo kept by a shop and by the house number on it is in both folders; same `#NN_<photo id>` in
the file name), unless `"only": true`; an `add` whose file matches no photo stops apply. Plates
washed out by the sun or too far to read need nothing. After a rebuild (step 3) run detect again. `cloud.py push`
refuses a place folder that was not blurred. Website images (`WEB_*`) are public already and not touched.

## 5. Report

Open the folder (`explorer.exe <out>` on Windows) and tell the user: number of places, how many `low` confidence and
why (umbrellas, trees, distance), how many were found on Google Maps and how many not (likely new places, the most
valuable), positions triangulated vs single-photo, and the link to `index.html`.

## Cloud server (when the session came from it)

When the team uses the upload server (`server/README.md`), sessions arrive there instead of by adb. With
`POI_CLOUD_URL` (the tus endpoint; the team's is `https://poi-uploader.4hum.ai/files/`) and `POI_CLOUD_TOKEN` (a personal access key with Process rides, issued on the
admin page; the shared token is gone) set in the environment (never pass or print the token):

```
python <skill>/scripts/cloud.py list                         # raw sessions from phones (type raw-session)
python <skill>/scripts/cloud.py pull <id> <work>             # the session zip, for step 1; marks it "being processed by you"
python <skill>/scripts/cloud.py push <out folder | zip>      # after steps 4b and 5 (refuses unblurred photos): the processed result (result.json + photos)
python <skill>/scripts/cloud.py processing                   # who is processing which ride
python <skill>/scripts/cloud.py release <session id>         # giving up without a result (frees it for another agent)
```

`push` zips a folder and sends it as `type=processed-result` with its session id and name from result.json; the
reviewer's web app lists them in its session list (downloaded when opened). A slow or flaky link needs no retry
loop: push shrinks its chunks after a timeout (4 MB down to 256 KB, growing again when the link recovers), waits,
asks the server where the upload stands and goes on, and gives up only after 25 failures in a row without progress.
Run the same push again after that and it resumes from the server's offset (an unchanged folder's zip is kept in the
temp folder until the upload is complete). A big ride takes a while: run it in the background.

## Google data: merged, marked, kept out of OpenStreetMap

The project decided (2026-11-15) to use Google Maps data in its place records, knowing Google's terms do not allow
it. `result.py` merges a matched Google place (name match >= 0.5 or the same phone, within 100 m, no other house
number, a generic name only with the same phone or number: `google_check`) into each place's fields:

- the photos show the same value: kept, `"google": "match"`;
- the photos show a different value: the photo value is kept (newer), `"google": "differs"`, `"googleValue"`;
- the photos lack it: Google's value, `"source": "google"` (opening hours from one day only: `"sure": "low"`);
- the photos show it but unclearly (`"sure": "low"`, or "…" / "?" in it) and Google agrees with what was read (a
  phone read in full with at most 2 digits different, or read in parts sharing a run of 3+ digits; a name or street
  mostly the same): Google's value clarifies it, `"source": "google"`, `"clarified": true`, the reading kept in
  `"photoValue"` (e.g. "0904521417" read unsure, Google "0904523417").

Only that match (`google_check`; result.json's `google.merge` says `{"merged", "why"}`) counts: a farther or weaker Google result is not merged, does not lend its
website to enrich_web.py, and the place counts as **not** on Google Maps (a visit task, "nearest: …"). Google's
address is parsed for the first part that is "<number> <street>" or starts with Đường / Đ. / Phố, after building or
unit names ("tòa nhà One Verandah, 2 Bát Nàn, …" → 2, Bát Nàn); streets are compared without Đường / Đ. / Số and
the ward ("Đường Số 3, P. An Khánh" = "Đ. Số 3"). A street that neither the photos, the website nor Google give is
filled with the road the photos were taken on: `"source": "ride"`, `"sure": "medium"`, a suggestion the reviewer
accepts (a corner shop or a shop inside a building may be on another street); when another source names a different
street than the ride road, the field gets `"ride": "differs"`, `"rideValue"`.

**The place's own website comes first.** `python <skill>/scripts/enrich_web.py <out>` opens each place's website
(from the photos or Google; robots.txt not consulted, a project decision of 2026-11-16, unless `--respect-robots`;
Instagram / TikTok / link-in-bio / shop pages skipped) and reads its schema.org data
(phone, address, opening hours), title, description, `tel:` links, the labelled text most small sites have instead
("Địa chỉ:", "Điện thoại:", "Hotline:", "Giờ làm việc:", often in the footer; the "Liên hệ" page when phone or
address is missing) and up to 8 images: og:image, then the page's own photos at full size (`WEB_1.jpg` …, shown with
the place's photos). Its fields are merged like Google's (`"source": "website"`), before Google's, but only when the
website's address is this place's street or house number: a chain's site gives its head office, not this branch.
A product brand's site (castrol.com, honda.com.vn, dulux, michelin, …: the brand on a shop's sign) is skipped, and a
site whose address is in a province or city more than 120 km away (a Hà Nội company of the same name) is marked
`notThisPlace` with the reason (`"auto": true`): remove the mark if it is this shop after all.
Run it after the Google lookup (it needs Google's website when the photos show none).

**Then look at the website images** (a contact sheet of each place's `WEB_*`): keep the ones that show this place
(front, sign, inside, its logo, its menu or price list); drop stock photos, news-article pictures, other branches,
banners for promotions and theme leftovers by setting `"drop": "<why>"` on that entry of `website.images` in
place.json (result.py leaves them out). A site that is another business's (Google sometimes lists the brand on the sign,
or a neighbour's) gets `"notThisPlace": true` and the reason in `website.note`: neither its fields nor its images are used. When a page shows images only after JavaScript runs (a slider of `dummy.png` placeholders, an
empty gallery), the script already falls back to **jawb** (the local browser: `jawb open` + `jawb read --js`; set
`JAWB_DATA_DIR` to the running jawbd's data folder): sites that refuse scripts, fail, or give next to nothing are read
in Chrome (`"via": "jawb"`). Facebook pages are read through jawb too, but Facebook shows a login wall to a
logged-out browser: pass `--fb-profile <jawb profile logged in to Facebook>`; without it they are recorded as
`facebook: login needed`.

Google-sourced values are marked in the published GeoJSON / CSV (`fromGoogle`) and never written to the `.osm`
file: OpenStreetMap does not accept data copied from Google (edits get reverted, accounts blocked). Pins never come
from Google.

The comparison marks (`google`: `match` / `differs`, `googleValue`, `source`, `note`) are metadata for the review screen only: they are never written into a field's value or into the published data (tested in web/src/publish.test.ts).
