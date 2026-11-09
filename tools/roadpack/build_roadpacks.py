#!/usr/bin/env python3
"""
Build offline road packs for the POI Capture app from an OpenStreetMap extract (Geofabrik).

    python build_roadpacks.py --pbf vietnam-latest.osm.pbf --regions regions.json --out packs/

One ZIP per region + index.json (the catalogue the app downloads from). Format: see the app's core/RoadPack.kt.
Regions are bounding boxes ("vùng"), not administrative boundaries (provinces were merged in 2025).
The road filter must stay identical to the app's Overpass.SKIP_AREA (the app refuses packs built otherwise).
Data © OpenStreetMap contributors, ODbL 1.0.
"""
import argparse, datetime, hashlib, json, math, os, re, sys, time, zipfile

import osmium

FORMAT = 1
MAGIC = "road-capture-roadpack"
CELL = 0.005
# == Overpass.SKIP_AREA in app/src/main/java/io/github/tewnut/poicapture/core/Road.kt (same unanchored regex semantics)
SKIP = ("footway|path|steps|cycleway|pedestrian|bridleway|corridor|elevator|platform|proposed|construction"
        "|service|track|bus_guideway|raceway|abandoned|razed")
SKIP_RE = re.compile(SKIP)
DENSIFY_M = 150.0          # long straight segments get intermediate points: a piece never skips a whole cell
ATTRIBUTION = "© OpenStreetMap contributors, ODbL 1.0"
R = 6371008.8


def cell_of(lat, lon):
    return (math.floor(lon / CELL), math.floor(lat / CELL))


def dist_m(a, b):
    dlat = math.radians(b[0] - a[0]); dlon = math.radians(b[1] - a[1])
    h = math.sin(dlat / 2) ** 2 + math.cos(math.radians(a[0])) * math.cos(math.radians(b[0])) * math.sin(dlon / 2) ** 2
    return 2 * R * math.asin(math.sqrt(h))


def densify(pts):
    out = [pts[0]]
    for a, b in zip(pts, pts[1:]):
        n = int(dist_m(a, b) // DENSIFY_M)
        for k in range(1, n + 1):
            t = k / (n + 1)
            out.append((a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1])))
        out.append(b)
    return out


def pieces(pts):
    """Cut a way into pieces that each start in one cell and end at the first point in another cell."""
    pts = densify(pts)
    out, cur, c0 = [], [pts[0]], cell_of(*pts[0])
    for p in pts[1:]:
        cur.append(p)
        c = cell_of(*p)
        if c != c0:
            out.append((c0, cur))
            cur, c0 = [p], c
    if len(cur) > 1:
        out.append((c0, cur))
    return out


def encode(wid, piece, tags, pts):
    coords, plat, plon = [], 0, 0
    for lat, lon in pts:
        la, lo = int(round(lat * 1e6)), int(round(lon * 1e6))
        coords += [la - plat, lo - plon]
        plat, plon = la, lo
    return [wid, piece, tags.get("name", ""), tags.get("ref", ""), tags.get("highway", ""), tags.get("oneway", ""), coords]


def cell_box(c):
    x, y = c
    return (y * CELL, x * CELL, (y + 1) * CELL, (x + 1) * CELL)


def intersects(box, region):
    s, w, n, e = box
    rs, rw, rn, re_ = region["bbox"]
    return not (n <= rs or s >= rn or e <= rw or w >= re_)


def pbf_version(path):
    try:
        ts = osmium.io.Reader(path, osmium.osm.osm_entity_bits.NOTHING).header().get("osmosis_replication_timestamp")
        if ts:
            return ts[:10]
    except Exception:
        pass
    return datetime.datetime.fromtimestamp(os.path.getmtime(path), datetime.timezone.utc).strftime("%Y-%m-%d")


def peak_rss_mb():
    try:
        import psutil
        p = psutil.Process()
        info = p.memory_info()
        return round(getattr(info, "peak_wset", info.rss) / 1048576)
    except Exception:
        return None


def build(pbf, regions, out_dir, base_url=""):
    t0 = time.time()
    version = pbf_version(pbf)
    os.makedirs(out_dir, exist_ok=True)
    cells = {r["id"]: {} for r in regions}       # region -> cell -> [encoded pieces]
    counts = {r["id"]: {"ways": set(), "pieces": 0} for r in regions}
    seen = kept = bad = 0
    # node locations on disk (~750 MB for Vietnam): next to the pbf, never in the output folder that gets hosted;
    # removed at the end or, when Windows still holds it, at the start of the next run
    idx_path = os.path.abspath(pbf) + ".nodeidx.bin"
    if os.path.exists(idx_path):
        os.unlink(idx_path)
    proc = (osmium.FileProcessor(pbf, osmium.osm.NODE | osmium.osm.WAY)
            .with_locations("sparse_file_array," + idx_path)
            .with_filter(osmium.filter.KeyFilter("highway")))
    for obj in proc:
        if not obj.is_way():
            continue
        seen += 1
        hw = obj.tags.get("highway", "")
        if not hw or SKIP_RE.search(hw):
            continue
        try:
            pts = [(n.lat, n.lon) for n in obj.nodes]
        except osmium.InvalidLocationError:
            bad += 1                                   # node outside the extract: skip the way
            continue
        if len(pts) < 2:
            continue
        lats = [p[0] for p in pts]; lons = [p[1] for p in pts]
        wbox = (min(lats) - CELL, min(lons) - CELL, max(lats) + CELL, max(lons) + CELL)
        regs = [r for r in regions if intersects(wbox, r)]
        if not regs:
            continue
        kept += 1
        tags = {k: obj.tags.get(k, "") for k in ("name", "ref", "highway", "oneway")}
        for i, (c, ppts) in enumerate(pieces(pts)):
            enc = None
            for r in regs:
                if intersects(cell_box(c), r):
                    enc = enc or encode(obj.id, i, tags, ppts)
                    cells[r["id"]].setdefault(c, []).append(enc)
                    counts[r["id"]]["ways"].add(obj.id)
                    counts[r["id"]]["pieces"] += 1
    del proc                                           # releases the node index file (Windows keeps it locked)
    import gc; gc.collect()
    try:
        os.unlink(idx_path)
    except OSError:
        print("note: temporary node index %s (%d MB) is still locked; it is removed by the next run, or delete it"
              % (idx_path, os.path.getsize(idx_path) // 1048576), file=sys.stderr)
    parse_s = time.time() - t0

    index = {"format": FORMAT, "generated": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
             "source": os.path.basename(pbf), "version": version, "attribution": ATTRIBUTION, "packs": []}
    for r in regions:
        rid = r["id"]
        header = {"magic": MAGIC, "format": FORMAT, "id": rid, "name": r["name"], "bbox": r["bbox"], "cellSize": CELL,
                  "skip": SKIP, "version": version, "builtAt": index["generated"],
                  "source": "Geofabrik " + os.path.basename(pbf), "attribution": ATTRIBUTION,
                  "ways": len(counts[rid]["ways"]), "pieces": counts[rid]["pieces"], "cells": len(cells[rid])}
        path = os.path.join(out_dir, rid + ".zip")
        with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
            z.writestr("pack.json", json.dumps(header, ensure_ascii=False))
            for (x, y), ws in sorted(cells[rid].items()):
                z.writestr("cells/%d_%d.json" % (x, y), json.dumps({"w": ws}, ensure_ascii=False, separators=(",", ":")))
        data = open(path, "rb").read()
        entry = {"id": rid, "name": r["name"], "version": version, "bytes": len(data),
                 "sha256": hashlib.sha256(data).hexdigest(), "url": base_url + rid + ".zip", "bbox": r["bbox"],
                 "ways": header["ways"], "pieces": header["pieces"]}
        index["packs"].append(entry)
        print("%-24s %8.1f MB  %7d ways  %8d pieces  %6d cells" % (rid, len(data) / 1048576, header["ways"], header["pieces"], header["cells"]))
    with open(os.path.join(out_dir, "index.json"), "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, indent=1)
    print("highway ways read %d, kept %d, skipped (node outside extract) %d" % (seen, kept, bad))
    print("parse %.0f s, total %.0f s, peak RAM %s MB" % (parse_s, time.time() - t0, peak_rss_mb()))
    return index


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--pbf", required=True, help="Geofabrik extract, e.g. vietnam-latest.osm.pbf")
    ap.add_argument("--regions", required=True, help="regions.json: [{id, name, bbox: [south, west, north, east]}]")
    ap.add_argument("--out", required=True)
    ap.add_argument("--base-url", default="", help="prefix for pack URLs in index.json (default: relative)")
    a = ap.parse_args()
    build(a.pbf, json.load(open(a.regions, encoding="utf-8")), a.out, a.base_url)
