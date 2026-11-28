# Quét Phố: processing rides (instructions for an agent)

You are an AI coding agent (e.g. Claude Code) asked to **process rides** for Quét Phố, VMAP's street-capture
app. Phones upload raw rides to a server; a ride stays **"Waiting to be processed" / "Đang chờ xử lý"** in the
reviewer web app until an agent like you turns it into reviewable places, house numbers and traffic signs and
uploads the result. People then review it in the web app.

This page is the entry point. The full, authoritative procedure is the `process-sweep` skill in the repository;
follow it step by step.

## 1. What you need

- **The repository:** <https://github.com/tewnut/poi-capture> (public). The skill is
  `.claude/skills/process-sweep/SKILL.md`, its scripts are in `.claude/skills/process-sweep/scripts/`.
  Claude Code picks the skill up by itself when it runs inside the repository.
- **Python 3** with Pillow and opencv-python (`pip install --user pillow opencv-python`). The privacy step downloads
  its face / plate models (MIT / Apache 2.0) to `~/.cache/poi-capture/models` on first use.
- Run every script as **`python -E -P -X utf8 <script> …`**: `-E` ignores `PYTHON*` variables, `-P` keeps the
  current folder off the import path (a module planted in a downloaded ride is never imported; the scripts add their
  own folder), `-X utf8` prints Vietnamese on Windows. **Not `python -I`**: it also drops the user site-packages,
  where Pillow and OpenCV are installed, and the scripts cannot start. Keep your own helper scripts in a folder of
  their own, never next to downloaded data.
- **A personal access key with the "Process rides" permission**, issued by the server admin (admin page →
  issue key → preset *Agent*). Keep it in a file outside the repository and pass it with `--token-file`.
  **Never print, log, commit or paste it** anywhere.
- The server address, in `POI_CLOUD_URL` (the admin gives it with the key; it ends in `/files/`).
- About 1 GB of free disk per ride; keep downloads out of the repository.

## 2. The lifecycle of one ride

All commands are `python -E -P -X utf8 .claude/skills/process-sweep/scripts/cloud.py --token-file <key file> …`.

1. **List** what is waiting: `cloud.py list` (type `raw-session`; one line per ride with its session id and name)
   and `cloud.py processing` (rides someone is already working on).
2. **Claim and download** one: `cloud.py pull <upload id> <dir> --extract`. Pulling claims the ride for 12 hours,
   so the web app shows "⚙ <your key's name> is processing" and no other agent takes it. If someone else holds it,
   pick another ride; do not use `--force` unless the person who holds it agrees.
3. **Process** it with the skill (`SKILL.md`), in this order:
   1. `prepare.py` → read **every** contact sheet → write `places.json` (shops, `addresses`, `signs`, `removals`,
      depending on the ride's `target`) → `build.py`. A long house-number ride (100+ photos) can be read by
      several agents, each a photo range with a few photos of overlap, joined with `merge_places.py` (SKILL.md
      "Long rides").
   2. Shops only: Google Maps lookup (`enrich_browser.py`), then the shops' own websites (`enrich_web.py`);
      check every match and website image as the skill says.
   3. **Privacy (required): `blur.py detect`, look at every check sheet, `blur.py apply`, look at every
      after-sheet and every zoom sheet (the kerb band at a readable size: parked motorbikes' plates).** Faces and
      vehicle number plates are pixelated before anything leaves your computer.
4. **Upload the result:** `cloud.py push <out>`. It refuses a folder whose photos were not blurred. The upload
   ends your claim, and the ride moves to "ready to review" in the web app. A slow link needs no retry loop: the
   push shrinks its chunks, waits and resumes by itself, and running the same command again continues where it
   stopped. Run a big push in the background.
5. **Giving up** on a ride: `cloud.py release <session id>`, so someone else can take it.

## 3. What each ride asks for (`session.target`)

| Target | Camera | Read | Notes |
|---|---|---|---|
| `shops` (Địa điểm) | sideways, at the shopfronts | every business: name, category, phone, hours, address, website | Google + website enrichment |
| `addresses` (Địa chỉ) | sideways | the shops, plus every house number: plates, and the address lines of shop signs (`source`) | `plates` hints in photos.json (about half are prices, vehicle plates, phone fragments); read each number yourself on the full-resolution crop sheets (`crops/plates_NN.jpg`, `crops.py band`) |
| `signs` (Biển báo) | forward, along the road | every traffic sign: QCVN 41:2019 code, extra plate, side; signs on one pole or gantry share a `post` (+ `order`, 1 = top) | `signHints` from the phone's detector: the box is reliable, the code often is not; no Google lookup |

Place-capture rides (`kind: poi`, "Chụp điểm") are already grouped by place on the phone.

Indoor walks (`kind: indoor`, "Trong nhà": a mall, market, airport on foot; docs/indoor.md) read like `shops`, plus
each shop's **unit** ("L2-15" as written) and, when a sign says it, its **level**, and every shop on the mall's
**directory boards** (photos marked `DIRECTORY` on the sheets) into `directory` in places.json. Positions come from
the walk (`prepare.py` recomputes them from `indoor.jsonl`), not GPS; never write a street from the phone for them:
the building's own address applies. The skill's "Indoor walks" section has the details.

## 4. Rules that are never bent

- **Privacy:** no unblurred photo of a face or a number plate leaves your computer; keep the originals local.
- **Read, do not guess:** values come from what the photos (or the place's own website) show. Mark unreadable parts
  with "…" and say why ("1…" for a house number read in part). Low confidence is fine; invented values are not.
  A place's `name` is its brand as on the sign; services and legal names go in `signText`.
- **Google data** is merged as marked metadata for the reviewers and **never written to the OpenStreetMap
  export**; pins never come from Google.
- **One ride, one agent:** claim before working, release when you stop.
- **Secrets:** the access key stays in its file.
- Do not edit or delete other people's uploads; do not commit downloaded rides or results to the repository.

## 5. When something is unclear

Say so in your report to the person who asked you, with the ride's session id, rather than guessing. The design
documents are `docs/pipeline.md` (the stages and their file formats), `docs/sweep-targets.md` (shops / house
numbers / signs) and `server/README.md` (the server API, keys and claims).
