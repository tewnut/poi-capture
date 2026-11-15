"""Turn the places read from a sweep into one folder per place, with review links.

usage: python build.py <workdir> <outdir> [--best N] [--keep-all] [--doors <place-capture session folder>]
       python build.py --index <outdir>        (only rewrite README / places.csv / places.geojson / index.html)

--doors: a Place capture session made at the same shops (doors photographed on site, GPS at the door). A place
with "truth": "P3" in places.json (the 3rd place of that session, in capture order) gets `groundTruth` with the
distance from its estimate; "truth": {"lat": .., "lon": ..} works without the session. The map shows both.
A rebuild keeps each place's Google Maps result from the previous run (same folder and name).

<workdir>/places.json (written while reading the contact sheets):
{
  "street": "Mai Chí Thọ",
  "defaultFacadeDistanceM": 30,
  "places": [
    {"name": "Highlands Coffee", "category": "cafe", "signText": "HIGHLANDS COFFEE",
     "photos": [33, 45],                       # first and last photo index the sign is on
     "cover": 42,                              # the photo where it is clearest / most central
     "sightings": [{"photo": 36, "x": 0.15}, {"photo": 42, "x": 0.68}],   # sign centre, 0 = left edge, 1 = right
     "confidence": "high", "note": ""}
  ]
}
Position: rays from >= 2 sightings that span >= 8 degrees are intersected (weighted least squares, the handoff's step 4);
otherwise the cover sighting is pushed out defaultFacadeDistanceM along its bearing (single-photo estimate).
"""
import csv, html, json, math, os, re, shutil, sys, urllib.parse

import mapview
import result

FIELDS = ('name', 'phone', 'houseNumber', 'street', 'openingHours', 'website', 'signText', 'other')


def fields_of(pl):
    """The values read on the photos, each {value, photo, sure}; a bare value is accepted (sure = high)."""
    out = {}
    for k, v in (pl.get('fields') or {}).items():
        if v in (None, '', []):
            continue
        out[k] = dict(v) if isinstance(v, dict) and 'value' in v else {'value': v, 'photo': pl.get('cover'), 'sure': 'high'}
    if 'name' not in out:
        out['name'] = {'value': pl['name'], 'photo': pl.get('cover'), 'sure': pl.get('confidence') or 'high'}
    if pl.get('signText') and 'signText' not in out:
        out['signText'] = {'value': pl['signText'], 'photo': pl.get('cover'), 'sure': pl.get('confidence') or 'high'}
    return out


def best_list(pl):
    """`best` as [index, ...] or [{"photo": index, "why": ...}, ...] -> [(index, why)]."""
    out = []
    for b in pl.get('best') or []:
        out.append((b['photo'], b.get('why')) if isinstance(b, dict) else (b, None))
    return out
from datetime import datetime, timezone

sys.stdout.reconfigure(encoding='utf-8')


def gmaps(lat, lon):
    return f"https://www.google.com/maps?q={lat:.6f},{lon:.6f}"


def to_xy(lat, lon, lat0, lon0):
    return ((lon - lon0) * 111320 * math.cos(math.radians(lat0)), (lat - lat0) * 110540)


def to_ll(x, y, lat0, lon0):
    return (lat0 + y / 110540, lon0 + x / (111320 * math.cos(math.radians(lat0))))


BEARING_OFFSET = 0.0   # set by build(): the session's mount correction (see bearing_correction)


def bearing_correction(photos, spec):
    """Degrees to add to the photos' cameraBearing. The app assumes the camera looks at 90 deg to travel until it
    measures the mount ("assumed"); the phone's compass at each photo (`compassDeg`) says how it really pointed. One
    reading is unreliable on a motorbike, the median over the sweep is not (first ride: 32 deg forward, middle half of
    68 photos within 28-36 deg; correcting it halved the error against Google Maps). `bearingOffsetDeg` in places.json
    overrides it."""
    if spec.get('bearingOffsetDeg') is not None:
        return float(spec['bearingOffsetDeg']), 'set in places.json'
    assumed = [p for p in photos if p.get('cameraBearingSource') == 'assumed' and p.get('cameraBearing') is not None
               and p.get('compassDeg') is not None and (p.get('speedMps') or 0) >= 2]
    if len(assumed) < 15:
        return 0.0, f"camera bearing as recorded ({photos[0].get('cameraBearingSource') if photos else '?'})"
    d = sorted(((p['compassDeg'] - p['cameraBearing'] + 540) % 360) - 180 for p in assumed)
    q1, med, q3 = d[len(d) // 4], d[len(d) // 2], d[len(d) * 3 // 4]
    if q3 - q1 > 25:
        return 0.0, f'assumed bearing kept: compass too scattered (middle half spans {q3 - q1:.0f} deg)'
    return med, f'assumed bearing corrected by {med:+.0f} deg from the compass (median of {len(d)} photos, middle half {q1:+.0f}..{q3:+.0f})'


def bearing_of(photo, x):
    """Bearing from the camera to a point at horizontal position x (0..1) in the upright photo."""
    hf = photo.get('hFovDeg') or 69.1
    f = 0.5 / math.tan(math.radians(hf / 2))
    base = photo['cameraBearing'] + (BEARING_OFFSET if photo.get('cameraBearingSource') == 'assumed' else 0)
    return (base + math.degrees(math.atan((x - 0.5) / f))) % 360


def locate(place, photos, default_d):
    sights = [s for s in place.get('sightings') or [] if photos[s['photo']].get('lat') is not None]
    if not sights:
        sights = [{'photo': place['cover'], 'x': 0.5}]
    lat0, lon0 = photos[sights[0]['photo']]['lat'], photos[sights[0]['photo']]['lon']
    rays = []
    for s in sights:
        p = photos[s['photo']]
        b = math.radians(bearing_of(p, s['x']))
        rays.append((to_xy(p['lat'], p['lon'], lat0, lon0), (math.sin(b), math.cos(b)), p, s))
    seen = [{'i': s['photo'], 'lat': p['lat'], 'lon': p['lon'], 'bearing': round(math.degrees(math.atan2(u[0], u[1])) % 360, 1)}
            for _xy, u, p, s in rays]
    bs = [math.degrees(math.atan2(u[0], u[1])) for _, u, _, _ in rays]
    # widest angle between two rays (across 0/360: 350 and 10 are 20 apart)
    spread = max((abs((a - b + 180) % 360 - 180) for a in bs for b in bs), default=0)
    if len(rays) >= 2 and spread >= 8:
        # point closest to all rays (handoff step 4): (sum w M) x = sum w M p, M = I - u u^T, with
        # w = 1 / ((d * 10 deg)^2 + gpsAccuracy^2); d starts at default_d, then the distance to the first solution
        sol = None
        for _round in range(2):
            a11 = a12 = a22 = b1 = b2 = 0.0
            for (px, py), (ux, uy), p, _s in rays:
                d = default_d if sol is None else max(5.0, math.hypot(sol[0] - px, sol[1] - py))
                w = 1.0 / ((d * math.radians(10)) ** 2 + (p.get('accuracyM') or 5) ** 2)
                m11, m12, m22 = 1 - ux * ux, -ux * uy, 1 - uy * uy
                a11 += w * m11; a12 += w * m12; a22 += w * m22
                b1 += w * (m11 * px + m12 * py); b2 += w * (m12 * px + m22 * py)
            det = a11 * a22 - a12 * a12
            if abs(det) < 1e-12:
                sol = None
                break
            sol = ((a22 * b1 - a12 * b2) / det, (a11 * b2 - a12 * b1) / det)
        if sol is not None:
            in_front = all((sol[0] - px) * ux + (sol[1] - py) * uy > 0 for (px, py), (ux, uy), _p, _s in rays)
            dist = [math.hypot(sol[0] - px, sol[1] - py) for (px, py), _u, _p, _s in rays]
            # sweep bearings are mostly assumed (mount yaw unknown): a point much nearer or farther than the
            # shopfronts seen on the photos is a bearing error, not a finding
            if in_front and 0.5 * default_d <= min(dist) and max(dist) <= 2.5 * default_d:
                lat, lon = to_ll(sol[0], sol[1], lat0, lon0)
                # 2-sigma of the larger axis of the error ellipse ((sum w M)^-1)
                tr, dt = a11 + a22, det
                big = (tr + math.sqrt(max(tr * tr - 4 * dt, 0))) / 2 / dt
                return lat, lon, {'method': f'triangulated from {len(rays)} photos ({spread:.0f} deg apart)',
                                  'uncertaintyM': round(2 * math.sqrt(big), 1), 'sightings': seen}
    # single-photo estimate from the cover (or the first sighting)
    s = next((s for s in sights if s['photo'] == place['cover']), sights[0])
    p = photos[s['photo']]
    b = math.radians(bearing_of(p, s['x']))
    x, y = to_xy(p['lat'], p['lon'], lat0, lon0)
    lat, lon = to_ll(x + default_d * math.sin(b), y + default_d * math.cos(b), lat0, lon0)
    return lat, lon, {'method': f'single-photo estimate, {default_d:.0f} m from the camera', 'uncertaintyM': round(default_d / 2),
                      'sightings': [x for x in seen if x['i'] == s['photo']]}


def facade_snap(lat, lon, photos, d):
    """The pin moved onto the shopfront line: same position along the street as (lat, lon), at distance d from the
    ride on the side the pin is on. Uses the nearest photo's camera position and travel direction."""
    near = [p for p in photos if p.get('lat') is not None and p.get('travelBearing') is not None]
    if not near:
        return lat, lon
    c = min(near, key=lambda p: math.hypot(*to_xy(lat, lon, p['lat'], p['lon'])))
    x, y = to_xy(lat, lon, c['lat'], c['lon'])
    tb = math.radians(c['travelBearing'])
    tx, ty = math.sin(tb), math.cos(tb)            # along travel
    nx, ny = ty, -tx                                # right of travel
    along, across = x * tx + y * ty, x * nx + y * ny
    side = 1 if across >= 0 else -1
    return to_ll(along * tx + side * d * nx, along * ty + side * d * ny, c['lat'], c['lon'])


def agent_pin(pl, lat, lon, photos, d):
    """`agentPin` from places.json: {"lat", "lon", "why"} as given, or {"snap": "facade", "why"}: the algorithm's pin
    moved onto the shopfront line (facade_snap) at `distanceM` (default: the street's facade distance)."""
    a = pl.get('agentPin')
    if not a:
        return None
    if a.get('snap') == 'facade':
        la, lo = facade_snap(lat, lon, photos, float(a.get('distanceM') or d))
        return {'lat': round(la, 6), 'lon': round(lo, 6), 'why': a.get('why') or 'moved onto the shopfront line'}
    return {'lat': a['lat'], 'lon': a['lon'], 'why': a.get('why')}


def sign_x(place, i):
    """Where the sign is in photo i (0..1), interpolated between the sightings; None without sightings."""
    s = sorted(((t['photo'], t['x']) for t in place.get('sightings') or []), key=lambda t: t[0])
    if not s:
        return None
    if i <= s[0][0]:
        return s[0][1] + (s[1][1] - s[0][1]) / max(1, s[1][0] - s[0][0]) * (i - s[0][0]) if len(s) > 1 else s[0][1]
    for (a, xa), (b, xb) in zip(s, s[1:]):
        if a <= i <= b:
            return xa + (xb - xa) * (i - a) / max(1, b - a)
    (a, xa), (b, xb) = (s[-2], s[-1]) if len(s) > 1 else (s[-1], s[-1])
    return xb + (xb - xa) / max(1, b - a) * (i - b) if b != a else xb


def choose(place, photos, n):
    """The photos to keep for a place: `best` as chosen while reading, else the cover plus the best-scoring others.

    Score: the sign near the middle of the frame (from the sightings), sharp, not blurry. The second pick must be at
    least 2 photos away from the first, so the two show the place from different angles.
    """
    a, b = place['photos']
    cover = place['cover']
    picked = [i for i, _w in best_list(place) if a <= i <= b]
    if picked:   # 1 to N photos, as chosen while reading (no near-duplicates: that is the reader's call)
        return [cover] + [i for i in dict.fromkeys(picked) if i != cover], 'chosen while reading the photos'
    rng = range(a, b + 1)
    sharp = sorted(photos[i].get('sharpness') or 0 for i in rng)
    med = sharp[len(sharp) // 2] or 1

    def score(i):
        x = sign_x(place, i)
        centre = 1 - min(1.0, abs(x - 0.5) * 2) if x is not None else 0.5
        if x is not None and not 0.05 <= x <= 0.95:
            centre -= 1                                   # sign cut by the frame edge
        return centre + 0.5 * min(1.5, (photos[i].get('sharpness') or 0) / med) - (1 if photos[i].get('blurry') else 0)

    out = [cover]
    for i in sorted(rng, key=score, reverse=True):
        if len(out) >= n:
            break
        if all(abs(i - j) >= 2 for j in out):
            out.append(i)
    return out, 'automatic: sign near the middle, sharp, at least 2 photos apart'


def safe(name):
    s = re.sub(r'[<>:"/\\|?*…()\[\]]+', '', name).strip()
    return re.sub(r'\s+', '_', s)[:40].strip('_.') or 'place'


def photo_meta(p, i, cover, fn):
    return {'file': fn, 'id': p['id'], 'sweepIndex': i, 'cover': i == cover, 't': p['t'],
            'camera': {'lat': p['lat'], 'lon': p['lon'], 'accuracyM': p['accuracyM'],
                       'bearing': p['cameraBearing'], 'bearingSource': p['cameraBearingSource'],
                       'googleMaps': gmaps(p['lat'], p['lon']) if p['lat'] is not None else None},
            'speedKmh': round((p['speedMps'] or 0) * 3.6, 1), 'size': f"{p['width']}x{p['height']}",
            'blurry': p['blurry'], 'sharpness': p.get('sharpness'), 'roadName': p['roadName']}


def doors(folder):
    """P1, P2, ... -> GPS position of each place of a Place capture session (pois.jsonl, last version of each)."""
    if not folder:
        return {}
    d = {}
    for line in open(os.path.join(folder, 'pois.jsonl'), encoding='utf-8'):
        o = json.loads(line)
        if 'id' in o and (o.get('position') or {}).get('lat') is not None:
            d[o['id']] = o['position']
    return {f'P{i + 1}': v for i, v in enumerate(d.values())}


def ground_truth(pl, lat, lon, door_pos, door_session):
    t = pl.get('truth')
    if isinstance(t, str) and t in door_pos:
        q, label = door_pos[t], t
    elif isinstance(t, dict) and t.get('lat') is not None:
        q, label = t, t.get('label') or 'given'
    else:
        return None
    x, y = to_xy(lat, lon, q['lat'], q['lon'])
    return {'lat': q['lat'], 'lon': q['lon'], 'label': label, 'source': door_session or 'places.json',
            'accuracyM': q.get('accuracyM'), 'errorM': round(math.hypot(x, y), 1)}


def build(work, out, best_n=2, keep_all=False, door_folder=None):
    photos = json.load(open(os.path.join(work, 'photos.json'), encoding='utf-8'))
    session = json.load(open(os.path.join(work, 'session.json'), encoding='utf-8'))
    spec = json.load(open(os.path.join(work, 'places.json'), encoding='utf-8'))
    default_d = float(spec.get('defaultFacadeDistanceM') or 30)
    best_n = int(spec.get('bestPhotos') or best_n)
    global BEARING_OFFSET
    BEARING_OFFSET, bearing_note = bearing_correction(photos, spec)
    print('camera bearing:', bearing_note)
    door_pos = doors(door_folder)
    door_session = None
    if door_folder and os.path.isfile(os.path.join(door_folder, 'session.json')):
        ds = json.load(open(os.path.join(door_folder, 'session.json'), encoding='utf-8'))
        door_session = f"place capture {ds.get('id')} ({ds.get('name')})"
    previous = {}   # folder -> (name, googleMaps) from the last run, so a rebuild does not need a new lookup
    if os.path.isdir(out):
        for d in os.listdir(out):
            f = os.path.join(out, d, 'place.json')
            if os.path.isfile(f):
                m = json.load(open(f, encoding='utf-8'))
                if m.get('googleMaps'):
                    previous[d] = (m['place']['name'], m['googleMaps'])
    if os.path.exists(out):
        for d in os.listdir(out):   # keep nothing from an earlier run but the folder itself
            q = os.path.join(out, d)
            shutil.rmtree(q) if os.path.isdir(q) else os.remove(q)
    os.makedirs(out, exist_ok=True)
    for n, pl in enumerate(spec['places'], 1):
        a, b = pl['photos']
        lat, lon, how = locate(pl, photos, default_d)
        d = os.path.join(out, f"{n:02d}_{safe(pl['name'])}")
        os.makedirs(d)
        # only the best photos go in the folder (cover first); the others are listed, or kept in more/ (--keep-all)
        keep, how_chosen = choose(pl, photos, best_n)
        files, others = [], []
        for k, i in enumerate(keep):
            p = photos[i]
            fn = f"{'COVER' if i == pl['cover'] else f'BEST{k + 1}'}_#{i:02d}_{p['id']}.jpg"
            shutil.copy2(p['file'], os.path.join(d, fn))
            files.append(photo_meta(p, i, pl['cover'], fn))
            files[-1]['why'] = dict(best_list(pl)).get(i)
        for i in range(a, b + 1):
            if i in keep:
                continue
            p = photos[i]
            fn = None
            if keep_all:
                os.makedirs(os.path.join(d, 'more'), exist_ok=True)
                fn = f"more/#{i:02d}_{p['id']}.jpg"
                shutil.copy2(p['file'], os.path.join(d, fn))
            others.append({'id': p['id'], 'sweepIndex': i, 'file': fn})
        cover = photos[pl['cover']]
        old = previous.get(os.path.basename(d))
        if not old or old[0] != pl['name']:   # renumbered: find it by name
            old = next((v for v in previous.values() if v[0] == pl['name']), None)
        meta = {
            'place': {'name': pl['name'], 'category': pl.get('category'), 'signText': pl.get('signText'),
                      'confidence': pl.get('confidence'), 'note': pl.get('note') or None,
                      'status': pl.get('status') or 'open',
                      'readBy': 'vision model, from the photos in this folder',
                      'street': spec.get('street') or cover.get('roadName'),
                      'address': None, 'phone': None, 'openingHours': None, 'website': None,
                      'position': {'lat': round(lat, 6), 'lon': round(lon, 6), **how,
                                   'cameraBearing': bearing_note,
                                   'googleMaps': gmaps(lat, lon),
                                   'coverCameraOnGoogleMaps': gmaps(cover['lat'], cover['lon'])}},
            'review': {'status': 'todo', 'comment': None},
            'fields': fields_of(pl),
            'pins': {'agent': agent_pin(pl, lat, lon, photos, default_d)},
            'googleMaps': old[1] if old and old[0] == pl['name'] else None,
            'groundTruth': ground_truth(pl, lat, lon, door_pos, door_session),
            'session': {'id': session.get('id'), 'name': session.get('name'), 'kind': session.get('kind')},
            'photos': files,
            'photoSelection': {'how': how_chosen, 'seenOn': b - a + 1, 'kept': len(files),
                               'whyBest': pl.get('bestWhy')},
            'otherPhotos': others,
        }
        json.dump(meta, open(os.path.join(d, 'place.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
    # places no longer there (closed, for rent, gone), seen or expected on this stretch: for the removal tasks
    rem = []
    for r in spec.get('removals') or []:
        r = dict(r)
        if r.get('lat') is None and r.get('photo') is not None:
            lat, lon, _h = locate({'cover': r['photo'], 'sightings': [{'photo': r['photo'], 'x': r.get('x', 0.5)}]},
                                  photos, default_d)
            r['lat'], r['lon'] = round(lat, 6), round(lon, 6)
        if r.get('photo') is not None:
            p = photos[r['photo']]
            fn = f"REMOVED_#{r['photo']:02d}_{p['id']}.jpg"
            os.makedirs(os.path.join(out, '_removals'), exist_ok=True)
            shutil.copy2(p['file'], os.path.join(out, '_removals', fn))
            r['photo'] = f'_removals/{fn}'
        rem.append(r)
    json.dump(rem, open(os.path.join(out, 'removals.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    # the ride for the map: every photo's camera position, in order
    shots = [[p['idx'], round(p['lat'], 6), round(p['lon'], 6),
              None if p.get('cameraBearing') is None else round(p['cameraBearing'], 1)] for p in photos if p.get('lat') is not None]
    json.dump({'session': {k: session.get(k) for k in ('id', 'name', 'kind', 'holding', 'startedAt', 'endedAt')},
               'street': spec.get('street'), 'track': [[x[1], x[2]] for x in shots], 'photos': shots},
              open(os.path.join(out, 'sweep.json'), 'w', encoding='utf-8'), ensure_ascii=False)
    write_indexes(out, session)


def csv_cell(v):
    """Text starting with = + - @ (or a tab / return) gets a leading ' so a spreadsheet does not run it as a formula."""
    return "'" + v if isinstance(v, str) and v[:1] in ('=', '+', '-', '@', '\t', '\r') else v


def write_indexes(out, session=None):
    rows = []
    for d in sorted(os.listdir(out)):
        f = os.path.join(out, d, 'place.json')
        if os.path.isfile(f):
            rows.append((d, json.load(open(f, encoding='utf-8'))))
    if session is None and rows:
        session = rows[0][1]['session']
    g = lambda m, k: (m.get('googleMaps') or {}).get(k)
    # places.csv (Excel-friendly), places.geojson, README.txt, index.html
    with open(os.path.join(out, 'places.csv'), 'w', newline='', encoding='utf-8-sig') as fh:
        w = csv.writer(fh)
        w.writerow(['#', 'name', 'category', 'confidence', 'photos', 'lat', 'lon', 'position', 'review link',
                    'google name', 'google address', 'google phone', 'google hours', 'google website', 'google link',
                    'google distance m', 'folder'])
        for d, m in rows:
            p = m['place']; pos = p['position']
            w.writerow([csv_cell(x) for x in [d[:2], p['name'], p['category'], p['confidence'], len(m['photos']), pos['lat'], pos['lon'],
                        pos['method'], pos['googleMaps'], g(m, 'name'), g(m, 'address'), g(m, 'phone'),
                        ' | '.join(g(m, 'openingHours') or []), g(m, 'website'), g(m, 'url'), g(m, 'distanceM'), d]])
    feats = [{'type': 'Feature', 'geometry': {'type': 'Point', 'coordinates': [m['place']['position']['lon'], m['place']['position']['lat']]},
              'properties': {'folder': d, 'name': m['place']['name'], 'category': m['place']['category'],
                             'confidence': m['place']['confidence'], 'googleName': g(m, 'name')}} for d, m in rows]
    json.dump({'type': 'FeatureCollection', 'features': feats}, open(os.path.join(out, 'places.geojson'), 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    lines = [f"{d[:2]}  {m['place']['name']:<38} {len(m['photos']):>2} photos  {m['place']['position']['googleMaps']}"
             + (f"\n    Google: {g(m, 'name')} | {g(m, 'address')} | {g(m, 'phone') or '-'}" if g(m, 'name') else '')
             for d, m in rows]
    open(os.path.join(out, 'README.txt'), 'w', encoding='utf-8').write(
        f"Places from street sweep {session.get('id')} ({session.get('name')})\n"
        "One folder per place: its photos (COVER_ = cover, #NN = position in the sweep) and place.json.\n"
        "Open index.html to review: photos, map links, and what Google Maps knows.\n\n" + '\n'.join(lines) + '\n')
    e = html.escape
    cards = []
    for d, m in rows:
        p = m['place']; pos = p['position']; gm = m.get('googleMaps') or {}
        cover = next((x for x in m['photos'] if x['cover']), m['photos'][0])
        def u(*parts):   # a relative link: "#" in the photo names would otherwise start a fragment
            return e('/'.join(urllib.parse.quote(x) for x in parts))
        thumbs = ''.join(f'<a href="{u(d, x["file"])}" target="_blank"><img loading="lazy" src="{u(d, x["file"])}"></a>'
                         for x in m['photos'] if not x['cover'])
        gblock = ('<p class="g"><b>Google Maps:</b> ' + e(gm.get('name') or '') +
                  (f' ({gm.get("distanceM")} m away)' if gm.get('distanceM') is not None else '') + '<br>' +
                  '<br>'.join(e(str(v)) for v in [gm.get('address'), gm.get('phone'), gm.get('website')] if v) +
                  (('<br><small>' + '<br>'.join(e(h) for h in gm.get('openingHours') or []) + '</small>') if gm.get('openingHours') else '') +
                  (f'<br><a href="{e(gm["url"])}" target="_blank">open on Google Maps</a>' if gm.get('url') else '') + '</p>'
                  ) if gm.get('name') else ('<p class="g muted">Google Maps: ' + e(gm.get('status') or 'not looked up yet') + '</p>')
        tr = m.get('groundTruth')
        tline = (f'<p class="t">Door captured on site ({e(tr["label"])}): <b>{tr["errorM"]} m</b> from the estimate</p>'
                 if tr else '')
        cards.append(f'''<section class="place" id="{e(d)}"><h2 title="show on the map">{e(d[:2])} · {e(p["name"])} <span class="c {e(p.get("confidence") or "")}">{e(p.get("confidence") or "")}</span></h2>
<p>{e(p.get("category") or "")}{" · " + e(p["note"]) if p.get("note") else ""}</p>
<a href="{u(d, cover["file"])}" target="_blank"><img class="cover" src="{u(d, cover["file"])}"></a>
<div class="thumbs">{thumbs}</div>
<p><a href="{e(pos["googleMaps"])}" target="_blank">Estimated position</a> ({e(pos["method"])}, ±{e(str(pos.get("uncertaintyM")))} m) ·
<a href="{e(pos["coverCameraOnGoogleMaps"])}" target="_blank">where the cover was taken</a> · <a href="{u(d, 'place.json')}" target="_blank">place.json</a></p>
{gblock}{tline}</section>''')
    head, script = mapview.session_map_block(out)
    errs = sorted(m['groundTruth']['errorM'] for _d, m in rows if m.get('groundTruth'))
    stats = (f" · vs {len(errs)} doors captured on site: median <b>{errs[len(errs) // 2]} m</b>" if errs else '')
    hub = os.path.join(os.path.dirname(os.path.abspath(out)), 'index.html')
    open(os.path.join(out, 'index.html'), 'w', encoding='utf-8').write(f'''<!doctype html><html lang="en"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Sweep places</title>{head}
<style>{mapview.CSS}
body{{display:grid;grid-template-columns:minmax(0,1.15fr) minmax(360px,1fr);height:100vh;overflow:hidden}}
#map{{height:100vh}}#list{{overflow:auto;padding:16px 18px}}
section{{background:#fff;border:1px solid var(--line);border-radius:12px;padding:12px 16px;margin:12px 0;scroll-margin-top:12px}}
section.on{{border-color:var(--accent);box-shadow:0 0 0 2px rgba(31,122,82,.25)}}
h2{{margin:0 0 4px;font-size:17px;cursor:pointer}}h2:hover{{color:var(--accent)}}.cover{{width:100%;border-radius:8px}}
.thumbs img{{height:72px;margin:4px 4px 0 0;border-radius:4px}}.c{{font-size:12px;padding:2px 8px;border-radius:10px;background:#e7f6ec}}
.c.low{{background:#fdecea}}.g{{background:#f3f6ff;padding:8px;border-radius:6px}}.t{{background:#fdf0f4;padding:6px 8px;border-radius:6px}}
@media(max-width:860px){{body{{display:block;height:auto;overflow:auto}}#map{{height:60vh;position:sticky;top:0;z-index:5}}}}</style>
<div id="map"></div><div id="list">
<p class="muted" style="margin:0 0 6px"><a href="../index.html">← all sessions</a></p>
<h1>Places from {e(str(session.get("name")))}</h1><p class="muted">{len(rows)} places · session {e(str(session.get("id")))} ·
generated {datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M")} UTC{stats}<br>Tap a pin or a place's title: the map shows the
photos that located it (green lines) and Google's pin (blue dots); red dashes go to the door captured on site.</p>
{"".join(cards)}</div>
{script}</html>''')
    result.write(out)
    mapview.write_hub(os.path.dirname(os.path.abspath(out)))
    print('\n'.join(lines))


if __name__ == '__main__':
    if sys.argv[1] == '--index':
        write_indexes(sys.argv[2])
    else:
        n = int(sys.argv[sys.argv.index('--best') + 1]) if '--best' in sys.argv else 2
        dz = sys.argv[sys.argv.index('--doors') + 1] if '--doors' in sys.argv else None
        build(sys.argv[1], sys.argv[2], best_n=n, keep_all='--keep-all' in sys.argv, door_folder=dz)
