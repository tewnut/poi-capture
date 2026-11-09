# Offline road packs

Builds the road packs the app downloads (Profile → Offline road data, and during onboarding), so roads are recognised
without network and without the public Overpass servers. Source today: OpenStreetMap via Geofabrik; another road
network can be packed the same way later (same pack format, only the builder input changes).

```
python -m venv .venv && .venv\Scripts\pip install -r requirements.txt
curl -LO https://download.geofabrik.de/asia/vietnam-latest.osm.pbf        # ~316 MB, check the .md5 next to it
.venv\Scripts\python build_roadpacks.py --pbf vietnam-latest.osm.pbf --regions regions.json --out packs
```

**Published automatically**: `.github/workflows/roadpacks.yml` rebuilds the packs on the 3rd of every month (or on
demand: Actions → roadpacks → Run workflow, or `gh workflow run roadpacks.yml`) and replaces the assets of the GitHub
release `roadpacks`. The app's default catalogue is
`https://github.com/tewnut/poi-capture/releases/download/roadpacks/index.json`. Forks: change `DEFAULT_ROADPACK_INDEX`
in `core/Session.kt`, or set the catalogue URL in the app.

Output: `packs/<region>.zip` + `packs/index.json`. Temporary node indexes (~750 MB) are written next to the pbf
(`<pbf>.nodeidx.bin`, `<pbf>.areaidx.bin`) and deleted, or by the next run when Windows still holds them. Host the
folder on any static HTTPS server (or object storage) and give the app the URL of `index.json`. A pack file can also be
shared by hand and imported in the app.

- **Regions** (`regions.json`): the 34 provinces / cities of Vietnam since the 2025 merger. Each lists the former
  provinces it absorbed (`from`); its area is the union of the `admin_level=4` boundaries of the extract with those
  names (accents, case and "Tỉnh" / "Thành phố" prefixes ignored). This is right whether or not OSM already carries the
  new boundaries. Small islands (< ~1 km²) are left out. A region can also be a plain box (`bbox`), as in
  `test/regions.json`.
- **Roads kept**: every `highway=*` except the values matched by the app's filter (`Overpass.SKIP_AREA`: footways,
  paths, steps, cycleways, service roads, tracks, construction, …). The filter string is written in `pack.json`; the app
  refuses a pack built with another filter.
- **Format** (read by `core/RoadPack.kt`): `pack.json` (magic, format, id, name, bbox, cellSize 0.005, skip, version =
  extract date, source, attribution, counts) and `cells/{x}_{y}.json` = `{"w": [[id, piece, name, ref, highway, oneway,
  [lat0e6, lon0e6, dlat, dlon, …]], …]}` on the app's 0.005° grid (x = floor(lon / 0.005), y = floor(lat / 0.005)).
  Long ways are densified (a point every ≤ 150 m) and cut into pieces: each piece starts in one cell and ends at the
  first point of the next, stored in its start cell. Readers merge by (id, piece), never by id alone.
  - Format 1 (a box region): the pack covers every cell of its bbox.
  - Format 2 (a province): the pack holds the cells whose centre is inside the province or within ~600 m of it, and
    lists them in `pack.json` as `coverage` = rows `[y, x0, x1, x0', x1', …]` (inclusive x ranges). Only those cells
    are covered: the boxes of neighbouring provinces overlap, and a covered cell without a road file means "no road".
  - `index.json` gives each province a simplified `outline` (rings of `[lat, lon]`, ~1 km tolerance) so the app can
    tell which pack covers the contributor's position.
- **Licence**: OSM data is ODbL 1.0. Packs carry "© OpenStreetMap contributors, ODbL 1.0" and the app shows it.
  Publishing a database derived from it (e.g. mixed into another map) has share-alike obligations.
- `test/fixture.osm` + `test/regions.json` build the tiny pack used by the app's unit tests
  (`app/src/test/resources/roadpack_fixture.zip`): rebuild it after changing the format or the filter.
