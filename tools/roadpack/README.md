# Offline road packs ("Dữ liệu đường offline")

Builds the road packs the app downloads in **Hồ sơ → Dữ liệu đường offline**, so roads are recognised without network
and without the public Overpass servers. Source today: OpenStreetMap via Geofabrik; VMAP's road network later (same
pack format, only the builder input changes).

```
python -m venv .venv && .venv\Scripts\pip install -r requirements.txt
curl -LO https://download.geofabrik.de/asia/vietnam-latest.osm.pbf        # ~316 MB, check the .md5 next to it
.venv\Scripts\python build_roadpacks.py --pbf vietnam-latest.osm.pbf --regions regions.json --out packs
```

**Published automatically**: `.github/workflows/roadpacks.yml` rebuilds the packs on the 3rd of every month (or on
demand: Actions → roadpacks → Run workflow, or `gh workflow run roadpacks.yml`) and replaces the assets of the GitHub
release `roadpacks`. The app's default catalogue is
`https://github.com/tewnut/road-capture/releases/download/roadpacks/index.json` (works once the repository is public).
Forks: change `DEFAULT_ROADPACK_INDEX` in `core/Session.kt` or set "Nguồn gói" in the app.

Output: `packs/<region>.zip` + `packs/index.json` (only these: the ~750 MB temporary node index is written next to
the pbf as `<pbf>.nodeidx.bin` and deleted, or by the next run when Windows still holds it). Reference run on
vietnam-latest of 2026-11-08: 148 s, peak RAM 1.2 GB; Hà Nội nội thành 0.9 MB, Hà Nội toàn vùng 12.8 MB, TP.HCM trung tâm
1.9 MB, TP.HCM mở rộng 11.7 MB, Đà Nẵng trung tâm 1.0 MB. Host the folder on any static HTTPS server (or object storage) and
put the URL of `index.json` in the app ("Nguồn gói"). A pack file can also be shared by hand (Zalo, Drive) and imported
with "Nhập file gói…".

- **Regions** (`regions.json`) are bounding boxes ("vùng"), not administrative boundaries: provinces were merged in
  2025 and OSM boundaries lag behind. Big cities have a small "nội thành / trung tâm" pack next to the whole area.
- **Roads kept**: every `highway=*` except the values matched by the app's filter (`Overpass.SKIP_AREA`: footways,
  paths, steps, cycleways, service roads, tracks, construction, …). The filter string is written in `pack.json`; the app
  refuses a pack built with another filter.
- **Format 1** (read by `core/RoadPack.kt`): `pack.json` (magic, format, id, name, bbox, cellSize 0.005, skip, version =
  extract date, source, attribution, counts) and `cells/{x}_{y}.json` = `{"w": [[id, piece, name, ref, highway, oneway,
  [lat0e6, lon0e6, dlat, dlon, …]], …]}` on the app's 0.005° grid (x = floor(lon/0.005), y = floor(lat/0.005)).
  Long ways are densified (a point every ≤ 150 m) and cut into pieces: each piece starts in one cell and ends at the
  first point of the next, stored in its start cell. Readers merge by (id, piece), never by id alone.
- **Licence**: OSM data is ODbL 1.0. Packs carry "© OpenStreetMap contributors, ODbL 1.0" and the app shows it.
  Internal use is fine; publishing a database derived from it (e.g. mixed into VMAP) has share-alike obligations: ask
  legal before doing so.
- `test/fixture.osm` + `test/regions.json` build the tiny pack used by the app's unit tests
  (`app/src/test/resources/roadpack_fixture.zip`): rebuild it after changing the format or the filter.
