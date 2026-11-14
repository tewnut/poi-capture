"""Turn the places read from a sweep into one folder per place, with review links.

usage: python build.py <workdir> <outdir>
       python build.py --index <outdir>        (only rewrite README / places.csv / places.geojson / index.html)

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
import csv, html, json, math, os, re, shutil, sys
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
                                  'uncertaintyM': round(2 * math.sqrt(big), 1)}
    # single-photo estimate from the cover (or the first sighting)
    s = next((s for s in sights if s['photo'] == place['cover']), sights[0])
    p = photos[s['photo']]
    b = math.radians(bearing_of(p, s['x']))
    x, y = to_xy(p['lat'], p['lon'], lat0, lon0)
    lat, lon = to_ll(x + default_d * math.sin(b), y + default_d * math.cos(b), lat0, lon0)
    return lat, lon, {'method': f'single-photo estimate, {default_d:.0f} m from the camera', 'uncertaintyM': round(default_d / 2)}


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
    picked = [i for i in place.get('best') or [] if a <= i <= b]
    if picked:
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


def build(work, out, best_n=2, keep_all=False):
    photos = json.load(open(os.path.join(work, 'photos.json'), encoding='utf-8'))
    session = json.load(open(os.path.join(work, 'session.json'), encoding='utf-8'))
    spec = json.load(open(os.path.join(work, 'places.json'), encoding='utf-8'))
    default_d = float(spec.get('defaultFacadeDistanceM') or 30)
    best_n = int(spec.get('bestPhotos') or best_n)
    global BEARING_OFFSET
    BEARING_OFFSET, bearing_note = bearing_correction(photos, spec)
    print('camera bearing:', bearing_note)
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
        meta = {
            'place': {'name': pl['name'], 'category': pl.get('category'), 'signText': pl.get('signText'),
                      'confidence': pl.get('confidence'), 'note': pl.get('note') or None,
                      'readBy': 'vision model, from the photos in this folder',
                      'street': spec.get('street') or cover.get('roadName'),
                      'address': None, 'phone': None, 'openingHours': None, 'website': None,
                      'position': {'lat': round(lat, 6), 'lon': round(lon, 6), **how,
                                   'cameraBearing': bearing_note,
                                   'googleMaps': gmaps(lat, lon),
                                   'coverCameraOnGoogleMaps': gmaps(cover['lat'], cover['lon'])}},
            'review': {'status': 'todo', 'comment': None},
            'googleMaps': None,
            'session': {'id': session.get('id'), 'name': session.get('name'), 'kind': session.get('kind')},
            'photos': files,
            'photoSelection': {'how': how_chosen, 'seenOn': b - a + 1, 'kept': len(files),
                               'whyBest': pl.get('bestWhy')},
            'otherPhotos': others,
        }
        json.dump(meta, open(os.path.join(d, 'place.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
    write_indexes(out, session)


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
            w.writerow([d[:2], p['name'], p['category'], p['confidence'], len(m['photos']), pos['lat'], pos['lon'],
                        pos['method'], pos['googleMaps'], g(m, 'name'), g(m, 'address'), g(m, 'phone'),
                        ' | '.join(g(m, 'openingHours') or []), g(m, 'website'), g(m, 'url'), g(m, 'distanceM'), d])
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
        thumbs = ''.join(f'<a href="{e(d)}/{e(x["file"])}" target="_blank"><img loading="lazy" src="{e(d)}/{e(x["file"])}"></a>'
                         for x in m['photos'] if not x['cover'])
        gblock = ('<p class="g"><b>Google Maps:</b> ' + e(gm.get('name') or '') +
                  (f' ({gm.get("distanceM")} m away)' if gm.get('distanceM') is not None else '') + '<br>' +
                  '<br>'.join(e(str(v)) for v in [gm.get('address'), gm.get('phone'), gm.get('website')] if v) +
                  (('<br><small>' + '<br>'.join(e(h) for h in gm.get('openingHours') or []) + '</small>') if gm.get('openingHours') else '') +
                  (f'<br><a href="{e(gm["url"])}" target="_blank">open on Google Maps</a>' if gm.get('url') else '') + '</p>'
                  ) if gm.get('name') else ('<p class="g muted">Google Maps: ' + e(gm.get('status') or 'not looked up yet') + '</p>')
        cards.append(f'''<section><h2>{e(d[:2])} · {e(p["name"])} <span class="c {e(p.get("confidence") or "")}">{e(p.get("confidence") or "")}</span></h2>
<p>{e(p.get("category") or "")}{" · " + e(p["note"]) if p.get("note") else ""}</p>
<a href="{e(d)}/{e(cover["file"])}" target="_blank"><img class="cover" src="{e(d)}/{e(cover["file"])}"></a>
<div class="thumbs">{thumbs}</div>
<p><a href="{e(pos["googleMaps"])}" target="_blank">Estimated position</a> ({e(pos["method"])}, ±{e(str(pos.get("uncertaintyM")))} m) ·
<a href="{e(pos["coverCameraOnGoogleMaps"])}" target="_blank">where the cover was taken</a> · <a href="{e(d)}/place.json" target="_blank">place.json</a></p>
{gblock}</section>''')
    open(os.path.join(out, 'index.html'), 'w', encoding='utf-8').write(f'''<!doctype html><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Sweep places</title>
<style>body{{font:15px system-ui,sans-serif;margin:16px;max-width:1100px;background:#fafafa;color:#222}}section{{background:#fff;border:1px solid #ddd;border-radius:10px;padding:12px 16px;margin:14px 0}}
h2{{margin:0 0 4px;font-size:18px}}.cover{{width:100%;max-width:720px;border-radius:8px}}.thumbs img{{height:72px;margin:4px 4px 0 0;border-radius:4px}}
.c{{font-size:12px;padding:2px 8px;border-radius:10px;background:#e7f6ec}}.c.low{{background:#fdecea}}.g{{background:#f3f6ff;padding:8px;border-radius:6px}}.muted{{color:#888}}a{{color:#1659c7}}</style>
<h1>Places from {e(str(session.get("name")))}</h1><p>{len(rows)} places · session {e(str(session.get("id")))} · generated {datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M")} UTC</p>
{"".join(cards)}''')
    print('\n'.join(lines))


if __name__ == '__main__':
    if sys.argv[1] == '--index':
        write_indexes(sys.argv[2])
    else:
        n = int(sys.argv[sys.argv.index('--best') + 1]) if '--best' in sys.argv else 2
        build(sys.argv[1], sys.argv[2], best_n=n, keep_all='--keep-all' in sys.argv)
