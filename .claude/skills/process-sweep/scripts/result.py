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
        'fields': m.get('fields') or {}, 'note': p.get('note'),
        'photos': [{'file': f"{folder}/{x['file']}", 'index': x.get('sweepIndex'), 'cover': x.get('cover'),
                    'why': x.get('why')} for x in m.get('photos') or []],
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
