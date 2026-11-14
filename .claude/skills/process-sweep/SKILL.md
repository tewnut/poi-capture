---
name: process-sweep
description: Process a street sweep received from the POI Capture app (exported zip, unzipped folder, or a session pulled from the phone) into one folder per place, with photos, place.json, Google Maps review links and a review page, then enrich each place from Google Maps (address, phone, opening hours, website). Use when the user asks to process, extract places / POIs from, review or enrich a sweep or session zip.
---

# Process a received street sweep

Turns a sweep into reviewable places: the agent pipeline of `docs/handoff.md` (steps 3–5, a simple step 4), done with
your own vision on contact sheets, plus a Google Maps lookup for what the road cannot see.

Scripts are in `scripts/` next to this file (`<skill>` below). They need Python 3 with Pillow (use `python`, not
`python -I`: Pillow lives in the user site-packages). Keep the work folder out of the repo, e.g. in the scratchpad.

## 1. Prepare

```
python <skill>/scripts/prepare.py <session.zip | folder> <work>
```

Prints the session, the photo count and the roads. `kind` must be `sweep`; for `poi` (Place capture) the places are
already grouped: say so and stop unless the user wants the enrichment only.

## 2. Read the contact sheets

Read every `<work>/sheets/sheet_NNN.jpg` (4 photos each, labelled `#idx`, in riding order). Do not skip sheets; a
sign is usually on 3–14 consecutive photos. For each **business** (shop, cafe, restaurant, office, showroom; not
traffic signs, building names alone, banners, parking signs) note:

- `name` as written on the sign (keep Vietnamese diacritics; mark unreadable parts with "…" and say why in `note`),
- `category` (cafe, restaurant, pharmacy, grocery, fashion, bank, office, …),
- `photos`: `[first, last]` index it appears on, `cover`: the photo where it is clearest and most central,
- `sightings`: its sign centre `x` (0 = left edge, 1 = right) on 2–3 photos spread over its range (first part, cover,
  last part): these give the position by triangulation; one sighting only = rough position,
- `confidence`: `high` (name fully readable) or `low`, and `signText` (the main text as read),
- `best`: the 2 photos to keep (a place is often on 8–14 photos; the rest are dropped from its folder), and in
  `bestWhy` one line on why. Pick:
  1. the **cover**: the sign whole, sharp, as frontal as possible, not cut by the frame edge and not covered by a
     tree, pole, umbrella, vehicle or person;
  2. a **second view** that adds something: the whole storefront and entrance, the other end of a long frontage, or
     a second sign (opening hours, phone, a side sign); not a near-copy of the cover (at least 2 photos apart).
  Avoid blurry photos and photos with a face or a number plate close to the camera. Leave `best` out to let the script
  choose (sign nearest the middle, sharpest, 2 photos apart); for a `low` place choose by hand, the script cannot see
  what is covered.

When a name is only partly readable, open the full-size photo (`file` in `<work>/photos.json`) before giving up.
Estimate `defaultFacadeDistanceM` (camera to shopfronts) from the photos: ~8 m on a narrow street with shops on the
pavement, 20–35 m behind a service road or garden. Write `<work>/places.json`:

```json
{"street": "Mai Chí Thọ", "defaultFacadeDistanceM": 30, "places": [
  {"name": "Highlands Coffee", "category": "cafe", "signText": "HIGHLANDS COFFEE", "photos": [33, 45], "cover": 42,
   "sightings": [{"photo": 36, "x": 0.15}, {"photo": 42, "x": 0.68}, {"photo": 45, "x": 0.9}],
   "best": [42, 38], "bestWhy": "42: sign whole and central; 38: storefront with terrace and entrance",
   "confidence": "high", "note": ""}]}
```

`"bestPhotos": 3` at the top level keeps 3 per place instead of 2.

## 3. Build the place folders

```
python <skill>/scripts/build.py <work> <out> [--best N] [--keep-all]
```

`<out>` (ask where if the user has not said; default `~/Documents/poi-sweep-places/<session name>`) is emptied and
rebuilt (run step 4 again after a rebuild). One folder per place: only its best photos (`COVER_#NN_…`, `BEST2_#NN_…`;
`#NN` = position in the sweep; `--keep-all` also copies the others into `more/`), and `place.json` (name, category, position with
method and uncertainty, `googleMaps` review links for the place and the cover camera, `review.status = "todo"`, each
kept photo's camera position / bearing / speed / blur, `photoSelection` (how they were chosen, how many the place was
seen on) and `otherPhotos` (ids of the photos not kept)), plus `index.html` (review page: cover, thumbnails, map links),
`places.csv`, `places.geojson`, `README.txt`. It prints the camera-bearing correction first: sweeps whose bearing is
`"assumed"` (90° to travel) are corrected by the median of the phone's compass over the photos (first ride: −32°,
which took the median error against Google Maps from 40 to 27 m); `"bearingOffsetDeg"` in places.json overrides it.
If it says the compass was too scattered, positions are as weak as before: say so. Check the printed positions look sane (all on the shop side of the road,
in riding order); a triangulated point far from its neighbours usually means a wrong `x` or photo index.

## 4. Enrich from Google Maps

**Default: Google Maps in a browser** (needs the `agent-browser` CLI; no key):

```
python <skill>/scripts/enrich_browser.py <out> [--force] [--only 06_Starbucks,16_Highlands_Coffee]
```

For each place it opens Google Maps at the estimated position (zoom 18), searches the name read on the sign there,
takes the nearby results, picks the one whose name contains most of the sign's words (`nameMatch` >= 0.5) and is
within 300 m, opens it and reads the address, phone, website, plus code, opening hours, rating, open / closed and its
exact position. The other results are kept as `candidates` for review. One search at a time, 2–4 s apart.

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

## Terms: keep Google's data for review only

What comes from Google Maps stays in the separate `googleMaps` block, with its source and date, for a person to check
the place against. Google's terms of service do not allow automated reading of Google Maps pages (the browser route:
keep it to reviewing a few sweeps by hand, at a human pace, not bulk collection), and the Maps Platform terms do not allow using Google content to build or enrich another map or
place database, and limit storing it (place IDs may be kept; other content only briefly). Do not copy it into the
place's own fields (`address`, `phone`, `openingHours`, …) or into anything contributed to a map or sold. Those fields
are for what the photos show or a contributor confirms on site. Say this to the user when they ask to merge it in.
