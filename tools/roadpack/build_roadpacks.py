#!/usr/bin/env python3
"""
Build offline road packs for the POI Capture app from an OpenStreetMap extract (Geofabrik).

    python build_roadpacks.py --pbf vietnam-latest.osm.pbf --regions regions.json --out packs/

One ZIP per region + index.json (the catalogue the app downloads from). Format: see the app's core/RoadPack.kt.

A region is either
- an administrative area: {"id", "name", "from": [names]} = the union of the admin_level=4 boundaries of the extract
  whose name matches one of `from` (accents, case and "Tỉnh" / "Thành phố" prefixes ignored). Listing the former
  provinces of a merged one works whether or not OSM already has the new boundary. The pack holds the road cells inside
  the area (plus a ~600 m margin) and lists them ("coverage", pack format 2); index.json carries a simplified outline
  so the app can tell which pack covers the contributor's position.
- a box: {"id", "name", "bbox": [south, west, north, east]} (pack format 1, every cell of the box).

The road filter must stay identical to the app's Overpass.SKIP_AREA (the app refuses packs built otherwise).
Data © OpenStreetMap contributors, ODbL 1.0.
"""
import argparse, datetime, gc, hashlib, json, math, os, re, sys, time, unicodedata, zipfile

import osmium

FORMAT_BOX = 1
FORMAT_AREA = 2
MAGIC = "road-capture-roadpack"
CELL = 0.005
# == Overpass.SKIP_AREA in app/src/main/java/io/github/tewnut/poicapture/core/Road.kt (same unanchored regex semantics)
SKIP = ("footway|path|steps|cycleway|pedestrian|bridleway|corridor|elevator|platform|proposed|construction"
        "|service|track|bus_guideway|raceway|abandoned|razed")
SKIP_RE = re.compile(SKIP)
DENSIFY_M = 150.0          # long straight segments get intermediate points: a piece never skips a whole cell
ATTRIBUTION = "© OpenStreetMap contributors, ODbL 1.0"
R = 6371008.8
MARGIN_DEG = 0.006         # cells whose centre is this close to the area are included (roads on the border)
MIN_PART_DEG2 = 1e-4       # parts of an area smaller than ~1 km² (small islands) are left out of packs and outlines
OUTLINE_TOL_DEG = 0.01     # outline simplification (~1 km): enough to tell the province of a position


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


def intersects(box, bbox):
    s, w, n, e = box
    rs, rw, rn, re_ = bbox
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


def remove_index(path):
    gc.collect()
    try:
        os.unlink(path)
    except OSError:
        print("note: temporary node index %s is still locked; it is removed by the next run, or delete it" % path,
              file=sys.stderr)


# ---------------------------------------------------------------- administrative areas

def norm(name):
    """Province name for matching: no accents, đ → d, lowercase, without "Tỉnh" / "Thành phố" / "TP."."""
    s = unicodedata.normalize("NFD", name.replace("đ", "d").replace("Đ", "D"))
    s = "".join(ch for ch in s if unicodedata.category(ch) != "Mn").lower()
    s = re.sub(r"[^a-z0-9]+", " ", s).strip()
    for p in ("thanh pho ", "tinh ", "tp "):
        if s.startswith(p):
            s = s[len(p):]
    return s


def admin_areas(pbf):
    """admin_level=4 boundaries of the extract: normalised name -> list of shapely polygons (lon, lat)."""
    from shapely.geometry import Polygon
    idx = os.path.abspath(pbf) + ".areaidx.bin"
    if os.path.exists(idx):
        os.unlink(idx)
    out = {}
    fp = (osmium.FileProcessor(pbf)
          .with_locations("sparse_file_array," + idx)
          .with_areas(osmium.filter.TagFilter(("boundary", "administrative"))))
    for o in fp:
        if not o.is_area() or o.tags.get("admin_level") != "4":
            continue
        polys = []
        for outer in o.outer_rings():
            try:
                ext = [(n.lon, n.lat) for n in outer]
                holes = [[(n.lon, n.lat) for n in inner] for inner in o.inner_rings(outer)]
            except osmium.InvalidLocationError:
                continue
            p = Polygon(ext, holes)
            polys.append(p if p.is_valid else p.buffer(0))
        names = {o.tags.get(k) for k in ("name", "name:vi", "official_name", "old_name") if o.tags.get(k)}
        for nm in names:
            out.setdefault(norm(nm), []).extend(polys)
    del fp
    remove_index(idx)
    return out


def area_region(r, areas):
    """Geometry, bbox, outline and covered cells of a region built from administrative areas."""
    import numpy as np
    import shapely
    from shapely.ops import unary_union
    found, missing = [], []
    for nm in r["from"]:
        (found if norm(nm) in areas else missing).append(nm)
    if not found:
        raise SystemExit("region %s: no admin_level=4 boundary named %s in the extract" % (r["id"], r["from"]))
    geom = unary_union([p for nm in found for p in areas[norm(nm)]])
    parts = [g for g in getattr(geom, "geoms", [geom]) if g.area >= MIN_PART_DEG2]
    main = unary_union(parts)
    w, s, e, n = main.bounds
    outline = []
    for g in getattr(main.simplify(OUTLINE_TOL_DEG), "geoms", [main.simplify(OUTLINE_TOL_DEG)]):
        if g.is_empty:
            continue
        outline.append([[round(lat, 3), round(lon, 3)] for lon, lat in g.exterior.coords])
    # cells whose centre is inside the area or within the margin
    zone = main.simplify(CELL / 2).buffer(MARGIN_DEG)
    shapely.prepare(zone)
    x0, x1 = math.floor((w - MARGIN_DEG) / CELL), math.floor((e + MARGIN_DEG) / CELL)
    y0, y1 = math.floor((s - MARGIN_DEG) / CELL), math.floor((n + MARGIN_DEG) / CELL)
    xs, ys = np.meshgrid(np.arange(x0, x1 + 1), np.arange(y0, y1 + 1))
    inside = shapely.contains_xy(zone, (xs + 0.5) * CELL, (ys + 0.5) * CELL)
    cells = set(zip(xs[inside].tolist(), ys[inside].tolist()))
    return {"bbox": [round(s, 4), round(w, 4), round(n, 4), round(e, 4)], "outline": outline, "cells": cells,
            "matched": found, "missing": missing}


def coverage_rows(cells):
    """Covered cells as rows [y, x0, x1, x0', x1', …] (inclusive x ranges): compact and quick to read in the app."""
    rows = {}
    for x, y in cells:
        rows.setdefault(y, []).append(x)
    out = []
    for y in sorted(rows):
        xs = sorted(rows[y])
        row, start, prev = [y], xs[0], xs[0]
        for x in xs[1:]:
            if x != prev + 1:
                row += [start, prev]
                start = x
            prev = x
        out.append(row + [start, prev])
    return out


# ---------------------------------------------------------------- build

def build(pbf, regions, out_dir, base_url=""):
    t0 = time.time()
    version = pbf_version(pbf)
    os.makedirs(out_dir, exist_ok=True)

    # 1. regions: boxes as given, administrative areas from the boundaries in the extract
    if any("from" in r for r in regions):
        areas = admin_areas(pbf)
        print("admin_level=4 boundaries read: %d names (%.0f s)" % (len(areas), time.time() - t0))
        for r in regions:
            if "from" in r:
                r.update(area_region(r, areas))
                if r["missing"]:
                    print("  %s: not in the extract %s (found %s)" % (r["id"], r["missing"], r["matched"]))
    by_cell = {}                                         # area regions: cell -> [region ids]
    for r in regions:
        for c in r.get("cells", ()):
            by_cell.setdefault(c, []).append(r["id"])
    box_regions = [r for r in regions if "cells" not in r]

    cells = {r["id"]: {} for r in regions}               # region -> cell -> [encoded pieces]
    counts = {r["id"]: {"ways": set(), "pieces": 0} for r in regions}
    seen = kept = bad = 0
    # node locations on disk (~750 MB for Vietnam): next to the pbf, never in the output folder that gets hosted
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
            bad += 1                                     # node outside the extract: skip the way
            continue
        if len(pts) < 2:
            continue
        lats = [p[0] for p in pts]; lons = [p[1] for p in pts]
        wbox = (min(lats) - CELL, min(lons) - CELL, max(lats) + CELL, max(lons) + CELL)
        boxes = [r for r in box_regions if intersects(wbox, r["bbox"])]
        tags = None
        for i, (c, ppts) in enumerate(pieces(pts)):
            targets = list(by_cell.get(c, ())) + [r["id"] for r in boxes if intersects(cell_box(c), r["bbox"])]
            if not targets:
                continue
            tags = tags or {k: obj.tags.get(k, "") for k in ("name", "ref", "highway", "oneway")}
            enc = encode(obj.id, i, tags, ppts)
            for rid in targets:
                cells[rid].setdefault(c, []).append(enc)
                counts[rid]["ways"].add(obj.id)
                counts[rid]["pieces"] += 1
        if tags is not None:
            kept += 1
    del proc                                             # releases the node index file (Windows keeps it locked)
    remove_index(idx_path)
    parse_s = time.time() - t0

    # 2. one zip per region, and the catalogue
    index = {"format": FORMAT_AREA, "generated": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
             "source": os.path.basename(pbf), "version": version, "attribution": ATTRIBUTION, "packs": []}
    for r in regions:
        rid = r["id"]
        area = "cells" in r
        header = {"magic": MAGIC, "format": FORMAT_AREA if area else FORMAT_BOX, "id": rid, "name": r["name"],
                  "bbox": r["bbox"], "cellSize": CELL, "skip": SKIP, "version": version, "builtAt": index["generated"],
                  "source": "Geofabrik " + os.path.basename(pbf), "attribution": ATTRIBUTION,
                  "ways": len(counts[rid]["ways"]), "pieces": counts[rid]["pieces"], "cells": len(cells[rid])}
        if area:
            header["coverage"] = coverage_rows(r["cells"])
        path = os.path.join(out_dir, rid + ".zip")
        with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
            z.writestr("pack.json", json.dumps(header, ensure_ascii=False, separators=(",", ":")))
            for (x, y), ws in sorted(cells[rid].items()):
                z.writestr("cells/%d_%d.json" % (x, y), json.dumps({"w": ws}, ensure_ascii=False, separators=(",", ":")))
        data = open(path, "rb").read()
        entry = {"id": rid, "name": r["name"], "version": version, "bytes": len(data),
                 "sha256": hashlib.sha256(data).hexdigest(), "url": base_url + rid + ".zip", "bbox": r["bbox"],
                 "format": header["format"], "ways": header["ways"], "pieces": header["pieces"]}
        if area:
            entry["outline"] = r["outline"]
        index["packs"].append(entry)
        print("%-14s %7.1f MB  %7d ways  %8d pieces  %7d cells%s" % (rid, len(data) / 1048576, header["ways"],
              header["pieces"], header["cells"], "" if not area else "  (%d covered)" % len(r["cells"])))
    with open(os.path.join(out_dir, "index.json"), "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, separators=(",", ":"))
    print("highway ways read %d, kept %d, skipped (node outside extract) %d" % (seen, kept, bad))
    print("parse %.0f s, total %.0f s, peak RAM %s MB" % (parse_s, time.time() - t0, peak_rss_mb()))
    return index


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--pbf", required=True, help="Geofabrik extract, e.g. vietnam-latest.osm.pbf")
    ap.add_argument("--regions", required=True, help="regions.json: [{id, name, from: [names]} | {id, name, bbox}]")
    ap.add_argument("--out", required=True)
    ap.add_argument("--base-url", default="", help="prefix for pack URLs in index.json (default: relative)")
    a = ap.parse_args()
    build(a.pbf, json.load(open(a.regions, encoding="utf-8")), a.out, a.base_url)
