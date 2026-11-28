"""Load a street sweep (exported zip, unzipped folder, or a raw session folder) and make contact sheets to read.

usage: python prepare.py <session.zip | folder> <workdir> [--no-crops]

Writes into <workdir>:
  photos.json   every photo in order: idx, id, file (absolute path), time, camera position / bearing / FOV, size, blur
  session.json  the session record
  sheets/       sheet_NNN.jpg: 4 photos per sheet (2 x 2, 1000 px wide each; a sheet mixing landscape and portrait
                photos gives each its own shape of cell), each labelled "#idx"
  crops/        on a house-number ride: plates_NN.jpg, a full-resolution tile around each number the phone read
                (photos.json plateHints; crops.py plates), so plates are read without cropping by hand
  indoor_track.json  an indoor walk (session.kind "indoor", docs/indoor.md): the walk recomputed from indoor.jsonl
                (anchors, footprint), [[lat, lon, level], ...]; photos.json then has each photo's corrected position,
                livePosition (the phone's), level, uncertaintyM, unitHints, and the sheets say the level
  plans/        an indoor walk with floor plans (session.building.plans or plans/plans.json, docs/indoor.md §7): per
                plan plan_<level>_<n>.jpg (the straightened board, whole, with a grid and rulers in plan pixels every
                100 px) and plan_<level>_<n>_tNN.jpg (tiles of it, enlarged, same grid) to outline the units in plan
                pixels (places.json planUnits); plans.json = the plans with their image's path (build.py reads it)
Prints the ride's target (session.target: shops | addresses | signs; what to read, docs/sweep-targets.md).
"""
import json, math, os, sys, zipfile

sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from safety import inside, safe_id, extract  # noqa: E402  (untrusted export: paths and ids are checked)
from paths import arg_path  # noqa: E402
import indoor  # noqa: E402


def load(src, work):
    """(session, items, photo_dir) from a zip, an unzipped export (manifest.json) or a raw session dir."""
    if zipfile.is_zipfile(src):
        raw = os.path.join(work, 'raw')
        extract(src, raw)
        src = raw
    if os.path.exists(os.path.join(src, 'manifest.json')):
        m = json.load(open(os.path.join(src, 'manifest.json'), encoding='utf-8'))
        items = [i for i in m['items'] if i.get('kind') == 'photo']
        for i in items:
            i['_path'] = inside(src, i.get('file') or f"photos/{safe_id(i['id'])}.jpg")
            safe_id(i['id'])
        return m['session'], items
    # raw session dir (pulled from the phone): items.jsonl, later lines {"_patch": id, ...} update an item
    session = json.load(open(os.path.join(src, 'session.json'), encoding='utf-8'))
    by_id, order = {}, []
    for line in open(os.path.join(src, 'items.jsonl'), encoding='utf-8'):
        if not line.strip():
            continue
        o = json.loads(line)
        pid = o.pop('_patch', None)
        if pid is not None:
            if pid in by_id:
                by_id[pid].update(o)
            continue
        by_id[o['id']] = o
        order.append(o['id'])
    items = [by_id[i] for i in order if by_id[i].get('kind') == 'photo']
    for i in items:
        i['_path'] = inside(src, os.path.join('photos', safe_id(i['id']) + '.jpg'))
    return session, items


def load_track(src, work):
    """GPS fixes [(elapsedNanos, lat, lon, speed, heading)] from track.jsonl (raw session dir) or track.geojson (export)."""
    for d in (src, os.path.join(work, 'raw')):
        f = os.path.join(d, 'track.jsonl') if os.path.isdir(d) else None
        if f and os.path.exists(f):
            out = []
            for line in open(f, encoding='utf-8'):
                try:
                    o = json.loads(line)
                except ValueError:
                    continue
                if o.get('en') is not None and o.get('lat') is not None:
                    out.append((o['en'], o['lat'], o['lon'], o.get('spd'), o.get('hdg')))
            return sorted(out)
    # an export from the app has track.geojson instead: one Point feature per fix, with elapsedNanos
    for d in (src, os.path.join(work, 'raw')):
        f = os.path.join(d, 'track.geojson') if os.path.isdir(d) else None
        if f and os.path.exists(f):
            out = []
            for ft in json.load(open(f, encoding='utf-8')).get('features', []):
                g, pr = ft.get('geometry') or {}, ft.get('properties') or {}
                if g.get('type') == 'Point' and pr.get('elapsedNanos') is not None:
                    lon, lat = g['coordinates'][:2]
                    out.append((pr['elapsedNanos'], lat, lon, pr.get('speedMps'), pr.get('headingDeg')))
            if out:
                return sorted(out)
    return []


def place_in_time(item, track, max_gap_s=3.0, max_extra_s=1.5):
    """The camera's position when the photo was taken, not at the nearest GPS fix: the app stamps a photo with the
    latest fix, up to ~1 s old (6 m at 25 km/h, a shop over). Interpolated between the fixes before and after the
    photo on the phone's elapsed-time clock (both carry it; the wall clock can be ~1 s off GPS time); just past the
    last fix, moved on by its speed and heading. None when there is no usable fix around."""
    import bisect, math
    en = item.get('elapsedNanos')
    if en is None or not track:
        return None
    k = bisect.bisect_left(track, (en,))
    if 0 < k < len(track):
        a, b = track[k - 1], track[k]
        span = (b[0] - a[0]) / 1e9
        if 0 < span <= max_gap_s:
            f = (en - a[0]) / (b[0] - a[0])
            return a[1] + f * (b[1] - a[1]), a[2] + f * (b[2] - a[2]), 'interpolated'
    a = track[k - 1] if k > 0 else None
    if a is not None and a[3] is not None and a[4] is not None and (en - a[0]) / 1e9 <= max_extra_s:
        d = a[3] * (en - a[0]) / 1e9
        h = math.radians(a[4])
        lat = a[1] + d * math.cos(h) / 111320
        lon = a[2] + d * math.sin(h) / (111320 * math.cos(math.radians(a[1])))
        return lat, lon, 'extrapolated'
    return None


def sheet_layout(sizes, width=1000, max_w=2000):
    """Where the tiles of one contact sheet go: [(w, h)] of the upright photos -> ([(x, y, tw, th)], (W, H)).
    All one orientation: 2 per row, each `width` wide (as before). Mixed landscape and portrait: every photo at the
    same scale (its long side `width`), so a landscape photo gets a landscape cell (1000 x 563) and a portrait one a
    portrait cell (563 x 1000), packed in rows in riding order; no landscape photo sits in a portrait-high cell."""
    land = [w >= h for w, h in sizes]
    mixed = len(set(land)) > 1
    tiles = []
    for (w, h) in sizes:
        k = width / max(w, h) if mixed else width / w
        tiles.append((max(1, round(w * k)), max(1, round(h * k))))
    out, x, y, row_h, in_row = [], 0, 0, 0, 0
    for tw, th in tiles:
        if in_row and (x + tw > max_w or (not mixed and in_row == 2)):
            y += row_h
            x, row_h, in_row = 0, 0, 0
        out.append((x, y, tw, th))
        x += tw
        row_h = max(row_h, th)
        in_row += 1
    W = max(x + tw for x, _y, tw, _th in out) if out else 0
    H = y + row_h
    return out, (max(W, 2 * width if not mixed else W), H)


def sheets(photos, out, per=4, width=1000):
    from PIL import Image, ImageDraw, ImageFont, ImageOps
    os.makedirs(out, exist_ok=True)
    try:
        font = ImageFont.load_default(size=36)
    except TypeError:
        font = ImageFont.load_default()
    for s in range(0, len(photos), per):
        ims = [ImageOps.exif_transpose(Image.open(p['file'])).convert('RGB') for p in photos[s:s + per]]
        boxes, (W, H) = sheet_layout([im.size for im in ims], width)
        sheet = Image.new('RGB', (W, H), 'white')
        for p, im, (x, y, tw, th) in zip(photos[s:s + per], ims, boxes):
            im = im.resize((tw, th))
            d = ImageDraw.Draw(im)
            lines = sheet_label(p)
            if len(lines) == 1 and lines[0] == f"#{p['idx']}":
                d.rectangle([0, 0, 200, 50], fill='black')
            else:   # indoor: level, directory board, unit hints under the number
                w = max([200] + [int(d.textlength(t, font=font)) + 16 for t in lines])
                d.rectangle([0, 0, min(w, tw), 8 + 44 * len(lines)], fill='black')
            for k, t in enumerate(lines):
                d.text((8, 4 + 44 * k), t, fill='yellow' if k == 0 else 'white', font=font)
            sheet.paste(im, (x, y))
        sheet.save(os.path.join(out, f"sheet_{s:03d}.jpg"), quality=85)


def sheet_label(p):
    """The lines written on a photo's tile: "#idx"; on an indoor walk also its level ("#12  lvl 2"), "DIRECTORY"
    for a directory-board photo, and the unit numbers the phone's text reader saw ("L2-15?": hints)."""
    lines = [f"#{p['idx']}" + (f"  lvl {p['level']}" if p.get('level') not in (None, '') else '')]
    if p.get('trigger') == 'directory':
        lines.append('DIRECTORY')
    if p.get('trigger') == 'plan':
        lines.append('FLOOR PLAN')
    if p.get('unitHints'):
        lines.append(' '.join(u + '?' for u in p['unitHints'][:4]))
    nu = p.get('nearUnit') or {}
    if nu.get('unit'):   # the app's guess of the unit in front (from the plan): a hint
        d = nu.get('distanceM')
        lines.append(f"near {nu['unit']}" + (f" {d:.0f}m" if isinstance(d, (int, float)) else '') + '?')
    return lines


def sign_hints(item):
    """The phone's sign detector boxes on a photo, as hints: {code, group, label, score, x, w, h, box}. x = the box
    centre, w / h its width / height (fractions of the upright photo's width / height; box = [x0, y0, x1, y1] as the
    phone gave it). build.py ranges a sign from w (a standard-size sign's width in the photo gives its distance)."""
    out = []
    for s in item.get('signs') or []:
        b = s.get('box')
        ok = isinstance(b, (list, tuple)) and len(b) == 4 and all(isinstance(v, (int, float)) for v in b)
        out.append({'code': s.get('code'), 'group': s.get('group'), 'label': s.get('label'), 'score': s.get('score'),
                    'x': round((b[0] + b[2]) / 2, 3) if ok else None,
                    'w': round(abs(b[2] - b[0]), 4) if ok else None,
                    'h': round(abs(b[3] - b[1]), 4) if ok else None,
                    'box': [round(v, 4) for v in b] if ok else None})
    return out or None


def plate_hints(item, key='plates'):
    """The phone's house-number hints with their boxes: [{text, box, heightPx, kind, src, why, line}] (box = [x0, y0,
    x1, y1] in the upright photo's pixels, as the phone gave it, None when it gave none; app 0.20+ also says kind
    "number" | "doubt", src "plate" | "sign" (a number on a plate, or in a sign's address line), why, the whole
    line read). key="platesDropped": what the phone read as a number but judged not one (price, phone, date, vehicle
    plate, the street's own number), with why."""
    out = []
    for p in item.get(key) or []:
        b = p.get('box')
        ok = isinstance(b, (list, tuple)) and len(b) == 4 and all(isinstance(v, (int, float)) for v in b)
        h = {'text': p.get('text'), 'box': [int(v) for v in b] if ok else None, 'heightPx': p.get('heightPx')}
        h.update({k: p[k] for k in ('kind', 'src', 'why', 'line') if p.get(k) is not None})
        out.append(h)
    return out or None


TARGETS = {
    'shops': 'shops: read every business (SKILL.md, step 2)',
    'addresses': 'addresses (shops + house numbers): read every business and every readable house-number plate, '
                 'shop or not ("addresses" in places.json)',
    'signs': 'signs: the camera looked FORWARD, along the road: read every traffic sign ("signs" in places.json); '
             "a sign's x across the frame is an angle off the travel bearing (hFovDeg of each photo), not off a "
             'sideways view; positions come from 2-3 sightings as the rider approaches',
}


def target_line(session):
    """What the ride was captured for (session.target; older apps and Place capture: shops)."""
    t = session.get('target') or 'shops'
    return 'target: ' + TARGETS.get(t, f'{t} (unknown: read it as shops)')


def find_plans(src, work, session):
    """The walk's floor plans (indoor.plans_of: session.building.plans, else plans/plans.json), each with `path`: its
    image in the session folder / export (or <work>/raw), checked to stay inside it; a plan whose image is missing
    gets path None."""
    dirs = [d for d in (src, os.path.join(work, 'raw')) if d and os.path.isdir(d)]
    pj = None
    for d in dirs:
        f = os.path.join(d, 'plans', 'plans.json')
        if os.path.isfile(f):
            try:
                pj = json.load(open(f, encoding='utf-8'))
            except ValueError:
                pj = None
            break
    out = []
    for p in indoor.plans_of(session, pj):
        p['path'] = None
        for d in dirs:
            try:
                f = inside(d, p['file'])
            except ValueError:
                break   # a path leaving the session: not used
            if os.path.isfile(f):
                p['path'] = f
                break
        out.append(p)
    return out


def _grid_sheet(im, box, k, step, file, title, marks=(), labels=()):
    """The part `box` (x0, y0, x1, y1 in plan px) of the plan, scaled by k, with a grid every `step` plan px (every
    5th line stronger) and rulers on all four sides labelled in plan px; marks = [(x, y, text)] in plan px; labels =
    the unit numbers the phone read (plan "units": a green box round each, its reading above)."""
    from PIL import Image, ImageDraw, ImageFont
    x0, y0, x1, y1 = box
    try:
        font = ImageFont.load_default(size=20)
        big = ImageFont.load_default(size=26)
    except TypeError:
        font = big = ImageFont.load_default()
    w, h = max(1, round((x1 - x0) * k)), max(1, round((y1 - y0) * k))
    M, T = 56, 40
    part = im.crop((x0, y0, x1, y1)).resize((w, h)).convert('RGBA')
    over = Image.new('RGBA', part.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(over)
    sheet = Image.new('RGB', (w + 2 * M, h + 2 * M + T), 'white')
    ds = ImageDraw.Draw(sheet)
    ds.text((M, 6), title, fill='black', font=big)
    first = lambda a: (a + step - 1) // step * step
    for x in range(first(x0), x1 + 1, step):
        X = round((x - x0) * k)
        strong = x % (5 * step) == 0
        d.line([(X, 0), (X, h)], fill=(220, 0, 0, 170 if strong else 90), width=2 if strong else 1)
        for y in (T + M - 24, T + M + h + 4):
            ds.text((M + X, y), str(x), fill=(200, 0, 0), font=font, anchor='mt')
    for y in range(first(y0), y1 + 1, step):
        Y = round((y - y0) * k)
        strong = y % (5 * step) == 0
        d.line([(0, Y), (w, Y)], fill=(220, 0, 0, 170 if strong else 90), width=2 if strong else 1)
        ds.text((M - 4, T + M + Y), str(y), fill=(200, 0, 0), font=font, anchor='rm')
        ds.text((M + w + 4, T + M + Y), str(y), fill=(200, 0, 0), font=font, anchor='lm')
    for mx, my, text in marks:
        if x0 <= mx <= x1 and y0 <= my <= y1:
            X, Y = (mx - x0) * k, (my - y0) * k
            d.ellipse([X - 12, Y - 12, X + 12, Y + 12], outline=(0, 90, 255, 255), width=4)
            d.text((X + 16, Y - 12), text, fill=(0, 90, 255, 255), font=font)
    for u in labels:
        if x0 <= u['x'] <= x1 and y0 <= u['y'] <= y1:
            X, Y = (u['x'] - x0) * k, (u['y'] - y0) * k
            hh = max(6.0, (u.get('h') or 14) * k * 0.7)
            ww = max(hh * 2, hh * 0.6 * len(u['unit']))
            d.rectangle([X - ww, Y - hh, X + ww, Y + hh], outline=(0, 150, 60, 230), width=2)
            d.text((X - ww, Y - hh - 2), 'phone: ' + u['unit'], fill=(0, 130, 50, 255), font=font, anchor='lb')
    sheet.paste(Image.alpha_composite(part, over).convert('RGB'), (M, T + M))
    sheet.save(file, quality=88)


def plan_sheets(plans, work, step=100, whole_px=1800, tile=800, overlap=100, tile_px=1600):
    """Sheets to read a floor plan from: the whole plan (long edge whole_px) and, when that shrinks it, tiles of
    `tile` plan px (overlapping by `overlap`, starting on the grid) enlarged to about tile_px, all with the grid
    and rulers in plan px (step). Writes <work>/plans/ and <work>/plans.json. -> [(plan, [sheet files])]."""
    from PIL import Image, ImageOps
    d = os.path.join(work, 'plans')
    os.makedirs(d, exist_ok=True)
    res = []
    for p in plans:
        files = []
        if p.get('path'):
            im = ImageOps.exif_transpose(Image.open(p['path'])).convert('RGB')
            W, H = im.size
            stem = os.path.splitext(os.path.basename(p['file']))[0]
            stem = ''.join(c if c.isalnum() or c in '-_' else '_' for c in stem) or 'plan'
            marks = [(p['youAreHere'][0], p['youAreHere'][1], 'you are here')] if p.get('youAreHere') else []
            labels = indoor.phone_units(p)
            k = min(2.0, whole_px / max(W, H))
            f = os.path.join(d, f'{stem}.jpg')
            _grid_sheet(im, (0, 0, W, H), k, step, f,
                        f"{p['file']}  level {p.get('level')}  {W} x {H} px  (grid: plan px every {step})", marks, labels)
            files.append(f)
            if k < 1.0:
                n = 0
                for ty in range(0, max(1, H - overlap), tile - overlap):
                    for tx in range(0, max(1, W - overlap), tile - overlap):
                        box = (tx, ty, min(W, tx + tile), min(H, ty + tile))
                        kt = min(3.0, tile_px / max(box[2] - box[0], box[3] - box[1]))
                        f = os.path.join(d, f'{stem}_t{n:02d}.jpg')
                        _grid_sheet(im, box, kt, step, f, f"{p['file']}  tile {n}: x {box[0]}-{box[2]}, "
                                                          f"y {box[1]}-{box[3]} (plan px)", marks, labels)
                        files.append(f)
                        n += 1
        p['sheets'] = files
        res.append((p, files))
    json.dump(plans, open(os.path.join(work, 'plans.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    return res


def plans_line(res):
    """The prepare printout's lines about the floor plans."""
    if not res:
        return None
    lines = [f'floor plans: {len(res)} (read every one: places.json planUnits, SKILL.md "Floor plans")']
    for p, files in res:
        tm = p['toMap']
        lines.append(f"  level {p.get('level')}: {p['file']} {p.get('size')} placed by {p.get('placedBy')}, "
                     f"{tm['scaleMPerPx']} m/px, rotated {tm.get('rotationDeg') or 0} deg"
                     + (f", you are here at {p['youAreHere']}" if p.get('youAreHere') else '')
                     + (f" -> {len(files)} sheet(s): {files[0]}" + (' + tiles' if len(files) > 1 else '')
                        if files else ' -> IMAGE MISSING in the session'))
        pu = indoor.phone_units(p)
        if pu:
            lines.append(f"    the phone read {len(pu)} unit label(s) (green on the sheets; a start: check, correct, "
                         f"outline): " + ', '.join(f"{u['unit']} ({u['x']:.0f}, {u['y']:.0f})" for u in pu[:15])
                         + (' ...' if len(pu) > 15 else ''))
    return '\n'.join(lines)


def diagnostics_line(session, ev, summary):
    """For tuning the phone's dead reckoning: the anchors by how, the drift each anchor corrected (metres and % of
    the distance walked since the one before), the other (diagnostic) lines in indoor.jsonl, session.indoor.diag."""
    ind = (session or {}).get('indoor') or {}
    hows = {}
    for a in (ev or {}).get('anchors', []):
        hows[a.get('how')] = hows.get(a.get('how'), 0) + 1
    out = [f"indoor diagnostics: anchors {hows or 'none'}; step {summary.get('stepLengthM') or ind.get('stepLengthM')} m "
           f"({ind.get('stepSource') or '?'}), {summary.get('steps', ind.get('steps'))} steps, walked {ind.get('walkedM')} m, "
           f"barometer {ind.get('barometer')}"]
    cs = summary.get('corrections') or []
    if cs:
        pc = sorted(c['pct'] for c in cs if c['pct'] is not None)
        out.append('  drift corrected at each anchor: ' + ', '.join(
            f"{c['gapM']} m / {c['walkedM']} m" + (f" ({c['pct']} %)" if c['pct'] is not None else '') + f" [{c['how']}]"
            for c in cs[:12]) + (f' ... ({len(cs)})' if len(cs) > 12 else '')
            + (f"; median {pc[len(pc) // 2]} %, max {pc[-1]} %" if pc else ''))
    if (ev or {}).get('other'):
        out.append(f"  other lines in indoor.jsonl: {ev['other']}")
    if ind.get('diag') is not None:
        d = json.dumps(ind['diag'], ensure_ascii=False)
        out.append('  session.indoor.diag: ' + (d if len(d) <= 600 else d[:600] + ' ...'))
    return '\n'.join(out)


def indoor_positions(src, work, session, items, photos, plans=()):
    """An indoor walk (docs/indoor.md): every photo's position recomputed from indoor.jsonl (indoor.recompute: the
    walk split at the anchors, each stretch rotated / scaled onto them, kept inside the building), the phone's live
    one kept as livePosition; level, uncertainty, unit hints. Writes <work>/indoor_track.json (the corrected walk
    with levels, for the map). Without indoor.jsonl (or without anchors in it) the live positions stay.
    -> a line for the report."""
    log = indoor.find_log(src, work)
    ev = indoor.load(log) if log else None
    n_plan = sum(1 for a in (ev or {}).get('anchors', []) if a.get('how') == 'plan')
    replaced = indoor.plan_anchors(ev, list(plans)) if ev and plans else 0
    times = [i.get('elapsedNanos') for i in items]
    fixes, track, summary = indoor.recompute(ev, times, session) if ev else ([None] * len(items), [], {})
    moved, placed = [], 0
    for i, p, f in zip(items, photos, fixes):
        live = {'lat': i.get('lat'), 'lon': i.get('lon')} if i.get('lat') is not None else None
        own = str(i['level']) if i.get('level') not in (None, '') else None
        p['livePosition'] = live
        p['fixLat'] = p['fixLon'] = None
        p['level'] = indoor.level_at(ev, i['elapsedNanos'], own) if ev and i.get('elapsedNanos') is not None else own
        p['sinceAnchorM'] = i.get('sinceAnchorM')
        p['positionSource'] = i.get('positionSource')
        p['unitHints'] = indoor.unit_hints(p.get('ocrText'))
        p['nearUnit'] = i.get('nearUnit') if isinstance(i.get('nearUnit'), dict) else None   # the app's hint
        p['roadName'] = None   # no road indoors
        if f:
            placed += 1
            if live:
                from build import to_xy
                moved.append(math.hypot(*to_xy(f['lat'], f['lon'], live['lat'], live['lon'])))
            p['lat'], p['lon'] = f['lat'], f['lon']
            p['positionFrom'] = 'indoor walk: ' + f['how']
            p['sinceAnchorM'], p['uncertaintyM'] = f['sinceAnchorM'], f['uncertaintyM']
            p['clampedM'] = f['clampedM'] or None
            if f['bearingDeltaDeg'] is not None and p.get('cameraBearing') is not None:
                p['cameraBearingLive'] = p['cameraBearing']
                p['cameraBearing'] = round((p['cameraBearing'] + f['bearingDeltaDeg']) % 360, 1)
        else:
            p['positionFrom'] = 'indoor walk: live estimate (phone)' if live else None
            p['uncertaintyM'] = (round(max(indoor.MIN_UNC_M, indoor.UNC_PER_M * (p['sinceAnchorM'] or 0)), 1)
                                 if live else None)
        if p.get('uncertaintyM') is not None:
            p['accuracyM'] = p['uncertaintyM']   # weighs the rays when a shop is triangulated (build.locate)
    if not track:   # the live positions, in order
        track = [[p['lat'], p['lon'], p['level']] for p in photos if p.get('lat') is not None]
    json.dump({'track': track, 'summary': summary, 'log': bool(log)},
              open(os.path.join(work, 'indoor_track.json'), 'w', encoding='utf-8'), ensure_ascii=False)
    b = session.get('building') or {}
    levels = {}
    for p in photos:
        levels[p['level']] = levels.get(p['level'], 0) + 1
    if not log:
        how = 'no indoor.jsonl: the live positions kept'
    elif not placed:
        how = f"indoor.jsonl not usable ({summary.get('why')}): the live positions kept"
    else:
        m = sorted(moved)
        how = (f"{placed} photo positions recomputed from {summary['steps']} steps and {summary['anchors']} anchors "
               f"({summary['stretches'] or 'translated only'})"
               + (f"; moved from the live estimate median {m[len(m) // 2]:.1f} m, max {m[-1]:.1f} m" if m else ''))
    if n_plan:
        how += (f"; {n_plan} anchor(s) tapped on a floor plan"
                + (f", {replaced} placed again through the plan's final position" if replaced else ''))
    how += '\n' + diagnostics_line(session, ev, summary)
    return (f"indoor walk in {b.get('name') or 'no building'} ({b.get('osm') or 'no footprint'}): {how}\n"
            f"levels (photos): {levels}; directory photos: {sum(1 for p in photos if p.get('trigger') == 'directory')}")


def main():
    src, work = arg_path(sys.argv[1]), arg_path(sys.argv[2])
    os.makedirs(work, exist_ok=True)
    session, items = load(src, work)
    in_door = indoor.is_indoor(session)
    track = [] if in_door else load_track(src, work)   # indoor: the live track is the phone's own estimate
    moved, how = [], {}
    photos = []
    for n, i in enumerate(items):
        p = place_in_time(i, track) if i.get('lat') is not None else None
        if p:
            from build import to_xy
            moved.append(math.hypot(*to_xy(p[0], p[1], i['lat'], i['lon'])))
            how[p[2]] = how.get(p[2], 0) + 1
            i['fixLat'], i['fixLon'] = i['lat'], i['lon']
            i['lat'], i['lon'] = p[0], p[1]
        photos.append({
            'positionFrom': p[2] if p else ('gps fix' if i.get('lat') is not None else None),
            'fixLat': i.get('fixLat'), 'fixLon': i.get('fixLon'),
            'idx': n, 'id': i['id'], 'file': i['_path'], 't': i.get('t'),
            'lat': i.get('lat'), 'lon': i.get('lon'), 'accuracyM': i.get('accuracyM'),
            'cameraBearing': i.get('cameraBearing'), 'cameraBearingSource': i.get('cameraBearingSource'),
            'hFovDeg': i.get('hFovDeg'), 'width': i.get('width'), 'height': i.get('height'),
            'orientation': i.get('orientation'), 'speedMps': i.get('speedMps'), 'roadName': i.get('roadName'),
            'facadeDistanceM': i.get('facadeDistanceM'), 'blurry': (i.get('blur') or {}).get('blurry'),
            'sharpness': (i.get('blur') or {}).get('sharpness'),
            'compassDeg': (i.get('motion') or {}).get('cameraAzimuthDeg'), 'travelBearing': i.get('travelBearing'),
            'ocrText': (i.get('ocr') or {}).get('text'),
            # house-number rides: the numbers the phone's text reader found on this photo (a hint where to look)
            'plates': [p.get('text') for p in i.get('plates') or []] or None,
            # ... with where it is (box in the upright photo's pixels, app 0.17+): crops.py plates tiles them
            'plateHints': plate_hints(i),
            'platesDropped': [f"{p.get('text')} ({p.get('why') or 'not a number'})" for p in i.get('platesDropped') or []] or None,
            # signs rides (app 0.18+): what the phone's sign detector found, box centre x (0 = left) and score; a
            # hint where to look: about half are shop letters or logos, and the code is right on about a third
            # signs rides (app 0.19+): "sign" = an extra photo taken as a near sign reached the edge of the frame
            # (its closest sharp view); signBoost = taken while photos came denser because a sign was in view
            'trigger': i.get('trigger'), 'signBoost': i.get('signBoost'),
            'signHints': sign_hints(i),
        })
    plans = find_plans(src, work, session) if in_door else []
    indoor_line = indoor_positions(src, work, session, items, photos, plans) if in_door else None
    plan_line = plans_line(plan_sheets(plans, work)) if plans else None
    json.dump(photos, open(os.path.join(work, 'photos.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    json.dump(session, open(os.path.join(work, 'session.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    sheets(photos, os.path.join(work, 'sheets'))
    plate_line = None
    if (session.get('target') == 'addresses' or any(p.get('plateHints') for p in photos)) and '--no-crops' not in sys.argv:
        import crops
        n, t, nobox = crops.plates(work)
        plate_line = (f"plate hints: {t} tiles on {n} sheet(s) in {os.path.join(work, 'crops')} (plates_NN.jpg)"
                      + (f', {nobox} without a box' if nobox else '')
                      + '; crops.py band / boxes for the rest (SKILL.md)')
    if indoor_line:
        print(indoor_line)
    if plan_line:
        print(plan_line)
    roads = {}
    for p in photos:
        roads[p['roadName']] = roads.get(p['roadName'], 0) + 1
    print(f"session {session.get('id')} | {session.get('kind')} | {session.get('name')}")
    print(target_line(session))
    print(f"{len(photos)} photos -> {(len(photos) + 3) // 4} sheets in {os.path.join(work, 'sheets')}")
    if not in_door:
        print("roads:", sorted(roads.items(), key=lambda kv: -kv[1])[:5])
    if plate_line:
        print(plate_line)
    if in_door:
        pass
    elif moved:
        m = sorted(moved)
        print(f"photo positions from the GPS track by time: {how}, moved median {m[len(m) // 2]:.1f} m, max {m[-1]:.1f} m "
              f"({len(items) - len(moved)} kept at the fix)")
    else:
        print("photo positions: as stamped (no track.jsonl with elapsed times)")


if __name__ == '__main__':
    main()
