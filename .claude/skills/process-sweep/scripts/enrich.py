"""Look each place up on Google Maps and record what Google knows (for review), then rewrite the indexes.

usage:
  python enrich.py <outdir>                     Google Places API (New) Text Search; needs GOOGLE_MAPS_API_KEY
  python enrich.py <outdir> --from manual.json  merge lookups done by hand / web search:
                                                {"06_Starbucks": {"name": ..., "address": ..., "phone": ...,
                                                 "openingHours": [...], "website": ..., "url": ..., "lat": ..,
                                                 "lon": .., "source": "web search: <url>"}, ...}
  add --force to look up places that already have a result

Each place.json gets a "googleMaps" block (kept apart from the place's own fields, see SKILL.md on terms):
  {name, address, phone, openingHours[], website, url, types, businessStatus, rating, lat, lon, distanceM,
   nameMatch (0..1), placeId, source, fetchedAt, status}
"""
import json, math, os, re, sys, unicodedata, urllib.request
from datetime import datetime, timezone

sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build  # noqa: E402

FIELDS = ','.join('places.' + f for f in [
    'id', 'displayName', 'formattedAddress', 'location', 'nationalPhoneNumber', 'internationalPhoneNumber',
    'regularOpeningHours.weekdayDescriptions', 'websiteUri', 'googleMapsUri', 'types', 'businessStatus', 'rating'])


def fold(s):
    s = unicodedata.normalize('NFD', (s or '').replace('đ', 'd').replace('Đ', 'D'))
    return re.sub(r'[^a-z0-9]+', ' ', ''.join(c for c in s if not unicodedata.combining(c)).lower()).strip()


def similarity(a, b):
    ta, tb = set(fold(a).split()), set(fold(b).split())
    return round(len(ta & tb) / len(ta | tb), 2) if ta and tb else 0.0


def dist_m(lat1, lon1, lat2, lon2):
    return math.hypot((lon2 - lon1) * 111320 * math.cos(math.radians(lat1)), (lat2 - lat1) * 110540)


def search(key, query, lat, lon, radius=250):
    body = json.dumps({'textQuery': query, 'languageCode': 'vi', 'maxResultCount': 5,
                       'locationBias': {'circle': {'center': {'latitude': lat, 'longitude': lon}, 'radius': radius}}}).encode()
    req = urllib.request.Request('https://places.googleapis.com/v1/places:searchText', data=body, method='POST', headers={
        'Content-Type': 'application/json', 'X-Goog-Api-Key': key, 'X-Goog-FieldMask': FIELDS})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.load(r).get('places', [])


def best(places, name, lat, lon):
    scored = []
    for p in places:
        loc = p.get('location') or {}
        if 'latitude' not in loc:
            continue
        d = dist_m(lat, lon, loc['latitude'], loc['longitude'])
        s = similarity(name, (p.get('displayName') or {}).get('text'))
        scored.append((s - d / 500, s, d, p))   # name first, then nearness
    if not scored:
        return None
    _, s, d, p = max(scored, key=lambda t: t[0])
    if s < 0.3 or d > 300:
        return None
    loc = p['location']
    return {'name': (p.get('displayName') or {}).get('text'), 'address': p.get('formattedAddress'),
            'phone': p.get('nationalPhoneNumber') or p.get('internationalPhoneNumber'),
            'openingHours': (p.get('regularOpeningHours') or {}).get('weekdayDescriptions'),
            'website': p.get('websiteUri'), 'url': p.get('googleMapsUri'), 'types': p.get('types'),
            'businessStatus': p.get('businessStatus'), 'rating': p.get('rating'),
            'lat': loc['latitude'], 'lon': loc['longitude'], 'distanceM': round(d), 'nameMatch': s, 'placeId': p.get('id')}


def main():
    out = sys.argv[1]
    force = '--force' in sys.argv
    manual = None
    if '--from' in sys.argv:
        manual = json.load(open(sys.argv[sys.argv.index('--from') + 1], encoding='utf-8'))
    key = os.environ.get('GOOGLE_MAPS_API_KEY')
    if manual is None and not key:
        sys.exit('GOOGLE_MAPS_API_KEY is not set: look the places up by web search and pass them with --from (see SKILL.md)')
    now = datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
    for d in sorted(os.listdir(out)):
        f = os.path.join(out, d, 'place.json')
        if not os.path.isfile(f):
            continue
        m = json.load(open(f, encoding='utf-8'))
        if m.get('googleMaps') and m['googleMaps'].get('name') and not force and manual is None:
            continue
        p = m['place']; pos = p['position']
        if manual is not None:
            g = manual.get(d)
            if g is None:
                continue
            if g.get('lat') is not None and g.get('lon') is not None:
                g.setdefault('distanceM', round(dist_m(pos['lat'], pos['lon'], g['lat'], g['lon'])))
            if g.get('name'):
                g.setdefault('nameMatch', similarity(p['name'], g['name']))
            g.setdefault('source', 'web search')
            g.update({'fetchedAt': now, 'status': g.get('status') or ('found' if g.get('name') else 'not found')})
        else:
            # the readable part of the name: drop the "(…)" notes written while reading
            name = re.sub(r'\(.*?\)|…', ' ', p['name']).strip()
            query = f"{name} {p.get('street') or ''}".strip()
            try:
                g = best(search(key, query, pos['lat'], pos['lon']), name, pos['lat'], pos['lon'])
                g = ({**g, 'source': 'Google Places API (New) text search', 'query': query, 'fetchedAt': now, 'status': 'found'}
                     if g else {'status': 'not found', 'query': query, 'fetchedAt': now})
            except Exception as e:  # noqa: BLE001  (quota, network: keep going, say so in the file)
                g = {'status': f'error: {e}', 'query': query, 'fetchedAt': now}
        m['googleMaps'] = g
        json.dump(m, open(f, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
        print(f"{d}: {g.get('status')} {g.get('name') or ''} {('(' + str(g.get('distanceM')) + ' m)') if g.get('distanceM') is not None else ''}")
    build.write_indexes(out)


if __name__ == '__main__':
    main()
