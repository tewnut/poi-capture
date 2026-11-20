# POI pipeline: capture → agentic processing → human review → agentic VMAP edit

Four stages, each with one job and a file contract to the next:

```
 PHONE (capture)        AGENT (process)              WEB APP (human review)            AGENT (VMAP edit)
 ───────────────        ─────────────────            ──────────────────────            ─────────────────
 Street sweep ─ zip ─►  sweep pipeline  ─ result ─►  confirm / edit / reject  ─ project ─►  poi-contribution:
 Place capture ─ zip ─► place pipeline  ─ bundle ─►  final pin, photos, cover   (.zip)     fill missing fields
        ▲                                            publish (export)                      or create on VMAP
        └──────────── task list (visit / verify removal) ◄──┘
```

The two capture modes are **separate tasks** end to end: a sweep is not merged with place captures, and a place
capture is not checked against sweeps. Both are checked against Google Maps (for review only, see Google below).
They meet only in the web app's published data, and through the task list a sweep produces.

## Moving the files: by hand or through the cloud server

By hand: the phone's Send / Save zip (or adb), the agent's output folder or zip imported into the web app. Or through
a shared **tus server** (resumable uploads, tus.io 1.0; `server/`, see its README). The team's server is a
Cloudflare Worker storing uploads in R2 (`server/worker/`), at `https://poi-capture-cloud.4hum-ai.workers.dev/files/`;
every request needs its bearer token, which is not in the repository. A self-hosted Node version is in `server/` too.

```
phone ── raw session zip, type=raw-session ──►  tus server  ──► agent: cloud.py list / pull <id>
                                                    ▲           ... process-sweep ...
web app ◄── From server: type=processed-result ─────┴────────── agent: cloud.py push <result folder>
```

- **Phone** (Settings → Upload server, or the optional onboarding step): endpoint, optional token (Keystore-encrypted
  on the phone, never in exports), Test connection (OPTIONS), upload each session when it ends (Wi-Fi only by
  default). Each session card shows not uploaded / waiting / uploading n % / uploaded / failed with Upload or Retry.
  The upload is the same zip as Send (`Exporter`), created with `Upload-Metadata` filename, filetype, type,
  sessionId, name, kind, createdAt, startedAt, photoCount, then sent in 2 MB PATCHes (as POST +
  `X-HTTP-Method-Override`); the upload URL and offset are kept per session, so it resumes after a dropped
  connection or an app restart (HEAD). Background work: WorkManager (`capture/UploadWork.kt`); never while a session
  is recording. Release builds need https.
- **Agent**: `.claude/skills/process-sweep/scripts/cloud.py` (`list`, `pull <id> <dir>`, `push <zip|folder>`),
  `POI_CLOUD_URL` (e.g. the Worker URL above) / `POI_CLOUD_TOKEN`.
- **Web app** (Settings → Cloud storage: server, preset to the Worker URL and changeable, + token, kept in this
  browser's localStorage only): *From server*
  lists the finished processed packages (`GET <endpoint>index.json`, the server's addition to tus), marks those
  already imported (same session id) or newer than the imported copy, downloads the picked ones (`GET <endpoint><id>`)
  and imports them like a dropped zip (`web/src/cloud.ts`).

## 1. Capture (phone app)

- **Street sweep** (mounted or in hand): auto photos along a street, GPS, gyro + compass direction per photo.
  Finds what is on the street, fast (~6 s of riding per business on Nguyễn Thanh Sơn, 2026-11-15).
- **Place capture**: a contributor stands at one place and photographs sign, door, hours board; the photos are
  grouped by the place they tapped; the pin is the GPS at the door or where they dragged it.
- **Task list import** (Settings → Tasks from the reviewer): the file the web app exports (`poi-tasks/1`, below),
  shown on the New session map (orange = visit, red = is it gone?) with a list by distance, Directions, Done / Skip.
  A Place capture finished within 25 m of an open task completes it, and the place records `taskId` / `taskType`
  so the processing can link the capture back. No sweep photo on the phone yet (the file carries none).

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
5. **Street**: each place's street is the road its photos were taken on (the phone's `roadName`), not the ride's;
   it becomes a street *suggestion* (`source: "ride"`) only when the photos, the website and Google give none.
6. **Locate**: three pins per place, kept side by side:
   - **algorithm**: triangulation from 2–4 sightings, or one sighting at the assumed shop distance, with an
     uncertainty (`build.py`);
   - **Google**: the matching Google Maps result near it, if any (name match, distance);
   - **agent**: the agent's suggested position from its own evidence: the algorithm's pin moved to the shopfront
     the photos show (OSM building outline facing the street, the house number between its neighbours'), and a
     note on why. When Google's pin disagrees with the algorithm by more than the uncertainty, the agent says so
     and explains which it believes, but its suggestion is not copied from Google (see Google below).
   The reviewer picks or drags the final pin in the web app.
7. **Compare with Google**: found / changed (name, category or closed) / not found nearby; Google's details in a
   separate review block.
8. **Hand over**:
   - **visit tasks** (to Place capture): places new to Google, changed, or partly read; with position, cover photo,
     what to confirm;
   - **removal candidates**: places on Google (and later in our published data) along the swept stretch that the
     sweep shows closed, for rent, or gone. "Gone" only where the facade was clearly in view (camera side, not
     blocked by trucks or trees); otherwise it becomes a *verify* task, never a removal on its own.

### 2b. Place capture

1. Places are already grouped (the contributor's taps).
2. **Read the photos** (language model): the same fields as 2a.2, from the close-ups; the phone's OCR is only a
   hint to check (it got the name right for 9 of 22 places, and took neighbours' phones, 2026-11-15).
3. **Locate**: three pins as in 2a.6: **contributor** (door GPS or dragged pin) instead of algorithm, Google, agent.
4. **Compare with Google** as in 2a.7. No matching against sweeps.

## 3. Web app: confirm and publish

Vue + Leaflet, OpenStreetMap tiles (served over http, so the tile policy is met), deployable as static files
first.

- **Import** result bundles (and raw zips, to see a ride or a capture session).
- **Sessions**: list and filters (date, kind, mounted / in hand, street, status), all on one map.
- **Review queue** per place: photos, the three pins (algorithm or contributor / Google / agent) with sighting
  lines, every field with its source photo; the reviewer **confirms, edits or rejects** each field and picks or drags
  the final pin. **Confirm** accepts the fields not decided one by one when they were read clearly (sure high /
  medium) or come from Google / the website (marked); **suggestions** are never accepted by Confirm: unsure readings
  (sure low, Google's one-day hours) and the road the photos were taken on (`source: "ride"`, a street nobody else
  gave). They are shown dashed with what they are, listed after Confirm, and published only once accepted (✓) or
  typed (`web/src/fields.ts`, tested in `fields.test.ts`). A place with no street at all is flagged in the list and
  the review ("no street"). Removal candidates are confirmed or turned into verify tasks. Photos: the reviewer can **remove**
  (and restore) a photo, **add** photos (pick, drop or paste), **crop** one (a cropped copy is stored beside the
  original) and pick the **cover**; the imported photos are never changed, the edits are kept with the review.
  Publish → *Photos (.zip)* writes the kept photos of the confirmed places as `<place id>/<n>.jpg`, cover first,
  with `places.geojson`; the `photos` column of the GeoJSON / CSV lists them.
- **Task list**: visit and verify-removal tasks from the sweeps; **Export** writes the work left on site for the
  phone: the agent's visits and verify-removal tasks, removals the reviewer sent "on site" (confirmed or dismissed
  ones are left out) and places the reviewer marked *visit* (at the final / agent pin). GeoJSON FeatureCollection
  with `"schema": "poi-tasks/1"`, `createdAt`, `createdBy`; each Point's properties: `id` (stable, so re-importing
  keeps what was done on the phone), `type` (`visit` | `verify_removal`), `name`, `why`, `place`, `session`.
- **Publish** (per session): GeoJSON, CSV and .osm of the confirmed places and removals, *Photos (.zip)*, and
  **Project (.zip)**, the package for stage 4 (below).

### Project package (`poi-project/1`), the hand-off to the VMAP edit

Publish → *Project (.zip)* writes `poi-project-<session id>-<yyyymmddhhmm>.zip` (built in `web/src/project.ts`,
tested in `project.test.ts`), self-describing so nothing has to be merged by hand:

```
README.md                  layout, counts, "Check before handing on" (warnings) and a table of every place
project.json               schema poi-project/1 (below)
tasks.jsonl                one line per confirmed place: {id, name, address, lat, lon, category, status, action,
                           photos}; address = house number + street; loads with poi-contribution/tools/tasks.py
places/<place id>/<n>.<ext> kept photos of the confirmed places, cropped where cropped, cover = 1
removals/<n>.<ext>         photos of removal tasks
```

`project.json`: `exportedAt`, `exportedBy`, `session` (id, name, startedAt, kind, street), `counts`, `rules`,
`warnings`, and

- `places`: every **reviewed** place (confirmed, visit, rejected; not-yet-reviewed ones are only counted and
  warned about) with `decision` and `action` for the VMAP agent: `add_or_update` (confirmed, open / unknown),
  `mark_closed` (confirmed as closed, gone or for rent), `skip` (visit, rejected, under construction). Then name,
  category, status, address, final `pin` (lat, lon, source), `fields` (each: reviewed `value`, null when rejected;
  `state` ok / edited / rejected; `source` photo / google / website / reviewer; `edited`; `readValue` / `readFrom`
  when the reviewer changed it; `photo` index; `clarified`), `notChecked` (unsure readings never confirmed, left
  out), `fromGoogle`, comment, reviewer, reviewedAt, `photos` (file in the zip, role cover / photo, source street /
  website / reviewer, cropped, capture index, original path, website url), the agent's note, and
  `googleMatchHint` (Google's nearest match: name, distance, url; `notForPublishing: true`, for duplicate checking
  only).
- Opening hours keep the reviewer's text verbatim plus `hours` when it parses cleanly: `days` (mo…su, each a list
  of `[open, close]` slots, `[]` = closed), `vmap` (the `--hours` spec of poi-contribution's `vmap_edit.py` /
  `vmap_create.py` when it fits their presets: `07:00-22:00`, `24/7`, `mon-fri=…;sat-sun=…`; null for split shifts
  etc.) and `ignored` (junk rows such as Google's `5: `). Unparsed hours are warned about.
- `removals`: removal tasks with `decision` (todo / confirmed / verify / dismissed) and `action` (`mark_closed`
  when confirmed, else `skip`).
- `warnings`: places not reviewed, reviewer comments, text that looks like a test leftover, photos added by the
  reviewer, confirmed places without photos, unchecked fields, fields still Google's, unparsed hours, photos missing
  from the browser store.
- **Accuracy dashboard**: per ride and app version, distance from the algorithm's pin to the confirmed pin. Once
  enough places are confirmed, this replaces Google pins as the yardstick.

## 4. Agentic VMAP edit (poi-contribution)

A Claude agent in `poi-contribution` drives the VMAP Android app in an emulator with its Python tools. Input: a
project package. `tasks.jsonl` loads with `tools/tasks.py` (key `id`, `name`, `address`, `lat` / `lon`); for each
place with `action: add_or_update` the agent checks VMAP for the place (dedup; `googleMatchHint` may help), then
fills the empty fields from `fields` (hours from `hours.vmap`) or creates it with the photos under `places/<id>/`;
`mark_closed` places and confirmed removals are reported closed; `skip` ones are left alone. It reads the
package's README warnings first.

## Google

Project decision (2026-11-15): Google Maps data is used in place records, against Google's terms, as a choice the
project owns. Matched fields are compared (`match` / `differs`, the photo wins when they differ, as it is newer);
fields the photos lack are filled from Google with `source: "google"`, only from a match within 100 m whose name
matches the sign (>= 0.5): farther or weaker results are not merged and the place counts as not on Google; a photo reading the agent was unsure of is
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
| 3 web app (POI Capture Reviewer): import, sessions, map, review (fields, 3 pins, final pin, photos), tasks, shareable links, publish GeoJSON / CSV / .osm / photos / project package | yes | `web/`, Cloudflare Pages `poi-capture-reviewer` (`.github/workflows/web.yml`) |
| Phone: import the task list as a map layer, close tasks by Place capture | yes (app 0.17, unreleased) | `core/FieldTasks.kt`, `ui/TasksUi.kt`; web `tasksGeojson` |
| Cloud path: phone uploads (tus), agent pull / push, web app *From server* | yes (app 0.17, unreleased); server deployed as a Cloudflare Worker + R2 | `server/worker/`, `server/`, `capture/UploadWork.kt`, `cloud.py`, `web/src/cloud.ts` |
| 4 VMAP edit from a project package | tools take tasks.jsonl | poi-contribution (`tools/tasks.py`, `vmap_edit.py`, `vmap_create.py`) |
| OSM building outlines for the agent pin | no | next |
