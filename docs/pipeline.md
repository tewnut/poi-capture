# POI pipeline: capture → agentic processing → confirm and publish

Three stages, each with one job and a file contract to the next:

```
 PHONE (capture)              AGENT (process)                    WEB APP (confirm, publish)
 ─────────────────            ───────────────────                ──────────────────────────
 Street sweep ── zip ──►      sweep pipeline   ── result ──►     review queue, 3 pins, fields
 Place capture ─ zip ──►      place pipeline   ── bundle ──►     confirm / edit / reject
        ▲                                                         publish (export)
        └──────────── task list (visit / verify removal) ◄────────┘
```

The two capture modes are **separate tasks** end to end: a sweep is not merged with place captures, and a place
capture is not checked against sweeps. Both are checked against Google Maps (for review only, see Google below).
They meet only in the web app's published data, and through the task list a sweep produces.

## 1. Capture (phone app)

- **Street sweep** (mounted or in hand): auto photos along a street, GPS, gyro + compass direction per photo.
  Finds what is on the street, fast (~6 s of riding per business on Nguyễn Thanh Sơn, 2026-11-15).
- **Place capture**: a contributor stands at one place and photographs sign, door, hours board; the photos are
  grouped by the place they tapped; the pin is the GPS at the door or where they dragged it.
- **Task list import**: the visit / verify-removal tasks from the web app (stage 3) as a map layer with the
  sweep photo of each, so a contributor can go and do them as Place captures.

## 2. Agentic processing

Runs on the exported zip; today in Claude Code (`.claude/skills/process-sweep`), later possibly as a server job.
Scripts do everything deterministic; the language model only reads and judges photos. Output: a **result bundle**
(`result.json` + the kept photos) that the web app imports.

### 2a. Street sweep

1. **Prepare**: unpack, contact sheets in riding order (`prepare.py`).
2. **Read the photos** (language model): every business on the photos, and for each one everything readable:
   name (with Vietnamese accents), category, sign text, phone(s), house number / address, opening hours, website,
   other text (services, branch, "since …"), and a **status**: open / closed / for rent ("cho thuê", "sang
   nhượng") / under construction. Each value records the photo it was read on (`#NN`) and how sure the reading is.
   The phone's OCR is not used for sweeps (it does not run on them, and is weak: docs below).
3. **Group** sightings into places (script + language model):
   - consecutive photos where the same sign slides across the frame (the main signal);
   - a shop seen again after others were passed (approach and pass-by, both sides when hand-held);
   - a strong text match (≥ 50 % distinctive words in common) within ~30 m as a joining signal only; text alone
     never decides (OCR grouping test: finds a quarter of same-place pairs, 2026-11-15).
4. **Best photos**: 1 to N per place, no near-duplicates. The cover (sign whole, sharp, frontal, unobstructed), then
   any further photo only if it adds something: the entrance, the other end of a long frontage, a second sign
   (hours, phone). One photo is fine when the others repeat it.
5. **Locate**: three pins per place, kept side by side:
   - **algorithm**: triangulation from 2–4 sightings, or one sighting at the assumed shop distance, with an
     uncertainty (`build.py`);
   - **Google**: the matching Google Maps result near it, if any (name match, distance);
   - **agent**: the agent's suggested position from its own evidence: the algorithm's pin moved to the shopfront
     the photos show (OSM building outline facing the street, the house number between its neighbours'), and a
     note on why. When Google's pin disagrees with the algorithm by more than the uncertainty, the agent says so
     and explains which it believes, but its suggestion is not copied from Google (see Google below).
   The reviewer picks or drags the final pin in the web app.
6. **Compare with Google**: found / changed (name, category or closed) / not found nearby; Google's details in a
   separate review block.
7. **Hand over**:
   - **visit tasks** (to Place capture): places new to Google, changed, or partly read; with position, cover photo,
     what to confirm;
   - **removal candidates**: places on Google (and later in our published data) along the swept stretch that the
     sweep shows closed, for rent, or gone. "Gone" only where the facade was clearly in view (camera side, not
     blocked by trucks or trees); otherwise it becomes a *verify* task, never a removal on its own.

### 2b. Place capture

1. Places are already grouped (the contributor's taps).
2. **Read the photos** (language model): the same fields as 2a.2, from the close-ups; the phone's OCR is only a
   hint to check (it got the name right for 9 of 22 places, and took neighbours' phones, 2026-11-15).
3. **Locate**: three pins as in 2a.5: **contributor** (door GPS or dragged pin) instead of algorithm, Google, agent.
4. **Compare with Google** as in 2a.6. No matching against sweeps.

## 3. Web app: confirm and publish

Vue + Leaflet, OpenStreetMap tiles (served over http, so the tile policy is met), deployable as static files
first.

- **Import** result bundles (and raw zips, to see a ride or a capture session).
- **Sessions**: list and filters (date, kind, mounted / in hand, street, status), all on one map.
- **Review queue** per place: photos, the three pins (algorithm or contributor / Google / agent) with sighting
  lines, every field with its source photo; the reviewer **confirms, edits or rejects** each field and picks or drags
  the final pin. Removal candidates are confirmed or turned into verify tasks.
- **Task list**: visit and verify-removal tasks from the sweeps, exported to the phone (file or QR).
- **Publish**: confirmed places, with the reviewer and date, to the target dataset (export format to decide:
  GeoJSON / CSV / API).
- **Accuracy dashboard**: per ride and app version, distance from the algorithm's pin to the confirmed pin. Once
  enough places are confirmed, this replaces Google pins as the yardstick.

## Google

Project decision (2026-11-15): Google Maps data is used in place records, against Google's terms, as a choice the
project owns. Matched fields are compared (`match` / `differs`, the photo wins when they differ, as it is newer);
fields the photos lack are filled from Google with `source: "google"`; a photo reading the agent was unsure of is
clarified by Google when Google agrees with what was read (`clarified: true`, `photoValue` kept). The place's own
website (enrich_web.py) is read first and merged the same way (`source: "website"`), when its address is this place's. These are marked in the GeoJSON / CSV export
(`fromGoogle`) and left out of the OpenStreetMap file, which must not contain Google data. Pins are never Google's.
The comparison marks (`google`: `match` / `differs`, `googleValue`, `source`, `note`) are metadata for the review screen only: they are never written into a field's value or into the published data (tested in web/src/publish.test.ts).

## Measured so far (2026-11-15, Thủ Đức, small samples)

- Sweep places vs Google pins: median 11–30 m by ride (36 places, 4 rides); hand-held as good as the best mounted.
- Tuning one global correction on Google pins did not hold up when checked on a ride left out: per-ride errors.
- Phone OCR: name right for 9/22 places; phone 4 right, 2 wrong (a neighbour's) of 17; not a data source.

## Status (2026-11-15)

| Stage | Built | Where |
|---|---|---|
| 2a sweep: read every business with its fields, status, 1–N best photos, agent pin, removals | yes | `process-sweep` skill: `build.py` (`places.json` format in SKILL.md) |
| 2a grouping extended (approach / pass-by, text as a joining signal) | as reading instructions | SKILL.md step 2 |
| 2a agent pin | `{"snap": "facade"}` or explicit lat/lon | `build.py` `agent_pin`; on Nguyễn Thanh Sơn 11.6 → 8.5 m median to Google pins (25 places) |
| 2a task list: visits, removals, verify removals | yes | `result.py` → `result.json` |
| 2b Place capture: read photos, contributor + agent pins | yes | `build_place.py` (session folder or export zip + the agent's readings) |
| Result bundle | `result.json` (`poi-result/1`) + photo folders | every processed folder |
| 3 web app (POI Capture Reviewer): import, sessions, map, review (fields, 3 pins, final pin), tasks, shareable links, publish GeoJSON / CSV / .osm | yes | `web/`, Cloudflare Pages `poi-capture-reviewer` (`.github/workflows/web.yml`) |
| Phone: import the task list as a map layer | no | next |
| OSM building outlines for the agent pin | no | next |
