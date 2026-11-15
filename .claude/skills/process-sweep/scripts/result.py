"""The result bundle the web app imports: <out>/result.json next to the place folders (docs/pipeline.md).

    python result.py <out>      (re)write <out>/result.json from the place folders; build.write_indexes calls it

result.json (schema "poi-result/1"):
  session   id, name, kind (sweep | poi), holding, startedAt, street
  track     [[lat, lon], ...] the ride (sweeps), shots [[photoIndex, lat, lon, cameraBearing], ...]
  places    one per place folder:
            id, folder, name, category, status (open | closed | for_rent | under_construction | gone | unknown),
            confidence, fields {name|phone|houseNumber|street|openingHours|website|signText|other:
                                {value, photo, sure}}  (read from the photos; photo = sweep index or photo id),
            photos [{file, index, cover, why}],
            pins {algorithm | contributor: {lat, lon, method, uncertaintyM, sightings},
                  google: {lat, lon, name, distanceM, url} | null,      (for review only: never published)
                  agent: {lat, lon, why} | null},
            google {the review block}, review {status: todo}
  tasks     [{type: visit | verify_removal | removal, place (id) | null, name, lat, lon, why, photo}]
            visit: new to Google, changed, or partly read -> Place capture; removal: closed / for rent / gone seen
            on the photos; verify_removal: gone but the facade was not clearly in view
"""
import json, os, sys
from datetime import datetime, timezone

STATUS_GONE = ('closed', 'for_rent', 'gone')


def _google_pin(g):
    if not g or g.get('lat') is None:
        return None
    return {'lat': g['lat'], 'lon': g['lon'], 'name': g.get('name'), 'distanceM': g.get('distanceM'),
            'nameMatch': g.get('nameMatch'), 'url': g.get('url')}


# ---- merge with Google Maps (the project's decision, 2026-11-15) ----
# A matched Google place (name match >= 0.5, within 100 m) is compared field by field with what the photos show:
#   same -> the photo value, google = "match";   different -> the photo value (newer), google = "differs" + its value;
#   missing on the photos -> Google's value, source = "google".
# Google-sourced values are marked and never go into the OpenStreetMap export (OSM does not accept Google data).
import re, unicodedata

def _fold(s):
    s = unicodedata.normalize('NFD', (s or '').lower().replace('đ', 'd'))
    return ''.join(c for c in s if unicodedata.category(c) != 'Mn')

def _digits(p):
    d = re.sub(r'\D', '', p or '')
    return '0' + d[2:] if d.startswith('84') else d

def _google_fields(g):
    out = {}
    if g.get('phone'):
        out['phone'] = _digits(g['phone'])
    addr = g.get('address') or ''
    m = re.match(r'\s*(\d+[A-Za-z]?(?:[-/]\d+[A-Za-z]?)*)\s+([^,]+)', addr)
    if m:
        out['houseNumber'] = m.group(1)
        out['street'] = m.group(2).strip()
    if g.get('openingHours'):
        out['openingHours'] = '; '.join(g['openingHours'])
    if g.get('website'):
        out['website'] = re.sub(r'^https?://(www\.)?', '', g['website']).rstrip('/')
    return out

def _same(k, photo, google):
    if k == 'phone':
        ps = {_digits(x) for x in re.split(r'[,;/]', photo) if _digits(x)}
        return google in ps
    if k == 'houseNumber':
        first = re.match(r'\s*([\dA-Za-z/-]+)', photo or '')
        return bool(first) and _fold(first.group(1)) == _fold(google)
    if k == 'street':
        a, b = _fold(photo), _fold(google)
        return b in a or a.split(',')[0].strip() in b
    if k == 'website':
        dom = lambda x: re.sub(r'^https?://(www\.)?', '', _fold(x)).split('/')[0]
        return dom(photo) == dom(google)
    if k == 'openingHours':
        times = lambda x: set(re.findall(r'\d{1,2}[:h]\d{2}', x.replace('h', ':')))
        t1 = {t.zfill(5) for t in times(photo)}; t2 = {t.zfill(5) for t in times(google)}
        return bool(t1 & t2)
    return _fold(photo) == _fold(google)

def _unclear(v):
    """A reading the agent was not sure of: low sureness, or parts it could not read ("…", "?")."""
    val = str(v.get('value') or '')
    return v.get('sure') == 'low' or '…' in val or '...' in val or '?' in val


def _compatible(k, photo, other):
    """An unclear photo reading that another source can clarify: what was read agrees with it."""
    if k == 'phone':
        chunks = [c for c in re.split(r'\D+', photo) if len(c) >= 3]
        return any(c in _digits(other) for c in chunks)
    a = re.sub(r'(…|\.\.\.|\?)', ' ', _fold(photo)).split()
    b = _fold(other)
    if not a:
        return False
    import difflib
    return sum(1 for w in a if w in b) / len(a) >= 0.5 or difflib.SequenceMatcher(None, ' '.join(a), b).ratio() >= 0.6


def merge_source(fields, values, source):
    """Compare / clarify / fill fields with another source's values ({field: value}), each field marked:
    match / differs (the photo wins: newer); an unclear photo reading that agrees with the source is clarified by it
    (source = <source>, photoValue kept); a field the photos lack is filled (source = <source>)."""
    out = {k: dict(v) for k, v in fields.items()}
    for k, gv in values.items():
        if not gv:
            continue
        cur = out.get(k)
        if cur and cur.get('value') and cur.get('source') in (None, 'photo'):
            val = str(cur['value'])
            if _same(k, val, gv):
                cur[source] = 'match'
            elif _unclear(cur) and _compatible(k, val, gv):
                out[k] = {'value': gv, 'source': source, 'sure': 'medium', 'clarified': True, 'photoValue': val,
                          'photo': cur.get('photo')}
            else:
                cur[source] = 'differs'
                cur[source + 'Value'] = gv
        elif not cur or not cur.get('value'):
            out[k] = {'value': gv, 'source': source, 'sure': 'medium'}
    return out


def merge_google(fields, g):
    """fields (from the photos) merged with a matched Google place, each field marked as above."""
    if not g or not g.get('name') or (g.get('nameMatch') or 0) < 0.5 or (g.get('distanceM') or 999) > 100:
        return fields
    gv = _google_fields(g)
    out = merge_source(fields, gv, 'google')
    if out.get('openingHours', {}).get('source') == 'google' and len(g.get('openingHours') or []) <= 1:
        out['openingHours'].update({'sure': 'low', 'note': 'Google lists only one day'})
    return out


def _web_fields(w, fields, g):
    """Fields from the place's own website (enrich_web.py), only when its address is this place's: a chain's site
    gives the head office's address and hotline, not this branch's (then only its description and images are kept)."""
    if not w or w.get('status') != 'ok':
        return {}
    f = w.get('fields') or {}
    addr = _fold(w.get('address') or '')
    known = _google_fields(g or {}) if g else {}
    street = (fields.get('street') or {}).get('value') or known.get('street') or ''
    house = (fields.get('houseNumber') or {}).get('value') or known.get('houseNumber') or ''
    house = re.match(r'\s*([\dA-Za-z/-]+)', house).group(1) if re.match(r'\s*([\dA-Za-z/-]+)', house) else ''
    here = bool(addr) and ((street and _fold(street).split(',')[0].strip() in addr) or (house and f.get('houseNumber') == house))
    if not here:
        return {}
    return {k: f.get(k) for k in ('phone', 'houseNumber', 'street', 'openingHours') if f.get(k)}


def _site_is_this_place(m):
    """The website is this place's (its images are shown): its address is this place's, or its domain carries the
    place's name (Google sometimes lists an unrelated site)."""
    w = m.get('website') or {}
    if w.get('status') != 'ok':
        return False
    if _web_fields(w, m.get('fields') or {}, m.get('googleMaps')):
        return True
    import urllib.parse
    host = re.sub(r'[^a-z0-9]', '', _fold(urllib.parse.urlsplit(w.get('url') or '').netloc).replace('www.', ''))
    words = [x for x in re.findall(r'[a-z0-9]+', _fold(m['place']['name'])) if len(x) >= 3]
    return bool(words) and any(x in host for x in words)


def place_entry(folder, m, session_id):
    p, pos = m['place'], m['place']['position']
    pins = dict(m.get('pins') or {})
    first = 'contributor' if (m.get('session') or {}).get('kind') == 'poi' else 'algorithm'
    pins.setdefault(first, {'lat': pos['lat'], 'lon': pos['lon'], 'method': pos.get('method'),
                            'uncertaintyM': pos.get('uncertaintyM'), 'sightings': pos.get('sightings') or []})
    pins['google'] = _google_pin(m.get('googleMaps'))
    pins.setdefault('agent', None)
    return {
        'id': f'{session_id}-{folder[:2]}', 'folder': folder, 'name': p['name'], 'category': p.get('category'),
        'status': p.get('status') or 'open', 'confidence': p.get('confidence'),
        'fields': merge_google(merge_source(m.get('fields') or {}, _web_fields(m.get('website'), m.get('fields') or {},
                                                                              m.get('googleMaps')), 'website'),
                               m.get('googleMaps')), 'note': p.get('note'),
        'website': {k: (m.get('website') or {}).get(k) for k in ('status', 'url', 'title', 'description', 'address')}
                   if m.get('website') else None,
        'photos': [{'file': f"{folder}/{x['file']}", 'index': x.get('sweepIndex'), 'cover': x.get('cover'),
                    'why': x.get('why')} for x in m.get('photos') or []]
                  + [{'file': f"{folder}/{x['file']}", 'index': None, 'cover': False, 'why': 'website',
                      'source': 'website', 'url': x.get('url')} for x in (m.get('website') or {}).get('images') or []
                     if _site_is_this_place(m)],
        'pins': pins, 'google': m.get('googleMaps'), 'review': m.get('review') or {'status': 'todo'},
        'groundTruth': m.get('groundTruth'),
    }


def tasks_for(places, removals):
    out = []
    for p in places:
        g = p.get('google') or {}
        pin = p['pins'].get('agent') or p['pins'].get('algorithm') or p['pins'].get('contributor')
        cover = next((x['file'] for x in p['photos'] if x.get('cover')), None)
        why = []
        if p['status'] in STATUS_GONE:
            out.append({'type': 'removal', 'place': p['id'], 'name': p['name'], 'lat': pin['lat'], 'lon': pin['lon'],
                        'why': f"seen {p['status'].replace('_', ' ')} on the photos", 'photo': cover})
            continue
        if g and not g.get('name') and g.get('status'):
            why.append('not on Google Maps nearby')
        if g.get('businessStatus') and g['businessStatus'] != 'OPERATIONAL':
            why.append(f"Google says {g['businessStatus'].lower().replace('_', ' ')}")
        if p.get('confidence') == 'low':
            why.append('name only partly read')
        if why:
            out.append({'type': 'visit', 'place': p['id'], 'name': p['name'], 'lat': pin['lat'], 'lon': pin['lon'],
                        'why': '; '.join(why), 'photo': cover})
    for r in removals:
        t = 'removal' if r.get('inView', True) and r.get('status') in STATUS_GONE else 'verify_removal'
        out.append({'type': t, 'place': None, 'name': r.get('name'), 'lat': r.get('lat'), 'lon': r.get('lon'),
                    'why': r.get('why') or r.get('status'), 'photo': r.get('photo')})
    return out


def write(out):
    folders = sorted(d for d in os.listdir(out) if os.path.isfile(os.path.join(out, d, 'place.json')))
    if not folders:
        return None
    metas = [(d, json.load(open(os.path.join(out, d, 'place.json'), encoding='utf-8'))) for d in folders]
    sw = json.load(open(os.path.join(out, 'sweep.json'), encoding='utf-8')) if os.path.isfile(os.path.join(out, 'sweep.json')) else {}
    ses = dict(sw.get('session') or metas[0][1].get('session') or {})
    ses['street'] = sw.get('street')
    removals = json.load(open(os.path.join(out, 'removals.json'), encoding='utf-8')) if os.path.isfile(os.path.join(out, 'removals.json')) else []
    places = [place_entry(d, m, ses.get('id')) for d, m in metas]
    res = {'schema': 'poi-result/1', 'generatedAt': datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
           'session': ses, 'track': sw.get('track') or [], 'shots': sw.get('photos') or [],
           'places': places, 'tasks': tasks_for(places, removals)}
    f = os.path.join(out, 'result.json')
    json.dump(res, open(f, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    return f


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    print(write(sys.argv[1]))
