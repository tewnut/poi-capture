"""Turn the places read from a sweep into one folder per place, with review links.

usage: python build.py <workdir> <outdir> [--best N] [--keep-all] [--doors <place-capture session folder>]
       python build.py --index <outdir>        (only rewrite README / places.csv / places.geojson / index.html)

--doors: a Place capture session made at the same shops (doors photographed on site, GPS at the door). A place
with "truth": "P3" in places.json (the 3rd place of that session, in capture order) gets `groundTruth` with the
distance from its estimate; "truth": {"lat": .., "lon": ..} works without the session. The map shows both.
A rebuild keeps each place's Google Maps result and website block (with its WEB_ images) from the previous run
(same folder and name, else same name).
place.street is the road the place was photographed from (the phone's roadName: the cover photo's, else the most
common over its photos); the ride-level "street" of places.json only when the photos carry none.
"bearingOffsetDeg" in places.json is added to every photo's camera bearing, compass-measured ones too (a compass that
read a few degrees off on that ride); without it only "assumed" bearings get the compass correction.

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

Optional, by the ride's target (docs/sweep-targets.md): "addresses" (house-number plates) and "signs" (traffic signs)
next to "places", each built like a place (folder, best photos, position from the sightings) with place.layer
"address" / "sign", numbered after the places:
  "addresses": [{"houseNumber": "106", "street": "Nguyễn Thanh Sơn", "unit": "Hẻm 12", "photos": [33, 36], "cover": 34,
                 "sightings": [{"photo": 34, "x": 0.41}], "confidence": "high", "placeRef": "<a place's name>", "note": ""}]
  "signs": [{"code": "P.131a", "label": "Cấm đỗ xe", "group": "prohibitory", "extra": "6h-9h", "text": "",
             "photos": [120, 123], "cover": 122, "sightings": [{"photo": 121, "x": 0.82}], "side": "right",
             "confidence": "high", "note": ""}]
A sign was photographed by a camera looking ahead: each photo's bearing is its travel bearing ("forward"), x across
the frame an angle off it (hFovDeg); no facade snap. facingDeg = the travel bearing at the cover photo.
"defaultSignDistanceM" (default 15) is the single-photo distance for signs without a range.
Range: a circle / triangle / octagon sign has a standard size (QCVN 41:2019: 0.70 / 0.70 / 0.60 m at size
coefficient 1, urban roads; "signSizeFactor" for the ride, "sizeFactor" or "sizeM" per sign; a gantry sign 1.8x), so
its width in the photo ("w" of a sighting, else the phone detector's box in signHints) gives its distance; rays and
ranges are solved together (locate_sign). "road" (the cross-section per stretch of photos) then snaps each sign
across the road onto the line of its "side" (snap_sign); see SKILL.md.
"""
import csv, html, json, math, os, re, shutil, sys, tempfile, urllib.parse

import mapview
import result
from paths import arg_path, folder_no, layer_of, place_folders

FIELDS = ('name', 'phone', 'houseNumber', 'street', 'openingHours', 'website', 'signText', 'other')
SIGN_GROUPS = {'P': 'prohibitory', 'W': 'danger', 'R': 'mandatory', 'I': 'indication', 'S': 'supplementary'}


def fields_of(pl):
    """The values read on the photos, each {value, photo, sure}; a bare value is accepted (sure = high)."""
    if pl.get('layer') in ('address', 'sign'):
        return dict(pl['fields'])   # made by address_item / sign_item: no "name" field (the name is the label)
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
BEARING_ALL = False    # True when bearingOffsetDeg is set in places.json: the offset applies to every photo


def bearing_correction(photos, spec):
    """Degrees to add to the photos' cameraBearing. The app assumes the camera looks at 90 deg to travel until it
    measures the mount ("assumed"); the phone's compass at each photo (`compassDeg`) says how it really pointed. One
    reading is unreliable on a motorbike, the median over the sweep is not (first ride: 32 deg forward, middle half of
    68 photos within 28-36 deg; correcting it halved the error against Google Maps). `bearingOffsetDeg` in places.json
    overrides it."""
    if spec.get('bearingOffsetDeg') is not None:
        off = float(spec['bearingOffsetDeg'])
        return off, f'every camera bearing (compass ones too) corrected by {off:+.0f} deg, set in places.json'
    assumed = [p for p in photos if p.get('cameraBearingSource') == 'assumed' and p.get('cameraBearing') is not None
               and p.get('compassDeg') is not None and (p.get('speedMps') or 0) >= 2]
    if len(assumed) < 15:
        return 0.0, f"camera bearing as recorded ({photos[0].get('cameraBearingSource') if photos else '?'})"
    d = sorted(((p['compassDeg'] - p['cameraBearing'] + 540) % 360) - 180 for p in assumed)
    q1, med, q3 = d[len(d) // 4], d[len(d) // 2], d[len(d) * 3 // 4]
    if q3 - q1 > 25:
        return 0.0, f'assumed bearing kept: compass too scattered (middle half spans {q3 - q1:.0f} deg)'
    return med, f'assumed bearing corrected by {med:+.0f} deg from the compass (median of {len(d)} photos, middle half {q1:+.0f}..{q3:+.0f})'


def offset_applies(photo):
    """The automatic correction is for bearings the app assumed (90 deg to travel), also when borrowed from a
    neighbour ("assumed+neighbour"); compass-measured ones get it only when bearingOffsetDeg is set (BEARING_ALL)."""
    return str(photo.get('cameraBearingSource') or '').startswith('assumed')


def is_forward(photo):
    """A forward camera's bearing is the GPS travel direction, not the compass: no compass correction."""
    return str(photo.get('cameraBearingSource') or '').startswith('forward')


def bearing_of(photo, x):
    """Bearing from the camera to a point at horizontal position x (0..1) in the upright photo."""
    hf = photo.get('hFovDeg') or 69.1
    f = 0.5 / math.tan(math.radians(hf / 2))
    base = photo['cameraBearing'] + (0 if is_forward(photo) else
                                     BEARING_OFFSET if BEARING_ALL or offset_applies(photo) else 0)
    return (base + math.degrees(math.atan((x - 0.5) / f))) % 360


def locate(place, photos, default_d, gate=None):
    """(lat, lon, how). gate: (nearest, farthest) camera distance a triangulated point may have; default 0.5x-2.5x
    the facade distance (shopfronts at the side); a sign ahead is seen from 3 to 80 m."""
    lo, hi = gate or (0.5 * default_d, 2.5 * default_d)
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
            if in_front and lo <= min(dist) and max(dist) <= hi:
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


# ---------- traffic signs: range from the sign's size, road layout, snap ----------

# QCVN 41:2019 art. 16, table 1 (size coefficient 1, metres): circle outer diameter 0.70, triangle side 0.70,
# octagon (R.122 stop) outer diameter 0.60. Table 2: coefficient 1 on urban roads (the default here), 1.25 on ordinary
# roads, 1.8 on dual carriageways outside towns, and signs on a gantry / cantilever of an urban dual carriageway take
# the dual-carriageway coefficient (1.8). Rectangles (lane boards R.4xx, indication I.xxx, plates S.xxx) have no one
# size: no range unless places.json gives the sign's "sizeM".
SIGN_SHAPE_WIDTH_M = {'circle': 0.70, 'triangle': 0.70, 'octagon': 0.60}
GANTRY_SIZE_FACTOR = 1.8
RANGE_REL_ERR = 0.12            # a sign's width read in the photo, and its real size, each a little off
RANGE_PX_ERR = 6                # pixels of box error on top (the detector margin varies 0-14 px)
RAY_ERR_DEG = 5                 # a forward ray: GPS travel bearing + x (the road's vanishing point sat within ~2 deg
                                # of the centre on the first ride); sideways sweeps keep 10 (mount yaw unknown)
LANE_WIDTH_M = {'car': 3.5, 'bus': 3.5, 'truck': 3.5, 'moto': 2.75, 'motorbike': 2.75, 'bike': 2.0}
SIGN_SETBACK_M = 1.0            # a roadside sign's pole stands this far outside the kerb
MEDIAN_WIDTH_M = 1.0            # a median sign stands in the middle of the median
MAX_SNAP_M = 6.0                # a lateral move larger than this is flagged, not made
# the phone detector's boxes are wider than the sign by ~12 px of a 3264 px photo (~2 px of its 640 px input),
# measured on the first signs ride (Dong Van Cong, 24 Nov 2026): 6-14 px on 6 signs; with it taken off, the widths
# of 21 signs on two photos and the GPS distance between them give a median real width of 0.71 m (0.83 without)
BOX_PAD = 0.0035                # of the photo width; places.json "signBoxPad" overrides


def sign_shape(code):
    """circle | triangle | octagon | None (rectangle or unknown) from a QCVN 41 code."""
    c = (code or '').strip().upper()
    m = re.match(r'(DP|P|W|R|I|S)\.?(\d+)', c)
    if not m:
        return None
    letter, num = m.group(1), int(m.group(2))
    if letter in ('P', 'DP'):
        return 'circle'
    if letter == 'W':
        return 'triangle' if num < 245 else None   # W.245+ are rectangles (go slow, chevrons, ...)
    if letter == 'R':
        return 'octagon' if num == 122 else 'circle' if num < 400 else None
    return None


def sign_width_m(sign, spec=None):
    """The sign's real width in metres: its "sizeM" in places.json, else its shape's QCVN size times the size
    coefficient ("sizeFactor" of the sign, else 1.8 on a gantry, else the ride's "signSizeFactor", default 1)."""
    if sign.get('sizeM'):
        return float(sign['sizeM'])
    base = SIGN_SHAPE_WIDTH_M.get(sign_shape(sign.get('code')))
    if base is None:
        return None
    k = sign.get('sizeFactor') or (GANTRY_SIZE_FACTOR if sign.get('side') == 'over' else None) \
        or (spec or {}).get('signSizeFactor') or 1.0
    return base * float(k)


def upright_aspect(photo):
    """Height / width of the upright photo."""
    w, h = photo.get('width') or 0, photo.get('height') or 0
    if not w or not h:
        return 9 / 16
    if photo.get('orientation') == 'portrait' and w > h or photo.get('orientation') == 'landscape' and h > w:
        w, h = h, w
    return h / w


def sighting_width(s, photo, sign, pad=BOX_PAD):
    """(w, source): the sign's width in the photo as a fraction of the photo width. The sighting's own "w" (or "h",
    its height as a fraction of the photo height, when the sign is cut at the side) as measured by the reader, else
    the phone detector's box at that x (signHints; the same code preferred; of a sign's shape; less `pad`, the
    detector's margin). None without either."""
    shape = sign_shape(sign.get('code'))
    if s.get('w'):
        return float(s['w']), 'read'
    if s.get('h'):
        hw = float(s['h']) * upright_aspect(photo)        # in photo widths
        return (hw / (math.sqrt(3) / 2) if shape == 'triangle' else hw), 'read (height)'
    best = None
    for hnt in photo.get('signHints') or []:
        if hnt.get('w') is None or hnt.get('x') is None:
            continue
        if hnt.get('h'):   # a box of another shape (a lane board, two signs on one pole) gives no range
            ratio = hnt['w'] / (hnt['h'] * upright_aspect(photo))
            if not (0.75 <= ratio <= 1.45 if shape else True):
                continue
        dx = abs(hnt['x'] - s['x'])
        if dx > max(0.02, 0.6 * hnt['w']):
            continue
        key = (0 if (hnt.get('code') or '') == (sign.get('code') or '') else 1, dx)
        if best is None or key < best[0]:
            best = (key, hnt)
    return (max(0.5 * best[1]['w'], best[1]['w'] - pad), 'phone box') if best else None


def sign_depth(width_m, w, photo):
    """Distance along the camera axis to a sign of real width width_m seen w (fraction of the photo) wide. A sign
    facing the camera is parallel to the image plane, so its width shows unforeshortened at f * W / Z."""
    hf = photo.get('hFovDeg') or 69.1
    return width_m / (2 * math.tan(math.radians(hf / 2)) * w)


def locate_sign(sign, photos, default_d, gate=(3, 80), spec=None):
    """(lat, lon, how) for a traffic sign from a forward camera. Each sighting gives a ray (bearing from x) and, when
    the sign's width in the photo is known (sighting_width) and its real size too (sign_width_m), a range: the
    distance along the travel direction. Rays and ranges are solved together by weighted least squares (a range
    pins the along-road position the nearly parallel rays leave loose); one photo with a range is a full fix.
    Without any range: locate() (rays, else the single-photo default distance)."""
    lo, hi = gate
    width_m = sign_width_m(sign, spec)
    sights = [s for s in sign.get('sightings') or [] if photos[s['photo']].get('lat') is not None]
    if not sights or width_m is None:
        return locate(sign, photos, default_d, gate)
    lat0, lon0 = photos[sights[0]['photo']]['lat'], photos[sights[0]['photo']]['lon']
    obs, seen = [], []
    for s in sights:
        p = photos[s['photo']]
        c = to_xy(p['lat'], p['lon'], lat0, lon0)
        b = math.radians(bearing_of(p, s['x']))
        u = (math.sin(b), math.cos(b))
        ax = math.radians(p['cameraBearing'])
        t = (math.sin(ax), math.cos(ax))
        wd = sighting_width(s, p, sign, float((spec or {}).get('signBoxPad', BOX_PAD)))
        z = sign_depth(width_m, wd[0], p) if wd and wd[0] > 0 else None
        if z is not None and not 2 <= z <= 150:
            z = None                                    # a box around something else
        wpx = (wd[0] * max(p.get('width') or 0, p.get('height') or 0, 1) *
               (1 if upright_aspect(p) <= 1 else 1 / upright_aspect(p))) if z is not None else 0
        obs.append((c, u, t, z, p, wpx))
        seen.append({'i': s['photo'], 'lat': p['lat'], 'lon': p['lon'], 'bearing': round(math.degrees(b) % 360, 1),
                     **({'rangeM': round(z, 1), 'w': round(wd[0], 4), 'wFrom': wd[1]} if z is not None else {})})
    # each range puts the sign at camera + z along the axis; with 3+ ranges one far from the others' median
    # (more than 30 %, at least 5 m) was a box around something else
    alongs = [(i, o[2][0] * o[0][0] + o[2][1] * o[0][1] + o[3]) for i, o in enumerate(obs) if o[3] is not None]
    if len(alongs) >= 3:
        med = sorted(a for _i, a in alongs)[len(alongs) // 2]
        for i, a in alongs:
            if abs(a - med) > max(5.0, 0.3 * obs[i][3]):
                obs[i] = obs[i][:3] + (None,) + obs[i][4:5] + (0,)
                for k in ('rangeM', 'w', 'wFrom'):
                    seen[i].pop(k, None)
                seen[i]['rangeDropped'] = True
    nr = sum(1 for o in obs if o[3] is not None)
    if not nr:
        return locate(sign, photos, default_d, gate)
    sol = None
    for _round in range(3):
        a11 = a12 = a22 = b1 = b2 = 0.0

        def add(a, rhs, var):
            nonlocal a11, a12, a22, b1, b2
            w = 1.0 / var
            a11 += w * a[0] * a[0]; a12 += w * a[0] * a[1]; a22 += w * a[1] * a[1]
            b1 += w * a[0] * rhs; b2 += w * a[1] * rhs
        for (px, py), (ux, uy), (tx, ty), z, p, wpx in obs:
            acc2 = (p.get('accuracyM') or 5) ** 2
            d = (z if z is not None else default_d) if sol is None else max(5.0, math.hypot(sol[0] - px, sol[1] - py))
            n = (uy, -ux)                               # across the ray: n . P = n . c
            add(n, n[0] * px + n[1] * py, (d * math.radians(RAY_ERR_DEG)) ** 2 + acc2)
            if z is not None:                           # along the camera axis: t . P = t . c + z
                rel = math.hypot(RANGE_REL_ERR, RANGE_PX_ERR / max(1.0, wpx))
                add((tx, ty), tx * px + ty * py + z, (z * rel) ** 2 + acc2)
        det = a11 * a22 - a12 * a12
        if abs(det) < 1e-12:
            return locate(sign, photos, default_d, gate)
        sol = ((a22 * b1 - a12 * b2) / det, (a11 * b2 - a12 * b1) / det)
    dist = [math.hypot(sol[0] - px, sol[1] - py) for (px, py), *_r in obs]
    in_front = all((sol[0] - px) * tx + (sol[1] - py) * ty > 0 for (px, py), _u, (tx, ty), *_r in obs)
    if not (in_front and lo <= min(dist) and max(dist) <= hi):
        return locate(sign, photos, default_d, gate)
    lat, lon = to_ll(sol[0], sol[1], lat0, lon0)
    tr = a11 + a22
    big = (tr + math.sqrt(max(tr * tr - 4 * det, 0))) / 2 / det
    rays = f', {len(obs)} rays' if len(obs) > 1 else ''
    return lat, lon, {'method': f'ranged from the sign size ({width_m:.2f} m) on {nr} photo{"s" if nr > 1 else ""}{rays}',
                      'uncertaintyM': round(2 * math.sqrt(big), 1), 'sightings': seen}


def _lane_widths(lanes):
    """"lanes": a count (car lanes) or a list of kinds / widths in metres, from the right kerb leftwards."""
    if lanes is None:
        return []
    if isinstance(lanes, (int, float)):
        return [LANE_WIDTH_M['car']] * int(lanes)
    return [float(x) if isinstance(x, (int, float)) else LANE_WIDTH_M.get(str(x).lower(), LANE_WIDTH_M['car'])
            for x in lanes]


def road_at(spec, i):
    """The stretch of places.json "road" that covers photo i, else None."""
    for seg in (spec or {}).get('road') or []:
        if seg.get('fromPhoto', 0) <= i <= seg.get('toPhoto', 10 ** 9):
            return seg
    return None


def layout_lines(seg):
    """Lateral offsets (metres from the rider's track, + = right of travel) where the signs of one stretch stand:
    {'right', 'left', 'leftIs', 'lanes': [centre of lane 1 (rightmost), 2, ...], 'centre'}.

    seg (places.json "road" entry): "lanes" (count or kinds / widths from the right kerb: "moto" 2.75, "car" 3.5),
    "riderLane" (1 = rightmost, default 1), "divider" (true: a median on the left), "medianWidthM" (default 1),
    "oppositeLanes" (no divider: the other direction's lanes, default as many as "lanes"), "signSetbackM" (pole
    outside the kerb, default 1). Measured offsets win: "rightKerbOffsetM", "medianOffsetM" (to the median's kerb),
    "leftKerbOffsetM" (no divider: to the far kerb), all positive metres from the rider."""
    widths = _lane_widths(seg.get('lanes'))
    edges = [0.0]                                    # lane edges, metres from the right kerb
    for w in widths:
        edges.append(edges[-1] + w)
    r = int(seg.get('riderLane') or 1)
    rider = (edges[r - 1] + edges[r]) / 2 if 0 < r <= len(widths) else edges[-1] / 2
    # the rider's distance to the right kerb: measured, else from the lanes
    kerb = float(seg['rightKerbOffsetM']) if seg.get('rightKerbOffsetM') is not None else rider
    setback = float(seg.get('signSetbackM', SIGN_SETBACK_M))
    our_left = edges[-1] - kerb if widths else None  # rider -> left edge of the rider's direction
    out = {'right': kerb + setback, 'lanes': [round(kerb - (a + b) / 2, 2) for a, b in zip(edges, edges[1:])]}
    if seg.get('divider'):
        far = float(seg['medianOffsetM']) if seg.get('medianOffsetM') is not None else our_left
        if far is not None:
            out['left'], out['leftIs'] = -(far + float(seg.get('medianWidthM', MEDIAN_WIDTH_M)) / 2), 'median'
    else:
        if seg.get('leftKerbOffsetM') is not None:
            far = float(seg['leftKerbOffsetM'])
        elif our_left is not None:
            opp = seg.get('oppositeLanes')
            far = our_left + sum(_lane_widths(opp if opp is not None else seg.get('lanes')))
        else:
            far = None
        if far is not None:
            out['left'], out['leftIs'] = -(far + setback), 'left kerb'
    left_edge = (far if seg.get('divider') else our_left) if our_left is not None or seg.get('divider') else None
    if left_edge is not None:
        out['centre'] = round((kerb - left_edge) / 2, 2)  # middle of the rider's carriageway
    return out


def lane_offset(centres, lane):
    """Lateral offset of lane `lane` (1 = rightmost; 2.5 = the line between lanes 2 and 3) from its centres."""
    if lane is None or not centres or not 1 <= float(lane) <= len(centres):
        return None
    k = float(lane)
    a = int(math.floor(k))
    if a == k or a >= len(centres):
        return centres[a - 1]
    return centres[a - 1] + (centres[a] - centres[a - 1]) * (k - a)


def ride_frame(lat, lon, photos):
    """(photo, along, across): the ride photo nearest (lat, lon) with a travel bearing, and the point's position in
    its frame (metres along travel and across, + = right)."""
    near = [p for p in photos if p.get('lat') is not None and p.get('travelBearing') is not None]
    if not near:
        return None, 0.0, 0.0
    c = min(near, key=lambda p: math.hypot(*to_xy(lat, lon, p['lat'], p['lon'])))
    x, y = to_xy(lat, lon, c['lat'], c['lon'])
    tb = math.radians(c['travelBearing'])
    tx, ty = math.sin(tb), math.cos(tb)
    return c, x * tx + y * ty, x * ty - y * tx


def rays_on_line(sign, photos, c, target, line_err=1.0, bearing_err_deg=3.0):
    """(along, uncertaintyM, n): where the sign's sighting rays cross the line `target` metres across (+ = right)
    in the frame of ride photo c, combined by inverse variance; a ray within 8 deg of the line (it meets it far
    and badly) or crossing it behind the camera or beyond 80 m does not count. None when none counts. Error per ray:
    the line's place (line_err m) and the bearing (bearing_err_deg at the crossing) turned into along-road metres."""
    tb = math.radians(c['travelBearing'])
    tx, ty = math.sin(tb), math.cos(tb)
    num = den = 0.0
    n = 0
    for s in sign.get('sightings') or []:
        p = photos[s['photo']]
        if p.get('lat') is None or p.get('cameraBearing') is None:
            continue
        x, y = to_xy(p['lat'], p['lon'], c['lat'], c['lon'])
        pa, pc = x * tx + y * ty, x * ty - y * tx
        b = math.radians(bearing_of(p, s['x']))
        ua, uc = math.sin(b) * tx + math.cos(b) * ty, math.sin(b) * ty - math.cos(b) * tx
        if abs(uc) < math.sin(math.radians(8)):
            continue
        t = (target - pc) / uc
        if not 3 <= t <= 80:
            continue
        sd = math.hypot(line_err, t * math.radians(bearing_err_deg)) * abs(ua / uc)
        num += (pa + t * ua) / sd ** 2
        den += 1 / sd ** 2
        n += 1
    return (num / den, 2 / math.sqrt(den), n) if n else None


def snap_sign(sign, lat, lon, how, photos, spec):
    """Move a located sign across the road (its along-road position kept) onto the line its "side" puts it on, from
    the road layout (places.json "road"): right -> right kerb, left -> median (divider) else left kerb, over -> the
    centre of its "lane" (1 = rightmost; none given: the middle of the carriageway). (lat, lon, how) with the
    estimate before the snap kept in how['unsnapped'] and what was done in how['snap'] and the method; a move larger
    than "maxSnapM" (default 6 m) is not made but flagged. A sign without a range (a rectangle, no width read) also
    gets its along-road position from where its rays cross that line (rays_on_line), when that is surer."""
    side = sign.get('side')
    if not (spec or {}).get('road') or side not in ('left', 'right', 'over') or sign.get('snap') is False:
        return lat, lon, how
    c, along, across = ride_frame(lat, lon, photos)
    if c is None:
        return lat, lon, how
    seg = road_at(spec, c['idx'] if c.get('idx') is not None else sign.get('cover', 0))
    if seg is None:
        return lat, lon, how
    lines = layout_lines(seg)
    if side == 'over':
        target, what = lane_offset(lines['lanes'], sign.get('lane')), f"over lane {sign.get('lane')}"
        if target is None:
            target, what = lines.get('centre'), 'over the middle of the carriageway'
    elif side == 'right':
        target, what = lines['right'], 'the right kerb line'
    else:
        target, what = lines.get('left'), f"the {lines.get('leftIs', 'left')} line"
    if target is None:
        return lat, lon, how
    move = target - across
    how = dict(how)
    how['unsnapped'] = {'lat': round(lat, 6), 'lon': round(lon, 6), 'acrossM': round(across, 1),
                        'method': how.get('method')}
    limit = float(spec.get('maxSnapM') or MAX_SNAP_M)
    rng = f"road layout photos {seg.get('fromPhoto', 0)}-{seg.get('toPhoto', '')}"
    if abs(move) > limit:
        how['snap'] = {'done': False, 'side': side, 'lineM': round(target, 1), 'moveM': round(move, 1),
                       'why': f'{abs(move):.1f} m from {what} (> {limit:g} m): check the side or the position'}
        how['method'] += f'; NOT snapped: {abs(move):.1f} m from {what}, check'
        return lat, lon, how
    tb = math.radians(c['travelBearing'])
    tx, ty = math.sin(tb), math.cos(tb)
    nx, ny = ty, -tx
    how['snap'] = {'done': True, 'side': side, 'lineM': round(target, 1), 'moveM': round(move, 1), 'road': rng}
    how['method'] += f"; snapped {abs(move):.1f} m {'right' if move > 0 else 'left'} onto {what} ({rng})"
    if not str(how.get('unsnapped', {}).get('method', '')).startswith('ranged'):
        # no range: the along-road position from where the sightings' rays cross the line (when that beats it)
        cross = rays_on_line(sign, photos, c, target)
        if cross and cross[1] < (how.get('uncertaintyM') or 1e9):
            how['snap']['alongMovedM'] = round(cross[0] - along, 1)
            along = cross[0]
            how['uncertaintyM'] = round(cross[1], 1)
            how['method'] += f'; along the road where {cross[2]} ray{"s cross" if cross[2] > 1 else " crosses"} that line'
    la, lo = to_ll(along * tx + target * nx, along * ty + target * ny, c['lat'], c['lon'])
    return la, lo, how


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


def forward_photos(photos):
    """The photos as a forward camera saw them: bearing = travel bearing (the camera looks along the road), for
    locating signs. A photo without a travel bearing keeps its own camera bearing."""
    out = []
    for p in photos:
        q = dict(p)
        if p.get('travelBearing') is not None:
            q['cameraBearing'] = p['travelBearing']
        if q.get('cameraBearing') is not None:
            q['cameraBearingSource'] = 'forward'
        out.append(q)
    return out


def _read(value, pl):
    return {'value': value, 'photo': pl.get('cover'), 'sure': pl.get('confidence') or 'high'}


def address_item(a, photos, ride_street=None):
    """An entry of places.json "addresses" as a place-like item (layer "address"). The name's street is the plate's,
    else the road the plate was photographed from (result.py then fills the street field from it, source "ride")."""
    a = dict(a)
    lo, hi = a.get('photos') or [a['cover'], a['cover']]
    road, _how = result.pick_road(photos[a['cover']].get('roadName'),
                                  [photos[i].get('roadName') for i in range(lo, hi + 1)], ride_street)
    fields = {'houseNumber': _read(str(a['houseNumber']), a)}
    if a.get('street'):
        fields['street'] = _read(a['street'], a)
    if a.get('unit'):
        fields['other'] = _read(a['unit'], a)
    a.update({'layer': 'address', 'name': ' '.join(x for x in (str(a['houseNumber']), a.get('street') or road) if x),
              'category': 'address', 'photos': [lo, hi], 'fields': fields})
    return a


def sign_item(s):
    """An entry of places.json "signs" as a place-like item (layer "sign"): name = its label, the code a field."""
    s = dict(s)
    code = (s.get('code') or '').strip()
    fields = {}
    if code:
        fields['signCode'] = _read(code, s)
    if s.get('text') or s.get('signText'):
        fields['signText'] = _read(s.get('text') or s.get('signText'), s)
    if s.get('extra'):
        fields['other'] = _read(s['extra'], s)
    s.update({'layer': 'sign', 'name': s.get('label') or code or 'traffic sign', 'category': 'traffic_sign',
              'photos': s.get('photos') or [s['cover'], s['cover']], 'fields': fields, 'code': code or None,
              'group': s.get('group') or SIGN_GROUPS.get(code[:1].upper()), 'signText': None})
    if (s.get('agentPin') or {}).get('snap'):
        s.pop('agentPin')   # no shopfront line to snap a sign onto
    return s


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
    # stopped (no travel direction yet): a photo without a camera direction borrows its nearest neighbour's
    known = [i for i, p in enumerate(photos) if p.get('cameraBearing') is not None]
    for i, p in enumerate(photos):
        if p.get('cameraBearing') is None and known:
            j = min(known, key=lambda k: abs(k - i))
            p['cameraBearing'] = photos[j]['cameraBearing']
            p['cameraBearingSource'] = (photos[j].get('cameraBearingSource') or 'assumed') + '+neighbour'
    default_d = float(spec.get('defaultFacadeDistanceM') or 30)
    best_n = int(spec.get('bestPhotos') or best_n)
    global BEARING_OFFSET, BEARING_ALL
    BEARING_OFFSET, bearing_note = bearing_correction(photos, spec)
    BEARING_ALL = spec.get('bearingOffsetDeg') is not None
    print('camera bearing:', bearing_note)
    door_pos = doors(door_folder)
    door_session = None
    if door_folder and os.path.isfile(os.path.join(door_folder, 'session.json')):
        ds = json.load(open(os.path.join(door_folder, 'session.json'), encoding='utf-8'))
        door_session = f"place capture {ds.get('id')} ({ds.get('name')})"
    # folder -> (name, googleMaps, website) from the last run, so a rebuild needs no new lookup; the website's
    # images (WEB_*) wait in a temporary folder while <out> is emptied
    previous, web_keep = {}, tempfile.mkdtemp(prefix='poi-web-')
    if os.path.isdir(out):
        for d in os.listdir(out):
            f = os.path.join(out, d, 'place.json')
            if os.path.isfile(f):
                m = json.load(open(f, encoding='utf-8'))
                if m.get('googleMaps') or m.get('website'):
                    previous[d] = (m['place']['name'], m.get('googleMaps'), m.get('website'))
                for x in (m.get('website') or {}).get('images') or []:
                    src = os.path.join(out, d, x.get('file') or '')
                    if x.get('file') and os.path.isfile(src):
                        os.makedirs(os.path.join(web_keep, d), exist_ok=True)
                        shutil.copy2(src, os.path.join(web_keep, d, x['file']))
    if os.path.exists(out):
        for d in os.listdir(out):   # keep nothing from an earlier run but the folder itself
            q = os.path.join(out, d)
            shutil.rmtree(q) if os.path.isdir(q) else os.remove(q)
    os.makedirs(out, exist_ok=True)
    target = session.get('target') or 'shops'
    sign_d = float(spec.get('defaultSignDistanceM') or 15)
    fwd = forward_photos(photos) if spec.get('signs') else None
    items = ([dict(pl, layer='place') for pl in spec.get('places') or []]
             + [address_item(x, photos, spec.get('street')) for x in spec.get('addresses') or []]
             + [sign_item(x) for x in spec.get('signs') or []])
    for n, pl in enumerate(items, 1):
        a, b = pl['photos']
        layer = pl['layer']
        if layer == 'sign':   # seen ahead, along the road: anywhere from a few metres to a block away
            lat, lon, how = locate_sign(pl, fwd, sign_d, gate=(3, 80), spec=spec)
            lat, lon, how = snap_sign(pl, lat, lon, how, fwd, spec)
        else:
            lat, lon, how = locate(pl, photos, default_d)
        prefix = {'address': 'addr_', 'sign': 'sign_'}.get(layer, '')
        d = os.path.join(out, f"{n:02d}_{prefix}{safe(pl['name'])}")
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
        old_dir = os.path.basename(d)
        old = previous.get(old_dir)
        if not old or old[0] != pl['name']:   # renumbered: find it by name
            old_dir, old = next(((k, v) for k, v in previous.items() if v[0] == pl['name']), (None, None))
        if layer != 'place':
            old = None   # nothing is looked up for an address or a sign
        if old and old[2]:
            for x in old[2].get('images') or []:
                kept = os.path.join(web_keep, old_dir, x.get('file') or '')
                if x.get('file') and os.path.isfile(kept):
                    shutil.copy2(kept, os.path.join(d, x['file']))
        # the road it was photographed from (phone's roadName), not the ride's street
        road, road_from = result.pick_road(cover.get('roadName'), [photos[i].get('roadName') for i in range(a, b + 1)],
                                           spec.get('street'))
        extra = {}
        if layer == 'address':
            extra = {'placeRef': pl.get('placeRef') or None}
        elif layer == 'sign':
            # facing: the way a driver who sees it drives = the travel bearing when it was photographed
            tb = cover.get('travelBearing') if cover.get('travelBearing') is not None else cover.get('cameraBearing')
            extra = {'sign': {'code': pl.get('code'), 'group': pl.get('group'),
                              'facingDeg': None if tb is None else round(tb % 360, 1), 'side': pl.get('side')}}
        meta = {
            'place': {'layer': layer, **extra,
                      'name': pl['name'], 'category': pl.get('category'), 'signText': pl.get('signText'),
                      'confidence': pl.get('confidence'), 'note': pl.get('note') or None,
                      'status': pl.get('status') or 'open',
                      'readBy': 'vision model, from the photos in this folder',
                      'street': road, 'streetFrom': road_from,
                      'address': None, 'phone': None, 'openingHours': None, 'website': None,
                      'position': {'lat': round(lat, 6), 'lon': round(lon, 6), **how,
                                   'cameraBearing': 'forward: the travel bearing' if layer == 'sign' else bearing_note,
                                   'googleMaps': gmaps(lat, lon),
                                   'coverCameraOnGoogleMaps': gmaps(cover['lat'], cover['lon'])}},
            'review': {'status': 'todo', 'comment': None},
            'fields': fields_of(pl),
            'pins': {'agent': agent_pin(pl, lat, lon, photos, default_d)},
            'googleMaps': old[1] if old and old[0] == pl['name'] else None,
            **({'website': old[2]} if old and old[0] == pl['name'] and old[2] else {}),
            'groundTruth': ground_truth(pl, lat, lon, door_pos, door_session),
            'session': {'id': session.get('id'), 'name': session.get('name'), 'kind': session.get('kind'),
                        'target': target},
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
    shutil.rmtree(web_keep, ignore_errors=True)
    json.dump(rem, open(os.path.join(out, 'removals.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    # the ride for the map: every photo's camera position, in order
    shots = [[p['idx'], round(p['lat'], 6), round(p['lon'], 6),
              None if p.get('cameraBearing') is None else round(p['cameraBearing'], 1)] for p in photos if p.get('lat') is not None]
    json.dump({'session': {**{k: session.get(k) for k in ('id', 'name', 'kind', 'holding', 'startedAt', 'endedAt')},
                           'target': target},
               'street': spec.get('street'), 'track': [[x[1], x[2]] for x in shots], 'photos': shots},
              open(os.path.join(out, 'sweep.json'), 'w', encoding='utf-8'), ensure_ascii=False)
    write_indexes(out, session)


def csv_cell(v):
    """Text starting with = + - @ (or a tab / return) gets a leading ' so a spreadsheet does not run it as a formula."""
    return "'" + v if isinstance(v, str) and v[:1] in ('=', '+', '-', '@', '\t', '\r') else v


def sign_code(m):
    """A sign's code: the field (the reader may have corrected it), else the sign block's; None for other layers."""
    if layer_of(m) != 'sign':
        return None
    return ((m.get('fields') or {}).get('signCode') or {}).get('value') or (m['place'].get('sign') or {}).get('code')


def write_indexes(out, session=None):
    rows = [(d, json.load(open(os.path.join(out, d, 'place.json'), encoding='utf-8'))) for d in place_folders(out)]
    if session is None and rows:
        session = rows[0][1]['session']
    g = lambda m, k: (m.get('googleMaps') or {}).get(k)
    # places.csv (Excel-friendly), places.geojson, README.txt, index.html
    with open(os.path.join(out, 'places.csv'), 'w', newline='', encoding='utf-8-sig') as fh:
        w = csv.writer(fh)
        w.writerow(['#', 'layer', 'name', 'category', 'signCode', 'confidence', 'photos', 'lat', 'lon', 'position',
                    'review link',
                    'google name', 'google address', 'google phone', 'google hours', 'google website', 'google link',
                    'google distance m', 'folder'])
        for d, m in rows:
            p = m['place']; pos = p['position']
            w.writerow([csv_cell(x) for x in [folder_no(d), layer_of(m), p['name'], p['category'], sign_code(m),
                        p['confidence'], len(m['photos']), pos['lat'], pos['lon'],
                        pos['method'], pos['googleMaps'], g(m, 'name'), g(m, 'address'), g(m, 'phone'),
                        ' | '.join(g(m, 'openingHours') or []), g(m, 'website'), g(m, 'url'), g(m, 'distanceM'), d]])
    feats = [{'type': 'Feature', 'geometry': {'type': 'Point', 'coordinates': [m['place']['position']['lon'], m['place']['position']['lat']]},
              'properties': {'folder': d, 'layer': layer_of(m), 'name': m['place']['name'],
                             'category': m['place']['category'], 'confidence': m['place']['confidence'],
                             'googleName': g(m, 'name'),
                             **({'signCode': sign_code(m)} if layer_of(m) == 'sign' else {})}} for d, m in rows]
    json.dump({'type': 'FeatureCollection', 'features': feats}, open(os.path.join(out, 'places.geojson'), 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    lines = [f"{folder_no(d):>2}  {m['place']['name']:<38} {len(m['photos']):>2} photos  {m['place']['position']['googleMaps']}"
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
        if layer_of(m) != 'place':   # never looked up on Google: say what it is instead
            gblock = f'<p class="muted">{e(layer_of(m))}' + (f' · {e(str(sign_code(m)))}' if sign_code(m) else '') + '</p>'
        tr = m.get('groundTruth')
        tline = (f'<p class="t">Door captured on site ({e(tr["label"])}): <b>{tr["errorM"]} m</b> from the estimate</p>'
                 if tr else '')
        cards.append(f'''<section class="place" id="{e(d)}"><h2 title="show on the map">{e(folder_no(d))} · {e(p["name"])} <span class="c {e(p.get("confidence") or "")}">{e(p.get("confidence") or "")}</span></h2>
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
        write_indexes(arg_path(sys.argv[2]))
    else:
        n = int(sys.argv[sys.argv.index('--best') + 1]) if '--best' in sys.argv else 2
        dz = arg_path(sys.argv[sys.argv.index('--doors') + 1]) if '--doors' in sys.argv else None
        build(arg_path(sys.argv[1]), arg_path(sys.argv[2]), best_n=n, keep_all='--keep-all' in sys.argv, door_folder=dz)
