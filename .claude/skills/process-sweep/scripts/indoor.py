"""Indoor walks (session.kind "indoor", docs/indoor.md): positions recomputed from the phone's raw sensor log, the
building footprint, levels and units, and the mall's directory boards.

Used by prepare.py (positions, contact-sheet labels) and build.py (places kept inside the building, directory
matching, the building's address). Nothing here asks Overpass: the building comes with the session
(session.building).

indoor.jsonl (one JSON per line, t = elapsedRealtimeNanos, the photos' elapsedNanos clock):
  {"type": "step"} | {"type": "yaw", "deg"} | {"type": "pressure", "hPa"} |
  {"type": "anchor", "lat", "lon", "level", "how", "headingDeg"} | {"type": "level", "level", "how"} |
  {"type": "align", "offsetDeg", "how"} | {"type": "plan", "level", "file"} (a floor plan placed or moved: not used here;
  its "you are here" tap is an anchor with how "plan", placed again through the plan's final toMap by plan_anchors)

Floor plans (docs/indoor.md §7): plan_xy / plan_to_ll map a straightened board's pixels onto the map (toMap), the
plan units' outlines (places.json planUnits) become polygons with a centre (plan_unit_geo).

recompute(): the walk is dead-reckoned again from the steps and the (aligned) yaw, then split at the anchors:
- a stretch between two anchors is rotated and scaled about its first anchor so it starts and ends on them (the
  drift spread along it); a stretch too short or a loop (start and end close together, so the angle and scale are
  meaningless) is translated instead and the gap at its end spread along it by distance walked;
- before the first anchor and after the last: translated only (one anchor: the whole walk translated);
- every point kept inside the footprint (moved to the nearest point of the wall, 0.3 m in: "slides along the
  wall"), except near an anchor outside it (an entrance GPS fix: the walk starts outdoors);
- the uncertainty: max(3 m, 5 % of the distance walked to the nearest anchor).
"""
import bisect, difflib, json, math, os, re, unicodedata

MIN_UNC_M = 3.0
UNC_PER_M = 0.05
STEP_M = 0.7
WALL_IN_M = 0.3
OUTSIDE_FREE_M = 15.0     # walked metres from an anchor outside the footprint in which the walk is not clamped


def to_xy(lat, lon, lat0, lon0):
    return ((lon - lon0) * 111320 * math.cos(math.radians(lat0)), (lat - lat0) * 110540)


def to_ll(x, y, lat0, lon0):
    return (lat0 + y / 110540, lon0 + x / (111320 * math.cos(math.radians(lat0))))


def is_indoor(session):
    return (session or {}).get('kind') == 'indoor'


# ---------- the raw log ----------

def find_log(src, work):
    """indoor.jsonl in the session folder / export, or in <work>/raw (an extracted zip)."""
    for d in (src, os.path.join(work, 'raw')):
        if d and os.path.isdir(d):
            for name in ('indoor.jsonl', os.path.join('indoor', 'indoor.jsonl')):
                f = os.path.join(d, name)
                if os.path.isfile(f):
                    return f
    return None


def parse(lines):
    """{steps: [t], yaws: [(t, deg)], anchors: [{t, lat, lon, level, how, headingDeg}], levels: [(t, level, how)]}.
    Lines that are not JSON, or lack t, are skipped (a log cut short by a crash)."""
    ev = {'steps': [], 'yaws': [], 'anchors': [], 'levels': [], 'other': {}}
    for line in lines:
        try:
            o = json.loads(line)
        except ValueError:
            continue
        if not isinstance(o, dict) or not isinstance(o.get('t'), (int, float)):
            continue
        k = o.get('type')
        if k == 'step':
            ev['steps'].append(o['t'])
        elif k == 'yaw' and isinstance(o.get('deg'), (int, float)):
            ev['yaws'].append((o['t'], float(o['deg'])))
        elif k == 'anchor' and isinstance(o.get('lat'), (int, float)) and isinstance(o.get('lon'), (int, float)):
            ev['anchors'].append({'t': o['t'], 'lat': o['lat'], 'lon': o['lon'], 'level': _lv(o.get('level')),
                                  'how': o.get('how'), 'headingDeg': o.get('headingDeg')})
        elif k == 'level' and o.get('level') not in (None, ''):
            ev['levels'].append((o['t'], _lv(o['level']), o.get('how')))
        else:   # diagnostics (lookup, plan-corners, plan-place, pdr, floor-prompt, gps, align, plan...): counted only
            ev['other'][str(k)] = ev['other'].get(str(k), 0) + 1
    ev['steps'].sort()
    ev['yaws'].sort()
    ev['anchors'].sort(key=lambda a: a['t'])
    ev['levels'].sort(key=lambda x: x[0])
    return ev


def load(path):
    with open(path, encoding='utf-8') as f:
        return parse(f)


def _lv(v):
    return None if v is None or str(v).strip() == '' else str(v).strip()


# ---------- the building ----------

def rings(building):
    """(outer rings, holes) of the building in [[lat, lon], ...]: "outer" (one or more rings) and "holes" when
    given, else the single "footprint" ring."""
    b = building or {}
    outer = [r for r in (b.get('outer') or []) if _ring_ok(r)]
    if not outer and _ring_ok(b.get('footprint')):
        outer = [b['footprint']]
    holes = [r for r in (b.get('holes') or []) if _ring_ok(r)]
    return outer, holes


def _ring_ok(r):
    return isinstance(r, list) and len(r) >= 3 and all(isinstance(p, (list, tuple)) and len(p) >= 2 for p in r)


def _inside_ring(x, y, ring):
    c = False
    for (x1, y1), (x2, y2) in zip(ring, ring[1:] + ring[:1]):
        if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1:
            c = not c
    return c


def _nearest_on(x, y, ring):
    best = None
    for (x1, y1), (x2, y2) in zip(ring, ring[1:] + ring[:1]):
        dx, dy = x2 - x1, y2 - y1
        L = dx * dx + dy * dy
        f = 0 if L == 0 else max(0.0, min(1.0, ((x - x1) * dx + (y - y1) * dy) / L))
        px, py = x1 + f * dx, y1 + f * dy
        d = math.hypot(px - x, py - y)
        if best is None or d < best[0]:
            best = (d, px, py)
    return best


class Footprint:
    """The building in a local metre frame: inside() and clamp() (to the nearest point of the wall, a little in)."""

    def __init__(self, building):
        outer, holes = rings(building)
        self.ok = bool(outer)
        if not self.ok:
            return
        self.lat0, self.lon0 = outer[0][0][0], outer[0][0][1]
        xy = lambda r: [to_xy(p[0], p[1], self.lat0, self.lon0) for p in r]
        self.outer, self.holes = [xy(r) for r in outer], [xy(r) for r in holes]

    def _in_xy(self, x, y):
        return any(_inside_ring(x, y, r) for r in self.outer) and not any(_inside_ring(x, y, r) for r in self.holes)

    def inside(self, lat, lon):
        if not self.ok:
            return True
        return self._in_xy(*to_xy(lat, lon, self.lat0, self.lon0))

    def clamp(self, lat, lon):
        """(lat, lon, moved metres): unchanged inside; outside (or in a courtyard hole) moved onto the nearest wall
        and WALL_IN_M further in."""
        if not self.ok:
            return lat, lon, 0.0
        x, y = to_xy(lat, lon, self.lat0, self.lon0)
        if self._in_xy(x, y):
            return lat, lon, 0.0
        in_hole = [r for r in self.holes if _inside_ring(x, y, r)]
        cands = [_nearest_on(x, y, r) for r in (in_hole or self.outer + self.holes)]
        d, px, py = min(cands)
        if d > 0:
            px, py = px + WALL_IN_M * (px - x) / d, py + WALL_IN_M * (py - y) / d
        la, lo = to_ll(px, py, self.lat0, self.lon0)
        return la, lo, math.hypot(px - x, py - y)

    def centre(self):
        if not self.ok:
            return None
        r = self.outer[0]
        x, y = sum(p[0] for p in r) / len(r), sum(p[1] for p in r) / len(r)
        return to_ll(x, y, self.lat0, self.lon0)


def address_of(building):
    """The building's address from its OSM tags: {housenumber, street, ...} ("addr" without the prefix, or
    addr:* in "tags"); {} when none."""
    b = building or {}
    out = {}
    for k, v in (b.get('tags') or {}).items():
        if k.startswith('addr:') and v:
            out[k[5:]] = v
    for k, v in (b.get('addr') or {}).items():
        if v:
            out[k.removeprefix('addr:')] = v
    return out


def address_text(addr):
    parts = [' '.join(x for x in (addr.get('housenumber'), addr.get('street')) if x)]
    parts += [addr.get(k) for k in ('subdistrict', 'quarter', 'district', 'city', 'province')]
    return ', '.join(p for p in parts if p) or None


def building_block(building):
    """What every place of the walk carries about its building (place.json place.building)."""
    b = building or {}
    addr = address_of(b)
    return {'osm': b.get('osm'), 'name': b.get('name'), 'addr': addr or None, 'address': address_text(addr)}


# ---------- recomputing the walk ----------

def _yaw_at(yaws, ts, t):
    k = bisect.bisect_right(ts, t) - 1
    return yaws[max(k, 0)][1]


def dead_reckon(ev, step_m):
    """The raw walk: [(t, x, y, walked)] in metres from where the log starts (0, 0), one point per step."""
    yaws = ev['yaws']
    ts = [y[0] for y in yaws]
    t0 = min([x['t'] for x in ev['anchors']] + ev['steps'][:1] + ts[:1])
    path = [(t0, 0.0, 0.0, 0.0)]
    x = y = d = 0.0
    for t in ev['steps']:
        if not yaws:
            break
        h = math.radians(_yaw_at(yaws, ts, t))
        x, y, d = x + step_m * math.sin(h), y + step_m * math.cos(h), d + step_m
        path.append((t, x, y, d))
    return path


def _at(path, ts, t):
    """(x, y, walked) on the raw path at time t (linear between steps)."""
    k = bisect.bisect_right(ts, t)
    if k <= 0:
        return path[0][1:]
    if k >= len(path):
        return path[-1][1:]
    a, b = path[k - 1], path[k]
    f = 0 if b[0] == a[0] else (t - a[0]) / (b[0] - a[0])
    return tuple(a[i] + f * (b[i] - a[i]) for i in (1, 2, 3))


def _stretch(ra, rb, A, B, da, db):
    """A function raw (x, y, walked) -> corrected (x, y), and its rotation in degrees (counterclockwise, or None)
    and how. Similarity when well-conditioned, else translation + the end gap spread by distance walked."""
    vr = (rb[0] - ra[0], rb[1] - ra[1])
    vt = (B[0] - A[0], B[1] - A[1])
    lr, lt = math.hypot(*vr), math.hypot(*vt)
    walked = max(db - da, 1e-9)
    s = lt / lr if lr > 0 else 0
    if lr >= 3 and lt >= 3 and 0.5 <= s <= 2 and lr >= 0.25 * walked:
        th = math.atan2(vt[1], vt[0]) - math.atan2(vr[1], vr[0])
        c, sn = math.cos(th), math.sin(th)

        def f(x, y, _d):
            px, py = x - ra[0], y - ra[1]
            return A[0] + s * (c * px - sn * py), A[1] + s * (sn * px + c * py)
        return f, math.degrees(th), f'rotated {math.degrees(th):+.0f} deg, scaled x{s:.2f} between two anchors'
    ea, eb = (A[0] - ra[0], A[1] - ra[1]), (B[0] - rb[0], B[1] - rb[1])

    def g(x, y, d):
        k = max(0.0, min(1.0, (d - da) / walked))
        return x + ea[0] + k * (eb[0] - ea[0]), y + ea[1] + k * (eb[1] - ea[1])
    return g, None, 'translated, the gap at the next anchor spread along the stretch (short or a loop)'


def level_at(ev, t, fallback=None):
    """The walker's level at time t: the last level event or anchor with a level before t; else the photo's own
    level (fallback); else the first one logged."""
    marks = sorted([(x[0], x[1]) for x in ev['levels']] + [(a['t'], a['level']) for a in ev['anchors'] if a['level']])
    before = [lv for tt, lv in marks if tt <= t]
    if before:
        return before[-1]
    if fallback not in (None, ''):
        return str(fallback)
    return marks[0][1] if marks else None


def recompute(ev, times, session):
    """Positions for the given times (elapsedNanos; None = unknown) from the log.

    -> (fixes, track, summary): fixes[i] = {lat, lon, level, uncertaintyM, sinceAnchorM, how, bearingDeltaDeg,
    clampedM} or None (no time, or nothing to place it with); track = [[lat, lon, level], ...] about every 1.5 m;
    summary = {anchors, stretches: {how: n}, stepLengthM, steps, why?}. Without an anchor (or without steps / yaw)
    nothing can be placed: every fix is None and the caller keeps the phone's live positions."""
    step_m = float(((session or {}).get('indoor') or {}).get('stepLengthM') or STEP_M)
    anchors = ev['anchors']
    summary = {'anchors': len(anchors), 'stretches': {}, 'stepLengthM': step_m, 'steps': len(ev['steps'])}
    if not anchors or not ev['steps'] or not ev['yaws']:
        summary['why'] = ('no anchor in indoor.jsonl' if not anchors else 'no steps / yaw in indoor.jsonl')
        return [None] * len(times), [], summary
    path = dead_reckon(ev, step_m)
    ts = [p[0] for p in path]
    lat0, lon0 = anchors[0]['lat'], anchors[0]['lon']
    fp = Footprint((session or {}).get('building'))
    A = [to_xy(a['lat'], a['lon'], lat0, lon0) for a in anchors]
    R = [_at(path, ts, a['t']) for a in anchors]          # (x, y, walked) of the raw walk at each anchor
    D = [r[2] for r in R]
    outside = [not fp.inside(a['lat'], a['lon']) for a in anchors]

    def piece(walked):
        """(transform, rotation, how) for a point walked metres into the log."""
        if len(anchors) == 1 or walked <= D[0]:
            k = 0 if walked <= D[0] else len(anchors) - 1
            dx, dy = A[k][0] - R[k][0], A[k][1] - R[k][1]
            what = ('translated onto the only anchor' if len(anchors) == 1 else
                    'before the first anchor: translated')
            return (lambda x, y, _d: (x + dx, y + dy)), None, what
        if walked >= D[-1]:
            dx, dy = A[-1][0] - R[-1][0], A[-1][1] - R[-1][1]
            return (lambda x, y, _d: (x + dx, y + dy)), None, 'after the last anchor: translated'
        k = max(i for i in range(len(D) - 1) if D[i] <= walked)
        return pieces[k]

    pieces = [_stretch(R[i], R[i + 1], A[i], A[i + 1], D[i], D[i + 1]) for i in range(len(anchors) - 1)]

    def fix(x, y, walked, level):
        f, rot, how = piece(walked)
        cx, cy = f(x, y, walked)
        la, lo = to_ll(cx, cy, lat0, lon0)
        near = [abs(walked - d) for d in D]
        if len(anchors) > 1 and D[0] < walked < D[-1]:
            j = max(i for i in range(len(D) - 1) if D[i] <= walked)
            since = min(walked - D[j], D[j + 1] - walked)
        else:
            since = min(near)
        free = any(o and abs(walked - d) <= OUTSIDE_FREE_M for o, d in zip(outside, D))
        moved = 0.0
        if not free:
            la, lo, moved = fp.clamp(la, lo)
        return {'lat': la, 'lon': lo, 'level': level, 'uncertaintyM': round(max(MIN_UNC_M, UNC_PER_M * since), 1),
                'sinceAnchorM': round(since, 1), 'how': how, 'bearingDeltaDeg': None if rot is None else -rot,
                'clampedM': round(moved, 1) if moved else 0.0}

    fixes = []
    for t in times:
        if t is None:
            fixes.append(None)
            continue
        x, y, walked = _at(path, ts, t)
        fixes.append(fix(x, y, walked, level_at(ev, t)))
    for _f, _r, how in pieces:
        summary['stretches'][how] = summary['stretches'].get(how, 0) + 1
    # how far the dead-reckoned walk was off at each anchor, from the one before: the drift corrected
    summary['corrections'] = []
    for i in range(1, len(anchors)):
        gap = math.hypot((A[i][0] - A[i - 1][0]) - (R[i][0] - R[i - 1][0]), (A[i][1] - A[i - 1][1]) - (R[i][1] - R[i - 1][1]))
        w = D[i] - D[i - 1]
        summary['corrections'].append({'how': anchors[i].get('how'), 'gapM': round(gap, 1), 'walkedM': round(w, 1),
                                       'pct': round(100 * gap / w, 1) if w > 0 else None})
    track, last = [], None
    for t, x, y, walked in path:
        lv = level_at(ev, t)
        if last is not None and walked - last[0] < 1.5 and lv == last[1]:
            continue
        q = fix(x, y, walked, lv)
        track.append([round(q['lat'], 7), round(q['lon'], 7), lv])
        last = (walked, lv)
    return fixes, track, summary


# ---------- levels and units ----------

_UNIT = re.compile(r'(?<![A-Za-z0-9])((?:LG|UG|SH|[LBGK])\s?\d{0,2}\s?[-.]\s?\d{1,3}[A-Za-z]?)(?![A-Za-z0-9])', re.I)


def unit_hints(text):
    """Unit numbers in the phone's OCR text ("L2-15", "B1-03", "K-05", "SH 12"): hints, not readings."""
    out = []
    for m in _UNIT.finditer(text or ''):
        u = norm_unit(m.group(1))
        if u and u not in out:
            out.append(u)
    return out or None


def norm_unit(u):
    """"l2 - 015" -> "L2-15", "B1.03" -> "B1-3": upper case, no spaces, "-" between the parts, no leading zeros."""
    s = re.sub(r'\s+', '', str(u or '')).upper().replace('.', '-').replace('_', '-')
    s = re.sub(r'(?<![0-9])0+(?=\d)', '', s)
    return s or None


def unit_tail(u):
    """The unit's own number without its level prefix: "L2-15" -> "15", "15A" -> "15A"."""
    m = re.search(r'(\d+[A-Z]?)$', norm_unit(u) or '')
    return m.group(1) if m else None


def norm_level(v):
    """A level as the building names it, tidied: "Tầng 2" / "L2" / "Lầu 2" / "level 2" -> "2", "Hầm 1" / "B1" ->
    "B1", "Trệt" / "GF" / "Ground" -> "G". Other names are kept as written."""
    if v is None:
        return None
    s = _fold(str(v)).strip()
    if not s:
        return None
    if re.fullmatch(r'(?:tret|gf|g|ground(?: floor)?)', s):
        return 'G'
    m = re.fullmatch(r'(?:ham|basement|b)\s*(\d+)', s)
    if m:
        return 'B' + str(int(m.group(1)))
    m = re.fullmatch(r'(?:tang|lau|level|floor|l)?\s*(\d+)', s)
    if m:
        return str(int(m.group(1)))
    return str(v).strip()


def level_key(v):
    """Sort key of a level, lowest first: B2, B1, G, 1, 2, ...; names that are not levels last."""
    n = norm_level(v) or ''
    if re.fullmatch(r'B\d+', n):
        return (0, -int(n[1:]), '')
    if n == 'G':
        return (1, 0, '')
    if re.fullmatch(r'-?\d+', n):
        return (1, int(n), '')
    return (2, 0, n)


def unit_level(u):
    """The level a unit number names: "L2-15" -> "2", "B1-03" -> "B1", "G-12" -> "G"; None when it names none."""
    m = re.match(r'^(LG|UG|[LBG])(\d*)-', norm_unit(u) or '')
    if not m:
        return None
    p, n = m.group(1), m.group(2)
    if p == 'L' and n:
        return str(int(n))
    if p == 'B' and n:
        return 'B' + str(int(n))
    if p == 'G' and not n:
        return 'G'
    return None


def _fold(s):
    s = unicodedata.normalize('NFD', (s or '').lower().replace('đ', 'd'))
    return ''.join(c for c in s if unicodedata.category(c) != 'Mn')


def _name_key(s):
    return ' '.join(re.findall(r'[a-z0-9]+', _fold(s)))


def similar_names(a, b):
    """Two shop names that are the same shop: equal once folded, one inside the other (a word of 4+ letters at
    least), or close (difflib ratio >= 0.8)."""
    x, y = _name_key(a), _name_key(b)
    if not x or not y:
        return False
    if x == y:
        return True
    short, long_ = (x, y) if len(x) <= len(y) else (y, x)
    if len(short) >= 4 and f' {short} ' in f' {long_} ':
        return True
    return difflib.SequenceMatcher(None, x, y).ratio() >= 0.8


def match_directory(directory, places):
    """Directory entries ({level, unit, name, photo}) against the places ({name, level, unit}, in order).

    -> [{**entry, match: place index | None, by: "unit" | "name" | None}]. A place matches at most one entry.
    Same level and the same unit first (the full unit, or its own number when one side has no level prefix);
    then a similar name on the same level (or either level unknown)."""
    out = [dict(e, match=None, by=None) for e in directory or []]
    used = set()

    def same_level(e, p):
        a, b = norm_level(e.get('level')), norm_level(p.get('level'))
        return a is None or b is None or a == b
    for e in out:
        u = norm_unit(e.get('unit'))
        if not u:
            continue
        for i, p in enumerate(places):
            pu = norm_unit(p.get('unit'))
            if i in used or not pu or not same_level(e, p):
                continue
            if pu == u or (unit_tail(pu) == unit_tail(u) and (pu == unit_tail(pu) or u == unit_tail(u))):
                e['match'], e['by'] = i, 'unit'
                used.add(i)
                break
    for e in out:
        if e['match'] is not None or not e.get('name'):
            continue
        for i, p in enumerate(places):
            if i not in used and same_level(e, p) and similar_names(e['name'], p.get('name')):
                e['match'], e['by'] = i, 'name'
                used.add(i)
                break
    return out


# ---------- floor plans (docs/indoor.md §7) ----------
#
# A plan is a mall board photographed by the walker and straightened by the app (plans/plan_<level>_<n>.jpg), placed
# on the map by a similarity transform: toMap = {origin: [lat, lon], scaleMPerPx, rotationDeg}. A plan pixel (x right,
# y down) is rotated by rotationDeg clockwise (as seen on the map), scaled to metres and y flipped: metres east /
# north of origin, then degrees with to_ll (the same local frame as the walk).

def plan_xy(px, py, tm):
    """Plan pixel -> (metres east, metres north) of toMap's origin."""
    th = math.radians(float(tm.get('rotationDeg') or 0))
    s = float(tm['scaleMPerPx'])
    c, sn = math.cos(th), math.sin(th)
    return s * (px * c - py * sn), -s * (px * sn + py * c)


def plan_px(e, n, tm):
    """(metres east, metres north) of toMap's origin -> plan pixel (plan_xy inverted)."""
    th = math.radians(float(tm.get('rotationDeg') or 0))
    s = float(tm['scaleMPerPx'])
    c, sn = math.cos(th), math.sin(th)
    x, y = e / s, -n / s
    return x * c + y * sn, -x * sn + y * c


def plan_to_ll(px, py, tm):
    """Plan pixel -> (lat, lon)."""
    e, n = plan_xy(px, py, tm)
    return to_ll(e, n, float(tm['origin'][0]), float(tm['origin'][1]))


def ll_to_plan(lat, lon, tm):
    """(lat, lon) -> plan pixel."""
    return plan_px(*to_xy(lat, lon, float(tm['origin'][0]), float(tm['origin'][1])), tm)


def plan_ok(p):
    """A plan entry that can be drawn: a file, a size and a usable toMap."""
    tm = (p or {}).get('toMap') or {}
    o = tm.get('origin')
    return (isinstance(p, dict) and isinstance(p.get('file'), str) and p['file'] != ''
            and isinstance(o, (list, tuple)) and len(o) >= 2 and all(isinstance(v, (int, float)) for v in o[:2])
            and isinstance(tm.get('scaleMPerPx'), (int, float)) and tm['scaleMPerPx'] > 0
            and isinstance(tm.get('rotationDeg', 0), (int, float)))


def plans_of(session, plans_json=None):
    """The walk's floor plans: session.building.plans, else plans/plans.json (a list, or {"plans": [...]}); only those
    with a file and a usable toMap; level tidied (norm_level)."""
    b = (session or {}).get('building') or {}
    raw = b.get('plans') or ((session or {}).get('indoor') or {}).get('plans')   # a walk without a building
    if not raw and plans_json:
        raw = plans_json.get('plans') if isinstance(plans_json, dict) else plans_json
    out = []
    for p in raw or []:
        if plan_ok(p):
            out.append(dict(p, level=norm_level(p.get('level'))))
    return out


def plan_anchors(ev, plans, max_dt_s=120):
    """The "plan" anchors ("you are here" tapped on a board) placed again through their plan's final toMap: the walker
    may have moved the plan after tapping. The plan of the anchor's level whose time is nearest (within max_dt_s),
    with youAreHere. -> how many anchors moved (more than 0.5 m)."""
    moved = 0
    for a in ev.get('anchors') or []:
        if a.get('how') != 'plan':
            continue
        cands = [p for p in plans if p.get('youAreHere') and len(p['youAreHere']) >= 2
                 and (a.get('level') is None or norm_level(p.get('level')) == norm_level(a.get('level')))]
        timed = [p for p in cands if isinstance(p.get('t'), (int, float))]
        if timed:
            p = min(timed, key=lambda q: abs(q['t'] - a['t']))
            if abs(p['t'] - a['t']) > max_dt_s * 1e9:
                continue
        elif len(cands) == 1:
            p = cands[0]
        else:
            continue
        la, lo = plan_to_ll(p['youAreHere'][0], p['youAreHere'][1], p['toMap'])
        if math.hypot(*to_xy(la, lo, a['lat'], a['lon'])) > 0.5:
            a['lat'], a['lon'], a['fromPlan'] = la, lo, p.get('file')
            moved += 1
    return moved


def polygon_centre(pts):
    """Area centroid of a polygon [(x, y), ...] (vertex mean when degenerate)."""
    a = cx = cy = 0.0
    for (x1, y1), (x2, y2) in zip(pts, pts[1:] + pts[:1]):
        c = x1 * y2 - x2 * y1
        a += c
        cx += (x1 + x2) * c
        cy += (y1 + y2) * c
    if abs(a) < 1e-12:
        return sum(p[0] for p in pts) / len(pts), sum(p[1] for p in pts) / len(pts)
    return cx / (3 * a), cy / (3 * a)


def plan_unit_geo(u, plan):
    """A plan unit ({level, unit, polygon in plan px, name}) on the map: {polygon [[lat, lon]], centre (lat, lon),
    radiusM (the farthest corner from the centre: half the unit's size), areaM2}."""
    tm = plan['toMap']
    xy = [plan_xy(float(p[0]), float(p[1]), tm) for p in u['polygon']]
    ce, cn = polygon_centre(xy)
    o = tm['origin']
    area = abs(sum(x1 * y2 - x2 * y1 for (x1, y1), (x2, y2) in zip(xy, xy[1:] + xy[:1]))) / 2
    return {'polygon': [list(to_ll(e, n, o[0], o[1])) for e, n in xy], 'centre': to_ll(ce, cn, o[0], o[1]),
            'radiusM': max(math.hypot(e - ce, n - cn) for e, n in xy), 'areaM2': area}


LABEL_UNC_M = 5.0   # a unit placed from the phone's label only (no outline): its size is unknown


def phone_units(plan):
    """The unit numbers the phone read on a plan ("units": [{unit, x, y, h}] in plan px: the label's centre and text
    height): usable ones only, unit tidied for matching kept as read."""
    out = []
    for u in (plan or {}).get('units') or []:
        if (isinstance(u, dict) and u.get('unit') not in (None, '') and all(
                isinstance(u.get(k), (int, float)) and not isinstance(u.get(k), bool) for k in ('x', 'y'))):
            out.append({'unit': str(u['unit']).strip(), 'x': u['x'], 'y': u['y'], 'h': u.get('h')})
    return out


def label_unit_geo(u, plan):
    """A unit known only by its label on the plan: the label's centre is the unit's centre, no outline."""
    return {'polygon': None, 'centre': plan_to_ll(u['x'], u['y'], plan['toMap']), 'radiusM': LABEL_UNC_M, 'areaM2': None}
