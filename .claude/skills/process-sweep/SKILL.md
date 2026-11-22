---
name: process-sweep
description: Process a street sweep received from the POI Capture app (exported zip, unzipped folder, or a session pulled from the phone) into one folder per place, with photos, place.json, Google Maps review links and a review page, then enrich each place from Google Maps (address, phone, opening hours, website). Use when the user asks to process, extract places / POIs from, review or enrich a sweep or session zip.
---

# Process a received street sweep

Turns a sweep into reviewable places: the agent pipeline of `docs/handoff.md` (steps 3–5, a simple step 4), done with
your own vision on contact sheets, plus a Google Maps lookup for what the road cannot see.

Scripts are in `scripts/` next to this file (`<skill>` below). They need Python 3 with Pillow (use `python`, not
`python -I`: Pillow lives in the user site-packages). Keep the work folder out of the repo, e.g. in the scratchpad.
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
already grouped: say so and stop unless the user wants the enrichment only.

## 2. Read the contact sheets

Read every `<work>/sheets/sheet_NNN.jpg` (4 photos each, labelled `#idx`, in riding order). Do not skip sheets; a
sign is usually on 3–14 consecutive photos. What to read depends on the ride's target (prepare.py prints it; see
"Reading by target" below). For each **business** (shop, cafe, restaurant, office, showroom; not traffic signs, which
are never businesses (a signs ride lists them in `"signs"`), nor building names alone, banners, parking signs) note:

- `name` as written on the sign (keep Vietnamese diacritics; mark unreadable parts with "…" and say why in `note`),
- `category` (cafe, restaurant, pharmacy, grocery, fashion, bank, office, …),
- `photos`: `[first, last]` index it appears on, `cover`: the photo where it is clearest and most central,
- `sightings`: its sign centre `x` (0 = left edge, 1 = right) on 2–3 photos spread over its range (first part, cover,
  last part): these give the position by triangulation; one sighting only = rough position,
- `confidence`: `high` (name fully readable) or `low`, and `signText` (the main text as read),
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
("106", "45/2A"). Use it to find the plates, not as the answer: it misses small or angled plates and may read a
price or a date as a number; read every number yourself on the full-size photo.

It sets the least to read, not the most: a shops ride whose photos show plates may still get `"addresses"`.

- **shops**: the businesses, as above.
- **addresses** (shops + house numbers; photos about twice as dense): the businesses, plus **every readable
  house-number plate**, shop or not, in `"addresses"`: `houseNumber` as written ("106", "12/3", "45A"), `street`
  (from the plate; leave it out when the plate has none: the ride's road is used, marked for the reviewer), `unit`
  (alley / lane / unit: "Hẻm 12", "Kiệt 5"), `photos`, `cover`, `sightings` (the plate's centre, 2–3 photos),
  `confidence`, `placeRef` (the `name` of the place in `"places"` it is on, when there is one), `note`. Crop the
  full-size photo to read a plate; a number only guessed is not written. `agentPin` with the facade snap works as for
  places.
- **signs** (the camera looked **forward**, along the road): every **traffic sign** in `"signs"`: `code` (QCVN
  41:2019: `P.xxx` prohibitory, `W.xxx` danger, `R.xxx` mandatory, `I.xxx` indication, `S.xxx` supplementary plate,
  e.g. P.131a, P.127, W.207a, R.302a, I.423b), `label` (its name in Vietnamese: "Cấm đỗ xe", "Tốc độ tối đa cho
  phép"), `group` (prohibitory | danger | mandatory | indication | supplementary; from the code letter when left out),
  `text` (words or numbers on the sign itself, e.g. "40"), `extra` (the plate under it: "6h-9h", "Trừ xe buýt"),
  `side` (`left` | `right` | `over` a gantry), `photos`, `cover`, `sightings`: its centre `x` on 2–3 photos as the rider
  approaches (it slides outwards and grows), `confidence`, `note`. `x` is measured from the forward view: the script
  turns it into an angle off the travel bearing. A supplementary plate is the `extra` of its sign, not a sign of its
  own. Shops are not required on a signs ride (the camera did not look at them).

```json
"addresses": [{"houseNumber": "106", "street": "Nguyễn Thanh Sơn", "unit": "", "photos": [33, 36], "cover": 34,
               "sightings": [{"photo": 34, "x": 0.41}, {"photo": 36, "x": 0.12}], "confidence": "high",
               "placeRef": "Bún Bò Huế Hương Cố Đô", "note": ""}],
"signs": [{"code": "P.131a", "label": "Cấm đỗ xe", "group": "prohibitory", "extra": "6h-9h", "photos": [120, 123],
           "cover": 122, "sightings": [{"photo": 120, "x": 0.66}, {"photo": 122, "x": 0.82}], "side": "right",
           "confidence": "high", "note": ""}]
```

build.py makes a folder for each (`NN_addr_…`, `NN_sign_…`, numbered after the places) with its best photos, and
result.json entries with `layer` `address` / `sign` (a sign: `sign {code, group, facingDeg, side}`, facing = the
travel bearing at the cover; a sign with one sighting is put `"defaultSignDistanceM"` (default 15) ahead along it,
so give two or three). The Google and website lookups skip them, and they get no visit tasks.

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
rejected ones stay in `candidates`, marked). The other results are kept as `candidates` for review. One search at a
time, 2–4 s apart, in its own agent-browser session (`AGENT_BROWSER_SESSION`, default `poi-<output folder>-<hash>`),
so runs on two rides in parallel do not share a tab.

Check the results before reporting them:
- a match for a `low` confidence place (name partly read) is a guess, possibly a good one ("CAF… ANH" → "CAT ANH TEA
  COFFEE" 57 m away): say it needs a look at the photo;
- `distanceM` over ~100 m: maybe another branch or another unit of the same building;
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
python <skill>/scripts/cloud.py push <out folder | zip>      # after step 5: the processed result (result.json + photos)
python <skill>/scripts/cloud.py processing                   # who is processing which ride
python <skill>/scripts/cloud.py release <session id>         # giving up without a result (frees it for another agent)
```

`push` zips a folder and sends it as `type=processed-result` with its session id and name from result.json; the
reviewer's web app lists them in its session list (downloaded when opened). An interrupted push resumes when run again.

## Google data: merged, marked, kept out of OpenStreetMap

The project decided (2026-11-15) to use Google Maps data in its place records, knowing Google's terms do not allow
it. `result.py` merges a matched Google place (name match >= 0.5, within 100 m) into each place's fields:

- the photos show the same value: kept, `"google": "match"`;
- the photos show a different value: the photo value is kept (newer), `"google": "differs"`, `"googleValue"`;
- the photos lack it: Google's value, `"source": "google"` (opening hours from one day only: `"sure": "low"`);
- the photos show it but unclearly (`"sure": "low"`, or "…" / "?" in it) and Google agrees with what was read (a
  phone read in full with at most 2 digits different, or read in parts sharing a run of 3+ digits; a name or street
  mostly the same): Google's value clarifies it, `"source": "google"`, `"clarified": true`, the reading kept in
  `"photoValue"` (e.g. "0904521417" read unsure, Google "0904523417").

Only that match (name >= 0.5, <= 100 m) counts: a farther or weaker Google result is not merged, does not lend its
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
